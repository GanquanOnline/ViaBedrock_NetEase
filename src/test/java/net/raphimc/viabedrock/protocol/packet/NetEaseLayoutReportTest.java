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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetEaseLayoutReportTest {

    @AfterEach
    void resetReport() {
        NetEaseLayoutReport.reset();
    }

    @Test
    void aggregatesLeftoversPerPacket() {
        NetEaseLayoutReport.record("clientbound/PLAY/46", 4);
        NetEaseLayoutReport.record("clientbound/PLAY/46", 9);
        NetEaseLayoutReport.record("serverbound/PLAY/7", 1);

        final String summary = NetEaseLayoutReport.summary();
        assertTrue(summary.contains("clientbound/PLAY/46: 2 occurrence(s), 13 byte(s) total, 9 byte(s) max"), summary);
        assertTrue(summary.contains("serverbound/PLAY/7: 1 occurrence(s), 1 byte(s) total"), summary);
    }

    @Test
    void ignoresNonPositiveLeftovers() {
        NetEaseLayoutReport.record("clientbound/PLAY/46", 0);
        assertEquals("no layout leftovers recorded", NetEaseLayoutReport.summary());
    }

    @Test
    void resetClearsRecordedPackets() {
        NetEaseLayoutReport.record("clientbound/PLAY/46", 3);
        NetEaseLayoutReport.reset();
        assertEquals("no layout leftovers recorded", NetEaseLayoutReport.summary());
    }

}
