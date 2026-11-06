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
        assertFalse(InteractPacketLayout.usesOptionalPosition(false, 860));
    }

    /** 897 的边界：命令串化、实体尾部、交互位置与划船时间。 */
    @Test
    void officialAxisFollowsThe897Forks() {
        assertFalse(CommandPacketLayout.usesStringLayout(false, 896));
        assertTrue(CommandPacketLayout.usesStringLayout(false, 897));
        assertFalse(EntityPacketLayout.usesAmbient(false, 896));
        assertTrue(EntityPacketLayout.usesAmbient(false, 897));
        assertFalse(EntityPacketLayout.usesSwingSource(false, 896));
        assertTrue(EntityPacketLayout.usesSwingSource(false, 897));
        assertFalse(EntityPacketLayout.usesByteAnimateAction(false, 896));
        assertTrue(EntityPacketLayout.usesByteAnimateAction(false, 897));
        assertTrue(EntityPacketLayout.usesRowingTime(false, 896));
        assertFalse(EntityPacketLayout.usesRowingTime(false, 897));
        assertFalse(InteractPacketLayout.usesOptionalPosition(false, 896));
        assertTrue(InteractPacketLayout.usesOptionalPosition(false, 897));
        assertTrue(JoinPackets.usesStartGameTickDeathSystems(896));
        assertFalse(JoinPackets.usesStartGameTickDeathSystems(897));
    }

    /** 924 的四个边界：BOOK_EDIT、TEXT、START_GAME 尾部与 tickDeathSystems。 */
    @Test
    void officialAxisFollowsThe924Forks() {
        assertFalse(BookEditLayout.usesVarIntLayout(false, 923));
        assertTrue(BookEditLayout.usesVarIntLayout(false, 924));
        assertTrue(TextPacketLayout.usesCategoryNameStrings(false, 923));
        assertFalse(TextPacketLayout.usesCategoryNameStrings(false, 924));
        assertTrue(JoinPackets.usesLegacyStartGameTail(923));
        assertFalse(JoinPackets.usesLegacyStartGameTail(924));
        assertTrue(JoinPackets.usesStartGameTickDeathSystems(860));
        assertTrue(JoinPackets.usesStartGameTickDeathSystems(896));
        assertFalse(JoinPackets.usesStartGameTickDeathSystems(897));
        assertFalse(JoinPackets.usesStartGameTickDeathSystems(924));
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

    /** 共享包的这些分支只看协议号：阈值均 ≤ 785，官方 860 已在阈值之上，与网易仿真开关无关。 */
    @Test
    void sharedLayoutsIgnoreTheNetEaseFlagOnTheOfficialAxis() {
        for (final int protocol : new int[]{860, 897, 898, 924, 944, 975}) {
            assertTrue(ItemStackResponseLayout.usesFullContainerName(false, protocol));
            assertTrue(ItemStackResponseLayout.usesCustomName(false, protocol));
            assertTrue(ItemStackResponseLayout.usesFilteredCustomName(false, protocol));
            assertTrue(ItemStackResponseLayout.usesDurabilityCorrection(false, protocol));
            assertTrue(ItemStackRequestLayout.usesOptionalDynamicId(false, protocol));
            assertTrue(LevelSoundEventLayout.usesEntityUniqueId(false, protocol));
            assertTrue(CraftingDataLayout.usesUnlockingRequirement(false, protocol));
        }
        assertFalse(ItemStackResponseLayout.usesFilteredCustomName(false, 765));
        assertFalse(ItemStackRequestLayout.usesOptionalDynamicId(false, 728));
        assertFalse(LevelSoundEventLayout.usesEntityUniqueId(false, 784));
        assertFalse(CraftingDataLayout.usesUnlockingRequirement(false, 684));
    }
}
