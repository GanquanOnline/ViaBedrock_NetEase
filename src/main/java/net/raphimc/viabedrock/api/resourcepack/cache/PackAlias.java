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

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * 作用域限定到单个后端的资源包声明。它只是候选定位符：内容标识始终在
 * 解密并规范化该包后由 {@link ContentDigest} 确定。
 */
public record PackAlias(String backendScope, UUID id, String version, long announcedSize, String contentId,
                        String contentKeyFingerprint) {

    private static final String EMPTY_CONTENT_KEY_FINGERPRINT = fingerprintContentKey(new byte[0]);

    public PackAlias {
        Objects.requireNonNull(backendScope, "backendScope");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(contentKeyFingerprint, "contentKeyFingerprint");
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Resource pack version must not be empty");
        }
        if (announcedSize < -1L) {
            throw new IllegalArgumentException("Resource pack announced size must be non-negative or unknown");
        }
        DigestSupport.strictUtf8(version, "resource pack version");
        DigestSupport.strictUtf8(backendScope, "resource pack backend scope");
        DigestSupport.strictUtf8(contentId, "resource pack content id");
        contentKeyFingerprint = DigestSupport.requireSha256Hex(contentKeyFingerprint);
    }

    /** 没有后端与公告元数据的兼容性别名。绝不能把它用于可信查找。 */
    public PackAlias(final UUID id, final String version) {
        this("", id, version, -1L, "", EMPTY_CONTENT_KEY_FINGERPRINT);
    }

    public static PackAlias from(final ResourcePack.Key key) {
        Objects.requireNonNull(key, "key");
        return new PackAlias(key.id(), key.version());
    }

    public static PackAlias from(final String backendScope, final ResourcePack.Key key, final long announcedSize,
                                 final String contentId, final byte[] contentKey) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentKey, "contentKey");
        return new PackAlias(backendScope, key.id(), key.version(), announcedSize, contentId,
                fingerprintContentKey(contentKey));
    }

    public static String fingerprintContentKey(final byte[] contentKey) {
        Objects.requireNonNull(contentKey, "contentKey");
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(contentKey);
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit(Byte.toUnsignedInt(value) >>> 4, 16));
                hex.append(Character.forDigit(Byte.toUnsignedInt(value) & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isComplete() {
        return !this.backendScope.isEmpty() && this.announcedSize >= 0L;
    }

    public ResourcePack.Key toResourcePackKey() {
        return new ResourcePack.Key(this.id, this.version);
    }

}
