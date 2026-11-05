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

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Objects;

/** 传输或存储时资源包归档原始字节的 SHA-256。 */
public record ArchiveDigest(String hex) {

    public ArchiveDigest {
        hex = DigestSupport.requireSha256Hex(hex);
    }

    public static ArchiveDigest compute(final byte[] archive) {
        Objects.requireNonNull(archive, "archive");
        return new ArchiveDigest(DigestSupport.toHex(DigestSupport.sha256().digest(archive)));
    }

    /** 计算摘要，且不关闭传入的流。 */
    public static ArchiveDigest compute(final InputStream archive) throws IOException {
        Objects.requireNonNull(archive, "archive");
        final MessageDigest digest = DigestSupport.sha256();
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = archive.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return new ArchiveDigest(DigestSupport.toHex(digest.digest()));
    }

    @Override
    public String toString() {
        return this.hex;
    }

}
