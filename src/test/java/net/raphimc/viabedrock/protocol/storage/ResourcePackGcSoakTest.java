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

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.experimental.model.animation.ServerEntityTicker;
import net.raphimc.viabedrock.experimental.resourcepack.cache.FrozenPackBlob;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选的本地浸泡测试；等同生产环境的 30 分钟时长即 1800 秒。 */
@Tag("performance")
@EnabledIfEnvironmentVariable(named = "VIABEDROCK_GC_SOAK", matches = "(?i)true")
class ResourcePackGcSoakTest {

    private static final int SESSION_COUNT = 20;
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int MINIMUM_SLOPE_GATE_SECONDS = 600;
    private static final int CHURN_ARRAYS_PER_CYCLE = 128;
    private static final int CHURN_ARRAY_BYTES = 64 * 1024;

    private static volatile Object allocationSink;
    private static volatile long resultSink;

    @Test
    void twentyDiskBackedAnimatedSessionsShareOneRuntimeWithoutFullGcOrOldGenGrowth(
            @TempDir final Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is unavailable in this test JVM");
        final int durationSeconds = integerEnvironment("VIABEDROCK_GC_SOAK_SECONDS", DEFAULT_DURATION_SECONDS, 10);
        final boolean enforceSlope = durationSeconds >= MINIMUM_SLOPE_GATE_SECONDS
                || booleanEnvironment("VIABEDROCK_GC_ENFORCE_SLOPE");
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final List<AnimatedSession> sessions = new ArrayList<>(SESSION_COUNT);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack pack = AnimatedResourcePackTestFixture.loadCanonical(
                    archiveStore, UUID.randomUUID(), 8 * 1024 * 1024);
            final ZipFileContent canonicalContent = assertInstanceOf(ZipFileContent.class, pack.content());
            final ContentDigest contentDigest = canonicalContent.contentDigest();
            for (int i = 0; i < SESSION_COUNT; i++) {
                final ResourcePackStorage storage = new ResourcePackStorage(cache.acquire(List.of(pack)));
                try {
                    sessions.add(new AnimatedSession(
                            storage, AnimatedResourcePackTestFixture.ticker(storage)));
                } catch (RuntimeException | Error failure) {
                    storage.onRemove();
                    throw failure;
                }
            }
            assertEquals(SESSION_COUNT, metrics.getActiveRuntimeLeases());
            assertEquals(1L, metrics.getRuntimeBuilds());
            assertTrue(metrics.getLayerBuilds() >= 1L);
            assertEquals(1L, metrics.getMotionBuilds());
            assertEquals(1L, metrics.getArchiveBuilds());
            assertEquals(1L, metrics.getContentBuilds());
            assertEquals(1, cache.completedBlobCount());
            final FrozenPackBlob sharedBlob = cache.findCompletedBlob(contentDigest);
            assertNotNull(sharedBlob);
            assertTrue(sharedBlob.isProductionCanonical());
            final long layerBuilds = metrics.getLayerBuilds();
            final Set<ServerEntityTicker> tickers = Collections.newSetFromMap(new IdentityHashMap<>());
            sessions.forEach(session -> assertTrue(tickers.add(session.ticker())));

            runSteadyWorkload(sessions, Duration.ofSeconds(3));
            final Path recordingPath = tempDir.resolve("resource-pack-gc-soak.jfr");
            try (Recording recording = new Recording()) {
                recording.enable("jdk.GarbageCollection");
                recording.enable("jdk.GCPhasePause");
                recording.enable("jdk.GCPhasePauseLevel1");
                recording.enable("jdk.G1HeapSummary");
                recording.start();
                runSteadyWorkload(sessions, Duration.ofSeconds(durationSeconds));
                recording.stop();
                recording.dump(recordingPath);
            } finally {
                allocationSink = null;
            }

            final GcSoakResult result = analyze(recordingPath, Runtime.getRuntime().maxMemory());
            System.out.printf(Locale.ROOT,
                    "GC_SOAK sessions=%d durationSeconds=%d gcCount=%d oldGenSamples=%d "
                            + "oldGenSlopeMiBPer10Min=%.3f oldGenSlopePctXmxPer10Min=%.4f fullGc=%s%n",
                    SESSION_COUNT, durationSeconds, result.gcCount(), result.oldGenSamples(),
                    result.oldGenSlopeBytesPerSecond() * 600D / (1024D * 1024D),
                    result.oldGenSlopePercentXmxPerTenMinutes(), result.fullGcNames());

            assertTrue(result.gcCount() > 0, "Controlled allocation churn did not trigger a GC");
            assertTrue(result.oldGenSamples() >= 3, "Too few post-GC old-generation samples");
            assertTrue(result.fullGcNames().isEmpty(), "Full GC observed: " + result.fullGcNames());
            if (enforceSlope) {
                assertTrue(result.oldGenSlopePercentXmxPerTenMinutes() < 1D,
                        "Old-generation slope exceeds 1% Xmx per 10 minutes: "
                                + result.oldGenSlopePercentXmxPerTenMinutes());
            }
            assertEquals(1L, metrics.getRuntimeBuilds());
            assertEquals(layerBuilds, metrics.getLayerBuilds());
            assertEquals(1L, metrics.getMotionBuilds());
            assertEquals(1L, metrics.getArchiveBuilds());
            assertEquals(1L, metrics.getContentBuilds());
            assertEquals(1, cache.completedBlobCount());
            assertEquals(contentDigest, sharedBlob.contentDigest());
            assertSame(sharedBlob, cache.findCompletedBlob(contentDigest));
            assertEquals(SESSION_COUNT, metrics.getActiveRuntimeLeases());
        } finally {
            for (AnimatedSession session : sessions) {
                session.storage().onRemove();
            }
            scheduler.shutdown();
        }
    }

    private static void runSteadyWorkload(final List<AnimatedSession> sessions, final Duration duration)
            throws InterruptedException {
        final long deadline = System.nanoTime() + duration.toNanos();
        long cycles = 0L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < sessions.size(); i++) {
                final AnimatedSession session = sessions.get(i);
                session.storage().setSupportsFreeRotation((cycles + i & 1L) == 0L);
                resultSink ^= session.storage().getExactArtifactCacheKey().hashCode();
                resultSink += session.storage().getPackStackTopToBottom().size();
                resultSink += session.ticker().tick(new MutableObjectBinding()).size();
            }
            for (int i = 0; i < CHURN_ARRAYS_PER_CYCLE; i++) {
                allocationSink = new byte[CHURN_ARRAY_BYTES];
            }
            cycles++;
            Thread.sleep(100L);
        }
    }

    private static GcSoakResult analyze(final Path recordingPath, final long maxHeapBytes) throws Exception {
        final List<OldGenSample> samples = new ArrayList<>();
        final List<String> fullGcNames = new ArrayList<>();
        int gcCount = 0;
        for (RecordedEvent event : RecordingFile.readAllEvents(recordingPath)) {
            switch (event.getEventType().getName()) {
                case "jdk.GarbageCollection" -> {
                    gcCount++;
                    final String name = event.getString("name");
                    final String cause = event.getString("cause");
                    if (isFullGc(name) || isFullGc(cause)) {
                        fullGcNames.add(name + " (" + cause + ")");
                    }
                }
                case "jdk.GCPhasePause", "jdk.GCPhasePauseLevel1" -> {
                    final String name = event.getString("name");
                    if (isFullGc(name)) {
                        fullGcNames.add(name);
                    }
                }
                case "jdk.G1HeapSummary" -> {
                    if ("After GC".equals(event.getString("when"))) {
                        samples.add(new OldGenSample(event.getStartTime(), event.getLong("oldGenUsedSize")));
                    }
                }
                default -> {
                }
            }
        }
        final List<OldGenSample> steadySamples = samples.size() < 4
                ? samples : samples.subList(samples.size() / 2, samples.size());
        final double slopeBytesPerSecond = linearRegressionSlope(steadySamples);
        final double positiveSlope = Math.max(0D, slopeBytesPerSecond);
        return new GcSoakResult(gcCount, samples.size(), slopeBytesPerSecond,
                positiveSlope * 600D * 100D / maxHeapBytes, List.copyOf(fullGcNames));
    }

    private static boolean isFullGc(final String value) {
        if (value == null) {
            return false;
        }
        final String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("full") || normalized.contains("marksweep")
                || normalized.contains("serial old") || normalized.contains("system.gc");
    }

    static double linearRegressionSlope(final List<OldGenSample> samples) {
        if (samples.size() < 2) {
            return 0D;
        }
        final long originNanos = epochNanos(samples.getFirst().time());
        double sumX = 0D;
        double sumY = 0D;
        for (OldGenSample sample : samples) {
            sumX += (epochNanos(sample.time()) - originNanos) / 1_000_000_000D;
            sumY += sample.usedBytes();
        }
        final double meanX = sumX / samples.size();
        final double meanY = sumY / samples.size();
        double numerator = 0D;
        double denominator = 0D;
        for (OldGenSample sample : samples) {
            final double x = (epochNanos(sample.time()) - originNanos) / 1_000_000_000D - meanX;
            numerator += x * (sample.usedBytes() - meanY);
            denominator += x * x;
        }
        return denominator == 0D ? 0D : numerator / denominator;
    }

    private static long epochNanos(final Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private static ViaBedrockConfig config(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, "enable-server-entity-animation: true\n"
                + "resource-pack-cache:\n"
                + "  memory-budget-mib: 128\n"
                + "  memory-hard-limit-mib: 256\n"
                + "  cpu-workers: 2\n"
                + "  io-workers: 2\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(
                configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    private static int integerEnvironment(final String name, final int fallback, final int minimum) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Math.max(minimum, Integer.parseInt(value));
    }

    private static boolean booleanEnvironment(final String name) {
        return Boolean.parseBoolean(System.getenv(name));
    }

    record OldGenSample(Instant time, long usedBytes) {
    }

    private record GcSoakResult(int gcCount, int oldGenSamples, double oldGenSlopeBytesPerSecond,
                                double oldGenSlopePercentXmxPerTenMinutes, List<String> fullGcNames) {
    }

    private record AnimatedSession(ResourcePackStorage storage, ServerEntityTicker ticker) {
    }

}
