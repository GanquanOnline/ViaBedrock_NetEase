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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 只读有效视图，其中一个基岩子包覆写该包的基础文件。 */
public final class SelectedSubpackContent extends Content {

    private final Content source;
    private final String selectedSubpack;
    private final Map<String, String> physicalPaths;
    private final List<String> paths;

    public SelectedSubpackContent(final Content source, final String selectedSubpack) {
        this.source = Objects.requireNonNull(source, "source");
        this.selectedSubpack = validateSubpack(selectedSubpack);
        final Map<String, String> paths = new LinkedHashMap<>();
        for (String path : source.getFilesDeep("", "")) {
            if (!path.startsWith("subpacks/")) {
                paths.put(path, path);
            }
        }
        if (!this.selectedSubpack.isEmpty()) {
            final String prefix = "subpacks/" + this.selectedSubpack + "/";
            for (String path : source.getFilesDeep(prefix, "")) {
                final String effectivePath = path.substring(prefix.length());
                if (!effectivePath.isEmpty() && !isRootIdentityMetadata(effectivePath)) {
                    paths.put(effectivePath, path);
                }
            }
        }
        this.physicalPaths = Map.copyOf(paths);
        final List<String> sorted = new ArrayList<>(paths.keySet());
        sorted.sort(String::compareTo);
        this.paths = List.copyOf(sorted);
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
        return this.physicalPaths.containsKey(path);
    }

    @Override
    public byte[] get(final String path) {
        final String physicalPath = this.physicalPaths.get(path);
        return physicalPath == null ? null : this.source.get(physicalPath);
    }

    @Override
    public InputStream open(final String path) throws IOException {
        final String physicalPath = this.physicalPaths.get(path);
        return physicalPath == null ? null : this.source.open(physicalPath);
    }

    @Override
    public long size(final String path) throws IOException {
        final String physicalPath = this.physicalPaths.get(path);
        return physicalPath == null ? -1L : this.source.size(physicalPath);
    }

    @Override
    public <T> T withReadSession(final Supplier<T> action) {
        return this.source.withReadSession(action);
    }

    @Override
    public boolean put(final String path, final byte[] data) {
        throw new UnsupportedOperationException("Selected subpack content cannot be modified");
    }

    public String selectedSubpack() {
        return this.selectedSubpack;
    }

    private static boolean isRootIdentityMetadata(final String path) {
        return path.equals("manifest.json") || path.equals("pack_manifest.json");
    }

    private static String validateSubpack(final String selectedSubpack) {
        final String value = Objects.requireNonNullElse(selectedSubpack, "");
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid selected resource pack subpack: " + value);
        }
        return value;
    }

}
