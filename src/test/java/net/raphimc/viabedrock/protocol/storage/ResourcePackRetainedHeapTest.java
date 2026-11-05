/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.ServerAnimationLayer;
import net.easecation.bedrockmotion.pack.definitions.AnimationControllerDefinitions;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import net.raphimc.viabedrock.experimental.model.animation.ServerEntityTicker;
import net.raphimc.viabedrock.experimental.model.animation.SimpleBone;
import net.raphimc.viabedrock.experimental.model.animation.SimpleBoneModel;
import net.raphimc.viabedrock.experimental.resourcepack.cache.FrozenPackBlob;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntime;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.util.Multiset;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 针对共享资源包与动画图的可选保留堆门禁。 */
@Tag("performance")
@EnabledIfEnvironmentVariable(named = "VIABEDROCK_RETAINED_HEAP", matches = "(?i)true")
class ResourcePackRetainedHeapTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void oneTenAndTwentyAnimatedSessionsStayWithinRetainedHeapBudget(
            @TempDir final Path tempDir) throws Exception {
        final Fixture fixture = fixture(tempDir);
        final String previousJolMagicFieldOffset = System.setProperty("jol.magicFieldOffset", "true");
        final List<AnimatedSession> sessions = new ArrayList<>();
        try {
            final ResourcePack pack = AnimatedResourcePackTestFixture.loadCanonical(
                    fixture.archiveStore(), UUID.randomUUID(), 8 * 1024 * 1024);
            final ContentDigest contentDigest =
                    assertInstanceOf(ZipFileContent.class, pack.content()).contentDigest();
            growSessions(fixture.cache(), pack, sessions, 1);
            final AnimatedSession first = sessions.getFirst();
            final SharedPackRuntime sharedRuntime = first.runtime();
            final ParsedPackLayer sharedLayer = findLayer(sharedRuntime, pack.key());
            final PackManager sharedPackManager = first.storage().getBedrockMotionPackManager();
            final Object sharedAnimationDefinitions = sharedPackManager
                    .getAnimationDefinitions().getAnimations();
            final Object sharedAnimation = sharedPackManager.getAnimationDefinitions().getAnimations()
                    .get(AnimatedResourcePackTestFixture.STANDALONE_ANIMATION_IDENTIFIER);
            final Object sharedControllerDefinitions = sharedPackManager
                    .getAnimationControllerDefinitions().getControllers();
            final Object sharedController = sharedPackManager.getAnimationControllerDefinitions().getControllers()
                    .get(AnimatedResourcePackTestFixture.CONTROLLER_IDENTIFIER);
            final EntityDefinitions.EntityDefinition sharedEntity = first.storage().getEntities()
                    .get(AnimatedResourcePackTestFixture.ENTITY_IDENTIFIER);
            final BedrockGeometryModel sharedModel = first.storage().getModels()
                    .getEntityModel(AnimatedResourcePackTestFixture.GEOMETRY_IDENTIFIER);
            final GraphLayout oneSessionGraph = animationGraph(sessions);
            final SharedAnimationGraphCounts sharedCounts = SharedAnimationGraphCounts.capture(oneSessionGraph);
            final long baseItemDefinitionCount = classCount(oneSessionGraph, ItemDefinitions.class);
            final long oneSessionBytes = oneSessionGraph.totalSize();
            final FrozenPackBlob sharedBlob = fixture.cache().findCompletedBlob(contentDigest);
            final BuildCounts buildCounts = BuildCounts.capture(fixture.metrics());

            assertNotNull(sharedBlob);
            assertTrue(sharedBlob.isProductionCanonical());
            assertBlobCohort(fixture.cache(), contentDigest, sharedBlob);
            assertEquals(1L, buildCounts.blobs());
            assertTrue(buildCounts.layers() >= 1L);
            assertEquals(1L, buildCounts.runtimes());
            assertEquals(1L, buildCounts.motions());
            assertEquals(1L, sharedCounts.runtimes());
            assertTrue(sharedCounts.layers() >= 1L);
            assertTrue(sharedCounts.serverAnimationLayers() >= 1L);
            assertEquals(1L, sharedCounts.packManagers());
            assertTrue(sharedCounts.animationDefinitions() >= 1L);
            assertTrue(sharedCounts.animationData() >= 2L);
            assertTrue(sharedCounts.animationControllerDefinitions() >= 1L);
            assertTrue(sharedCounts.animationControllers() >= 1L);
            assertTrue(sharedCounts.entityDefinitions() >= 1L);
            assertTrue(sharedCounts.geometryModels() >= 1L);
            assertCohort(sessions, 1, sharedRuntime, sharedLayer, sharedPackManager,
                    sharedAnimationDefinitions, sharedAnimation, sharedControllerDefinitions,
                    sharedController, sharedEntity, sharedModel, sharedCounts, baseItemDefinitionCount);

            growSessions(fixture.cache(), pack, sessions, 10);
            final GraphLayout tenSessionGraph = animationGraph(sessions);
            assertCohort(sessions, 10, sharedRuntime, sharedLayer, sharedPackManager,
                    sharedAnimationDefinitions, sharedAnimation, sharedControllerDefinitions,
                    sharedController, sharedEntity, sharedModel, sharedCounts, baseItemDefinitionCount);
            assertBlobCohort(fixture.cache(), contentDigest, sharedBlob);
            assertEquals(buildCounts, BuildCounts.capture(fixture.metrics()));
            assertWithinSessionAllowance(
                    "10 animated sessions", oneSessionBytes, tenSessionGraph.totalSize(), 10L * MIB);

            growSessions(fixture.cache(), pack, sessions, 20);
            final GraphLayout twentySessionGraph = animationGraph(sessions);
            assertCohort(sessions, 20, sharedRuntime, sharedLayer, sharedPackManager,
                    sharedAnimationDefinitions, sharedAnimation, sharedControllerDefinitions,
                    sharedController, sharedEntity, sharedModel, sharedCounts, baseItemDefinitionCount);
            assertBlobCohort(fixture.cache(), contentDigest, sharedBlob);
            assertEquals(buildCounts, BuildCounts.capture(fixture.metrics()));
            assertWithinSessionAllowance(
                    "20 animated sessions", oneSessionBytes, twentySessionGraph.totalSize(), 20L * MIB);

            System.out.printf(
                    "Animated retained heap bytes: sessions=1:%d, sessions=10:%d, sessions=20:%d%n",
                    oneSessionBytes, tenSessionGraph.totalSize(), twentySessionGraph.totalSize());

            assertAnimationStateIsolation(sessions);
        } finally {
            sessions.forEach(session -> session.storage().onRemove());
            assertEquals(0L, fixture.metrics().getActiveRuntimeLeases());
            fixture.scheduler().shutdown();
            if (previousJolMagicFieldOffset == null) {
                System.clearProperty("jol.magicFieldOffset");
            } else {
                System.setProperty("jol.magicFieldOffset", previousJolMagicFieldOffset);
            }
        }
    }

    private static Fixture fixture(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock-retained-heap.yml");
        Files.writeString(configPath,
                "enable-server-entity-animation: true\nresource-pack-cache:\n"
                        + "  memory-budget-mib: 64\n  memory-hard-limit-mib: 128\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                tempDir.resolve("server-packs"), scheduler, metrics, config);
        return new Fixture(new SharedPackRuntimeCache(config, metrics, scheduler, archiveStore),
                archiveStore, metrics, scheduler);
    }

    private static void growSessions(final SharedPackRuntimeCache cache, final ResourcePack pack,
                                     final List<AnimatedSession> sessions, final int targetCount) {
        while (sessions.size() < targetCount) {
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(pack));
            final ResourcePackStorage storage = new ResourcePackStorage(lease);
            try {
                sessions.add(new AnimatedSession(
                        lease.runtime(), storage, AnimatedResourcePackTestFixture.ticker(storage)));
            } catch (RuntimeException | Error failure) {
                storage.onRemove();
                throw failure;
            }
        }
    }

    private static void assertCohort(
            final List<AnimatedSession> sessions, final int expectedSessions,
            final SharedPackRuntime sharedRuntime, final ParsedPackLayer sharedLayer,
            final PackManager sharedPackManager, final Object sharedAnimationDefinitions,
            final Object sharedAnimation, final Object sharedControllerDefinitions,
            final Object sharedController, final EntityDefinitions.EntityDefinition sharedEntity,
            final BedrockGeometryModel sharedModel, final SharedAnimationGraphCounts sharedCounts,
            final long baseItemDefinitionCount) {
        assertEquals(expectedSessions, sessions.size());
        final GraphLayout graph = animationGraph(sessions);
        assertEquals(sharedCounts, SharedAnimationGraphCounts.capture(graph));
        assertEquals(expectedSessions, classCount(graph, ResourcePackStorage.class));
        assertEquals(expectedSessions, classCount(graph, ServerEntityTicker.class));
        assertEquals(expectedSessions, classCount(graph, SimpleBoneModel.class));
        assertEquals(expectedSessions, classCount(graph, SimpleBone.class));
        assertEquals(expectedSessions, classCount(graph, AnimationControllerInstance.class));
        assertEquals(expectedSessions * 2L, classCount(graph, Animator.class));
        assertEquals(baseItemDefinitionCount + expectedSessions - 1L,
                classCount(graph, ItemDefinitions.class));

        final Set<ResourcePackStorage> storages = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ItemDefinitions> overlays = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ServerEntityTicker> tickers = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<SimpleBoneModel> boneModels = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<SimpleBone> rootBones = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Scope> entityScopes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AnimatedSession session : sessions) {
            final ResourcePackStorage storage = session.storage();
            assertTrue(storages.add(storage));
            assertTrue(overlays.add(storage.getItems()));
            assertTrue(tickers.add(session.ticker()));
            assertTrue(boneModels.add(session.ticker().getBoneModel()));
            assertTrue(rootBones.add(AnimatedResourcePackTestFixture.rootBone(session.ticker())));
            assertTrue(entityScopes.add(session.ticker().getEntityScope()));
            assertSame(sharedRuntime, session.runtime());
            assertSame(sharedLayer, findLayer(session.runtime(), sharedLayer.sourceKey()));
            assertSame(sharedPackManager, storage.getBedrockMotionPackManager());
            assertSame(sharedAnimationDefinitions,
                    storage.getBedrockMotionPackManager().getAnimationDefinitions().getAnimations());
            assertSame(sharedAnimation, storage.getBedrockMotionPackManager().getAnimationDefinitions()
                    .getAnimations().get(AnimatedResourcePackTestFixture.STANDALONE_ANIMATION_IDENTIFIER));
            assertSame(sharedControllerDefinitions,
                    storage.getBedrockMotionPackManager().getAnimationControllerDefinitions().getControllers());
            assertSame(sharedController, storage.getBedrockMotionPackManager().getAnimationControllerDefinitions()
                    .getControllers().get(AnimatedResourcePackTestFixture.CONTROLLER_IDENTIFIER));
            assertSame(sharedEntity,
                    storage.getEntities().get(AnimatedResourcePackTestFixture.ENTITY_IDENTIFIER));
            final BedrockGeometryModel detachedModel = storage.getModels()
                    .getEntityModel(AnimatedResourcePackTestFixture.GEOMETRY_IDENTIFIER);
            assertNotSame(sharedModel, detachedModel);
            assertEquals(sharedModel.getIdentifier(), detachedModel.getIdentifier());
            assertEquals(sharedModel.getParents().size(), detachedModel.getParents().size());
        }
    }

    private static void assertAnimationStateIsolation(final List<AnimatedSession> sessions) {
        final AnimatedSession first = sessions.get(0);
        final AnimatedSession second = sessions.get(1);
        for (AnimatedSession session : sessions) {
            assertDefaultRotation(AnimatedResourcePackTestFixture.rootBone(session.ticker()));
        }

        assertFalse(first.ticker().tick(new MutableObjectBinding()).isEmpty());
        final Vector3f firstRotation = new Vector3f(
                AnimatedResourcePackTestFixture.rootBone(first.ticker()).getRotation());
        assertAnimatedRotation(firstRotation);
        for (int i = 1; i < sessions.size(); i++) {
            assertDefaultRotation(AnimatedResourcePackTestFixture.rootBone(sessions.get(i).ticker()));
        }

        assertFalse(second.ticker().tick(new MutableObjectBinding()).isEmpty());
        assertEquals(firstRotation,
                AnimatedResourcePackTestFixture.rootBone(first.ticker()).getRotation(),
                "Ticking a second session changed the first session bone state");
        assertAnimatedRotation(AnimatedResourcePackTestFixture.rootBone(second.ticker()).getRotation());
        for (int i = 2; i < sessions.size(); i++) {
            assertDefaultRotation(AnimatedResourcePackTestFixture.rootBone(sessions.get(i).ticker()));
        }
    }

    private static void assertAnimatedRotation(final Vector3f rotation) {
        assertEquals(10F, rotation.x, 0.001F,
                "Standalone Animator did not apply its X rotation");
        assertEquals(20F, rotation.y, 0.001F,
                "AnimationControllerInstance did not apply its Y rotation");
        assertEquals(0F, rotation.z, 0.001F);
    }

    private static void assertBlobCohort(final SharedPackRuntimeCache cache,
                                         final ContentDigest contentDigest,
                                         final FrozenPackBlob sharedBlob) {
        assertEquals(1, cache.completedBlobCount());
        assertSame(sharedBlob, cache.findCompletedBlob(contentDigest));
        assertEquals(contentDigest, sharedBlob.contentDigest());
    }

    private static void assertDefaultRotation(final SimpleBone bone) {
        assertEquals(0F, bone.getRotation().x, 0.0001F);
        assertEquals(0F, bone.getRotation().y, 0.0001F);
        assertEquals(0F, bone.getRotation().z, 0.0001F);
    }

    private static GraphLayout animationGraph(final List<AnimatedSession> sessions) {
        final Object[] roots = new Object[sessions.size() * 2];
        for (int i = 0; i < sessions.size(); i++) {
            roots[i * 2] = sessions.get(i).storage();
            roots[i * 2 + 1] = sessions.get(i).ticker();
        }
        return GraphLayout.parseInstance(roots);
    }

    private static long classCount(final GraphLayout graph, final Class<?> type) {
        final Multiset<Class<?>> counts = graph.getClassCounts();
        return counts.count(type);
    }

    private static void assertWithinSessionAllowance(final String cohort, final long baselineBytes,
                                                     final long actualBytes, final long sessionAllowanceBytes) {
        final long limit = Math.addExact((long) Math.ceil(baselineBytes * 1.10D), sessionAllowanceBytes);
        assertTrue(actualBytes <= limit, () -> cohort + " retained " + actualBytes
                + " bytes, above baseline " + baselineBytes + " bytes with limit " + limit + " bytes");
    }

    private static ParsedPackLayer findLayer(final SharedPackRuntime runtime, final ResourcePack.Key key) {
        return runtime.parsedLayersBottomToTop().stream()
                .filter(layer -> layer.sourceKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(SharedPackRuntimeCache cache, ResourcePackArchiveStore archiveStore,
                           ResourcePackCacheMetrics metrics, ResourcePackWorkScheduler scheduler) {
    }

    private record AnimatedSession(SharedPackRuntime runtime, ResourcePackStorage storage,
                                   ServerEntityTicker ticker) {
    }

    private record SharedAnimationGraphCounts(
            long runtimes, long layers, long serverAnimationLayers, long packManagers,
            long animationDefinitions, long animationData, long animationControllerDefinitions,
            long animationControllers, long entityDefinitions, long geometryModels) {

        private static SharedAnimationGraphCounts capture(final GraphLayout graph) {
            return new SharedAnimationGraphCounts(
                    classCount(graph, SharedPackRuntime.class),
                    classCount(graph, ParsedPackLayer.class),
                    classCount(graph, ServerAnimationLayer.class),
                    classCount(graph, PackManager.class),
                    classCount(graph, AnimationDefinitions.class),
                    classCount(graph, AnimationDefinitions.AnimationData.class),
                    classCount(graph, AnimationControllerDefinitions.class),
                    classCount(graph, AnimationController.class),
                    classCount(graph, EntityDefinitions.EntityDefinition.class),
                    classCount(graph, BedrockGeometryModel.class));
        }
    }

    private record BuildCounts(long blobs, long layers, long runtimes, long motions) {

        private static BuildCounts capture(final ResourcePackCacheMetrics metrics) {
            return new BuildCounts(
                    metrics.getBlobBuilds(), metrics.getLayerBuilds(), metrics.getRuntimeBuilds(),
                    metrics.getMotionBuilds());
        }
    }
}
