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

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import com.viaversion.viaversion.util.Key;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class JavaSoundCatalog {

    // 内置目录由 Mojang 的 sounds.json 生成，并已去除文件名和非声学字段。
    private static final Variant DEFAULT_VARIANT = new Variant(1F, 1F, 16);

    private final Map<String, List<Entry>> events;
    private final Map<String, Integer> totalWeightCache = new ConcurrentHashMap<>();

    JavaSoundCatalog(final JsonObject soundsJson) {
        this.events = new HashMap<>(soundsJson.size());
        for (Map.Entry<String, JsonElement> event : soundsJson.entrySet()) {
            if (!event.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject eventData = event.getValue().getAsJsonObject();
            if (!eventData.has("sounds") || !eventData.get("sounds").isJsonArray()) {
                continue;
            }

            final List<Entry> entries = new ArrayList<>();
            for (JsonElement soundElement : eventData.getAsJsonArray("sounds")) {
                if (soundElement.isJsonPrimitive()) {
                    entries.add(Entry.file(1F, 1F, 1, 16));
                    continue;
                }
                if (!soundElement.isJsonObject()) {
                    continue;
                }
                final JsonObject sound = soundElement.getAsJsonObject();
                final float volume = sound.has("volume") ? sound.get("volume").getAsFloat() : 1F;
                final float pitch = sound.has("pitch") ? sound.get("pitch").getAsFloat() : 1F;
                final int weight = sound.has("weight") ? Math.max(1, sound.get("weight").getAsInt()) : 1;
                if (sound.has("type") && "event".equals(sound.get("type").getAsString())) {
                    if (sound.has("name")) {
                        entries.add(Entry.event(normalize(sound.get("name").getAsString()), volume, pitch));
                    }
                } else {
                    final int attenuationDistance = sound.has("attenuation_distance")
                            ? Math.max(1, sound.get("attenuation_distance").getAsInt()) : 16;
                    entries.add(Entry.file(volume, pitch, weight, attenuationDistance));
                }
            }
            this.events.put(normalize(event.getKey()), List.copyOf(entries));
        }
    }

    public static Variant resolve(final ProtocolVersion protocolVersion, final String identifier, final long seed) {
        final JavaSoundCatalog catalog;
        if (ProtocolVersion.v1_21_7.equals(protocolVersion)) { // 1.21.7 与 1.21.8
            catalog = Java1218.INSTANCE;
        } else if (ProtocolVersion.v1_21_9.equals(protocolVersion)) { // 1.21.9 与 1.21.10
            catalog = Java12110.INSTANCE;
        } else if (ProtocolVersion.v1_21_11.equals(protocolVersion)) {
            catalog = Java12111.INSTANCE;
        } else if (ProtocolVersion.v26_1.equals(protocolVersion)) {
            catalog = Java261.INSTANCE;
        } else {
            catalog = null;
        }
        if (catalog != null) {
            final Variant variant = catalog.resolve(identifier, new Random(seed), 1F, 1F, new HashSet<>());
            if (variant != null) return variant;
        }
        return DEFAULT_VARIANT;
    }

    Variant resolve(final String identifier, final long seed) {
        return this.resolve(identifier, new Random(seed), 1F, 1F, new HashSet<>());
    }

    private Variant resolve(final String identifier, final Random random, final float volumeMultiplier,
                            final float pitchMultiplier, final Set<String> resolving) {
        final String normalizedIdentifier = normalize(identifier);
        if (!resolving.add(normalizedIdentifier)) {
            return null;
        }
        try {
            final List<Entry> entries = this.events.get(normalizedIdentifier);
            final int totalWeight = this.totalWeight(normalizedIdentifier, new HashSet<>());
            if (entries == null || entries.isEmpty() || totalWeight <= 0) {
                return null;
            }

            int selection = random.nextInt(totalWeight);
            for (Entry entry : entries) {
                final int weight = entry.eventReference() != null
                        ? this.totalWeight(entry.eventReference(), new HashSet<>()) : entry.weight();
                selection -= weight;
                if (selection >= 0) {
                    continue;
                }
                if (entry.eventReference() != null) {
                    return this.resolve(entry.eventReference(), random,
                            volumeMultiplier * entry.volume(), pitchMultiplier * entry.pitch(), resolving);
                }
                return new Variant(volumeMultiplier * entry.volume(), pitchMultiplier * entry.pitch(),
                        entry.attenuationDistance());
            }
            return null;
        } finally {
            resolving.remove(normalizedIdentifier);
        }
    }

    private int totalWeight(final String identifier, final Set<String> resolving) {
        final String normalizedIdentifier = normalize(identifier);
        final Integer cached = this.totalWeightCache.get(normalizedIdentifier);
        if (cached != null) {
            return cached;
        }
        if (!resolving.add(normalizedIdentifier)) {
            return 0;
        }
        long total = 0;
        final List<Entry> entries = this.events.get(normalizedIdentifier);
        if (entries != null) {
            for (Entry entry : entries) {
                total += entry.eventReference() != null
                        ? this.totalWeight(entry.eventReference(), resolving) : entry.weight();
                if (total >= Integer.MAX_VALUE) {
                    total = Integer.MAX_VALUE;
                    break;
                }
            }
        }
        resolving.remove(normalizedIdentifier);
        final int result = (int) total;
        this.totalWeightCache.put(normalizedIdentifier, result);
        return result;
    }

    private static String normalize(final String identifier) {
        return Key.stripMinecraftNamespace(identifier);
    }

    private static JavaSoundCatalog load(final String resourcePath) {
        try (InputStream input = JavaSoundCatalog.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing Java sound catalog: " + resourcePath);
            }
            final JsonObject json = GsonUtil.getGson().fromJson(
                    new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
            return new JavaSoundCatalog(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Java sound catalog: " + resourcePath, e);
        }
    }

    private static final class Java1218 {
        private static final JavaSoundCatalog INSTANCE = load(
                "assets/viabedrock/data/java/sounds-1.21.8.json");
    }

    private static final class Java12110 {
        private static final JavaSoundCatalog INSTANCE = load(
                "assets/viabedrock/data/java/sounds-1.21.10.json");
    }

    private static final class Java12111 {
        private static final JavaSoundCatalog INSTANCE = load(
                "assets/viabedrock/data/java/sounds-1.21.11.json");
    }

    private static final class Java261 {
        private static final JavaSoundCatalog INSTANCE = load(
                "assets/viabedrock/data/java/sounds-26.1.json");
    }

    public record Variant(float volume, float pitch, int attenuationDistance) {
    }

    private record Entry(String eventReference, float volume, float pitch, int weight, int attenuationDistance) {

        private static Entry file(final float volume, final float pitch, final int weight,
                                  final int attenuationDistance) {
            return new Entry(null, volume, pitch, weight, attenuationDistance);
        }

        private static Entry event(final String reference, final float volume, final float pitch) {
            return new Entry(reference, volume, pitch, 0, 0);
        }

    }

}
