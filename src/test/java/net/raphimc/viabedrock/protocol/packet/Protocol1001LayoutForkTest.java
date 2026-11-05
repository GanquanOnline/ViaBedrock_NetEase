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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.types.inventory.InventorySourcePacketType;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.LevelSoundEventNames;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol1001LayoutForkTest {

    @Test
    void deviationsOfTheFirstForwardWindowArePinned() {
        assertEquals(11, BedrockWire.deviations(1001).size());
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.ITEM_STACK_DESCRIPTOR));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.INVENTORY_SOURCE_PRESENCE_PAIRS));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.START_GAME_EDITOR_FIELDS));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.BOSS_EVENT_UNIFIED_LAYOUT));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.BLOB_STATUS_INTERLEAVED_HASHES));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.SUB_CHUNK_REQUEST_OFFSETS_FIRST));
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.LEVEL_SOUND_EVENT_NAME));
        assertTrue(BedrockWire.deviations(975).isEmpty());
    }

    @Test
    void rangesEndWhereTheNextVersionRedefinedTheField() {
        assertTrue(BedrockWire.applies(1000, BedrockDeviation.ITEM_STACK_DESCRIPTOR) == false);
        assertTrue(BedrockWire.applies(2167, BedrockDeviation.INVENTORY_CONSTANT_BOOLEANS));
        assertFalse(BedrockWire.applies(2192, BedrockDeviation.INVENTORY_CONSTANT_BOOLEANS));
        assertFalse(BedrockWire.applies(2192, BedrockDeviation.INVENTORY_SOURCE_PRESENCE_PAIRS));
        assertTrue(BedrockWire.applies(2167, BedrockDeviation.INVENTORY_SOURCE_PRESENCE_PAIRS));
        assertTrue(BedrockWire.applies(2167, BedrockDeviation.START_GAME_LOGGING_CHAT));
        assertFalse(BedrockWire.applies(2168, BedrockDeviation.START_GAME_LOGGING_CHAT));
        assertTrue(BedrockWire.applies(3000, BedrockDeviation.LEVEL_SOUND_EVENT_NAME));
    }

    @Test
    void inventoryTransactionShapesSplitAtOneThousandOne() {
        assertFalse(InventoryTransactionLayout.usesLegacySlotFlag(975));
        assertTrue(InventoryTransactionLayout.usesLegacySlotFlag(1001));
        assertFalse(InventoryTransactionLayout.usesConstantBooleans(1000));
        assertTrue(InventoryTransactionLayout.usesConstantBooleans(1001));
        assertFalse(InventoryTransactionLayout.usesConstantBooleans(2192));
        assertTrue(InventoryTransactionLayout.usesSourcePresencePairs(1001));
        assertFalse(InventoryTransactionLayout.usesSourcePresencePairs(975));
        assertFalse(InventoryTransactionLayout.usesSourcePresencePairs(2192));
        assertFalse(InventoryTransactionLayout.usesByteEnums(975));
        assertTrue(InventoryTransactionLayout.usesByteEnums(1001));
        assertFalse(InventoryTransactionLayout.usesEmbeddedInteractionShape(1001));
        assertTrue(InventoryTransactionLayout.usesEmbeddedInteractionShape(2168));
    }

    @Test
    void byteEnumsRoundTripOnBothSidesOfTheFork() {
        final ByteBuf buffer = Unpooled.buffer();
        InventoryTransactionLayout.writeActionType(buffer, 4, 975);
        InventoryTransactionLayout.writeUnsignedEnum(buffer, 3, 975);
        InventoryTransactionLayout.writeFace(buffer, 2, 975);
        assertEquals(3, buffer.readableBytes());
        assertEquals(4, InventoryTransactionLayout.readActionType(buffer, 975));
        assertEquals(3, InventoryTransactionLayout.readUnsignedEnum(buffer, 975));
        assertEquals(2, InventoryTransactionLayout.readFace(buffer, 975));
        assertEquals(0, buffer.readableBytes());

        InventoryTransactionLayout.writeActionType(buffer, 4, 1001);
        InventoryTransactionLayout.writeUnsignedEnum(buffer, 3, 1001);
        InventoryTransactionLayout.writeFace(buffer, 2, 1001);
        assertEquals(3, buffer.readableBytes());
        assertEquals(4, InventoryTransactionLayout.readActionType(buffer, 1001));
        assertEquals(3, InventoryTransactionLayout.readUnsignedEnum(buffer, 1001));
        assertEquals(2, InventoryTransactionLayout.readFace(buffer, 1001));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void sourcePresencePairsRoundTripWithoutLeftoverBytes() {
        final ByteBuf buffer = Unpooled.buffer();
        final InventorySource container = new InventorySource(InventorySourceType.ContainerInventory, 12,
                InventorySource_InventorySourceFlags.NoFlag);
        InventorySourcePacketType.write(buffer, container, true);
        final InventorySource readContainer = InventorySourcePacketType.read(buffer, true);
        assertEquals(InventorySourceType.ContainerInventory, readContainer.type());
        assertEquals(12, readContainer.containerId());
        assertEquals(0, buffer.readableBytes());

        final InventorySource world = new InventorySource(InventorySourceType.WorldInteraction,
                ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag);
        InventorySourcePacketType.write(buffer, world, true);
        final InventorySource readWorld = InventorySourcePacketType.read(buffer, true);
        assertEquals(InventorySourceType.WorldInteraction, readWorld.type());
        assertEquals(InventorySource_InventorySourceFlags.NoFlag, readWorld.flags());
        assertEquals(0, buffer.readableBytes());

        final InventorySource creative = new InventorySource(InventorySourceType.CreativeInventory,
                ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag);
        InventorySourcePacketType.write(buffer, creative, true);
        final InventorySource readCreative = InventorySourcePacketType.read(buffer, true);
        assertEquals(InventorySourceType.CreativeInventory, readCreative.type());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void legacySourceShapeStaysUnchanged() {
        final ByteBuf buffer = Unpooled.buffer();
        final InventorySource container = new InventorySource(InventorySourceType.ContainerInventory, -1,
                InventorySource_InventorySourceFlags.NoFlag);
        InventorySourcePacketType.write(buffer, container, false);
        final InventorySource read = InventorySourcePacketType.read(buffer, false);
        assertEquals(-1, read.containerId());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void levelSoundEventNamesCoverTheMotTable() {
        assertEquals(Integer.valueOf(0), LevelSoundEventNames.id("item.use.on"));
        assertEquals(Integer.valueOf(1), LevelSoundEventNames.id("hit"));
        assertEquals("step", LevelSoundEventNames.name(2));
        assertEquals(1, LevelSoundEventNames.requireId("hit"));
        assertEquals(567, LevelSoundEventNames.size());
        assertNull(LevelSoundEventNames.id("no.such.level_sound_event"));
        assertNull(LevelSoundEventNames.name(-1));
        assertThrows(IllegalStateException.class, () -> LevelSoundEventNames.requireId("no.such.level_sound_event"));
        assertThrows(IllegalStateException.class, () -> LevelSoundEventNames.requireName(-1));
    }

}
