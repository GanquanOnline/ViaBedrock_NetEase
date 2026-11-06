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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialBedrockLayoutForkTest {

    @Test
    void officialAxisKeeps975LayoutAndFollowsPre944Forks() {
        assertFalse(BlockPositionLayout.usesUnsignedY(false, 975));
        assertTrue(BlockPositionLayout.usesUnsignedY(false, 860));
        assertTrue(BlockPositionLayout.usesUnsignedY(false, 943));
        assertFalse(BlockPositionLayout.usesUnsignedY(false, 944));
        assertTrue(InventoryTransactionLayout.usesClientCooldownState(false, 975));
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(false, 860));
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(false, 943));
        assertTrue(InventoryTransactionLayout.usesClientCooldownState(false, 944));
        assertTrue(UpdateClientInputLocksLayout.usesServerPosition(false, 943));
        assertFalse(UpdateClientInputLocksLayout.usesServerPosition(false, 944));
        assertTrue(InteractPacketLayout.usesOptionalPosition(false, 975));
        assertTrue(InteractPacketLayout.usesOptionalPosition(false, 860));
    }

    @Test
    void netease860OnlyChangesWhenEmulationIsEnabled() {
        assertTrue(BlockPositionLayout.usesUnsignedY(true, 860));
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(true, 860));
        assertFalse(InteractPacketLayout.usesOptionalPosition(true, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseTrailer(false, 975));
        assertTrue(ContainerOpenLayout.usesNetEaseTrailer(true, 860));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(false, 975));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(false, 860));
        assertTrue(PlayerAuthInputLayout.usesCameraDeparted(true, 860));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(true, 421));
        assertTrue(InventoryContentLayout.usesRequiredContainerFields(false, 975));
        assertTrue(InventoryContentLayout.usesRequiredContainerFields(true, 860));
        assertTrue(InventorySlotLayout.usesRequiredContainerFields(true, 860));
        assertFalse(InventorySlotLayout.usesRequiredContainerFields(false, 975));
        assertTrue(PlayerListLayout.usesNetEaseAddTrailer(true, 860));
        assertFalse(PlayerListLayout.usesNetEaseAddTrailer(false, 975));
        assertFalse(PlayerListLayout.usesNetEaseAddTrailer(true, 648));
        assertFalse(BookEditLayout.usesVarIntLayout(true, 860));
        assertTrue(BookEditLayout.usesVarIntLayout(false, 975));
        assertTrue(UpdateClientInputLocksLayout.usesServerPosition(true, 860));
        assertFalse(UpdateClientInputLocksLayout.usesServerPosition(false, 975));
    }
}
