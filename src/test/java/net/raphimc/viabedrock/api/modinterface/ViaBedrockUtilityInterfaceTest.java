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
package net.raphimc.viabedrock.api.modinterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ViaBedrockUtilityInterfaceTest {

    @Test
    void skinPayloadChunkSizesIncludeTheirCompleteHeaders() {
        assertEquals(24, ViaBedrockUtilityInterface.SKIN_DATA_HEADER_SIZE);
        assertEquals(28, ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE);
        assertEquals(
                ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE,
                ViaBedrockUtilityInterface.SKIN_DATA_HEADER_SIZE + ViaBedrockUtilityInterface.SKIN_DATA_CHUNK_SIZE
        );
        assertEquals(
                ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE,
                ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE + ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE
        );
    }

    @Test
    void splits512SquareRgbaAnimationWithoutExceedingPayloadLimit() {
        final int animationBytes = 512 * 512 * 4;
        final int chunkCount = (int) Math.ceil(animationBytes / (double) ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE);
        final int firstPayloadSize = ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_HEADER_SIZE
                + Math.min(animationBytes, ViaBedrockUtilityInterface.SKIN_ANIMATION_DATA_CHUNK_SIZE);

        assertEquals(2, chunkCount);
        assertEquals(ViaBedrockUtilityInterface.MAX_PAYLOAD_SIZE, firstPayloadSize);
    }

}
