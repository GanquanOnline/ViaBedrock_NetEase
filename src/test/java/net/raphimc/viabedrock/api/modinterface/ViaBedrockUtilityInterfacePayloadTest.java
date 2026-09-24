/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.modinterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ViaBedrockUtilityInterfacePayloadTest {

    @Test
    void spectatorNoclipIsAppendedAfterParticleV2() {
        assertEquals(8, ViaBedrockUtilityInterface.PayloadType.SPAWN_PARTICLE.ordinal());
        assertEquals(9, ViaBedrockUtilityInterface.PayloadType.SPAWN_PARTICLE_V2.ordinal());
        assertEquals(10, ViaBedrockUtilityInterface.PayloadType.SPECTATOR_NOCLIP.ordinal());
        assertEquals("viabedrockutility:spectator_noclip", ViaBedrockUtilityInterface.SPECTATOR_NOCLIP_CAPABILITY);
        assertEquals("viabedrockutility:particle_runtime_v2", ViaBedrockUtilityInterface.PARTICLE_RUNTIME_V2_CAPABILITY);
    }

    @Test
    void missingChannelStorageIsNotASpectatorNoclipClient() {
        assertFalse(ViaBedrockUtilityInterface.hasSpectatorNoclip(null));
    }
}
