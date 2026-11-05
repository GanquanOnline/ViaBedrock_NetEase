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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StorableObject;
import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.cache.PackMount;
import net.raphimc.viabedrock.api.resourcepack.definition.*;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.cache.RuntimeStackKey;
import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntime;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache.PackStackLease;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache.RuntimeLease;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class ResourcePackStorage implements StorableObject {

    private final Collection<ResourcePack> legacyPackStackBottomToTop;
    private final Collection<ResourcePack> legacyPackStackTopToBottom;
    private final RuntimeLease runtimeLease;
    private final RuntimeStackKey runtimeStackKey;
    private final String rewriterFingerprint;
    private final ThreadLocal<PackStackScope> packStackScope = new ThreadLocal<>();

    private boolean loadedOnJavaClient;
    // 连接的 Java 客户端是否支持自由模型元素旋转格式（MC >= 1.21.11）。
    // 在资源包协商期间写一次（netty 线程），之后在资源包转换时读取
    // （HTTP 线程）-> 用 volatile 保证跨线程可见性。默认为 false（保守：输出
    // 旧式单轴兼容格式，绝不输出在旧客户端上加载失败的格式）。
    private volatile boolean supportsFreeRotation = false;
    private final Map<String, Object> converterData = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> sessionInitializationFutures =
            new ConcurrentHashMap<>();

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

    public ResourcePackStorage(final List<ResourcePack> resourcePacksTopToBottom) {
        this(resourcePacksTopToBottom, Collections.nCopies(resourcePacksTopToBottom.size(), ""), true);
    }

    public ResourcePackStorage(final List<ResourcePack> resourcePacksTopToBottom, final List<String> subpacksTopToBottom) {
        this(resourcePacksTopToBottom, subpacksTopToBottom, true);
    }

    private ResourcePackStorage(final List<ResourcePack> resourcePacksTopToBottom, final List<String> subpacksTopToBottom,
                                final boolean useSharedRuntime) {
        this(resourcePacksTopToBottom, subpacksTopToBottom, useSharedRuntime,
                ResourcePackRewriter.rewriterFingerprint());
    }

    private ResourcePackStorage(final List<ResourcePack> resourcePacksTopToBottom,
                                final List<String> subpacksTopToBottom,
                                final boolean useSharedRuntime,
                                final String rewriterFingerprint) {
        this.rewriterFingerprint = Objects.requireNonNull(
                rewriterFingerprint, "rewriterFingerprint");
        final SharedPackRuntimeCache runtimeCache = useSharedRuntime ? activeSharedRuntimeCache() : null;
        if (runtimeCache != null) {
            this.runtimeLease = runtimeCache.acquire(
                    resourcePacksTopToBottom, subpacksTopToBottom, this.rewriterFingerprint);
            final SharedPackRuntime runtime = this.runtimeLease.runtime();
            this.runtimeStackKey = runtime.key();
            this.legacyPackStackBottomToTop = List.of();
            this.legacyPackStackTopToBottom = List.of();
            this.texts = runtime.texts();
            this.blocks = runtime.blocks();
            this.items = ItemDefinitions.sessionOverlay(runtime.items());
            this.attachables = runtime.attachables();
            this.textures = runtime.textures();
            this.sounds = runtime.sounds();
            this.particles = runtime.particles();
            this.entities = runtime.entities();
            this.models = runtime.models();
            this.fogs = runtime.fogs();
            this.biomes = runtime.biomes();
            this.renderControllers = runtime.renderControllers();
            return;
        }

        this.runtimeLease = null;
        this.runtimeStackKey = null;
        final List<ResourcePack> bottomToTop = new ArrayList<>();
        if (BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks() != null) {
            bottomToTop.addAll(BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks());
        }
        for (int i = resourcePacksTopToBottom.size() - 1; i >= 0; i--) {
            bottomToTop.add(resourcePacksTopToBottom.get(i));
        }
        final List<ResourcePack> topToBottom = new ArrayList<>(resourcePacksTopToBottom);
        if (BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks() != null) {
            for (int i = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks().size() - 1; i >= 0; i--) {
                topToBottom.add(BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks().get(i));
            }
        }
        this.legacyPackStackBottomToTop = List.copyOf(bottomToTop);
        this.legacyPackStackTopToBottom = List.copyOf(topToBottom);
        this.texts = new TextDefinitions(this);
        this.blocks = new BlockDefinitions(this);
        this.items = new ItemDefinitions(this);
        this.attachables = new AttachableDefinitions(this);
        this.textures = new TextureDefinitions(this);
        this.sounds = new SoundDefinitions(this);
        this.particles = new ParticleDefinitions(this);
        this.entities = new EntityDefinitions(this);
        this.models = new ModelDefinitions(this);
        this.fogs = new FogDefinitions(this);
        this.biomes = new BiomeDefinitions(this);
        this.renderControllers = new RenderControllerDefinitions(this);
    }

    public static ResourcePackStorage createUnshared(final List<ResourcePack> resourcePacksTopToBottom) {
        return new ResourcePackStorage(resourcePacksTopToBottom,
                Collections.nCopies(resourcePacksTopToBottom.size(), ""), false);
    }

    public static CompletableFuture<ResourcePackStorage> createAsync(final List<ResourcePack> resourcePacksTopToBottom,
                                                                     final List<String> subpacksTopToBottom) {
        return createAsync(resourcePacksTopToBottom, subpacksTopToBottom,
                activeSharedRuntimeCache(), ViaBedrock.getResourcePackWorkScheduler());
    }

    static CompletableFuture<ResourcePackStorage> createAsync(
            final List<ResourcePack> resourcePacksTopToBottom,
            final List<String> subpacksTopToBottom,
            final SharedPackRuntimeCache runtimeCache,
            final ResourcePackWorkScheduler scheduler) {
        if (resourcePacksTopToBottom.size() != subpacksTopToBottom.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Resource pack and subpack stack sizes differ"));
        }
        final List<ResourcePack> resourcePacksSnapshot = List.copyOf(resourcePacksTopToBottom);
        final List<String> subpacksSnapshot = List.copyOf(subpacksTopToBottom);
        final String rewriterFingerprint = ResourcePackRewriter.rewriterFingerprint();
        if (runtimeCache != null) {
            final CompletableFuture<ResourcePackStorage> result = new CompletableFuture<>();
            runtimeCache.acquireAsync(
                            resourcePacksSnapshot, subpacksSnapshot, rewriterFingerprint)
                    .whenComplete((runtimeLease, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                            return;
                        }

                        final ResourcePackStorage storage;
                        try {
                            storage = fromRuntimeLease(runtimeLease, rewriterFingerprint);
                        } catch (Throwable creationError) {
                            result.completeExceptionally(creationError);
                            return;
                        }
                        if (!result.complete(storage)) {
                            storage.onRemove();
                        }
                    });
            return result;
        }
        if (scheduler != null) {
            return scheduler.submitCpu(() ->
                    new ResourcePackStorage(
                            resourcePacksSnapshot, subpacksSnapshot, false, rewriterFingerprint));
        }
        final CompletableFuture<ResourcePackStorage> future = new CompletableFuture<>();
        try {
            Via.getPlatform().runAsync(() -> {
                try {
                    future.complete(new ResourcePackStorage(
                            resourcePacksSnapshot, subpacksSnapshot, false, rewriterFingerprint));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static SharedPackRuntimeCache activeSharedRuntimeCache() {
        return ViaBedrock.getSharedPackRuntimeCache();
    }

    private static ResourcePackStorage fromRuntimeLease(
            final RuntimeLease runtimeLease, final String rewriterFingerprint) {
        try {
            return new ResourcePackStorage(runtimeLease, rewriterFingerprint);
        } catch (RuntimeException | Error e) {
            runtimeLease.close();
            throw e;
        }
    }

    ResourcePackStorage(final RuntimeLease runtimeLease) {
        this(runtimeLease, runtimeLease.runtime().runtimeDataFingerprint());
    }

    private ResourcePackStorage(final RuntimeLease runtimeLease, final String rewriterFingerprint) {
        this.rewriterFingerprint = Objects.requireNonNull(
                rewriterFingerprint, "rewriterFingerprint");
        this.runtimeLease = runtimeLease;
        final SharedPackRuntime runtime = runtimeLease.runtime();
        if (!this.rewriterFingerprint.equals(runtime.runtimeDataFingerprint())) {
            throw new IllegalArgumentException(
                    "Resource pack storage fingerprint does not match its shared runtime");
        }
        this.runtimeStackKey = runtime.key();
        this.legacyPackStackBottomToTop = List.of();
        this.legacyPackStackTopToBottom = List.of();
        this.texts = runtime.texts();
        this.blocks = runtime.blocks();
        this.items = ItemDefinitions.sessionOverlay(runtime.items());
        this.attachables = runtime.attachables();
        this.textures = runtime.textures();
        this.sounds = runtime.sounds();
        this.particles = runtime.particles();
        this.entities = runtime.entities();
        this.models = runtime.models();
        this.fogs = runtime.fogs();
        this.biomes = runtime.biomes();
        this.renderControllers = runtime.renderControllers();
    }

    public Collection<ResourcePack> getPackStackBottomToTop() {
        if (this.runtimeLease == null) return this.legacyPackStackBottomToTop;
        final PackStackScope scoped = this.packStackScope.get();
        return scoped != null ? scoped.stack(this.runtimeLease).bottomToTop()
                : this.runtimeLease.runtime().packStackBottomToTop();
    }

    public Collection<ResourcePack> getPackStackTopToBottom() {
        if (this.runtimeLease == null) return this.legacyPackStackTopToBottom;
        final PackStackScope scoped = this.packStackScope.get();
        return scoped != null ? scoped.stack(this.runtimeLease).topToBottom()
                : this.runtimeLease.runtime().packStackTopToBottom();
    }

    public List<PackMount> getPackMountsBottomToTop() {
        if (this.runtimeLease != null) return this.runtimeLease.runtime().packMountsBottomToTop();
        return mounts(this.legacyPackStackBottomToTop);
    }

    public List<PackMount> getPackMountsTopToBottom() {
        if (this.runtimeLease != null) return this.runtimeLease.runtime().packMountsTopToBottom();
        return mounts(this.legacyPackStackTopToBottom);
    }

    /** 为一次同步的原始内容操作保持规范包文件被固定。 */
    public <T> T withPackStack(final PackStackOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        if (this.runtimeLease == null || this.packStackScope.get() != null) {
            return operation.run();
        }
        try (PackStackLease scope = this.runtimeLease.openPackStack()) {
            this.packStackScope.set(new PackStackScope(scope));
            try {
                return operation.run();
            } finally {
                this.packStackScope.remove();
            }
        }
    }

    /** 仅当兼容重写器确实请求原始包内容时才打开规范栈。 */
    public <T> T withLazyPackStack(final PackStackOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        if (this.runtimeLease == null || this.packStackScope.get() != null) {
            return operation.run();
        }
        final PackStackScope scope = new PackStackScope(null);
        this.packStackScope.set(scope);
        try {
            return operation.run();
        } finally {
            this.packStackScope.remove();
            scope.close();
        }
    }

    public boolean isLoadedOnJavaClient() {
        return this.loadedOnJavaClient;
    }

    public void setLoadedOnJavaClient() {
        this.loadedOnJavaClient = true;
    }

    public boolean isSupportsFreeRotation() {
        return this.supportsFreeRotation;
    }

    public void setSupportsFreeRotation(final boolean supportsFreeRotation) {
        this.supportsFreeRotation = supportsFreeRotation;
    }

    public Map<String, Object> getConverterData() {
        return this.converterData;
    }

    /** 由资源包栈派生的转换/运行时数据，仅在资源包栈键完全相同的会话之间共享。 */
    public Map<String, Object> getRuntimeData() {
        return this.runtimeLease != null ? this.runtimeLease.runtime().converterData() : this.converterData;
    }

    /** 仅在初始化共享运行时时使用的内部写入路径。 */
    public Object putRuntimeData(final String key, final Object value) {
        return this.runtimeLease != null
                ? this.runtimeLease.runtime().putConverterDataDuringInitialization(key, value)
                : this.converterData.put(key, value);
    }

    public CompletableFuture<Void> putRuntimeInitializationIfAbsent(
            final String key, final CompletableFuture<Void> completion, final boolean shared) {
        if (shared && this.runtimeLease != null) {
            return this.runtimeLease.runtime().putInitializationIfAbsent(key, completion);
        }
        return this.sessionInitializationFutures.putIfAbsent(key, completion);
    }

    public boolean removeRuntimeInitialization(
            final String key, final CompletableFuture<Void> completion, final boolean shared) {
        if (shared && this.runtimeLease != null) {
            return this.runtimeLease.runtime().removeInitialization(key, completion);
        }
        return this.sessionInitializationFutures.remove(key, completion);
    }

    public RuntimeStackKey getRuntimeStackKey() {
        return this.runtimeStackKey;
    }

    public String getExactArtifactCacheKey() {
        return this.getExactArtifactKey().hex();
    }

    public ArtifactKey getExactArtifactKey() {
        return this.runtimeLease != null
                ? this.runtimeLease.runtime().artifactKey(
                        this.supportsFreeRotation, this.rewriterFingerprint)
                : new ArtifactKey(JavaPackCache.computeExactCacheKey(
                        this.legacyPackStackTopToBottom, this.supportsFreeRotation,
                        this.rewriterFingerprint));
    }

    public String getRewriterFingerprint() {
        return this.rewriterFingerprint;
    }

    public PackManager getBedrockMotionPackManager() {
        if (this.runtimeLease != null) {
            return this.runtimeLease.runtime().bedrockMotionPackManager();
        }
        final Object legacy = this.converterData.get(ResourcePackRewriter.BEDROCK_MOTION_PACK_MANAGER_KEY);
        return legacy instanceof PackManager packManager ? packManager : null;
    }

    public Set<String> getSharedCustomSoundNames() {
        return this.runtimeLease != null ? this.runtimeLease.runtime().customSoundNames() : null;
    }

    public RuntimeLease retainRuntimeLease() {
        return this.runtimeLease != null ? this.runtimeLease.retain() : null;
    }

    public void refreshSharedRuntimeWeight() {
        if (this.runtimeLease != null) {
            this.runtimeLease.refreshRetainedWeight();
        }
    }

    public void rejectSharedRuntime(final Throwable failure) {
        if (this.runtimeLease != null) {
            this.runtimeLease.rejectRuntime(failure);
        }
    }

    public <T> CompletableFuture<T> retainRuntimeDuring(
            final Supplier<CompletableFuture<T>> operation) {
        final RuntimeLease retained = this.retainRuntimeLease();
        final CompletableFuture<T> source;
        try {
            source = Objects.requireNonNull(operation.get(), "operation returned null future");
        } catch (RuntimeException | Error error) {
            if (retained != null) retained.close();
            throw error;
        }
        if (retained != null) {
            source.whenComplete((value, error) -> retained.close());
        }
        return source.thenApply(value -> value);
    }

    @Override
    public void onRemove() {
        if (this.runtimeLease != null) {
            this.runtimeLease.close();
        }
    }

    public TextDefinitions getTexts() {
        return this.texts;
    }

    public BlockDefinitions getBlocks() {
        return this.blocks;
    }

    public ItemDefinitions getItems() {
        return this.items;
    }

    public AttachableDefinitions getAttachables() {
        return this.attachables;
    }

    public TextureDefinitions getTextures() {
        return this.textures;
    }

    public SoundDefinitions getSounds() {
        return this.sounds;
    }

    public ParticleDefinitions getParticles() {
        return this.particles;
    }

    public EntityDefinitions getEntities() {
        return this.entities;
    }

    public ModelDefinitions getModels() {
        return this.models;
    }

    public FogDefinitions getFogs() {
        return this.fogs;
    }

    public BiomeDefinitions getBiomes() {
        return this.biomes;
    }

    public RenderControllerDefinitions getRenderControllers() {
        return this.renderControllers;
    }

    private static List<PackMount> mounts(final Collection<ResourcePack> packs) {
        return packs.stream().map(pack -> new PackMount(
                PackAlias.from(pack.key()), ContentDigest.compute(pack.content()))).toList();
    }

    @FunctionalInterface
    public interface PackStackOperation<T> {

        T run() throws IOException;
    }

    private static final class PackStackScope implements AutoCloseable {

        private PackStackLease stack;

        private PackStackScope(final PackStackLease stack) {
            this.stack = stack;
        }

        private PackStackLease stack(final RuntimeLease runtimeLease) {
            if (this.stack == null) {
                try {
                    this.stack = runtimeLease.openPackStack();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(
                            "Failed to materialize shared resource pack content", e);
                }
            }
            return this.stack;
        }

        @Override
        public void close() {
            if (this.stack != null) this.stack.close();
        }
    }

}
