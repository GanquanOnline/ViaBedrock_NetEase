/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.cache;

import net.raphimc.viabedrock.api.resourcepack.content.Content;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 经校验的有效资源包文件树的 SHA-256。 */
public record ContentDigest(String hex) {

    private static final byte[] DOMAIN = "ViaBedrock-EffectivePack-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final Comparator<Entry> PATH_ORDER = (left, right) -> compareUnsigned(left.pathBytes(), right.pathBytes());

    public ContentDigest {
        hex = DigestSupport.requireSha256Hex(hex);
    }

    public static ContentDigest compute(final Content content) {
        Objects.requireNonNull(content, "content");
        final List<String> paths = Objects.requireNonNull(content.getFilesDeep("", ""), "content paths");
        final Set<String> uniquePaths = new HashSet<>(paths.size());
        final List<Entry> entries = new ArrayList<>(paths.size());
        for (String path : paths) {
            final byte[] pathBytes = validatePath(path);
            if (!uniquePaths.add(path)) {
                throw new IllegalArgumentException("Duplicate resource pack path: " + path);
            }

            entries.add(new Entry(path, pathBytes));
        }
        entries.sort(PATH_ORDER);

        final MessageDigest digest = DigestSupport.sha256();
        digest.update(DOMAIN);
        DigestSupport.updateInt(digest, entries.size());
        final byte[] buffer = new byte[64 * 1024];
        final Map<String, Entry> entriesByPath = new HashMap<>(entries.size());
        final List<String> sortedPaths = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            entriesByPath.put(entry.path(), entry);
            sortedPaths.add(entry.path());
        }
        try {
            content.visitFiles(sortedPaths, (path, expectedSize, input) -> {
                final Entry entry = entriesByPath.get(path);
                DigestSupport.updateBytes(digest, entry.pathBytes());
                if (expectedSize < 0L) {
                    throw new IllegalArgumentException("Resource pack path has no content: " + path);
                }
                DigestSupport.updateLong(digest, expectedSize);
                long actualSize = 0L;
                if (input == null) {
                    throw new IllegalArgumentException("Resource pack path has no content: " + path);
                }
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    actualSize += read;
                }
                if (actualSize != expectedSize) {
                    throw new IllegalArgumentException("Resource pack entry size changed while hashing: " + path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash resource pack content", e);
        }
        return new ContentDigest(DigestSupport.toHex(digest.digest()));
    }

    private static byte[] validatePath(final String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Resource pack path must not be empty");
        }
        if (path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("Resource pack path must be a relative file path: " + path);
        }
        if (path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Resource pack path must use forward slashes: " + path);
        }
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            throw new IllegalArgumentException("Resource pack path must not use a drive prefix: " + path);
        }

        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Resource pack path is not canonical: " + path);
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IllegalArgumentException("Resource pack path contains a control character");
            }
        }
        return DigestSupport.strictUtf8(path, "resource pack path");
    }

    private static int compareUnsigned(final byte[] left, final byte[] right) {
        final int sharedLength = Math.min(left.length, right.length);
        for (int i = 0; i < sharedLength; i++) {
            final int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    @Override
    public String toString() {
        return this.hex;
    }

    private record Entry(String path, byte[] pathBytes) {
    }

}
