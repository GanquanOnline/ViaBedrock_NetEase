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
package net.raphimc.viabedrock.experimental.inventory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAuthInventoryModuleTest {

    @Test
    void simulationFailureTriggersAuthoritativeRollbackPath() {
        final RuntimeException failure = new IllegalStateException("broken item hash");
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        final Object result = ClientAuthInventoryModule.runOrRollback(() -> {
            throw failure;
        }, handled::set);

        assertNull(result);
        assertSame(failure, handled.get());
    }

    @Test
    void successfulSimulationPassesThroughUntouched() {
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        assertEquals("actions", ClientAuthInventoryModule.runOrRollback(() -> "actions", handled::set));
        assertNull(handled.get());
    }

    @Test
    void unsupportedSimulationPreservesAuthoritativeRollbackSignal() {
        final AtomicReference<RuntimeException> handled = new AtomicReference<>();

        assertNull(ClientAuthInventoryModule.runOrRollback(() -> null, handled::set));
        assertNull(handled.get());
    }

    @Test
    void playerInventoryMutationsOpenBedrockInventoryOnlyUntilAcknowledged() {
        assertTrue(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), false));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), true));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(4, false));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), false, true));
    }

    @Test
    void javaHotbarDropNeedsOpenThenCloseHandshake() {
        // Interact.OpenInventory is still required for MOT SAI Drop/Swap, then
        // closeTransientBedrockPlayerInventory must run so inventoryOpen does not stick.
        assertTrue(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), false));
        assertFalse(ClientAuthInventoryModule.needsBedrockPlayerInventoryOpen(
                ContainerID.CONTAINER_ID_INVENTORY.getValue(), true));
    }

    @Test
    void creativeDestinationsKeepJavaSlotSemantics() {
        final ClientAuthInventoryModule.CreativeDestination cursor = ClientAuthInventoryModule.resolveCreativeDestination(
                new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CursorContainer, 0, 0));
        assertEquals(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), cursor.containerId());
        assertEquals(0, cursor.slot());
        assertEquals(CreativeSlotSemantics.JAVA_CURSOR_SLOT, cursor.javaSlot());

        final ClientAuthInventoryModule.CreativeDestination hotbar = ClientAuthInventoryModule.resolveCreativeDestination(
                new ItemStackRequestLayout.SlotInfo(ContainerEnumName.HotbarContainer, 3, 0));
        assertEquals(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbar.containerId());
        assertEquals(3, hotbar.slot());
        assertEquals(39, hotbar.javaSlot());

        final ClientAuthInventoryModule.CreativeDestination armor = ClientAuthInventoryModule.resolveCreativeDestination(
                new ItemStackRequestLayout.SlotInfo(ContainerEnumName.ArmorContainer, 1, 0));
        assertEquals(ContainerID.CONTAINER_ID_ARMOR.getValue(), armor.containerId());
        assertEquals(1, armor.slot());
        assertEquals(6, armor.javaSlot());

        final ClientAuthInventoryModule.CreativeDestination offhand = ClientAuthInventoryModule.resolveCreativeDestination(
                new ItemStackRequestLayout.SlotInfo(ContainerEnumName.OffhandContainer, 0, 0));
        assertEquals(ContainerID.CONTAINER_ID_OFFHAND.getValue(), offhand.containerId());
        assertEquals(45, offhand.javaSlot());
    }

    @Test
    void encodeCreativeEmptyHotbarWritesDestroy() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final InventoryTracker tracker = new InventoryTracker(user);
            user.put(tracker);
            final BedrockItem wool = new BedrockItem(35, (short) 0, (byte) 16);
            tracker.getInventoryContainer().setItemSilent(0, wool.copy());
            final CreativeSlotSemantics.Plan destroy = CreativeSlotSemantics.plan(
                    36, com.viaversion.viaversion.api.minecraft.item.StructuredItem.empty(), tracker, null, null);
            assertEquals(CreativeSlotSemantics.Kind.DESTROY, destroy.kind());

            final ItemStackRequestEncoder.EncodedRequest encoded = ClientAuthInventoryModule.encodeCreativePlan(destroy, tracker);
            assertFalse(encoded.unsupported());
            assertFalse(encoded.isEmpty());

            final ByteBuf buffer = Unpooled.wrappedBuffer(encoded.payload());
            try {
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                BedrockTypes.VAR_INT.read(buffer);
                assertEquals(1, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
                assertEquals(ItemStackRequestActionType.Destroy.getValue(), buffer.readUnsignedByte());
                assertEquals(16, buffer.readUnsignedByte());
                final ItemStackRequestLayout.DecodedSlotInfo source = ItemStackRequestLayout.readSlotInfo(buffer, true, 860);
                assertEquals(ContainerEnumName.HotbarContainer, source.container());
                assertEquals(0, source.slot());
                ItemStackRequestLayout.readRequestTrailer(buffer, true, 860);
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
