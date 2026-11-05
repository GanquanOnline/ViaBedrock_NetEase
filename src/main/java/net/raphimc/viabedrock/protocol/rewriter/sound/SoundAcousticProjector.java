/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.sound;

import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.random.RandomGenerator;

public final class SoundAcousticProjector {

    private static final float BLOCKS_PER_VOLUME = 16F;
    private static final float MAX_FINITE_GAIN = Math.nextDown(1F);
    private static final SoundDefinitions.SoundFile DEFAULT_BEDROCK_VARIANT =
            new SoundDefinitions.SoundFile("", 1F, 1F, 1, true);

    private SoundAcousticProjector() {
    }

    public static Projection project(final SoundDefinitions.SoundDefinition definition, final float eventVolume,
                                     final float eventPitch, final float listenerDistance,
                                     final boolean forceNonSpatial, final JavaSoundCatalog.Variant javaVariant,
                                     final RandomGenerator random) {
        final SoundDefinitions.SoundFile bedrockVariant = selectBedrockVariant(definition, random);
        final float safeEventVolume = nonNegativeFinite(eventVolume);
        final float sourceGain = Math.min(safeEventVolume, 1F) * nonNegativeFinite(bedrockVariant.volume());
        if (sourceGain <= 0F) {
            return Projection.cancelled(bedrockVariant);
        }

        final boolean nonSpatial = forceNonSpatial || !bedrockVariant.is3D()
                || ignoresDistance(definition != null ? definition.category() : null);
        final float audibleRange = nonSpatial ? Float.POSITIVE_INFINITY
                : audibleRange(definition, safeEventVolume);
        if (!nonSpatial && listenerDistance > audibleRange) {
            return Projection.cancelled(bedrockVariant, audibleRange);
        }

        final float attenuation = nonSpatial ? 1F : bedrockAttenuation(definition, listenerDistance, audibleRange);
        final float targetGain = Math.min(1F, sourceGain * attenuation);
        if (targetGain <= 0F) {
            return Projection.cancelled(bedrockVariant, audibleRange);
        }

        final float javaFileVolume = positiveOrDefault(javaVariant.volume(), 1F);
        final float javaFilePitch = positiveOrDefault(javaVariant.pitch(), 1F);
        final float packetVolume = nonSpatial
                ? finiteVolume(targetGain / javaFileVolume)
                : solveJavaPacketVolume(targetGain, listenerDistance, javaFileVolume,
                        Math.max(1, javaVariant.attenuationDistance()));
        final float safeEventPitch = finiteOrDefault(eventPitch, 1F);
        final float bedrockPitch = positiveOrDefault(bedrockVariant.pitch(), 1F);
        final float packetPitch = nonNegativeFinite(safeEventPitch * bedrockPitch / javaFilePitch);
        return new Projection(true, packetVolume, packetPitch, nonSpatial, audibleRange, targetGain,
                bedrockVariant);
    }

    public static JavaSoundCatalog.Variant resolveGeneratedJavaVariant(
            final SoundDefinitions.SoundDefinition definition, final long seed) {
        final SoundDefinitions.SoundFile variant = selectBedrockVariant(definition, new Random(seed));
        return new JavaSoundCatalog.Variant(Math.max(0F, variant.volume()),
                positiveOrDefault(variant.pitch(), 1F), 16);
    }

    static float audibleRange(final SoundDefinitions.SoundDefinition definition, final float eventVolume) {
        // 基岩用事件音量决定可达距离，但在把文件音量应用到响度之前会先对其做钳制。
        final float volumeRange = Math.max(0F, eventVolume) * BLOCKS_PER_VOLUME;
        if (definition == null || definition.maxDistance() == null
                || !Float.isFinite(definition.maxDistance())) {
            return volumeRange;
        }
        return Math.max(0F, Math.min(definition.maxDistance(), Math.max(volumeRange, BLOCKS_PER_VOLUME)));
    }

    static float bedrockAttenuation(final SoundDefinitions.SoundDefinition definition, final float distance,
                                    final float audibleRange) {
        final float minDistance = definition != null ? Math.max(0F, definition.minDistance()) : 0F;
        if (distance <= minDistance) {
            return 1F;
        }
        final float attenuationEnd = definition != null && definition.maxDistance() != null
                && Float.isFinite(definition.maxDistance())
                ? Math.max(0F, definition.maxDistance()) : audibleRange;
        if (attenuationEnd <= minDistance) {
            return distance <= audibleRange ? 1F : 0F;
        }
        final float remaining = clamp01(1F - ((distance - minDistance) / (attenuationEnd - minDistance)));
        // 基岩文档给出的曲线是近似的，但明显是凸的，而不是 Java 的线性衰减。
        return remaining * remaining;
    }

    static float javaHeardGain(final float packetVolume, final float listenerDistance,
                               final float fileVolume, final int attenuationDistance) {
        final float combinedVolume = Math.max(0F, packetVolume) * Math.max(0F, fileVolume);
        final float sourceGain = Math.min(combinedVolume, 1F);
        final float range = Math.max(combinedVolume, 1F) * Math.max(1, attenuationDistance);
        return sourceGain * clamp01(1F - (Math.max(0F, listenerDistance) / range));
    }

    private static float solveJavaPacketVolume(final float targetGain, final float listenerDistance,
                                               final float fileVolume, final int attenuationDistance) {
        final float distance = Math.max(0F, listenerDistance);
        final float attenuation = Math.max(1, attenuationDistance);
        final float gainAtCombinedVolumeOne = clamp01(1F - distance / attenuation);

        final float combinedVolume;
        if (distance == 0F) {
            combinedVolume = targetGain;
        } else if (targetGain <= gainAtCombinedVolumeOne && gainAtCombinedVolumeOne > 0F) {
            combinedVolume = targetGain / gainAtCombinedVolumeOne;
        } else {
            // Java 的空间音效只有在其衰减范围趋于无穷大时增益才逼近 1。
            final float representableTarget = Math.min(targetGain, MAX_FINITE_GAIN);
            combinedVolume = distance / (attenuation * (1F - representableTarget));
        }
        return finiteVolume(combinedVolume / fileVolume);
    }

    private static float finiteVolume(final float volume) {
        if (Float.isNaN(volume) || volume <= 0F) {
            return 0F;
        }
        return Float.isFinite(volume) ? volume : Float.MAX_VALUE;
    }

    private static SoundDefinitions.SoundFile selectBedrockVariant(
            final SoundDefinitions.SoundDefinition definition, final RandomGenerator random) {
        final List<SoundDefinitions.SoundFile> variants = definition != null
                ? definition.soundFiles() : List.of();
        if (variants.isEmpty()) {
            return DEFAULT_BEDROCK_VARIANT;
        }
        int totalWeight = 0;
        for (SoundDefinitions.SoundFile variant : variants) {
            final int weight = Math.max(1, variant.weight());
            totalWeight = totalWeight > Integer.MAX_VALUE - weight ? Integer.MAX_VALUE : totalWeight + weight;
        }
        int selection = random.nextInt(totalWeight);
        for (SoundDefinitions.SoundFile variant : variants) {
            selection -= Math.max(1, variant.weight());
            if (selection < 0) {
                return variant;
            }
        }
        return variants.getLast();
    }

    private static boolean ignoresDistance(final String category) {
        if (category == null) {
            return false;
        }
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "music", "ui" -> true;
            default -> false;
        };
    }

    private static float positiveOrDefault(final float value, final float fallback) {
        return Float.isFinite(value) && value > 0F ? value : fallback;
    }

    private static float finiteOrDefault(final float value, final float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static float nonNegativeFinite(final float value) {
        return Float.isFinite(value) ? Math.max(0F, value) : 0F;
    }

    private static float clamp01(final float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    public record Projection(boolean send, float volume, float pitch, boolean nonSpatial, float audibleRange,
                             float targetGain, SoundDefinitions.SoundFile bedrockVariant) {

        private static Projection cancelled(final SoundDefinitions.SoundFile variant) {
            return cancelled(variant, 0F);
        }

        private static Projection cancelled(final SoundDefinitions.SoundFile variant, final float audibleRange) {
            return new Projection(false, 0F, 0F, false, audibleRange, 0F, variant);
        }

    }

}
