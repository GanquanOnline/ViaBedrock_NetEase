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
package net.raphimc.viabedrock.api.resourcepack.definition;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.util.JsonUtil;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.*;

// https://wiki.bedrock.dev/concepts/sounds.html
public class SoundDefinitions {

    private final Map<String, SoundDefinition> soundDefinitions;
    private final Map<String, EventSound> eventSounds;
    private final Map<String, EventSounds> entitySounds;
    private final Map<String, EventSounds> blockSounds;

    public SoundDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private SoundDefinitions(final MutableDefinitions definitions) {
        this.soundDefinitions = DefinitionImmutability.map(definitions.soundDefinitions);
        this.eventSounds = DefinitionImmutability.map(definitions.eventSounds);
        this.entitySounds = immutableEventSounds(definitions.entitySounds);
        this.blockSounds = immutableEventSounds(definitions.blockSounds);
    }

    static SoundDefinitions fromPack(final ResourcePack pack) {
        final MutableDefinitions definitions = new MutableDefinitions();
        parsePack(pack, definitions);
        return new SoundDefinitions(definitions);
    }

    static SoundDefinitions fold(final Collection<SoundDefinitions> layersBottomToTop) {
        final MutableDefinitions definitions = new MutableDefinitions();
        for (SoundDefinitions layer : layersBottomToTop) {
            definitions.soundDefinitions.putAll(layer.soundDefinitions);
            definitions.eventSounds.putAll(layer.eventSounds);
            mergeEventSounds(definitions.entitySounds, layer.entitySounds);
            mergeEventSounds(definitions.blockSounds, layer.blockSounds);
        }
        return new SoundDefinitions(definitions);
    }

    private static MutableDefinitions parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final MutableDefinitions definitions = new MutableDefinitions();
        for (ResourcePack pack : packsBottomToTop) {
            final SoundDefinitions layer = fromPack(pack);
            definitions.soundDefinitions.putAll(layer.soundDefinitions);
            definitions.eventSounds.putAll(layer.eventSounds);
            mergeEventSounds(definitions.entitySounds, layer.entitySounds);
            mergeEventSounds(definitions.blockSounds, layer.blockSounds);
        }
        return definitions;
    }

    private static void parsePack(final ResourcePack pack, final MutableDefinitions definitions) {
        if (pack.content().contains("sounds/sound_definitions.json")) {
            try {
                JsonObject soundDefinitions = pack.content().getJson("sounds/sound_definitions.json");
                soundDefinitions = soundDefinitions.has("sound_definitions") ? soundDefinitions.getAsJsonObject("sound_definitions") : soundDefinitions;
                for (Map.Entry<String, JsonElement> entry : soundDefinitions.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        if (entry.getValue().isJsonArray()) {
                            final List<SoundFile> soundFiles = SoundFile.fromSoundsArray(
                                    entry.getValue().getAsJsonArray());
                            definitions.soundDefinitions.put(entry.getKey(),
                                    new SoundDefinition(entry.getKey(), null, 0F, null, soundFiles));
                        }
                        continue;
                    }
                    final JsonObject entryData = entry.getValue().getAsJsonObject();
                    final String category = entryData.has("category") ? entryData.get("category").getAsString() : null;
                    final Float configuredMinDistance = nullableFloat(entryData, "min_distance");
                    final float minDistance = configuredMinDistance != null ? configuredMinDistance : 0F;
                    final Float maxDistance = nullableFloat(entryData, "max_distance");
                    final List<SoundFile> soundFiles;
                    if (entryData.has("sounds")) {
                        if (!entryData.get("sounds").isJsonArray()) {
                            continue;
                        }
                        soundFiles = SoundFile.fromSoundsArray(entryData.getAsJsonArray("sounds"));
                    } else {
                        soundFiles = Collections.emptyList();
                    }
                    definitions.soundDefinitions.put(entry.getKey(),
                            new SoundDefinition(entry.getKey(), category, minDistance, maxDistance, soundFiles));
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse sound_definitions.json in pack " + pack.key(), e);
            }
        }
        if (pack.content().contains("sounds.json")) {
            try {
                final JsonObject sounds = pack.content().getJson("sounds.json");
                if (sounds.has("individual_event_sounds")) {
                    final JsonObject events = sounds.getAsJsonObject("individual_event_sounds").getAsJsonObject("events");
                    for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
                        final ConfiguredSound configuredSound = ConfiguredSound.fromJson(entry.getValue().getAsJsonObject());
                        if (configuredSound != null) {
                            definitions.eventSounds.put(entry.getKey(), new EventSound(entry.getKey(), configuredSound));
                        }
                    }
                }
                if (sounds.has("entity_sounds")) {
                    final JsonObject entitySounds = sounds.getAsJsonObject("entity_sounds");
                    final JsonObject entities = entitySounds.getAsJsonObject("entities");
                    mergeDefaults(entitySounds, entities);
                    parseEvents(entities, true, definitions.entitySounds);
                }
                if (sounds.has("block_sounds")) {
                    parseEvents(sounds.getAsJsonObject("block_sounds"), false, definitions.blockSounds);
                }
                if (sounds.has("interactive_sounds")) {
                    final JsonObject interactiveSounds = sounds.getAsJsonObject("interactive_sounds");
                    if (interactiveSounds.has("entity_sounds")) {
                        final JsonObject entitySounds = interactiveSounds.getAsJsonObject("entity_sounds");
                        final JsonObject entities = entitySounds.getAsJsonObject("entities");
                        mergeDefaults(entitySounds, entities);
                        // 条目可以为每种方块音效使用不同声音。保留默认路径。
                        for (Map.Entry<String, JsonElement> entityEntry : entities.entrySet()) {
                            final JsonObject events = entityEntry.getValue().getAsJsonObject().getAsJsonObject("events");
                            for (Map.Entry<String, JsonElement> eventEntry : events.entrySet()) {
                                if (eventEntry.getValue().isJsonObject() && eventEntry.getValue().getAsJsonObject().has("default")) {
                                    events.add(eventEntry.getKey(), eventEntry.getValue().getAsJsonObject().get("default"));
                                }
                            }
                        }
                        parseEvents(entities, true, definitions.entitySounds);
                    }
                    if (interactiveSounds.has("block_sounds")) {
                        parseEvents(interactiveSounds.getAsJsonObject("block_sounds"), false, definitions.blockSounds);
                    }
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse sounds.json in pack " + pack.key(), e);
            }
        }
    }

    private static Float nullableFloat(final JsonObject object, final String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsFloat();
    }

    private static void parseEvents(final JsonObject sounds, final boolean namespace, final Map<String, EventSounds> soundMap) {
        for (Map.Entry<String, JsonElement> entry : sounds.entrySet()) {
            final JsonObject entity = entry.getValue().getAsJsonObject();
            if (!entity.has("events")) {
                continue;
            }

            final JsonElement volume = entity.has("volume") ? entity.get("volume") : null;
            final JsonElement pitch = entity.has("pitch") ? entity.get("pitch") : null;
            final Map<String, ConfiguredSound> eventSounds = new HashMap<>();
            for (Map.Entry<String, JsonElement> eventEntry : entity.getAsJsonObject("events").entrySet()) {
                if (eventEntry.getValue().isJsonPrimitive()) {
                    final JsonObject eventSound = new JsonObject();
                    eventSound.addProperty("sound", eventEntry.getValue().getAsString());
                    if (volume != null) {
                        eventSound.add("volume", volume);
                    }
                    if (pitch != null) {
                        eventSound.add("pitch", pitch);
                    }
                    final ConfiguredSound configuredSound = ConfiguredSound.fromJson(eventSound);
                    if (configuredSound != null) {
                        eventSounds.put(eventEntry.getKey(), configuredSound);
                    }
                } else {
                    final JsonObject eventSound = eventEntry.getValue().getAsJsonObject();
                    if (!eventSound.has("volume") && volume != null) {
                        eventSound.add("volume", volume);
                    }
                    if (!eventSound.has("pitch") && pitch != null) {
                        eventSound.add("pitch", pitch);
                    }
                    final ConfiguredSound configuredSound = ConfiguredSound.fromJson(eventSound);
                    if (configuredSound != null) {
                        eventSounds.put(eventEntry.getKey(), configuredSound);
                    }
                }
            }
            final String key = namespace ? Key.namespaced(entry.getKey()) : entry.getKey();
            final EventSounds current = soundMap.get(key);
            if (current != null) {
                final Map<String, ConfiguredSound> merged = new LinkedHashMap<>(current.eventSounds());
                merged.putAll(eventSounds);
                soundMap.put(key, new EventSounds(key, merged));
            } else {
                soundMap.put(key, new EventSounds(key, eventSounds));
            }
        }
    }

    private static void mergeDefaults(final JsonObject sounds, final JsonObject target) {
        if (sounds.has("defaults")) {
            final JsonObject defaults = sounds.getAsJsonObject("defaults");
            for (JsonElement value : target.asMap().values()) {
                JsonUtil.merge(value.getAsJsonObject(), defaults);
            }
        }
    }

    private static void mergeEventSounds(final Map<String, EventSounds> target, final Map<String, EventSounds> source) {
        for (Map.Entry<String, EventSounds> entry : source.entrySet()) {
            final Map<String, ConfiguredSound> events = new LinkedHashMap<>();
            final EventSounds current = target.get(entry.getKey());
            if (current != null) {
                events.putAll(current.eventSounds());
            }
            events.putAll(entry.getValue().eventSounds());
            target.put(entry.getKey(), new EventSounds(entry.getKey(), events));
        }
    }

    private static Map<String, EventSounds> immutableEventSounds(final Map<String, EventSounds> source) {
        final Map<String, EventSounds> immutable = new LinkedHashMap<>();
        source.forEach((identifier, sounds) -> immutable.put(identifier,
                new EventSounds(identifier, sounds.eventSounds())));
        return DefinitionImmutability.map(immutable);
    }

    private static final class MutableDefinitions {
        private final Map<String, SoundDefinition> soundDefinitions = new LinkedHashMap<>();
        private final Map<String, EventSound> eventSounds = new LinkedHashMap<>();
        private final Map<String, EventSounds> entitySounds = new LinkedHashMap<>();
        private final Map<String, EventSounds> blockSounds = new LinkedHashMap<>();
    }

    public Map<String, SoundDefinition> soundDefinitions() {
        return Collections.unmodifiableMap(this.soundDefinitions);
    }

    public Map<String, EventSound> eventSounds() {
        return Collections.unmodifiableMap(this.eventSounds);
    }

    public Map<String, EventSounds> entitySounds() {
        return Collections.unmodifiableMap(this.entitySounds);
    }

    public Map<String, EventSounds> blockSounds() {
        return Collections.unmodifiableMap(this.blockSounds);
    }

    public record SoundDefinition(String name, String category, float minDistance, Float maxDistance,
                                  List<SoundFile> soundFiles) {

        public SoundDefinition {
            soundFiles = List.copyOf(soundFiles);
        }

    }

    public record SoundFile(String path, float volume, float pitch, int weight, boolean is3D) {

        public static List<SoundFile> fromSoundsArray(final JsonArray sounds) {
            final List<SoundFile> files = new ArrayList<>();
            for (JsonElement element : sounds) {
                if (element.isJsonPrimitive()) {
                    files.add(new SoundFile(element.getAsString(), 1F, 1F, 1, true));
                } else if (element.isJsonObject()) {
                    final JsonObject obj = element.getAsJsonObject();
                    final String name = obj.has("name") ? obj.get("name").getAsString() : null;
                    if (name == null) continue;
                    final float volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 1F;
                    final float pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 1F;
                    final int weight = obj.has("weight") ? Math.max(1, obj.get("weight").getAsInt()) : 1;
                    final boolean is3D = !obj.has("is3D") || obj.get("is3D").getAsBoolean();
                    files.add(new SoundFile(name, volume, pitch, weight, is3D));
                }
            }
            return files;
        }
    }

    public record ConfiguredSound(String sound, float minVolume, float maxVolume, float minPitch, float maxPitch) {

        public static ConfiguredSound fromJson(final JsonObject obj) {
            if (!obj.has("sound")) {
                return null;
            }
            final String sound = obj.get("sound").getAsString();
            if (sound.isEmpty()) {
                return null;
            }

            final float minVolume;
            final float maxVolume;
            if (obj.has("volume")) {
                if (obj.get("volume").isJsonArray()) {
                    minVolume = obj.get("volume").getAsJsonArray().get(0).getAsFloat();
                    maxVolume = obj.get("volume").getAsJsonArray().get(1).getAsFloat();
                } else {
                    minVolume = obj.get("volume").getAsFloat();
                    maxVolume = obj.get("volume").getAsFloat();
                }
            } else {
                minVolume = 1F;
                maxVolume = 1F;
            }
            final float minPitch;
            final float maxPitch;
            if (obj.has("pitch")) {
                if (obj.get("pitch").isJsonArray()) {
                    minPitch = obj.get("pitch").getAsJsonArray().get(0).getAsFloat();
                    maxPitch = obj.get("pitch").getAsJsonArray().get(1).getAsFloat();
                } else {
                    minPitch = obj.get("pitch").getAsFloat();
                    maxPitch = obj.get("pitch").getAsFloat();
                }
            } else {
                minPitch = 1F;
                maxPitch = 1F;
            }
            return new ConfiguredSound(sound, minVolume, maxVolume, minPitch, maxPitch);
        }

        public JsonObject toJson() {
            final JsonObject obj = new JsonObject();
            obj.addProperty("sound", this.sound);
            if (this.minVolume != 1F || this.maxVolume != 1F) {
                if (this.minVolume == this.maxVolume) {
                    obj.addProperty("volume", this.minVolume);
                } else {
                    final JsonArray volumeArray = new JsonArray();
                    volumeArray.add(this.minVolume);
                    volumeArray.add(this.maxVolume);
                    obj.add("volume", volumeArray);
                }
            }
            if (this.minPitch != 1F || this.maxPitch != 1F) {
                if (this.minPitch == this.maxPitch) {
                    obj.addProperty("pitch", this.minPitch);
                } else {
                    final JsonArray pitchArray = new JsonArray();
                    pitchArray.add(this.minPitch);
                    pitchArray.add(this.maxPitch);
                    obj.add("pitch", pitchArray);
                }
            }
            return obj;
        }

    }

    public record EventSound(String event, ConfiguredSound sound) {
    }

    public record EventSounds(String identifier, Map<String, ConfiguredSound> eventSounds) {

        public EventSounds {
            eventSounds = DefinitionImmutability.map(eventSounds);
        }

    }

}
