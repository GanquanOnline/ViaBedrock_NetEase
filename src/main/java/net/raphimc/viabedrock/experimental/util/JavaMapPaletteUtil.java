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
package net.raphimc.viabedrock.experimental.util;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.experimental.model.map.MapColor;

import java.awt.Color;
import java.util.Arrays;

public class JavaMapPaletteUtil {

    private static final float[] JAVA_L;
    private static final float[] JAVA_A;
    private static final float[] JAVA_B;
    private static final int[] JAVA_R;
    private static final int[] JAVA_G;
    private static final int[] JAVA_B_RGB;

    // 调色板索引 0-3 是透明「none」基础色的四种深浅，因此不透明像素不能映射到
    // 这些索引（否则会渲染成空洞）。抖动算法从这里开始最近色搜索。
    private static final int FIRST_OPAQUE_COLOR = 4;

    private static final int CACHE_BITS = 5;
    private static final int CACHE_SIZE = 1 << (CACHE_BITS * 3);
    private static final short[] CACHE = new short[CACHE_SIZE];

    static {
        Arrays.fill(CACHE, (short) -1);

        MapColor[] colors = MapColor.values();
        JAVA_L = new float[colors.length];
        JAVA_A = new float[colors.length];
        JAVA_B = new float[colors.length];
        JAVA_R = new int[colors.length];
        JAVA_G = new int[colors.length];
        JAVA_B_RGB = new int[colors.length];

        for (int i = 0; i < colors.length; i++) {
            Color c = colors[i].getColor();
            float[] lab = ColorSpaceUtil.rgbToLab(c.getRed(), c.getGreen(), c.getBlue());
            JAVA_L[i] = lab[0];
            JAVA_A[i] = lab[1];
            JAVA_B[i] = lab[2];
            JAVA_R[i] = c.getRed();
            JAVA_G[i] = c.getGreen();
            JAVA_B_RGB[i] = c.getBlue();
        }
    }

    public static short[] convertToJavaPalette(int[] bedrockColors) {
        // TODO: 检查草/树叶/水的生物群系着色
        short[] javaColors = new short[bedrockColors.length];

        for (int i = 0; i < bedrockColors.length; i++) {
            int c = bedrockColors[i];

            int a = (c >>> 24);
            if (a == 0) {
                javaColors[i] = 0;
                continue;
            }

            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;

            int key = quantKey(r, g, b);
            short cached = CACHE[key];
            if (cached != -1) {
                javaColors[i] = cached;
                continue;
            }

            float[] lab = ColorSpaceUtil.rgbToLab(r, g, b);

            float bestDist = Float.MAX_VALUE;
            short best = 0;

            for (short j = 0; j < JAVA_L.length; j++) {
                float dL = lab[0] - JAVA_L[j];
                float dA = lab[1] - JAVA_A[j];
                float dB = lab[2] - JAVA_B[j];

                float dist = dL * dL + dA * dA + dB * dB;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = j;
                }
            }

            CACHE[key] = best;
            javaColors[i] = best;
        }

        return javaColors;
    }

    /**
     * 使用 Floyd-Steinberg 抖动把基岩地图像素转换为 Java 调色板。每个像素的残余
     * 量化误差会扩散到尚未处理的邻居，从而把生硬的色带变成精细
     * 噪点，大幅改善人脸/肖像之类的平滑渐变。
     *
     * @param width  纹理宽度；如果尺寸与像素数量不匹配，则回退到最近色。
     *
     * @param height 纹理高度。
     */
    public static short[] convertToJavaPaletteDithered(final int[] bedrockColors, final int width, final int height) {
        if (width <= 0 || height <= 0 || (long) width * height != bedrockColors.length) {
            return convertToJavaPalette(bedrockColors); // 未知结构，无法安全抖动
        }

        final short[] javaColors = new short[bedrockColors.length];
        final float[] errR = new float[bedrockColors.length];
        final float[] errG = new float[bedrockColors.length];
        final float[] errB = new float[bedrockColors.length];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int idx = y * width + x;
                final int c = bedrockColors[idx];

                final int a = (c >>> 24);
                if (a == 0) {
                    javaColors[idx] = 0; // 透明
                    continue;
                }

                // 与最近色路径相同的通道提取（与调色板表保持一致）。
                final float fr = ((c >> 16) & 0xFF) + errR[idx];
                final float fg = ((c >> 8) & 0xFF) + errG[idx];
                final float fb = (c & 0xFF) + errB[idx];

                final short best = nearestPaletteIndex(clamp255(fr), clamp255(fg), clamp255(fb), FIRST_OPAQUE_COLOR);
                javaColors[idx] = best;

                final float er = fr - JAVA_R[best];
                final float eg = fg - JAVA_G[best];
                final float eb = fb - JAVA_B_RGB[best];

                diffuse(errR, errG, errB, width, height, x + 1, y, er, eg, eb, 7F / 16F);
                diffuse(errR, errG, errB, width, height, x - 1, y + 1, er, eg, eb, 3F / 16F);
                diffuse(errR, errG, errB, width, height, x, y + 1, er, eg, eb, 5F / 16F);
                diffuse(errR, errG, errB, width, height, x + 1, y + 1, er, eg, eb, 1F / 16F);
            }
        }

        return javaColors;
    }

    private static void diffuse(final float[] errR, final float[] errG, final float[] errB, final int width, final int height,
                                final int x, final int y, final float er, final float eg, final float eb, final float factor) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        final int idx = y * width + x;
        errR[idx] += er * factor;
        errG[idx] += eg * factor;
        errB[idx] += eb * factor;
    }

    private static short nearestPaletteIndex(final int r, final int g, final int b, final int startIndex) {
        final float[] lab = ColorSpaceUtil.rgbToLab(r, g, b);
        float bestDist = Float.MAX_VALUE;
        short best = (short) startIndex;
        for (short j = (short) startIndex; j < JAVA_L.length; j++) {
            final float dL = lab[0] - JAVA_L[j];
            final float dA = lab[1] - JAVA_A[j];
            final float dB = lab[2] - JAVA_B[j];
            final float dist = dL * dL + dA * dA + dB * dB;
            if (dist < bestDist) {
                bestDist = dist;
                best = j;
            }
        }
        return best;
    }

    private static int clamp255(final float v) {
        final int i = Math.round(v);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }

    private static int quantKey(int r, int g, int b) {
        int rq = r >> (8 - CACHE_BITS);
        int gq = g >> (8 - CACHE_BITS);
        int bq = b >> (8 - CACHE_BITS);
        return (rq << (CACHE_BITS * 2)) | (gq << CACHE_BITS) | bq;
    }

}
