/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstantBreakBlocksTest {

    @Test
    void recognizesVanillaLeavesForShears() {
        assertTrue(InstantBreakBlocks.isShearsInstantBreak("cherry_leaves", null));
        assertTrue(InstantBreakBlocks.isShearsInstantBreak("flowering_azalea_leaves", null));
    }

    @Test
    void recognizesEaseCationCustomCherryLeavesForShears() {
        assertTrue(InstantBreakBlocks.isShearsInstantBreak(null, "easecation:cherry_leaves"));
    }

    @Test
    void doesNotTreatUnrelatedBlocksAsShearsInstantBreak() {
        assertFalse(InstantBreakBlocks.isShearsInstantBreak("stone", null));
        assertFalse(InstantBreakBlocks.isShearsInstantBreak(null, "easecation:hxgz_planks"));
    }

    @Test
    void creativeStartCompletesEvenForHardBlocks() {
        assertTrue(InstantBreakBlocks.shouldCompleteOnJavaStart(true, "stone", null, "minecraft:diamond_pickaxe", null));
    }

    @Test
    void survivalStoneDoesNotCompleteOnStart() {
        assertFalse(InstantBreakBlocks.shouldCompleteOnJavaStart(false, "stone", null, "minecraft:diamond_pickaxe", null));
    }

    @Test
    void survivalZeroHardnessStillCompletesOnStart() {
        assertTrue(InstantBreakBlocks.shouldCompleteOnJavaStart(false, "wheat", null, "minecraft:air", null));
    }

    @Test
    void customZeroSecondsCompletesOnStart() {
        assertTrue(InstantBreakBlocks.shouldCompleteOnJavaStart(false, "mod_block", 0.0F, "minecraft:air", null));
    }

    @Test
    void unknownCustomSecondsDoesNotCompleteOnStart() {
        assertFalse(InstantBreakBlocks.shouldCompleteOnJavaStart(false, "stone", Float.NaN, "minecraft:air", null));
    }

    @Test
    void survivalScaffoldingDoesNotCompleteOnStart() {
        assertFalse(InstantBreakBlocks.shouldCompleteOnJavaStart(false, "scaffolding", null, "minecraft:air", null));
        assertFalse(InstantBreakBlocks.isVanillaInstantBreak("scaffolding"));
        assertTrue(InstantBreakBlocks.isJavaStartOnlyDelayedMotBreak("scaffolding"));
        assertEquals(13, InstantBreakBlocks.delayedMotBreakTicks("scaffolding"));
        assertEquals(0, InstantBreakBlocks.delayedMotBreakTicks("wheat"));
    }

    @Test
    void creativeScaffoldingStillCompletesOnStart() {
        assertTrue(InstantBreakBlocks.shouldCompleteOnJavaStart(true, "scaffolding", null, "minecraft:air", null));
    }

}
