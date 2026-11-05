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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 少数实体数据包的尾部辅助方法——这些包在网易 860 之后
 * 增加了额外的官方 Bedrock 字段。
 * <p>
 * {@code MOB_EFFECT}（0x1C）在协议 897 增加了 {@code ambient}。网易 860 仍然
 * 在 tick varlong 之后结束（典型的入服效果为 16 字节）。越过该载荷
 * 多读一个布尔值，会以 {@code UPDATE_MOB_EFFECT} 为由中止 Java 连接。
 * {@code ANIMATE} 在 897 增加了可选的 {@code swingSource}，{@code ENTITY_EVENT}
 * 在 974 增加了可选的 {@code fireAtPosition}。
 */
public final class EntityPacketLayout {

    public static final int AMBIENT_PROTOCOL = 897;
    public static final int SWING_SOURCE_PROTOCOL = 897;
    public static final int ENTITY_EVENT_FIRE_AT_POSITION_PROTOCOL = 974;
    /** 网易 898 在 ANIMATE 载荷之后追加攻击者的 {@code attackId} varlong。 */
    public static final int NETEASE_ATTACK_ID_PROTOCOL = 898;
    /**
     * MOT 的 {@code AnimatePacket.Action.ROW_RIGHT}/{@code ROW_LEFT}。官方 ViaBedrock 枚举
     * 止于 {@code MagicCriticalHit(5)}；860 仍然使用这些划船 ID，外加一个
     * {@code rowingTime} 浮点数（{@code protocol < 897}）。
     */
    public static final int ROW_RIGHT_ACTION = 128;
    public static final int ROW_LEFT_ACTION = 129;
    public static final int ROWING_TIME_PROTOCOL = 897;

    private EntityPacketLayout() {
    }

    public static boolean usesAmbient() {
        return usesAmbient(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesAmbient(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= AMBIENT_PROTOCOL;
    }

    public static boolean usesSwingSource() {
        return usesSwingSource(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesSwingSource(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= SWING_SOURCE_PROTOCOL;
    }

    public static boolean usesEntityEventFireAtPosition() {
        return usesEntityEventFireAtPosition(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesEntityEventFireAtPosition(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= ENTITY_EVENT_FIRE_AT_POSITION_PROTOCOL;
    }

    public static boolean usesRowingTime() {
        return usesRowingTime(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesRowingTime(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < ROWING_TIME_PROTOCOL;
    }

    public static boolean isRowAction(final int action) {
        return action == ROW_RIGHT_ACTION || action == ROW_LEFT_ACTION;
    }

    public static boolean readAmbient(final PacketWrapper wrapper) {
        if (!usesAmbient()) {
            return false;
        }
        return wrapper.read(Types.BOOLEAN);
    }

    public static boolean usesByteAnimateAction() {
        return usesSwingSource();
    }

    public static boolean usesByteAnimateAction(final boolean emulateNetEase, final int protocol) {
        return usesSwingSource(emulateNetEase, protocol);
    }

    public static int readAnimateAction(final PacketWrapper wrapper) {
        if (usesByteAnimateAction()) {
            return wrapper.read(Types.UNSIGNED_BYTE);
        }
        return wrapper.read(BedrockTypes.VAR_INT);
    }

    public static void writeAnimateAction(final PacketWrapper wrapper, final int action) {
        if (usesByteAnimateAction()) {
            wrapper.write(Types.UNSIGNED_BYTE, (short) action);
        } else {
            wrapper.write(BedrockTypes.VAR_INT, action);
        }
    }

    public static int readAnimateAction(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesByteAnimateAction(emulateNetEase, protocol)) {
            return buffer.readUnsignedByte();
        }
        return BedrockTypes.VAR_INT.read(buffer);
    }

    public static void writeAnimateAction(final ByteBuf buffer, final int action,
                                          final boolean emulateNetEase, final int protocol) {
        if (usesByteAnimateAction(emulateNetEase, protocol)) {
            buffer.writeByte(action);
        } else {
            BedrockTypes.VAR_INT.write(buffer, action);
        }
    }

    public static void skipSwingSource(final PacketWrapper wrapper) {
        if (usesSwingSource()) {
            wrapper.read(BedrockTypes.OPTIONAL_STRING);
        }
    }

    public static boolean usesNetEaseAttackId() {
        return usesNetEaseAttackId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesNetEaseAttackId(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= NETEASE_ATTACK_ID_PROTOCOL;
    }

    public static void skipNetEaseAttackId(final PacketWrapper wrapper) {
        if (usesNetEaseAttackId()) {
            wrapper.read(BedrockTypes.VAR_LONG); // 攻击 ID
        }
    }

    public static void skipRowingTime(final PacketWrapper wrapper, final int action) {
        if (usesRowingTime() && isRowAction(action)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    public static void writeRowingTime(final PacketWrapper wrapper, final int action, final float rowingTime) {
        writeRowingTime(wrapper, action, rowingTime, emulateNetEase(), netEaseProtocol());
    }

    public static void writeRowingTime(final PacketWrapper wrapper, final int action, final float rowingTime,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesRowingTime(emulateNetEase, protocol) && isRowAction(action)) {
            wrapper.write(BedrockTypes.FLOAT_LE, rowingTime);
        }
    }

    public static void writeAnimateTrailer(final PacketWrapper wrapper, final String swingSource) {
        if (usesSwingSource()) {
            wrapper.write(BedrockTypes.OPTIONAL_STRING, swingSource);
        }
    }

    public static void skipEntityEventFireAtPosition(final PacketWrapper wrapper) {
        if (usesEntityEventFireAtPosition()) {
            wrapper.read(BedrockTypes.OPTIONAL_POSITION_3F);
        }
    }

    public static boolean readAmbient(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesAmbient(emulateNetEase, protocol)) {
            return false;
        }
        return buffer.readBoolean();
    }

    public static void skipSwingSource(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesSwingSource(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_STRING.read(buffer);
        }
    }

    public static void skipRowingTime(final ByteBuf buffer, final int action,
                                      final boolean emulateNetEase, final int protocol) {
        if (usesRowingTime(emulateNetEase, protocol) && isRowAction(action)) {
            BedrockTypes.FLOAT_LE.read(buffer);
        }
    }

    public static void writeRowingTime(final ByteBuf buffer, final int action, final float rowingTime,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesRowingTime(emulateNetEase, protocol) && isRowAction(action)) {
            BedrockTypes.FLOAT_LE.write(buffer, rowingTime);
        }
    }

    public static void skipEntityEventFireAtPosition(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesEntityEventFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.read(buffer);
        }
    }

    public static void writeMobEffectTrailer(final ByteBuf buffer, final long tick, final boolean ambient,
                                             final boolean emulateNetEase, final int protocol) {
        BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, tick);
        if (usesAmbient(emulateNetEase, protocol)) {
            buffer.writeBoolean(ambient);
        }
    }

    public static void writeAnimateTrailer(final ByteBuf buffer, final String swingSource,
                                           final boolean emulateNetEase, final int protocol) {
        if (usesSwingSource(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_STRING.write(buffer, swingSource);
        }
    }

    public static void writeEntityEventTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesEntityEventFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.write(buffer, null);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
