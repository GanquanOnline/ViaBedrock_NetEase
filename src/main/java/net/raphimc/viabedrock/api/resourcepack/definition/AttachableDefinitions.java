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
import org.cube.converter.data.bedrock.BedrockAttachableData;
import org.cube.converter.parser.bedrock.data.impl.BedrockAttachableParser;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// https://wiki.bedrock.dev/items/attachables.html
public class AttachableDefinitions {

    private final Map<String, AttachableDefinition> attachables;

    public AttachableDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private AttachableDefinitions(final Map<String, AttachableDefinition> attachables) {
        this.attachables = DefinitionImmutability.map(attachables);
    }

    static AttachableDefinitions fromPack(final ResourcePack pack) {
        final Map<String, AttachableDefinition> attachables = new LinkedHashMap<>();
        for (String attachablePath : DefinitionSource.ATTACHABLES.files(pack.content())) {
            try {
                final BedrockAttachableData parsed = BedrockAttachableParser.parse(pack.content().getString(attachablePath));
                final BedrockAttachableData attachableData = DefinitionImmutability.attachableData(parsed);
                final String identifier = Key.namespaced(attachableData.getIdentifier());
                attachables.put(identifier, new AttachableDefinition(identifier, attachableData));
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse attachable definition " + attachablePath + " in pack " + pack.key(), e);
            }
        }
        return new AttachableDefinitions(attachables);
    }

    static AttachableDefinitions fold(final Collection<AttachableDefinitions> layersBottomToTop) {
        final Map<String, AttachableDefinition> attachables = new LinkedHashMap<>();
        for (AttachableDefinitions layer : layersBottomToTop) {
            attachables.putAll(layer.attachables);
        }
        return new AttachableDefinitions(attachables);
    }

    private static Map<String, AttachableDefinition> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, AttachableDefinition> attachables = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            attachables.putAll(fromPack(pack).attachables);
        }
        return attachables;
    }

    public Map<String, AttachableDefinition> attachables() {
        return Collections.unmodifiableMap(this.attachables);
    }

    public record AttachableDefinition(String identifier, BedrockAttachableData attachableData) {
    }

}
