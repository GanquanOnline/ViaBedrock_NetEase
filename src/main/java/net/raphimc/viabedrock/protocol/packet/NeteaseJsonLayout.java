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
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 网易 NeteaseJson（203 / 0xCB 号包）的线路布局。
 * <p>
 * MOT {@code NeteaseJsonPacket} 是单个 JSON 字符串。MOT 自身目前唯一会
 * 发出的载荷是带 {@code gravity} 数字的 {@code SET_LEVEL_GRAVITY}
 * （{@code Player.sendNetEaseLevelGravityReset()} 写入 {@code -0.08}）。
 * 其他事件名作为常量存在于该数据包类上，但本工作区中的 MOT
 * 与 NukkitMaster 都没有序列化它们的字段，因此这些事件
 * 只被消费，不臆造 schema。
 */
public final class NeteaseJsonLayout {

    public static final String EVENT_SET_LEVEL_GRAVITY = "SET_LEVEL_GRAVITY";
    public static final String EVENT_SET_PLAYER_SIZE = "SET_PLAYER_SIZE";
    public static final String EVENT_CAN_PLAYER_MOVE = "CAN_PLAYER_MOVE";
    public static final String EVENT_CAN_PLAYER_ATTACK = "CAN_PLAYER_ATTACK";
    public static final String EVENT_SET_PLAYER_POSITION = "SET_PLAYER_POSITION";
    public static final String EVENT_SET_JUMP_POWER = "SET_JUMP_POWER";
    public static final String EVENT_SET_PLAYER_SIZE_AABB = "SET_PLAYER_SIZE_AABB";

    private NeteaseJsonLayout() {
    }

    public record Event(String rawJson, String eventName, JsonObject object) {
        public boolean hasEventName() {
            return this.eventName != null && !this.eventName.isEmpty();
        }
    }

    public static String readJson(final PacketWrapper wrapper) {
        return wrapper.read(BedrockTypes.STRING);
    }

    public static String readJson(final ByteBuf buffer) {
        return BedrockTypes.STRING.read(buffer);
    }

    public static void writeJson(final ByteBuf buffer, final String json) {
        BedrockTypes.STRING.write(buffer, json != null ? json : "{}");
    }

    public static Event parse(final String json) {
        final String raw = json != null ? json : "{}";
        try {
            final JsonElement element = JsonParser.parseString(raw);
            if (!element.isJsonObject()) {
                return new Event(raw, "", new JsonObject());
            }
            final JsonObject object = element.getAsJsonObject();
            final String eventName = object.has("eventName") && object.get("eventName").isJsonPrimitive()
                    ? object.get("eventName").getAsString()
                    : "";
            return new Event(raw, eventName, object);
        } catch (final RuntimeException ignored) {
            return new Event(raw, "", new JsonObject());
        }
    }

    public static Float readGravity(final Event event) {
        if (event == null || event.object() == null || !event.object().has("gravity")) {
            return null;
        }
        final JsonElement gravity = event.object().get("gravity");
        if (gravity == null || !gravity.isJsonPrimitive() || !gravity.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return gravity.getAsFloat();
    }
}
