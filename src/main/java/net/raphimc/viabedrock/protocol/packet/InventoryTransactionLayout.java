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
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩物品栏事务物品使用载荷的尾部辅助工具。
 * <p>
 * 官方基岩 / Nukkit-MOT 协议 944+ 在 {@code clientInteractPrediction} 之后
 * 追加 {@code clientCooldownState}。官方 860..943 与网易 860 仍结束于
 * 预测结果的 varint。把这个额外字节写入 PlayerAuthInput 的
 * PERFORM_ITEM_INTERACTION 载荷（或独立的 USE_ITEM 事务）
 * 会使其后的模拟量/相机/原始移动字段错位，Nukkit 会以
 * {@code Unable to decode PlayerAuthInputPacket} / {@code Sent malformed packet} 判定整批失败。
 * <p>
 * 1001 的形状只作用于<b>独立的 INVENTORY_TRANSACTION 包</b>。MOT 内嵌在
 * PLAYER_AUTH_INPUT 里的交互载荷仍按旧形状读写（那个开关是 2168），所以
 * 内嵌路径必须显式要旧形状，而不是跟着协议号走。
 */
public final class InventoryTransactionLayout {

    public static final int CLIENT_COOLDOWN_STATE_PROTOCOL = 944;
    /** legacy 槽位存在性 bool / 单字节枚举 / 有符号 actionType 的起点 */
    public static final int TRANSACTION_SHAPE_1001_PROTOCOL = 1001;
    /** 恒 true bool 与 bool 存在性对的终点（不含） */
    public static final int TRANSACTION_SHAPE_2192_PROTOCOL = 2192;
    /** PLAYER_AUTH_INPUT 内嵌交互载荷改形状的起点 */
    public static final int EMBEDDED_INTERACTION_PROTOCOL = 2168;
    /** USE_ITEM 事务在 hotbarSlot 之后多一个手持字节 */
    public static final int USE_ITEM_HAND_PROTOCOL = 2192;
    /** USE_ITEM_ON_ENTITY 与 RELEASE_ITEM 事务补手持字节 */
    public static final int LATE_HAND_PROTOCOL = 2225;

    private InventoryTransactionLayout() {
    }

    public static boolean usesLegacySlotFlag(final int protocol) {
        return protocol >= TRANSACTION_SHAPE_1001_PROTOCOL;
    }

    public static boolean usesConstantBooleans(final int protocol) {
        return protocol >= TRANSACTION_SHAPE_1001_PROTOCOL && protocol < TRANSACTION_SHAPE_2192_PROTOCOL;
    }

    public static boolean usesSourcePresencePairs(final int protocol) {
        return SourceShape.of(protocol) == SourceShape.PRESENCE_PAIRS;
    }

    /** USE_ITEM 事务的手持字节：2192 起紧跟 hotbarSlot，值为主手 0 / 副手 1。 */
    public static boolean usesUseItemHand(final int protocol) {
        return protocol >= USE_ITEM_HAND_PROTOCOL;
    }

    /** USE_ITEM_ON_ENTITY 事务的手持字节。 */
    public static boolean usesUseItemOnEntityHand(final int protocol) {
        return protocol >= LATE_HAND_PROTOCOL;
    }

    /** RELEASE_ITEM 事务的手持字节，位于 headRot 之后。 */
    public static boolean usesReleaseItemHand(final int protocol) {
        return protocol >= LATE_HAND_PROTOCOL;
    }

    public static boolean usesByteEnums(final int protocol) {
        return protocol >= TRANSACTION_SHAPE_1001_PROTOCOL;
    }

    /** @return true 表示内嵌在 PLAYER_AUTH_INPUT 里的交互载荷已经换成 1001 形状 */
    public static boolean usesEmbeddedInteractionShape(final int protocol) {
        return protocol >= EMBEDDED_INTERACTION_PROTOCOL;
    }

    /**
     * 动作来源在线上有三种形状：1001 之前按来源类型分支写各自字段；1001~2191 是两组
     * “外层存在性 + 内层存在性”的 bool 对；2192 起外层 bool 消失，只剩内层存在性 bool。
     */
    public enum SourceShape {

        LEGACY,
        PRESENCE_PAIRS,
        PRESENCE_BOOL;

        /** 伪协议号 0 表示“按旧形状写”，供内嵌载荷复用。 */
        public static SourceShape of(final int protocol) {
            if (protocol < TRANSACTION_SHAPE_1001_PROTOCOL) {
                return LEGACY;
            }
            return protocol < TRANSACTION_SHAPE_2192_PROTOCOL ? PRESENCE_PAIRS : PRESENCE_BOOL;
        }

    }

    public static int readActionType(final ByteBuf buffer, final int protocol) {
        return usesByteEnums(protocol) ? BedrockTypes.VAR_INT.read(buffer) : BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
    }

    public static void writeActionType(final ByteBuf buffer, final int actionType, final int protocol) {
        if (usesByteEnums(protocol)) {
            BedrockTypes.VAR_INT.write(buffer, actionType);
        } else {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, actionType);
        }
    }

    /** triggerType / 客户端交互预测：1001 起是无符号单字节 */
    public static int readUnsignedEnum(final ByteBuf buffer, final int protocol) {
        return usesByteEnums(protocol) ? buffer.readUnsignedByte() : BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
    }

    public static void writeUnsignedEnum(final ByteBuf buffer, final int value, final int protocol) {
        if (usesByteEnums(protocol)) {
            buffer.writeByte(value);
        } else {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value);
        }
    }

    /** 方块面：1001 起是有符号单字节 */
    public static int readFace(final ByteBuf buffer, final int protocol) {
        return usesByteEnums(protocol) ? buffer.readByte() : BedrockTypes.VAR_INT.read(buffer);
    }

    public static void writeFace(final ByteBuf buffer, final int face, final int protocol) {
        if (usesByteEnums(protocol)) {
            buffer.writeByte(face);
        } else {
            BedrockTypes.VAR_INT.write(buffer, face);
        }
    }

    public static boolean usesClientCooldownState() {
        return usesClientCooldownState(BedrockWire.effectiveProtocol());
    }

    public static boolean usesClientCooldownState(final int protocol) {
        return protocol >= CLIENT_COOLDOWN_STATE_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesClientCooldownState(final boolean emulateNetEase, final int protocol) {
        return usesClientCooldownState(protocol);
    }

    public static byte readClientCooldownState(final ByteBuf buffer) {
        return readClientCooldownState(buffer, BedrockWire.effectiveProtocol());
    }

    public static byte readClientCooldownState(final ByteBuf buffer, final int protocol) {
        if (!usesClientCooldownState(protocol)) {
            return 0;
        }
        return buffer.readByte();
    }

    public static byte readClientCooldownState(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesClientCooldownState(emulateNetEase, protocol)) {
            return 0;
        }
        return buffer.readByte();
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState) {
        writeClientCooldownState(buffer, clientCooldownState, BedrockWire.effectiveProtocol());
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState, final int protocol) {
        if (usesClientCooldownState(protocol)) {
            buffer.writeByte(clientCooldownState);
        }
    }
}
