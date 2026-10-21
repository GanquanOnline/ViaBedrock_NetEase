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
 * Copies Bedrock UI textures (textures/ui/, textures/gui/) and their nine-slice JSON
 * definitions into the Java resource pack so that ModUIClient can render HUD elements.
 * Always active — the extra files have no side effects for clients without ModUIClient.
 * (Cannot gate on ModUIClient channel because resource pack conversion runs in
 * Configuration phase, before ModUIClient registers its channel in Play phase.)
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
                // PNG and JSON files: copy directly
                for (final String extension : COPY_EXTENSIONS) {
                    final List<String> files = bedrockContent.getFilesDeep(prefix, extension);
                    for (final String bedrockPath : files) {
                        // Java resource identifiers must be lowercase
                        final String javaPath = "assets/minecraft/" + bedrockPath.toLowerCase(Locale.ROOT);
                        // Higher-priority packs take precedence (top-to-bottom order)
                        if (!javaContent.contains(javaPath)) {
                            javaContent.copyFrom(bedrockContent, bedrockPath, javaPath);
                            count++;
                        }
                    }
                }

                // JPG/JPEG files: convert to PNG
                for (final String extension : IMAGE_EXTENSIONS) {
                    final List<String> files = bedrockContent.getFilesDeep(prefix, extension);
                    for (final String bedrockPath : files) {
                        // Strip .jpg/.jpeg and replace with .png
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
