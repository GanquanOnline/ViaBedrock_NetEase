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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetEaseProtocolProfileTest {

    @Test
    void resolvesSelectableProfiles() {
        assertEquals(898, NetEaseProtocolProfile.resolve(898).protocolVersion());
        assertEquals(860, NetEaseProtocolProfile.resolve(860).protocolVersion());
        assertEquals(898, NetEaseProtocolProfile.resolve(NetEaseProtocolProfile.AUTO).protocolVersion());
        assertEquals(898, NetEaseProtocolProfile.newest().protocolVersion());
    }

    @Test
    void unsupportedProtocolFallsBackToNewestProfile() {
        assertEquals(898, NetEaseProtocolProfile.resolve(897).protocolVersion());
        assertEquals(898, NetEaseProtocolProfile.resolve(975).protocolVersion());
        assertNull(NetEaseProtocolProfile.byProtocol(897));
        assertFalse(NetEaseProtocolProfile.isSupported(897));
        assertTrue(NetEaseProtocolProfile.isSupported(860));
        assertTrue(NetEaseProtocolProfile.isSelectable(NetEaseProtocolProfile.AUTO));
        assertFalse(NetEaseProtocolProfile.isSelectable(999));
    }

    @Test
    void mapsMotGameVersionNames() {
        assertEquals("1.21.124_NetEase", NetEaseProtocolProfile.resolve(860).gameVersion());
        assertEquals("1.21.130_NetEase", NetEaseProtocolProfile.resolve(898).gameVersion());
    }

    @Test
    void resolvesRakNetProtocolVersion() {
        assertEquals(8, NetEaseProtocolProfile.resolveRakNetProtocolVersion(0));
        assertEquals(8, NetEaseProtocolProfile.resolveRakNetProtocolVersion(8));
        assertEquals(11, NetEaseProtocolProfile.resolveRakNetProtocolVersion(11));
        assertEquals(8, NetEaseProtocolProfile.resolveRakNetProtocolVersion(10));
    }
}
