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

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.parser.bedrock.geometry.BedrockGeometryParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelDefinitions {

    private final Map<String, BedrockGeometryModel> entityModels;

    public ModelDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage));
    }

    private ModelDefinitions(final Map<String, BedrockGeometryModel> entityModels) {
        this.entityModels = DefinitionImmutability.map(entityModels);
    }

    static ModelDefinitions fromPack(final ResourcePack pack) {
        final Map<String, BedrockGeometryModel> entityModels = new LinkedHashMap<>();
        loadModels(pack, entityModels);
        return new ModelDefinitions(entityModels);
    }

    static ModelDefinitions fold(final Collection<ModelDefinitions> layersBottomToTop) {
        final Map<String, BedrockGeometryModel> entityModels = new LinkedHashMap<>();
        for (ModelDefinitions layer : layersBottomToTop) {
            entityModels.putAll(layer.entityModels);
        }
        return new ModelDefinitions(entityModels);
    }

    private static Map<String, BedrockGeometryModel> parsePacks(final ResourcePackStorage resourcePackStorage) {
        final List<ResourcePack> packsBottomToTop = new ArrayList<>();
        if (BedrockProtocol.MAPPINGS.getBedrockSkinPacks() != null) {
            final ResourcePack skinPack = BedrockProtocol.MAPPINGS.getBedrockSkinPacks().get(DataValues.VANILLA_SKIN_PACK_KEY);
            if (skinPack != null) {
                packsBottomToTop.add(skinPack);
            }
        }
        packsBottomToTop.addAll(resourcePackStorage.getPackStackBottomToTop());

        final Map<String, BedrockGeometryModel> entityModels = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            entityModels.putAll(fromPack(pack).entityModels);
        }
        return entityModels;
    }

    private static void loadModels(final ResourcePack pack, final Map<String, BedrockGeometryModel> entityModels) {
        for (String modelPath : DefinitionSource.MODELS.files(pack.content())) {
            loadModel(pack, modelPath, modelPath.startsWith("models/entity/"), entityModels);
        }
        if (pack.content().contains(DefinitionSource.LEGACY_MODEL.path())) {
            loadModel(pack, DefinitionSource.LEGACY_MODEL.path(), true, entityModels);
        }
    }

    private static void loadModel(final ResourcePack pack, final String modelPath, final boolean entityModel,
                                  final Map<String, BedrockGeometryModel> entityModels) {
        try {
            for (BedrockGeometryModel bedrockGeometry : BedrockGeometryParser.parse(pack.content().getString(modelPath))) {
                if (entityModel) {
                    final BedrockGeometryModel immutableModel = DefinitionImmutability.model(bedrockGeometry);
                    entityModels.put(immutableModel.getIdentifier(), immutableModel);
                }
            }
        } catch (Throwable e) {
            DefinitionLogger.warning("Failed to parse model definition " + modelPath + " in pack " + pack.key(), e);
        }
    }

    public BedrockGeometryModel getEntityModel(final String name) {
        final BedrockGeometryModel model = this.entityModels.get(name);
        return model == null ? null : DefinitionImmutability.model(model);
    }

    public Map<String, BedrockGeometryModel> entityModels() {
        final Map<String, BedrockGeometryModel> detached = new LinkedHashMap<>(this.entityModels.size());
        this.entityModels.forEach((key, value) -> detached.put(key, DefinitionImmutability.model(value)));
        return Collections.unmodifiableMap(detached);
    }

    public boolean containsEntityModel(final String name) {
        return this.entityModels.containsKey(name);
    }

    public int entityModelCount() {
        return this.entityModels.size();
    }

}
