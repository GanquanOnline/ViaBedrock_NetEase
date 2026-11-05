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

import net.raphimc.viabedrock.protocol.data.Netease860BlockRuntimeOverlay;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextPacketType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住每个网易偏差在两个已知档案上的边界，防止把门控搬进偏差表时改变语义。
 */
class NetEaseLayoutBoundaryTest {

    @Test
    void containerOpenTrailerBoundaries() {
        assertTrue(ContainerOpenLayout.usesNetEaseTrailer(true, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseTrailer(true, 859));
        assertFalse(ContainerOpenLayout.usesNetEaseExtendedTrailer(true, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseExtendedTrailer(true, 897));
        assertTrue(ContainerOpenLayout.usesNetEaseExtendedTrailer(true, 898));
        assertFalse(ContainerOpenLayout.usesNetEaseTrailer(false, 898));
    }

    @Test
    void entityAndPlayerListBoundaries() {
        assertFalse(EntityPacketLayout.usesNetEaseAttackId(true, 860));
        assertTrue(EntityPacketLayout.usesNetEaseAttackId(true, 898));
        assertTrue(PlayerListLayout.usesNetEaseAddTrailer(true, 649));
        assertFalse(PlayerListLayout.usesNetEaseAddTrailer(true, 648));
    }

    @Test
    void resourcePackAndPaletteBoundaries() {
        assertTrue(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(true, 860));
        assertFalse(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(true, 897));
        assertTrue(Netease860BlockRuntimeOverlay.applies(true, 860, false));
        assertFalse(Netease860BlockRuntimeOverlay.applies(true, 898, false));
        assertFalse(Netease860BlockRuntimeOverlay.applies(true, 860, true));
    }

    @Test
    void textLayoutBoundaries() {
        assertTrue(TextPacketLayout.usesRequiredFilteredString(true, 860));
        assertFalse(TextPacketLayout.usesRequiredFilteredString(true, 898));
        assertFalse(TextPacketLayout.usesRequiredFilteredString(true, 684));
        assertTrue(TextPacketLayout.usesRequiredFilteredString(true, 685));
        assertTrue(TextPacketLayout.usesRequiredFilteredString(true, 896));
        assertTrue(TextPacketLayout.usesNetEasePopupTail(true, 860, TextPacketType.popup));
        assertFalse(TextPacketLayout.usesNetEasePopupTail(true, 898, TextPacketType.popup));
        assertFalse(TextPacketLayout.usesNetEasePopupTail(true, 409, TextPacketType.popup));
        assertTrue(TextPacketLayout.usesNetEasePopupTail(true, 410, TextPacketType.popup));
        assertTrue(TextPacketLayout.usesNetEaseParameterList(true, 860, TextPacketType.chat));
        assertTrue(TextPacketLayout.usesNetEaseParameterList(true, 898, TextPacketType.chat));
        assertFalse(TextPacketLayout.usesNetEaseParameterList(true, 409, TextPacketType.chat));
        assertTrue(TextPacketLayout.usesNetEaseParameterList(true, 410, TextPacketType.chat));
        assertTrue(TextPacketLayout.usesNetEaseParameterList(true, 923, TextPacketType.chat));
        assertFalse(TextPacketLayout.usesNetEaseParameterList(true, 924, TextPacketType.chat));
    }

}
