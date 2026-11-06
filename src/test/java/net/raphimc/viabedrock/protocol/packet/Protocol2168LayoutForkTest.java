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
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol2168LayoutForkTest {

    private static final List<BedrockDeviation> PROTOCOL_2168_DEVIATIONS = List.of(
            BedrockDeviation.SKIN_ANIMATION_VARINTS,
            BedrockDeviation.SKIN_ARM_SIZE_BYTE,
            BedrockDeviation.ITEM_DESCRIPTOR_V2168,
            BedrockDeviation.RECIPE_INGREDIENT_TYPED,
            BedrockDeviation.ITEM_STACK_REQUEST_TYPED_ACTIONS,
            BedrockDeviation.ITEM_STACK_RESPONSE_OPTIONAL_FILTERED_NAME,
            BedrockDeviation.MOVE_PLAYER_TELEPORT_FLAG,
            BedrockDeviation.MOVE_ENTITY_DELTA_AXIS_BOOLS,
            BedrockDeviation.PLAYER_LIST_NO_TRUSTED,
            BedrockDeviation.PLAYER_SKIN_NO_TRUSTED,
            BedrockDeviation.RESOURCE_PACK_VARINT_COUNTS,
            BedrockDeviation.RESOURCE_PACK_STATUS_VARINT,
            BedrockDeviation.START_GAME_BYTE_FIELDS,
            BedrockDeviation.SUB_CHUNK_PACKET_V2168,
            BedrockDeviation.LEVEL_CHUNK_PACKET_V2168,
            BedrockDeviation.DIMENSION_DATA_PACK_ID,
            BedrockDeviation.PLAY_SOUND_V2168,
            BedrockDeviation.SET_SCORE_V2168,
            BedrockDeviation.CRAFTING_DATA_BUCKETS
    );

    @Test
    void profileIsRegisteredAsTheNewestLayout() {
        assertEquals(2168, BedrockProtocolProfile.V1_26_40.protocolVersion());
        assertEquals("1.26.40", BedrockProtocolProfile.V1_26_40.gameVersion());
        assertEquals(2168, BedrockProtocolProfile.newest().protocolVersion());
        assertEquals(2168, BedrockProtocolProfile.resolveProtocol(BedrockProtocolProfile.AUTO));
        assertTrue(BedrockProtocolProfile.isSupported(2168));
        assertTrue(BedrockProtocolProfile.isSelectable(2168));
        assertTrue(BedrockProtocolProfile.supportedList().contains("2168"));
        assertFalse(BedrockWire.inferred(2168));
    }

    @Test
    void deviationSetIsPinned() {
        final List<BedrockDeviation> active = BedrockWire.deviations(2168);
        assertTrue(active.containsAll(PROTOCOL_2168_DEVIATIONS));
        assertEquals(PROTOCOL_2168_DEVIATIONS.size(),
                active.stream().filter(deviation -> deviation.introducedAt() == 2168).count());
        assertFalse(active.contains(BedrockDeviation.START_GAME_LOGGING_CHAT),
                "START_GAME 的 loggingChat 在 2168 消失");
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.START_GAME_LOGGING_CHAT));
        assertEquals(BedrockWire.deviations(2167).size() + PROTOCOL_2168_DEVIATIONS.size() - 1,
                BedrockWire.deviations(2168).size(),
                "2168 新增 19 条，同时 START_GAME 的 loggingChat 在 2168 消失");
    }

    @Test
    void everyForkStartsAt2168AndStaysActiveUntilRemoved() {
        for (final BedrockDeviation deviation : PROTOCOL_2168_DEVIATIONS) {
            assertEquals(2168, deviation.introducedAt(), deviation.name());
            assertTrue(BedrockWire.applies(2168, deviation), deviation.name());
            assertTrue(BedrockWire.applies(2169, deviation), deviation.name());
            assertFalse(BedrockWire.applies(2167, deviation), deviation.name());
            assertFalse(BedrockWire.applies(1001, deviation), deviation.name());
        }
    }

    @Test
    void itemStackResponseFilteredNameIsOptionalAt2168() {
        assertEquals(2168, ItemStackResponseLayout.OPTIONAL_FILTERED_NAME_PROTOCOL);
        assertEquals(BedrockDeviation.ITEM_STACK_RESPONSE_OPTIONAL_FILTERED_NAME.introducedAt(),
                ItemStackResponseLayout.OPTIONAL_FILTERED_NAME_PROTOCOL);
        assertTrue(ItemStackResponseLayout.usesOptionalFilteredCustomName(2168));
        assertFalse(ItemStackResponseLayout.usesOptionalFilteredCustomName(2167));

        for (final String filtered : new String[]{null, "", "hidden"}) {
            final ByteBuf buffer = Unpooled.buffer();
            ItemStackResponseLayout.writeOkEntry(buffer, false, 2168, 7, 0, 3, 1, 42,
                    "visible", filtered, 0);
            final ItemStackResponseLayout.DecodedEntry entry =
                    ItemStackResponseLayout.decodeEntry(buffer, false, 2168);
            assertEquals(1, entry.containers().size());
            final List<ItemStackResponseLayout.DecodedSlot> slots = entry.containers().get(0).slots();
            assertEquals(1, slots.size());
            assertEquals("visible", slots.get(0).customName());
            assertEquals(filtered, slots.get(0).filteredCustomName());
            assertEquals(42, slots.get(0).stackNetworkId());
            assertEquals(0, buffer.readableBytes());
            buffer.release();
        }
    }

    @Test
    void itemStackResponseKeepsTheFixedFilteredNameBelow2168() {
        final ByteBuf buffer = Unpooled.buffer();
        ItemStackResponseLayout.writeOkEntry(buffer, false, 2167, 7, 0, 3, 1, 42,
                "visible", null, 0);
        final ItemStackResponseLayout.DecodedEntry entry =
                ItemStackResponseLayout.decodeEntry(buffer, false, 2167);
        assertEquals(1, entry.containers().size());
        final List<ItemStackResponseLayout.DecodedSlot> slots = entry.containers().get(0).slots();
        assertEquals(1, slots.size());
        assertEquals("", slots.get(0).filteredCustomName());
        assertEquals(0, buffer.readableBytes());
        buffer.release();
    }

}
