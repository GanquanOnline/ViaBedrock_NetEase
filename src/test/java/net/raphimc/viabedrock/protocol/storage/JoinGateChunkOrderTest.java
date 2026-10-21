/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JoinGateChunkOrderTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final JoinGate gate = new JoinGate(this.user);

    @AfterEach
    void closeChannel() {
        this.gate.onRemove();
        this.channel.finishAndReleaseAll();
    }

    @Test
    void unloadingBeforeLoginRemainsSuppressed() throws Exception {
        assertTrue(this.gate.interceptClientbound(ClientboundPackets26_1.FORGET_LEVEL_CHUNK, this.unloadPacket(-6, 14)));
        assertTrue(this.gate.interceptClientbound(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT, this.packet(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT)));
        assertFalse(this.gate.isOpen());
    }

    @Test
    void unloadedColumnsCanBeReloadedBeforePlayerLoadedWithoutReordering() throws Exception {
        this.gate.onPlayerChunkSent();
        assertFalse(this.gate.isOpen());
        this.assertUnloadThenReloadOrder();
        this.gate.onPlayerLoaded();
        assertTrue(this.gate.isOpen());
    }

    @Test
    void unloadedColumnsCanBeReloadedAfterGateOpens() throws Exception {
        this.gate.onPlayerChunkSent();
        this.gate.onPlayerLoaded();
        assertTrue(this.gate.isOpen());
        this.assertUnloadThenReloadOrder();
    }

    @Test
    void chunkUnloadsDoNotOpenGateOrReleaseEntityPackets() throws Exception {
        this.gate.onPlayerChunkSent();
        assertFalse(this.gate.interceptClientbound(ClientboundPackets26_1.FORGET_LEVEL_CHUNK, this.unloadPacket(-6, 14)));
        assertTrue(this.gate.interceptClientbound(ClientboundPackets26_1.ADD_ENTITY, this.packet(ClientboundPackets26_1.ADD_ENTITY)));
        assertFalse(this.gate.isOpen());
    }

    private void assertUnloadThenReloadOrder() throws Exception {
        final int[][] columns = {{-7, 13}, {-6, 13}, {-5, 13}, {-6, 14}, {-5, 14}};
        final Set<Long> clientColumns = new HashSet<>();
        final List<String> delivered = new ArrayList<>();
        final List<String> expected = new ArrayList<>();
        for (int[] column : columns) {
            final int x = column[0];
            final int z = column[1];
            final long key = ChunkPosition.chunkKey(x, z);
            clientColumns.add(key);
            final String unload = "unload:" + x + "," + z;
            final String full = "full:" + x + "," + z;
            expected.add(unload);
            expected.add(full);

            if (!this.gate.interceptClientbound(ClientboundPackets26_1.FORGET_LEVEL_CHUNK, this.unloadPacket(x, z))) {
                delivered.add(unload);
                clientColumns.remove(key);
            }
            if (!this.gate.interceptClientbound(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT, this.packet(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT))) {
                delivered.add(full);
                clientColumns.add(key);
            }
        }
        assertEquals(expected, delivered, "unloads must reach the client before newer snapshots of the same columns");
        assertEquals(columns.length, clientColumns.size());
    }

    private PacketWrapper unloadPacket(final int x, final int z) {
        final PacketWrapper wrapper = this.packet(ClientboundPackets26_1.FORGET_LEVEL_CHUNK);
        wrapper.write(Types.CHUNK_POSITION, new ChunkPosition(x, z));
        return wrapper;
    }

    private PacketWrapper packet(final ClientboundPackets26_1 type) {
        return new PacketWrapperImpl(type, null, this.user);
    }
}
