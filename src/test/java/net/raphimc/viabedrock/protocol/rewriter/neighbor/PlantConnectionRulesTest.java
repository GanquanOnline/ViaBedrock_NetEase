/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.BaseChunk;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSectionImpl;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlantConnectionRulesTest {

    private static final BlockPosition POS = new BlockPosition(8, -49, 8);
    private final BiMap<BlockState, Integer> states = HashBiMap.create();

    PlantConnectionRulesTest() {
        add("air");
        add("stone");
        add("water[level=0]");
        add("end_stone");
        for (String plant : List.of("weeping_vines", "twisting_vines", "kelp")) {
            add(plant + "_plant");
            for (int age = 0; age <= 25; age++) add(plant + "[age=" + age + "]");
        }
        for (boolean berries : new boolean[]{false, true}) {
            add("cave_vines_plant[berries=" + berries + "]");
            for (int age = 0; age <= 25; age++) add("cave_vines[age=" + age + ",berries=" + berries + "]");
        }
        for (int age = 0; age <= 5; age++) add("chorus_flower[age=" + age + "]");
        for (int mask = 0; mask < 64; mask++) {
            final Map<String, String> connections = new HashMap<>();
            final String[] properties = {"north", "east", "south", "west", "up", "down"};
            for (int i = 0; i < properties.length; i++) connections.put(properties[i], Boolean.toString((mask & (1 << i)) != 0));
            this.states.put(new BlockState("chorus_plant", connections), this.states.size());
        }
    }

    @Test
    void downwardVinesUseBodiesUntilTheirBottomTip() {
        final MemoryView view = new MemoryView();
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        for (int age = 0; age <= 25; age++) {
            final int head = id("weeping_vines[age=" + age + "]");
            view.put(POS.getRelative(BlockFace.BOTTOM), head);
            assertEquals(id("weeping_vines_plant"), rewriter.resolveUpdate(view, POS, head).get(POS));
            view.put(POS.getRelative(BlockFace.BOTTOM), 0);
            assertEquals(head, rewriter.resolveUpdate(view, POS, head).get(POS));
        }
    }

    @Test
    void upwardVinesAndKelpUseBodiesUntilTheirTopTip() {
        final MemoryView view = new MemoryView();
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        for (String plant : List.of("twisting_vines", "kelp")) {
            for (int age = 0; age <= 25; age++) {
                final int head = id(plant + "[age=" + age + "]");
                view.put(POS.getRelative(BlockFace.TOP), head);
                assertEquals(id(plant + "_plant"), rewriter.resolveUpdate(view, POS, head).get(POS));
                view.put(POS.getRelative(BlockFace.TOP), id("water[level=0]"));
                assertEquals(head, rewriter.resolveUpdate(view, POS, head).get(POS));
            }
        }
    }

    @Test
    void caveVinesKeepTheirOwnBerriesWhileConnectingToEitherBerryVariant() {
        final GrowingPlantRule rule = new GrowingPlantRule(this.states);
        final MemoryView view = new MemoryView();
        for (boolean selfBerries : new boolean[]{false, true}) {
            for (boolean neighborBerries : new boolean[]{false, true}) {
                final int head = id("cave_vines[age=17,berries=" + selfBerries + "]");
                final int body = id("cave_vines_plant[berries=" + selfBerries + "]");
                view.put(POS.getRelative(BlockFace.BOTTOM), id("cave_vines[age=25,berries=" + neighborBerries + "]"));
                assertEquals(body, rule.recompute(view, POS, head));
                view.put(POS.getRelative(BlockFace.BOTTOM), id("cave_vines_plant[berries=" + neighborBerries + "]"));
                assertEquals(body, rule.recompute(view, POS, head));
                view.put(POS.getRelative(BlockFace.BOTTOM), 0);
                assertEquals(head, rule.recompute(view, POS, head));
                assertEquals(id("cave_vines[age=0,berries=" + selfBerries + "]"), rule.recompute(view, POS, body));
            }
        }
    }

    @Test
    void doesNotJoinDifferentPlantsOrLookInTheOppositeGrowthDirection() {
        final GrowingPlantRule rule = new GrowingPlantRule(this.states);
        final MemoryView view = new MemoryView();
        final int head = id("twisting_vines[age=13]");
        view.put(POS.getRelative(BlockFace.TOP), id("kelp_plant"));
        view.put(POS.getRelative(BlockFace.BOTTOM), head);
        assertEquals(head, rule.recompute(view, POS, head));
        assertFalse(rule.handles(id("stone")));
        assertFalse(rule.handles(-123));
        assertEquals(-123, rule.recompute(view, POS, -123));
        assertFalse(rule.affectsNeighborChunks());
    }

    @Test
    void growthAndBreakResendTheFormerTipWithItsOriginalAge() {
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        for (String plant : List.of("weeping_vines", "twisting_vines", "kelp", "cave_vines")) {
            final MemoryView view = new MemoryView();
            final String berries = plant.equals("cave_vines") ? ",berries=true" : "";
            final int head = id(plant + "[age=23" + berries + "]");
            final int body = id(plant + "_plant" + (berries.isEmpty() ? "" : "[berries=true]"));
            final BlockFace direction = plant.equals("weeping_vines") || plant.equals("cave_vines") ? BlockFace.BOTTOM : BlockFace.TOP;
            final BlockPosition next = POS.getRelative(direction);
            view.put(POS, head);
            view.put(next, head);
            assertEquals(body, rewriter.resolveUpdate(view, next, head).get(POS));
            view.put(next, 0);
            assertEquals(head, rewriter.resolveUpdate(view, next, 0).get(POS));
        }
    }

    @Test
    void initialChunkCorrectsPlantsAcrossSectionsAndAtNegativeHeights() {
        final ChunkSection[] sections = {new ChunkSectionImpl(false), new ChunkSectionImpl(false)};
        for (ChunkSection section : sections) section.palette(PaletteType.BLOCKS).addId(0);
        final Chunk chunk = new BaseChunk(0, 0, true, false, 3, sections, null, List.of());
        for (int x = 6; x <= 9; x++) {
            final String plant = List.of("weeping_vines", "twisting_vines", "kelp", "cave_vines").get(x - 6);
            final int head = id(plant + "[age=11" + (plant.equals("cave_vines") ? ",berries=false" : "") + "]");
            sections[0].palette(PaletteType.BLOCKS).setIdAt(x, 15, 8, head);
            sections[1].palette(PaletteType.BLOCKS).setIdAt(x, 0, 8, head);
        }
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        rewriter.fixChunk(null, chunk, 0, 0, -64);
        for (int x = 6; x <= 9; x++) {
            final String plant = List.of("weeping_vines", "twisting_vines", "kelp", "cave_vines").get(x - 6);
            final int body = id(plant + "_plant" + (plant.equals("cave_vines") ? "[berries=false]" : ""));
            final int head = id(plant + "[age=11" + (plant.equals("cave_vines") ? ",berries=false" : "") + "]");
            final boolean downward = plant.equals("weeping_vines") || plant.equals("cave_vines");
            assertEquals(downward ? head : body, sections[0].palette(PaletteType.BLOCKS).idAt(x, 15, 8));
            assertEquals(downward ? body : head, sections[1].palette(PaletteType.BLOCKS).idAt(x, 0, 8));
        }
    }

    @Test
    void chorusConnectsToPlantsAndFlowersInAllDirections() {
        final ChorusPlantConnectionRule rule = new ChorusPlantConnectionRule(this.states);
        final MemoryView view = new MemoryView();
        final int plant = id("chorus_plant[north=false,east=false,south=false,west=false,up=false,down=false]");
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.TOP, BlockFace.BOTTOM)) {
            view.put(POS.getRelative(face), face == BlockFace.TOP ? id("chorus_flower[age=5]") : plant);
        }
        assertEquals(id("chorus_plant[north=true,east=true,south=true,west=true,up=true,down=true]"), rule.recompute(view, POS, plant));
        assertTrue(rule.affectsNeighborChunks());
    }

    @Test
    void chorusConnectsToEndStoneOnlyBelowAndDisconnectsAfterBreak() {
        final MemoryView view = new MemoryView();
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        final int plant = id("chorus_plant[north=false,east=false,south=false,west=false,up=false,down=false]");
        view.put(POS, plant);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.TOP, BlockFace.BOTTOM)) {
            view.put(POS.getRelative(face), id("end_stone"));
        }
        assertEquals(id("chorus_plant[north=false,east=false,south=false,west=false,up=false,down=true]"), rewriter.resolveUpdate(view, POS, plant).get(POS));
        final BlockPosition below = POS.getRelative(BlockFace.BOTTOM);
        view.put(below, 0);
        assertEquals(plant, rewriter.resolveUpdate(view, below, 0).get(POS));
    }

    @Test
    void chorusOnChunkBorderTriggersNeighborResend() {
        final ChunkSection section = new ChunkSectionImpl(false);
        section.palette(PaletteType.BLOCKS).addId(0);
        section.palette(PaletteType.BLOCKS).setIdAt(15, 8, 8,
                id("chorus_plant[north=false,east=false,south=false,west=false,up=false,down=false]"));
        final Chunk chunk = new BaseChunk(0, 0, true, false, 1, new ChunkSection[]{section}, null, List.of());
        final NeighborAwareBlockRewriter rewriter = new NeighborAwareBlockRewriter(this.states);
        assertTrue(rewriter.hasCrossChunkSensitiveBlockOnBorder(chunk, 1));
        assertFalse(rewriter.hasCrossChunkSensitiveBlockOnBorder(chunk, 0));

        // 新到达的区块即使只有紫颂花，也必须让另一侧已经发出的枝干重新连接。
        section.palette(PaletteType.BLOCKS).setIdAt(15, 8, 8, id("chorus_flower[age=5]"));
        assertTrue(rewriter.hasCrossChunkSensitiveBlockOnBorder(chunk, 1));
        section.palette(PaletteType.BLOCKS).setIdAt(15, 8, 8, id("end_stone"));
        assertFalse(rewriter.hasCrossChunkSensitiveBlockOnBorder(chunk, 1));
    }

    private void add(final String state) {
        this.states.put(BlockState.fromString(state), this.states.size());
    }

    private int id(final String state) {
        return this.states.get(BlockState.fromString(state));
    }

    private static final class MemoryView implements BlockNeighborView {
        private final Map<BlockPosition, Integer> blocks = new HashMap<>();

        void put(final BlockPosition pos, final int id) {
            this.blocks.put(pos, id);
        }

        @Override
        public int getJavaBlockState(final BlockPosition pos) {
            return this.blocks.getOrDefault(pos, 0);
        }

        @Override
        public BedrockBlockEntity getBlockEntity(final BlockPosition pos) {
            return null;
        }
    }

}
