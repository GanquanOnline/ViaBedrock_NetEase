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
 * MOT 死亡／立即重生数据包的线上布局辅助方法。
 * <p>
 * Nukkit-MOT 的 {@code Player.kill()}（协议 860）不会为本地
 * 玩家发出 {@code UPDATE_ATTRIBUTES(health=0)} 或 {@code ActorEvent.DEATH}。
 * 在 {@code PlayerDeathEvent} 之后它写入：
 * <ol>
 * <li>{@code DeathInfoPacket}（189）——字符串翻译键 + string[] 参数
 * ——仅当 {@code SHOW_DEATH_MESSAGES} 开启且死亡消息非空时；</li>
 * <li>{@code RespawnPacket}（45），状态为 {@code STATE_SEARCHING_FOR_SPAWN=0}；</li>
 * <li>{@code SetHealthPacket}（42），携带 {@code UnsignedVarInt health = getMaxHealth()}
 * ——仅当 {@code doImmediateRespawn} 为 true 时。</li>
 * </ol>
 * {@code RespawnProcessor} 之后会以 {@code STATE_READY_TO_SPAWN=1} 回复客户端的
 * {@code STATE_CLIENT_READY_TO_SPAWN=2}。
 * 立即重生从不自动调用 {@code handleRespawnRequest()}；Java
 * 客户端仍须自行发送 {@code PERFORM_RESPAWN} / {@code ClientReadyToSpawn}。
 * <p>
 * 官方 975+ 可能在 860 载荷之后追加字段；调用方必须在成功读取后
 * 调用 {@link PacketLeftoverLayout#discardUnreadInput}。
 */
public final class DeathSyncLayout {

    private DeathSyncLayout() {
    }

    public static void writeDeathInfo(final ByteBuf buffer, final String messageTranslationKey, final String[] messageParameters) {
        BedrockTypes.STRING.write(buffer, messageTranslationKey != null ? messageTranslationKey : "");
        final String[] parameters = messageParameters != null ? messageParameters : new String[0];
        BedrockTypes.STRING_ARRAY.write(buffer, parameters);
    }

    public static DeathInfo readDeathInfo(final PacketWrapper wrapper) {
        final String message = wrapper.read(BedrockTypes.STRING);
        final String[] parameters = wrapper.read(BedrockTypes.STRING_ARRAY);
        return new DeathInfo(message, parameters != null ? parameters : new String[0]);
    }

    public static void writeSetHealth(final ByteBuf buffer, final int health) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, Math.max(0, health));
    }

    public static int readSetHealth(final PacketWrapper wrapper) {
        return wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
    }

    /**
     * MOT {@code SetHealthPacket} on immediate respawn writes {@code getMaxHealth()}
     * while the player is still dead ({@code this.health = 0}). Values {@code >= 1}
     * therefore mean "start the Java respawn handshake", not "the player is alive".
     */
    public static boolean isImmediateRespawnHealth(final int health) {
        return health >= 1;
    }

    public record DeathInfo(String messageTranslationKey, String[] messageParameters) {
    }
}
