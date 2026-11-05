package net.raphimc.viabedrock.experimental.inventory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import net.easecation.blockstates.SharedBlockStates;
import net.easecation.blockstates.SharedBlockStates.JavaState;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.StairShapeRule;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 重建 Java 1.21.8 放置的目标状态，只在形状已知时判断玩家自身阻挡。 */
public final class PlacementPrediction {

    private static final double EPSILON = 1.0E-7;
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_STANDING_HEIGHT = 1.8;
    private static final double PLAYER_CROUCHING_HEIGHT = 1.5;
    private static final double[][] FULL_BLOCK = {{0, 0, 0, 1, 1, 1}};
    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    // 首次放置时映射已加载；不能在 BedrockMappingData 初始化期间构造楼梯规则。
    private static final class CatalogHolder {
        private static final CollisionCatalog COLLISIONS = new CollisionCatalog();
        private static final StairShapeRule STAIRS = new StairShapeRule(BedrockProtocol.MAPPINGS.getJavaBlockStates());
    }

    public record Result(BlockPosition target, boolean success) {
    }

    private PlacementPrediction() {
    }

    public static Result predict(final UserConnection user, final ClientPlayerEntity player, final BedrockItem item,
                                 final ChunkTracker chunks, final BlockPosition clicked, final BlockFace face,
                                 final Position3f hit) {
        BlockPosition target = clicked.getRelative(face);
        final BlockStateRewriter rewriter = user.get(BlockStateRewriter.class);
        final int itemStateId = rewriter.javaId(item.blockRuntimeId());
        // 自定义物品的替代显示状态不代表其实际放置行为。
        if (rewriter.sharedState(item.blockRuntimeId()) == null) return new Result(target, true);
        final JavaState itemState = clientState(itemStateId);
        if (itemState == null) return new Result(target, true);
        final JavaState clickedState = knownState(chunks, clicked);
        if (clickedState == null) return new Result(target, true);

        final CollisionCatalog catalog = CatalogHolder.COLLISIONS;
        final boolean mergeClicked = isSlab(itemState) && itemState.name().equals(clickedState.name())
                && canMergeClickedSlab(clickedState, face, hit.y());
        if (mergeClicked || isAirOrFluid(clickedState)) {
            target = clicked;
        } else if (!isSlab(clickedState) && !isStairs(clickedState)
                && !catalog.hasConstantSolidShape(clickedState.name())) {
            // 目录尚未导出 canBeReplaced。雪层、植被等特殊替换规则不能靠空碰撞箱猜测。
            return new Result(target, true);
        }

        final JavaState previous = target.equals(clicked) ? clickedState : knownState(chunks, target);
        if (previous == null) return new Result(target, true);
        JavaState placed = itemState;
        final double[][] shape;
        if (isSlab(itemState)) {
            if (itemState.name().equals(previous.name()) && !"double".equals(previous.properties().get("type"))) {
                // 合并发生在原半砖所在格，包括侧面点击后相邻格已有半砖的情况。
                placed = placed.with("type", "double").with("waterlogged", "false");
            } else {
                if (!isAirOrFluid(previous)) return new Result(target, true);
                placed = placed.with("type", upperHalf(face, hit.y()) ? "top" : "bottom")
                        .with("waterlogged", sourceWater(previous));
            }
            shape = catalog.shapes.get(placed);
        } else if (isStairs(itemState)) {
            if (!isAirOrFluid(previous) || !Float.isFinite(player.rotation().y())) return new Result(target, true);
            final Direction facing = Direction.getFromHorizontalId((int) Math.floor(player.rotation().y() / 90.0 + 0.5) & 3);
            placed = placed.with("facing", facing.name().toLowerCase(java.util.Locale.ROOT))
                    .with("half", upperHalf(face, hit.y()) ? "top" : "bottom")
                    .with("shape", "straight").with("waterlogged", sourceWater(previous));
            placed = stairState(chunks, target, placed);
            if (placed == null) return new Result(target, true);
            shape = catalog.shapes.get(placed);
        } else {
            if (!isAirOrFluid(previous)) return new Result(target, true);
            // 不依赖放置方向/开关等状态的类型可直接用完整目录，避免逐方块适配。
            shape = catalog.constantShapes.get(itemState.name());
        }

        final ClientPlayerEntity.JavaPosition feet = player.javaPosition();
        // 空数组是已知无碰撞；null 是未建模/上下文形状，继续由后端权威判定。
        if (shape == null || feet == null) return new Result(target, true);
        final boolean intersects = isFullBlock(shape)
                ? intersectsPlayer(target, feet.x(), feet.y(), feet.z(), player.isSneaking())
                : intersectsPlayer(target, shape, feet.x(), feet.y(), feet.z(), player.isSneaking());
        return new Result(target, !intersects);
    }

    private static boolean canMergeClickedSlab(final JavaState slab, final BlockFace face, final float hitY) {
        return switch (slab.properties().get("type")) {
            case "bottom" -> face == BlockFace.TOP || face.modY() == 0 && hitY > 0.5F;
            case "top" -> face == BlockFace.BOTTOM || face.modY() == 0 && hitY <= 0.5F;
            default -> false;
        };
    }

    private static boolean upperHalf(final BlockFace face, final float hitY) {
        return face == BlockFace.BOTTOM || face != BlockFace.TOP && hitY > 0.5F;
    }

    private static boolean isSlab(final JavaState state) {
        return state.name().endsWith("_slab") && state.properties().containsKey("type");
    }

    private static boolean isStairs(final JavaState state) {
        return state.name().endsWith("_stairs") && state.properties().containsKey("half")
                && state.properties().containsKey("facing") && state.properties().containsKey("shape");
    }

    private static boolean isAirOrFluid(final JavaState state) {
        return switch (state.name()) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water", "minecraft:lava" -> true;
            default -> false;
        };
    }

    private static String sourceWater(final JavaState state) {
        return Boolean.toString(state.name().equals("minecraft:water") && "0".equals(state.properties().get("level")));
    }

    private static JavaState clientState(final int id) {
        final BlockState state = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(id);
        return state == null ? null : SharedBlockStates.instance().clientState(
                new JavaState(state.namespacedIdentifier(), state.properties()));
    }

    private static JavaState knownState(final ChunkTracker chunks, final BlockPosition pos) {
        final BedrockChunkSection section = chunks.getChunkSection(pos);
        if (section == null || section.hasPendingBlockUpdates() || chunks.getKnownSharedBlockState(pos) == null) return null;
        if (section.palettesCount(PaletteType.BLOCKS) == 0) return new JavaState("minecraft:air", Map.of());
        final CustomMappingAccess.JavaBlockStateResolution resolution = chunks.getJavaBlockStateResolution(
                section, pos.x() & 15, pos.y() & 15, pos.z() & 15, "placement prediction");
        if (resolution.reason() != CustomMappingAccess.FallbackReason.VANILLA) return null;
        // 放置楼梯只读取邻居的 facing/half，不需要递归重算邻居的 shape。
        return clientState(resolution.javaBlockStateId());
    }

    private static Integer protocolStateId(final JavaState state) {
        final int colon = state.name().indexOf(':');
        return BedrockProtocol.MAPPINGS.getJavaBlockStates().get(new BlockState(
                state.name().substring(0, colon), state.name().substring(colon + 1), state.properties()));
    }

    private static JavaState stairState(final ChunkTracker chunks, final BlockPosition target, final JavaState placed) {
        final Integer placedId = protocolStateId(placed);
        if (placedId == null || !CatalogHolder.STAIRS.handles(placedId)) return null;
        final Map<BlockPosition, Integer> neighbors = new HashMap<>();
        for (final BlockFace direction : HORIZONTAL_FACES) {
            final BlockPosition pos = target.getRelative(direction);
            final JavaState neighbor = knownState(chunks, pos);
            if (neighbor == null) return null;
            // 以实际 1.21.8 客户端状态识别楼梯，不能把替代显示的方块当内部协议的楼梯。
            final Integer id = isStairs(neighbor) ? protocolStateId(neighbor) : Integer.valueOf(0);
            if (id == null || isStairs(neighbor) && !CatalogHolder.STAIRS.handles(id)) return null;
            neighbors.put(pos, id);
        }
        final BlockNeighborView view = new BlockNeighborView() {
            @Override
            public int getJavaBlockState(final BlockPosition pos) {
                return neighbors.getOrDefault(pos, 0);
            }

            @Override
            public BedrockBlockEntity getBlockEntity(final BlockPosition pos) {
                return null;
            }
        };
        // 复用已有的原版楼梯拐角规则；BE 邻接规则不能替代 Java 本地放置预测。
        final int result = CatalogHolder.STAIRS.recompute(view, target, placedId);
        final BlockState state = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(result);
        return state == null ? null : placed.with("shape", state.properties().get("shape"));
    }

    /** 已知完整立方体的几何工具入口；仅接触边界不算重叠。 */
    public static boolean intersectsPlayer(final BlockPosition target, final double feetX, final double feetY,
                                           final double feetZ, final boolean crouching) {
        return intersectsPlayer(target, FULL_BLOCK, feetX, feetY, feetZ, crouching);
    }

    private static boolean intersectsPlayer(final BlockPosition target, final double[][] shape, final double feetX,
                                            final double feetY, final double feetZ, final boolean crouching) {
        final double radius = PLAYER_WIDTH / 2.0;
        final double height = crouching ? PLAYER_CROUCHING_HEIGHT : PLAYER_STANDING_HEIGHT;
        for (final double[] box : shape) {
            if (overlap(target.x() + box[0], target.x() + box[3], feetX - radius, feetX + radius)
                    && overlap(target.y() + box[1], target.y() + box[4], feetY, feetY + height)
                    && overlap(target.z() + box[2], target.z() + box[5], feetZ - radius, feetZ + radius)) return true;
        }
        return false;
    }

    private static boolean isFullBlock(final double[][] shape) {
        return java.util.Arrays.deepEquals(shape, FULL_BLOCK);
    }

    private static boolean overlap(final double shapeMin, final double shapeMax,
                                   final double entityMin, final double entityMax) {
        return Math.min(shapeMax, entityMax) - Math.max(shapeMin, entityMin) > EPSILON;
    }

    /** 原版完整静态目录；不把“内部协议→客户端”投影表误当成所有可放置状态的目录。 */
    private static final class CollisionCatalog {
        private final Map<JavaState, double[][]> shapes = new HashMap<>();
        private final Map<String, double[][]> constantShapes = new HashMap<>();

        private CollisionCatalog() {
            final JsonObject geometry = SharedBlockStates.readRoot().getAsJsonObject("java_collision_geometry");
            final ArrayList<double[][]> boxes = new ArrayList<>();
            for (final JsonElement element : geometry.getAsJsonArray("shapes")) {
                final var rows = element.getAsJsonArray();
                final double[][] shape = new double[rows.size()][6];
                for (int i = 0; i < rows.size(); i++) {
                    for (int j = 0; j < 6; j++) shape[i][j] = rows.get(i).getAsJsonArray().get(j).getAsDouble();
                }
                boxes.add(shape);
            }
            final Map<String, Integer> constantIds = new HashMap<>();
            for (final JsonElement element : geometry.getAsJsonArray("states")) {
                final JsonObject row = element.getAsJsonObject();
                final String name = row.get("name").getAsString();
                final Map<String, String> properties = new HashMap<>();
                row.getAsJsonObject("properties").entrySet().forEach(e -> properties.put(e.getKey(), e.getValue().getAsString()));
                final int shape = row.get("shape").getAsInt();
                shapes.put(new JavaState(name, properties), boxes.get(shape));
                constantIds.merge(name, shape, (before, next) -> before.equals(next) ? before : -1);
            }
            for (final JsonElement element : geometry.getAsJsonArray("contextual_states")) {
                constantIds.put(element.getAsJsonObject().get("name").getAsString(), -1);
            }
            constantIds.forEach((name, id) -> {
                if (id >= 0) constantShapes.put(name, boxes.get(id));
            });
        }

        private boolean hasConstantSolidShape(final String name) {
            final double[][] shape = constantShapes.get(name);
            return shape != null && shape.length > 0;
        }
    }
}
