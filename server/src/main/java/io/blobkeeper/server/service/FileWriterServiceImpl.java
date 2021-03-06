package io.blobkeeper.server.service;

/*
 * Copyright (C) 2015-2016 by Denis M. Gabaydulin
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.blobkeeper.cluster.configuration.ClusterPropertiesConfiguration;
import io.blobkeeper.cluster.service.ClusterMembershipService;
import io.blobkeeper.cluster.service.CompactionService;
import io.blobkeeper.cluster.service.RepairService;
import io.blobkeeper.cluster.service.ReplicationClientService;
import io.blobkeeper.file.domain.Disk;
import io.blobkeeper.file.domain.ReplicationFile;
import io.blobkeeper.file.domain.StorageFile;
import io.blobkeeper.file.service.DiskService;
import io.blobkeeper.file.service.FileStorage;
import io.blobkeeper.file.service.ReplicationQueue;
import io.blobkeeper.file.service.WriterTaskQueue;
import io.blobkeeper.index.domain.IndexTempElt;
import io.blobkeeper.index.service.IndexService;
import io.blobkeeper.server.configuration.ServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.FIVE_HUNDRED_MILLISECONDS;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Singleton
public class FileWriterServiceImpl implements FileWriterService {
    private static final Logger log = LoggerFactory.getLogger(FileWriterServiceImpl.class);

    @Inject
    private WriterTaskQueue writerTaskQueue;

    @Inject
    private FileStorage fileStorage;

    @Inject
    private ServerConfiguration configuration;

    @Inject
    private DiskService diskService;

    @Inject
    private ReplicationQueue replicationQueue;

    @Inject
    private RepairService repairService;

    @Inject
    private ReplicationClientService replicationClientService;

    @Inject
    private ClusterMembershipService clusterMembershipService;

    @Inject
    private CompactionService compactionService;

    @Inject
    private ClusterPropertiesConfiguration clusterConfiguration;

    @Inject
    private IndexService indexService;

    private Map<Integer, ScheduledFuture<?>> disksToWriters = new ConcurrentHashMap<>();

    private final ScheduledExecutorService writer = newScheduledThreadPool(
            16,
            new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("BlobFileWriter-%d")
                    .build()
    );

    @Override
    public void start() {
        fileStorage.start();

        List<Integer> disks = diskService.getDisks();
        checkArgument(disks.size() > 0, "No disks were found for writer!");

        disks.forEach(this::addDiskWriter);

        addReplicationWriter();

        if (clusterConfiguration.isMaster()) {
            compactionService.start();
        }

        this.restore();
    }

    @Override
    public void stop() {
        // wait for write task
        await().forever().pollInterval(FIVE_HUNDRED_MILLISECONDS).until(
                () -> {
                    log.trace("Waiting for writer");
                    return writerTaskQueue.isEmpty();
                });

        // wait for replication task
        await().forever().pollInterval(FIVE_HUNDRED_MILLISECONDS).until(
                () -> {
                    log.trace("Waiting for replication writer");
                    return replicationQueue.isEmpty();
                });

        // wait for writers
        try {
            sleep(2000);
        } catch (InterruptedException e) {
            log.error("Thread interrupted while stop was in progress", e);
        }

        if (clusterConfiguration.isMaster()) {
            compactionService.stop();
        }

        disksToWriters.values().forEach(
                writerFuture -> writerFuture.cancel(false)
        );

        fileStorage.stop();
    }

    @Override
    public void restore() {
        log.info("Restore files is started");
        List<IndexTempElt> elts = indexService.getTempIndexList(1024);

        elts.parallelStream().forEach(this::restoreFile);

        log.info("Restore files are scheduled");
    }

    @Override
    public synchronized void refresh() {
        diskService.refresh();

        List<Integer> disks = diskService.getDisks();

        // TODO: remove failed disks

        disks.stream()
                .filter(disk -> !disksToWriters.containsKey(disk))
                .forEach(disk -> {
                    addDiskWriter(disk);
                    repairService.repair(disk, true);
                });
    }

    private void addDiskWriter(int disk) {
        WriterTask task = new WriterTask(disk);
        disksToWriters.put(disk, writer.schedule(task, configuration.getWriterTaskStartDelay(), MILLISECONDS));
    }

    private void addReplicationWriter() {
        writer.schedule(new ReplicationWriterTask(), configuration.getWriterTaskStartDelay(), MILLISECONDS);
    }

    private void restoreFile(IndexTempElt indexElt) {
        log.info("Restore file {}", indexElt);

        StorageFile storageFile = null;
        int disk = -1;
        try {
            storageFile = new StorageFile.StorageFileBuilder()
                    .file(new java.io.File(indexElt.getFile()))
                    .id(indexElt.getId())
                    .type(indexElt.getType())
                    .metadata(indexElt.getMetadata())
                    .build();

            writerTaskQueue.offer(storageFile);
        } catch (Exception e) {
            log.error("Can't add file {} to the disk {}", storageFile, disk, e);
        }
    }


    // Only one thread has an access to the disk for writing.
    private class WriterTask implements Runnable {
        private final int disk;

        WriterTask(int disk) {
            this.disk = disk;
        }

        public void run() {
            log.info("Writer task started");

            while (true) {
                long writeTimeStarted = 0;
                try {
                    Disk disk = diskService.get(this.disk).orElse(null);

                    if (disk == null || !disk.isWritable()) {
                        // TODO: exit, or wait for a compaction?
                        // TODO: make a progressive time sleeping strategy
                        Thread.sleep(1000L);
                        continue;
                    }

                    StorageFile storageFile = writerTaskQueue.take();
                    checkArgument(clusterMembershipService.isMaster(), "Only master node accepts files!");

                    log.trace("File writing started");

                    writeTimeStarted = currentTimeMillis();

                    if (storageFile.isCompaction()) {
                        fileStorage.copyFile(this.disk, storageFile);
                    } else {
                        ReplicationFile file = fileStorage.addFile(this.disk, storageFile);
                        replicationClientService.replicate(file);
                    }
                } catch (Throwable t) {
                    log.error("Can't write file to the storage", t);
                    // TODO: push back a failed to add storage file?
                } finally {
                    log.trace("File writing finished {}", currentTimeMillis() - writeTimeStarted);
                }
            }
        }
    }

    private class ReplicationWriterTask implements Runnable {
        @Override
        public void run() {
            log.info("Replication writer task started");

            while (true) {
                long writeTimeStarted = 0;
                try {
                    ReplicationFile replicationFile = replicationQueue.take();

                    log.trace("Replication file writing started");

                    writeTimeStarted = currentTimeMillis();
                    fileStorage.addFile(replicationFile);
                } catch (Throwable t) {
                    log.error("Can't write replication file to the storage", t);
                } finally {
                    log.trace("Replication file writing finished {}", currentTimeMillis() - writeTimeStarted);
                }
            }
        }
    }
}
