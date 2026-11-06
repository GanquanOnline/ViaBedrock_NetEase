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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.model.SkinType;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol2192_2225LayoutForkTest {

    @Test
    void deviationBitMapStillFitsInLong() {
        assertTrue(BedrockDeviation.values().length <= BedrockDeviation.MAX_DEVIATIONS,
                "偏差集用一个 long 存放，常量数不能超过 64");
    }

    @Test
    void dimensionDataForksStartAt2192And2225() {
        assertFalse(applies(BedrockDeviation.DIMENSION_DATA_BOUNDED_HEIGHTS, 2191));
        assertTrue(applies(BedrockDeviation.DIMENSION_DATA_BOUNDED_HEIGHTS, 2192));
        assertTrue(applies(BedrockDeviation.DIMENSION_DATA_BOUNDED_HEIGHTS, 2225));

        assertFalse(applies(BedrockDeviation.DIMENSION_DATA_DEFAULT_BIOME, 2191));
        assertTrue(applies(BedrockDeviation.DIMENSION_DATA_DEFAULT_BIOME, 2192));

        assertFalse(applies(BedrockDeviation.DIMENSION_DATA_CLOUD_FIELDS, 2192));
        assertTrue(applies(BedrockDeviation.DIMENSION_DATA_CLOUD_FIELDS, 2225));
    }

    @Test
    void startGameMigrationAndChunkTailAre2225Only() {
        assertFalse(applies(BedrockDeviation.START_GAME_EDITOR_LEVEL_MIGRATION, 2192));
        assertTrue(applies(BedrockDeviation.START_GAME_EDITOR_LEVEL_MIGRATION, 2225));

        assertFalse(applies(BedrockDeviation.LEVEL_CHUNK_CLIENT_BIOME_UPDATE, 2192));
        assertTrue(applies(BedrockDeviation.LEVEL_CHUNK_CLIENT_BIOME_UPDATE, 2225));

        assertFalse(applies(BedrockDeviation.ANIMATE_HAND_BYTE, 2192));
        assertTrue(applies(BedrockDeviation.ANIMATE_HAND_BYTE, 2225));

        assertFalse(applies(BedrockDeviation.PASSENGER_OF_BLOCK_ARGUMENTS, 2192));
        assertTrue(applies(BedrockDeviation.PASSENGER_OF_BLOCK_ARGUMENTS, 2225));
    }

    @Test
    void bossEventLosesThePlayerIdAt2192() {
        assertTrue(applies(BedrockDeviation.BOSS_EVENT_PLAYER_EID, 1001));
        assertTrue(applies(BedrockDeviation.BOSS_EVENT_PLAYER_EID, 2191));
        assertFalse(applies(BedrockDeviation.BOSS_EVENT_PLAYER_EID, 2192));
        assertFalse(applies(BedrockDeviation.BOSS_EVENT_PLAYER_EID, 1000));
    }

    @Test
    void playSoundAndDebugTextForksStartAt2192() {
        assertFalse(applies(BedrockDeviation.PLAY_SOUND_V2192, 2168));
        assertTrue(applies(BedrockDeviation.PLAY_SOUND_V2192, 2192));

        assertFalse(applies(BedrockDeviation.DEBUG_DRAWER_TEXT_LINE_GAP, 2168));
        assertTrue(applies(BedrockDeviation.DEBUG_DRAWER_TEXT_LINE_GAP, 2192));

        assertFalse(applies(BedrockDeviation.SUB_CHUNK_HEIGHTMAP_SEGMENTS, 2168));
        assertTrue(applies(BedrockDeviation.SUB_CHUNK_HEIGHTMAP_SEGMENTS, 2192));
    }

    @Test
    void playFabIdMovesOutOfTheSkinBlockAt2225() {
        assertFalse(applies(BedrockDeviation.SKIN_PLAY_FAB_ID_REMOVED, 2224));
        assertTrue(applies(BedrockDeviation.SKIN_PLAY_FAB_ID_REMOVED, 2225));

        assertFalse(applies(BedrockDeviation.PLAYER_LIST_PLAY_FAB_ID, 2224));
        assertTrue(applies(BedrockDeviation.PLAYER_LIST_PLAY_FAB_ID, 2225));
    }

    @Test
    void skinBlockSkipsPlayFabIdAt2225() {
        final int at2168Length;
        final ByteBuf at2168 = Unpooled.buffer();
        try {
            SkinType.write(at2168, skinFixture(), 2168);
            at2168Length = at2168.readableBytes();
            assertEquals("playfab", SkinType.read(at2168, 2168).playFabId());
            assertEquals(0, at2168.readableBytes());
        } finally {
            at2168.release();
        }

        final ByteBuf at2225 = Unpooled.buffer();
        try {
            SkinType.write(at2225, skinFixture(), 2225);
            assertEquals(at2168Length - (1 + "playfab".length()), at2225.readableBytes(),
                    "2225 起皮肤块不再写 playFabId（1 字节长度前缀 + 7 字节内容）");
            assertEquals("", SkinType.read(at2225, 2225).playFabId());
            assertEquals(0, at2225.readableBytes());
        } finally {
            at2225.release();
        }
    }

    @Test
    void itemStackResponseContainerPresenceCollapsesAt2192() {
        assertTrue(ItemStackResponseLayout.usesOptionalContainerEntries(false, 2168));
        assertTrue(ItemStackResponseLayout.usesOptionalContainerEntries(false, 2191));
        assertFalse(ItemStackResponseLayout.usesOptionalContainerEntries(false, 2192));
        assertFalse(ItemStackResponseLayout.usesOptionalContainerEntries(false, 2225));

        assertFalse(ItemStackResponseLayout.usesSingleContainerPresence(false, 2191));
        assertTrue(ItemStackResponseLayout.usesSingleContainerPresence(false, 2192));
        assertTrue(ItemStackResponseLayout.usesSingleContainerPresence(false, 2225));

        assertFalse(ItemStackResponseLayout.usesOptionalContainerEntries(true, 860),
                "网易 860 仍使用“结果非 OK 就不带容器”的旧布局");
    }

    @Test
    void newDeviationsAreAddedToTheWireTableAsNewest() {
        assertTrue(BedrockWire.deviations(2225).contains(BedrockDeviation.ANIMATE_HAND_BYTE));
        assertTrue(BedrockWire.deviations(2192).contains(BedrockDeviation.SUB_CHUNK_HEIGHTMAP_SEGMENTS));
        assertFalse(BedrockWire.deviations(2191).contains(BedrockDeviation.SUB_CHUNK_HEIGHTMAP_SEGMENTS),
                "2191 仍是整块高度图");
    }

    private static boolean applies(final BedrockDeviation deviation, final int protocol) {
        return BedrockWire.applies(protocol, deviation);
    }

    @Test
    void inventoryHandBytesLandAt2192And2225() {
        assertFalse(InventoryTransactionLayout.usesUseItemHand(2191));
        assertTrue(InventoryTransactionLayout.usesUseItemHand(2192));
        assertTrue(InventoryTransactionLayout.usesUseItemHand(2225));

        assertFalse(InventoryTransactionLayout.usesUseItemOnEntityHand(2192));
        assertTrue(InventoryTransactionLayout.usesUseItemOnEntityHand(2225));

        assertFalse(InventoryTransactionLayout.usesReleaseItemHand(2192));
        assertTrue(InventoryTransactionLayout.usesReleaseItemHand(2225));
    }

    @Test
    void sourceShapeIsThreeStateAround2192() {
        assertEquals(InventoryTransactionLayout.SourceShape.LEGACY,
                InventoryTransactionLayout.SourceShape.of(0));
        assertEquals(InventoryTransactionLayout.SourceShape.LEGACY,
                InventoryTransactionLayout.SourceShape.of(1000));
        assertEquals(InventoryTransactionLayout.SourceShape.PRESENCE_PAIRS,
                InventoryTransactionLayout.SourceShape.of(1001));
        assertEquals(InventoryTransactionLayout.SourceShape.PRESENCE_PAIRS,
                InventoryTransactionLayout.SourceShape.of(2191));
        assertEquals(InventoryTransactionLayout.SourceShape.PRESENCE_BOOL,
                InventoryTransactionLayout.SourceShape.of(2192));
        assertEquals(InventoryTransactionLayout.SourceShape.PRESENCE_BOOL,
                InventoryTransactionLayout.SourceShape.of(2225));
    }

    @Test
    void inventorySourceRoundTripsInBothPresenceShapes() {
        final InventorySource container = new InventorySource(InventorySourceType.ContainerInventory, 5,
                InventorySource_InventorySourceFlags.NoFlag);

        final ByteBuf pairs = Unpooled.buffer();
        try {
            InventorySourcePacketType.write(pairs, container, InventoryTransactionLayout.SourceShape.PRESENCE_PAIRS);
            assertEquals(6, pairs.readableBytes(), "外层 bool + 内层 bool + 单字节 windowId + 外层 bool + 内层 bool");
            assertEquals(container, InventorySourcePacketType.read(pairs, InventoryTransactionLayout.SourceShape.PRESENCE_PAIRS));
            assertFalse(pairs.isReadable());
        } finally {
            pairs.release();
        }

        final ByteBuf single = Unpooled.buffer();
        try {
            InventorySourcePacketType.write(single, container, InventoryTransactionLayout.SourceShape.PRESENCE_BOOL);
            assertEquals(4, single.readableBytes(), "2192 起外层 bool 消失：存在性 bool + windowId + 存在性 bool");
            assertEquals(container, InventorySourcePacketType.read(single, InventoryTransactionLayout.SourceShape.PRESENCE_BOOL));
            assertFalse(single.isReadable());
        } finally {
            single.release();
        }

        final InventorySource world = new InventorySource(InventorySourceType.WorldInteraction,
                ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag);
        final ByteBuf singleWorld = Unpooled.buffer();
        try {
            InventorySourcePacketType.write(singleWorld, world, InventoryTransactionLayout.SourceShape.PRESENCE_BOOL);
            assertEquals(world, InventorySourcePacketType.read(singleWorld, InventoryTransactionLayout.SourceShape.PRESENCE_BOOL));
            assertFalse(singleWorld.isReadable());
        } finally {
            singleWorld.release();
        }
    }

    @Test
    void cameraPresetTailAndInventoryDeviationRanges() {
        assertFalse(CameraPacketLayout.usesStartingRotation(false, 2191));
        assertTrue(CameraPacketLayout.usesStartingRotation(false, 2192));
        assertTrue(CameraPacketLayout.usesStartingRotation(false, 2225));
        assertFalse(CameraPacketLayout.usesStartingRotation(true, 860));

        assertFalse(applies(BedrockDeviation.INVENTORY_USE_ITEM_HAND, 2191));
        assertTrue(applies(BedrockDeviation.INVENTORY_USE_ITEM_HAND, 2192));

        assertTrue(applies(BedrockDeviation.INVENTORY_SOURCE_PRESENCE_PAIRS, 2191));
        assertFalse(applies(BedrockDeviation.INVENTORY_SOURCE_PRESENCE_PAIRS, 2192));
        assertFalse(applies(BedrockDeviation.INVENTORY_SOURCE_PRESENCE_BOOL, 2191));
        assertTrue(applies(BedrockDeviation.INVENTORY_SOURCE_PRESENCE_BOOL, 2192));

        assertFalse(applies(BedrockDeviation.INVENTORY_LATE_HAND, 2192));
        assertTrue(applies(BedrockDeviation.INVENTORY_LATE_HAND, 2225));

        assertFalse(applies(BedrockDeviation.CAMERA_PRESETS_STARTING_ROTATION, 2191));
        assertTrue(applies(BedrockDeviation.CAMERA_PRESETS_STARTING_ROTATION, 2192));
    }

    private static SkinData skinFixture() {
        final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        return new SkinData(
                "skin-id", "playfab", "{}", image,
                List.of(new SkinData.AnimationData(image, 2, 1.5F, 3)),
                image, "{}", "1.26.40", "",
                true, false, true, true,
                "cape", "full-skin", "wide", "#00FF00",
                List.of(new SkinData.PersonaPieceData("piece", "body", "12345678-1234-1234-1234-123456789012", true, "product")),
                List.of(new SkinData.PersonaPieceTintData("persona_skin", List.of("#112233", "#445566"))),
                true, true, "hash");
    }

}
