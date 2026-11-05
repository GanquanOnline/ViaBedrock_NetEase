/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.data;

import com.viaversion.viaversion.libs.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Netease860BlockRuntimeOverlayTest {

    @Test
    void overlayOnlyAppliesToThe860SequentialPalette() {
        assertTrue(Netease860BlockRuntimeOverlay.applies(true, 860, false));
        assertFalse(Netease860BlockRuntimeOverlay.applies(true, 860, true));
        assertFalse(Netease860BlockRuntimeOverlay.applies(false, 860, false));
        assertFalse(Netease860BlockRuntimeOverlay.applies(true, 898, false));
        assertFalse(Netease860BlockRuntimeOverlay.applies(true, 0, false));
    }

    @Test
    void bundledMot860OverlayMapsHashedIdsAndAddsMicroBlock() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                        "assets/viabedrock/data/bedrock/netease_860_block_runtime_ids.json")),
                StandardCharsets.UTF_8)) {
            final Netease860BlockRuntimeOverlay overlay = Netease860BlockRuntimeOverlay.parse(
                    JsonParser.parseReader(reader).getAsJsonObject());
            assertFalse(overlay.isEmpty());
            assertEquals(15829, overlay.hashedToSequential().size());
            assertEquals(1, overlay.extraStates().size());
            assertEquals("minecraft:micro_block", overlay.extraStates().getFirst().name());
            assertEquals(3211, overlay.extraStates().getFirst().runtimeId());
            assertEquals(3211, overlay.sequentialRuntimeId(overlay.extraStates().getFirst().networkId()));
            assertTrue(overlay.nextCustomSequentialId() > 3211);
            assertEquals(-1, overlay.sequentialRuntimeId(0x7fffffff));
        }
    }
}
