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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StorableObject;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PackType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ResourcePackDownloadTracker implements StorableObject {

    public static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CHUNK_COUNT = 1_000_000;

    private final Map<String, Download> downloads = new HashMap<>();
    private final Map<String, TransferMetadata> transferMetadata = new HashMap<>();
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<?>> connectionStages = ConcurrentHashMap.newKeySet();
    private final Set<ResourcePackArchiveStore.Claim> pendingArchiveClaims = ConcurrentHashMap.newKeySet();
    private final ResourcePackArchiveStore archiveStore;
    private final ResourcePackWorkScheduler scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourcePackDownloadTracker() {
        this(ViaBedrock.getResourcePackArchiveStore(), ViaBedrock.getResourcePackWorkScheduler());
    }

    ResourcePackDownloadTracker(final ResourcePackArchiveStore archiveStore,
                                final ResourcePackWorkScheduler scheduler) {
        this.archiveStore = archiveStore;
        this.scheduler = scheduler;
    }

    public Download add(final String key, final long size, final long chunkSize, final byte[] hash,
                        final boolean premium, final PackType type) {
        return this.add(key, size, chunkSize, hash, premium, type, null);
    }

    public Download add(final String key, final long size, final long chunkSize, final byte[] hash,
                        final boolean premium, final PackType type,
                        final ResourcePackArchiveStore.Claim archiveClaim) {
        return this.add(key, null, size, chunkSize, hash, premium, type, archiveClaim);
    }

    public Download add(final String key, final ResourcePack.Key declaredKey,
                        final long size, final long chunkSize, final byte[] hash,
                        final boolean premium, final PackType type,
                        final ResourcePackArchiveStore.Claim archiveClaim) {
        if (this.closed.get()) {
            this.abandonClosedClaim(archiveClaim);
            throw new CancellationException("Resource pack download connection is closed");
        }
        final int chunkCount = validateMetadata(size, chunkSize, hash);

        final Path tempFile;
        try {
            tempFile = archiveClaim != null
                    ? this.requireArchiveStore().createRawTemp(archiveClaim)
                    : Files.createTempFile("viabedrock-pack-", ".mcpack.tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create resource pack download file", e);
        }

        final Download download;
        try {
            download = new Download(declaredKey, hash, premium, type, size, chunkSize,
                    chunkCount, tempFile, archiveClaim);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw new UncheckedIOException("Failed to open resource pack download file", e);
        } catch (RuntimeException | Error e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }

        final Download previous;
        synchronized (this.lifecycleLock) {
            if (this.closed.get()) {
                download.discardAsync(this.scheduler);
                this.abandonClosedClaim(archiveClaim);
                throw new CancellationException("Resource pack download connection is closed");
            }
            previous = this.downloads.put(key, download);
            if (archiveClaim != null) {
                this.pendingArchiveClaims.remove(archiveClaim);
            }
        }
        if (previous != null) {
            this.abort(previous, new IllegalStateException("Resource pack download was replaced: " + key));
        }
        return download;
    }

    public Download get(final String key) {
        synchronized (this.lifecycleLock) {
            return this.downloads.get(key);
        }
    }

    public TransferRegistration registerTransfer(
            final String key, final ResourcePack.Key declaredKey,
            final long size, final long chunkSize, final byte[] hash,
            final boolean premium, final PackType type) {
        final TransferMetadata metadata = new TransferMetadata(
                declaredKey, size, chunkSize, hash, premium, type);
        synchronized (this.lifecycleLock) {
            if (this.closed.get()) {
                throw new CancellationException("Resource pack download connection is closed");
            }
            final TransferMetadata previous = this.transferMetadata.putIfAbsent(key, metadata);
            if (previous == null) return TransferRegistration.NEW;
            if (previous.matches(metadata)) return TransferRegistration.DUPLICATE;
            throw new IllegalStateException(
                    "Conflicting RESOURCE_PACK_DATA_INFO received for " + key);
        }
    }

    public static int validateMetadata(final long size, final long chunkSize, final byte[] hash) {
        final int maxArchiveMiB = ViaBedrock.getConfig() != null
                ? ViaBedrock.getConfig().getResourcePackMaxArchiveMiB() : 2_048;
        final long maxArchiveBytes = (long) maxArchiveMiB * 1024L * 1024L;
        if (size <= 0L || size > maxArchiveBytes) {
            throw new IllegalArgumentException("Resource pack archive exceeds the configured size limit: " + size);
        }
        if (chunkSize <= 0L || chunkSize > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Resource pack chunk size is invalid: " + chunkSize);
        }
        if (hash.length != 32) {
            throw new IllegalArgumentException("Resource pack SHA-256 must contain 32 bytes");
        }

        final long chunkCount = 1L + ((size - 1L) / chunkSize);
        if (chunkCount > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException("Resource pack contains too many chunks: " + chunkCount);
        }
        return Math.toIntExact(chunkCount);
    }

    /** 移除未完成或无人认领的下载，并删除其临时文件。 */
    public void remove(final String key) {
        final Download download;
        synchronized (this.lifecycleLock) {
            download = this.downloads.remove(key);
        }
        if (download != null) {
            this.abort(download, new CancellationException("Resource pack download was removed"));
        }
    }

    /** 把已完成的临时文件的所有权转交给 CAS 发布器。 */
    public Path takeCompleted(final String key) {
        final Download download;
        final Path completedFile;
        synchronized (this.lifecycleLock) {
            download = this.downloads.get(key);
            if (download == null) {
                throw new IllegalStateException("Unknown resource pack download: " + key);
            }
            completedFile = download.releaseCompletedFile();
            this.downloads.remove(key, download);
        }
        return completedFile;
    }

    public void fail(final String key, final Throwable error) {
        final Download download;
        synchronized (this.lifecycleLock) {
            download = this.downloads.remove(key);
        }
        if (download != null) {
            this.abort(download, error);
        }
    }

    /** 取消连接本地的传输工作，而不污染共享的归档失败退避。 */
    public void cancel(final String key, final Throwable reason) {
        final Download download;
        synchronized (this.lifecycleLock) {
            download = this.downloads.remove(key);
        }
        if (download == null) {
            return;
        }
        final CancellationException cancellation = new CancellationException("Resource pack transfer was cancelled");
        if (reason != null) {
            cancellation.initCause(reason);
        }
        this.abort(download, cancellation);
    }

    @Override
    public void onRemove() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        final CancellationException error = new CancellationException("Resource pack download connection closed");
        for (CompletableFuture<?> stage : this.connectionStages) {
            stage.cancel(false);
        }
        this.connectionStages.clear();
        final ArrayList<Download> abandonedDownloads;
        synchronized (this.lifecycleLock) {
            abandonedDownloads = new ArrayList<>(this.downloads.values());
            this.downloads.clear();
            this.transferMetadata.clear();
        }
        for (Download download : abandonedDownloads) {
            this.abort(download, error);
        }
        for (ResourcePackArchiveStore.Claim claim : this.pendingArchiveClaims) {
            this.requireArchiveStore().abandon(claim, error);
        }
        this.pendingArchiveClaims.clear();
    }

    private void abort(final Download download, final Throwable error) {
        download.discardAsync(this.scheduler);
        if (download.archiveClaim() != null && download.archiveClaim().leader()) {
            if (error instanceof CancellationException) {
                this.requireArchiveStore().abandon(download.archiveClaim(), error);
            } else {
                this.requireArchiveStore().fail(download.archiveClaim(), error);
            }
        }
    }

    private ResourcePackArchiveStore requireArchiveStore() {
        if (this.archiveStore == null) {
            throw new IllegalStateException("Resource pack archive store is not initialized");
        }
        return this.archiveStore;
    }

    private void abandonClosedClaim(final ResourcePackArchiveStore.Claim claim) {
        if (claim != null) {
            this.pendingArchiveClaims.remove(claim);
            this.requireArchiveStore().abandon(
                    claim, new CancellationException("Resource pack download connection is closed"));
        }
    }

    public <T> CompletableFuture<T> trackConnectionStage(final CompletableFuture<T> stage) {
        Objects.requireNonNull(stage, "stage");
        this.connectionStages.add(stage);
        stage.whenComplete((result, error) -> this.connectionStages.remove(stage));
        if (this.closed.get() && this.connectionStages.remove(stage)) {
            stage.cancel(false);
        }
        return stage;
    }

    /**
     * 跟踪跟随者认领直至其完成或被提升。返回的 future 与共享飞行任务解绑取消；
     * 调用方应仅在其以 {@code true} 完成时才发起协议区块请求。
     */
    public CompletableFuture<Boolean> trackArchiveClaim(final ResourcePackArchiveStore.Claim claim) {
        Objects.requireNonNull(claim, "claim");
        this.pendingArchiveClaims.add(claim);
        claim.path().whenComplete((path, error) -> this.pendingArchiveClaims.remove(claim));
        if (this.closed.get() && this.pendingArchiveClaims.remove(claim)) {
            this.requireArchiveStore().abandon(
                    claim, new CancellationException("Resource pack download connection already closed"));
        }
        return claim.leadership();
    }

    public static final class Download {

        private final ResourcePack.Key declaredKey;
        private final byte[] hash;
        private final boolean premium;
        private final PackType type;
        private final long size;
        private final long chunkSize;
        private final int chunkCount;
        private final BitSet receivedChunks = new BitSet();
        private final Path tempFile;
        private final ResourcePackArchiveStore.Claim archiveClaim;
        private final Object ioChainLock = new Object();
        private FileChannel channel;
        private CompletableFuture<Void> ioTail = CompletableFuture.completedFuture(null);
        private int receivedChunkCount;
        private int nextChunkRequest;
        private boolean completed;
        private boolean released;
        private volatile boolean cancelled;

        private Download(final ResourcePack.Key declaredKey, final byte[] hash,
                         final boolean premium, final PackType type, final long size,
                         final long chunkSize, final int chunkCount, final Path tempFile,
                         final ResourcePackArchiveStore.Claim archiveClaim) throws IOException {
            this.declaredKey = declaredKey;
            this.hash = hash.clone();
            this.premium = premium;
            this.type = type;
            this.size = size;
            this.chunkSize = chunkSize;
            this.chunkCount = chunkCount;
            this.tempFile = tempFile;
            this.archiveClaim = archiveClaim;
            this.channel = FileChannel.open(tempFile, StandardOpenOption.WRITE);
        }

        /** 在其声明的偏移处写入一个协议区块。最后一块之后返回完成的文件。 */
        public synchronized Path processDataChunk(final long chunk, final long byteOffset, final byte[] data)
                throws IOException {
            return this.processDataChunkResult(chunk, byteOffset, data).completedFile();
        }

        private synchronized ChunkResult processDataChunkResult(
                final long chunk, final long byteOffset, final byte[] data) throws IOException {
            if (this.cancelled) {
                throw new CancellationException("Resource pack download was cancelled");
            }
            if (chunk < 0L || chunk >= this.chunkCount) {
                throw new IllegalStateException("Received out of bounds resource pack chunk: " + chunk);
            }
            final int chunkIndex = Math.toIntExact(chunk);

            final long expectedOffset;
            try {
                expectedOffset = Math.multiplyExact(chunk, this.chunkSize);
            } catch (ArithmeticException e) {
                throw new IllegalStateException("Resource pack chunk offset overflow", e);
            }
            if (byteOffset != expectedOffset) {
                throw new IllegalStateException("Unexpected resource pack chunk offset: " + byteOffset
                        + " != " + expectedOffset);
            }
            final long remaining = this.size - byteOffset;
            if (remaining <= 0L) {
                throw new IllegalStateException("Resource pack chunk starts outside the archive");
            }
            final int expectedLength = Math.toIntExact(Math.min(this.chunkSize, remaining));
            if (data.length != expectedLength) {
                throw new IllegalStateException("Unexpected resource pack chunk length: " + data.length
                        + " != " + expectedLength);
            }
            if (byteOffset + data.length > this.size) {
                throw new IllegalStateException("Resource pack chunk extends beyond the archive");
            }
            if (this.receivedChunks.get(chunkIndex)) {
                if (!this.matchesStoredChunk(byteOffset, data)) {
                    throw new IllegalStateException(
                            "Received conflicting duplicate resource pack chunk: " + chunk);
                }
                return new ChunkResult(this.completed ? this.tempFile : null, false);
            }
            if (this.completed) {
                throw new IllegalStateException("Resource pack download is already complete");
            }

            final ByteBuffer buffer = ByteBuffer.wrap(data);
            long writeOffset = byteOffset;
            while (buffer.hasRemaining()) {
                final int written = this.channel.write(buffer, writeOffset);
                if (written <= 0) {
                    throw new IOException("Failed to make progress writing resource pack chunk");
                }
                writeOffset += written;
            }
            this.receivedChunks.set(chunkIndex);
            this.receivedChunkCount++;

            if (this.receivedChunkCount != this.chunkCount) {
                return new ChunkResult(null, true);
            }
            this.channel.close();
            this.channel = null;
            this.completed = true;
            return new ChunkResult(this.tempFile, true);
        }

        private boolean matchesStoredChunk(final long byteOffset, final byte[] data) throws IOException {
            try (FileChannel stored = FileChannel.open(this.tempFile, StandardOpenOption.READ)) {
                final ByteBuffer comparison = ByteBuffer.allocate(Math.min(data.length, 64 * 1024));
                int compared = 0;
                while (compared < data.length) {
                    comparison.clear();
                    comparison.limit(Math.min(comparison.capacity(), data.length - compared));
                    int read = 0;
                    while (comparison.hasRemaining()) {
                        final int count = stored.read(comparison, byteOffset + compared + read);
                        if (count < 0) return false;
                        if (count == 0) continue;
                        read += count;
                    }
                    comparison.flip();
                    while (comparison.hasRemaining()) {
                        if (comparison.get() != data[compared++]) return false;
                    }
                }
                return true;
            }
        }

        /** 在有界共享 IO 执行器上串行化文件写入。 */
        public CompletableFuture<ChunkResult> processDataChunkAsync(final ResourcePackWorkScheduler scheduler,
                                                                   final long chunk, final long byteOffset,
                                                                   final byte[] data) {
            synchronized (this.ioChainLock) {
                final CompletableFuture<ChunkResult> write = this.ioTail.thenCompose(
                        ignored -> scheduler.submitIo(() -> this.processDataChunkResult(chunk, byteOffset, data)));
                this.ioTail = write.thenApply(ignored -> null);
                return write;
            }
        }

        /** 校验非 CAS 下载，且不在堆中实际展开归档。 */
        public synchronized void verifyCompletedHash() throws IOException {
            if (!this.completed) {
                throw new IllegalStateException("Resource pack download is incomplete");
            }
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            final byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(this.tempFile)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (this.cancelled) {
                        throw new CancellationException("Resource pack download was cancelled");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            if (!Arrays.equals(this.hash, digest.digest())) {
                this.discard();
                throw new IllegalStateException("Resource pack archive hash mismatch");
            }
        }

        public CompletableFuture<Void> verifyCompletedHashAsync(final ResourcePackWorkScheduler scheduler) {
            synchronized (this.ioChainLock) {
                final CompletableFuture<Void> verification = this.ioTail.thenCompose(
                        ignored -> scheduler.runIo(() -> {
                            try {
                                this.verifyCompletedHash();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }));
                this.ioTail = verification;
                return verification;
            }
        }

        /** 重建旧式的每连接内存资源包，不涉及共享 CAS。 */
        public CompletableFuture<ResourcePack> loadCompletedLegacyPackAsync(
                final ResourcePackWorkScheduler scheduler) {
            synchronized (this.ioChainLock) {
                final CompletableFuture<ResourcePack> load = this.ioTail.thenCompose(
                                ignored -> scheduler.submitIo(() -> {
                                    this.verifyCompletedHash();
                                    if (this.cancelled) {
                                        throw new CancellationException("Resource pack download was cancelled");
                                    }
                                    return Files.readAllBytes(this.tempFile);
                                }))
                        .thenCompose(archive -> scheduler.submitCpu(() -> {
                            if (this.cancelled) {
                                throw new CancellationException("Resource pack download was cancelled");
                            }
                            return new ResourcePack(new ZipContent(archive));
                        }));
                this.ioTail = load.thenApply(ignored -> null);
                return load;
            }
        }

        private synchronized Path releaseCompletedFile() {
            if (!this.completed) {
                throw new IllegalStateException("Resource pack download is incomplete");
            }
            if (this.released) {
                throw new IllegalStateException("Resource pack download file was already released");
            }
            this.released = true;
            return this.tempFile;
        }

        private void discardAsync(final ResourcePackWorkScheduler scheduler) {
            this.cancelled = true;
            if (scheduler == null) {
                this.discard();
                return;
            }
            final CompletableFuture<Void> cleanup;
            synchronized (this.ioChainLock) {
                cleanup = this.ioTail.handle((ignored, error) -> null)
                        .thenCompose(ignored -> scheduler.runIo(this::discard));
                this.ioTail = cleanup;
            }
            cleanup.exceptionally(error -> {
                this.discard();
                return null;
            });
        }

        private synchronized void discard() {
            if (this.channel != null) {
                try {
                    this.channel.close();
                } catch (IOException ignored) {
                }
                this.channel = null;
            }
            if (!this.released) {
                try {
                    Files.deleteIfExists(this.tempFile);
                } catch (IOException ignored) {
                }
            }
        }

        public byte[] hash() {
            return this.hash.clone();
        }

        public ResourcePack.Key declaredKey() {
            return this.declaredKey;
        }

        public boolean premium() {
            return this.premium;
        }

        public PackType type() {
            return this.type;
        }

        public long size() {
            return this.size;
        }

        public long chunkSize() {
            return this.chunkSize;
        }

        public int chunkCount() {
            return this.chunkCount;
        }

        /** 认领一个尚未请求的区块 id；当整个请求窗口都已发出时返回 {@code -1}。 */
        public synchronized long claimNextChunkRequest() {
            if (this.nextChunkRequest >= this.chunkCount) {
                return -1L;
            }
            return this.nextChunkRequest++;
        }

        public Path tempFile() {
            return this.tempFile;
        }

        public ResourcePackArchiveStore.Claim archiveClaim() {
            return this.archiveClaim;
        }

        public record ChunkResult(Path completedFile, boolean acceptedNewChunk) {
        }

    }

    public enum TransferRegistration {
        NEW,
        DUPLICATE
    }

    private record TransferMetadata(ResourcePack.Key declaredKey, long size, long chunkSize,
                                    byte[] hash, boolean premium, PackType type) {

        private TransferMetadata {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(type, "type");
            hash = hash.clone();
        }

        private boolean matches(final TransferMetadata other) {
            return Objects.equals(this.declaredKey, other.declaredKey)
                    && this.size == other.size
                    && this.chunkSize == other.chunkSize
                    && Arrays.equals(this.hash, other.hash)
                    && this.premium == other.premium
                    && this.type == other.type;
        }
    }

}
