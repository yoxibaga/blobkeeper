package io.blobkeeper.file.domain;

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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import io.blobkeeper.file.util.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.blobkeeper.common.util.MetadataUtils.AUTH_TOKEN_HEADER;

public class StorageFile {
    private final long id;
    private final int type;
    private final java.io.File file;
    private final long length;
    private final byte[] data;
    private final Multimap<String, String> metadata;
    private final String name;
    private List<String> authTokens = new ArrayList<>();

    public StorageFile(StorageFileBuilder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.file = builder.file;
        this.length = builder.length;
        this.data = builder.data;
        this.metadata = builder.metadata;
        this.name = builder.name;
        setAuthTokens(metadata);
    }

    public java.io.File getFile() {
        return file;
    }

    public long getId() {
        return id;
    }

    public long getLength() {
        return length;
    }

    public ByteBuffer getData() {
        if (null != data) {
            return getFromData();
        }

        if (null != file) {
            return getFromFile();
        }

        throw new IllegalStateException("File or data must be not null!");
    }

    public Multimap<String, String> getMetadata() {
        return metadata;
    }

    public String getName() {
        return name;
    }

    private ByteBuffer getFromFile() {
        return FileUtils.readFile(file);
    }

    private ByteBuffer getFromData() {
        return ByteBuffer.wrap(data);
    }

    public int getType() {
        return type;
    }

    public boolean hasAuthTokens() {
        return !authTokens.isEmpty();
    }

    private void setAuthTokens(Multimap<String, String> metadata) {
        if (metadata.containsKey(AUTH_TOKEN_HEADER)) {
            this.authTokens = ImmutableList.copyOf(metadata.get(AUTH_TOKEN_HEADER));
        }
    }

    public List<String> getAuthTokens() {
        return authTokens;
    }

    public static class StorageFileBuilder {
        private long id;
        private int type;
        private java.io.File file;
        private long length;
        private byte[] data;
        private Multimap<String, String> metadata;
        private String name;

        public StorageFileBuilder id(long id) {
            this.id = id;
            return this;
        }

        public StorageFileBuilder type(int type) {
            this.type = type;
            return this;
        }

        public StorageFileBuilder file(@NotNull java.io.File file) {
            this.file = file;
            length(file.length());
            return this;
        }

        public StorageFileBuilder length(long length) {
            this.length = length;
            return this;
        }

        public StorageFileBuilder data(byte[] data) {
            this.data = data;
            length(data.length);
            return this;
        }

        public StorageFileBuilder metadata(@NotNull Multimap<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public StorageFileBuilder name(@NotNull String name) {
            this.name = name;
            return this;
        }

        public StorageFile build() {
            checkArgument(null == data || null == file, "Must be only one file source!");
            checkArgument(length > 0, "Zero length files are not acceptable!");

            return new StorageFile(this);
        }
    }
}
