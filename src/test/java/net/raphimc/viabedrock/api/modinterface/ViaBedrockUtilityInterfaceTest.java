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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ViaBedrockUtilityInterfaceTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private static final UUID PLAYER_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void forwardsEveryPlayerControllerFieldWithoutChangingUnicodeOrEmptyValues() throws Exception {
        enablePlayerAnimations();
        final PacketWrapper packet = packet();
        ViaBedrockUtilityInterface.writePlayerAnimation(packet, PLAYER_UUID, "animation.剧情", "", "query.is_sneaking",
                0x01020304, "", 0.25F);

        final ByteBuf buffer = serialize(packet);
        try {
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(buffer));
            assertEquals(11, buffer.readInt());
            assertEquals(PLAYER_UUID, Types.UUID.read(buffer));
            assertEquals("animation.剧情", readString(buffer));
            assertEquals("", readString(buffer));
            assertEquals("query.is_sneaking", readString(buffer));
            assertEquals(0x01020304, buffer.readInt());
            assertEquals("", readString(buffer));
            assertEquals(0.25F, buffer.readFloat());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void preservesLegacyWireFormatWhenPlayerCapabilityWasNotAdvertised() throws Exception {
        final ChannelStorage channels = new ChannelStorage();
        channels.addChannels(List.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL,
                ViaBedrockUtilityInterface.PLAYER_VISUAL_STATE_V1_CAPABILITY));
        this.user.put(channels);
        final PacketWrapper packet = packet();
        ViaBedrockUtilityInterface.writePlayerAnimation(packet, PLAYER_UUID, "animation.humanoid.base_pose",
                "default", "", 1, "__runtime_controller", 0F);
        assertLegacyAnimation(packet, "animation.humanoid.base_pose");
    }

    @Test
    void customEntityAnimationKeepsLegacyPayloadEvenForCapableClients() throws Exception {
        enablePlayerAnimations();
        final PacketWrapper packet = packet();
        ViaBedrockUtilityInterface.writeAnimateEntity(packet, PLAYER_UUID, "animation.custom.walk");
        assertLegacyAnimation(packet, "animation.custom.walk");
    }

    @Test
    void rejectsCombinedPayloadOverflowBeforeWritingAnyFields() {
        enablePlayerAnimations();
        final PacketWrapper packet = packet();
        final String largeField = "a".repeat(ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE / 2);
        assertThrows(IllegalArgumentException.class, () -> ViaBedrockUtilityInterface.writePlayerAnimation(
                packet, PLAYER_UUID, largeField, "default", largeField, 1, "controller", 0F));
        assertFalse(packet.is(Types.INT, 0));
    }

    @Test
    void rejectsInvalidBlendDurationBeforeWritingAnyFields() {
        enablePlayerAnimations();
        for (final float blendOutTime : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.25F}) {
            final PacketWrapper packet = packet();
            assertThrows(IllegalArgumentException.class, () -> ViaBedrockUtilityInterface.writePlayerAnimation(
                    packet, PLAYER_UUID, "animation.test", "default", "", 1, "controller", blendOutTime));
            assertFalse(packet.is(Types.INT, 0));
        }
    }

    private void enablePlayerAnimations() {
        final ChannelStorage channels = new ChannelStorage();
        channels.addChannels(List.of(ViaBedrockUtilityInterface.PLAYER_ANIMATION_V1_CAPABILITY));
        this.user.put(channels);
    }

    private PacketWrapper packet() {
        final PacketWrapper packet = new PacketWrapperImpl(ClientboundPackets26_1.CUSTOM_PAYLOAD, Unpooled.EMPTY_BUFFER, this.user);
        packet.setId(-1);
        return packet;
    }

    private static ByteBuf serialize(final PacketWrapper packet) throws Exception {
        final ByteBuf buffer = Unpooled.buffer();
        packet.writeToBuffer(buffer);
        return buffer;
    }

    private static String readString(final ByteBuf buffer) {
        return buffer.readCharSequence(buffer.readInt(), StandardCharsets.UTF_8).toString();
    }

    private static void assertLegacyAnimation(final PacketWrapper packet, final String animationName) throws Exception {
        final ByteBuf buffer = serialize(packet);
        try {
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(buffer));
            assertEquals(2, buffer.readInt());
            assertEquals(PLAYER_UUID, Types.UUID.read(buffer));
            assertEquals(animationName, readString(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void skinPayloadChunkSizesIncludeTheirCompleteHeaders() {
        assertEquals(24, ViaBedrockUtilityInterface.SKIN_DATA_HEADER_SIZE);
        assertEquals(28, ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE);
        assertEquals(
                ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE,
                ViaBedrockUtilityInterface.SKIN_DATA_HEADER_SIZE + ViaBedrockUtilityInterface.SKIN_DATA_CHUNK_SIZE
        );
        assertEquals(
                ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE,
                ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE + ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE
        );
    }

    @Test
    void splits512SquareRgbaAnimationWithoutExceedingPayloadLimit() {
        final int animationBytes = 512 * 512 * 4;
        final int chunkCount = (int) Math.ceil(animationBytes / (double) ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE);
        final int firstPayloadSize = ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE
                + Math.min(animationBytes, ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE);

        assertEquals(2, chunkCount);
        assertEquals(ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE, firstPayloadSize);
    }

}
