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
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.cube.converter.parser.bedrock.controller.BedrockControllerParser;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// https://wiki.bedrock.dev/entities/render-controllers
public class RenderControllerDefinitions {

    private final Map<String, BedrockRenderController> renderControllers;

    public RenderControllerDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private RenderControllerDefinitions(final Map<String, BedrockRenderController> renderControllers) {
        this.renderControllers = DefinitionImmutability.map(renderControllers);
    }

    static RenderControllerDefinitions fromPack(final ResourcePack pack) {
        final Map<String, BedrockRenderController> renderControllers = new LinkedHashMap<>();
        for (String controllerPath : DefinitionSource.RENDER_CONTROLLERS.files(pack.content())) {
            try {
                for (BedrockRenderController parsed : BedrockControllerParser.parse(pack.content().getString(controllerPath))) {
                    final BedrockRenderController renderController = DefinitionImmutability.renderController(parsed);
                    renderControllers.put(renderController.identifier(), renderController);
                }
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse render controller " + controllerPath + " in pack " + pack.key(), e);
            }
        }
        return new RenderControllerDefinitions(renderControllers);
    }

    static RenderControllerDefinitions fold(final Collection<RenderControllerDefinitions> layersBottomToTop) {
        final Map<String, BedrockRenderController> renderControllers = new LinkedHashMap<>();
        for (RenderControllerDefinitions layer : layersBottomToTop) {
            renderControllers.putAll(layer.renderControllers);
        }
        return new RenderControllerDefinitions(renderControllers);
    }

    private static Map<String, BedrockRenderController> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, BedrockRenderController> renderControllers = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            renderControllers.putAll(fromPack(pack).renderControllers);
        }
        return renderControllers;
    }

    public BedrockRenderController get(final String name) {
        return this.renderControllers.get(name);
    }

    public Map<String, BedrockRenderController> renderControllers() {
        return Collections.unmodifiableMap(this.renderControllers);
    }

}
