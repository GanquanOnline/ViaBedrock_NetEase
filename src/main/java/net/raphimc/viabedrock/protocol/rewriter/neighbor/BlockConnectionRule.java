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
package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.api.model.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Java 的栅栏、玻璃板和铁栏杆带有每边的 {@code north/east/south/west} 连接布尔值，
 * 而基岩由客户端根据相邻方块计算。该规则会依据四个水平邻居
 * 重新计算单个可连接方块的连接变体。
 */
public final class BlockConnectionRule implements NeighborAwareBlockRule {

    private enum ConnectionType {FENCE, NETHER_FENCE, PANE}

    private final Int2ObjectMap<ConnectableProperties> connectableBlocks = new Int2ObjectOpenHashMap<>();
    private final IntSet solidBlockStates = new IntOpenHashSet();
    private final Int2ObjectMap<BlockFace> fenceGateFacing = new Int2ObjectOpenHashMap<>();
    private final IntSet wallStates = new IntOpenHashSet();
    private final IntSet stairStates = new IntOpenHashSet();

    public BlockConnectionRule(final BiMap<BlockState, Integer> javaBlockStates) {
        final Map<String, Map<Integer, Integer>> connectableGroups = new HashMap<>();
        final Map<String, ConnectionType> groupTypes = new HashMap<>();

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            final String id = state.identifier();
            final Map<String, String> props = state.properties();
            final int stateId = entry.getValue();

            if (id.endsWith("_fence_gate")) {
                final String facing = props.get("facing");
                if (facing != null) {
                    this.fenceGateFacing.put(stateId, parseFacing(facing));
                }
                continue;
            }

            if (id.endsWith("_wall")) {
                this.wallStates.add(stateId);
                continue;
            }

            if (id.endsWith("_stairs")) {
                this.stairStates.add(stateId);
                continue;
            }

            ConnectionType type = null;
            if (id.equals("nether_brick_fence")) {
                type = ConnectionType.NETHER_FENCE;
            } else if (id.endsWith("_fence")) {
                type = ConnectionType.FENCE;
            } else if (id.endsWith("_glass_pane") || id.equals("glass_pane") || id.equals("iron_bars")) {
                type = ConnectionType.PANE;
            }

            if (type != null && props.containsKey("east") && props.containsKey("north")
                    && props.containsKey("south") && props.containsKey("west")) {
                final String groupKey = state.namespacedIdentifier() + "|" + props.getOrDefault("waterlogged", "false");
                groupTypes.putIfAbsent(groupKey, type);

                final int connectionIndex = encodeConnections(
                        "true".equals(props.get("east")),
                        "true".equals(props.get("north")),
                        "true".equals(props.get("south")),
                        "true".equals(props.get("west"))
                );
                connectableGroups.computeIfAbsent(groupKey, k -> new HashMap<>()).put(connectionIndex, stateId);
                continue;
            }

            if (isSolidBlock(id)) {
                this.solidBlockStates.add(stateId);
            }
        }

        for (Map.Entry<String, Map<Integer, Integer>> groupEntry : connectableGroups.entrySet()) {
            final Map<Integer, Integer> indexToId = groupEntry.getValue();
            final ConnectionType type = groupTypes.get(groupEntry.getKey());

            final int[] connectionIds = new int[16];
            boolean complete = true;
            for (int i = 0; i < 16; i++) {
                final Integer id = indexToId.get(i);
                if (id == null) {
                    complete = false;
                    break;
                }
                connectionIds[i] = id;
            }
            if (!complete) continue;

            final ConnectableProperties properties = new ConnectableProperties(type, connectionIds);
            for (int stateId : connectionIds) {
                this.connectableBlocks.put(stateId, properties);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.connectableBlocks.containsKey(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final ConnectableProperties props = this.connectableBlocks.get(javaBlockStateId);
        if (props == null) return javaBlockStateId;

        final int connectionIndex = computeConnections(view, pos, props);
        return props.connectionIds[connectionIndex];
    }

    private int computeConnections(final BlockNeighborView view, final BlockPosition position, final ConnectableProperties props) {
        final boolean east = connects(props.type, BlockFace.EAST, view.getJavaBlockState(position.getRelative(BlockFace.EAST)));
        final boolean north = connects(props.type, BlockFace.NORTH, view.getJavaBlockState(position.getRelative(BlockFace.NORTH)));
        final boolean south = connects(props.type, BlockFace.SOUTH, view.getJavaBlockState(position.getRelative(BlockFace.SOUTH)));
        final boolean west = connects(props.type, BlockFace.WEST, view.getJavaBlockState(position.getRelative(BlockFace.WEST)));
        return encodeConnections(east, north, south, west);
    }

    private boolean connects(final ConnectionType selfType, final BlockFace face, final int neighborState) {
        if (neighborState == 0) return false;

        final ConnectableProperties neighborProps = this.connectableBlocks.get(neighborState);
        if (neighborProps != null) {
            return switch (selfType) {
                case FENCE -> neighborProps.type == ConnectionType.FENCE;
                case NETHER_FENCE -> neighborProps.type == ConnectionType.NETHER_FENCE;
                case PANE -> neighborProps.type == ConnectionType.PANE;
            };
        }

        if (selfType == ConnectionType.FENCE || selfType == ConnectionType.NETHER_FENCE) {
            final BlockFace gateFacing = this.fenceGateFacing.get(neighborState);
            if (gateFacing != null) {
                return face.axis() != gateFacing.axis();
            }
        }

        if (selfType == ConnectionType.PANE) {
            if (this.wallStates.contains(neighborState)) return true;
        }

        if (this.stairStates.contains(neighborState)) return true;

        return this.solidBlockStates.contains(neighborState);
    }

    private static int encodeConnections(final boolean east, final boolean north, final boolean south, final boolean west) {
        int index = 0;
        if (east) index |= 1;
        if (north) index |= 2;
        if (south) index |= 4;
        if (west) index |= 8;
        return index;
    }

    private static BlockFace parseFacing(final String facing) {
        return switch (facing) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static boolean isSolidBlock(final String identifier) {
        if (identifier.equals("air") || identifier.equals("cave_air") || identifier.equals("void_air")
                || identifier.equals("water") || identifier.equals("lava")) return false;

        for (String suffix : NON_SOLID_SUFFIXES) {
            if (identifier.endsWith(suffix)) return false;
        }

        return !NON_SOLID_BLOCKS.contains(identifier);
    }

    private static final String[] NON_SOLID_SUFFIXES = {
            "_fence", "_fence_gate", "_wall",
            "_pane",
            "_door", "_trapdoor",
            "_sign", "_hanging_sign", "_banner",
            "_button", "_pressure_plate",
            "_carpet", "_candle",
            "_torch", "_lantern", "_chain",
            "_rod", "_rail",
            "_flower", "_plant", "_sapling", "_mushroom",
            "_coral", "_coral_fan",
            "_pickle",
            "_head", "_skull",
            "_campfire",
            "_bed",
            "_cake",
            "_pot", "_cauldron",
            "_anvil",
            "_vine",
            "_fern", "_grass", "_bush",
            "_roots", "_sprouts",
            "_fungus",
            "_dripleaf",
            "_azalea",
            "_propagule",
            "_amethyst_cluster", "_amethyst_bud",
    };

    private static final Set<String> NON_SOLID_BLOCKS = Set.of(
            "barrier", "light",
            "enchanting_table", "brewing_stand",
            "hopper", "bell", "grindstone", "lectern",
            "composter", "stonecutter",
            "scaffolding",
            "honey_block", "slime_block",
            "spawner", "conduit",
            "end_portal_frame", "end_portal", "nether_portal",
            "daylight_detector",
            "farmland", "dirt_path", "soul_sand", "mud",
            "snow",
            "cobweb", "string",
            "ladder", "lever",
            "tripwire", "tripwire_hook",
            "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "repeater", "comparator",
            "piston", "piston_head", "sticky_piston", "moving_piston",
            "chest", "trapped_chest", "ender_chest",
            "shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
            "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box",
            "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box",
            "blue_shulker_box", "brown_shulker_box", "green_shulker_box", "red_shulker_box",
            "black_shulker_box",
            "dragon_egg",
            "turtle_egg", "sniffer_egg", "frogspawn",
            "bamboo",
            "cactus", "sugar_cane", "kelp", "seagrass", "tall_seagrass",
            "lily_pad", "moss_carpet", "pink_petals",
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "sweet_berry_bush", "cave_vines", "cave_vines_plant",
            "nether_wart", "chorus_plant", "chorus_flower",
            "cocoa", "torchflower_crop", "pitcher_crop", "pitcher_plant",
            "fire", "soul_fire",
            "structure_void",
            "end_rod",
            "lightning_rod",
            "pointed_dripstone",
            "decorated_pot",
            "trial_spawner", "vault",
            "heavy_core",
            "pale_hanging_moss",
            "open_eyeblossom", "closed_eyeblossom",
            "creaking_heart"
    );

    private record ConnectableProperties(ConnectionType type, int[] connectionIds) {
    }

}
