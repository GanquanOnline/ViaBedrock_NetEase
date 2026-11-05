/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.FrozenContent;
import net.raphimc.viabedrock.api.resourcepack.content.PackContentView;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 一份不可变的资源包文件树，按精确内容摘要驻留复用。
 *
 * <p>生产环境的条目只能通过 {@link ResourcePackArchiveStore} 打开，并基于经过强校验的规范 CAS ZIP。
 * 兼容性工厂仅供在没有 store 的情况下构造 {@link SharedPackRuntimeCache} 的嵌入方和测试使用；
 * 不得把它当作生产环境共享缓存的证据。</p>
 */
public final class FrozenPackBlob {

    public enum BackingKind {
        CANONICAL_CAS,
        COMPATIBILITY
    }

    private final ContentDigest contentDigest;
    private final ResourcePack resourcePack;
    private final PackContentView content;
    private final Path canonicalPath;
    private final BackingKind backingKind;
    private final long estimatedHeapWeightBytes;

    private FrozenPackBlob(final ContentDigest contentDigest, final ResourcePack resourcePack,
                           final Path canonicalPath, final BackingKind backingKind,
                           final long estimatedHeapWeightBytes) {
        this.contentDigest = Objects.requireNonNull(contentDigest, "contentDigest");
        this.resourcePack = Objects.requireNonNull(resourcePack, "resourcePack");
        this.content = resourcePack.content();
        this.canonicalPath = canonicalPath;
        this.backingKind = Objects.requireNonNull(backingKind, "backingKind");
        this.estimatedHeapWeightBytes = Math.max(1L, estimatedHeapWeightBytes);
    }

    static FrozenPackBlob canonical(final ContentDigest expectedDigest, final ResourcePack resourcePack,
                                    final Path canonicalPath, final long estimatedHeapWeightBytes) {
        Objects.requireNonNull(expectedDigest, "expectedDigest");
        final Path normalizedPath = Objects.requireNonNull(canonicalPath, "canonicalPath")
                .toAbsolutePath().normalize();
        if (!normalizedPath.getFileName().toString().equals(expectedDigest.hex() + ".zip")) {
            throw new IllegalArgumentException("Canonical resource pack path does not match its content digest");
        }
        return new FrozenPackBlob(expectedDigest, resourcePack, normalizedPath, BackingKind.CANONICAL_CAS,
                saturatingAdd(estimatedHeapWeightBytes, 512L));
    }

    static FrozenPackBlob compatibility(final ContentDigest expectedDigest, final ResourcePack source) {
        Objects.requireNonNull(expectedDigest, "expectedDigest");
        Objects.requireNonNull(source, "source");
        // ZipFileContent 对象是只读的，但其外部路径仍可能被原地替换。
        // 兼容性缓存没有 CAS 租约，因此只有已持有的 FrozenContent 才能复用。
        final Content immutableContent = source.content() instanceof FrozenContent
                ? source.content() : new FrozenContent(source.content());

        final ContentDigest actualDigest = ContentDigest.compute(immutableContent);
        if (!expectedDigest.equals(actualDigest)) {
            throw new IllegalArgumentException("Compatibility resource pack content digest mismatch: "
                    + actualDigest + " != " + expectedDigest);
        }
        final ResourcePack immutablePack = immutableContent == source.content()
                ? source : new ResourcePack(immutableContent);
        if (!source.key().equals(immutablePack.key())) {
            throw new IllegalArgumentException("Compatibility resource pack manifest identity changed while freezing");
        }
        return new FrozenPackBlob(expectedDigest, immutablePack, null, BackingKind.COMPATIBILITY,
                saturatingAdd(estimateContentWeight(immutableContent), 512L));
    }

    public ContentDigest contentDigest() {
        return this.contentDigest;
    }

    public ResourcePack.Key manifestKey() {
        return this.resourcePack.key();
    }

    public String name() {
        return this.resourcePack.name();
    }

    public PackContentView content() {
        return this.content;
    }

    public Optional<Path> canonicalPath() {
        return Optional.ofNullable(this.canonicalPath);
    }

    public BackingKind backingKind() {
        return this.backingKind;
    }

    public boolean isProductionCanonical() {
        return this.backingKind == BackingKind.CANONICAL_CAS;
    }

    public long estimatedHeapWeightBytes() {
        return this.estimatedHeapWeightBytes;
    }

    ResourcePack resourcePack() {
        return this.resourcePack;
    }

    private static long estimateContentWeight(final Content content) {
        if (content instanceof FrozenContent frozenContent) {
            return frozenContent.weightBytes();
        }
        if (content instanceof ZipFileContent zipFileContent) {
            return zipFileContent.weightBytes();
        }
        throw new IllegalArgumentException("Frozen pack blob content is not immutable");
    }

    private static long saturatingAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

}
