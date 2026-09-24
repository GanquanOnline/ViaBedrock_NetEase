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

import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginPacketsXuidTest {

    private static final Pattern DECIMAL_XUID = Pattern.compile("[1-9]\\d{0,19}");

    @Test
    void emitsUnsignedDecimalXuidFromUuidLowBits() {
        final UUID uuid = UUID.fromString("00000000-0000-0000-0000-0000ffffffff");
        final String xuid = LoginPackets.decimalXuid(uuid, "Steve");
        assertEquals("4294967295", xuid);
        assertTrue(DECIMAL_XUID.matcher(xuid).matches());
    }

    @Test
    void neverEmitsZeroOrHexXuid() {
        final UUID uuid = new UUID(0L, 0L);
        final String xuid = LoginPackets.decimalXuid(uuid, "Steve");
        assertEquals("1", xuid);
        assertTrue(DECIMAL_XUID.matcher(xuid).matches());
    }
}
