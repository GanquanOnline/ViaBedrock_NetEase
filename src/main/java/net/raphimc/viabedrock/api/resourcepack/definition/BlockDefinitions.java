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
                    final JsonObject block = entry.getValue().getAsJsonObject();
                    final String sound = block.has("sound") ? block.get("sound").getAsString() : null;
                    final String identifier = Key.namespaced(entry.getKey());
                    blocks.put(identifier, new BlockDefinition(identifier, sound));
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

    public BlockDefinition get(final String identifier) {
        return this.blocks.get(identifier);
    }

    public Map<String, BlockDefinition> blocks() {
        return Collections.unmodifiableMap(this.blocks);
    }

    public record BlockDefinition(String identifier, String sound) {
    }

}
