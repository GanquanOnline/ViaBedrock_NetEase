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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** 一个已转换的 Java 产物变体的 SHA-256 缓存键。 */
public record ArtifactKey(String hex) {

    private static final byte[] DOMAIN = "ViaBedrock-Artifact-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FINGERPRINTED_DOMAIN = "ViaBedrock-Artifact-v2\0".getBytes(StandardCharsets.US_ASCII);

    public ArtifactKey {
        hex = DigestSupport.requireSha256Hex(hex);
    }

    public static ArtifactKey compute(final RuntimeStackKey stackKey, final int converterVersion, final boolean supportsFreeRotation) {
        Objects.requireNonNull(stackKey, "stackKey");
        if (converterVersion < 0) {
            throw new IllegalArgumentException("Converter version must not be negative");
        }

        final MessageDigest digest = DigestSupport.sha256();
        digest.update(DOMAIN);
        digest.update(DigestSupport.fromHex(stackKey.hex()));
        DigestSupport.updateInt(digest, converterVersion);
        digest.update((byte) (supportsFreeRotation ? 1 : 0));
        return new ArtifactKey(DigestSupport.toHex(digest.digest()));
    }

    public static ArtifactKey compute(final RuntimeStackKey stackKey, final int converterVersion,
                                      final String rewriterFingerprint,
                                      final boolean supportsFreeRotation) {
        Objects.requireNonNull(stackKey, "stackKey");
        if (converterVersion < 0) {
            throw new IllegalArgumentException("Converter version must not be negative");
        }

        final MessageDigest digest = DigestSupport.sha256();
        digest.update(FINGERPRINTED_DOMAIN);
        digest.update(DigestSupport.fromHex(stackKey.hex()));
        DigestSupport.updateInt(digest, converterVersion);
        DigestSupport.updateBytes(digest,
                DigestSupport.strictUtf8(rewriterFingerprint, "rewriter fingerprint"));
        digest.update((byte) (supportsFreeRotation ? 1 : 0));
        return new ArtifactKey(DigestSupport.toHex(digest.digest()));
    }

    @Override
    public String toString() {
        return this.hex;
    }

}
