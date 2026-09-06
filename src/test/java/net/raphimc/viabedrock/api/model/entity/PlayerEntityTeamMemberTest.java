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
 */
package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerEntityTeamMemberTest {

    private static final UUID JAVA_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void remotePlayersUseTheirSyntheticProfileName() {
        PlayerEntity player = new PlayerEntity(null, 1L, 2, JAVA_UUID, new PlayerAbilities(3L, (byte) 0, (byte) 0));

        assertEquals(StringUtil.encodeUUID(JAVA_UUID), player.javaTeamMemberName());
    }

    @Test
    void localPlayerUsesTheLoginProfileName() {
        assertEquals("LocalPlayer", ClientPlayerEntity.localJavaTeamMemberName("LocalPlayer", JAVA_UUID));
    }

    @Test
    void localPlayerFallsBackWhenTheLoginNameIsUnavailable() {
        assertEquals(StringUtil.encodeUUID(JAVA_UUID), ClientPlayerEntity.localJavaTeamMemberName(null, JAVA_UUID));
        assertEquals(StringUtil.encodeUUID(JAVA_UUID), ClientPlayerEntity.localJavaTeamMemberName("", JAVA_UUID));
    }
}
