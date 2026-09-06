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
package net.raphimc.viabedrock.experimental.util;

import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaTeamColorUtilTest {

    @Test
    void mapsExactJavaTeamColorsToTheirOwnOrdinal() {
        for (int ordinal = 0; ordinal < 16; ordinal++) {
            int rgb = TextFormatting.getByOrdinal(ordinal).getRgbValue();
            assertEquals(ordinal, JavaTeamColorUtil.closestOrdinal(
                    rgb >> 16 & 0xFF,
                    rgb >> 8 & 0xFF,
                    rgb & 0xFF
            ));
        }
    }

    @Test
    void keepsBlackAndWhiteDistinct() {
        assertEquals(TextFormatting.BLACK.getOrdinal(), JavaTeamColorUtil.closestOrdinal(0, 0, 0));
        assertEquals(TextFormatting.WHITE.getOrdinal(), JavaTeamColorUtil.closestOrdinal(255, 255, 255));
    }
}
