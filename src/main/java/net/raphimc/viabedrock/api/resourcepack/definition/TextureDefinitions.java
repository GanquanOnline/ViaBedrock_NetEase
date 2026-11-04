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

    public TextureDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private TextureDefinitions(final Map<String, List<ItemTextureDefinition>> itemTextures) {
        final Map<String, List<ItemTextureDefinition>> immutable = new LinkedHashMap<>();
        itemTextures.forEach((name, definitions) -> immutable.put(name, List.copyOf(definitions)));
        this.itemTextures = DefinitionImmutability.map(immutable);
    }

    static TextureDefinitions fromPack(final ResourcePack pack) {
        final Map<String, List<ItemTextureDefinition>> itemTextures = new LinkedHashMap<>();
        if (pack.content().contains(DefinitionSource.TEXTURES.path())) {
            try {
                final JsonObject itemTexture = pack.content().getJson(DefinitionSource.TEXTURES.path());
                final String textureName = itemTexture.has("texture_name") ? itemTexture.get("texture_name").getAsString() : "atlas.items";
                if (textureName.equals("atlas.items")) {
                    final JsonObject textureData = itemTexture.getAsJsonObject("texture_data");
                    for (Map.Entry<String, JsonElement> entry : textureData.entrySet()) {
                        final String name = entry.getKey();
                        final JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
                        final List<ItemTextureDefinition> itemTextureDefinitions = new ArrayList<>();
                        if (textures.isJsonPrimitive() && textures.getAsJsonPrimitive().isString()) {
                            itemTextureDefinitions.add(new ItemTextureDefinition(name, textures.getAsString()));
                        } else if (textures.isJsonArray()) {
                            for (JsonElement texture : textures.getAsJsonArray()) {
                                itemTextureDefinitions.add(new ItemTextureDefinition(name, texture.getAsString()));
                            }
                        }
                        itemTextures.put(name, itemTextureDefinitions);
                    }
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse item_texture.json in pack " + pack.key(), e);
            }
        }
        return new TextureDefinitions(itemTextures);
    }

    static TextureDefinitions fold(final Collection<TextureDefinitions> layersBottomToTop) {
        final Map<String, List<ItemTextureDefinition>> itemTextures = new LinkedHashMap<>();
        for (TextureDefinitions layer : layersBottomToTop) {
            itemTextures.putAll(layer.itemTextures);
        }
        return new TextureDefinitions(itemTextures);
    }

    private static Map<String, List<ItemTextureDefinition>> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, List<ItemTextureDefinition>> itemTextures = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            itemTextures.putAll(fromPack(pack).itemTextures);
        }
        return itemTextures;
    }

    public Map<String, List<ItemTextureDefinition>> itemTextures() {
        return Collections.unmodifiableMap(this.itemTextures);
    }

    public record ItemTextureDefinition(String name, String texturePath) {
    }

}
