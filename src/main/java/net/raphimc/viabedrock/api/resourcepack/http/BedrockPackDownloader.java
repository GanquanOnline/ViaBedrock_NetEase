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
package net.raphimc.viabedrock.api.resourcepack.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class BedrockPackDownloader {

    private static final int TIMEOUT = 6000;

    private final URL url;

    public BedrockPackDownloader(final URL url) {
        this.url = url;
    }

    public int getContentLength() throws IOException {
        final HttpURLConnection connection = this.createConnection();
        try {
            connection.setRequestMethod("HEAD");
            connection.connect();
            this.checkResponseCode(connection);
            if (connection.getContentLength() < 0) {
                throw new IOException("Content-Length is not set");
            }
            return connection.getContentLength();
        } finally {
            connection.disconnect();
        }
    }

    public byte[] download() throws IOException {
        return this.download(Integer.MAX_VALUE);
    }

    public byte[] download(final long maxBytes) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        this.downloadTo(output, Math.min(maxBytes, Integer.MAX_VALUE));
        return output.toByteArray();
    }

    public long downloadTo(final Path target) throws IOException {
        return this.downloadTo(target, Long.MAX_VALUE);
    }

    /** 把响应流式写入调用方拥有的临时文件，若传输不完整则删除它。 */
    public long downloadTo(final Path target, final long maxBytes) throws IOException {
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            return this.downloadTo(output, maxBytes);
        } catch (IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    /** 流式写入调用方拥有的输出，且不关闭它。 */
    public long downloadTo(final OutputStream output, final long maxBytes) throws IOException {
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("Resource pack size limit must not be negative");
        }
        final HttpURLConnection connection = this.createConnection();
        try {
            connection.setRequestMethod("GET");
            connection.connect();
            this.checkResponseCode(connection);
            final long contentLength = connection.getContentLengthLong();
            if (contentLength > maxBytes) {
                throw new IOException("Resource pack download exceeds the configured size limit");
            }

            try (InputStream input = connection.getInputStream()) {
                final byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int read;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        final InterruptedIOException interrupted = new InterruptedIOException(
                                "Resource pack download was interrupted");
                        interrupted.bytesTransferred = (int) Math.min(total, Integer.MAX_VALUE);
                        throw interrupted;
                    }
                    read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    if (total > maxBytes - read) {
                        throw new IOException("Resource pack download exceeds the configured size limit");
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                if (contentLength >= 0L && total != contentLength) {
                    throw new IOException("Resource pack response length does not match Content-Length");
                }
                return total;
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection createConnection() throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT * 2);
        connection.setDoInput(true);
        connection.setDoOutput(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("User-Agent", "libhttpclient/1.0.0.0");
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    private void checkResponseCode(final HttpURLConnection connection) throws IOException {
        if (connection.getResponseCode() / 100 != 2) {
            throw new IOException("HTTP response code: " + connection.getResponseCode());
        }
    }

}
