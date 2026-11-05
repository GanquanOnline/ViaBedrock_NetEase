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
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.*;

public class TextureDefinitions {

    private final Map<String, List<ItemTextureDefinition>> itemTextures;
    private final Map<String, List<TerrainTextureDefinition>> terrainTextures;

    public TextureDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(fold(resourcePackStorage.getPackStackBottomToTop().stream().map(TextureDefinitions::fromPack).toList()));
    }

    private TextureDefinitions(final TextureDefinitions source) {
        this.itemTextures = source.itemTextures;
        this.terrainTextures = source.terrainTextures;
    }

    private TextureDefinitions(final Map<String, List<ItemTextureDefinition>> itemTextures,
                               final Map<String, List<TerrainTextureDefinition>> terrainTextures) {
        final Map<String, List<ItemTextureDefinition>> immutableItems = new LinkedHashMap<>();
        itemTextures.forEach((name, definitions) -> immutableItems.put(name, List.copyOf(definitions)));
        this.itemTextures = DefinitionImmutability.map(immutableItems);
        final Map<String, List<TerrainTextureDefinition>> immutableTerrain = new LinkedHashMap<>();
        terrainTextures.forEach((name, definitions) -> immutableTerrain.put(name, List.copyOf(definitions)));
        this.terrainTextures = DefinitionImmutability.map(immutableTerrain);
    }

    static TextureDefinitions fromPack(final ResourcePack pack) {
        final Map<String, List<ItemTextureDefinition>> itemTextures = new LinkedHashMap<>();
        final Map<String, List<TerrainTextureDefinition>> terrainTextures = new LinkedHashMap<>();
        if (pack.content().contains("textures/item_texture.json")) {
            try {
                final JsonObject itemTexture = pack.content().getJson("textures/item_texture.json");
                final String textureName = itemTexture.has("texture_name") ? itemTexture.get("texture_name").getAsString() : "atlas.items";
                if (textureName.equals("atlas.items")) {
                    parseNamedTextures(itemTexture.getAsJsonObject("texture_data"), itemTextures, true);
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse item_texture.json in pack " + pack.key(), e);
            }
        }
        if (pack.content().contains("textures/terrain_texture.json")) {
            try {
                final JsonObject terrainTexture = pack.content().getJson("textures/terrain_texture.json");
                final String textureName = terrainTexture.has("texture_name") ? terrainTexture.get("texture_name").getAsString() : "atlas.terrain";
                if (textureName.equals("atlas.terrain")) {
                    parseNamedTextures(terrainTexture.getAsJsonObject("texture_data"), terrainTextures, false);
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse terrain_texture.json in pack " + pack.key(), e);
            }
        }
        return new TextureDefinitions(itemTextures, terrainTextures);
    }

    static TextureDefinitions fold(final Collection<TextureDefinitions> layersBottomToTop) {
        final Map<String, List<ItemTextureDefinition>> itemTextures = new LinkedHashMap<>();
        final Map<String, List<TerrainTextureDefinition>> terrainTextures = new LinkedHashMap<>();
        for (TextureDefinitions layer : layersBottomToTop) {
            itemTextures.putAll(layer.itemTextures);
            terrainTextures.putAll(layer.terrainTextures);
        }
        return new TextureDefinitions(itemTextures, terrainTextures);
    }

    @SuppressWarnings("unchecked")
    private static <T> void parseNamedTextures(final JsonObject textureData, final Map<String, List<T>> output, final boolean itemAtlas) {
        if (textureData == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : textureData.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            final String name = entry.getKey();
            final JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
            final List<String> paths = texturePaths(textures);
            if (paths.isEmpty()) {
                continue;
            }
            final List<T> definitions = new ArrayList<>(paths.size());
            for (String path : paths) {
                if (itemAtlas) {
                    definitions.add((T) new ItemTextureDefinition(name, path));
                } else {
                    definitions.add((T) new TerrainTextureDefinition(name, path));
                }
            }
            output.put(name, definitions);
        }
    }

    private static List<String> texturePaths(final JsonElement textures) {
        final List<String> paths = new ArrayList<>();
        if (textures == null || textures.isJsonNull()) {
            return paths;
        }
        if (textures.isJsonPrimitive() && textures.getAsJsonPrimitive().isString()) {
            paths.add(textures.getAsString());
            return paths;
        }
        if (textures.isJsonArray()) {
            for (JsonElement texture : textures.getAsJsonArray()) {
                if (texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isString()) {
                    paths.add(texture.getAsString());
                } else if (texture.isJsonObject() && texture.getAsJsonObject().has("path")) {
                    paths.add(texture.getAsJsonObject().get("path").getAsString());
                }
            }
        }
        return paths;
    }

    public Map<String, List<ItemTextureDefinition>> itemTextures() {
        return Collections.unmodifiableMap(this.itemTextures);
    }

    public Map<String, List<TerrainTextureDefinition>> terrainTextures() {
        return Collections.unmodifiableMap(this.terrainTextures);
    }

    public String firstTerrainPath(final String textureName) {
        if (textureName == null) {
            return null;
        }
        // 基岩允许 blocks.json 直接引用纹理路径，
        // 而无需在 terrain_texture.json 中有对应条目。
        if (textureName.startsWith("textures/")) {
            return textureName;
        }
        final List<TerrainTextureDefinition> definitions = this.terrainTextures.get(textureName);
        return definitions == null || definitions.isEmpty() ? null : definitions.getFirst().texturePath();
    }

    public record ItemTextureDefinition(String name, String texturePath) {
    }

    public record TerrainTextureDefinition(String name, String texturePath) {
    }

}
