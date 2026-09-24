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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransferPacketMappingTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void keepsJavaTransferWhenReloadWorldAndGatheringsLeftoverArePresent() {
        final PacketWrapper wrapper = transferWrapper("127.0.0.1", 19132, true, new byte[]{0x01, 0x02});
        TransferPacketMapping.consumeLegacyAndLeftoverFields(wrapper);

        assertFalse(wrapper.isCancelled());
        assertEquals("127.0.0.1", wrapper.get(Types.STRING, 0));
        assertEquals(19132, wrapper.get(Types.VAR_INT, 0));
        assertEquals(0, ((PacketWrapperImpl) wrapper).getInputBuffer().readableBytes());
    }

    @Test
    void keepsJavaTransferWhenReloadWorldIsTrue() {
        final PacketWrapper wrapper = transferWrapper("lobby.internal", 19133, true, new byte[0]);
        TransferPacketMapping.consumeLegacyAndLeftoverFields(wrapper);

        assertFalse(wrapper.isCancelled());
        assertEquals("lobby.internal", wrapper.get(Types.STRING, 0));
        assertEquals(19133, wrapper.get(Types.VAR_INT, 0));
    }

    @Test
    void keepsJavaTransferWhenReloadWorldFieldIsMissing() {
        final PacketWrapper wrapper = transferWrapper("lobby.internal", 19133, false, new byte[0]);
        TransferPacketMapping.consumeLegacyAndLeftoverFields(wrapper);

        assertFalse(wrapper.isCancelled());
        assertEquals("lobby.internal", wrapper.get(Types.STRING, 0));
        assertEquals(19133, wrapper.get(Types.VAR_INT, 0));
        assertEquals(0, ((PacketWrapperImpl) wrapper).getInputBuffer().readableBytes());
    }

    private PacketWrapper transferWrapper(final String host, final int port, final boolean writeReloadWorld, final byte[] leftover) {
        final ByteBuf payload = Unpooled.buffer();
        BedrockTypes.STRING.write(payload, host);
        BedrockTypes.UNSIGNED_SHORT_LE.write(payload, Integer.valueOf(port));
        if (writeReloadWorld) {
            payload.writeBoolean(true);
        }
        payload.writeBytes(leftover);

        final PacketWrapper wrapper = new PacketWrapperImpl(ClientboundPackets26_1.TRANSFER, payload, this.user);
        wrapper.write(Types.STRING, wrapper.read(BedrockTypes.STRING));
        wrapper.write(Types.VAR_INT, wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE).intValue());
        return wrapper;
    }
}
