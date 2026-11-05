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

import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 不可变的、磁盘支撑的包内容。二进制资源仅在调用方读取时才进入堆。 */
public final class ZipFileContent extends Content {

    private final Path path;
    private final List<String> paths;
    private final Set<String> pathSet;
    private final Map<String, Long> entrySizes;
    private final long weightBytes;
    private final long expandedBytes;
    private final ContentDigest contentDigest;
    private final ThreadLocal<ZipFile> readSession = new ThreadLocal<>();

    public ZipFileContent(final Path path) throws IOException {
        this(path, null);
    }

    public ZipFileContent(final Path path, final ContentDigest contentDigest) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.contentDigest = contentDigest;
        final List<String> files = new ArrayList<>();
        final Set<String> unique = new HashSet<>();
        final Map<String, Long> sizes = new LinkedHashMap<>();
        long weight = 128L;
        long expanded = 0L;
        try (ZipFile zip = new ZipFile(this.path.toFile())) {
            final var entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!unique.add(entry.getName())) {
                    throw new IOException("Duplicate path in canonical resource pack: " + entry.getName());
                }
                files.add(entry.getName());
                sizes.put(entry.getName(), entry.getSize());
                weight += 64L + (long) entry.getName().length() * Character.BYTES;
                if (entry.getSize() > 0L) expanded += entry.getSize();
            }
        }
        Collections.sort(files);
        this.paths = List.copyOf(files);
        this.pathSet = Set.copyOf(unique);
        this.entrySizes = Map.copyOf(sizes);
        this.weightBytes = weight;
        this.expandedBytes = expanded;
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
        return path != null && this.pathSet.contains(path);
    }

    @Override
    public byte[] get(final String path) {
        try (InputStream input = this.open(path)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(final String path) throws IOException {
        if (path == null || !this.pathSet.contains(path)) return null;
        final ZipFile activeSession = this.readSession.get();
        if (activeSession != null) {
            final ZipEntry entry = activeSession.getEntry(path);
            return entry == null ? null : activeSession.getInputStream(entry);
        }
        final ZipFile zip = new ZipFile(this.path.toFile());
        final ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            zip.close();
            return null;
        }
        return new FilterInputStream(zip.getInputStream(entry)) {
            @Override
            public void close() throws IOException {
                IOException failure = null;
                try {
                    super.close();
                } catch (IOException e) {
                    failure = e;
                }
                try {
                    zip.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
                if (failure != null) throw failure;
            }
        };
    }

    @Override
    public long size(final String path) throws IOException {
        return path == null ? -1L : this.entrySizes.getOrDefault(path, -1L);
    }

    @Override
    public void visitFiles(final List<String> paths, final FileVisitor visitor) throws IOException {
        final ZipFile activeSession = this.readSession.get();
        if (activeSession != null) {
            this.visitFiles(activeSession, paths, visitor);
            return;
        }
        try (ZipFile zip = new ZipFile(this.path.toFile())) {
            this.visitFiles(zip, paths, visitor);
        }
    }

    private void visitFiles(final ZipFile zip, final List<String> paths,
                            final FileVisitor visitor) throws IOException {
        for (String path : paths) {
            final ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                visitor.visit(path, -1L, null);
                continue;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                visitor.visit(path, entry.getSize(), input);
            }
        }
    }

    @Override
    public <T> T withReadSession(final Supplier<T> action) {
        if (this.readSession.get() != null) {
            return action.get();
        }
        try (ZipFile zip = new ZipFile(this.path.toFile())) {
            this.readSession.set(zip);
            return action.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            this.readSession.remove();
        }
    }

    @Override
    public boolean put(final String path, final byte[] data) {
        throw new UnsupportedOperationException("Canonical resource pack content cannot be modified");
    }

    @Override
    public void writeZip(final Path target) throws IOException {
        Files.copy(this.path, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public Path path() {
        return this.path;
    }

    public long weightBytes() {
        return this.weightBytes;
    }

    public long expandedBytes() {
        return this.expandedBytes;
    }

    public ContentDigest contentDigest() {
        return this.contentDigest;
    }

}
