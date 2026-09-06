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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.pyrpc;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowModEventCodecTest {

    @Test
    void decodesBinaryStringUpdateWithSignedEntityId() throws IOException {
        byte[] payload;
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packArrayHeader(3);
            packer.packString("ModEventS2C");
            packer.packArrayHeader(4);
            packer.packBinaryHeader("ECNukkitClientMod".length());
            packer.writePayload("ECNukkitClientMod".getBytes(StandardCharsets.UTF_8));
            packer.packString("ECNukkitServerSystem");
            packer.packString("RequestEntityGlowUpdate");
            packer.packMapHeader(7);
            packer.packString("schema");
            packer.packInt(1);
            packer.packString("entity_id");
            packer.packString("-123");
            packer.packString("enabled");
            packer.packBoolean(true);
            packer.packString("red");
            packer.packInt(255);
            packer.packString("green");
            packer.packInt(80);
            packer.packString("blue");
            packer.packInt(160);
            packer.packString("revision");
            packer.packLong(4L);
            packer.packNil();
            payload = packer.toByteArray();
        }

        GlowModEventCodec.Message decoded = GlowModEventCodec.decode(payload).orElseThrow();
        GlowModEventCodec.Update update = assertInstanceOf(GlowModEventCodec.Update.class, decoded);
        assertEquals("-123", update.entityId());
        assertEquals(255, update.red());
        assertEquals(160, update.blue());
        assertEquals(4L, update.revision());
    }

    @Test
    void rejectsDuplicateSyncEntriesAndTrailingBytes() throws IOException {
        assertFalse(GlowModEventCodec.decode(new byte[]{0x01, 0x02}).isPresent());
        assertTrue(GlowModEventCodec.decode(packSync(List.of("1", "1"))).isEmpty());
    }

    private static byte[] packSync(List<String> entityIds) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packArrayHeader(3);
            packer.packString("ModEventS2C");
            packer.packArrayHeader(4);
            packer.packString("ECNukkitClientMod");
            packer.packString("ECNukkitServerSystem");
            packer.packString("RequestEntityGlowSync");
            packer.packMapHeader(4);
            packer.packString("schema");
            packer.packInt(1);
            packer.packString("replace");
            packer.packBoolean(true);
            packer.packString("revision");
            packer.packLong(1L);
            packer.packString("entries");
            packer.packArrayHeader(entityIds.size());
            for (String entityId : entityIds) {
                packer.packMapHeader(6);
                packer.packString("schema");
                packer.packInt(1);
                packer.packString("entity_id");
                packer.packString(entityId);
                packer.packString("enabled");
                packer.packBoolean(true);
                packer.packString("red");
                packer.packInt(255);
                packer.packString("green");
                packer.packInt(0);
                packer.packString("blue");
                packer.packInt(0);
            }
            packer.packNil();
            return packer.toByteArray();
        }
    }
}
