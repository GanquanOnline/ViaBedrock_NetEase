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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.util;

/**
 * 地图调色板与 Glow 颜色量化共享的 sRGB/Lab 转换。
 */
public final class ColorSpaceUtil {
    private ColorSpaceUtil() {
    }

    public static float[] rgbToLab(final int red, final int green, final int blue) {
        float redLinear = pivotRgb(red / 255F);
        float greenLinear = pivotRgb(green / 255F);
        float blueLinear = pivotRgb(blue / 255F);

        float x = redLinear * 0.4124F + greenLinear * 0.3576F + blueLinear * 0.1805F;
        float y = redLinear * 0.2126F + greenLinear * 0.7152F + blueLinear * 0.0722F;
        float z = redLinear * 0.0193F + greenLinear * 0.1192F + blueLinear * 0.9505F;
        return xyzToLab(x, y, z);
    }

    public static float distanceSquared(final float[] first, final float[] second) {
        float deltaL = first[0] - second[0];
        float deltaA = first[1] - second[1];
        float deltaB = first[2] - second[2];
        return deltaL * deltaL + deltaA * deltaA + deltaB * deltaB;
    }

    private static float pivotRgb(final float value) {
        return value <= 0.04045F
                ? value / 12.92F
                : (float) Math.pow((value + 0.055F) / 1.055F, 2.4F);
    }

    private static float[] xyzToLab(final float x, final float y, final float z) {
        float fx = pivotXyz(x / 0.95047F);
        float fy = pivotXyz(y);
        float fz = pivotXyz(z / 1.08883F);
        return new float[]{
                116F * fy - 16F,
                500F * (fx - fy),
                200F * (fy - fz)
        };
    }

    private static float pivotXyz(final float value) {
        return value > 0.008856F
                ? (float) Math.cbrt(value)
                : (7.787F * value) + (16F / 116F);
    }
}
