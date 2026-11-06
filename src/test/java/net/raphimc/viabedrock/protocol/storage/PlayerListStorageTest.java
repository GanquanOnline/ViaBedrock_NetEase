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
package net.raphimc.viabedrock.protocol.storage;

import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.packet.ConfirmSkinLayout;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListStorageTest {

    private static final UUID LOCAL_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final UUID REMOTE_UUID = UUID.fromString("87654321-4321-8765-cba9-876543210fed");

    @Test
    void cachesSnapshotBeforePlayerListEntryArrives() {
        final PlayerListStorage storage = new PlayerListStorage();

        assertTrue(storage.replaceServerLatencies(Map.of(REMOTE_UUID, 87), LOCAL_UUID).isEmpty());
        assertEquals(87, storage.serverLatency(REMOTE_UUID));

        storage.addPlayer(REMOTE_UUID, 1L, "remote");
        assertEquals(87, storage.serverLatency(REMOTE_UUID));
    }

    @Test
    void publishesOnlyChangedRemoteLatencies() {
        final PlayerListStorage storage = new PlayerListStorage();
        storage.addPlayer(LOCAL_UUID, 1L, "local");
        storage.addPlayer(REMOTE_UUID, 2L, "remote");
        storage.markLatencyPublished(REMOTE_UUID, 87);

        assertTrue(storage.replaceServerLatencies(Map.of(REMOTE_UUID, 87), LOCAL_UUID).isEmpty());
        assertEquals(Map.of(REMOTE_UUID, 142), storage.replaceServerLatencies(Map.of(REMOTE_UUID, 142), LOCAL_UUID));
        storage.markLatencyPublished(REMOTE_UUID, 142);
        assertEquals(Map.of(REMOTE_UUID, PacketSyncStorage.UNKNOWN_LATENCY), storage.replaceServerLatencies(Map.of(), LOCAL_UUID));
        assertFalse(storage.replaceServerLatencies(Map.of(LOCAL_UUID, 999), LOCAL_UUID).containsKey(LOCAL_UUID));
    }

    @Test
    void removingPlayerClearsPublishedStateButKeepsSnapshot() {
        final PlayerListStorage storage = new PlayerListStorage();
        storage.addPlayer(REMOTE_UUID, 2L, "remote");
        storage.replaceServerLatencies(Map.of(REMOTE_UUID, 87), LOCAL_UUID);
        storage.markLatencyPublished(REMOTE_UUID, 87);

        storage.removePlayer(REMOTE_UUID);
        assertEquals(87, storage.serverLatency(REMOTE_UUID));

        storage.addPlayer(REMOTE_UUID, 2L, "remote");
        assertEquals(Map.of(REMOTE_UUID, 87), storage.replaceServerLatencies(Map.of(REMOTE_UUID, 87), LOCAL_UUID));
    }

    @Test
    void confirmSkinKeepsPlayerListSlimHintWhenGeometryHasBothTemplates() {
        final PlayerListStorage storage = new PlayerListStorage();
        final SkinData playerList = dummySkin(ConfirmSkinLayout.SLIM_RESOURCE_PATCH, "slim",
                "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.humanoid.custom\"}},{\"description\":{\"identifier\":\"geometry.humanoid.customSlim\"}}]}");
        final SkinData afterList = storage.rememberAndApplyArmHint(REMOTE_UUID, playerList);
        assertEquals("slim", afterList.armSize());
        assertTrue(afterList.skinResourcePatch().contains("customSlim"));

        final SkinData confirm = dummySkin("", "", playerList.geometryData());
        final SkinData afterConfirm = storage.rememberAndApplyArmHint(REMOTE_UUID, confirm);
        assertEquals("slim", afterConfirm.armSize());
        assertTrue(afterConfirm.skinResourcePatch().contains("customSlim"));
    }

    private static SkinData dummySkin(final String patch, final String armSize, final String geometry) {
        final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF112233);
        return new SkinData(
                "skin-id",
                "playfab",
                patch,
                image,
                Collections.emptyList(),
                null,
                geometry,
                "1.21.124",
                "",
                false,
                false,
                false,
                true,
                "",
                "full-skin-id",
                armSize,
                "#0",
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false,
                null
        );
    }
}
