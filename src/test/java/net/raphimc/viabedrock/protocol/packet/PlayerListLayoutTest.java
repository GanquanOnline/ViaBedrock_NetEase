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
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerListPacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.test.ViaTestBootstrap;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListLayoutTest {

    @Test
    void netease860AddTrailerMatchesMotBloomLayout() {
        assertTrue(PlayerListLayout.usesNetEaseAddTrailer(true, 860));
        assertFalse(PlayerListLayout.usesNetEaseAddTrailer(false, 860));
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerListLayout.writeNetEaseAddTrailer(buffer, 1, true, 860);
            PlayerListLayout.skipNetEaseAddTrailer(buffer, 1, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void legacyRemovalReadsUuidsAfterTheEntryCount() {
        ViaTestBootstrap.installDefaultManager();
        final UUID first = UUID.fromString("4e6c903a-dda0-4435-bd47-429bb00e0326");
        final UUID second = UUID.fromString("b1e4a4f6-3d0f-4a34-9b4f-2c0ed5a4e1a1");
        final ByteBuf payload = Unpooled.buffer();
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            // MOT 旧布局：动作字节 + 条目数 + 每个条目一个 UUID
            payload.writeByte(PlayerListPacketType.Remove.getValue());
            BedrockTypes.UNSIGNED_VAR_INT.write(payload, 2);
            BedrockTypes.UUID.write(payload, first);
            BedrockTypes.UUID.write(payload, second);

            final UserConnection user = new UserConnectionImpl(channel);
            final PacketWrapper wrapper = new PacketWrapperImpl(ClientboundBedrockPackets.PLAYER_LIST, payload, user);
            assertEquals(PlayerListPacketType.Remove.getValue(), wrapper.read(Types.BYTE).intValue());
            final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            assertEquals(2, length);

            // 条目数只写在包首，UUID_ARRAY 自带条目数前缀，再交给它读会把 UUID 首字节当成条目数
            assertArrayEquals(new UUID[]{first, second}, PlayerListLayout.readRemovalUuids(wrapper, length));
            assertEquals(0, payload.readableBytes(), "UUID 列表后不应该还有未读字节");
        } finally {
            if (payload.refCnt() > 0) {
                payload.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void officialPlayerListHasNoBloomTrailer() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PlayerListLayout.writeNetEaseAddTrailer(buffer, 2, false, 975);
            assertFalse(buffer.isReadable());
            PlayerListLayout.skipNetEaseAddTrailer(buffer, 2, false, 975);
        } finally {
            buffer.release();
        }
    }
}
