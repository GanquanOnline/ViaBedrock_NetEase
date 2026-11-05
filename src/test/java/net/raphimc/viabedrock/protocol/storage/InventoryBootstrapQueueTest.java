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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryBootstrapQueueTest {

    @BeforeAll
    static void installViaManager() {
        net.raphimc.viabedrock.test.ViaTestBootstrap.installDefaultManager();
    }

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final List<ClientboundBedrockPackets> replayedPackets = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> debugMessages = new ArrayList<>();

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void waitsForPlayAndRegistryThenPreservesInventoryOrder() {
        final InventoryBootstrapQueue queue = this.newQueue(8, 1024);
        final ByteBuf content = payload(1);
        final ByteBuf slot = payload(2);
        final ByteBuf hotbar = payload(3);

        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_CONTENT, content, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, slot, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.PLAYER_HOTBAR, hotbar, State.CONFIGURATION);
        queue.onItemRegistryReady();
        this.channel.runPendingTasks();
        assertTrue(this.replayedPackets.isEmpty());

        queue.onPlayReady();
        this.channel.runPendingTasks();

        assertEquals(List.of(
                ClientboundBedrockPackets.INVENTORY_CONTENT,
                ClientboundBedrockPackets.INVENTORY_SLOT,
                ClientboundBedrockPackets.PLAYER_HOTBAR
        ), this.replayedPackets);
        assertEquals(0, content.refCnt());
        assertEquals(0, slot.refCnt());
        assertEquals(0, hotbar.refCnt());
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void replaysEarlyRegistryBeforeInventory() {
        final AtomicReference<InventoryBootstrapQueue> queueRef = new AtomicReference<>();
        final InventoryBootstrapQueue queue = this.newQueue((packet, payload) -> {
            this.replayedPackets.add(packet);
            if (packet == ClientboundBedrockPackets.ITEM_REGISTRY) {
                queueRef.get().onItemRegistryReady();
            }
        }, 8, 1024);
        queueRef.set(queue);
        final ByteBuf inventory = payload(1);
        final ByteBuf registry = payload(2);

        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, inventory, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.ITEM_REGISTRY, registry, State.CONFIGURATION);
        queue.onPlayReady();
        this.channel.runPendingTasks();
        this.channel.runPendingTasks();

        assertEquals(List.of(ClientboundBedrockPackets.ITEM_REGISTRY, ClientboundBedrockPackets.INVENTORY_SLOT), this.replayedPackets);
        assertEquals(0, inventory.refCnt());
        assertEquals(0, registry.refCnt());
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void rejectsDuplicateEarlyRegistryAndReleasesEverything() {
        final InventoryBootstrapQueue queue = this.newQueue(8, 1024);
        final ByteBuf inventory = payload(1);
        final ByteBuf firstRegistry = payload(2);
        final ByteBuf secondRegistry = payload(3);

        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, inventory, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.ITEM_REGISTRY, firstRegistry, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.ITEM_REGISTRY, secondRegistry, State.CONFIGURATION);

        assertEquals(1, this.failures.size());
        assertTrue(this.failures.getFirst().contains("multiple ITEM_REGISTRY"));
        assertEquals(0, inventory.refCnt());
        assertEquals(0, firstRegistry.refCnt());
        assertEquals(0, secondRegistry.refCnt());
    }

    @Test
    void enforcesPacketAndByteLimits() {
        final InventoryBootstrapQueue packetLimited = this.newQueue(1, 1024);
        final ByteBuf first = payload(1);
        final ByteBuf second = payload(2);
        packetLimited.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, first, State.CONFIGURATION);
        packetLimited.deferOwnedPayload(ClientboundBedrockPackets.PLAYER_HOTBAR, second, State.CONFIGURATION);

        assertEquals(1, this.failures.size());
        assertEquals(0, first.refCnt());
        assertEquals(0, second.refCnt());

        this.failures.clear();
        final InventoryBootstrapQueue byteLimited = this.newQueue(8, 1);
        final ByteBuf oversized = Unpooled.wrappedBuffer(new byte[]{1, 2});
        byteLimited.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, oversized, State.CONFIGURATION);

        assertEquals(1, this.failures.size());
        assertEquals(0, oversized.refCnt());
    }

    @Test
    void timesOutOnlyAfterPlayWhileWaitingForRegistry() {
        final InventoryBootstrapQueue queue = this.newQueue(8, 1024);
        final ByteBuf inventory = payload(1);
        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, inventory, State.CONFIGURATION);

        queue.onRegistryTimeout();
        assertTrue(this.failures.isEmpty());

        queue.onPlayReady();
        queue.onRegistryTimeout();

        assertEquals(1, this.failures.size());
        assertTrue(this.failures.getFirst().contains("Timed out waiting for ITEM_REGISTRY"));
        assertEquals(0, inventory.refCnt());
    }

    @Test
    void releasesQueuedPayloadsWhenRemoved() {
        final InventoryBootstrapQueue queue = this.newQueue(8, 1024);
        final ByteBuf inventory = payload(1);
        final ByteBuf registry = payload(2);
        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, inventory, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.ITEM_REGISTRY, registry, State.CONFIGURATION);

        queue.onRemove();

        assertEquals(0, inventory.refCnt());
        assertEquals(0, registry.refCnt());
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void copiesWrapperPayloadAndDefersAgainDuringReconfigure() {
        final List<Integer> replayedPayloads = new ArrayList<>();
        final InventoryBootstrapQueue queue = this.newQueue((packet, payload) -> {
            this.replayedPackets.add(packet);
            replayedPayloads.add((int) payload.readUnsignedByte());
        }, 8, 1024);
        queue.onItemRegistryReady();
        queue.onPlayReady();
        this.channel.runPendingTasks();

        final ByteBuf readyPayload = payload(7);
        final PacketWrapperImpl readyWrapper = new PacketWrapperImpl(ClientboundBedrockPackets.INVENTORY_SLOT, readyPayload, this.user);
        assertFalse(queue.deferIfNeeded(ClientboundBedrockPackets.INVENTORY_SLOT, readyWrapper, State.PLAY));
        assertEquals(1, readyPayload.refCnt());
        readyPayload.release();

        final ByteBuf reconfigurePayload = payload(9);
        final PacketWrapperImpl reconfigureWrapper = new PacketWrapperImpl(ClientboundBedrockPackets.INVENTORY_SLOT, reconfigurePayload, this.user);
        assertTrue(queue.deferIfNeeded(ClientboundBedrockPackets.INVENTORY_SLOT, reconfigureWrapper, State.CONFIGURATION));
        reconfigurePayload.setByte(0, 10);
        reconfigurePayload.release();
        this.channel.runPendingTasks();
        assertTrue(this.replayedPackets.isEmpty());

        queue.onPlayReady();
        this.channel.runPendingTasks();

        assertEquals(List.of(ClientboundBedrockPackets.INVENTORY_SLOT), this.replayedPackets);
        assertEquals(List.of(9), replayedPayloads);
    }

    @Test
    void replayFailureReleasesCurrentAndRemainingPayloads() {
        final InventoryBootstrapQueue queue = this.newQueue((packet, payload) -> {
            throw new IllegalStateException("broken replay");
        }, 8, 1024);
        final ByteBuf first = payload(1);
        final ByteBuf second = payload(2);
        queue.deferOwnedPayload(ClientboundBedrockPackets.INVENTORY_SLOT, first, State.CONFIGURATION);
        queue.deferOwnedPayload(ClientboundBedrockPackets.PLAYER_HOTBAR, second, State.CONFIGURATION);
        queue.onItemRegistryReady();
        queue.onPlayReady();

        this.channel.runPendingTasks();

        assertEquals(1, this.failures.size());
        assertEquals(0, first.refCnt());
        assertEquals(0, second.refCnt());
    }

    private InventoryBootstrapQueue newQueue(final int maxPackets, final int maxBytes) {
        return this.newQueue((packet, payload) -> this.replayedPackets.add(packet), maxPackets, maxBytes);
    }

    private InventoryBootstrapQueue newQueue(final InventoryBootstrapQueue.PacketReplayer replayer, final int maxPackets, final int maxBytes) {
        return new InventoryBootstrapQueue(
                this.user,
                replayer,
                (reason, throwable) -> this.failures.add(reason),
                this.debugMessages::add,
                maxPackets,
                maxBytes,
                10_000L
        );
    }

    private static ByteBuf payload(final int value) {
        return Unpooled.buffer(1).writeByte(value);
    }

}
