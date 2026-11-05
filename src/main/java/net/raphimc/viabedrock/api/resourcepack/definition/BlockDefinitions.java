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

import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// https://wiki.bedrock.dev/blocks/blocks-intro.html
public class BlockDefinitions {

    private final Map<String, BlockDefinition> blocks;

    public BlockDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private BlockDefinitions(final Map<String, BlockDefinition> blocks) {
        this.blocks = DefinitionImmutability.map(blocks);
    }

    static BlockDefinitions fromPack(final ResourcePack pack) {
        final Map<String, BlockDefinition> blocks = new LinkedHashMap<>();
        if (pack.content().contains(DefinitionSource.BLOCKS.path())) {
            try {
                final JsonObject blocksJson = pack.content().getJson(DefinitionSource.BLOCKS.path());
                for (Map.Entry<String, JsonElement> entry : blocksJson.entrySet()) {
                    if (entry.getKey().equals("format_version")) continue;
                    if (!entry.getValue().isJsonObject()) continue;
                    final JsonObject block = entry.getValue().getAsJsonObject();
                    final String sound = block.has("sound") ? block.get("sound").getAsString() : null;
                    final String identifier = Key.namespaced(entry.getKey());
                    blocks.put(identifier, new BlockDefinition(identifier, sound, textures(block.get("textures"))));
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse blocks.json in pack " + pack.key(), e);
            }
        }
        return new BlockDefinitions(blocks);
    }

    static BlockDefinitions fold(final Collection<BlockDefinitions> layersBottomToTop) {
        final Map<String, BlockDefinition> blocks = new LinkedHashMap<>();
        for (BlockDefinitions layer : layersBottomToTop) {
            blocks.putAll(layer.blocks);
        }
        return new BlockDefinitions(blocks);
    }

    private static Map<String, BlockDefinition> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, BlockDefinition> blocks = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            blocks.putAll(fromPack(pack).blocks);
        }
        return blocks;
    }

    private static BlockTextures textures(final JsonElement textures) {
        if (textures == null || textures.isJsonNull()) {
            return BlockTextures.EMPTY;
        }
        if (textures.isJsonPrimitive() && textures.getAsJsonPrimitive().isString()) {
            return BlockTextures.uniform(textures.getAsString());
        }
        if (!textures.isJsonObject()) {
            return BlockTextures.EMPTY;
        }
        final JsonObject object = textures.getAsJsonObject();
        return new BlockTextures(
                stringOrNull(object, "up"),
                stringOrNull(object, "down"),
                stringOrNull(object, "north"),
                stringOrNull(object, "south"),
                stringOrNull(object, "west"),
                stringOrNull(object, "east"),
                stringOrNull(object, "side")
        );
    }

    private static String stringOrNull(final JsonObject object, final String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    public BlockDefinition get(final String identifier) {
        return this.blocks.get(identifier);
    }

    public Map<String, BlockDefinition> blocks() {
        return Collections.unmodifiableMap(this.blocks);
    }

    public record BlockDefinition(String identifier, String sound, BlockTextures textures) {
        public BlockDefinition(final String identifier, final String sound) {
            this(identifier, sound, BlockTextures.EMPTY);
        }

        public boolean hasTextures() {
            return this.textures != null && this.textures.hasAny();
        }
    }

    public record BlockTextures(String up, String down, String north, String south, String west, String east, String side) {
        static final BlockTextures EMPTY = new BlockTextures(null, null, null, null, null, null, null);

        static BlockTextures uniform(final String texture) {
            return new BlockTextures(texture, texture, texture, texture, texture, texture, texture);
        }

        public boolean hasAny() {
            return this.up != null || this.down != null || this.north != null || this.south != null
                    || this.west != null || this.east != null || this.side != null;
        }

        public String face(final String face) {
            final String fallback = first(this.up, this.down, this.north, this.south, this.west, this.east, this.side);
            return switch (face) {
                case "up" -> first(this.up, this.side, fallback);
                case "down" -> first(this.down, this.side, fallback);
                case "north" -> first(this.north, this.side, fallback);
                case "south" -> first(this.south, this.side, fallback);
                case "west" -> first(this.west, this.side, fallback);
                case "east" -> first(this.east, this.side, fallback);
                default -> first(this.side, fallback);
            };
        }

        public String firstName() {
            return first(this.up, this.down, this.north, this.south, this.west, this.east, this.side);
        }

        private static String first(final String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }

}
