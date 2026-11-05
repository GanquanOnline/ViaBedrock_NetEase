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
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基岩 PLAYER_ENCHANT_OPTIONS（146 / 0x92 号包）的线路布局。
 * <p>
 * MOT {@code PlayerEnchantOptionsPacket} 依据 {@code gameVersion.isNetEase()}
 * 和协议 974 分支出两种布局：
 * <ul>
 * <li>网易 860 与官方 &lt;974：无符号 varint minLevel，随后三个
 * 附魔列表 {@code {byte type, byte level}}，外加第四个自定义列表
 * 以及仅在网易上出现的每附魔 {@code modEnchantIdentifier} 字符串。</li>
 * <li>官方 974+：byte minLevel，没有自定义列表，每个附魔为
 * {@code {unsigned-varint type, byte level}}。</li>
 * </ul>
 * 不注册该包会被取消，于是 Java 永远收不到
 * {@code CONTAINER_SET_DATA} 的经验消耗 / 提示，也就无法点击选项。
 */
public final class PlayerEnchantOptionsLayout {

    public static final int OFFICIAL_BYTE_MIN_LEVEL_PROTOCOL = 974;
    public static final int MAX_OPTIONS = 1000;
    public static final int MAX_ENCHANTS = 1000;

    private PlayerEnchantOptionsLayout() {
    }

    public static boolean usesOfficial974Layout() {
        return usesOfficial974Layout(emulateNetEase(), protocolVersion());
    }

    public static boolean usesOfficial974Layout(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase && protocol >= OFFICIAL_BYTE_MIN_LEVEL_PROTOCOL;
    }

    public static boolean usesNetEaseCustomEnchants() {
        return usesNetEaseCustomEnchants(emulateNetEase());
    }

    public static boolean usesNetEaseCustomEnchants(final boolean emulateNetEase) {
        return emulateNetEase;
    }

    public static List<EnchantOption> readPacket(final PacketWrapper wrapper) {
        return readPacket(wrapper, emulateNetEase(), protocolVersion());
    }

    public static List<EnchantOption> readPacket(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        if (size < 0 || size > MAX_OPTIONS) {
            throw new IllegalArgumentException("EnchantOptions too big: " + size);
        }
        final boolean official974 = usesOfficial974Layout(emulateNetEase, protocol);
        final List<EnchantOption> options = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final int minLevel = official974
                    ? wrapper.read(Types.UNSIGNED_BYTE)
                    : wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            final int primarySlot = wrapper.read(BedrockTypes.INT_LE);
            final List<EnchantData> enchants0 = readEnchantDataList(wrapper, emulateNetEase, official974);
            final List<EnchantData> enchants1 = readEnchantDataList(wrapper, emulateNetEase, official974);
            final List<EnchantData> enchants2 = readEnchantDataList(wrapper, emulateNetEase, official974);
            final List<EnchantData> enchantsCustom = emulateNetEase
                    ? readEnchantDataList(wrapper, true, false)
                    : Collections.emptyList();
            final String enchantName = wrapper.read(BedrockTypes.STRING);
            final int enchantNetId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            options.add(new EnchantOption(minLevel, primarySlot, enchants0, enchants1, enchants2, enchantsCustom, enchantName, enchantNetId));
        }
        return options;
    }

    public static List<EnchantOption> readPacket(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int size = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (size < 0 || size > MAX_OPTIONS) {
            throw new IllegalArgumentException("EnchantOptions too big: " + size);
        }
        final boolean official974 = usesOfficial974Layout(emulateNetEase, protocol);
        final List<EnchantOption> options = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final int minLevel = official974
                    ? buffer.readUnsignedByte()
                    : BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            final int primarySlot = buffer.readIntLE();
            final List<EnchantData> enchants0 = readEnchantDataList(buffer, emulateNetEase, official974);
            final List<EnchantData> enchants1 = readEnchantDataList(buffer, emulateNetEase, official974);
            final List<EnchantData> enchants2 = readEnchantDataList(buffer, emulateNetEase, official974);
            final List<EnchantData> enchantsCustom = emulateNetEase
                    ? readEnchantDataList(buffer, true, false)
                    : Collections.emptyList();
            final String enchantName = BedrockTypes.STRING.read(buffer);
            final int enchantNetId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            options.add(new EnchantOption(minLevel, primarySlot, enchants0, enchants1, enchants2, enchantsCustom, enchantName, enchantNetId));
        }
        return options;
    }

    public static void writePacket(final ByteBuf buffer, final List<EnchantOption> options,
                                   final boolean emulateNetEase, final int protocol) {
        final List<EnchantOption> list = options != null ? options : Collections.emptyList();
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, list.size());
        final boolean official974 = usesOfficial974Layout(emulateNetEase, protocol);
        for (EnchantOption option : list) {
            if (official974) {
                buffer.writeByte(option.minLevel());
            } else {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, option.minLevel());
            }
            buffer.writeIntLE(option.primarySlot());
            writeEnchantDataList(buffer, option.enchants0(), emulateNetEase, official974);
            writeEnchantDataList(buffer, option.enchants1(), emulateNetEase, official974);
            writeEnchantDataList(buffer, option.enchants2(), emulateNetEase, official974);
            if (emulateNetEase) {
                writeEnchantDataList(buffer, option.enchantsCustom(), true, false);
            }
            BedrockTypes.STRING.write(buffer, option.enchantName() != null ? option.enchantName() : "");
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, option.enchantNetId());
        }
    }

    private static List<EnchantData> readEnchantDataList(final PacketWrapper wrapper, final boolean emulateNetEase, final boolean official974) {
        final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        if (size < 0 || size > MAX_ENCHANTS) {
            throw new IllegalArgumentException("Enchantment list too big: " + size);
        }
        final List<EnchantData> list = new ArrayList<>(size);
        if (official974) {
            for (int i = 0; i < size; i++) {
                list.add(new EnchantData(wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), wrapper.read(Types.UNSIGNED_BYTE), ""));
            }
        } else {
            for (int i = 0; i < size; i++) {
                final int type = wrapper.read(Types.UNSIGNED_BYTE);
                final int level = wrapper.read(Types.UNSIGNED_BYTE);
                final String modId = emulateNetEase ? wrapper.read(BedrockTypes.STRING) : "";
                list.add(new EnchantData(type, level, modId));
            }
        }
        return list;
    }

    private static List<EnchantData> readEnchantDataList(final ByteBuf buffer, final boolean emulateNetEase, final boolean official974) {
        final int size = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (size < 0 || size > MAX_ENCHANTS) {
            throw new IllegalArgumentException("Enchantment list too big: " + size);
        }
        final List<EnchantData> list = new ArrayList<>(size);
        if (official974) {
            for (int i = 0; i < size; i++) {
                list.add(new EnchantData(BedrockTypes.UNSIGNED_VAR_INT.read(buffer), buffer.readUnsignedByte(), ""));
            }
        } else {
            for (int i = 0; i < size; i++) {
                final int type = buffer.readUnsignedByte();
                final int level = buffer.readUnsignedByte();
                final String modId = emulateNetEase ? BedrockTypes.STRING.read(buffer) : "";
                list.add(new EnchantData(type, level, modId));
            }
        }
        return list;
    }

    private static void writeEnchantDataList(final ByteBuf buffer, final List<EnchantData> enchants,
                                             final boolean emulateNetEase, final boolean official974) {
        final List<EnchantData> list = enchants != null ? enchants : Collections.emptyList();
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, list.size());
        if (official974) {
            for (EnchantData data : list) {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, data.type());
                buffer.writeByte(data.level());
            }
            return;
        }
        for (EnchantData data : list) {
            buffer.writeByte(data.type());
            buffer.writeByte(data.level());
            if (emulateNetEase) {
                BedrockTypes.STRING.write(buffer, data.modEnchantIdentifier() != null ? data.modEnchantIdentifier() : "");
            }
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int protocolVersion() {
        if (emulateNetEase() && ViaBedrock.getConfig() != null) {
            return ViaBedrock.getConfig().getNetEaseProtocolVersion();
        }
        return BedrockWire.current();
    }

    public record EnchantData(int type, int level, String modEnchantIdentifier) {
        public EnchantData(final int type, final int level) {
            this(type, level, "");
        }
    }

    public record EnchantOption(int minLevel, int primarySlot,
                                List<EnchantData> enchants0, List<EnchantData> enchants1, List<EnchantData> enchants2,
                                List<EnchantData> enchantsCustom, String enchantName, int enchantNetId) {
        public EnchantOption {
            enchants0 = enchants0 != null ? List.copyOf(enchants0) : List.of();
            enchants1 = enchants1 != null ? List.copyOf(enchants1) : List.of();
            enchants2 = enchants2 != null ? List.copyOf(enchants2) : List.of();
            enchantsCustom = enchantsCustom != null ? List.copyOf(enchantsCustom) : List.of();
            enchantName = enchantName != null ? enchantName : "";
        }

        public EnchantData primaryClue() {
            if (!enchants0.isEmpty()) {
                return enchants0.get(0);
            }
            if (!enchants1.isEmpty()) {
                return enchants1.get(0);
            }
            if (!enchants2.isEmpty()) {
                return enchants2.get(0);
            }
            if (!enchantsCustom.isEmpty()) {
                return enchantsCustom.get(0);
            }
            return null;
        }

        public int javaXpCost() {
            return primarySlot + 1;
        }
    }
}
