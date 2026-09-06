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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.DirectoryContent;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactLease;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache.ArtifactRef;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ResourcePackHttpServer {

    public static final String BEDROCK_PACK_STACK_PATH = "bedrock/pack_stack.json";
    private static final Pattern ARTIFACT_PATH = Pattern.compile("^/packs/([0-9a-f]{40})\\.zip$");
    private static final Pattern BYTE_RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");
    private static final ArtifactFileOpener ARTIFACT_FILE_OPENER = file -> new RandomAccessFile(file, "r");
    private static final int MIN_HTTP_EVENT_LOOP_THREADS = 2;
    private static final int MAX_HTTP_EVENT_LOOP_THREADS = 4;
    private static final int MAX_PENDING_REQUESTS_PER_TOKEN = 8;
    private static final int MAX_PENDING_REQUESTS_GLOBAL = 4_096;

    private final InetSocketAddress bindAddress;
    private final EventLoopGroup eventLoopGroup;
    private final ChannelFuture channelFuture;
    private final Map<UUID, TokenState> connections = new ConcurrentHashMap<>();
    private final PendingHttpRequestLimiter pendingRequests;

    static final class PendingHttpRequestLimiter {
        private final int perTokenLimit;
        private final int globalLimit;
        private final ResourcePackCacheMetrics metrics;
        private final AtomicInteger globalPending = new AtomicInteger();

        PendingHttpRequestLimiter(final int perTokenLimit, final int globalLimit,
                                  final ResourcePackCacheMetrics metrics) {
            this.perTokenLimit = Math.max(1, perTokenLimit);
            this.globalLimit = Math.max(1, globalLimit);
            this.metrics = metrics;
            if (metrics != null) {
                metrics.httpRequestCapacity(this.perTokenLimit, this.globalLimit);
            }
        }

        boolean tryAcquireGlobal() {
            while (true) {
                final int current = this.globalPending.get();
                if (current >= this.globalLimit) {
                    if (this.metrics != null) this.metrics.httpGlobalRequestRejected();
                    return false;
                }
                if (this.globalPending.compareAndSet(current, current + 1)) {
                    if (this.metrics != null) this.metrics.setPendingHttpRequests(current + 1L);
                    return true;
                }
            }
        }

        void rejectToken() {
            if (this.metrics != null) this.metrics.httpTokenRequestRejected();
        }

        void releaseGlobal() {
            final int remaining = this.globalPending.updateAndGet(value -> Math.max(0, value - 1));
            if (this.metrics != null) this.metrics.setPendingHttpRequests(remaining);
        }

        int perTokenLimit() {
            return this.perTokenLimit;
        }

        int pending() {
            return this.globalPending.get();
        }
    }

    static final class PendingHttpRequestRejectedException extends RuntimeException {
        private final HttpResponseStatus status;

        private PendingHttpRequestRejectedException(final HttpResponseStatus status, final String message) {
            super(message, null, false, false);
            this.status = status;
        }

        HttpResponseStatus status() {
            return this.status;
        }
    }

    static final class TokenState {
        private final PendingHttpRequestLimiter limiter;
        private final Map<CompletableFuture<ArtifactLease>, LeaseRequest> pending = new ConcurrentHashMap<>();
        private WeakReference<ResourcePackStorage> storage;
        private Throwable failure;
        private boolean storageReady;

        TokenState() {
            this(new PendingHttpRequestLimiter(MAX_PENDING_REQUESTS_PER_TOKEN, 64, null));
        }

        TokenState(final PendingHttpRequestLimiter limiter) {
            this.limiter = limiter;
        }

        CompletableFuture<ArtifactLease> request(
                final Function<ResourcePackStorage, CompletableFuture<ArtifactLease>> builder) {
            final CompletableFuture<ArtifactLease> dependent = new CompletableFuture<>();
            final LeaseRequest request = new LeaseRequest(dependent, builder);
            final ResourcePackStorage readyStorage;
            final boolean ready;
            synchronized (this) {
                if (this.failure != null) {
                    return CompletableFuture.failedFuture(this.failure);
                }
                if (this.pending.size() >= this.limiter.perTokenLimit()) {
                    this.limiter.rejectToken();
                    return CompletableFuture.failedFuture(new PendingHttpRequestRejectedException(
                            HttpResponseStatus.TOO_MANY_REQUESTS,
                            "Too many pending resource pack requests for this token"));
                }
                if (!this.limiter.tryAcquireGlobal()) {
                    return CompletableFuture.failedFuture(new PendingHttpRequestRejectedException(
                            HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "Resource pack HTTP request capacity is exhausted"));
                }
                this.pending.put(dependent, request);
                ready = this.storageReady;
                readyStorage = ready && this.storage != null ? this.storage.get() : null;
            }
            dependent.whenComplete((lease, error) -> {
                if (this.pending.remove(dependent) != null) {
                    this.limiter.releaseGlobal();
                }
            });
            if (ready) {
                this.startRequest(request, readyStorage);
            }
            return dependent;
        }

        void cancelRequest(final CompletableFuture<ArtifactLease> request, final Throwable error) {
            if (this.pending.containsKey(request)) {
                request.completeExceptionally(error);
            }
        }

        void complete(final ResourcePackStorage storage) {
            final List<LeaseRequest> requests;
            synchronized (this) {
                if (this.failure != null || this.storageReady) {
                    return;
                }
                this.storage = new WeakReference<>(storage);
                this.storageReady = true;
                requests = List.copyOf(this.pending.values());
            }
            for (LeaseRequest request : requests) {
                this.startRequest(request, storage);
            }
        }

        void fail(final Throwable error) {
            synchronized (this) {
                if (this.failure != null) {
                    return;
                }
                this.failure = error;
                this.storage = null;
            }
            for (CompletableFuture<ArtifactLease> request : List.copyOf(this.pending.keySet())) {
                request.completeExceptionally(error);
            }
        }

        int pendingRequests() {
            return this.pending.size();
        }

        private void startRequest(final LeaseRequest request, final ResourcePackStorage storage) {
            if (!request.started.compareAndSet(false, true) || request.dependent.isDone()) {
                return;
            }
            if (storage == null) {
                request.dependent.completeExceptionally(new CancellationException(
                        "Resource pack session ended before artifact lease acquisition"));
                return;
            }
            final CompletableFuture<ArtifactLease> leaseFuture;
            try {
                leaseFuture = request.builder.apply(storage);
                if (leaseFuture == null) {
                    throw new NullPointerException("artifact builder returned null future");
                }
            } catch (Throwable error) {
                request.dependent.completeExceptionally(error);
                return;
            }
            leaseFuture.whenComplete((lease, error) -> {
                if (error != null) {
                    request.dependent.completeExceptionally(error);
                } else if (lease == null) {
                    request.dependent.completeExceptionally(
                            new IOException("artifact builder completed without a request lease"));
                } else if (!request.dependent.complete(lease)) {
                    lease.close();
                }
            });
        }

        private static final class LeaseRequest {
            private final CompletableFuture<ArtifactLease> dependent;
            private final Function<ResourcePackStorage, CompletableFuture<ArtifactLease>> builder;
            private final AtomicBoolean started = new AtomicBoolean();

            private LeaseRequest(final CompletableFuture<ArtifactLease> dependent,
                                 final Function<ResourcePackStorage, CompletableFuture<ArtifactLease>> builder) {
                this.dependent = dependent;
                this.builder = builder;
            }
        }
    }

    private record PendingRequest(HttpMethod method, String range, String ifNoneMatch) {
    }

    public ResourcePackHttpServer(final InetSocketAddress bindAddress) {
        this.bindAddress = bindAddress;
        final int ioWorkers = ViaBedrock.getConfig().getResourcePackCacheIoWorkers();
        final int globalPendingLimit = Math.max(1, Math.min(MAX_PENDING_REQUESTS_GLOBAL,
                ViaBedrock.getConfig().getResourcePackCacheQueueCapacity()));
        this.pendingRequests = new PendingHttpRequestLimiter(
                Math.min(MAX_PENDING_REQUESTS_PER_TOKEN, globalPendingLimit), globalPendingLimit,
                ViaBedrock.getResourcePackCacheMetrics());
        this.eventLoopGroup = createEventLoopGroup(ioWorkers);
        try {
            this.channelFuture = new ServerBootstrap()
                    .group(this.eventLoopGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            channel.pipeline().addLast("http_codec", new HttpServerCodec());
                            channel.pipeline().addLast("chunked_writer", new ChunkedWriteHandler());
                            channel.pipeline().addLast("http_handler", new SimpleChannelInboundHandler<>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof HttpRequest request) {
                                        final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                                        if (queryStringDecoder.path().startsWith("/packs/")) {
                                            try {
                                                ResourcePackHttpServer.this.serveArtifact(ctx, request, queryStringDecoder.path());
                                            } catch (Throwable e) {
                                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to serve cached Java resource pack", e);
                                                sendEmptyResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                                            }
                                            return;
                                        }

                                        if (!request.method().equals(HttpMethod.GET) && !request.method().equals(HttpMethod.HEAD)) {
                                            sendEmptyResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                                            return;
                                        }

                                        if (!queryStringDecoder.parameters().containsKey("token")) {
                                            ctx.close();
                                            return;
                                        }
                                        final UUID uuid = UUID.fromString(queryStringDecoder.parameters().get("token").get(0));
                                        final TokenState tokenState = ResourcePackHttpServer.this.connections.get(uuid);
                                        if (tokenState == null) {
                                            sendEmptyResponse(ctx, HttpResponseStatus.NOT_FOUND);
                                            return;
                                        }
                                        final PendingRequest pendingRequest = new PendingRequest(
                                                request.method(), request.headers().get(HttpHeaderNames.RANGE),
                                                request.headers().get(HttpHeaderNames.IF_NONE_MATCH));
                                        final CompletableFuture<ArtifactLease> artifactFuture = tokenState.request(
                                                ResourcePackHttpServer.this::getOrCreateJavaPackLease);
                                        ctx.channel().closeFuture().addListener(ignored -> tokenState.cancelRequest(
                                                artifactFuture,
                                                new CancellationException("Resource pack HTTP request closed")));
                                        final CompletableFuture<ArtifactLease> responseFuture = withDetachedTimeout(artifactFuture,
                                                ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(),
                                                TimeUnit.SECONDS, ArtifactLease::close);
                                        responseFuture.whenComplete((lease, error) -> {
                                            final Throwable cause = error != null ? unwrap(error) : null;
                                            if (cause instanceof TimeoutException) {
                                                tokenState.cancelRequest(artifactFuture, cause);
                                            }
                                            final Runnable responseTask = () -> {
                                                if (!ctx.channel().isActive()) {
                                                    if (lease != null) lease.close();
                                                    return;
                                                }
                                                if (error != null) {
                                                    if (!(cause instanceof PendingHttpRequestRejectedException)) {
                                                        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Failed to convert resource packs", error);
                                                    }
                                                    sendEmptyResponse(ctx, httpFailureStatus(cause));
                                                    return;
                                                }
                                                try {
                                                    ResourcePackHttpServer.this.serveArtifact(ctx, pendingRequest, lease);
                                                } catch (Throwable e) {
                                                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to serve converted resource pack", e);
                                                    sendEmptyResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                                                }
                                            };
                                            try {
                                                ctx.executor().execute(responseTask);
                                            } catch (Throwable dispatchError) {
                                                if (lease != null) lease.close();
                                                ctx.close();
                                            }
                                        });
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    ctx.close();
                                }
                            });
                        }
                    })
                    .bind(bindAddress)
                    .syncUninterruptibly();
        } catch (Throwable e) {
            this.eventLoopGroup.shutdownGracefully();
            throw e;
        }
    }

    static NioEventLoopGroup createEventLoopGroup(final int configuredIoWorkers) {
        final int threads = Math.max(MIN_HTTP_EVENT_LOOP_THREADS,
                Math.min(MAX_HTTP_EVENT_LOOP_THREADS, configuredIoWorkers));
        return new NioEventLoopGroup(
                threads, new DefaultThreadFactory("ViaBedrock Pack HTTP", true));
    }

    private void serveArtifact(final ChannelHandlerContext ctx, final HttpRequest request, final String path) {
        if (!request.method().equals(HttpMethod.GET) && !request.method().equals(HttpMethod.HEAD)) {
            sendEmptyResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        final String hash = artifactHash(path);
        final JavaPackCache cache = ViaBedrock.getJavaPackCache();
        if (hash == null || cache == null) {
            sendEmptyResponse(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }

        if (ViaBedrock.getResourcePackWorkScheduler() == null) {
            sendEmptyResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
            return;
        }
        if (!this.pendingRequests.tryAcquireGlobal()) {
            sendEmptyResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
            return;
        }
        final PendingRequest pendingRequest = new PendingRequest(
                request.method(), request.headers().get(HttpHeaderNames.RANGE),
                request.headers().get(HttpHeaderNames.IF_NONE_MATCH));
        final CompletableFuture<ArtifactLease> acquisition;
        try {
            acquisition = ViaBedrock.getResourcePackWorkScheduler()
                    .submitIo(() -> cache.acquireArtifact(hash));
        } catch (Throwable error) {
            this.pendingRequests.releaseGlobal();
            sendEmptyResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
            return;
        }
        acquisition.whenComplete((lease, error) -> {
            this.pendingRequests.releaseGlobal();
            final Runnable responseTask = () -> {
                if (!ctx.channel().isActive()) {
                    if (lease != null) lease.close();
                    return;
                }
                if (error != null) {
                    ViaBedrock.getPlatform().getLogger().log(
                            Level.WARNING, "Failed to acquire cached Java resource pack", error);
                    sendEmptyResponse(ctx, httpFailureStatus(unwrap(error)));
                    return;
                }
                if (lease == null) {
                    sendEmptyResponse(ctx, HttpResponseStatus.NOT_FOUND);
                    return;
                }
                try {
                    this.serveArtifact(ctx, pendingRequest, lease);
                } catch (Throwable serveError) {
                    ViaBedrock.getPlatform().getLogger().log(
                            Level.WARNING, "Failed to serve cached Java resource pack", serveError);
                    sendEmptyResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                }
            };
            try {
                ctx.executor().execute(responseTask);
            } catch (Throwable dispatchError) {
                if (lease != null) lease.close();
                ctx.close();
            }
        });
    }

    private void serveArtifact(final ChannelHandlerContext ctx, final PendingRequest request,
                               final ArtifactLease lease) throws IOException {
        try {
            serveArtifact(ctx, request, lease.artifact(), ARTIFACT_FILE_OPENER, lease::close);
        } catch (IOException | RuntimeException | Error e) {
            lease.close();
            throw e;
        }
    }

    static void serveArtifact(final ChannelHandlerContext ctx, final HttpRequest request, final ArtifactRef artifact,
                              final ArtifactFileOpener fileOpener) throws IOException {
        serveArtifact(ctx, request, artifact, fileOpener, () -> {
        });
    }

    static void serveArtifact(final ChannelHandlerContext ctx, final HttpRequest request, final ArtifactRef artifact,
                              final ArtifactFileOpener fileOpener, final Runnable completion) throws IOException {
        try {
            serveArtifact(ctx, new PendingRequest(
                    request.method(), request.headers().get(HttpHeaderNames.RANGE),
                    request.headers().get(HttpHeaderNames.IF_NONE_MATCH)), artifact, fileOpener, completion);
        } catch (IOException | RuntimeException | Error error) {
            completion.run();
            throw error;
        }
    }

    private static void serveArtifact(final ChannelHandlerContext ctx, final PendingRequest request,
                                      final ArtifactRef artifact, final ArtifactFileOpener fileOpener,
                                      final Runnable completion) throws IOException {
        final String hash = artifact.hash();
        final File artifactFile = artifact.path().toFile();
        if (!artifactFile.isFile()) {
            completeResponse(sendEmptyResponse(ctx, HttpResponseStatus.NOT_FOUND), completion);
            return;
        }

        final String etag = "\"" + hash + "\"";
        if (etag.equals(request.ifNoneMatch())) {
            final DefaultHttpResponse response = artifactResponse(HttpResponseStatus.NOT_MODIFIED, hash);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            completeResponse(ctx.writeAndFlush(response), completion);
            return;
        }

        final long fileLength = artifact.size();
        final HttpByteRange range = parseRange(request.range(), fileLength);
        if (range == null) {
            final DefaultHttpResponse response = artifactResponse(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE, hash);
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + fileLength);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            completeResponse(ctx.writeAndFlush(response), completion);
            return;
        }

        final HttpResponseStatus status = range.partial() ? HttpResponseStatus.PARTIAL_CONTENT : HttpResponseStatus.OK;
        final DefaultHttpResponse response = artifactResponse(status, hash);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/zip");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, range.length());
        if (range.partial()) {
            response.headers().set(HttpHeaderNames.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + fileLength);
        }
        if (request.method().equals(HttpMethod.HEAD)) {
            ctx.write(response);
            completeResponse(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), completion);
            return;
        }

        final RandomAccessFile file = fileOpener.open(artifactFile);
        final ChunkedFile chunkedFile;
        try {
            chunkedFile = new ChunkedFile(file, range.start(), range.length(), 65_535);
        } catch (IOException | RuntimeException | Error e) {
            file.close();
            throw e;
        }
        boolean responseStarted = false;
        try {
            ctx.write(response);
            responseStarted = true;
            completeResponse(ctx.writeAndFlush(new HttpChunkedInput(chunkedFile)), completion);
        } catch (RuntimeException | Error e) {
            file.close();
            if (responseStarted) {
                completion.run();
                ctx.close();
                return;
            }
            throw e;
        }
    }

    private static void completeResponse(final ChannelFuture future, final Runnable completion) {
        future.addListener(ignored -> completion.run()).addListener(ChannelFutureListener.CLOSE);
    }

    @FunctionalInterface
    interface ArtifactFileOpener {

        RandomAccessFile open(File file) throws IOException;

    }

    static DefaultHttpResponse artifactResponse(final HttpResponseStatus status, final String hash) {
        final DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);
        response.headers().set(HttpHeaderNames.ETAG, "\"" + hash + "\"");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=31536000, immutable");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private static ChannelFuture sendEmptyResponse(final ChannelHandlerContext ctx, final HttpResponseStatus status) {
        final DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    static String artifactHash(final String path) {
        final Matcher matcher = ARTIFACT_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static <T> CompletableFuture<T> withDetachedTimeout(final CompletableFuture<T> source, final long timeout, final TimeUnit unit) {
        return withDetachedTimeout(source, timeout, unit, ignored -> {
        });
    }

    static <T> CompletableFuture<T> withDetachedTimeout(final CompletableFuture<T> source, final long timeout,
                                                        final TimeUnit unit, final Consumer<T> lateValueCleanup) {
        final CompletableFuture<T> dependent = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                dependent.completeExceptionally(error);
            } else if (!dependent.complete(value)) {
                lateValueCleanup.accept(value);
            }
        });
        return dependent.orTimeout(timeout, unit);
    }

    private static Throwable unwrap(final Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    static HttpResponseStatus httpFailureStatus(final Throwable cause) {
        if (cause instanceof PendingHttpRequestRejectedException rejected) {
            return rejected.status();
        }
        if (cause instanceof TimeoutException) {
            return HttpResponseStatus.GATEWAY_TIMEOUT;
        }
        if (cause instanceof RejectedExecutionException) {
            return HttpResponseStatus.SERVICE_UNAVAILABLE;
        }
        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }

    static HttpByteRange parseRange(final String header, final long fileLength) {
        if (fileLength <= 0) {
            return null;
        }
        if (header == null) {
            return new HttpByteRange(0, fileLength - 1, false);
        }

        final Matcher matcher = BYTE_RANGE.matcher(header);
        if (!matcher.matches() || matcher.group(1).isEmpty() && matcher.group(2).isEmpty()) {
            return null;
        }

        try {
            final long start;
            final long end;
            if (matcher.group(1).isEmpty()) {
                final long suffixLength = Long.parseLong(matcher.group(2));
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, fileLength - suffixLength);
                end = fileLength - 1;
            } else {
                start = Long.parseLong(matcher.group(1));
                if (start < 0 || start >= fileLength) {
                    return null;
                }
                end = matcher.group(2).isEmpty()
                        ? fileLength - 1
                        : Math.min(Long.parseLong(matcher.group(2)), fileLength - 1);
                if (end < start) {
                    return null;
                }
            }
            return new HttpByteRange(start, end, true);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record HttpByteRange(long start, long end, boolean partial) {

        long length() {
            return this.end - this.start + 1;
        }

    }

    private CompletableFuture<ArtifactLease> getOrCreateJavaPackLease(
            final ResourcePackStorage resourcePackStorage) {
        final JavaPackCache cache = ViaBedrock.getJavaPackCache();
        if (cache == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Java resource pack cache is unavailable"));
        }
        return resourcePackStorage.retainRuntimeDuring(() -> {
            final ArtifactKey cacheKey = resourcePackStorage.getExactArtifactKey();
            if (ViaBedrock.getResourcePackWorkScheduler() == null) {
                return cache.getOrBuildLeaseHashed(cacheKey,
                        target -> this.convertJavaPackWithAdmission(cache, resourcePackStorage, target));
            }
            return ViaBedrock.getResourcePackWorkScheduler().submitIo(() ->
                            cache.getOrBuildLeaseHashed(cacheKey,
                                    target -> this.convertJavaPackWithAdmission(cache, resourcePackStorage, target)))
                    .thenCompose(future -> future);
        });
    }

    static JavaPackCache.ArtifactBuildResult convertJavaPackWithAdmission(
            final JavaPackCache cache, final ResourcePackStorage resourcePackStorage,
            final Path target) throws IOException {
        return resourcePackStorage.withPackStack(() -> {
            final SharedPackRuntimeCache runtimeCache = ViaBedrock.getSharedPackRuntimeCache();
            if (runtimeCache == null) {
                return convertJavaPack(cache, resourcePackStorage, target);
            }

            final long estimateBytes = estimateArtifactBuildWeightInScope(resourcePackStorage);
            try (var ignored = runtimeCache.reserveArtifactBuild(estimateBytes)) {
                return convertJavaPack(cache, resourcePackStorage, target);
            }
        });
    }

    static long estimateArtifactBuildWeight(final ResourcePackStorage resourcePackStorage) {
        try {
            return resourcePackStorage.withPackStack(
                    () -> estimateArtifactBuildWeightInScope(resourcePackStorage));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect resource pack content", e);
        }
    }

    private static long estimateArtifactBuildWeightInScope(final ResourcePackStorage resourcePackStorage) {
        long structuredBytes = 0L;
        long peakFileBytes = 0L;
        long entries = 0L;
        final IdentityHashMap<Content, Boolean> visited = new IdentityHashMap<>();
        for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
            final Content content = pack.content();
            if (visited.put(content, Boolean.TRUE) != null) continue;

            for (String path : content.getFilesDeep("", "")) {
                entries++;
                final long size;
                try {
                    size = Math.max(0L, content.size(path));
                } catch (IOException ignored) {
                    peakFileBytes = Math.max(peakFileBytes, 4L * 1024L * 1024L);
                    continue;
                }
                final String lowerPath = path.toLowerCase(Locale.ROOT);
                if (lowerPath.endsWith(".json") || lowerPath.endsWith(".lang")) {
                    structuredBytes = saturatingAdd(structuredBytes, saturatingMultiply(size, 8L));
                } else if (lowerPath.endsWith(".png") || lowerPath.endsWith(".tga")
                        || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
                    peakFileBytes = Math.max(peakFileBytes, saturatingMultiply(size, 32L));
                } else {
                    peakFileBytes = Math.max(peakFileBytes, saturatingMultiply(size, 2L));
                }
            }
        }
        long estimate = 1024L * 1024L;
        estimate = saturatingAdd(estimate, structuredBytes);
        estimate = saturatingAdd(estimate, peakFileBytes);
        return saturatingAdd(estimate, saturatingMultiply(entries, 256L));
    }

    private static long saturatingMultiply(final long value, final long multiplier) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static JavaPackCache.ArtifactBuildResult convertJavaPack(
            final JavaPackCache cache, final ResourcePackStorage resourcePackStorage,
            final Path target) throws IOException {
        final long start = System.nanoTime();
        final JavaPackCache.ArtifactBuildResult result;
        try (JavaPackCache.BuildWorkspace workspace = cache.openBuildWorkspace()) {
            result = writeJavaPackArtifact(resourcePackStorage, workspace.path(), target);
        }
        final long end = System.nanoTime();
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Converted resource packs in " + ((end - start) / 1_000_000L) + "ms");
        return result;
    }

    static JavaPackCache.ArtifactBuildResult writeJavaPackArtifact(
            final ResourcePackStorage resourcePackStorage, final Path outputDirectory,
            final Path target) throws IOException {
        final Map<String, Path> embeddedPacks = new HashMap<>();
        final Map<String, byte[]> generatedEntries = new HashMap<>();
        final Content javaContent = ResourcePackRewriter.bedrockToJava(
                resourcePackStorage, new DirectoryContent(outputDirectory));
        final List<String> outputPaths = new ArrayList<>(javaContent.getFilesDeep("", ""));
        final Set<String> uniquePaths = new HashSet<>(outputPaths);
        if (!uniquePaths.add(BEDROCK_PACK_STACK_PATH)) {
            throw new IOException("Converted resource pack already contains reserved path "
                    + BEDROCK_PACK_STACK_PATH);
        }
        final List<ResourcePack> embeddedPackStack = embeddedPackStackBottomToTop(resourcePackStorage);
        generatedEntries.put(BEDROCK_PACK_STACK_PATH,
                buildBedrockPackStackManifest(embeddedPackStack));
        outputPaths.add(BEDROCK_PACK_STACK_PATH);
        final Path embeddedDirectory = Files.createDirectory(outputDirectory.resolve(".embedded-packs"));
        for (ResourcePack pack : embeddedPackStack) {
            final String mcpackPath = "bedrock/" + pack.id() + ".mcpack";
            if (!uniquePaths.add(mcpackPath)) {
                throw new IOException("Converted resource pack already contains reserved path "
                        + mcpackPath);
            }
            final Path packTemp = Files.createTempFile(embeddedDirectory, pack.id() + "-", ".mcpack.tmp");
            embeddedPacks.put(mcpackPath, packTemp);
            pack.content().writeZip(packTemp);
            outputPaths.add(mcpackPath);
        }

        outputPaths.sort(String::compareTo);
        final MessageDigest digest = sha1Digest();
        try (OutputStream fileOutput = Files.newOutputStream(target);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             ZipOutputStream output = new ZipOutputStream(digestOutput)) {
            for (String path : outputPaths) {
                final ZipEntry entry = new ZipEntry(path);
                entry.setTime(0L);
                output.putNextEntry(entry);
                final Path embeddedPack = embeddedPacks.get(path);
                if (embeddedPack != null) {
                    Files.copy(embeddedPack, output);
                } else if (generatedEntries.containsKey(path)) {
                    output.write(generatedEntries.get(path));
                } else {
                    final Path contentPath = outputDirectory.resolve(path).normalize();
                    if (!contentPath.startsWith(outputDirectory) || !Files.isRegularFile(contentPath)) {
                        throw new IOException("Invalid converted resource pack path: " + path);
                    }
                    Files.copy(contentPath, output);
                }
                output.closeEntry();
            }
        }
        return new JavaPackCache.ArtifactBuildResult(
                HexFormat.of().formatHex(digest.digest()), Files.size(target));
    }

    static byte[] buildBedrockPackStackManifest(ResourcePackStorage resourcePackStorage) {
        return buildBedrockPackStackManifest(embeddedPackStackBottomToTop(resourcePackStorage));
    }

    private static byte[] buildBedrockPackStackManifest(List<ResourcePack> embeddedPackStack) {
        final JsonObject root = new JsonObject();
        root.addProperty("format_version", 1);
        root.addProperty("order", "bottom_to_top");
        final JsonArray packs = new JsonArray();
        for (ResourcePack pack : embeddedPackStack) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("path", "bedrock/" + pack.id() + ".mcpack");
            entry.addProperty("uuid", pack.id().toString());
            entry.addProperty("version", pack.version());
            entry.addProperty("name", pack.name());
            packs.add(entry);
        }
        root.add("packs", packs);
        return GsonUtil.getGson().toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /** Mirrors the UUID-based archive paths: the topmost occurrence of one pack identity wins. */
    static List<ResourcePack> embeddedPackStackBottomToTop(ResourcePackStorage resourcePackStorage) {
        final List<ResourcePack> selectedTopToBottom = new ArrayList<>();
        final Set<UUID> selectedIds = new HashSet<>();
        for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
            if (selectedIds.add(pack.id())) {
                selectedTopToBottom.add(pack);
            }
        }
        return List.copyOf(selectedTopToBottom.reversed());
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    static void deleteRecursively(final Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        IOException failure = null;
        final List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void addConnection(final UUID uuid, final ChannelFuture connectionCloseFuture) {
        final TokenState state = new TokenState(this.pendingRequests);
        final TokenState previous = this.connections.putIfAbsent(uuid, state);
        if (previous != null) {
            throw new IllegalStateException("Duplicate resource pack HTTP token");
        }
        connectionCloseFuture.addListener(future -> {
            if (this.connections.remove(uuid, state)) {
                state.fail(new CancellationException("Resource pack connection closed"));
            }
        });
    }

    public void completeConnection(final UUID uuid, final ResourcePackStorage storage) {
        final TokenState state = this.connections.get(uuid);
        if (state != null) {
            state.complete(storage);
        }
    }

    public void failConnection(final UUID uuid, final Throwable error) {
        final TokenState state = this.connections.remove(uuid);
        if (state != null) {
            state.fail(error);
        }
    }

    public void stop() {
        final CancellationException stopped = new CancellationException("Resource pack HTTP server stopped");
        this.connections.forEach((uuid, state) -> {
            if (this.connections.remove(uuid, state)) {
                state.fail(stopped);
            }
        });
        if (this.channelFuture != null) {
            this.channelFuture.channel().close();
        }
        this.eventLoopGroup.shutdownGracefully();
    }

    public String getUrl() {
        final String overrideUrl = ViaBedrock.getConfig().getResourcePackUrl();
        if (!overrideUrl.isEmpty()) {
            return overrideUrl;
        } else {
            final InetSocketAddress bindAddress = (InetSocketAddress) this.channelFuture.channel().localAddress();
            return "http://" + this.bindAddress.getHostString() + ":" + bindAddress.getPort() + "/";
        }
    }

    public String getArtifactUrl(final String hash) {
        final String baseUrl = this.getUrl();
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "packs/" + hash + ".zip";
    }

    public Channel getChannel() {
        return this.channelFuture.channel();
    }

}
