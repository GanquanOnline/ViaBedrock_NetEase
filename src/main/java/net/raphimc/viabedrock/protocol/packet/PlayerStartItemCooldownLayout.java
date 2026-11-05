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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 PLAYER_START_ITEM_COOLDOWN（176 号包）的线路布局辅助方法。
 * <p>
 * Nukkit-MOT {@code PlayerStartItemCoolDownPacket.encode()} 写入
 * {@code string itemCategory + varint coolDownDuration}，不按协议版本分支。
 * MOT 目前会发出 {@code goat_horn}（{@code ItemGoatHorn}）和 {@code shield}
 * （{@code Player.onBlock} 中盾牌被打破时）。Java 1.21.2+ 的冷却数据包
 * 接受物品 Identifier 加剩余刻数；MOT 中不含
 * {@code :} 的类别会被加上 {@code minecraft:<category>} 命名空间。
 */
public final class PlayerStartItemCooldownLayout {

    private PlayerStartItemCooldownLayout() {
    }

    public static void write(final ByteBuf buffer, final String itemCategory, final int coolDownDuration) {
        BedrockTypes.STRING.write(buffer, itemCategory != null ? itemCategory : "");
        BedrockTypes.VAR_INT.writePrimitive(buffer, coolDownDuration);
    }

    public static Packet read(final ByteBuf buffer) {
        final String itemCategory = BedrockTypes.STRING.read(buffer);
        final int coolDownDuration = BedrockTypes.VAR_INT.readPrimitive(buffer);
        return new Packet(itemCategory, coolDownDuration);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String itemCategory = wrapper.read(BedrockTypes.STRING);
        final int coolDownDuration = wrapper.read(BedrockTypes.VAR_INT);
        return new Packet(itemCategory, coolDownDuration);
    }

    /**
     * 把 MOT 的冷却类别映射为 Java 1.21.2+ 的冷却 Identifier。
     * 空类别在 Java 中没有对应物，应被丢弃。
     */
    public static String javaCooldownIdentifier(final String itemCategory) {
        if (itemCategory == null) {
            return null;
        }
        final String trimmed = itemCategory.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return "minecraft:" + trimmed;
    }

    public record Packet(String itemCategory, int coolDownDuration) {
    }
}
