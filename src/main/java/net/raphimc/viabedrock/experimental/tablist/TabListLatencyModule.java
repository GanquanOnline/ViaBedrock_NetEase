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
package net.raphimc.viabedrock.experimental.tablist;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TabListLatencyModule implements FeatureModule {

    static final String NPC_NAME = "NPC";

    @Override
    public boolean isPlayerListEntryListed(final UserConnection user, final UUID uuid, final long entityUniqueId, final String name) {
        return !isNpcName(name);
    }

    @Override
    public Tag decoratePlayerListDisplayName(final UserConnection user, final UUID uuid, final long entityUniqueId, final String name, final int latency, final Tag displayName) {
        if (isNpcName(name)) {
            return displayName;
        }
        return TextUtil.stringToNbt(formatDisplayName(name, latency, identityFor(user, uuid, entityUniqueId)));
    }

    @Override
    public void onPlayerLatenciesUpdated(final UserConnection user, final Map<UUID, Integer> latencies) {
        if (user.getProtocolInfo().getServerState() != State.PLAY) {
            return;
        }

        final Map<UUID, String> displayNames = displayNamesFor(user, user.get(PlayerListStorage.class), latencies);
        if (displayNames.isEmpty()) {
            return;
        }

        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME)); // 动作
        playerInfoUpdate.write(Types.VAR_INT, displayNames.size()); // 长度
        for (Map.Entry<UUID, String> entry : displayNames.entrySet()) {
            playerInfoUpdate.write(Types.UUID, entry.getKey()); // UUID
            playerInfoUpdate.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(entry.getValue())); // 显示名称
        }
        playerInfoUpdate.send(BedrockProtocol.class);
    }

    @Override
    public void onPlayerIdentitiesUpdated(final UserConnection user, final Map<UUID, PlayerIdentity> identities) {
        if (user.getProtocolInfo().getServerState() != State.PLAY || identities == null || identities.isEmpty()) {
            return;
        }

        final PlayerListStorage playerList = user.get(PlayerListStorage.class);
        final Map<UUID, Integer> latencies = new LinkedHashMap<>();
        for (UUID uuid : identities.keySet()) {
            final Pair<Long, String> player = playerList != null ? playerList.getPlayer(uuid) : null;
            if (player == null || isNpcName(player.value())) {
                continue;
            }
            latencies.put(uuid, playerList.serverLatency(uuid));
        }
        if (latencies.isEmpty()) {
            return;
        }
        onPlayerLatenciesUpdated(user, latencies);
    }

    static Map<UUID, String> displayNamesFor(final PlayerListStorage playerList, final Map<UUID, Integer> latencies) {
        return displayNamesFor(null, playerList, latencies);
    }

    static Map<UUID, String> displayNamesFor(final UserConnection user, final PlayerListStorage playerList, final Map<UUID, Integer> latencies) {
        final Map<UUID, String> displayNames = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : latencies.entrySet()) {
            final Pair<Long, String> player = playerList.getPlayer(entry.getKey());
            if (player == null || isNpcName(player.value())) {
                continue;
            }
            displayNames.put(entry.getKey(), formatDisplayName(player.value(), entry.getValue(), identityFor(user, playerList, entry.getKey(), player.key())));
        }
        return displayNames;
    }

    static boolean isNpcName(final String name) {
        return NPC_NAME.equals(name);
    }

    static String formatDisplayName(final String name, final int latency) {
        return formatDisplayName(name, latency, null);
    }

    static String formatDisplayName(final String name, final int latency, final PlayerIdentity identity) {
        final StringBuilder builder = new StringBuilder();
        if (identity != null) {
            builder.append(identity.javaEdition() ? "\u00A7b" : "\u00A7a")
                    .append(identity.prefix()).append("\u00A7r ");
        }
        builder.append(name);
        if (latency < 0) {
            return builder.toString();
        }

        final String color;
        if (latency < 150) {
            color = "\u00A7a";
        } else if (latency < 300) {
            color = "\u00A7e";
        } else if (latency < 600) {
            color = "\u00A76";
        } else {
            color = "\u00A7c";
        }
        builder.append(" \u00A77[").append(color).append(latency).append("ms\u00A77]");
        return builder.toString();
    }

    static PlayerIdentity identityFor(final UserConnection user, final UUID uuid, final long entityUniqueId) {
        return identityFor(user, user != null ? user.get(PlayerListStorage.class) : null, uuid, entityUniqueId);
    }

    static PlayerIdentity identityFor(final UserConnection user, final PlayerListStorage playerList, final UUID uuid, final long entityUniqueId) {
        if (isLocalPlayer(user, uuid, entityUniqueId)) {
            return PlayerIdentity.javaEdition(PlayerIdentity.javaVersionName(user));
        }
        if (playerList != null) {
            return playerList.identity(uuid);
        }
        return null;
    }

    static boolean isLocalPlayer(final UserConnection user, final UUID uuid, final long entityUniqueId) {
        if (user == null) {
            return false;
        }
        final net.raphimc.viabedrock.protocol.storage.EntityTracker tracker = user.get(net.raphimc.viabedrock.protocol.storage.EntityTracker.class);
        if (tracker == null || tracker.getClientPlayer() == null) {
            return false;
        }
        return tracker.getClientPlayer().uniqueId() == entityUniqueId
                || tracker.getClientPlayer().javaUuid().equals(uuid);
    }

}
