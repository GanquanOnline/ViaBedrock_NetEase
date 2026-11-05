/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.ByteTag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import jdk.jfr.EventSettings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.model.EntityEffect;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选的运行时分配门禁。在 JDK 21 上用 {@code VIABEDROCK_JFR_ALLOCATIONS=true} 运行。 */
@Tag("performance")
@EnabledIfEnvironmentVariable(named = "VIABEDROCK_JFR_ALLOCATIONS", matches = "(?i)true")
class HotPathAllocationJfrTest {

    private static final int ENTITY_WARMUP_ITERATIONS = 100_000;
    private static final int ENTITY_RECORDED_ITERATIONS = 500_000;
    private static final int BLOCK_STATE_WARMUP_ITERATIONS = 2_000;
    private static final int BLOCK_STATE_RECORDED_ITERATIONS = 20_000;

    private static volatile Object allocationSink;
    private static volatile long resultSink;

    @Test
    void optimizedHotPathsDoNotExposeRegressedAllocations(@TempDir final Path tempDir) throws Exception {
        assertTrue(FlightRecorder.isAvailable(), "JFR is unavailable in this test JVM");

        final LivingEntity livingEntity = livingEntityWithInfiniteEffect();
        final Entity flaggedEntity = flaggedEntity();
        final BlockStateRewriter blockStateRewriter = blockStateRewriter();
        final List<CompoundTag> warmupBlockStates = dirtyBlockStates(BLOCK_STATE_WARMUP_ITERATIONS);
        final List<CompoundTag> recordedBlockStates = dirtyBlockStates(BLOCK_STATE_RECORDED_ITERATIONS);
        runEntityWorkload(livingEntity, flaggedEntity, ENTITY_WARMUP_ITERATIONS);
        runBlockStateWorkload(blockStateRewriter, warmupBlockStates);

        final Path recordingPath = tempDir.resolve("hot-path-allocations.jfr");
        try (Recording recording = new Recording()) {
            enableAllocationEvent(recording, "jdk.ObjectAllocationInNewTLAB");
            enableAllocationEvent(recording, "jdk.ObjectAllocationOutsideTLAB");
            recording.start();
            positiveControlAllocations();
            runEntityWorkload(livingEntity, flaggedEntity, ENTITY_RECORDED_ITERATIONS);
            runBlockStateWorkload(blockStateRewriter, recordedBlockStates);
            recording.stop();
            recording.dump(recordingPath);
        } finally {
            allocationSink = null;
        }

        final List<AllocationEvent> allocations = readAllocations(recordingPath);
        assertTrue(allocations.stream().anyMatch(event -> event.hasFrame(
                        HotPathAllocationJfrTest.class.getName(), "positiveControlAllocations")),
                "JFR positive control emitted no allocation event");
        assertNoAllocation(allocations, "LivingEntity.tick -> HashSet",
                event -> event.className().equals("java.util.HashSet")
                        && event.hasFrame(LivingEntity.class.getName(), "tick"));
        assertNoAllocation(allocations, "Entity.hasEntityFlag -> ActorFlags[] clone",
                event -> event.className().contains("ActorFlags")
                        && event.hasFrame(Entity.class.getName(), "hasEntityFlag"));
        assertNoAllocation(allocations, "Entity.hasEntityFlag -> BigInteger",
                event -> event.className().equals("java.math.BigInteger")
                        && event.hasFrame(Entity.class.getName(), "hasEntityFlag"));
        assertNoAllocation(allocations, "Entity.hasEntityFlag -> EnumSet",
                event -> (event.className().equals("java.util.JumboEnumSet")
                        || event.className().equals("java.util.RegularEnumSet"))
                        && event.hasFrame(Entity.class.getName(), "hasEntityFlag"));
        assertNoAllocation(allocations, "BlockStateRewriter.bedrockIdOwned -> CompoundTag.copy",
                event -> event.hasFrame(CompoundTag.class.getName(), "copy")
                        && event.hasFrame(BlockStateRewriter.class.getName(), "bedrockIdOwned"));
    }

    private static void enableAllocationEvent(final Recording recording, final String eventName) {
        final EventSettings settings = recording.enable(eventName);
        settings.withStackTrace();
    }

    private static LivingEntity livingEntityWithInfiniteEffect() {
        final LivingEntity entity = new LivingEntity(null, 1L, 2L, "minecraft:zombie", 3,
                UUID.randomUUID(), EntityTypes1_21_11.ZOMBIE);
        final EntityEffect infinite = new EntityEffect("minecraft:strength", 0, -1, true, false);
        entity.effects().put(infinite.identifier(), infinite);
        return entity;
    }

    private static Entity flaggedEntity() {
        final Entity entity = new Entity(null, 1L, 2L, "minecraft:pig", 3,
                UUID.randomUUID(), EntityTypes1_21_11.PIG);
        entity.entityData().put(ActorDataIDs.RESERVED_0, new EntityData(
                ActorDataIDs.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG,
                (1L << ActorFlags.ONFIRE.getValue()) | Long.MIN_VALUE));
        entity.entityData().put(ActorDataIDs.RESERVED_092, new EntityData(
                ActorDataIDs.RESERVED_092.getValue(), EntityDataTypesBedrock.LONG, 1L | Long.MIN_VALUE));
        return entity;
    }

    private static BlockStateRewriter blockStateRewriter() {
        final CompoundTag canonicalStates = new CompoundTag();
        canonicalStates.put("enabled", new ByteTag((byte) 1));
        final CompoundTag canonical = blockState("test:jfr_block", canonicalStates);
        return BlockStateRewriterTestFactory.create(Map.of(BedrockBlockState.fromNbt(canonical), 37));
    }

    private static List<CompoundTag> dirtyBlockStates(final int count) {
        final List<CompoundTag> states = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final CompoundTag properties = new CompoundTag();
            properties.put("enabled", new ByteTag((byte) 0));
            properties.put("unknown", new IntTag(i));
            states.add(blockState("test:JFR_BLOCK", properties));
        }
        return states;
    }

    private static CompoundTag blockState(final String name, final CompoundTag states) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.put("states", states);
        return tag;
    }

    private static void runEntityWorkload(final LivingEntity livingEntity, final Entity flaggedEntity,
                                          final int iterations) {
        long result = 0L;
        for (int i = 0; i < iterations; i++) {
            livingEntity.tick();
            if (flaggedEntity.hasEntityFlag((i & 1) == 0 ? ActorFlags.ONFIRE : ActorFlags.EATING)) {
                result++;
            }
        }
        resultSink = result;
    }

    private static void runBlockStateWorkload(final BlockStateRewriter rewriter,
                                              final List<CompoundTag> blockStates) {
        long result = 0L;
        for (CompoundTag blockState : blockStates) {
            result += rewriter.bedrockIdOwned(blockState);
        }
        resultSink = result;
    }

    private static void positiveControlAllocations() {
        for (int i = 0; i < 8; i++) {
            allocationSink = new byte[2 * 1024 * 1024];
        }
    }

    private static List<AllocationEvent> readAllocations(final Path recordingPath) throws IOException {
        final List<AllocationEvent> allocations = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(recordingPath)) {
            if (!event.getEventType().getName().startsWith("jdk.ObjectAllocation")) {
                continue;
            }
            final List<Frame> frames = event.getStackTrace() == null ? List.of()
                    : event.getStackTrace().getFrames().stream()
                    .filter(RecordedFrame::isJavaFrame)
                    .map(frame -> new Frame(
                            frame.getMethod().getType().getName(), frame.getMethod().getName()))
                    .toList();
            allocations.add(new AllocationEvent(event.getClass("objectClass").getName(), frames));
        }
        return allocations;
    }

    private static void assertNoAllocation(final List<AllocationEvent> allocations, final String description,
                                           final Predicate<AllocationEvent> predicate) {
        final List<AllocationEvent> offenders = allocations.stream().filter(predicate).limit(5).toList();
        assertTrue(offenders.isEmpty(), description + " recorded allocation events: " + offenders);
    }

    private record AllocationEvent(String className, List<Frame> frames) {

        private boolean hasFrame(final String className, final String methodName) {
            return this.frames.stream().anyMatch(frame -> frame.className().equals(className)
                    && frame.methodName().equals(methodName));
        }
    }

    private record Frame(String className, String methodName) {
    }
}
