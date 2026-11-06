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

import net.raphimc.viabedrock.protocol.data.BedrockWire;

/**
 * 基岩 {@code BlockVector3} 的线格式布局辅助。
 * <p>
 * 官方基岩 / Nukkit-MOT 协议 944+（{@code V1_26_10}）把 Y 编码为
 * zigzag varint。944 之前（官方 860..943 与网易 860 一样）是
 * {@code zigzag X, unsigned-varint Y, zigzag Z}。把三个 zigzag varint 写进
 * 860 的 USE_ITEM / CONTAINER_OPEN 会把 Y=64 变成 Y=32，于是箱子和
 * 右键使用方块会落在空气上。
 * <p>
 * Nukkit {@code getSignedBlockPosition()} / 实体元数据 POS 在 860 和官方 975 上
 * 始终是三个 zigzag varint。这些包必须继续
 * 使用 {@code SIGNED_BLOCK_POSITION}。
 */
public final class BlockPositionLayout {

    public static final int SIGNED_Y_PROTOCOL = 944;

    private BlockPositionLayout() {
    }

    public static boolean usesUnsignedY() {
        return usesUnsignedY(BedrockWire.effectiveProtocol());
    }

    public static boolean usesUnsignedY(final int protocol) {
        return protocol > 0 && protocol < SIGNED_Y_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesUnsignedY(final boolean emulateNetEase, final int protocol) {
        return usesUnsignedY(protocol);
    }

}

