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
package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class CustomSoundResourceRewriter implements ResourcePackRewriter.Rewriter {

    public static final String CUSTOM_SOUNDS_KEY = "custom_sound_names";

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        this.collectCustomSoundNames(resourcePackStorage, javaContent);
    }

    @Override
    public void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        final Set<String> sharedNames = resourcePackStorage.getSharedCustomSoundNames();
        resourcePackStorage.putRuntimeData(
                CUSTOM_SOUNDS_KEY, sharedNames != null
                        ? sharedNames : Set.copyOf(this.collectCustomSoundNames(resourcePackStorage, null)));
    }

    @Override
    public ResourcePackRewriter.RuntimeDataScope runtimeDataScope() {
        return ResourcePackRewriter.RuntimeDataScope.SHARED;
    }

    @Override
    public String artifactFingerprint() {
        return "3"; // 版本 3 还会把已映射的基岩声音暴露为 Java 事件别名。
    }

    private Set<String> collectCustomSoundNames(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        final SoundDefinitions sounds = resourcePackStorage.getSounds();
        final Map<String, BedrockMappingData.JavaSound> mappedSoundFiles = mappedSoundFiles(sounds);
        final Set<String> customSoundNames = new HashSet<>();
        final JsonObject javaSoundsJson = new JsonObject();

        for (Map.Entry<String, SoundDefinitions.SoundDefinition> entry : sounds.soundDefinitions().entrySet()) {
            final String soundName = entry.getKey();

            final SoundDefinitions.SoundDefinition definition = entry.getValue();
            if (definition.soundFiles().isEmpty()) {
                continue;
            }

            // 每个可播放的基岩标识符都会得到一个稳定的 bedrock:<identifier> Java 事件。
            // 自定义 OGG 仍作为文件保留；复用原版基岩采样点的定义则指向
            // 已有的映射 Java 事件并用 type=event，而不是复制原生 FSB 数据。
            final JsonArray javaSoundEntries = new JsonArray();
            final BedrockMappingData.JavaSound directMapping =
                    soundMappings().get(soundName);
            boolean hasCustomOgg = false;

            for (SoundDefinitions.SoundFile soundFile : definition.soundFiles()) {
                final String bedrockOggPath = soundFile.path() + ".ogg";
                final String javaOggPath = "assets/bedrock/sounds/" + soundFile.path() + ".ogg";

                // 在资源包栈中搜索 .ogg 文件
                boolean found = false;
                for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
                    if (pack.content().contains(bedrockOggPath)) {
                        if (javaContent != null) {
                            javaContent.copyFrom(pack.content(), bedrockOggPath, javaOggPath);
                        }
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    if (directMapping == null) {
                        final BedrockMappingData.JavaSound mappedVariant = resolveMappedVariant(
                                soundFile.path(), mappedSoundFiles);
                        if (mappedVariant != null && javaContent != null) {
                            javaSoundEntries.add(javaSoundEntry(mappedVariant.identifier(), soundFile, true));
                        } else if (mappedVariant != null) {
                            javaSoundEntries.add(new JsonObject());
                        }
                    }
                    continue;
                }

                hasCustomOgg = true;
                if (javaContent != null) {
                    javaSoundEntries.add(javaSoundEntry("bedrock:" + soundFile.path(), soundFile, false));
                } else {
                    javaSoundEntries.add(new JsonObject());
                }
            }

            // 直接映射的基岩事件本身已负责自己的变体。仅当没有自定义 OGG 覆盖该定义时，
            // 才添加一条事件引用。
            if (javaSoundEntries.isEmpty() && directMapping != null) {
                if (javaContent != null) {
                    javaSoundEntries.add(javaSoundEntry(directMapping.identifier(), null, true));
                } else {
                    javaSoundEntries.add(new JsonObject());
                }
            }

            if (!javaSoundEntries.isEmpty()) {
                if (javaContent != null) {
                    final JsonObject soundDef = new JsonObject();
                    soundDef.add("sounds", javaSoundEntries);
                    javaSoundsJson.add(soundName, soundDef);
                }
                if (directMapping == null || hasCustomOgg) {
                    customSoundNames.add(soundName);
                }
            }
        }

        // 即使下载的栈缺少原版 sound_definitions.json，也要为所有权威的基岩→Java 映射
        // 发布别名。这样进程内消费者总能引用与资源包自定义声音相同的
        // bedrock:<identifier> 事件名。
        soundMappings().forEach((bedrockName, javaSound) -> {
            if (javaContent != null && !javaSoundsJson.has(bedrockName)) {
                final JsonArray aliases = new JsonArray();
                aliases.add(javaSoundEntry(javaSound.identifier(), null, true));
                final JsonObject soundDef = new JsonObject();
                soundDef.add("sounds", aliases);
                javaSoundsJson.add(bedrockName, soundDef);
            }
        });

        if (javaContent != null && !javaSoundsJson.isEmpty()) {
            javaContent.putJson("assets/bedrock/sounds.json", javaSoundsJson);
            ViaBedrock.getPlatform().getLogger().log(Level.INFO,
                    "Added " + javaSoundsJson.size() + " Bedrock sound alias(es) to Java resource pack");
        }

        return customSoundNames;
    }

    private static JsonObject javaSoundEntry(final String name,
                                             final SoundDefinitions.SoundFile soundFile,
                                             final boolean eventReference) {
        final JsonObject soundEntry = new JsonObject();
        soundEntry.addProperty("name", name);
        if (eventReference) {
            soundEntry.addProperty("type", "event");
        }
        if (soundFile != null) {
            if (soundFile.volume() != 1F) soundEntry.addProperty("volume", soundFile.volume());
            if (soundFile.pitch() != 1F) soundEntry.addProperty("pitch", soundFile.pitch());
            if (soundFile.weight() != 1) soundEntry.addProperty("weight", soundFile.weight());
        }
        return soundEntry;
    }

    private static Map<String, BedrockMappingData.JavaSound> mappedSoundFiles(final SoundDefinitions sounds) {
        final Map<String, BedrockMappingData.JavaSound> result = new HashMap<>();
        soundMappings().forEach((bedrockName, javaSound) -> {
            final SoundDefinitions.SoundDefinition definition = sounds.soundDefinitions().get(bedrockName);
            if (definition != null) {
                definition.soundFiles().forEach(file -> result.putIfAbsent(file.path(), javaSound));
            }
        });
        return result;
    }

    private static BedrockMappingData.JavaSound resolveMappedVariant(
            final String path, final Map<String, BedrockMappingData.JavaSound> mappedSoundFiles) {
        final BedrockMappingData.JavaSound exact = mappedSoundFiles.get(path);
        if (exact != null) return exact;

        // 原版定义习惯上把 sounds/foo/bar 映射到事件 foo.bar。当原版定义不在
        // 下载的资源包栈中时，这也能覆盖对原生 FSB 采样点的引用
        // （例如 sounds/note/pling -> note.pling）。
        final String candidate = path.startsWith("sounds/") ? path.substring("sounds/".length()) : path;
        return soundMappings().get(candidate.replace('/', '.'));
    }

    private static Map<String, BedrockMappingData.JavaSound> soundMappings() {
        final Map<String, BedrockMappingData.JavaSound> mappings =
                BedrockProtocol.MAPPINGS.getBedrockToJavaSounds();
        return mappings != null ? mappings : Map.of();
    }

    /** 在运行时构建仍持有临时资源包栈时，计算由栈派生的声音名称。 */
    public static Set<String> findCustomSoundNames(final SoundDefinitions sounds,
                                                   final Collection<ResourcePack> packsTopToBottom) {
        final Set<String> customSoundNames = new HashSet<>();
        final Map<String, BedrockMappingData.JavaSound> mappedSoundFiles = mappedSoundFiles(sounds);
        for (Map.Entry<String, SoundDefinitions.SoundDefinition> entry : sounds.soundDefinitions().entrySet()) {
            final boolean directlyMapped =
                    soundMappings().containsKey(entry.getKey());
            boolean playable = false;
            boolean hasCustomOgg = false;
            for (SoundDefinitions.SoundFile soundFile : entry.getValue().soundFiles()) {
                final String path = soundFile.path() + ".ogg";
                for (ResourcePack pack : packsTopToBottom) {
                    if (pack.content().contains(path)) {
                        playable = true;
                        hasCustomOgg = true;
                        break;
                    }
                }
                if (!playable && !directlyMapped
                        && resolveMappedVariant(soundFile.path(), mappedSoundFiles) != null) {
                    playable = true;
                }
                if (playable) break;
            }
            if (playable && (!directlyMapped || hasCustomOgg)) customSoundNames.add(entry.getKey());
        }
        return Set.copyOf(customSoundNames);
    }

}
