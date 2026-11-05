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

import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.api.resourcepack.cache.PackMount;
import net.raphimc.viabedrock.api.resourcepack.cache.RuntimeStackKey;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.FrozenContent;
import net.raphimc.viabedrock.api.resourcepack.content.SelectedSubpackContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.definition.*;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class SharedPackRuntime {

    private final RuntimeStackKey key;
    private final String runtimeDataFingerprint;
    private final List<PackSource> packSourcesBottomToTop;
    private final List<PackSource> packSourcesTopToBottom;
    private final List<ResourcePack> compatibilityPackStackBottomToTop;
    private final List<ResourcePack> compatibilityPackStackTopToBottom;
    private final TextDefinitions texts;
    private final BlockDefinitions blocks;
    private final ItemDefinitions items;
    private final AttachableDefinitions attachables;
    private final TextureDefinitions textures;
    private final SoundDefinitions sounds;
    private final ParticleDefinitions particles;
    private final EntityDefinitions entities;
    private final ModelDefinitions models;
    private final FogDefinitions fogs;
    private final BiomeDefinitions biomes;
    private final RenderControllerDefinitions renderControllers;
    private final List<ParsedPackLayer> parsedLayersBottomToTop;
    private final List<ParsedPackLayerCache.RetainedLayer> retainedLayers;
    private final PackManager bedrockMotionPackManager;
    private final Set<String> customSoundNames;
    private ConcurrentMap<String, Object> converterDataBuilder = new ConcurrentHashMap<>();
    private volatile Map<String, Object> converterData =
            Collections.unmodifiableMap(this.converterDataBuilder);
    private Thread converterDataInitializationThread;
    private final ConcurrentMap<String, CompletableFuture<Void>> initializationFutures =
            new ConcurrentHashMap<>();
    private final Map<ArtifactVariant, ArtifactKey> artifactKeys = new ConcurrentHashMap<>();
    private final long runtimeGraphWeightBytes;
    private volatile long converterDataWeightBytes;
    // 按条目计算闭包权重使空闲缓存淘汰更保守；全局快照按身份去重。
    private volatile long estimatedWeightBytes;
    private final AtomicBoolean rejected = new AtomicBoolean();

    SharedPackRuntime(final RuntimeStackKey key, final String runtimeDataFingerprint,
                      final List<PackSource> packSourcesBottomToTop,
                      final SharedPackRuntimeCache packResolver,
                      final ParsedPackLayer.FoldedDefinitions definitions,
                      final List<ParsedPackLayerCache.RetainedLayer> retainedLayers,
                      final PackManager bedrockMotionPackManager,
                      final Set<String> customSoundNames) {
        this.key = key;
        this.runtimeDataFingerprint = Objects.requireNonNull(
                runtimeDataFingerprint, "runtimeDataFingerprint");
        this.packSourcesBottomToTop = List.copyOf(packSourcesBottomToTop);
        final java.util.ArrayList<PackSource> topToBottom = new java.util.ArrayList<>(packSourcesBottomToTop);
        java.util.Collections.reverse(topToBottom);
        this.packSourcesTopToBottom = List.copyOf(topToBottom);
        final CompatibilityPackViews compatibilityViews = new CompatibilityPackViews(
                packResolver, this.packSourcesBottomToTop);
        this.compatibilityPackStackBottomToTop = new CompatibilityPackStack(
                compatibilityViews, false);
        this.compatibilityPackStackTopToBottom = new CompatibilityPackStack(
                compatibilityViews, true);
        this.texts = definitions.texts();
        this.blocks = definitions.blocks();
        this.items = definitions.items();
        this.attachables = definitions.attachables();
        this.textures = definitions.textures();
        this.sounds = definitions.sounds();
        this.particles = definitions.particles();
        this.entities = definitions.entities();
        this.models = definitions.models();
        this.fogs = definitions.fogs();
        this.biomes = definitions.biomes();
        this.renderControllers = definitions.renderControllers();
        this.retainedLayers = List.copyOf(retainedLayers);
        this.parsedLayersBottomToTop = this.retainedLayers.stream()
                .map(ParsedPackLayerCache.RetainedLayer::layer)
                .toList();
        this.bedrockMotionPackManager = bedrockMotionPackManager;
        this.customSoundNames = Set.copyOf(customSoundNames);
        long retainedLayerWeight = 0L;
        final IdentityHashMap<ParsedPackLayer, Boolean> uniqueLayers = new IdentityHashMap<>();
        for (ParsedPackLayerCache.RetainedLayer retainedLayer : this.retainedLayers) {
            if (uniqueLayers.put(retainedLayer.layer(), Boolean.TRUE) == null) {
                retainedLayerWeight = saturatingAdd(retainedLayerWeight, retainedLayer.estimatedWeightBytes());
            }
        }
        long runtimeGraphWeight = 64L * 1024L
                + (long) (this.packSourcesBottomToTop.size() + this.retainedLayers.size()) * 4L * 1024L;
        // 折叠后的映射与各层共享定义值，但仍保留自己的哈希表。
        // 对这些索引按解析图权重的四分之一计算，是刻意保守的做法。
        runtimeGraphWeight = saturatingAdd(runtimeGraphWeight, retainedLayerWeight / 4L);
        if (this.bedrockMotionPackManager != null) {
            runtimeGraphWeight = saturatingAdd(runtimeGraphWeight,
                    (long) this.bedrockMotionPackManager.getAnimationDefinitions().getAnimations().size() * 512L
                    + (long) this.bedrockMotionPackManager.getAnimationControllerDefinitions().getControllers().size() * 512L
                    + (long) this.bedrockMotionPackManager.getRenderControllerDefinitions().getRenderControllers().size() * 512L);
        }
        this.runtimeGraphWeightBytes = Math.max(1L, runtimeGraphWeight);
        final RetainedWeight retainedWeight = new RetainedWeight();
        this.collectRetained(retainedWeight);
        this.estimatedWeightBytes = retainedWeight.totalBytes();
    }

    public RuntimeStackKey key() { return this.key; }
    public String runtimeDataFingerprint() { return this.runtimeDataFingerprint; }
    public List<PackSource> packSourcesBottomToTop() { return this.packSourcesBottomToTop; }
    public List<PackSource> packSourcesTopToBottom() { return this.packSourcesTopToBottom; }
    public List<ResourcePack> packStackBottomToTop() { return this.compatibilityPackStackBottomToTop; }
    public List<ResourcePack> packStackTopToBottom() { return this.compatibilityPackStackTopToBottom; }
    public List<PackMount> packMountsBottomToTop() {
        return this.packSourcesBottomToTop.stream().map(PackSource::mount).toList();
    }
    public List<PackMount> packMountsTopToBottom() {
        return this.packSourcesTopToBottom.stream().map(PackSource::mount).toList();
    }
    public TextDefinitions texts() { return this.texts; }
    public BlockDefinitions blocks() { return this.blocks; }
    public ItemDefinitions items() { return this.items; }
    public AttachableDefinitions attachables() { return this.attachables; }
    public TextureDefinitions textures() { return this.textures; }
    public SoundDefinitions sounds() { return this.sounds; }
    public ParticleDefinitions particles() { return this.particles; }
    public EntityDefinitions entities() { return this.entities; }
    public ModelDefinitions models() { return this.models; }
    public FogDefinitions fogs() { return this.fogs; }
    public BiomeDefinitions biomes() { return this.biomes; }
    public RenderControllerDefinitions renderControllers() { return this.renderControllers; }
    public List<ParsedPackLayer> parsedLayersBottomToTop() { return this.parsedLayersBottomToTop; }
    public PackManager bedrockMotionPackManager() { return this.bedrockMotionPackManager; }
    public Set<String> customSoundNames() { return this.customSoundNames; }
    public Map<String, Object> converterData() { return this.converterData; }
    public long estimatedWeightBytes() { return this.estimatedWeightBytes; }

    public CompletableFuture<Void> putInitializationIfAbsent(
            final String key, final CompletableFuture<Void> completion) {
        return this.initializationFutures.putIfAbsent(key, completion);
    }

    public boolean removeInitialization(
            final String key, final CompletableFuture<Void> completion) {
        return this.initializationFutures.remove(key, completion);
    }

    public synchronized Object putConverterDataDuringInitialization(
            final String key, final Object value) {
        if (this.converterDataBuilder == null) {
            throw new IllegalStateException("Shared resource pack runtime data is already immutable");
        }
        if (this.converterDataInitializationThread != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Shared resource pack runtime data may only be written by its initializer");
        }
        return this.converterDataBuilder.put(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    synchronized void beginConverterDataInitialization() {
        if (this.converterDataBuilder == null) {
            throw new IllegalStateException("Shared resource pack runtime data is already immutable");
        }
        if (this.converterDataInitializationThread != null) {
            throw new IllegalStateException("Shared resource pack runtime data is already being initialized");
        }
        this.converterDataInitializationThread = Thread.currentThread();
    }

    synchronized void sealConverterData() {
        if (this.converterDataBuilder == null
                || this.converterDataInitializationThread != Thread.currentThread()) {
            throw new IllegalStateException("Shared resource pack runtime data initializer ownership was lost");
        }
        final Map<String, Object> frozen = new LinkedHashMap<>(this.converterDataBuilder.size());
        this.converterDataBuilder.forEach((key, value) ->
                frozen.put(key, freezeRuntimeDataValue(value, new IdentityHashMap<>())));
        this.converterData = Collections.unmodifiableMap(frozen);
        this.converterDataBuilder = null;
        this.converterDataInitializationThread = null;
    }

    synchronized void abortConverterDataInitialization() {
        if (this.converterDataInitializationThread == Thread.currentThread()) {
            this.converterDataInitializationThread = null;
        }
    }

    private static Object freezeRuntimeDataValue(
            final Object value, final IdentityHashMap<Object, Boolean> visiting) {
        Objects.requireNonNull(value, "shared runtime data value");
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof java.util.UUID) {
            return value;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Shared resource pack runtime data must not contain cycles");
        }
        try {
            if (value instanceof List<?> list) {
                final List<Object> frozen = new ArrayList<>(list.size());
                for (Object element : list) {
                    frozen.add(freezeRuntimeDataValue(element, visiting));
                }
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                final Set<Object> frozen = new LinkedHashSet<>(set.size());
                for (Object element : set) {
                    frozen.add(freezeRuntimeDataValue(element, visiting));
                }
                return Collections.unmodifiableSet(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                final Map<Object, Object> frozen = new LinkedHashMap<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    frozen.put(
                            freezeRuntimeDataValue(entry.getKey(), visiting),
                            freezeRuntimeDataValue(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(frozen);
            }
        } finally {
            visiting.remove(value);
        }
        throw new IllegalArgumentException(
                "Shared resource pack runtime data requires an immutable typed field for value type "
                        + value.getClass().getName());
    }

    long initializationEstimateBytes() {
        return Math.max(1024L * 1024L, saturatingAdd(
                this.runtimeGraphWeightBytes / 4L,
                (long) (this.entities.entities().size() + this.models.entityModelCount()) * 4L * 1024L));
    }

    void collectRetained(final RetainedWeight retainedWeight) {
        retainedWeight.add(this, saturatingAdd(this.runtimeGraphWeightBytes, this.converterDataWeightBytes));
        for (ParsedPackLayerCache.RetainedLayer retainedLayer : this.retainedLayers) {
            retainedWeight.add(retainedLayer.layer(), retainedLayer.estimatedWeightBytes());
        }
    }

    synchronized long refreshConverterDataWeight() {
        this.converterDataWeightBytes = estimateObjectGraph(
                this.converterData, new IdentityHashMap<>());
        final RetainedWeight retainedWeight = new RetainedWeight();
        this.collectRetained(retainedWeight);
        this.estimatedWeightBytes = retainedWeight.totalBytes();
        return this.estimatedWeightBytes;
    }

    boolean isRejected() {
        return this.rejected.get();
    }

    boolean reject() {
        return this.rejected.compareAndSet(false, true);
    }

    private static long estimateObjectGraph(final Object value,
                                            final IdentityHashMap<Object, Boolean> visited) {
        if (value == null || visited.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof String string) {
            return 40L + (long) string.length() * Character.BYTES;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return 32L;
        }
        if (value instanceof byte[] bytes) {
            return 16L + bytes.length;
        }
        if (value instanceof Map<?, ?> map) {
            long weight = 64L + (long) map.size() * 64L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                weight = saturatingAdd(weight, estimateObjectGraph(entry.getKey(), visited));
                weight = saturatingAdd(weight, estimateObjectGraph(entry.getValue(), visited));
            }
            return weight;
        }
        if (value instanceof Collection<?> collection) {
            long weight = 48L + (long) collection.size() * 8L;
            for (Object element : collection) {
                weight = saturatingAdd(weight, estimateObjectGraph(element, visited));
            }
            return weight;
        }
        if (value.getClass().isArray()) {
            final int length = java.lang.reflect.Array.getLength(value);
            long weight = 16L + (long) length * 8L;
            if (!value.getClass().componentType().isPrimitive()) {
                for (int i = 0; i < length; i++) {
                    weight = saturatingAdd(weight,
                            estimateObjectGraph(java.lang.reflect.Array.get(value, i), visited));
                }
            }
            return weight;
        }
        return 128L;
    }

    static long estimateContentWeight(final Content content) {
        if (content instanceof FrozenContent frozenContent) {
            return Math.max(1L, frozenContent.weightBytes());
        }
        if (content instanceof ZipFileContent zipFileContent) {
            return Math.max(1L, zipFileContent.weightBytes());
        }
        if (content instanceof SelectedSubpackContent) {
            long weight = 256L;
            for (String path : content.getFilesDeep("", "")) {
                weight = saturatingAdd(weight, 128L + (long) path.length() * Character.BYTES * 2L);
            }
            return Math.max(1L, weight);
        }
        long weight = 0L;
        for (String path : content.getFilesDeep("", "")) {
            try {
                final long size = content.size(path);
                if (size < 0L) continue;
                weight = saturatingAdd(weight,
                        size + 64L + (long) path.length() * Character.BYTES);
            } catch (java.io.IOException e) {
                weight = saturatingAdd(weight,
                        4L * 1024L * 1024L + 64L + (long) path.length() * Character.BYTES);
            }
        }
        return Math.max(1L, weight);
    }

    static final class RetainedWeight {
        private final IdentityHashMap<Object, Long> weights = new IdentityHashMap<>();

        void add(final Object identity, final long weightBytes) {
            if (identity == null) return;
            final long weight = Math.max(1L, weightBytes);
            final Long current = this.weights.get(identity);
            if (current == null || weight > current) {
                this.weights.put(identity, weight);
            }
        }

        long totalBytes() {
            long total = 0L;
            for (long weight : this.weights.values()) {
                total = saturatingAdd(total, weight);
            }
            return total;
        }
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public ArtifactKey artifactKey(final boolean supportsFreeRotation) {
        return this.artifactKey(supportsFreeRotation, ResourcePackRewriter.rewriterFingerprint());
    }

    public ArtifactKey artifactKey(final boolean supportsFreeRotation, final String rewriterFingerprint) {
        final ArtifactVariant variant = new ArtifactVariant(supportsFreeRotation, rewriterFingerprint);
        return this.artifactKeys.computeIfAbsent(variant, ignored -> ArtifactKey.compute(
                this.key, JavaPackCache.CONVERTER_VERSION,
                variant.rewriterFingerprint(), variant.supportsFreeRotation()));
    }

    private record ArtifactVariant(boolean supportsFreeRotation, String rewriterFingerprint) {
    }

    public record PackSource(PackMount mount, SourceKind sourceKind) {

        public PackSource {
            Objects.requireNonNull(mount, "mount");
            Objects.requireNonNull(sourceKind, "sourceKind");
        }
    }

    public enum SourceKind {
        CANONICAL,
        BUILT_IN
    }

    private static final class CompatibilityPackStack extends AbstractList<ResourcePack>
            implements RandomAccess {

        private final CompatibilityPackViews views;
        private final boolean reversed;

        private CompatibilityPackStack(final CompatibilityPackViews views, final boolean reversed) {
            this.views = views;
            this.reversed = reversed;
        }

        @Override
        public ResourcePack get(final int index) {
            Objects.checkIndex(index, this.size());
            return this.views.get(this.reversed ? this.size() - 1 - index : index);
        }

        @Override
        public int size() {
            return this.views.size();
        }
    }

    private static final class CompatibilityPackViews {

        private final WeakReference<SharedPackRuntimeCache> resolver;
        private final List<PackSource> sources;
        private final AtomicReferenceArray<ResourcePack> materialized;

        private CompatibilityPackViews(final SharedPackRuntimeCache resolver,
                                       final List<PackSource> sources) {
            this.resolver = new WeakReference<>(Objects.requireNonNull(resolver, "resolver"));
            this.sources = List.copyOf(sources);
            this.materialized = new AtomicReferenceArray<>(sources.size());
        }

        private ResourcePack get(final int index) {
            final ResourcePack existing = this.materialized.get(index);
            if (existing != null) return existing;
            final SharedPackRuntimeCache resolver = this.resolver.get();
            if (resolver == null) {
                throw new IllegalStateException("Shared resource pack cache is unavailable");
            }
            final ResourcePack created = resolver.materializeView(this.sources.get(index));
            if (this.materialized.compareAndSet(index, null, created)) return created;
            return this.materialized.get(index);
        }

        private int size() {
            return this.sources.size();
        }
    }

}
