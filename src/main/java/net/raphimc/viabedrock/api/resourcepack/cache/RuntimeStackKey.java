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
import java.util.List;
import java.util.Objects;

/** 一个有序资源包挂载堆叠的 SHA-256 标识。 */
public record RuntimeStackKey(String hex) {

    private static final byte[] DOMAIN = "ViaBedrock-RuntimeStack-v2\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCHEMA_DOMAIN = "ViaBedrock-RuntimeStack-v3\0".getBytes(StandardCharsets.US_ASCII);

    public RuntimeStackKey {
        hex = DigestSupport.requireSha256Hex(hex);
    }

    public static RuntimeStackKey compute(final List<PackMount> mounts) {
        Objects.requireNonNull(mounts, "mounts");
        final List<PackMount> snapshot = List.copyOf(mounts);
        final MessageDigest digest = DigestSupport.sha256();
        digest.update(DOMAIN);
        DigestSupport.updateInt(digest, snapshot.size());
        for (PackMount mount : snapshot) {
            updateMount(digest, mount);
        }
        return new RuntimeStackKey(DigestSupport.toHex(digest.digest()));
    }

    /** 包含运行时 schema 以及影响跨包链接的内置定义指纹。 */
    public static RuntimeStackKey compute(final List<PackMount> mounts, final int runtimeSchemaVersion,
                                          final List<String> builtInFingerprints) {
        Objects.requireNonNull(mounts, "mounts");
        Objects.requireNonNull(builtInFingerprints, "builtInFingerprints");
        if (runtimeSchemaVersion < 0) {
            throw new IllegalArgumentException("Runtime schema version must not be negative");
        }
        final List<PackMount> snapshot = List.copyOf(mounts);
        final List<String> fingerprints = List.copyOf(builtInFingerprints);
        final MessageDigest digest = DigestSupport.sha256();
        digest.update(SCHEMA_DOMAIN);
        DigestSupport.updateInt(digest, runtimeSchemaVersion);
        DigestSupport.updateInt(digest, fingerprints.size());
        for (String fingerprint : fingerprints) {
            DigestSupport.updateBytes(digest, DigestSupport.strictUtf8(fingerprint, "built-in fingerprint"));
        }
        DigestSupport.updateInt(digest, snapshot.size());
        for (PackMount mount : snapshot) {
            updateMount(digest, mount);
        }
        return new RuntimeStackKey(DigestSupport.toHex(digest.digest()));
    }

    private static void updateMount(final MessageDigest digest, final PackMount mount) {
        final PackAlias alias = mount.alias();
        DigestSupport.updateLong(digest, alias.id().getMostSignificantBits());
        DigestSupport.updateLong(digest, alias.id().getLeastSignificantBits());
        DigestSupport.updateBytes(digest, DigestSupport.strictUtf8(alias.version(), "pack version"));
        digest.update(DigestSupport.fromHex(mount.contentDigest().hex()));
        DigestSupport.updateBytes(digest, DigestSupport.strictUtf8(mount.subpack(), "subpack"));
    }

    @Override
    public String toString() {
        return this.hex;
    }

}
