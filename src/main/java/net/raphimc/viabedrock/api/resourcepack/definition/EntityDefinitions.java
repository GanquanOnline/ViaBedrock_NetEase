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

import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.cube.converter.parser.bedrock.data.impl.BedrockEntityParser;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// https://wiki.bedrock.dev/entities/entity-intro-rp.html
public class EntityDefinitions {

    private final Map<String, EntityDefinition> entities;

    public EntityDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private EntityDefinitions(final Map<String, EntityDefinition> entities) {
        this.entities = DefinitionImmutability.map(entities);
    }

    static EntityDefinitions fromPack(final ResourcePack pack) {
        final Map<String, EntityDefinition> entities = new LinkedHashMap<>();
        for (String entityPath : DefinitionSource.ENTITIES.files(pack.content())) {
            try {
                final BedrockEntityData parsed = BedrockEntityParser.parse(pack.content().getString(entityPath));
                final BedrockEntityData entityData = DefinitionImmutability.entityData(parsed);
                final String identifier = Key.namespaced(entityData.getIdentifier());
                entities.put(identifier, new EntityDefinition(identifier, entityData));
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse entity definition " + entityPath + " in pack " + pack.key(), e);
            }
        }
        return new EntityDefinitions(entities);
    }

    static EntityDefinitions fold(final Collection<EntityDefinitions> layersBottomToTop) {
        final Map<String, EntityDefinition> entities = new LinkedHashMap<>();
        for (EntityDefinitions layer : layersBottomToTop) {
            entities.putAll(layer.entities);
        }
        return new EntityDefinitions(entities);
    }

    private static Map<String, EntityDefinition> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, EntityDefinition> entities = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            entities.putAll(fromPack(pack).entities);
        }
        return entities;
    }

    public EntityDefinition get(final String identifier) {
        return this.entities.get(identifier);
    }

    public Map<String, EntityDefinition> entities() {
        return Collections.unmodifiableMap(this.entities);
    }

    public record EntityDefinition(String identifier, BedrockEntityData entityData) {
    }

}
