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
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 消费 {@code waterdog:player_identity_v1}，使 Java 玩家列表能把每个
 * 远程玩家标记为 JE/BE 及版本。MOT PlayerList 没有这样的字段；
 * 必须由 Waterdog/ViaProxyAuth-WDPE 广播登录链身份。
 */
public final class PlayerIdentityPackets {

    static final String MESSAGE_ID = "waterdog:player_identity_v1";
    static final int MAX_PAYLOAD_LENGTH = 262_144;
    static final int MAX_ENTRIES = 4_096;
    static final int MAX_VERSION_LENGTH = 32;

    private PlayerIdentityPackets() {
    }

    static boolean isIdentityMessage(final String messageId) {
        return MESSAGE_ID.equals(messageId);
    }

    static void handle(final PacketWrapper wrapper, final String payload) {
        final Map<UUID, PlayerIdentity> identities;
        try {
            identities = parseSnapshot(payload);
        } catch (RuntimeException e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Ignoring invalid player identity snapshot");
            return;
        }

        final PlayerListStorage playerList = wrapper.user().get(PlayerListStorage.class);
        if (playerList == null) {
            return;
        }
        final Map<UUID, PlayerIdentity> updates = playerList.replaceIdentities(identities);
        if (updates.isEmpty() || wrapper.user().getProtocolInfo().getServerState() != State.PLAY) {
            return;
        }
        ExperimentalFeatures.dispatchPlayerIdentitiesUpdated(wrapper.user(), updates);
    }

    static Map<UUID, PlayerIdentity> parseSnapshot(final String payload) {
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Player identity snapshot is too large");
        }

        final JsonObject object = GsonUtil.getGson().fromJson(payload, JsonObject.class);
        if (object == null || object.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid player identity snapshot structure");
        }

        final Map<UUID, PlayerIdentity> identities = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            final UUID uuid;
            try {
                uuid = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!uuid.toString().equals(entry.getKey())) {
                continue;
            }
            final PlayerIdentity identity = parseIdentity(entry.getValue());
            if (identity != null) {
                identities.put(uuid, identity);
            }
        }
        return identities;
    }

    private static PlayerIdentity parseIdentity(final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        final String edition = stringField(object, "edition");
        final String platform = stringField(object, "platform");
        final boolean javaEdition = isJavaEdition(edition, platform);
        String version = stringField(object, "version");
        if (version.isEmpty()) {
            version = stringField(object, "java_version");
        }
        if (version.isEmpty()) {
            version = stringField(object, "engineVersion");
        }
        if (version.length() > MAX_VERSION_LENGTH) {
            version = version.substring(0, MAX_VERSION_LENGTH);
        }
        return javaEdition ? PlayerIdentity.javaEdition(version) : PlayerIdentity.bedrock(version);
    }

    private static boolean isJavaEdition(final String edition, final String platform) {
        if ("je".equalsIgnoreCase(edition) || "java".equalsIgnoreCase(edition)) {
            return true;
        }
        if ("be".equalsIgnoreCase(edition) || "bedrock".equalsIgnoreCase(edition)) {
            return false;
        }
        return "pc_java".equalsIgnoreCase(platform);
    }

    private static String stringField(final JsonObject object, final String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
