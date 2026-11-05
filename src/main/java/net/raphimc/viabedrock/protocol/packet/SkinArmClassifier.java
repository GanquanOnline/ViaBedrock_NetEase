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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import net.raphimc.viabedrock.protocol.model.SkinData;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * 区分 Java 的细手臂（Alex，3px）与宽手臂（Steve，4px）。
 * <p>
 * MOT ConfirmSkin 的 {@code geoStr} 常常是完整的 {@code minecraft:geometry}
 * 文档，其中同时包含 {@code geometry.humanoid.custom} 与
 * {@code geometry.humanoid.customSlim}。该文档是模板包，不是
 * 玩家实际选择的模型。基岩客户端使用 PLAYER_LIST / PLAYER_SKIN 中的
 * {@code skinResourcePatch} / {@code ArmSize}；ConfirmSkin 不携带
 * 这些字段。优先使用显式 patch 或 arm size，其次是明确的
 * 几何模型标识符，最后是 64x64 皮肤中未使用的第 4 条手臂列。
 */
public final class SkinArmClassifier {

    public static final String WIDE_GEOMETRY = "geometry.humanoid.custom";
    public static final String SLIM_GEOMETRY = "geometry.humanoid.customSlim";
    public static final String DEFAULT_RESOURCE_PATCH = "{\"geometry\":{\"default\":\"" + WIDE_GEOMETRY + "\"}}";
    public static final String SLIM_RESOURCE_PATCH = "{\"geometry\":{\"default\":\"" + SLIM_GEOMETRY + "\"}}";

    private SkinArmClassifier() {
    }

    public record Hint(String resourcePatch, String armSize) {
        public static Hint wide() {
            return new Hint(DEFAULT_RESOURCE_PATCH, "wide");
        }

        public static Hint slim() {
            return new Hint(SLIM_RESOURCE_PATCH, "slim");
        }

        public static Hint of(final boolean slim) {
            return slim ? slim() : wide();
        }

        public static Hint fromSkin(final SkinData skin) {
            if (skin == null) {
                return null;
            }
            return from(skin.skinResourcePatch(), skin.armSize());
        }

        public static Hint from(final String resourcePatch, final String armSize) {
            if (isClassicPatch(resourcePatch) || isKnownArmSize(armSize)) {
                return new Hint(resourcePatch != null ? resourcePatch : "", armSize != null ? armSize : "");
            }
            return null;
        }

        public boolean isSlim() {
            if (isSlimGeometryName(defaultGeometry(this.resourcePatch))) {
                return true;
            }
            if (isWideGeometryName(defaultGeometry(this.resourcePatch))) {
                return false;
            }
            return isSlimArmSize(this.armSize);
        }
    }

    public static boolean isSlimGeometryName(final String name) {
        return name != null && (name.equals(SLIM_GEOMETRY) || name.startsWith(SLIM_GEOMETRY + "."));
    }

    public static boolean isWideGeometryName(final String name) {
        return name != null
                && (name.equals(WIDE_GEOMETRY) || name.startsWith(WIDE_GEOMETRY + "."))
                && !isSlimGeometryName(name);
    }

    public static String defaultGeometry(final String resourcePatch) {
        if (resourcePatch == null || resourcePatch.isBlank()) {
            return null;
        }
        try {
            final JsonObject root = JsonParser.parseString(resourcePatch).getAsJsonObject();
            final JsonObject geometry = root.getAsJsonObject("geometry");
            if (geometry == null || !geometry.has("default")) {
                return null;
            }
            final String value = geometry.get("default").getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (final Exception ignored) {
            return null;
        }
    }

    public static boolean isClassicPatch(final String resourcePatch) {
        final String required = defaultGeometry(resourcePatch);
        return isSlimGeometryName(required) || isWideGeometryName(required);
    }

    public static boolean isKnownArmSize(final String armSize) {
        return isSlimArmSize(armSize) || isWideArmSize(armSize);
    }

    public static boolean isSlimArmSize(final String armSize) {
        return armSize != null && "slim".equalsIgnoreCase(armSize.trim());
    }

    public static boolean isWideArmSize(final String armSize) {
        return armSize != null && "wide".equalsIgnoreCase(armSize.trim());
    }

    public static Boolean slimFromPatchOrArmSize(final String resourcePatch, final String armSize) {
        final String required = defaultGeometry(resourcePatch);
        if (isSlimGeometryName(required)) {
            return Boolean.TRUE;
        }
        if (isWideGeometryName(required)) {
            return Boolean.FALSE;
        }
        if (isSlimArmSize(armSize)) {
            return Boolean.TRUE;
        }
        if (isWideArmSize(armSize)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * @return slim/wide when the document names exactly one classic humanoid, otherwise {@code null}
     */
    public static Boolean slimFromGeometry(final String geo) {
        if (geo == null || geo.isBlank()) {
            return null;
        }
        final String trimmed = geo.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            if (isSlimGeometryName(trimmed)) {
                return Boolean.TRUE;
            }
            if (isWideGeometryName(trimmed)) {
                return Boolean.FALSE;
            }
            return null;
        }
        try {
            final JsonElement root = JsonParser.parseString(trimmed);
            final boolean[] found = new boolean[2];
            final JsonObject[] selected = new JsonObject[2];
            collectHumanoids(root, (geometry, identifier) -> {
                if (isSlimGeometryName(identifier)) {
                    found[0] = true;
                    selected[0] = geometry;
                } else if (isWideGeometryName(identifier)) {
                    found[1] = true;
                    selected[1] = geometry;
                }
            });
            final boolean slim = found[0];
            final boolean wide = found[1];
            final JsonObject lastSlim = selected[0];
            final JsonObject lastWide = selected[1];
            if (slim && !wide) {
                final Float armWidth = minArmWidth(lastSlim);
                if (armWidth != null) {
                    return armWidth < 3.5f;
                }
                return Boolean.TRUE;
            }
            if (wide && !slim) {
                final Float armWidth = minArmWidth(lastWide);
                if (armWidth != null) {
                    return armWidth < 3.5f;
                }
                return Boolean.FALSE;
            }
            return null;
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * Java/基岩 64x64 Alex 皮肤会让未使用的第 4 条 Steve 手臂列
     * （{@code x=43, y=20..31}）保持透明。64x32 皮肤始终是宽手臂。
     *
     * @return 当该列明显为空或已绘制时返回 slim/wide，否则返回 {@code null}
     */
    public static Boolean slimFromTexture(final BufferedImage image) {
        if (image == null) {
            return null;
        }
        return slimFromTexture(image.getWidth(), image.getHeight(), (x, y) -> image.getRGB(x, y));
    }

    public static Boolean slimFromRgba(final byte[] data, final int width, final int height) {
        if (data == null || width <= 0 || height <= 0 || data.length < width * height * 4) {
            return null;
        }
        return slimFromTexture(width, height, (x, y) -> {
            final int index = (y * width + x) * 4;
            return ((data[index + 3] & 0xFF) << 24)
                    | ((data[index] & 0xFF) << 16)
                    | ((data[index + 1] & 0xFF) << 8)
                    | (data[index + 2] & 0xFF);
        });
    }

    public static Hint classify(final String resourcePatch, final String armSize, final String geometry, final BufferedImage image, final Hint cached) {
        final Boolean fromPacket = slimFromPatchOrArmSize(resourcePatch, armSize);
        if (fromPacket != null) {
            return Hint.of(fromPacket);
        }
        final Boolean fromGeometry = slimFromGeometry(geometry);
        if (fromGeometry != null) {
            return Hint.of(fromGeometry);
        }
        final Boolean fromTexture = slimFromTexture(image);
        if (fromTexture != null) {
            return Hint.of(fromTexture);
        }
        if (cached != null) {
            return cached;
        }
        return Hint.wide();
    }

    public static SkinData apply(final SkinData skin, final Hint hint) {
        if (skin == null || hint == null) {
            return skin;
        }
        final String patch = isClassicPatch(hint.resourcePatch())
                ? hint.resourcePatch()
                : (hint.isSlim() ? SLIM_RESOURCE_PATCH : DEFAULT_RESOURCE_PATCH);
        final String armSize = isKnownArmSize(hint.armSize())
                ? hint.armSize()
                : (hint.isSlim() ? "slim" : "wide");
        if (patch.equals(skin.skinResourcePatch()) && armSize.equals(skin.armSize())) {
            return skin;
        }
        return new SkinData(
                skin.skinId(),
                skin.playFabId(),
                patch,
                skin.skinData(),
                skin.animations(),
                skin.capeData(),
                skin.geometryData(),
                skin.geometryDataEngineVersion(),
                skin.animationData(),
                skin.premium(),
                skin.persona(),
                skin.capeOnClassic(),
                skin.primaryUser(),
                skin.capeId(),
                skin.fullSkinId(),
                armSize,
                skin.skinColor(),
                skin.personaPieces(),
                skin.tintColors(),
                skin.overridingPlayerAppearance()
        );
    }

    private interface PixelArgb {
        int get(int x, int y);
    }

    private static Boolean slimFromTexture(final int width, final int height, final PixelArgb pixels) {
        if (width < 64 || height < 32) {
            return null;
        }
        if (height < 64) {
            return Boolean.FALSE;
        }
        final int scale = Math.max(1, width / 64);
        int opaque = 0;
        int samples = 0;
        for (int dy = 0; dy < 12; dy++) {
            for (int sy = 0; sy < scale; sy++) {
                for (int sx = 0; sx < scale; sx++) {
                    final int x = 43 * scale + sx;
                    final int y = 20 * scale + dy * scale + sy;
                    if (x >= width || y >= height) {
                        continue;
                    }
                    samples++;
                    if (((pixels.get(x, y) >>> 24) & 0xFF) > 128) {
                        opaque++;
                    }
                }
            }
        }
        if (samples == 0) {
            return null;
        }
        final float ratio = opaque / (float) samples;
        if (ratio < 0.15f) {
            return Boolean.TRUE;
        }
        if (ratio > 0.50f) {
            return Boolean.FALSE;
        }
        return null;
    }

    private interface HumanoidSink {
        void accept(JsonObject geometry, String identifier);
    }

    private static void collectHumanoids(final JsonElement element, final HumanoidSink sink) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                collectHumanoids(child, sink);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        final JsonObject object = element.getAsJsonObject();
        if (object.has("minecraft:geometry")) {
            collectHumanoids(object.get("minecraft:geometry"), sink);
            return;
        }
        final String identifier = geometryIdentifier(object);
        if (isSlimGeometryName(identifier) || isWideGeometryName(identifier)) {
            sink.accept(object, identifier);
        }
    }

    private static String geometryIdentifier(final JsonObject object) {
        if (object.has("description") && object.get("description").isJsonObject()) {
            final JsonObject description = object.getAsJsonObject("description");
            if (description.has("identifier") && description.get("identifier").isJsonPrimitive()) {
                return description.get("identifier").getAsString();
            }
        }
        if (object.has("identifier") && object.get("identifier").isJsonPrimitive()) {
            return object.get("identifier").getAsString();
        }
        return null;
    }

    private static Float minArmWidth(final JsonElement element) {
        final float width = scanArmWidth(element);
        return width == Float.MAX_VALUE ? null : width;
    }

    private static float scanArmWidth(final JsonElement element) {
        float minWidth = Float.MAX_VALUE;
        if (element == null || element.isJsonNull()) {
            return minWidth;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                minWidth = Math.min(minWidth, scanArmWidth(child));
            }
            return minWidth;
        }
        if (!element.isJsonObject()) {
            return minWidth;
        }
        final JsonObject object = element.getAsJsonObject();
        if (object.has("bones")) {
            minWidth = Math.min(minWidth, scanArmWidth(object.get("bones")));
        }
        final String name = object.has("name") && object.get("name").isJsonPrimitive()
                ? object.get("name").getAsString()
                : "";
        if (isArmBone(name) && object.has("cubes") && object.get("cubes").isJsonArray()) {
            final JsonArray cubes = object.getAsJsonArray("cubes");
            for (final JsonElement cubeElement : cubes) {
                if (!cubeElement.isJsonObject()) {
                    continue;
                }
                final JsonObject cube = cubeElement.getAsJsonObject();
                if (!cube.has("size") || !cube.get("size").isJsonArray() || cube.getAsJsonArray("size").isEmpty()) {
                    continue;
                }
                final float width = Math.abs(cube.getAsJsonArray("size").get(0).getAsFloat());
                if (width > 0.1f && width < minWidth) {
                    minWidth = width;
                }
            }
        }
        return minWidth;
    }

    private static boolean isArmBone(final String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return lower.equals("leftarm") || lower.equals("rightarm");
    }
}
