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
package net.raphimc.viabedrock.api.modinterface;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.JavaPlayerStateStorage;
import net.raphimc.viabedrock.protocol.types.primitive.ImageType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ViaBedrockUtilityInterface {

    // This channel WILL ONLY be used to confirm that ViaBedrockUtility is present, the server will use viabedrockutility:data to respond
    public static final String CONFIRM_CHANNEL = "viabedrockutility:confirm";

    public static final String CHANNEL = "viabedrockutility:data";
    public static final String PLAYER_STATE_CHANNEL = "viabedrockutility:player_state";
    /** Client capability: unmapped Bedrock particle effects can use anchor-aware V2 requests. */
    public static final String PARTICLE_RUNTIME_V2_CAPABILITY = "viabedrockutility:particle_runtime_v2";
    /** Client capability: local MOT spectator can stay Java ADVENTURE while VBU forces Entity.noClip. */
    public static final String SPECTATOR_NOCLIP_CAPABILITY = "viabedrockutility:spectator_noclip";
    static final int MAX_PAYLOAD_SIZE = 1_048_576;
    static final int SKIN_DATA_HEADER_SIZE = Integer.BYTES + 2 * Long.BYTES + Integer.BYTES;
    static final int SKIN_ANIMATION_DATA_HEADER_SIZE = SKIN_DATA_HEADER_SIZE + Integer.BYTES;
    static final int SKIN_DATA_CHUNK_SIZE = MAX_PAYLOAD_SIZE - SKIN_DATA_HEADER_SIZE;
    static final int SKIN_ANIMATION_DATA_CHUNK_SIZE = MAX_PAYLOAD_SIZE - SKIN_ANIMATION_DATA_HEADER_SIZE;

    public static void confirmPresence(final UserConnection user) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL); // Channel
        pluginMessage.write(Types.INT, PayloadType.CONFIRM.ordinal()); // Type
        pluginMessage.send(BedrockProtocol.class);
    }

    public static void handlePlayerState(final UserConnection user, final byte[] payload) {
        if (user.getProtocolInfo().getClientState() == State.PLAY) {
            user.get(JavaPlayerStateStorage.class).updateFromPayload(payload);
        }
    }

    public static void spawnCustomEntity(final UserConnection user, final UUID uuid, final String identifier, final Map<ActorDataIDs, EntityData> entityData) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL); // Channel
        pluginMessage.write(Types.INT, PayloadType.MODEL_REQUEST.ordinal()); // Type

        pluginMessage.write(Types.UUID, uuid);
        writeString(pluginMessage, identifier);

        boolean writeBitmask1 = entityData.containsKey(ActorDataIDs.RESERVED_0);
        pluginMessage.write(Types.BOOLEAN, writeBitmask1);
        if (writeBitmask1) {
            pluginMessage.write(Types.LONG, entityData.get(ActorDataIDs.RESERVED_0).<Long>value());
        }
        boolean writeBitmask2 = entityData.containsKey(ActorDataIDs.RESERVED_092);
        pluginMessage.write(Types.BOOLEAN, writeBitmask2);
        if (writeBitmask2) {
            pluginMessage.write(Types.LONG, entityData.get(ActorDataIDs.RESERVED_092).<Long>value());
        }

        boolean writeVariant = entityData.containsKey(ActorDataIDs.VARIANT);
        pluginMessage.write(Types.BOOLEAN, writeVariant);
        if (writeVariant) {
            pluginMessage.write(Types.INT, entityData.get(ActorDataIDs.VARIANT).<Integer>value());
        }

        boolean writeMarkVariant = entityData.containsKey(ActorDataIDs.MARK_VARIANT);
        pluginMessage.write(Types.BOOLEAN, writeMarkVariant);
        if (writeMarkVariant) {
            pluginMessage.write(Types.INT, entityData.get(ActorDataIDs.MARK_VARIANT).<Integer>value());
        }

        boolean writeSkinId = entityData.containsKey(ActorDataIDs.SKIN_ID);
        pluginMessage.write(Types.BOOLEAN, writeSkinId);
        if (writeSkinId) {
            pluginMessage.write(Types.INT, entityData.get(ActorDataIDs.SKIN_ID).<Integer>value());
        }

        boolean writeScale = entityData.containsKey(ActorDataIDs.RESERVED_038);
        pluginMessage.write(Types.BOOLEAN, writeScale);
        if (writeScale) {
            pluginMessage.write(Types.FLOAT, entityData.get(ActorDataIDs.RESERVED_038).<Float>value());
        }

        pluginMessage.send(BedrockProtocol.class);
    }

    public static void sendSkin(final UserConnection user, final UUID uuid, final SkinData skin) {
        if (skin.skinData() == null) {
            return;
        }

        final boolean hasGeometry = !skin.geometryData().isEmpty() && !skin.geometryData().toLowerCase(Locale.ROOT).equals("null");
        final byte[] skinData = ImageType.getImageData(skin.skinData());
        final int chunkCount = (int) Math.ceil(skinData.length / (double) SKIN_DATA_CHUNK_SIZE);

        {
            final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            pluginMessage.write(Types.STRING, CHANNEL); // Channel
            pluginMessage.write(Types.INT, PayloadType.SKIN_INFORMATION.ordinal());
            pluginMessage.write(Types.UUID, uuid);
            pluginMessage.write(Types.INT, skin.skinData().getWidth());
            pluginMessage.write(Types.INT, skin.skinData().getHeight());

            writeString(pluginMessage, skin.skinResourcePatch());
            pluginMessage.write(Types.BOOLEAN, hasGeometry);
            if (hasGeometry) {
                writeString(pluginMessage, skin.geometryData());
            }

            pluginMessage.write(Types.INT, chunkCount);
            pluginMessage.scheduleSend(BedrockProtocol.class);
        }
        for (int i = 0; i < chunkCount; i++) {
            final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            pluginMessage.write(Types.STRING, CHANNEL); // Channel
            pluginMessage.write(Types.INT, PayloadType.SKIN_DATA.ordinal());
            pluginMessage.write(Types.UUID, uuid);
            pluginMessage.write(Types.INT, i);
            if (chunkCount == 1) { // Fast path
                pluginMessage.write(Types.REMAINING_BYTES, skinData);
            } else {
                pluginMessage.write(Types.REMAINING_BYTES, Arrays.copyOfRange(skinData, i * SKIN_DATA_CHUNK_SIZE, Math.min((i + 1) * SKIN_DATA_CHUNK_SIZE, skinData.length)));
            }
            pluginMessage.scheduleSend(BedrockProtocol.class);
        }
        if (skin.capeData() != null) {
            final byte[] capeData = ImageType.getImageData(skin.capeData());

            final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            pluginMessage.write(Types.STRING, CHANNEL); // Channel
            pluginMessage.write(Types.INT, PayloadType.CAPE.ordinal());
            pluginMessage.write(Types.UUID, uuid);
            pluginMessage.write(Types.INT, skin.capeData().getWidth());
            pluginMessage.write(Types.INT, skin.capeData().getHeight());
            writeString(pluginMessage, skin.capeId());
            pluginMessage.write(Types.INT, capeData.length);
            pluginMessage.write(Types.REMAINING_BYTES, capeData);
            pluginMessage.scheduleSend(BedrockProtocol.class);
        }
        if (skin.animations() != null && !skin.animations().isEmpty()) {
            for (int animIndex = 0; animIndex < skin.animations().size(); animIndex++) {
                final SkinData.AnimationData anim = skin.animations().get(animIndex);
                if (anim.image() == null) continue;

                final byte[] animData = ImageType.getImageData(anim.image());
                final int animChunkCount = (int) Math.ceil(animData.length / (double) SKIN_ANIMATION_DATA_CHUNK_SIZE);

                {
                    final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
                    pluginMessage.write(Types.STRING, CHANNEL);
                    pluginMessage.write(Types.INT, PayloadType.SKIN_ANIMATION_INFO.ordinal());
                    pluginMessage.write(Types.UUID, uuid);
                    pluginMessage.write(Types.INT, animIndex);
                    pluginMessage.write(Types.INT, anim.type());
                    pluginMessage.write(Types.FLOAT, anim.frames());
                    pluginMessage.write(Types.INT, anim.expression());
                    pluginMessage.write(Types.INT, anim.image().getWidth());
                    pluginMessage.write(Types.INT, anim.image().getHeight());
                    pluginMessage.write(Types.INT, animChunkCount);
                    pluginMessage.scheduleSend(BedrockProtocol.class);
                }
                for (int i = 0; i < animChunkCount; i++) {
                    final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
                    pluginMessage.write(Types.STRING, CHANNEL);
                    pluginMessage.write(Types.INT, PayloadType.SKIN_ANIMATION_DATA.ordinal());
                    pluginMessage.write(Types.UUID, uuid);
                    pluginMessage.write(Types.INT, animIndex);
                    pluginMessage.write(Types.INT, i);
                    if (animChunkCount == 1) {
                        pluginMessage.write(Types.REMAINING_BYTES, animData);
                    } else {
                        pluginMessage.write(Types.REMAINING_BYTES, Arrays.copyOfRange(animData, i * SKIN_ANIMATION_DATA_CHUNK_SIZE, Math.min((i + 1) * SKIN_ANIMATION_DATA_CHUNK_SIZE, animData.length)));
                    }
                    pluginMessage.scheduleSend(BedrockProtocol.class);
                }
            }
        }
    }

    private static void writeString(final PacketWrapper wrapper, final String s) {
        if (s == null) {
            throw new IllegalArgumentException("ViaBedrockUtility strings cannot be null");
        }
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("ViaBedrockUtility string exceeds " + MAX_PAYLOAD_SIZE + " bytes");
        }
        wrapper.write(Types.INT, bytes.length);
        wrapper.write(Types.REMAINING_BYTES, bytes);
    }

    enum PayloadType {
        CONFIRM, MODEL_REQUEST, ANIMATE,
        CAPE, SKIN_INFORMATION, SKIN_DATA,
        SKIN_ANIMATION_INFO, SKIN_ANIMATION_DATA,
        SPAWN_PARTICLE, SPAWN_PARTICLE_V2, SPECTATOR_NOCLIP
    }

    public static boolean hasSpectatorNoclip(final UserConnection user) {
        if (user == null) {
            return false;
        }
        final ChannelStorage channelStorage = user.get(ChannelStorage.class);
        return channelStorage != null && channelStorage.hasChannel(SPECTATOR_NOCLIP_CAPABILITY);
    }

    public static void syncSpectatorNoclip(final UserConnection user, final boolean enabled) {
        if (!hasSpectatorNoclip(user)) {
            return;
        }
        final State clientState = user.getProtocolInfo() != null ? user.getProtocolInfo().getClientState() : null;
        final PacketWrapper pluginMessage;
        if (clientState == State.PLAY) {
            pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        } else if (clientState == State.CONFIGURATION) {
            pluginMessage = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
        } else {
            return;
        }
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.SPECTATOR_NOCLIP.ordinal());
        pluginMessage.write(Types.BOOLEAN, enabled);
        pluginMessage.send(BedrockProtocol.class);
    }

    public static void spawnParticle(final UserConnection user, final String identifier, final float x, final float y, final float z) {
        spawnParticle(user, identifier, x, y, z, null);
    }

    public static void spawnParticle(final UserConnection user, final String identifier, final float x, final float y, final float z, final String molangVarsJson) {
        java.util.logging.Logger.getLogger("ViaBedrock").log(java.util.logging.Level.FINE, "[Particle:L2] Sending SPAWN_PARTICLE payload: " + identifier + " at (" + x + ", " + y + ", " + z + ") molang=" + (molangVarsJson != null));
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.SPAWN_PARTICLE.ordinal());
        writeString(pluginMessage, identifier);
        pluginMessage.write(Types.FLOAT, x);
        pluginMessage.write(Types.FLOAT, y);
        pluginMessage.write(Types.FLOAT, z);
        if (molangVarsJson != null && !molangVarsJson.isEmpty()) {
            pluginMessage.write(Types.BOOLEAN, true);
            writeString(pluginMessage, molangVarsJson);
        } else {
            pluginMessage.write(Types.BOOLEAN, false);
        }
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    /**
     * Sends a V2 particle request without reinterpreting SpawnParticleEffect coordinates. XYZ is
     * absolute for a world anchor and an entity-local offset for an entity anchor.
     */
    public static void spawnParticleV2(final UserConnection user, final String identifier,
                                       final int anchorKind, final UUID ownerUuid,
                                       final float x, final float y, final float z,
                                       final String molangVarsJson) {
        if (anchorKind < 0 || anchorKind > 1) {
            throw new IllegalArgumentException("Unsupported particle anchor kind: " + anchorKind);
        }
        if ((anchorKind == 0) != (ownerUuid == null)) {
            throw new IllegalArgumentException("Particle anchor kind and owner UUID are inconsistent");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Particle position contains a non-finite component");
        }
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.SPAWN_PARTICLE_V2.ordinal());
        writeString(pluginMessage, identifier);
        pluginMessage.write(Types.UNSIGNED_BYTE, (short) anchorKind);
        pluginMessage.write(Types.BOOLEAN, ownerUuid != null);
        if (ownerUuid != null) pluginMessage.write(Types.UUID, ownerUuid);
        pluginMessage.write(Types.FLOAT, x);
        pluginMessage.write(Types.FLOAT, y);
        pluginMessage.write(Types.FLOAT, z);
        if (molangVarsJson != null && !molangVarsJson.isEmpty()) {
            pluginMessage.write(Types.BOOLEAN, true);
            writeString(pluginMessage, molangVarsJson);
        } else {
            pluginMessage.write(Types.BOOLEAN, false);
        }
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    public static void sendAnimateEntity(final UserConnection user, final UUID uuid, final String animationName) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL); // Channel
        pluginMessage.write(Types.INT, PayloadType.ANIMATE.ordinal()); // Type
        pluginMessage.write(Types.UUID, uuid);
        writeString(pluginMessage, animationName);
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }
}
