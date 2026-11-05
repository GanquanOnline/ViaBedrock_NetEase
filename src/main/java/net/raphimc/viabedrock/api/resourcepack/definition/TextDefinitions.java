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

import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

// https://wiki.bedrock.dev/concepts/text-and-translations.html
public class TextDefinitions {

    private final Map<String, String> translations;

    public TextDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(parsePacks(resourcePackStorage.getPackStackBottomToTop()));
    }

    private TextDefinitions(final Map<String, String> translations) {
        this.translations = DefinitionImmutability.map(translations);
    }

    static TextDefinitions fromPack(final ResourcePack pack) {
        final Map<String, String> translations = new LinkedHashMap<>();
        if (pack.content().contains(DefinitionSource.TEXTS.path())) {
            try {
                translations.putAll(pack.content().getLang(DefinitionSource.TEXTS.path()));
            } catch (Throwable e) {
                DefinitionLogger.warning("Failed to parse texts/en_US.lang in pack " + pack.key(), e);
            }
        }
        return new TextDefinitions(translations);
    }

    static TextDefinitions fold(final Collection<TextDefinitions> layersBottomToTop) {
        final Map<String, String> translations = new LinkedHashMap<>();
        for (TextDefinitions layer : layersBottomToTop) {
            translations.putAll(layer.translations);
        }
        return new TextDefinitions(translations);
    }

    private static Map<String, String> parsePacks(final Collection<ResourcePack> packsBottomToTop) {
        final Map<String, String> translations = new LinkedHashMap<>();
        for (ResourcePack pack : packsBottomToTop) {
            translations.putAll(fromPack(pack).translations);
        }
        return translations;
    }

    public String translate(final String text, final Object... args) {
        return BedrockTranslator.translate(text, this.lookup(), args);
    }

    public String get(final String key) {
        return this.translations.getOrDefault(key, key);
    }

    public Function<String, String> lookup() {
        return this::get;
    }

}
