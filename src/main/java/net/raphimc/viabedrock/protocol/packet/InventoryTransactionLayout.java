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

import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;

/**
 * 基岩物品栏事务物品使用载荷的尾部辅助工具。
 * <p>
 * 官方基岩 / Nukkit-MOT 协议 944+ 在 {@code clientInteractPrediction} 之后
 * 追加 {@code clientCooldownState}。网易 860 仍结束于
 * 预测结果的 varint。把这个额外字节写入 PlayerAuthInput 的
 * PERFORM_ITEM_INTERACTION 载荷（或独立的 USE_ITEM 事务）
 * 会使其后的模拟量/相机/原始移动字段错位，Nukkit 会以
 * {@code Unable to decode PlayerAuthInputPacket} / {@code Sent malformed packet} 判定整批失败。
 */
public final class InventoryTransactionLayout {

    public static final int CLIENT_COOLDOWN_STATE_PROTOCOL = 944;

    private InventoryTransactionLayout() {
    }

    public static boolean usesClientCooldownState() {
        return usesClientCooldownState(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesClientCooldownState(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= CLIENT_COOLDOWN_STATE_PROTOCOL;
    }

    public static byte readClientCooldownState(final ByteBuf buffer) {
        return readClientCooldownState(buffer, emulateNetEase(), netEaseProtocol());
    }

    public static byte readClientCooldownState(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesClientCooldownState(emulateNetEase, protocol)) {
            return 0;
        }
        return buffer.readByte();
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState) {
        writeClientCooldownState(buffer, clientCooldownState, emulateNetEase(), netEaseProtocol());
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState,
                                                final boolean emulateNetEase, final int protocol) {
        if (usesClientCooldownState(emulateNetEase, protocol)) {
            buffer.writeByte(clientCooldownState);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
