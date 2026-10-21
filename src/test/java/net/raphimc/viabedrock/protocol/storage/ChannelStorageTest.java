/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChannelStorageTest {

    @Test
    void recordsSpectatorNoclipCapabilityIndependentlyOfConfirm() {
        final ChannelStorage storage = new ChannelStorage();
        storage.addChannels(List.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL));
        assertFalse(storage.hasChannel(ViaBedrockUtilityInterface.SPECTATOR_NOCLIP_CAPABILITY));

        storage.addChannels(List.of(ViaBedrockUtilityInterface.SPECTATOR_NOCLIP_CAPABILITY));
        assertTrue(storage.hasChannel(ViaBedrockUtilityInterface.SPECTATOR_NOCLIP_CAPABILITY));
        assertTrue(storage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL));
    }
}
