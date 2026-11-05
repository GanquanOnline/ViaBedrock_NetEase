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
package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetEaseDeviationTest {

    @Test
    void everyDeviationIsDocumentedAndConsistentWithItsKind() {
        for (NetEaseDeviation deviation : NetEaseDeviation.values()) {
            assertFalse(deviation.description().isBlank(), deviation.name());
            if (deviation.kind() == NetEaseDeviation.Kind.PERMANENT) {
                assertEquals(0, deviation.removedAt(), deviation.name() + " 是网易独有偏差，不应声明移除版本");
            } else {
                assertTrue(deviation.removedAt() > deviation.introducedAt(),
                        deviation.name() + " 必须在出现之后消失");
            }
        }
        assertTrue(NetEaseDeviation.values().length <= NetEaseDeviation.MAX_DEVIATIONS);
    }

    @Test
    void maskFitsInOneLong() {
        long mask = 0L;
        for (NetEaseDeviation deviation : NetEaseDeviation.values()) {
            assertEquals(0L, mask & deviation.bit(), "重复的位: " + deviation.name());
            mask |= deviation.bit();
        }
        assertEquals(NetEaseDeviation.values().length, Long.bitCount(mask));
    }

    @Test
    void pinsTheDeviationSetsOfKnownProfiles() {
        assertEquals(List.of(
                NetEaseDeviation.PLAYER_LIST_TRAILER,
                NetEaseDeviation.TEXT_NETEASE_PARAMETER_LIST,
                NetEaseDeviation.TEXT_REQUIRED_FILTERED_STRING,
                NetEaseDeviation.TEXT_NETEASE_POPUP_TAIL,
                NetEaseDeviation.BEHAVIOUR_PACK_STACK_TRAILER,
                NetEaseDeviation.CONTAINER_OPEN_TRAILER,
                NetEaseDeviation.SEQUENTIAL_BLOCK_PALETTE_OVERLAY
        ), NetEaseWire.of(true, 860).deviations());
        assertEquals(List.of(
                NetEaseDeviation.PLAYER_LIST_TRAILER,
                NetEaseDeviation.TEXT_NETEASE_PARAMETER_LIST,
                NetEaseDeviation.CONTAINER_OPEN_TRAILER,
                NetEaseDeviation.CONTAINER_OPEN_EXTENDED_TRAILER,
                NetEaseDeviation.COMMAND_UNLIMIT_AFTER_VERSION,
                NetEaseDeviation.ENTITY_ATTACK_ID
        ), NetEaseWire.of(true, 898).deviations());
    }

    @Test
    void infersUnknownProtocolsFromRangesInsteadOfFallingBack() {
        final NetEaseWire future = NetEaseWire.of(true, 940);
        assertTrue(future.inferred());
        assertEquals(940, future.protocolVersion());
        assertTrue(future.has(NetEaseDeviation.CONTAINER_OPEN_EXTENDED_TRAILER));
        assertTrue(future.has(NetEaseDeviation.ENTITY_ATTACK_ID));
        assertFalse(future.has(NetEaseDeviation.BEHAVIOUR_PACK_STACK_TRAILER));
        assertFalse(future.has(NetEaseDeviation.SEQUENTIAL_BLOCK_PALETTE_OVERLAY));
        assertTrue(future.describe().contains("inferred"));
    }

    @Test
    void disablesWhenNetEaseIsOff() {
        assertEquals(NetEaseWire.DISABLED, NetEaseWire.of(false, 898));
        assertFalse(NetEaseWire.of(false, 898).enabled());
        assertEquals("disabled", NetEaseWire.DISABLED.describe());
        for (NetEaseDeviation deviation : NetEaseDeviation.values()) {
            assertFalse(NetEaseWire.applies(false, 898, deviation), deviation.name());
            assertFalse(NetEaseWire.applies(true, 0, deviation), deviation.name());
        }
    }

}
