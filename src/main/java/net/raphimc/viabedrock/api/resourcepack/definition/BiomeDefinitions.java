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

import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BiomeDefinitions {

    private final Map<String, BiomeDefinition> biomes;

    public BiomeDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private BiomeDefinitions(final Map<String, BiomeDefinition> biomes) {
        this.biomes = DefinitionImmutability.map(biomes);
    }

    static BiomeDefinitions fromPack(final ResourcePack pack) {
        final Map<String, BiomeDefinition> biomes = new LinkedHashMap<>();
        for (String biomePath : DefinitionSource.BIOMES.files(pack.content())) {
            try {
                final JsonObject biome = pack.content().getJson(biomePath).getAsJsonObject("minecraft:client_biome");
                final String name = biome.getAsJsonObject("description").get("identifier").getAsString();
                Integer skyColor = null;
                Integer waterSurfaceColor = null;
                String fog = null;
                if (biome.has("components")) {
                    final JsonObject components = biome.getAsJsonObject("components");
                    if (components.has("minecraft:sky_color")) {
                        skyColor = Integer.parseInt(components.getAsJsonObject("minecraft:sky_color").get("sky_color").getAsString().substring(1, 7), 16);
                    }
                    if (components.has("minecraft:water_appearance")) {
                        waterSurfaceColor = Integer.parseInt(components.getAsJsonObject("minecraft:water_appearance").get("surface_color").getAsString().substring(1, 7), 16);
                    }
                    if (components.has("minecraft:fog_appearance")) {
                        fog = Key.namespaced(components.getAsJsonObject("minecraft:fog_appearance").get("fog_identifier").getAsString());
                    }
                }
                biomes.put(name, new BiomeDefinition(name, skyColor, waterSurfaceColor, fog));
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse biome definition " + biomePath + " in pack " + pack.key(), e);
            }
        }
        return new BiomeDefinitions(biomes);
    }

    static BiomeDefinitions fold(final Collection<BiomeDefinitions> layersBottomToTop) {
        final Map<String, BiomeDefinition> biomes = new LinkedHashMap<>();
        for (BiomeDefinitions layer : layersBottomToTop) {
            biomes.putAll(layer.biomes);
        }
        return new BiomeDefinitions(biomes);
    }

    private static Map<String, BiomeDefinition> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, BiomeDefinition> biomes = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            biomes.putAll(fromPack(pack).biomes);
        }
        return biomes;
    }

    public BiomeDefinition get(final String name) {
        return this.biomes.get(name);
    }

    public Map<String, BiomeDefinition> biomes() {
        return Collections.unmodifiableMap(this.biomes);
    }

    public static class BiomeDefinition {

        private final String name;
        private final Integer skyColor;
        private final Integer waterSurfaceColor;
        private final String fog;

        public BiomeDefinition(final String name) {
            this(name, null, null, null);
        }

        private BiomeDefinition(final String name, final Integer skyColor, final Integer waterSurfaceColor, final String fog) {
            this.name = name;
            this.skyColor = skyColor;
            this.waterSurfaceColor = waterSurfaceColor;
            this.fog = fog;
        }

        public String name() {
            return this.name;
        }

        public Integer skyColor() {
            return this.skyColor;
        }

        public Integer waterSurfaceColor() {
            return this.waterSurfaceColor;
        }

        public String fog() {
            return this.fog;
        }

    }

}
