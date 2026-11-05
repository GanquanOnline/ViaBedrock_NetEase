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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;

import java.util.UUID;
import java.util.logging.Level;

/**
 * 网易标题专属数据包 203 / 228 / 236 的翻译器。
 * <p>
 * 这些 ID 位于官方 {@code TitleSpecificPackets} 范围（200-299），
 * 由 MOT {@code packetPoolCurrentNetEase} 注册。没有处理器时
 * 它们会以 {@code Received unknown packet 228} 出现并被丢弃，
 * 于是其他玩家永远拿不到 Java 皮肤。PY_RPC（200）由
 * {@code PyRpcDispatcherModule} 负责，不在此注册。
 */
public final class NeteasePackets {

    private NeteasePackets() {
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CONFIRM_SKIN, null, wrapper -> {
            wrapper.cancel();
            final java.util.List<ConfirmSkinLayout.Entry> entries = ConfirmSkinLayout.readPacket(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                return;
            }
            for (ConfirmSkinLayout.Entry entry : entries) {
                if (!entry.valid() || entry.uuid() == null) {
                    continue;
                }
                final SkinData skin = ConfirmSkinLayout.toSkinData(entry);
                if (skin.skinData() == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.FINE, "ConfirmSkin entry has no usable RGBA image uuid=" + entry.uuid()
                            + " bytes=" + (entry.skinBytes() != null ? entry.skinBytes().length : 0));
                    continue;
                }
                applySkin(wrapper.user(), entry.uuid(), skin);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SYNC_SKIN, null, wrapper -> {
            wrapper.cancel();
            final SyncSkinLayout.Packet packet = SyncSkinLayout.readPacket(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                return;
            }
            if (packet.skin() == null || packet.skin().skinData() == null) {
                return;
            }
            for (SyncSkinLayout.Entry entry : packet.entries()) {
                if (!entry.flag() || entry.uuid() == null) {
                    continue;
                }
                applySkin(wrapper.user(), entry.uuid(), packet.skin());
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.NETEASE_JSON, null, wrapper -> {
            wrapper.cancel();
            final String json = NeteaseJsonLayout.readJson(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                return;
            }
            applyNeteaseJson(wrapper.user(), json);
        });
    }

    static UUID remapSkinUuid(final UserConnection user, final UUID uuid) {
        final EntityTracker tracker = user.get(EntityTracker.class);
        if (tracker == null || uuid == null) {
            return uuid;
        }
        final ClientPlayerEntity clientPlayer = tracker.getClientPlayer();
        if (clientPlayer != null && uuid.equals(clientPlayer.bedrockUuid())) {
            return clientPlayer.javaUuid();
        }
        return uuid;
    }

    static void applySkin(final UserConnection user, final UUID bedrockUuid, final SkinData skin) {
        final UUID targetUuid = remapSkinUuid(user, bedrockUuid);
        Via.getManager().getProviders().get(SkinProvider.class).setSkin(user, targetUuid, skin);
    }

    static void applyNeteaseJson(final UserConnection user, final String json) {
        final NeteaseJsonLayout.Event event = NeteaseJsonLayout.parse(json);
        if (!event.hasEventName()) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "NeteaseJson without eventName: " + event.rawJson());
            return;
        }
        switch (event.eventName()) {
            case NeteaseJsonLayout.EVENT_SET_LEVEL_GRAVITY -> {
                final Float gravity = NeteaseJsonLayout.readGravity(event);
                if (gravity == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.FINE, "SET_LEVEL_GRAVITY missing numeric gravity field");
                    return;
                }
                final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
                if (gameSession != null) {
                    gameSession.setNeteaseLevelGravity(gravity);
                }
            }
            default -> ViaBedrock.getPlatform().getLogger().log(Level.FINE,
                    "Ignoring NeteaseJson event " + event.eventName() + " (no MOT/Master payload schema)");
        }
    }
}
