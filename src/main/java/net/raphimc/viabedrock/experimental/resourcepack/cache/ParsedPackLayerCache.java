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

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.SelectedSubpackContent;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 带权重的已完成结果缓存，加上针对不可变单包定义层的 single-flight 解析。 */
final class ParsedPackLayerCache {

    private final Cache<LayerKey, RetainedLayer> completed;
    private final ConcurrentMap<LayerKey, CompletableFuture<RetainedLayer>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<LayerKey, WeakReference<RetainedLayer>> live = new ConcurrentHashMap<>();
    private final FailureBackoff<LayerKey> failureBackoff;
    private final ResourcePackCacheMetrics metrics;
    private final LayerPublicationAdmission publicationAdmission;
    private final long maxWeightBytes;
    private final AtomicLong weightBytes = new AtomicLong();

    ParsedPackLayerCache(final long maxWeightBytes, final int idleExpireMinutes,
                         final ResourcePackCacheMetrics metrics) {
        this(maxWeightBytes, idleExpireMinutes, metrics, System::nanoTime);
    }

    ParsedPackLayerCache(final long maxWeightBytes, final int idleExpireMinutes,
                         final ResourcePackCacheMetrics metrics, final LongSupplier nanoTime) {
        this(maxWeightBytes, idleExpireMinutes, metrics, nanoTime,
                (ignored, publication) -> publication.run());
    }

    ParsedPackLayerCache(final long maxWeightBytes, final int idleExpireMinutes,
                         final ResourcePackCacheMetrics metrics, final LongSupplier nanoTime,
                         final LayerPublicationAdmission publicationAdmission) {
        this.maxWeightBytes = Math.max(1L, maxWeightBytes);
        this.metrics = metrics;
        this.publicationAdmission = Objects.requireNonNull(publicationAdmission, "publicationAdmission");
        this.failureBackoff = new FailureBackoff<>(nanoTime);
        this.completed = CacheBuilder.newBuilder()
                .maximumWeight(this.maxWeightBytes)
                .weigher((LayerKey key, RetainedLayer value) -> clampWeight(value.estimatedWeightBytes()))
                .expireAfterAccess(Math.max(1, idleExpireMinutes), TimeUnit.MINUTES)
                .ticker(new Ticker() {
                    @Override
                    public long read() {
                        return nanoTime.getAsLong();
                    }
                })
                .removalListener(notification -> {
                    if (notification.getValue() instanceof RetainedLayer entry) {
                        this.weightBytes.addAndGet(-entry.estimatedWeightBytes());
                        if (notification.getCause() != RemovalCause.EXPLICIT
                                && notification.getCause() != RemovalCause.REPLACED) {
                            this.metrics.eviction(Tier.LAYER);
                        }
                        this.updateWeight();
                    }
                })
                .build();
        this.updateWeight();
    }

    ParsedPackLayer getOrParse(final ResourcePack pack, final ContentDigest digest, final String selectedSubpack) {
        return this.getOrParseRetained(pack, digest, selectedSubpack).layer();
    }

    RetainedLayer getOrParseRetained(final ResourcePack pack, final ContentDigest digest,
                                     final String selectedSubpack) {
        final LayerKey key = new LayerKey(digest, Objects.requireNonNullElse(selectedSubpack, ""));
        final RetainedLayer cached = this.completed.getIfPresent(key);
        if (cached != null) {
            this.failureBackoff.clear(key);
            this.metrics.hit(Tier.LAYER);
            this.live.put(key, new WeakReference<>(cached));
            return cached;
        }

        final WeakReference<RetainedLayer> liveReference = this.live.get(key);
        final RetainedLayer liveLayer = liveReference != null ? liveReference.get() : null;
        if (liveLayer != null) {
            this.metrics.hit(Tier.LAYER);
            return liveLayer;
        } else if (liveReference != null) {
            this.live.remove(key, liveReference);
        }

        this.metrics.miss(Tier.LAYER);
        final Throwable recentFailure = this.failureBackoff.getIfActive(key);
        if (recentFailure != null) {
            throw propagate(recentFailure);
        }
        final CompletableFuture<RetainedLayer> candidate = new CompletableFuture<>();
        final CompletableFuture<RetainedLayer> raced = this.inflight.putIfAbsent(key, candidate);
        if (raced != null) {
            this.metrics.waiter(Tier.LAYER);
            return raced.join();
        }
        final Throwable racedFailure = this.failureBackoff.getIfActive(key);
        if (racedFailure != null) {
            this.inflight.remove(key, candidate);
            candidate.completeExceptionally(racedFailure);
            throw propagate(racedFailure);
        }

        this.metrics.build(Tier.LAYER);
        this.metrics.setInflight(Tier.LAYER, this.inflight.size());
        RetainedLayer result = null;
        Throwable failure = null;
        final long start = System.nanoTime();
        try {
            final ParsedPackLayer layer = ParsedPackLayer.parse(pack, key.selectedSubpack());
            final RetainedLayer parsed = new RetainedLayer(key, layer, estimateWeight(pack, layer));
            this.publicationAdmission.publish(parsed, () -> {
                this.completed.put(key, parsed);
                this.weightBytes.addAndGet(parsed.estimatedWeightBytes());
                this.live.put(key, new WeakReference<>(parsed));
                this.updateWeight();
            });
            result = parsed;
        } catch (Throwable e) {
            failure = e;
            this.failureBackoff.record(key, e);
            this.metrics.failure(Tier.LAYER);
        } finally {
            this.metrics.recordBuildTime(Tier.LAYER,
                    (System.nanoTime() - start) / 1_000_000L);
            this.inflight.remove(key, candidate);
            this.metrics.setInflight(Tier.LAYER, this.inflight.size());
        }
        if (failure == null) {
            this.failureBackoff.clear(key);
            candidate.complete(result);
            return result;
        }
        candidate.completeExceptionally(failure);
        throw propagate(failure);
    }

    RetainedLayer getOrParseRetained(final FrozenPackBlob blob, final String selectedSubpack) {
        Objects.requireNonNull(blob, "blob");
        return this.getOrParseRetained(blob.resourcePack(), blob.contentDigest(), selectedSubpack);
    }

    long weightBytes() {
        return Math.max(0L, this.weightBytes.get());
    }

    long maxWeightBytes() {
        return this.maxWeightBytes;
    }

    int completedCount() {
        return this.completed.asMap().size();
    }

    LayerBuildEstimate estimateBuild(final ResourcePack pack, final ContentDigest digest,
                                     final String selectedSubpack) {
        final LayerKey key = new LayerKey(digest, Objects.requireNonNullElse(selectedSubpack, ""));
        final WeakReference<RetainedLayer> liveReference = this.live.get(key);
        final RetainedLayer liveLayer = liveReference != null ? liveReference.get() : null;
        if (liveLayer != null) {
            return new LayerBuildEstimate(0L, liveLayer.estimatedWeightBytes());
        }
        final RetainedLayer cached = this.completed.getIfPresent(key);
        if (cached != null) {
            return new LayerBuildEstimate(0L, cached.estimatedWeightBytes());
        }

        final long retainedWeight = estimateSourceWeight(pack, key.selectedSubpack(), 6L);
        final long parseReservation = this.inflight.containsKey(key)
                ? 0L : estimateSourceWeight(pack, key.selectedSubpack(), 12L);
        return new LayerBuildEstimate(parseReservation, retainedWeight);
    }

    void collectRetained(final SharedPackRuntime.RetainedWeight retainedWeight) {
        for (RetainedLayer entry : this.completed.asMap().values()) {
            retainedWeight.add(entry.layer(), entry.estimatedWeightBytes());
        }
    }

    void cleanUp() {
        this.completed.cleanUp();
        this.live.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }

    private void updateWeight() {
        this.metrics.setWeight(Tier.LAYER, this.weightBytes(), this.maxWeightBytes);
    }

    private static long estimateWeight(final ResourcePack pack, final ParsedPackLayer layer) {
        return saturatingAdd(estimateSourceWeight(pack, layer.selectedSubpack(), 6L),
                layer.serverAnimation().estimatedWeightBytes());
    }

    private static long estimateSourceWeight(final ResourcePack pack, final String selectedSubpack,
                                             final long expansionMultiplier) {
        final Content content = selectedSubpack == null || selectedSubpack.isEmpty()
                ? pack.content() : new SelectedSubpackContent(pack.content(), selectedSubpack);
        long bytes = 64L * 1024L;
        for (String path : content.getFilesDeep("", "")) {
            if (!path.endsWith(".json") && !path.endsWith(".lang")) continue;
            try {
                final long size = content.size(path);
                if (size > 0L) {
                    bytes = saturatingAdd(bytes, saturatingMultiply(size, expansionMultiplier));
                }
            } catch (IOException ignored) {
                bytes = saturatingAdd(bytes, 4L * 1024L * expansionMultiplier);
            }
        }
        return Math.max(64L * 1024L, bytes);
    }

    private static long saturatingMultiply(final long value, final long multiplier) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int clampWeight(final long weight) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, weight));
    }

    private static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) return runtimeException;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Failed to parse resource pack layer", failure);
    }

    record LayerKey(ContentDigest contentDigest, String selectedSubpack) {
        LayerKey {
            Objects.requireNonNull(contentDigest, "contentDigest");
            selectedSubpack = Objects.requireNonNullElse(selectedSubpack, "");
        }
    }

    record RetainedLayer(LayerKey key, ParsedPackLayer layer, long estimatedWeightBytes) {
        RetainedLayer {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(layer, "layer");
            estimatedWeightBytes = Math.max(1L, estimatedWeightBytes);
        }
    }

    record LayerBuildEstimate(long parseReservationBytes, long retainedWeightBytes) {
        LayerBuildEstimate {
            parseReservationBytes = Math.max(0L, parseReservationBytes);
            retainedWeightBytes = Math.max(1L, retainedWeightBytes);
        }
    }

    @FunctionalInterface
    interface LayerPublicationAdmission {

        void publish(RetainedLayer layer, Runnable publication);

    }

}
