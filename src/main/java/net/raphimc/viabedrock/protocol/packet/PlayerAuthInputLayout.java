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

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 基岩 {@code PLAYER_AUTH_INPUT}（0x90 / 144 号包）的线路布局辅助方法。
 * <p>
 * 官方基岩与 Nukkit-MOT 在协议 2168 之前仍把输入标志编码为 64 位无符号 varlong。
 * 网易在 {@code ClientAckServerData}（序号 44）之前立即插入额外的未使用位：
 * 协议 &gt;= 686 增加 1 位，协议 &gt;= 819（含网易 860）增加 2 位。Nukkit 把线路位 0-43
 * 保留在原版序号上，跳过网易的额外位，并把 {@code wire >= 44 + extra}
 * 映射回 {@code ordinal = wire - extra}。因此编码时标志 {@code < 44}
 * 保持不变，标志 {@code >= 44} 写成 {@code value + extra}。
 * <p>
 * Nukkit 只检查位 0-63，并拒绝超过 10 字节的无符号 varlong，因此
 * 会落到 64 位及以上的网易标志（例如 {@code SneakCurrentRaw}）被省略。
 * <p>
 * 此前的映射丢弃了位 14/15，并把 16-43 下移 2 位。这使
 * 加入时的 {@code HandledTeleport(37)} 变成线路位 35（{@code PERFORM_BLOCK_ACTIONS}）。
 * Nukkit 随后把剩余的模拟移动浮点数当作方块动作载荷，
 * 并以 {@code Sent malformed packet} 让这条 65 字节前缀批次失败。
 */
public final class PlayerAuthInputLayout {

    public static final int RECEIVED_SERVER_DATA_ORDINAL = 44;
    public static final int PERFORM_BLOCK_ACTIONS_ORDINAL = 35;
    public static final int HANDLED_TELEPORT_ORDINAL = 37;
    public static final int TWO_EXTRA_FLAGS_PROTOCOL = 819;
    public static final int ONE_EXTRA_FLAG_PROTOCOL = 686;
    public static final int CAMERA_DEPARTED_PROTOCOL = 422;
    public static final int NET_EASE_TAIL_PROTOCOL = 766;
    public static final int NUKKIT_FLAG_BIT_LIMIT = 64;
    /** 可选段多出的恒 true 外层 bool 在 2192 消失，之后只剩内层存在性 */
    public static final int OPTIONAL_SECTION_OUTER_BOOL_END = 2192;

    private PlayerAuthInputLayout() {
    }

    public static BigInteger encodeBitmask(final Set<PlayerAuthInputPacket_InputData> inputData) {
        return encodeBitmask(inputData, emulateNetEase(), netEaseProtocol());
    }

    public static BigInteger encodeBitmask(final Set<PlayerAuthInputPacket_InputData> inputData,
                                           final boolean emulateNetEase, final int protocol) {
        final int extraFlags = extraInputFlags(emulateNetEase, protocol);
        if (extraFlags <= 0) {
            return EnumUtil.getBigBitmaskFromEnumSet(inputData, PlayerAuthInputPacket_InputData::getValue);
        }
        return bitmask(inputData, extraFlags);
    }

    public static boolean usesCameraDeparted() {
        return usesCameraDeparted(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesCameraDeparted(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= CAMERA_DEPARTED_PROTOCOL;
    }

    /**
     * 网易 {@code v1_21_50} 起在 {@code rawMoveVector} 之后追加固定尾部块。
     * <p>
     * MOT 的 {@code PlayerAuthInputPacket.decode()} 会无条件读这 12 字节，缺了就读越界，
     * 整个批次解包失败，最终以 {@code Sent malformed packet} 断连。
     */
    public static boolean usesNetEaseTail() {
        return usesNetEaseTail(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesNetEaseTail(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= NET_EASE_TAIL_PROTOCOL;
    }

    /** @return true 表示输入标志不再是 64 位掩码，而是无符号 varint 计数的条目列表 */
    public static boolean usesInputDataList(final int protocol) {
        return BedrockWire.applies(protocol, BedrockDeviation.PLAYER_AUTH_INPUT_INPUT_LIST);
    }

    /** @return true 表示可选段前多一个恒 true 的外层 bool */
    public static boolean usesOptionalSectionOuterBool(final int protocol) {
        return BedrockWire.applies(protocol, BedrockDeviation.PLAYER_AUTH_INPUT_OPTIONAL_SECTIONS)
                && protocol < OPTIONAL_SECTION_OUTER_BOOL_END;
    }

    /**
     * @return true 表示方块动作条目恒带位置与朝向，不按动作类型省略
     * <p>
     * MOT 只看 {@code protocol >= v1_26_40} 本身，与可选段双 bool 无关；两者区间相同，共用一个偏差。
     */
    public static boolean usesUnconditionalBlockActions(final int protocol) {
        return BedrockWire.applies(protocol, BedrockDeviation.PLAYER_AUTH_INPUT_OPTIONAL_SECTIONS);
    }

    /** @return 按当前生效的官方协议号判定，见 {@link #usesInputDataList(int)} */
    public static boolean usesInputDataList() {
        return usesInputDataList(BedrockWire.current());
    }

    /** @return 按当前生效的官方协议号判定，见 {@link #usesOptionalSectionOuterBool(int)} */
    public static boolean usesOptionalSectionOuterBool() {
        return usesOptionalSectionOuterBool(BedrockWire.current());
    }

    /** @return 按当前生效的官方协议号判定，见 {@link #usesUnconditionalBlockActions(int)} */
    public static boolean usesUnconditionalBlockActions() {
        return usesUnconditionalBlockActions(BedrockWire.current());
    }

    public static int extraInputFlags(final boolean emulateNetEase, final int protocol) {
        if (!emulateNetEase || usesInputDataList(protocol)) {
            return 0;
        }
        if (protocol >= TWO_EXTRA_FLAGS_PROTOCOL) {
            return 2;
        }
        if (protocol >= ONE_EXTRA_FLAG_PROTOCOL) {
            return 1;
        }
        return 0;
    }

    public static int wireBit(final int value, final int extraFlags) {
        if (extraFlags <= 0 || value < RECEIVED_SERVER_DATA_ORDINAL) {
            return value;
        }
        return value + extraFlags;
    }

    public static BigInteger bitmask(final Set<PlayerAuthInputPacket_InputData> inputData, final int extraFlags) {
        BigInteger bitmask = BigInteger.ZERO;
        for (final PlayerAuthInputPacket_InputData flag : inputData) {
            final int bit = wireBit(flag.getValue(), extraFlags);
            if (bit >= 0 && bit < NUKKIT_FLAG_BIT_LIMIT) {
                bitmask = bitmask.setBit(bit);
            }
        }
        return bitmask;
    }

    public static Set<Integer> decodeToVanillaOrdinals(final BigInteger wireBits, final int extraFlags) {
        final Set<Integer> ordinals = new TreeSet<>();
        final int firstShiftedOrdinal = RECEIVED_SERVER_DATA_ORDINAL + extraFlags;
        for (int i = 0; i < NUKKIT_FLAG_BIT_LIMIT; i++) {
            int offset = 0;
            if (extraFlags > 0 && i >= RECEIVED_SERVER_DATA_ORDINAL) {
                if (i < firstShiftedOrdinal) {
                    continue;
                }
                offset = -extraFlags;
            }
            if (wireBits.testBit(i)) {
                ordinals.add(i + offset);
            }
        }
        return ordinals;
    }

    public static Set<PlayerAuthInputPacket_InputData> decodeToInputData(final BigInteger wireBits, final int extraFlags) {
        final Set<PlayerAuthInputPacket_InputData> flags = EnumSet.noneOf(PlayerAuthInputPacket_InputData.class);
        for (final int ordinal : decodeToVanillaOrdinals(wireBits, extraFlags)) {
            final PlayerAuthInputPacket_InputData flag = PlayerAuthInputPacket_InputData.getByValue(ordinal);
            if (flag != null) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
