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
package net.raphimc.viabedrock.experimental.modinterface;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.nio.ByteBuffer;
import java.util.Collection;

public class ModUIClientInterface {

    public static final String CONFIRM_CHANNEL = "moduiclient:confirm";
    public static final String ENTITY_MAPPING_CHANNEL = "netease_bridge:entity_mapping";

    // --- Entity ID Mapping ---

    private static final byte OP_ADD = 0;
    private static final byte OP_REMOVE = 1;
    private static final byte OP_SYNC = 2;

    public static void sendEntityMappingAdd(final UserConnection user, final long runtimeId, final int javaId) {
        if (!canSendEntityMappings(user)) return;
        final byte[] data = ByteBuffer.allocate(1 + 8 + 4).put(OP_ADD).putLong(runtimeId).putInt(javaId).array();
        sendEntityMappingPayload(user, data);
    }

    public static void sendEntityMappingRemove(final UserConnection user, final long runtimeId) {
        if (!canSendEntityMappings(user)) return;
        final byte[] data = ByteBuffer.allocate(1 + 8).put(OP_REMOVE).putLong(runtimeId).array();
        sendEntityMappingPayload(user, data);
    }

    public static void sendEntityMappingSync(final UserConnection user) {
        if (!canSendEntityMappings(user)) return;
        if (!user.has(EntityTracker.class)) return;
        final Collection<Entity> entities = user.get(EntityTracker.class).getEntities();
        final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + entities.size() * (8 + 4));
        buf.put(OP_SYNC);
        buf.putInt(entities.size());
        for (final Entity entity : entities) {
            buf.putLong(entity.runtimeId());
            buf.putInt(entity.javaId());
        }
        sendEntityMappingPayload(user, buf.array());
    }

    private static boolean canSendEntityMappings(final UserConnection user) {
        final ChannelStorage channels = user.get(ChannelStorage.class);
        // The mapping channel is owned by the shared Bridge, also consumed without ModUI.
        return channels.hasChannel(ENTITY_MAPPING_CHANNEL);
    }

    private static void sendEntityMappingPayload(final UserConnection user, final byte[] data) {
        try {
            final PacketWrapper pw = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            pw.write(Types.STRING, ENTITY_MAPPING_CHANNEL);
            pw.write(Types.REMAINING_BYTES, data);
            pw.scheduleSend(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("[ModUIClient] Failed to send entity mapping: " + e.getMessage());
        }
    }

}
