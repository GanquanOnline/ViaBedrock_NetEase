/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.content;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读内容，其字节数组的所有权由一次已完成的包构建转移而来。 */
public final class FrozenContent extends Content {

    private final Map<String, byte[]> content;
    private final List<String> paths;
    private final long weightBytes;

    public FrozenContent(final Content source) {
        final List<String> sourcePaths = new ArrayList<>(source.getFilesDeep("", ""));
        Collections.sort(sourcePaths);
        final Map<String, byte[]> frozen = new LinkedHashMap<>(sourcePaths.size());
        long weight = 0L;
        for (String path : sourcePaths) {
            final byte[] data = source.get(path);
            if (data == null) {
                throw new IllegalArgumentException("Missing resource pack content: " + path);
            }
            frozen.put(path, data.clone());
            weight += data.length + (long) path.length() * Character.BYTES + 64L;
        }
        this.content = Collections.unmodifiableMap(frozen);
        this.paths = List.copyOf(sourcePaths);
        this.weightBytes = weight;
    }

    @Override
    public List<String> getFilesShallow(final String path, final String extension) {
        final List<String> result = new ArrayList<>();
        for (String file : this.paths) {
            if (file.startsWith(path) && !file.substring(path.length()).contains("/") && file.endsWith(extension)) {
                result.add(file);
            }
        }
        return result;
    }

    @Override
    public List<String> getFilesDeep(final String path, final String extension) {
        final List<String> result = new ArrayList<>();
        for (String file : this.paths) {
            if (file.startsWith(path) && file.endsWith(extension)) {
                result.add(file);
            }
        }
        return result;
    }

    @Override
    public boolean contains(final String path) {
        return this.content.containsKey(path);
    }

    @Override
    public byte[] get(final String path) {
        final byte[] data = this.content.get(path);
        return data == null ? null : data.clone();
    }

    @Override
    public InputStream open(final String path) {
        final byte[] data = this.content.get(path);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    @Override
    public long size(final String path) {
        final byte[] data = this.content.get(path);
        return data == null ? -1L : data.length;
    }

    @Override
    public boolean put(final String path, final byte[] data) {
        throw new UnsupportedOperationException("Frozen resource pack content cannot be modified");
    }

    public long weightBytes() {
        return this.weightBytes;
    }

}
