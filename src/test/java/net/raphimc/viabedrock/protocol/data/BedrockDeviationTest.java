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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockDeviationTest {

    @Test
    void everyDeviationIsDocumentedAndConsistentWithItsRange() {
        for (BedrockDeviation deviation : BedrockDeviation.values()) {
            assertFalse(deviation.description().isBlank(), deviation.name());
            assertTrue(deviation.removedAt() == 0 || deviation.removedAt() > deviation.introducedAt(),
                    deviation.name() + " 必须在出现之后消失");
        }
        assertTrue(BedrockDeviation.values().length <= BedrockDeviation.MAX_DEVIATIONS);
    }

    @Test
    void maskFitsInOneLong() {
        long mask = 0L;
        for (BedrockDeviation deviation : BedrockDeviation.values()) {
            assertEquals(0L, mask & deviation.bit(), "重复的位: " + deviation.name());
            mask |= deviation.bit();
        }
        assertEquals(BedrockDeviation.values().length, Long.bitCount(mask));
    }

    @Test
    void appliesRejectsMissingProtocolsAndHonoursTheRange() {
        for (BedrockDeviation deviation : BedrockDeviation.values()) {
            assertFalse(BedrockWire.applies(0, deviation), deviation.name());
            if (deviation.introducedAt() > 0) {
                assertTrue(BedrockWire.applies(deviation.introducedAt(), deviation), deviation.name());
                assertFalse(BedrockWire.applies(deviation.introducedAt() - 1, deviation), deviation.name());
            }
            if (deviation.removedAt() > 0) {
                assertFalse(BedrockWire.applies(deviation.removedAt(), deviation), deviation.name());
            }
        }
    }

    @Test
    void pinsTheDeviationSetOfTheBaseline() {
        assertEquals(List.of(), BedrockWire.deviations(975));
        assertFalse(BedrockWire.inferred(975));
        assertTrue(BedrockWire.describe(975).contains("protocol 975"));
        assertTrue(BedrockWire.describe(975).contains("0 deviation(s)"));
        assertEquals(List.of(), BedrockWire.deviations(0));
    }

    @Test
    void unknownProtocolsKeepTheirNumberAndAreMarkedInferred() {
        assertEquals(1024, BedrockProtocolProfile.resolveProtocol(1024));
        assertTrue(BedrockWire.inferred(1024));
        assertTrue(BedrockWire.describe(1024).startsWith("protocol 1024"));
        assertTrue(BedrockWire.describe(1024).contains("inferred"));
    }

    @Test
    void resolvesProfilesAndAuto() {
        assertEquals(2168, BedrockProtocolProfile.newest().protocolVersion());
        assertEquals("1.26.40", BedrockProtocolProfile.newest().gameVersion());
        assertEquals(2168, BedrockProtocolProfile.resolveProtocol(BedrockProtocolProfile.AUTO));
        assertEquals(975, BedrockProtocolProfile.byProtocol(975).protocolVersion());
        assertEquals("1.26.20", BedrockProtocolProfile.byProtocol(975).gameVersion());
        assertEquals(1001, BedrockProtocolProfile.byProtocol(1001).protocolVersion());
        assertNull(BedrockProtocolProfile.byProtocol(1024));
        assertTrue(BedrockProtocolProfile.isSupported(975));
        assertTrue(BedrockProtocolProfile.isSupported(1001));
        assertTrue(BedrockProtocolProfile.isSelectable(BedrockProtocolProfile.AUTO));
        assertTrue(BedrockProtocolProfile.isSelectable(975));
        assertTrue(BedrockProtocolProfile.isSelectable(1001));
        assertFalse(BedrockProtocolProfile.isSelectable(1024));
        assertTrue(BedrockProtocolProfile.supportedList().contains("975"));
        assertTrue(BedrockProtocolProfile.supportedList().contains("1001"));
        assertTrue(BedrockProtocolProfile.supportedList().contains("0 = auto"));
    }

    @Test
    void intervalSemanticsAreHalfOpen() {
        assertTrue(BedrockDeviation.inRange(900, 0, 900));
        assertTrue(BedrockDeviation.inRange(900, 0, Integer.MAX_VALUE));
        assertFalse(BedrockDeviation.inRange(900, 0, 899));
        assertTrue(BedrockDeviation.inRange(900, 950, 949));
        assertFalse(BedrockDeviation.inRange(900, 950, 950));
        assertFalse(BedrockDeviation.inRange(900, 950, 899));
        assertFalse(BedrockDeviation.inRange(900, 900, 900));
    }

    @Test
    void announcedGameVersionFollowsTheProfile() {
        assertEquals("1.26.20", BedrockWire.announcedGameVersion(975));
        assertEquals("1.26.30", BedrockWire.announcedGameVersion(1001));
        assertEquals("1.26.40", BedrockWire.announcedGameVersion(2168));
        assertEquals(BedrockWire.announcedGameVersion(975), BedrockWire.announcedGameVersion());
    }

    @Test
    void defaultWireProtocolIsTheBaseline() {
        assertEquals(975, BedrockWire.current());
        assertFalse(BedrockWire.inferred());
        assertEquals(BedrockWire.describe(975), BedrockWire.describe());
    }

}
