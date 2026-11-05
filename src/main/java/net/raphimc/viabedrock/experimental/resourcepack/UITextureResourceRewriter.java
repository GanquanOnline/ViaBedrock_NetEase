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
package net.raphimc.viabedrock.experimental.resourcepack;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * 把基岩 UI 纹理（textures/ui/、textures/gui/）及其九宫格 JSON
 * 定义复制进 Java 资源包，以便 ModUIClient 渲染 HUD 元素。
 * 始终启用 —— 对没有 ModUIClient 的客户端而言这些额外文件没有副作用。
 * （不能根据 ModUIClient 通道来判断，因为资源包转换在 Configuration 阶段
 * 运行，早于 ModUIClient 在 Play 阶段注册其通道。）
 */
public class UITextureResourceRewriter implements ResourcePackRewriter.Rewriter {

    private static final String[] TEXTURE_PREFIXES = {"textures/ui/", "textures/gui/", "sfx/frame/"};
    private static final String[] COPY_EXTENSIONS = {".png", ".json"};
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg"};

    @Override
    public String artifactFingerprint() {
        return "2";
    }

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        int count = 0;
        for (final ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
            final Content bedrockContent = pack.content();

            for (final String prefix : TEXTURE_PREFIXES) {
                // PNG 和 JSON 文件：直接复制
                for (final String extension : COPY_EXTENSIONS) {
                    final List<String> files = bedrockContent.getFilesDeep(prefix, extension);
                    for (final String bedrockPath : files) {
                        // Java 资源标识符必须为小写
                        final String javaPath = "assets/minecraft/" + bedrockPath.toLowerCase(Locale.ROOT);
                        // 优先级更高的包优先（从上到下顺序）
                        if (!javaContent.contains(javaPath)) {
                            javaContent.copyFrom(bedrockContent, bedrockPath, javaPath);
                            count++;
                        }
                    }
                }

                // JPG/JPEG 文件：转换为 PNG
                for (final String extension : IMAGE_EXTENSIONS) {
                    final List<String> files = bedrockContent.getFilesDeep(prefix, extension);
                    for (final String bedrockPath : files) {
                        // 去掉 .jpg/.jpeg 后缀，替换为 .png
                        final String basePath = bedrockPath.substring(0, bedrockPath.length() - extension.length());
                        final String javaPath = "assets/minecraft/" + basePath.toLowerCase(Locale.ROOT) + ".png";
                        if (!javaContent.contains(javaPath)) {
                            final Content.LazyImage image = bedrockContent.getImage(bedrockPath);
                            if (image != null) {
                                javaContent.putPngImage(javaPath, image);
                                count++;
                            }
                        }
                    }
                }
            }
        }

        if (count > 0) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Copied " + count + " UI texture files for ModUIClient");
        }
    }

}
