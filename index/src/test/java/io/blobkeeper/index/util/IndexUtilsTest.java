package io.blobkeeper.index.util;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import io.blobkeeper.common.configuration.MetricModule;
import io.blobkeeper.common.configuration.RootModule;
import io.blobkeeper.common.service.IdGeneratorService;
import io.blobkeeper.common.util.Block;
import io.blobkeeper.common.util.MerkleTree;
import io.blobkeeper.common.util.Utils;
import io.blobkeeper.index.dao.IndexDao;
import io.blobkeeper.index.domain.IndexElt;
import io.blobkeeper.index.domain.Partition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import javax.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.blobkeeper.common.util.MerkleTree.MAX_LEVEL;
import static java.util.stream.Collectors.toMap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/*
 * Copyright (C) 2015 by Denis M. Gabaydulin
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

@Guice(modules = {RootModule.class, MetricModule.class})
public class IndexUtilsTest {
    @Inject
    private IndexDao indexDao;

    @Inject
    private IndexUtils indexUtils;

    @Inject
    private IdGeneratorService generatorService;

    @Test
    public void checkMinMax() {
        Partition partition = new Partition(42, 42);

        long min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < 100; i++) {
            long newId = generatorService.generate(1);

            assertTrue(indexDao.getListById(newId).isEmpty());
            assertNull(indexDao.getById(newId, 1));

            IndexElt expected = new IndexElt.IndexEltBuilder()
                    .id(newId)
                    .type(1)
                    .partition(partition)
                    .offset(128 * i)
                    .length(128L)
                    .metadata(ImmutableMap.of("key", "value"))
                    .crc(42L)
                    .build();

            indexDao.add(expected);

            min = Math.min(min, expected.getId());
            max = Math.max(max, expected.getId());
        }

        List<IndexElt> elts = indexDao.getListByPartition(partition);

        MinMaxConsumer minMax = elts.stream()
                .collect(MinMaxConsumer::new, MinMaxConsumer::accept, MinMaxConsumer::combine);

        assertEquals(minMax.getMin().getId(), min);
        assertEquals(minMax.getMax().getId(), max);
    }

    @Test
    public void buildMerkleTree() {
        Partition partition = new Partition(42, 42);

        assertTrue(indexDao.getListById(303277865741324292L).isEmpty());
        assertNull(indexDao.getById(303277865741324292L, 1));

        IndexElt expected1 = new IndexElt.IndexEltBuilder()
                .id(303277865741324292L)
                .type(1)
                .partition(partition)
                .offset(0L)
                .length(128L)
                .metadata(ImmutableMap.of("key", "value"))
                .crc(42L)
                .build();

        indexDao.add(expected1);

        IndexElt expected2 = new IndexElt.IndexEltBuilder()
                .id(303277865741324292L)
                .type(2)
                .partition(partition)
                .offset(128L)
                .length(128L)
                .metadata(ImmutableMap.of("key", "value"))
                .crc(42L)
                .build();

        indexDao.add(expected2);

        IndexElt expected3 = new IndexElt.IndexEltBuilder()
                .id(303277865741324291L)
                .type(0)
                .partition(partition)
                .offset(0L)
                .length(128L)
                .metadata(ImmutableMap.of("key", "value"))
                .crc(42L)
                .build();

        indexDao.add(expected3);

        MerkleTree merkleTree = indexUtils.buildMerkleTree(partition);
        assertEquals(merkleTree.getLeafNodes().size(), 2);

        log.info("{}", merkleTree.getLeafNodes().get(1).getHash());

        assertEquals(merkleTree.getLeafNodes().get(1).getHash(), new byte[]{4, 53, 117, -100, -81, -64, 16, 4, 0, 0, 0, 1, 0, 0, 0, 2});
    }

    private static final Logger log = LoggerFactory.getLogger(IndexUtilsTest.class);

    @Test
    public void difference() {
        Partition partition1 = new Partition(42, 42);
        IndexElt expected = new IndexElt.IndexEltBuilder()
                .id(42L)
                .type(1)
                .partition(partition1)
                .offset(0L)
                .length(128L)
                .metadata(ImmutableMap.of("key", "value"))
                .crc(42L)
                .build();

        indexDao.add(expected);

        Partition partition2 = new Partition(42, 43);
        IndexElt expected2 = new IndexElt.IndexEltBuilder()
                .id(43L)
                .type(1)
                .partition(partition2)
                .offset(0L)
                .length(128L)
                .metadata(ImmutableMap.of("key", "value"))
                .crc(42L)
                .build();

        indexDao.add(expected2);

        MerkleTree merkleTree1 = indexUtils.buildMerkleTree(partition1);
        MerkleTree merkleTree2 = indexUtils.buildMerkleTree(partition2);

        assertEquals(MerkleTree.difference(merkleTree1, merkleTree2).get(0).getRange(), Range.openClosed(42L, 43L));
    }

    @BeforeMethod
    private void clear() {
        indexDao.clear();
    }
}
