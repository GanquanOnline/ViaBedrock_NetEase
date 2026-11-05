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

import com.viaversion.viaversion.api.minecraft.item.data.CustomModelData1_21_4;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Cube;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.util.Map;
import java.util.Set;

public abstract class ItemModelResourceRewriter implements ResourcePackRewriter.Rewriter {

    private static final float LEGACY_MODEL_CENTER = 8F;
    private static final float LEGACY_MODEL_RADIUS = 24F;
    private static final float LEGACY_MODEL_MIN = -16F;
    private static final float LEGACY_MODEL_MAX = 32F;
    private static final Set<String> LEGACY_ROTATION_AXES = Set.of("x", "y", "z");

    public static CustomModelData1_21_4 getCustomModelData(final String key) {
        return new CustomModelData1_21_4(new float[0], new boolean[0], new String[]{key}, new int[0]);
    }

    private final String subFolder;

    public ItemModelResourceRewriter(final String subFolder) {
        this.subFolder = subFolder;
    }

    protected void putItemDefinition(final Content javaContent, final String name, final Map<String, JsonObject> modelDefinitions, final boolean supportsFreeRotation) {
        if (modelDefinitions.isEmpty()) {
            return;
        }
        final String itemPath = this.subFolder + '/' + StringUtil.makeIdentifierValueSafe(name);
        final JsonArray modelCases = new JsonArray();
        for (Map.Entry<String, JsonObject> modelDefinition : modelDefinitions.entrySet()) {
            final String modelPath = itemPath + '/' + StringUtil.makeIdentifierValueSafe(modelDefinition.getKey());
            sanitizeLegacyElements(modelDefinition.getValue(), supportsFreeRotation);
            ensureParticleTexture(modelDefinition.getValue());
            javaContent.putJson("assets/viabedrock/models/" + modelPath + ".json", modelDefinition.getValue());

            final JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", "viabedrock:" + modelPath);

            final JsonObject caseObj = new JsonObject();
            caseObj.addProperty("when", modelDefinition.getKey());
            caseObj.add("model", model);
            modelCases.add(caseObj);
        }

        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:select");
        model.addProperty("property", "minecraft:custom_model_data");
        model.add("cases", modelCases);
        final JsonObject itemDefinitionObj = new JsonObject();
        itemDefinitionObj.add("model", model);
        javaContent.putJson("assets/viabedrock/items/" + itemPath + ".json", itemDefinitionObj);
    }

    protected static RotationType rotationTypeFor(final boolean supportsFreeRotation) {
        return supportsFreeRotation ? RotationType.POST_1_21_11 : RotationType.HACKY_POST_1_21_6;
    }

    /**
     * 把 1.21.11 之前的元素坐标保持在原版 [-16, 32] 立方体内。模型的
     * 补偿显示缩放会保留其原始世界尺寸。
     */
    public static JavaItemModel prepareModelForClient(final JavaItemModel model, final boolean supportsFreeRotation) {
        if (supportsFreeRotation) {
            return model;
        }

        float maxDistance = 0F;
        for (Parent parent : model.getParents()) {
            for (Cube cube : parent.getCubes().values()) {
                final var from = cube.getPosition().asJavaPosition(cube.getSize());
                final var to = from.add(cube.getSize());
                maxDistance = Math.max(maxDistance, maxDistanceFromLegacyCenter(from.getX(), from.getY(), from.getZ()));
                maxDistance = Math.max(maxDistance, maxDistanceFromLegacyCenter(to.getX(), to.getY(), to.getZ()));
            }
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= LEGACY_MODEL_RADIUS) {
            return model;
        }

        final float geometryScale = Math.nextDown(LEGACY_MODEL_RADIUS) / maxDistance;
        for (Parent parent : model.getParents()) {
            parent.getPivot().scale(geometryScale);
            for (Cube cube : parent.getCubes().values()) {
                cube.getPosition().scale(geometryScale);
                cube.getSize().scale(geometryScale);
                cube.getPivot().scale(geometryScale);
            }
        }
        final float currentScale = Float.isFinite(model.getScale()) && model.getScale() > 0F
                ? model.getScale() : 1F;
        model.setScale(currentScale / geometryScale);
        return model;
    }

    private static float maxDistanceFromLegacyCenter(final float x, final float y, final float z) {
        return Math.max(Math.abs(x - LEGACY_MODEL_CENTER),
                Math.max(Math.abs(y - LEGACY_MODEL_CENTER), Math.abs(z - LEGACY_MODEL_CENTER)));
    }

    /**
     * 原版要求每个几何模型（带 "elements" 且没有 parent 的模型）声明一个
     * "particle" 纹理。CubeConverter 只输出带编号的纹理槽，因此原版
     * 加载器会为每个生成的实体/挂饰模型（数千个）打印 "Missing texture references ... particle"，
     * 并同步阻塞资源重载。把 particle 指向第一个
     * 已存在的纹理槽，使模型完整。与版本无关：所有客户端版本都要求 particle。
     */
    private static void ensureParticleTexture(final JsonObject model) {
        if (!model.has("elements")) {
            return; // item/generated 风格的模型（无 elements）不需要 particle 纹理
        }
        final JsonElement texturesElement = model.get("textures");
        if (texturesElement == null || !texturesElement.isJsonObject()) {
            return;
        }
        final JsonObject textures = texturesElement.getAsJsonObject();
        if (textures.has("particle") || textures.size() == 0) {
            return;
        }
        final String firstKey = textures.keySet().iterator().next();
        textures.add("particle", textures.get(firstKey)); // 复用同一个纹理
    }

    protected String getJavaTexturePath(final String bedrockPath) {
        return "item/" + this.subFolder + '/' + StringUtil.makeIdentifierValueSafe(bedrockPath.replace("textures/", ""));
    }

    /**
     * CubeConverter 以自由旋转对象形式 {x,y,z,origin} 输出元素旋转（仅在
     * MC >= 1.21.11 合法）。在更旧的客户端上，原版模型反序列化器会拒绝它（"Missing axis"），
     * 导致整个模型加载失败——而数千个生成的实体/挂饰模型此前
     * 会同步记录错误并让资源重载卡住数分钟，
     * 最终使基岩连接超时。
     * <p>
     * 对于不支持自由旋转的客户端，丢弃不支持的自由旋转以及格式错误
     * 或超出范围的旧式旋转。转换器通常输出旧式兼容模型；
     * 这最后一轮可防止某个意外方块使整个生成模型失效。
     * 它还会把元素边界钳制到旧式编解码器的 [-16, 32] 区间作为最终保障。
     */
    private static void sanitizeLegacyElements(final JsonObject model, final boolean supportsFreeRotation) {
        if (supportsFreeRotation) {
            return; // MC >= 1.21.11：自由旋转合法，保持原样
        }
        final JsonElement elementsElement = model.get("elements");
        if (elementsElement == null || !elementsElement.isJsonArray()) {
            return;
        }
        for (final JsonElement elementEntry : elementsElement.getAsJsonArray()) {
            if (!elementEntry.isJsonObject()) {
                continue;
            }
            final JsonObject element = elementEntry.getAsJsonObject();
            final JsonElement rotation = element.get("rotation");
            if (rotation != null && (!rotation.isJsonObject()
                    || isInvalidLegacyRotation(rotation.getAsJsonObject()))) {
                element.remove("rotation");
            }
            clampLegacyVector(element, "from");
            clampLegacyVector(element, "to");
        }
    }

    private static boolean isInvalidLegacyRotation(final JsonObject rotation) {
        if (rotation.has("x") || rotation.has("y") || rotation.has("z")) {
            return true;
        }
        final JsonElement angleElement = rotation.get("angle");
        final JsonElement axisElement = rotation.get("axis");
        if (angleElement == null || !angleElement.isJsonPrimitive()
                || !angleElement.getAsJsonPrimitive().isNumber()
                || axisElement == null || !axisElement.isJsonPrimitive()
                || !axisElement.getAsJsonPrimitive().isString()) {
            return true;
        }
        final double angle = angleElement.getAsDouble();
        return !Double.isFinite(angle) || Math.abs(angle) > 45D
                || !LEGACY_ROTATION_AXES.contains(axisElement.getAsString());
    }

    private static void clampLegacyVector(final JsonObject element, final String key) {
        final JsonElement vectorElement = element.get(key);
        if (vectorElement == null || !vectorElement.isJsonArray()
                || vectorElement.getAsJsonArray().size() != 3) {
            return;
        }
        final JsonArray vector = vectorElement.getAsJsonArray();
        for (int i = 0; i < vector.size(); i++) {
            final JsonElement coordinate = vector.get(i);
            if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            final double value = coordinate.getAsDouble();
            final double finiteValue = Double.isFinite(value) ? value : LEGACY_MODEL_CENTER;
            vector.set(i, new JsonPrimitive(Math.max(LEGACY_MODEL_MIN, Math.min(LEGACY_MODEL_MAX, finiteValue))));
        }
    }

    @Override
    public String artifactFingerprint() {
        return "2";
    }

}
