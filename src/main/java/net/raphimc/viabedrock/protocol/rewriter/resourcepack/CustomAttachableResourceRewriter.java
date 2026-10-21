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

import com.viaversion.viaversion.api.minecraft.item.data.ItemModel;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.resourcepack.definition.AttachableDefinitions;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.util.HashMap;
import java.util.Map;

public class CustomAttachableResourceRewriter extends ItemModelResourceRewriter {

    private static final String SUB_FOLDER = "attachables";

    public static ItemModel getItemModel(final String attachableIdentifier) {
        return new ItemModel(Key.of("viabedrock", SUB_FOLDER + '/' + StringUtil.makeIdentifierValueSafe(attachableIdentifier)));
    }

    public CustomAttachableResourceRewriter() {
        super(SUB_FOLDER);
    }

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        for (Map.Entry<String, AttachableDefinitions.AttachableDefinition> attachableEntry : resourcePackStorage.getAttachables().attachables().entrySet()) {
            final AttachableDefinitions.AttachableDefinition attachableDefinition = attachableEntry.getValue();
            final Map<String, JsonObject> javaModelDefinitions = new HashMap<>();
            for (String bedrockPath : attachableDefinition.attachableData().getTextures().values()) {
                for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
                    final Content.LazyImage texture = pack.content().getShortnameImage(bedrockPath);
                    if (texture != null) {
                        javaContent.putPngImage("assets/viabedrock/textures/" + this.getJavaTexturePath(bedrockPath) + ".png", texture);
                        break;
                    }
                }
            }
            for (Map.Entry<String, String> modelEntry : attachableDefinition.attachableData().getGeometries().entrySet()) {
                final BedrockGeometryModel bedrockGeometry = resourcePackStorage.getModels()
                        .getEntityModel(modelEntry.getValue());
                if (bedrockGeometry == null) {
                    continue;
                }
                if (!attachableDefinition.attachableData().getTextures().containsKey(modelEntry.getKey())) {
                    continue;
                }

                final String javaTexturePath = this.getJavaTexturePath(attachableDefinition.attachableData().getTextures().get(modelEntry.getKey()));
                final boolean supportsFreeRotation = resourcePackStorage.isSupportsFreeRotation();
                final JavaItemModel itemModelData = prepareModelForClient(
                        bedrockGeometry.toJavaItemModel("viabedrock:" + javaTexturePath,
                                rotationTypeFor(supportsFreeRotation)),
                        supportsFreeRotation);
                final JsonObject itemModel = JsonParser.parseString(itemModelData.compile().toString()).getAsJsonObject();

                final JsonObject display = new JsonObject();
                final JsonArray scaling = new JsonArray();
                final float safeScale = Float.isFinite(itemModelData.getScale()) ? itemModelData.getScale() : 1.0f;
                scaling.add(safeScale);
                scaling.add(safeScale);
                scaling.add(safeScale);

                final JsonObject value = new JsonObject();
                value.add("scale", scaling);

                display.add("firstperson_righthand", value);
                display.add("firstperson_lefthand", value);
                display.add("thirdperson_righthand", value);
                display.add("thirdperson_lefthand", value);
                display.add("head", value);
                display.add("gui", value);
                display.add("ground", value);
                display.add("fixed", value);

                itemModel.add("display", display);

                final String modelKey = modelEntry.getKey();
                javaModelDefinitions.put(modelKey, itemModel);
            }
            this.putItemDefinition(javaContent, attachableEntry.getKey(), javaModelDefinitions, resourcePackStorage.isSupportsFreeRotation());
            final ItemDefinitions.ItemDefinition item = resourcePackStorage.getItems().get(attachableEntry.getKey());
            if (!javaModelDefinitions.isEmpty() && item != null && item.iconComponent() != null) {
                final Key itemKey = getItemModel(attachableEntry.getKey()).key();
                final String itemPath = "assets/" + itemKey.namespace() + "/items/" + itemKey.path() + ".json";
                final JsonObject itemDefinition = javaContent.getJson(itemPath);
                final JsonObject icon = new JsonObject();
                icon.addProperty("type", "minecraft:model");
                icon.addProperty("model", CustomItemTextureResourceRewriter.getItemModel(item.iconComponent()).key() + "/0");
                final JsonObject guiCase = new JsonObject();
                guiCase.addProperty("when", "gui");
                guiCase.add("model", icon);
                final JsonArray cases = new JsonArray();
                cases.add(guiCase);
                final JsonObject selector = new JsonObject();
                selector.addProperty("type", "minecraft:select");
                selector.addProperty("property", "minecraft:display_context");
                selector.add("cases", cases);
                // 背包使用物品图标；其余视角继续使用附着模型，包括刻意隐藏的空手持几何。
                selector.add("fallback", itemDefinition.get("model"));
                itemDefinition.add("model", selector);
                javaContent.putJson(itemPath, itemDefinition);
            }
        }
    }

    @Override
    public void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        for (Map.Entry<String, AttachableDefinitions.AttachableDefinition> attachableEntry : resourcePackStorage.getAttachables().attachables().entrySet()) {
            final AttachableDefinitions.AttachableDefinition attachableDefinition = attachableEntry.getValue();
            for (Map.Entry<String, String> modelEntry : attachableDefinition.attachableData().getGeometries().entrySet()) {
                if (!attachableDefinition.attachableData().getTextures().containsKey(modelEntry.getKey())) {
                    continue;
                }
                if (resourcePackStorage.getModels().containsEntityModel(modelEntry.getValue())) {
                    resourcePackStorage.putRuntimeData(
                            "ca_" + attachableEntry.getKey() + '_' + modelEntry.getKey(), true);
                }
            }
        }
    }

    @Override
    public ResourcePackRewriter.RuntimeDataScope runtimeDataScope() {
        return ResourcePackRewriter.RuntimeDataScope.SHARED;
    }

    @Override
    public String artifactFingerprint() {
        return "3";
    }

}
