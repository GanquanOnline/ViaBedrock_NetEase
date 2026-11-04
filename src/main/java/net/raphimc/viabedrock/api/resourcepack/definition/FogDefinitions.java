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
import java.util.LinkedHashMap;
import java.util.Map;

public class FogDefinitions {

    private final Map<String, FogDefinition> fogs;

    public FogDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private FogDefinitions(final Map<String, FogDefinition> fogs) {
        this.fogs = DefinitionImmutability.map(fogs);
    }

    static FogDefinitions fromPack(final ResourcePack pack) {
        final Map<String, FogDefinition> fogs = new LinkedHashMap<>();
        for (String fogPath : DefinitionSource.FOGS.files(pack.content())) {
            try {
                final JsonObject fog = pack.content().getJson(fogPath).getAsJsonObject("minecraft:fog_settings");
                final String identifier = Key.namespaced(fog.getAsJsonObject("description").get("identifier").getAsString());
                final Map<String, Integer> colors = new LinkedHashMap<>();
                final JsonObject distance = fog.getAsJsonObject("distance");
                for (Map.Entry<String, JsonElement> entry : distance.entrySet()) {
                    final JsonObject value = entry.getValue().getAsJsonObject();
                    if (value.has("fog_color")) {
                        colors.put(entry.getKey(), Integer.parseInt(value.get("fog_color").getAsString().substring(1), 16));
                    }
                }
                fogs.put(identifier, new FogDefinition(identifier, colors));
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse fog definition " + fogPath + " in pack " + pack.key(), e);
            }
        }
        return new FogDefinitions(fogs);
    }

    static FogDefinitions fold(final Collection<FogDefinitions> layersBottomToTop) {
        final Map<String, FogDefinition> fogs = new LinkedHashMap<>();
        for (FogDefinitions layer : layersBottomToTop) {
            fogs.putAll(layer.fogs);
        }
        return new FogDefinitions(fogs);
    }

    private static Map<String, FogDefinition> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, FogDefinition> fogs = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            fogs.putAll(fromPack(pack).fogs);
        }
        return fogs;
    }

    public FogDefinition get(final String identifier) {
        return this.fogs.get(identifier);
    }

    public record FogDefinition(String identifier, Map<String, Integer> colors) {

        public FogDefinition {
            colors = DefinitionImmutability.map(colors);
        }

    }

}
