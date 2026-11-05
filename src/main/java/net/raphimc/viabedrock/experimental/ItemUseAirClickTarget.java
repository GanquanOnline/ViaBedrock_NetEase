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
package net.raphimc.viabedrock.experimental;

import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.model.Position3f;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 把 Java 的 {@code USE_ITEM}（点击空气）解析为基岩 CLICK_BLOCK 目标。
 * Java 的流体/船/睡莲/已装满的桶常常被当作空点击而错过；MOT 只从 CLICK_BLOCK
 * 调用 {@code Item.onActivate}。
 */
public final class ItemUseAirClickTarget {

    private static final Set<String> MOT_REPLACEABLE_BLOCKS = Set.of(
            "air", "bubble_column", "bush", "deadbush", "fern", "fire", "firefly_bush",
            "flowing_lava", "flowing_water", "glow_lichen", "large_fern", "lava", "light_block",
            "nether_sprouts", "seagrass", "short_dry_grass", "short_grass",
            "soul_fire", "tall_dry_grass", "tall_grass", "tallgrass", "vine", "water"
    );

    public static final double REACH_SURVIVAL = 4.5D;
    public static final double REACH_CREATIVE = 5.0D;

    public enum Fluid {
        WATER,
        LAVA,
        POWDER_SNOW
    }

    public record Hit(BlockPosition pos, int faceInt, BlockFace face, Position3f clickPosition, boolean insideBlock) {
    }

    public interface WorldView {
        int blockStateId(int layer, BlockPosition position);

        BlockState blockState(int bedrockBlockStateId);

        int airId();
    }

    public static Set<Fluid> pickupFluids(final String identifier) {
        if (ItemUseSemantics.isEmptyPickupBucket(identifier)) {
            return Set.of(Fluid.WATER, Fluid.LAVA, Fluid.POWDER_SNOW);
        }
        if (ItemUseSemantics.isGlassBottle(identifier)) {
            return Set.of(Fluid.WATER);
        }
        return null;
    }

    public static Hit raytraceFluidSource(final WorldView world, final Position3f eye, final float yaw, final float pitch,
                                          final double reach, final Set<Fluid> accepted) {
        if (world == null || eye == null || accepted == null || accepted.isEmpty()) {
            return null;
        }
        return raytrace(eye, yaw, pitch, reach, (pos, entry) -> {
            if (isAcceptedFluid(world, pos, accepted)) {
                return hit(eye, yaw, pitch, pos, entry, true);
            }
            return null;
        });
    }

    public static Hit raytracePlaceClick(final WorldView world, final Position3f eye, final float yaw, final float pitch,
                                         final double reach, final String identifier, final Set<String> itemTags) {
        if (world == null || eye == null || identifier == null) {
            return null;
        }
        if (ItemUseSemantics.isFilledPlaceBucket(identifier)) {
            return raytraceReplaceablePlace(world, eye, yaw, pitch, reach);
        }
        if (ItemUseSemantics.isWaterSurfacePlaceItem(identifier, itemTags)) {
            return raytraceWaterSurfacePlace(world, eye, yaw, pitch, reach);
        }
        return null;
    }

    static Hit raytraceReplaceablePlace(final WorldView world, final Position3f eye, final float yaw, final float pitch, final double reach) {
        return raytrace(eye, yaw, pitch, reach, (pos, entry) -> {
            if (entry.crossedAxis() < 0) {
                return null;
            }
            if (isReplaceable(world, pos)) {
                return null;
            }
            final BlockPosition placePos = pos.getRelative(entry.face());
            if (!isReplaceable(world, placePos) && !isLiquid(world, placePos) && !isSnowLayer(world, placePos)) {
                return null;
            }
            return hit(eye, yaw, pitch, pos, entry, false);
        });
    }

    static Hit raytraceWaterSurfacePlace(final WorldView world, final Position3f eye, final float yaw, final float pitch, final double reach) {
        return raytrace(eye, yaw, pitch, reach, (pos, entry) -> {
            if (!isWaterSource(world, pos)) {
                return null;
            }
            final BlockPosition above = pos.getRelative(BlockFace.TOP);
            if (!isAir(world, above)) {
                return null;
            }
            return new Hit(pos, Direction.UP.verticalId(), BlockFace.TOP, clickPosition(eye, yaw, pitch, entry.entryT(), pos), false);
        });
    }

    static boolean isAcceptedFluid(final WorldView world, final BlockPosition pos, final Set<Fluid> accepted) {
        if (accepted.contains(Fluid.POWDER_SNOW) && isPowderSnow(world, pos)) {
            return true;
        }
        return isLiquidSource(world.blockState(world.blockStateId(0, pos)), accepted)
                || isLiquidSource(world.blockState(world.blockStateId(1, pos)), accepted);
    }

    static boolean isLiquidSource(final BlockState state, final Set<Fluid> accepted) {
        if (state == null || accepted == null) {
            return false;
        }
        final String identifier = state.identifier();
        final boolean isWater = "water".equals(identifier) || "flowing_water".equals(identifier);
        final boolean isLava = "lava".equals(identifier) || "flowing_lava".equals(identifier);
        final boolean matches = (isWater && accepted.contains(Fluid.WATER))
                || (isLava && accepted.contains(Fluid.LAVA));
        if (!matches) {
            return false;
        }
        final String liquidDepth = state.properties().get("liquid_depth");
        return liquidDepth == null || "0".equals(liquidDepth);
    }

    static boolean isWaterSource(final WorldView world, final BlockPosition pos) {
        return isLiquidSource(world.blockState(world.blockStateId(0, pos)), Set.of(Fluid.WATER))
                || isLiquidSource(world.blockState(world.blockStateId(1, pos)), Set.of(Fluid.WATER));
    }

    static boolean isLiquid(final WorldView world, final BlockPosition pos) {
        return isLiquid(world.blockState(world.blockStateId(0, pos)))
                || isLiquid(world.blockState(world.blockStateId(1, pos)));
    }

    static boolean isLiquid(final BlockState state) {
        if (state == null) {
            return false;
        }
        final String identifier = state.identifier();
        return "water".equals(identifier)
                || "flowing_water".equals(identifier)
                || "lava".equals(identifier)
                || "flowing_lava".equals(identifier);
    }

    static boolean isPowderSnow(final WorldView world, final BlockPosition pos) {
        final BlockState state = world.blockState(world.blockStateId(0, pos));
        return state != null && "powder_snow".equals(state.identifier());
    }

    static boolean isSnowLayer(final WorldView world, final BlockPosition pos) {
        final BlockState state = world.blockState(world.blockStateId(0, pos));
        if (state == null || !"snow_layer".equals(state.identifier())) {
            return false;
        }
        final String height = state.properties().get("height");
        return height != null && !"7".equals(height);
    }

    static boolean isAir(final WorldView world, final BlockPosition pos) {
        final int id = world.blockStateId(0, pos);
        if (id == world.airId()) {
            return true;
        }
        final BlockState state = world.blockState(id);
        return state != null && "air".equals(state.identifier());
    }

    static boolean isReplaceable(final WorldView world, final BlockPosition pos) {
        final int id = world.blockStateId(0, pos);
        if (id == world.airId()) {
            return true;
        }
        final BlockState state = world.blockState(id);
        if (state == null) {
            return false;
        }
        if ("double_plant".equals(state.identifier())) {
            final String type = state.properties().get("double_plant_type");
            return "grass".equals(type) || "fern".equals(type);
        }
        if ("snow_layer".equals(state.identifier())) {
            final String height = state.properties().get("height");
            return height != null && !"7".equals(height);
        }
        return isMotReplaceableIdentifier(state.identifier());
    }

    static boolean isMotReplaceableIdentifier(final String identifier) {
        return identifier != null && MOT_REPLACEABLE_BLOCKS.contains(identifier);
    }

    private record Step(int crossedAxis, int crossedStep, double entryT, BlockFace face) {
    }

    private static Hit raytrace(final Position3f eye, final float yaw, final float pitch, final double reach,
                                final BiFunction<BlockPosition, Step, Hit> visitor) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        final double dy = -Math.sin(pitchRad);
        final double dz = Math.cos(yawRad) * Math.cos(pitchRad);

        final double ox = eye.x();
        final double oy = eye.y();
        final double oz = eye.z();

        int bx = (int) Math.floor(ox);
        int by = (int) Math.floor(oy);
        int bz = (int) Math.floor(oz);

        final int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        final int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        final int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        final double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        final double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        final double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX = dx != 0 ? ((dx > 0 ? bx + 1 : bx) - ox) / dx : Double.MAX_VALUE;
        double tMaxY = dy != 0 ? ((dy > 0 ? by + 1 : by) - oy) / dy : Double.MAX_VALUE;
        double tMaxZ = dz != 0 ? ((dz > 0 ? bz + 1 : bz) - oz) / dz : Double.MAX_VALUE;

        int crossedAxis = -1;
        int crossedStep = 0;
        double entryT = 0.0;

        for (int i = 0; i < 256; i++) {
            final BlockFace face = switch (crossedAxis) {
                case 0 -> crossedStep > 0 ? BlockFace.WEST : BlockFace.EAST;
                case 1 -> crossedStep > 0 ? BlockFace.BOTTOM : BlockFace.TOP;
                case 2 -> crossedStep > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
                default -> BlockFace.TOP;
            };
            final Hit hit = visitor.apply(new BlockPosition(bx, by, bz), new Step(crossedAxis, crossedStep, entryT, face));
            if (hit != null) {
                return hit;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > reach) {
                    break;
                }
                entryT = tMaxX;
                bx += stepX;
                tMaxX += tDeltaX;
                crossedAxis = 0;
                crossedStep = stepX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > reach) {
                    break;
                }
                entryT = tMaxY;
                by += stepY;
                tMaxY += tDeltaY;
                crossedAxis = 1;
                crossedStep = stepY;
            } else {
                if (tMaxZ > reach) {
                    break;
                }
                entryT = tMaxZ;
                bz += stepZ;
                tMaxZ += tDeltaZ;
                crossedAxis = 2;
                crossedStep = stepZ;
            }
        }
        return null;
    }

    private static Hit hit(final Position3f eye, final float yaw, final float pitch, final BlockPosition pos, final Step entry, final boolean insideBlock) {
        final Direction direction = switch (entry.crossedAxis()) {
            case 0 -> entry.crossedStep() > 0 ? Direction.WEST : Direction.EAST;
            case 1 -> entry.crossedStep() > 0 ? Direction.DOWN : Direction.UP;
            case 2 -> entry.crossedStep() > 0 ? Direction.NORTH : Direction.SOUTH;
            default -> Direction.UP;
        };
        return new Hit(pos, direction.verticalId(), direction.blockFace(), clickPosition(eye, yaw, pitch, entry.entryT(), pos), insideBlock);
    }

    static Position3f clickPosition(final Position3f eye, final float yaw, final float pitch, final double entryT, final BlockPosition pos) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        final double dy = -Math.sin(pitchRad);
        final double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Position3f(
                clamp01((float) (eye.x() + dx * entryT - pos.x())),
                clamp01((float) (eye.y() + dy * entryT - pos.y())),
                clamp01((float) (eye.z() + dz * entryT - pos.z()))
        );
    }

    private static float clamp01(final float value) {
        if (value < 0F) {
            return 0F;
        }
        if (value > 1F) {
            return 1F;
        }
        return value;
    }

    public static WorldView from(final Function<BlockPosition, Integer> layer0,
                                 final Function<BlockPosition, Integer> layer1,
                                 final Function<Integer, BlockState> states,
                                 final int airId) {
        return new WorldView() {
            @Override
            public int blockStateId(final int layer, final BlockPosition position) {
                return layer == 1 ? layer1.apply(position) : layer0.apply(position);
            }

            @Override
            public BlockState blockState(final int bedrockBlockStateId) {
                return states.apply(bedrockBlockStateId);
            }

            @Override
            public int airId() {
                return airId;
            }
        };
    }

    private ItemUseAirClickTarget() {
    }
}
