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

import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayStateTransitionQueueTest {

    @Test
    void defersJoinTimePlayersMountsIdentifiersAndChunkCenter() {
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_PLAYER));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_PAINTING));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.SET_ENTITY_LINK));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.AVAILABLE_ENTITY_IDENTIFIERS));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.NETWORK_CHUNK_PUBLISHER_UPDATE));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.LEVEL_CHUNK));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_ENTITY));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.START_GAME));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.RESOURCE_PACKS_INFO));
    }

    @Test
    void defersMot860ProcessLoginAbilitiesCreativeAndHeldItem() {
        // MOT 的 Player.processLogin() 在 START_GAME 之后：adventureSettings.update()、
        // sendCreativeContents()、sendHeldItemIfNotAir()、TrimDataPacket。
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.UPDATE_ABILITIES));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.UPDATE_ADVENTURE_SETTINGS));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.TRIM_DATA));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.CAMERA_PRESETS));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.SYNC_ENTITY_PROPERTY));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.PY_RPC));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.TEXT));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.SET_TITLE));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.TOAST_REQUEST));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.BOSS_EVENT));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ITEM_REGISTRY));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.INVENTORY_CONTENT));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.CREATIVE_CONTENT));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.MOB_EQUIPMENT));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.MOB_ARMOR_EQUIPMENT));
    }
}
