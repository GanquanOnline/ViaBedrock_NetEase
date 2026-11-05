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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 网易 ConfirmSkin（数据包 228 / 0xE4）的线上布局。
 * <p>
 * MOT 的 {@code ConfirmSkinPacket.encode()} 写入一个 unsigned-varint 数组，元素为
 * {@code {bool valid, UUID LE, byte[] skinBytes}}，随后再分两轮写入每个条目的
 * {@code uidStr} 和 {@code geoStr}。{@code skinBytes} 是裸
 * RGBA（{@code width * height * 4}），而不是官方那种在前面带
 * width/height 的 IMAGE 类型。play.mcscode.com 的运行时转储与该布局一致，
 * 而不是与 {@code SkinType} 一致。
 */
public final class ConfirmSkinLayout {

    public static final String DEFAULT_RESOURCE_PATCH = SkinArmClassifier.DEFAULT_RESOURCE_PATCH;
    public static final String SLIM_RESOURCE_PATCH = SkinArmClassifier.SLIM_RESOURCE_PATCH;

    private static final int[][] KNOWN_SKIN_SIZES = {
            {64, 32},
            {64, 64},
            {128, 64},
            {128, 128},
            {256, 256}
    };

    private ConfirmSkinLayout() {
    }

    public record Entry(boolean valid, UUID uuid, byte[] skinBytes, String uidStr, String geoStr) {
    }

    public record SkinSize(int width, int height) {
    }

    public static List<Entry> readPacket(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final boolean[] valid = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final byte[][] skinBytes = new byte[count][];
        for (int i = 0; i < count; i++) {
            valid[i] = wrapper.read(Types.BOOLEAN);
            uuids[i] = wrapper.read(BedrockTypes.UUID);
            skinBytes[i] = wrapper.read(BedrockTypes.BYTE_ARRAY);
        }
        final String[] uidStr = new String[count];
        for (int i = 0; i < count; i++) {
            uidStr[i] = wrapper.read(BedrockTypes.STRING);
        }
        final String[] geoStr = new String[count];
        for (int i = 0; i < count; i++) {
            geoStr[i] = wrapper.read(BedrockTypes.STRING);
        }
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(valid[i], uuids[i], skinBytes[i], uidStr[i], geoStr[i]));
        }
        return entries;
    }

    public static List<Entry> readPacket(final ByteBuf buffer) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
        final boolean[] valid = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final byte[][] skinBytes = new byte[count][];
        for (int i = 0; i < count; i++) {
            valid[i] = buffer.readBoolean();
            uuids[i] = BedrockTypes.UUID.read(buffer);
            skinBytes[i] = BedrockTypes.BYTE_ARRAY.read(buffer);
        }
        final String[] uidStr = new String[count];
        for (int i = 0; i < count; i++) {
            uidStr[i] = BedrockTypes.STRING.read(buffer);
        }
        final String[] geoStr = new String[count];
        for (int i = 0; i < count; i++) {
            geoStr[i] = BedrockTypes.STRING.read(buffer);
        }
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(valid[i], uuids[i], skinBytes[i], uidStr[i], geoStr[i]));
        }
        return entries;
    }

    public static void writePacket(final ByteBuf buffer, final List<Entry> entries) {
        BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, entries.size());
        for (Entry entry : entries) {
            buffer.writeBoolean(entry.valid());
            BedrockTypes.UUID.write(buffer, entry.uuid());
            BedrockTypes.BYTE_ARRAY.write(buffer, entry.skinBytes() != null ? entry.skinBytes() : new byte[0]);
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.uidStr() != null ? entry.uidStr() : "");
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.geoStr() != null ? entry.geoStr() : "");
        }
    }

    public static SkinSize inferSkinSize(final int byteLength) {
        if (byteLength <= 0 || (byteLength & 3) != 0) {
            return null;
        }
        final int pixels = byteLength / 4;
        for (int[] size : KNOWN_SKIN_SIZES) {
            if (size[0] * size[1] == pixels) {
                return new SkinSize(size[0], size[1]);
            }
        }
        final int square = (int) Math.round(Math.sqrt(pixels));
        if (square > 0 && square * square == pixels) {
            return new SkinSize(square, square);
        }
        return null;
    }

    public static BufferedImage imageFromRgba(final byte[] data) {
        if (data == null) {
            return null;
        }
        final SkinSize size = inferSkinSize(data.length);
        if (size == null) {
            return null;
        }
        final BufferedImage image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size.height(); y++) {
            for (int x = 0; x < size.width(); x++) {
                final int index = (y * size.width() + x) * 4;
                final int argb = ((data[index + 3] & 0xFF) << 24)
                        | ((data[index] & 0xFF) << 16)
                        | ((data[index + 1] & 0xFF) << 8)
                        | (data[index + 2] & 0xFF);
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    public static SkinData toSkinData(final Entry entry) {
        final BufferedImage image = imageFromRgba(entry.skinBytes());
        final String uid = entry.uidStr() != null && !entry.uidStr().isEmpty()
                ? entry.uidStr()
                : (entry.uuid() != null ? entry.uuid().toString() : "netease-unknown");
        final String geo = entry.geoStr() != null ? entry.geoStr() : "";
        // ConfirmSkin 没有 resourcePatch/armSize。双模板 MOT 几何模型不是
        // 玩家可选的项目——除非文档明确只给出一个模型，否则保持 patch 为空。
        final Boolean slim = SkinArmClassifier.slimFromGeometry(geo);
        final String resourcePatch = slim == null ? "" : (slim ? SLIM_RESOURCE_PATCH : DEFAULT_RESOURCE_PATCH);
        final String armSize = slim == null ? "" : (slim ? "slim" : "wide");
        return new SkinData(
                uid,
                "",
                resourcePatch,
                image,
                Collections.emptyList(),
                null,
                geo,
                "",
                "",
                false,
                false,
                false,
                true,
                "",
                uid,
                armSize,
                "#0",
                Collections.emptyList(),
                Collections.emptyList(),
                true
        );
    }

    /**
     * 仅当 {@code geo} 明确指向瘦身人形时才为 true。双模板的
     * MOT 文档返回 false，以免调用方凭空造出一个 Alex 模型。
     */
    public static boolean isSlimGeometry(final String geo) {
        return Boolean.TRUE.equals(SkinArmClassifier.slimFromGeometry(geo));
    }

    static boolean isSlimGeometryName(final String name) {
        return SkinArmClassifier.isSlimGeometryName(name);
    }

    static boolean isWideGeometryName(final String name) {
        return SkinArmClassifier.isWideGeometryName(name);
    }
}
