/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GameType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameTypeRewriterTest {

    @Test
    void motViewerWiresAreSpectatorNotUndefined() {
        assertEquals(GameType.Spectator, GameTypeRewriter.fromWire(3));
        assertEquals(GameType.Spectator, GameTypeRewriter.fromWire(4));
        assertEquals(GameType.Spectator, GameTypeRewriter.fromWire(6));
        assertEquals(GameType.Survival, GameTypeRewriter.fromWire(0));
        assertEquals(GameType.Undefined, GameTypeRewriter.fromWire(99));
    }

    @Test
    void remoteAndUncapableLocalSpectatorsStayJavaSpectator() {
        assertEquals(GameMode.SPECTATOR, GameTypeRewriter.getEffectiveGameMode(GameType.Spectator, GameType.Survival));
        assertEquals(GameMode.SPECTATOR, GameTypeRewriter.getEffectiveGameMode(GameType.Spectator, GameType.Survival, false));
        assertEquals(GameMode.SURVIVAL, GameTypeRewriter.getEffectiveGameMode(GameType.Survival, GameType.Creative, true));
        assertEquals(GameMode.CREATIVE, GameTypeRewriter.getEffectiveGameMode(GameType.Creative, GameType.Survival, true));
        assertEquals(GameMode.ADVENTURE, GameTypeRewriter.getEffectiveGameMode(GameType.Adventure, GameType.Survival, true));
    }

    @Test
    void localSpectatorBecomesAdventureOnlyWhenVbuCanForceNoclip() {
        assertEquals(GameMode.ADVENTURE, GameTypeRewriter.getEffectiveGameMode(GameType.Spectator, GameType.Survival, true));
        assertEquals(GameMode.ADVENTURE, GameTypeRewriter.getEffectiveGameMode(GameType.Default, GameType.Spectator, true));
        assertEquals(GameMode.SPECTATOR, GameTypeRewriter.getEffectiveGameMode(GameType.Default, GameType.Spectator, false));
    }

    @Test
    void motSpectatorIncludesNoclipAbilityPromotion() {
        assertTrue(GameTypeRewriter.isMotSpectator(GameType.Spectator));
        assertFalse(GameTypeRewriter.isMotSpectator(GameType.Creative));
        assertFalse(GameTypeRewriter.isMotSpectator(GameType.Creative, null));
    }
}
