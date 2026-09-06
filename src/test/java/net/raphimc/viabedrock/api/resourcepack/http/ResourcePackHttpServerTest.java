/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import com.viaversion.viaversion.libs.gson.JsonParser;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.DirectoryContent;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactLease;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactRef;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackHttpServerTest {

    @Test
    void embeddedManifestPreservesAuthoritativeBottomToTopOrder(
            @TempDir final Path tempDir) throws Exception {
        final UUID bottomId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID topId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final ResourcePack bottom = testPack(tempDir.resolve("bottom"), bottomId, "bottom");
        final ResourcePack top = testPack(tempDir.resolve("top"), topId, "top");
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of(top, bottom));

        final var manifest = JsonParser.parseString(new String(
                ResourcePackHttpServer.buildBedrockPackStackManifest(storage),
                StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals("bottom_to_top", manifest.get("order").getAsString());
        assertEquals("bedrock/" + bottomId + ".mcpack", manifest.getAsJsonArray("packs")
                .get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("bedrock/" + topId + ".mcpack", manifest.getAsJsonArray("packs")
                .get(1).getAsJsonObject().get("path").getAsString());
    }

    @Test
    void embeddedManifestKeepsOnlyTheTopmostVersionOfRepeatedPackIdentity(
            @TempDir final Path tempDir) throws Exception {
        final UUID repeatedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID middleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final ResourcePack bottom = testPack(
                tempDir.resolve("bottom"), repeatedId, "bottom", "1,0,0");
        final ResourcePack middle = testPack(
                tempDir.resolve("middle"), middleId, "middle", "1,0,0");
        final ResourcePack top = testPack(
                tempDir.resolve("top"), repeatedId, "top", "2,0,0");
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(
                List.of(top, middle, bottom));

        final List<ResourcePack> embedded =
                ResourcePackHttpServer.embeddedPackStackBottomToTop(storage);
        final var manifest = JsonParser.parseString(new String(
                ResourcePackHttpServer.buildBedrockPackStackManifest(storage),
                StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals(List.of(middle, top), embedded);
        assertEquals(2, manifest.getAsJsonArray("packs").size());
        assertEquals("bedrock/" + middleId + ".mcpack", manifest.getAsJsonArray("packs")
                .get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("bedrock/" + repeatedId + ".mcpack", manifest.getAsJsonArray("packs")
                .get(1).getAsJsonObject().get("path").getAsString());
        assertEquals("2.0.0", manifest.getAsJsonArray("packs")
                .get(1).getAsJsonObject().get("version").getAsString());
        assertEquals("top", manifest.getAsJsonArray("packs")
                .get(1).getAsJsonObject().get("name").getAsString());
    }

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void extractsOnlyContentAddressedArtifactPaths() {
        final String hash = "0123456789abcdef0123456789abcdef01234567";

        assertEquals(hash, ResourcePackHttpServer.artifactHash("/packs/" + hash + ".zip"));
        assertNull(ResourcePackHttpServer.artifactHash("/packs/not-a-hash.zip"));
        assertNull(ResourcePackHttpServer.artifactHash("/other/" + hash + ".zip"));
    }

    @Test
    void parsesFullAndOpenEndedRanges() {
        final ResourcePackHttpServer.HttpByteRange full = ResourcePackHttpServer.parseRange(null, 100);
        assertEquals(0, full.start());
        assertEquals(99, full.end());
        assertEquals(100, full.length());
        assertFalse(full.partial());

        final ResourcePackHttpServer.HttpByteRange openEnded = ResourcePackHttpServer.parseRange("bytes=40-", 100);
        assertEquals(40, openEnded.start());
        assertEquals(99, openEnded.end());
        assertEquals(60, openEnded.length());
        assertTrue(openEnded.partial());
    }

    @Test
    void parsesBoundedAndSuffixRanges() {
        final ResourcePackHttpServer.HttpByteRange bounded = ResourcePackHttpServer.parseRange("bytes=4-7", 16);
        assertEquals(4, bounded.start());
        assertEquals(7, bounded.end());

        final ResourcePackHttpServer.HttpByteRange suffix = ResourcePackHttpServer.parseRange("bytes=-4", 16);
        assertEquals(12, suffix.start());
        assertEquals(15, suffix.end());
    }

    @Test
    void rejectsInvalidOrUnsatisfiableRanges() {
        assertNull(ResourcePackHttpServer.parseRange("bytes=100-200", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=8-4", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=0-1,4-5", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=-0", 100));
        assertNull(ResourcePackHttpServer.parseRange(null, 0));
    }

    @Test
    void addsImmutableEtagAndRangeHeaders() {
        final String hash = HASH;

        final DefaultHttpResponse response = ResourcePackHttpServer.artifactResponse(HttpResponseStatus.OK, hash);

        assertEquals('"' + hash + '"', response.headers().get(HttpHeaderNames.ETAG));
        assertEquals(HttpHeaderValues.BYTES.toString(), response.headers().get(HttpHeaderNames.ACCEPT_RANGES));
        assertEquals("public, max-age=31536000, immutable", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
    }

    @Test
    void getStreamsArtifactFromFileInMultipleChunks(@TempDir final Path tempDir) throws Exception {
        final byte[] artifactBytes = artifactBytes(150_000);
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes);
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger completionCount = new AtomicInteger();

        final CapturedResponse response = exchange(
                artifact, HttpMethod.GET, headers -> {
                }, trackingOpener(openCount, closeCount), completionCount::incrementAndGet);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("application/zip", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals(Integer.toString(artifactBytes.length), response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals('"' + HASH + '"', response.headers().get(HttpHeaderNames.ETAG));
        assertArrayEquals(artifactBytes, response.body());
        assertTrue(response.dataChunks() >= 3, "the artifact should be emitted as bounded file chunks");
        assertEquals(1, openCount.get());
        assertEquals(1, closeCount.get());
        assertEquals(1, completionCount.get());
    }

    @Test
    void headReturnsArtifactHeadersWithoutOpeningFile(@TempDir final Path tempDir) throws Exception {
        final byte[] artifactBytes = artifactBytes(128);
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes);
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger completionCount = new AtomicInteger();

        final CapturedResponse response = exchange(
                artifact, HttpMethod.HEAD, headers -> {
                }, trackingOpener(openCount, closeCount), completionCount::incrementAndGet);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(Integer.toString(artifactBytes.length), response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, response.body().length);
        assertEquals(0, response.dataChunks());
        assertEquals(0, openCount.get());
        assertEquals(0, closeCount.get());
        assertEquals(1, completionCount.get());
    }

    @Test
    void rangeReturnsOnlyRequestedFileRegion(@TempDir final Path tempDir) throws Exception {
        final byte[] artifactBytes = artifactBytes(64);
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes);
        final AtomicInteger closeCount = new AtomicInteger();

        final CapturedResponse response = exchange(
                artifact, HttpMethod.GET,
                headers -> headers.set(HttpHeaderNames.RANGE, "bytes=9-21"),
                trackingOpener(new AtomicInteger(), closeCount));

        assertEquals(HttpResponseStatus.PARTIAL_CONTENT, response.status());
        assertEquals("bytes 9-21/64", response.headers().get(HttpHeaderNames.CONTENT_RANGE));
        assertEquals("13", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertArrayEquals(Arrays.copyOfRange(artifactBytes, 9, 22), response.body());
        assertEquals(1, closeCount.get());
    }

    @Test
    void matchingEtagReturnsNotModifiedWithoutOpeningFile(@TempDir final Path tempDir) throws Exception {
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes(32));
        final AtomicInteger openCount = new AtomicInteger();

        final CapturedResponse response = exchange(
                artifact, HttpMethod.GET,
                headers -> headers.set(HttpHeaderNames.IF_NONE_MATCH, '"' + HASH + '"'),
                trackingOpener(openCount, new AtomicInteger()));

        assertEquals(HttpResponseStatus.NOT_MODIFIED, response.status());
        assertEquals("0", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, response.body().length);
        assertEquals(0, openCount.get());
    }

    @Test
    void unsatisfiableRangeReturnsContentRangeWithoutOpeningFile(@TempDir final Path tempDir) throws Exception {
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes(32));
        final AtomicInteger openCount = new AtomicInteger();

        final CapturedResponse response = exchange(
                artifact, HttpMethod.GET,
                headers -> headers.set(HttpHeaderNames.RANGE, "bytes=32-64"),
                trackingOpener(openCount, new AtomicInteger()));

        assertEquals(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.status());
        assertEquals("bytes */32", response.headers().get(HttpHeaderNames.CONTENT_RANGE));
        assertEquals("0", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, response.body().length);
        assertEquals(0, openCount.get());
    }

    @Test
    void failedChunkWriteClosesArtifactFile(@TempDir final Path tempDir) throws Exception {
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes(150_000));
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger completionCount = new AtomicInteger();
        final ArtifactRef artifactRef = artifactRef(artifact);
        final ResourcePackHttpServer.ArtifactFileOpener opener =
                trackingOpener(new AtomicInteger(), closeCount);
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                        if (msg instanceof HttpContent content && content.content().isReadable()) {
                            ReferenceCountUtil.release(msg);
                            promise.setFailure(new IOException("simulated body write failure"));
                            return;
                        }
                        ctx.write(msg, promise);
                    }
                },
                new ChunkedWriteHandler(),
                artifactHandler(artifactRef, opener, completionCount::incrementAndGet));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            channel.runPendingTasks();

            assertEquals(1, closeCount.get());
            assertEquals(1, completionCount.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void openerFailureDoesNotEmitSuccessHeadersAndReleasesLease(@TempDir final Path tempDir) throws Exception {
        final Path artifact = Files.write(tempDir.resolve("artifact.zip"), artifactBytes(64));
        final AtomicInteger completionCount = new AtomicInteger();
        final List<HttpResponseStatus> statuses = new ArrayList<>();
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ChunkedWriteHandler(),
                new SimpleChannelInboundHandler<HttpRequest>() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpRequest request) {
                        try {
                            ResourcePackHttpServer.serveArtifact(
                                    ctx, request, artifactRef(artifact),
                                    file -> {
                                        throw new IOException("simulated open failure");
                                    }, completionCount::incrementAndGet);
                        } catch (IOException expected) {
                            final DefaultHttpResponse response = new DefaultHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                            ctx.writeAndFlush(response);
                        }
                    }
                });
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    if (outbound instanceof DefaultHttpResponse response) {
                        statuses.add(response.status());
                    }
                } finally {
                    ReferenceCountUtil.release(outbound);
                }
            }

            assertEquals(List.of(HttpResponseStatus.INTERNAL_SERVER_ERROR), statuses);
            assertEquals(1, completionCount.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void httpEventLoopUsesBoundedIoWorkerCountAndDaemonThreads() throws Exception {
        final NioEventLoopGroup group = ResourcePackHttpServer.createEventLoopGroup(8);
        try {
            assertEquals(4L, StreamSupport.stream(group.spliterator(), false).count());
            final ThreadSnapshot thread = group.submit(() -> {
                final Thread current = Thread.currentThread();
                return new ThreadSnapshot(current.getName(), current.isDaemon());
            }).get(2L, TimeUnit.SECONDS);

            assertTrue(thread.daemon());
            assertTrue(thread.name().startsWith("ViaBedrock Pack HTTP"));
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }

        final NioEventLoopGroup minimum = ResourcePackHttpServer.createEventLoopGroup(1);
        try {
            assertEquals(2L, StreamSupport.stream(minimum.spliterator(), false).count());
        } finally {
            minimum.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void requestTimeoutDoesNotCompleteSharedFuture() throws Exception {
        final CompletableFuture<String> shared = new CompletableFuture<>();
        final CompletableFuture<String> request = ResourcePackHttpServer.withDetachedTimeout(shared, 25, TimeUnit.MILLISECONDS);

        final ExecutionException error = assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertFalse(shared.isDone());

        shared.complete("ready");
        assertEquals("ready", shared.get(2, TimeUnit.SECONDS));
    }

    @Test
    void requestTimeoutCleansUpLateLeaseValue() throws Exception {
        final CompletableFuture<String> shared = new CompletableFuture<>();
        final AtomicInteger cleaned = new AtomicInteger();
        final CompletableFuture<String> request = ResourcePackHttpServer.withDetachedTimeout(
                shared, 25, TimeUnit.MILLISECONDS, ignored -> cleaned.incrementAndGet());

        assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
        shared.complete("late");

        assertEquals(1, cleaned.get());
    }

    @Test
    void tokenPendingLimitRejectsWith429AndReleasesAdmissionOnFailure() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackHttpServer.PendingHttpRequestLimiter limiter =
                new ResourcePackHttpServer.PendingHttpRequestLimiter(2, 8, metrics);
        final ResourcePackHttpServer.TokenState token = new ResourcePackHttpServer.TokenState(limiter);
        token.complete(ResourcePackStorage.createUnshared(List.of()));
        final List<CompletableFuture<ArtifactLease>> shared = new ArrayList<>();

        final CompletableFuture<ArtifactLease> first = token.request(ignored -> {
            final CompletableFuture<ArtifactLease> future = new CompletableFuture<>();
            shared.add(future);
            return future;
        });
        final CompletableFuture<ArtifactLease> second = token.request(ignored -> {
            final CompletableFuture<ArtifactLease> future = new CompletableFuture<>();
            shared.add(future);
            return future;
        });
        final CompletableFuture<ArtifactLease> rejected = token.request(
                ignored -> CompletableFuture.failedFuture(new AssertionError("must not start")));

        final ExecutionException rejection = assertThrows(
                ExecutionException.class, () -> rejected.get(2L, TimeUnit.SECONDS));
        assertInstanceOf(ResourcePackHttpServer.PendingHttpRequestRejectedException.class,
                rejection.getCause());
        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS,
                ResourcePackHttpServer.httpFailureStatus(rejection.getCause()));
        assertEquals(2, token.pendingRequests());
        assertEquals(2, limiter.pending());
        assertEquals(2L, metrics.getPendingHttpRequests());
        assertEquals(1L, metrics.getHttpTokenRequestRejections());

        token.fail(new CancellationException("test complete"));
        assertThrows(CancellationException.class, () -> first.get(2L, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> second.get(2L, TimeUnit.SECONDS));
        assertEquals(0, limiter.pending());
        assertEquals(0L, metrics.getPendingHttpRequests());
        assertTrue(shared.stream().noneMatch(CompletableFuture::isCancelled));
    }

    @Test
    void globalPendingLimitRejectsAcrossTokensWith503() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackHttpServer.PendingHttpRequestLimiter limiter =
                new ResourcePackHttpServer.PendingHttpRequestLimiter(4, 1, metrics);
        final ResourcePackHttpServer.TokenState firstToken = new ResourcePackHttpServer.TokenState(limiter);
        final ResourcePackHttpServer.TokenState secondToken = new ResourcePackHttpServer.TokenState(limiter);
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of());
        firstToken.complete(storage);
        secondToken.complete(storage);

        final CompletableFuture<ArtifactLease> shared = new CompletableFuture<>();
        final CompletableFuture<ArtifactLease> admitted = firstToken.request(ignored -> shared);
        final CompletableFuture<ArtifactLease> rejected = secondToken.request(
                ignored -> CompletableFuture.failedFuture(new AssertionError("must not start")));

        final ExecutionException rejection = assertThrows(
                ExecutionException.class, () -> rejected.get(2L, TimeUnit.SECONDS));
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE,
                ResourcePackHttpServer.httpFailureStatus(rejection.getCause()));
        assertEquals(1L, metrics.getHttpGlobalRequestRejections());
        assertEquals(1L, metrics.getMaxPendingHttpRequestsGlobal());
        assertEquals(4L, metrics.getMaxPendingHttpRequestsPerToken());

        firstToken.fail(new CancellationException("test complete"));
        secondToken.fail(new CancellationException("test complete"));
        assertThrows(CancellationException.class, () -> admitted.get(2L, TimeUnit.SECONDS));
        assertFalse(shared.isDone());
        assertEquals(0, limiter.pending());
    }

    @Test
    void removesTemporaryDirectoryTrees(@TempDir final Path tempDir) throws Exception {
        final Path workDirectory = Files.createDirectories(tempDir.resolve("work/nested"));
        Files.writeString(workDirectory.resolve("artifact.tmp"), "temporary");

        ResourcePackHttpServer.deleteRecursively(tempDir.resolve("work"));

        assertFalse(Files.exists(tempDir.resolve("work")));
    }

    @Test
    void fileBackedConversionMatchesLegacyEntriesAndIsSingleFlight(
            @TempDir final Path tempDir) throws Exception {
        final UUID packId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        final Path packDirectory = Files.createDirectories(tempDir.resolve("source-pack/texts"));
        Files.writeString(tempDir.resolve("source-pack/manifest.json"), """
                {"format_version":2,"header":{"uuid":"%s","version":[1,0,0],"name":"small"}}
                """.formatted(packId), StandardCharsets.UTF_8);
        Files.writeString(packDirectory.resolve("en_US.lang"), "test.key=Small pack\n",
                StandardCharsets.UTF_8);
        final ResourcePack pack = new ResourcePack(
                new DirectoryContent(tempDir.resolve("source-pack").toAbsolutePath()));
        final ResourcePackStorage legacyStorage = ResourcePackStorage.createUnshared(List.of(pack));
        final Content legacy = ResourcePackRewriter.bedrockToJava(legacyStorage);

        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = testScheduler(metrics);
        try {
            final JavaPackCache cache = new JavaPackCache(
                    tempDir.resolve("cache").toFile(), scheduler, metrics);
            final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of(pack));
            final AtomicInteger conversions = new AtomicInteger();
            final CountDownLatch conversionStarted = new CountDownLatch(1);
            final CountDownLatch releaseConversion = new CountDownLatch(1);
            final List<CompletableFuture<ArtifactRef>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                requests.add(cache.getOrBuildHashed("e".repeat(64), target -> {
                    conversions.incrementAndGet();
                    conversionStarted.countDown();
                    if (!releaseConversion.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release conversion");
                    }
                    try (JavaPackCache.BuildWorkspace workspace = cache.openBuildWorkspace()) {
                        return ResourcePackHttpServer.writeJavaPackArtifact(
                                storage, workspace.path(), target);
                    }
                }));
            }
            assertTrue(conversionStarted.await(10L, TimeUnit.SECONDS));
            releaseConversion.countDown();

            final ArtifactRef artifact = requests.getFirst().get(10L, TimeUnit.SECONDS);
            for (CompletableFuture<ArtifactRef> request : requests) {
                assertEquals(artifact, request.get(10L, TimeUnit.SECONDS));
            }
            assertEquals(1, conversions.get());
            assertEquals(1L, metrics.getArtifactBuilds());
            assertEquals(19L, metrics.getArtifactWaiters());
            assertEquals(Files.size(artifact.path()), artifact.size());
            assertEquals(sha1(artifact.path()), artifact.hash());

            final Map<String, byte[]> convertedEntries = zipEntries(artifact.path());
            for (String path : legacy.getFilesDeep("", "")) {
                assertArrayEquals(legacy.get(path), convertedEntries.get(path),
                        "file-backed output changed legacy entry " + path);
            }
            final String embeddedPath = "bedrock/" + packId + ".mcpack";
            final Set<String> expectedPaths = new java.util.HashSet<>(legacy.getFilesDeep("", ""));
            expectedPaths.add(embeddedPath);
            expectedPaths.add(ResourcePackHttpServer.BEDROCK_PACK_STACK_PATH);
            assertEquals(expectedPaths, convertedEntries.keySet());
            assertTrue(convertedEntries.containsKey(embeddedPath));
            final var stackManifest = JsonParser.parseString(new String(
                    convertedEntries.get(ResourcePackHttpServer.BEDROCK_PACK_STACK_PATH),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1, stackManifest.get("format_version").getAsInt());
            assertEquals("bottom_to_top", stackManifest.get("order").getAsString());
            assertEquals(1, stackManifest.getAsJsonArray("packs").size());
            assertEquals(embeddedPath, stackManifest.getAsJsonArray("packs").get(0)
                    .getAsJsonObject().get("path").getAsString());
            final Map<String, byte[]> embeddedEntries = zipEntries(convertedEntries.get(embeddedPath));
            assertArrayEquals(Files.readAllBytes(tempDir.resolve("source-pack/manifest.json")),
                    embeddedEntries.get("manifest.json"));
            assertArrayEquals(Files.readAllBytes(packDirectory.resolve("en_US.lang")),
                    embeddedEntries.get("texts/en_US.lang"));
            try (var workspaces = Files.list(tempDir.resolve("cache/work"))) {
                assertEquals(0L, workspaces.count());
            }
            assertEquals(0L, metrics.getActiveArtifactBuildWorkspaces());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void tokenBuildsArtifactOnceAndCreatesIndependentRequestLeases(@TempDir final Path tempDir) throws Exception {
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(java.util.List.of());
        final AtomicInteger builds = new AtomicInteger();
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackHttpServer.TokenState token = new ResourcePackHttpServer.TokenState(
                new ResourcePackHttpServer.PendingHttpRequestLimiter(32, 64, metrics));
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics);
        final String cacheKey = "a".repeat(64);
        final CountDownLatch requestsEntered = new CountDownLatch(20);
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch releaseBuild = new CountDownLatch(1);
        final java.util.function.Function<ResourcePackStorage, CompletableFuture<ArtifactLease>> builder = value -> {
            assertEquals(storage, value);
            return CompletableFuture.supplyAsync(() -> {
                requestsEntered.countDown();
                return cache.getOrBuildLease(cacheKey, target -> {
                    builds.incrementAndGet();
                    buildStarted.countDown();
                    if (!releaseBuild.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release artifact build");
                    }
                    Files.write(target, new byte[]{1});
                });
            }).thenCompose(future -> future);
        };
        token.complete(storage);
        assertEquals(0L, metrics.getActiveArtifactLeases(),
                "An online token without an HTTP request must not pin an artifact");

        final List<CompletableFuture<ArtifactLease>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(token.request(builder));
        }
        try {
            assertTrue(buildStarted.await(10L, TimeUnit.SECONDS));
            assertTrue(requestsEntered.await(10L, TimeUnit.SECONDS));
        } finally {
            releaseBuild.countDown();
        }

        final List<ArtifactLease> leases = new ArrayList<>();
        for (CompletableFuture<ArtifactLease> request : requests) {
            leases.add(request.get(2, TimeUnit.SECONDS));
        }
        final ArtifactRef expected = cache.getArtifact(cacheKey);
        leases.forEach(lease -> assertEquals(expected, lease.artifact()));
        assertEquals(1, builds.get());
        assertEquals(20L, metrics.getActiveArtifactLeases());
        leases.forEach(ArtifactLease::close);
        assertEquals(0L, metrics.getActiveArtifactLeases());

        try (ArtifactLease retry = token.request(builder).get(2, TimeUnit.SECONDS)) {
            assertEquals(expected, retry.artifact());
            assertEquals(1, builds.get(), "A later Range/retry request must hot-acquire the artifact");
            assertEquals(1L, metrics.getActiveArtifactLeases());
        }
        assertEquals(0L, metrics.getActiveArtifactLeases());
        token.fail(new CancellationException("test complete"));
    }

    @Test
    void requestLeaseClosesCleanerGapWithoutTokenPublicationPin(
            @TempDir final Path tempDir) throws Exception {
        final ResourcePackHttpServer.TokenState token = new ResourcePackHttpServer.TokenState();
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(java.util.List.of());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch acquisitionStarted = new CountDownLatch(1);
        final CountDownLatch releaseAcquisition = new CountDownLatch(1);
        final AtomicBoolean blockAcquisition = new AtomicBoolean();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics) {
            @Override
            public ArtifactLease acquireArtifact(final ArtifactRef artifact) throws IOException {
                if (blockAcquisition.get()) {
                    acquisitionStarted.countDown();
                    try {
                        if (!releaseAcquisition.await(10L, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to acquire request lease");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Request lease acquisition was interrupted", e);
                    }
                }
                return super.acquireArtifact(artifact);
            }
        };
        final String cacheKey = "d".repeat(64);
        cache.put(cacheKey, new byte[]{1, 2, 3});
        final ArtifactRef expected = cache.getArtifact(cacheKey);
        final AtomicInteger unexpectedBuilds = new AtomicInteger();

        blockAcquisition.set(true);
        final CompletableFuture<ArtifactLease> request = token.request(
                ignored -> cache.getOrBuildLease(cacheKey, target -> unexpectedBuilds.incrementAndGet()));
        final CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> token.complete(storage));
        assertTrue(acquisitionStarted.await(10L, TimeUnit.SECONDS));

        ageArtifact(tempDir, cacheKey, expected);
        cleanupNow(cache);
        assertTrue(Files.isRegularFile(expected.path()),
                "Cache validation must pin the artifact until the request lease is acquired");
        assertEquals(0L, metrics.getActiveArtifactLeases());
        assertEquals(0, unexpectedBuilds.get());

        releaseAcquisition.countDown();
        final ArtifactLease independent = request.get(2, TimeUnit.SECONDS);
        completion.get(2, TimeUnit.SECONDS);
        assertEquals(1L, metrics.getActiveArtifactLeases());
        token.fail(new CancellationException("token closed"));
        assertEquals(1L, metrics.getActiveArtifactLeases());

        ageArtifact(tempDir, cacheKey, expected);
        cleanupNow(cache);
        assertTrue(Files.isRegularFile(expected.path()),
                "The streaming request must retain its own independent lease");

        independent.close();
        ageArtifact(tempDir, cacheKey, expected);
        cleanupNow(cache);
        assertFalse(Files.exists(expected.path()));
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void failingTokenDoesNotCancelAnAlreadySharedArtifactBuild(@TempDir final Path tempDir) throws Exception {
        final ResourcePackHttpServer.TokenState token = new ResourcePackHttpServer.TokenState();
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(java.util.List.of());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics);
        final String cacheKey = "b".repeat(64);
        cache.put(cacheKey, new byte[]{1});
        final ArtifactRef expected = cache.getArtifact(cacheKey);
        final CompletableFuture<ArtifactLease> sharedBuild = new CompletableFuture<>();
        final AtomicInteger builds = new AtomicInteger();
        final CompletableFuture<ArtifactLease> request = token.request(value -> {
            assertSame(storage, value);
            builds.incrementAndGet();
            return sharedBuild;
        });
        token.complete(storage);
        assertEquals(1, builds.get());

        final IllegalStateException failure = new IllegalStateException("connection failed");
        token.fail(failure);

        final ExecutionException requestError = assertThrows(
                ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
        assertSame(failure, requestError.getCause());
        assertFalse(sharedBuild.isDone());

        sharedBuild.complete(cache.acquireArtifact(expected));
        assertFalse(sharedBuild.isCancelled());
        assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void closingHttpChannelDetachesOnlyItsPendingRequest(@TempDir final Path tempDir) throws Exception {
        final ResourcePackHttpServer.TokenState token = new ResourcePackHttpServer.TokenState();
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(java.util.List.of());
        final CompletableFuture<ArtifactRef> sharedBuild = new CompletableFuture<>();
        final AtomicInteger builderCalls = new AtomicInteger();
        final AtomicInteger acquisitions = new AtomicInteger();
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics);
        final String cacheKey = "c".repeat(64);
        cache.put(cacheKey, new byte[]{1});
        final ArtifactRef expected = cache.getArtifact(cacheKey);
        final java.util.function.Function<ResourcePackStorage, CompletableFuture<ArtifactLease>> builder = value -> {
            builderCalls.incrementAndGet();
            return sharedBuild.thenApply(artifact -> {
                acquisitions.incrementAndGet();
                try {
                    return cache.acquireArtifact(artifact);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        };
        final CompletableFuture<ArtifactLease> request = token.request(builder);
        final CompletableFuture<ArtifactLease> survivor = token.request(builder);
        token.complete(storage);
        assertEquals(2, builderCalls.get());
        assertEquals(2, token.pendingRequests());

        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.closeFuture().addListener(ignored -> token.cancelRequest(
                    request, new CancellationException("HTTP client disconnected")));
            channel.close().syncUninterruptibly();

            assertThrows(CancellationException.class, () -> request.get(2, TimeUnit.SECONDS));
            assertEquals(1, token.pendingRequests());
            assertFalse(sharedBuild.isDone());

            sharedBuild.complete(expected);
            assertFalse(sharedBuild.isCancelled());
            try (ArtifactLease lease = survivor.get(2, TimeUnit.SECONDS)) {
                assertEquals(expected, lease.artifact());
                assertEquals(1L, metrics.getActiveArtifactLeases());
            }
            assertEquals(2, acquisitions.get());
            assertEquals(0L, metrics.getActiveArtifactLeases());
            assertEquals(0, token.pendingRequests());
        } finally {
            token.fail(new CancellationException("test complete"));
            channel.finishAndReleaseAll();
        }
    }

    private static CapturedResponse exchange(final Path artifact, final HttpMethod method,
                                             final Consumer<HttpHeaders> configureHeaders,
                                             final ResourcePackHttpServer.ArtifactFileOpener opener) throws Exception {
        return exchange(artifact, method, configureHeaders, opener, () -> {
        });
    }

    private static CapturedResponse exchange(final Path artifact, final HttpMethod method,
                                             final Consumer<HttpHeaders> configureHeaders,
                                             final ResourcePackHttpServer.ArtifactFileOpener opener,
                                             final Runnable completion) throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new ChunkedWriteHandler(), artifactHandler(artifactRef(artifact), opener, completion));
        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, "/");
        configureHeaders.accept(request.headers());

        try {
            channel.writeInbound(request);
            channel.runPendingTasks();

            HttpResponseStatus status = null;
            HttpHeaders headers = null;
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            int dataChunks = 0;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    if (outbound instanceof DefaultHttpResponse response) {
                        status = response.status();
                        headers = response.headers().copy();
                    }
                    if (outbound instanceof HttpContent content && content.content().isReadable()) {
                        final byte[] bytes = new byte[content.content().readableBytes()];
                        content.content().getBytes(content.content().readerIndex(), bytes);
                        body.write(bytes);
                        dataChunks++;
                    }
                } finally {
                    ReferenceCountUtil.release(outbound);
                }
            }
            assertNotNull(status);
            assertNotNull(headers);
            return new CapturedResponse(status, headers, body.toByteArray(), dataChunks);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static SimpleChannelInboundHandler<HttpRequest> artifactHandler(
            final ArtifactRef artifact, final ResourcePackHttpServer.ArtifactFileOpener opener) {
        return artifactHandler(artifact, opener, () -> {
        });
    }

    private static SimpleChannelInboundHandler<HttpRequest> artifactHandler(
            final ArtifactRef artifact, final ResourcePackHttpServer.ArtifactFileOpener opener,
            final Runnable completion) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(final ChannelHandlerContext ctx, final HttpRequest request) throws Exception {
                ResourcePackHttpServer.serveArtifact(ctx, request, artifact, opener, completion);
            }
        };
    }

    private static ArtifactRef artifactRef(final Path artifact) throws IOException {
        return new ArtifactRef("key", HASH, artifact, Files.size(artifact));
    }

    private static void ageArtifact(final Path root, final String cacheKey,
                                    final ArtifactRef artifact) throws IOException {
        final FileTime old = FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8L));
        Files.setLastModifiedTime(root.resolve(cacheKey + ".zip"), old);
        Files.setLastModifiedTime(root.resolve(cacheKey + ".sha1"), old);
        Files.setLastModifiedTime(artifact.path(), old);
    }

    private static void cleanupNow(final JavaPackCache cache) throws Exception {
        final var cleanup = JavaPackCache.class.getDeclaredMethod("cleanupNow");
        cleanup.setAccessible(true);
        cleanup.invoke(cache);
    }

    private static ResourcePack testPack(Path directory, UUID id, String name) throws Exception {
        return testPack(directory, id, name, "1,0,0");
    }

    private static ResourcePack testPack(
            Path directory, UUID id, String name, String version) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"), """
                {"format_version":2,"header":{"uuid":"%s","version":[%s],"name":"%s"}}
                """.formatted(id, version, name), StandardCharsets.UTF_8);
        return new ResourcePack(new DirectoryContent(directory.toAbsolutePath()));
    }

    private static ResourcePackHttpServer.ArtifactFileOpener trackingOpener(
            final AtomicInteger openCount, final AtomicInteger closeCount) {
        return file -> {
            openCount.incrementAndGet();
            return new TrackingRandomAccessFile(file, closeCount);
        };
    }

    private static byte[] artifactBytes(final int length) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static ResourcePackWorkScheduler testScheduler(final ResourcePackCacheMetrics metrics) {
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers", "getResourcePackCacheIoWorkers" -> 2;
                    case "getResourcePackCacheQueueCapacity" -> 64;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return new ResourcePackWorkScheduler(config, metrics);
    }

    private static Map<String, byte[]> zipEntries(final Path zip) throws IOException {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            final Enumeration<? extends ZipEntry> zipEntries = file.entries();
            while (zipEntries.hasMoreElements()) {
                final ZipEntry entry = zipEntries.nextElement();
                if (!entry.isDirectory()) {
                    try (var input = file.getInputStream(entry)) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private static Map<String, byte[]> zipEntries(final byte[] zip) throws IOException {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
                input.closeEntry();
            }
        }
        return entries;
    }

    private static String sha1(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record CapturedResponse(HttpResponseStatus status, HttpHeaders headers, byte[] body, int dataChunks) {
    }

    private record ThreadSnapshot(String name, boolean daemon) {
    }

    private static final class TrackingRandomAccessFile extends RandomAccessFile {

        private final AtomicInteger closeCount;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingRandomAccessFile(final File file, final AtomicInteger closeCount) throws IOException {
            super(file, "r");
            this.closeCount = closeCount;
        }

        @Override
        public void close() throws IOException {
            if (this.closed.compareAndSet(false, true)) {
                this.closeCount.incrementAndGet();
            }
            super.close();
        }
    }

}
