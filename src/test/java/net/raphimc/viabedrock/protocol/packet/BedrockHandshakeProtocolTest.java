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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockHandshakeProtocolTest {

    @Test
    void netEaseEmulationWinsOverTheOfficialValue() {
        assertEquals(898, LoginPackets.selectBedrockProtocolVersion(true, 898, 1001, 776));
    }

    @Test
    void officialValueWinsOverTheJavaHandshake() {
        assertEquals(1001, LoginPackets.selectBedrockProtocolVersion(false, 898, 1001, 776));
        assertEquals(975, LoginPackets.selectBedrockProtocolVersion(false, 0, 975, 776));
        assertEquals(975, LoginPackets.selectBedrockProtocolVersion(true, 0, 975, 776));
    }

    @Test
    void fallsBackToTheJavaHandshakeWhenNothingIsConfigured() {
        assertEquals(776, LoginPackets.selectBedrockProtocolVersion(false, 0, 0, 776));
        assertEquals(776, LoginPackets.selectBedrockProtocolVersion(true, 0, 0, 776));
    }

}
