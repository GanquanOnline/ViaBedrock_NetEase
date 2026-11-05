/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.api.resourcepack.content;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;

public class DirectoryContent extends Content {

    private final Path dir;

    public DirectoryContent(final Path dir) {
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("Base path must be absolute");
        }
        this.dir = dir.normalize();
    }

    @Override
    public List<String> getFilesShallow(final String path, final String extension) {
        final Path resolvedPath = this.resolvePath(this.dir, Path.of(path));
        if (!Files.exists(resolvedPath)) {
            return List.of();
        }
        try (var files = Files.list(resolvedPath)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(this.dir::relativize)
                    .map(Path::toString)
                    .map(s -> s.replace('\\', '/'))
                    .filter(file -> !file.contains("/") && file.endsWith(extension))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> getFilesDeep(final String path, final String extension) {
        final Path resolvedPath = this.resolvePath(this.dir, Path.of(path));
        if (!Files.exists(resolvedPath)) {
            return List.of();
        }
        try (var files = Files.walk(resolvedPath)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(this.dir::relativize)
                    .map(Path::toString)
                    .map(s -> s.replace('\\', '/'))
                    .filter(file -> file.endsWith(extension))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean contains(final String path) {
        return Files.exists(this.resolvePath(this.dir, Path.of(path)));
    }

    @Override
    public byte[] get(final String path) {
        final Path resolvedPath = this.resolvePath(this.dir, Path.of(path));
        if (!Files.exists(resolvedPath)) {
            return null;
        }
        try {
            return Files.readAllBytes(resolvedPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(final String path) throws IOException {
        final Path resolvedPath = this.resolvePath(this.dir, Path.of(path));
        return Files.isRegularFile(resolvedPath) ? Files.newInputStream(resolvedPath) : null;
    }

    @Override
    public long size(final String path) throws IOException {
        final Path resolvedPath = this.resolvePath(this.dir, Path.of(path));
        return Files.isRegularFile(resolvedPath) ? Files.size(resolvedPath) : -1L;
    }

    @Override
    public void copyFrom(final Content content, final String sourcePath, final String targetPath) {
        final Path target = this.resolvePath(this.dir, Path.of(targetPath));
        try (InputStream input = content.open(sourcePath)) {
            if (input == null) {
                throw new IOException("Missing resource pack content: " + sourcePath);
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 用一个自有文件的解密字节替换该文件，而不在堆上实体化该条目。 */
    public void decryptFile(final String path, final Cipher cipher) throws IOException {
        this.decryptFile(path, cipher, 0L, Long.MAX_VALUE);
    }

    /** 跳过未加密前缀后，用一个有界的解密载荷替换一个自有文件。 */
    public void decryptFile(final String path, final Cipher cipher, final long encryptedOffset,
                            final long maxOutputBytes) throws IOException {
        if (encryptedOffset < 0L || maxOutputBytes < 0L) {
            throw new IllegalArgumentException("Decrypt offsets and limits must not be negative");
        }
        final Path target = this.resolvePath(this.dir, Path.of(path));
        if (!Files.isRegularFile(target)) {
            throw new IOException("Missing resource pack content: " + path);
        }
        final Path temp = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".decrypt.tmp");
        try {
            try (InputStream encrypted = Files.newInputStream(target);
                 OutputStream output = Files.newOutputStream(temp)) {
                encrypted.skipNBytes(encryptedOffset);
                try (CipherInputStream input = new CipherInputStream(encrypted, cipher)) {
                    final byte[] buffer = new byte[64 * 1024];
                    long outputBytes = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (outputBytes > maxOutputBytes - read) {
                            throw new IOException("Decrypted resource pack entry exceeds its size limit: " + path);
                        }
                        output.write(buffer, 0, read);
                        outputBytes += read;
                    }
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public boolean put(final String path, final byte[] data) {
        final boolean exists = this.contains(path);
        try {
            final Path target = this.resolvePath(this.dir, Path.of(path));
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return exists;
    }

    private Path resolvePath(final Path basePath, final Path userPath) {
        if (userPath.isAbsolute()) {
            throw new IllegalArgumentException("User path must be relative");
        }
        final Path resolvedPath = basePath.resolve(userPath).normalize();
        if (!resolvedPath.startsWith(basePath)) {
            throw new IllegalArgumentException("Path traversal attempt: " + userPath);
        }
        return resolvedPath;
    }

}
