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

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import com.viaversion.viaversion.libs.fastutil.longs.LongList;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity.ItemUseSnapshot;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.model.map.MapDecoration;
import net.raphimc.viabedrock.experimental.model.map.MapObject;
import net.raphimc.viabedrock.experimental.model.map.MapTrackedObject;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.block.CustomBlockMappingModule;
import net.raphimc.viabedrock.experimental.blockbreak.BlockBreakingProgressModule;
import net.raphimc.viabedrock.experimental.camera.CameraModule;
import net.raphimc.viabedrock.experimental.inventory.BedrockItemLockPolicy;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.experimental.inventory.CraftingDataModule;
import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.experimental.inventory.PlacementPrediction;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.experimental.dimension.AlternateDimensionModule;
import net.raphimc.viabedrock.experimental.entity.CustomEntityTypeResolver;
import net.raphimc.viabedrock.experimental.npc.NpcDialogueModule;
import net.raphimc.viabedrock.experimental.modinterface.ModUIClientModule;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.riding.RidingModule;
import net.raphimc.viabedrock.experimental.tablist.TabListLatencyModule;
import net.raphimc.viabedrock.experimental.storage.BlockPlacementAckTracker;
import net.raphimc.viabedrock.experimental.storage.MapTracker;
import net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker;
import net.raphimc.viabedrock.experimental.storage.ScriptDebugTextTracker;
import net.raphimc.viabedrock.experimental.task.BlockBreakingProgressTickTask;
import net.raphimc.viabedrock.experimental.task.MultilineNametagTickTask;
import net.raphimc.viabedrock.experimental.task.ScriptDebugTextTickTask;
import net.raphimc.viabedrock.experimental.util.JavaMapPaletteUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ItemUseInventoryTransaction_TriggerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This class is used to register experimental features that are not yet stable/tested enough to be included in the main protocol.
 * These features may be subject to change or removal in future versions.
 */
public class ExperimentalFeatures {

    private static final int CROSSBOW_CHARGE_TICKS = 23;
    private static final int CROSSBOW_AUTO_FINISH_TICKS = 40;
    private static final long FINISH_USE_RELEASE_DELAY_MS = 50L;
    private static final int USE_ITEM_LEGACY_REQUEST_ID = 0;
    private static final BlockPosition AIR_USE_BLOCK_POSITION = new BlockPosition(0, 0, 0);
    private static final int AIR_USE_BLOCK_FACE = 255;
    private static final int AIR_USE_BLOCK_RUNTIME_ID = 0;
    private static final byte DEFAULT_COOLDOWN_STATE = 0;
    private static final List<FeatureModule> MODULES = new ArrayList<>();

    private record ReleaseItemSnapshot(int hotbarSlot, BedrockItem itemInHand, Position3f headPosition) {
    }

    public static List<FeatureModule> getModules() {
        return Collections.unmodifiableList(MODULES);
    }

    public static void registerModule(final FeatureModule module) {
        MODULES.add(module);
    }

    // --- Module dispatch methods ---

    public static void dispatchMappingsLoad(final BedrockMappingData data, final MappingLoadPhase phase) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onMappingsLoad(data, phase);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onMappingsLoad (" + phase + ")", e);
            }
        }
    }

    public static void dispatchEntityAdded(final UserConnection user, final Entity entity) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityAdded(user, entity);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityAdded", e);
            }
        }
    }

    public static void dispatchEntityRemoved(final UserConnection user, final Entity entity) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityRemoved(user, entity);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityRemoved", e);
            }
        }
    }

    public static void dispatchEntityLinks(final UserConnection user, final EntityLink[] links) {
        if (links.length == 0) {
            return;
        }

        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityLinks(user, links);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityLinks", e);
            }
        }
    }

    public static void dispatchEntityDataChanged(final UserConnection user, final Entity entity, final EntityData[] entityData) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityDataChanged(user, entity, entityData);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityDataChanged", e);
            }
        }
    }

    public static void dispatchEntityMoved(final UserConnection user, final Entity entity) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onEntityMoved(user, entity);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onEntityMoved", e);
            }
        }
    }

    public static void dispatchPlayerAuthInput(final UserConnection user, final ClientPlayerEntity clientPlayer, final PlayerAuthInputContext context) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onPlayerAuthInput(user, clientPlayer, context);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onPlayerAuthInput", e);
            }
        }
    }

    public static void dispatchChannelRegistered(final UserConnection user, final Set<String> channels) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onChannelRegistered(user, channels);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onChannelRegistered", e);
            }
        }
    }

    public static String dispatchResolveClientboundPyRpcChannel(final byte[] data) {
        for (final FeatureModule module : MODULES) {
            try {
                final String channel = module.resolveClientboundPyRpcChannel(data);
                if (channel != null) {
                    return channel;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module resolveClientboundPyRpcChannel", e);
            }
        }
        return null;
    }

    public static boolean isPlayerListEntryListed(final UserConnection user, final UUID uuid, final long entityUniqueId, final String name) {
        for (final FeatureModule module : MODULES) {
            try {
                if (!module.isPlayerListEntryListed(user, uuid, entityUniqueId, name)) {
                    return false;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module isPlayerListEntryListed", e);
            }
        }
        return true;
    }

    public static Tag decoratePlayerListDisplayName(final UserConnection user, final UUID uuid, final long entityUniqueId, final String name, final int latency, final Tag displayName) {
        Tag result = displayName;
        for (final FeatureModule module : MODULES) {
            try {
                result = module.decoratePlayerListDisplayName(user, uuid, entityUniqueId, name, latency, result);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module decoratePlayerListDisplayName", e);
            }
        }
        return result;
    }

    public static void dispatchPlayerLatenciesUpdated(final UserConnection user, final Map<UUID, Integer> latencies) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onPlayerLatenciesUpdated(user, latencies);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onPlayerLatenciesUpdated", e);
            }
        }
    }

    public static String dispatchResolveDimensionKey(final Dimension dimension, final ChunkTracker oldChunkTracker) {
        for (final FeatureModule module : MODULES) {
            try {
                final String result = module.resolveDimensionKey(dimension, oldChunkTracker);
                if (result != null) {
                    return result;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module resolveDimensionKey", e);
            }
        }
        return null;
    }

    public static Entity dispatchResolveEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type) {
        for (final FeatureModule module : MODULES) {
            try {
                final Entity result = module.resolveEntity(user, uniqueId, runtimeId, type);
                if (result != null) {
                    return result;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module resolveEntity", e);
            }
        }
        return null;
    }

    public static void dispatchResourcePackStackSet(final UserConnection user) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onResourcePackStackSet(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onResourcePackStackSet", e);
            }
        }
    }

    public static boolean dispatchCustomPayload(final String channel, final PacketWrapper wrapper) {
        for (final FeatureModule module : MODULES) {
            try {
                if (module.handleCustomPayload(channel, wrapper)) {
                    return true;
                }
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module handleCustomPayload", e);
            }
        }
        return false;
    }

    public static synchronized void registerModules() {
        if (!MODULES.isEmpty()) {
            return;
        }

        registerModule(new PyRpcDispatcherModule());  // Must be first (owns PY_RPC handler)
        registerModule(new ModUIClientModule());      // PY_RPC consumer
        registerModule(new CameraModule());
        registerModule(new AlternateDimensionModule());
        registerModule(new CustomEntityTypeResolver());
        registerModule(new CustomBlockMappingModule());
        registerModule(new ResourcePackModule());
        registerModule(new NpcDialogueModule());
        registerModule(new TabListLatencyModule());
        registerModule(new CraftingDataModule());
        registerModule(new ClientAuthInventoryModule());
        registerModule(new RidingModule());
        registerModule(new BlockBreakingProgressModule());
    }

    private static ItemReleaseInventoryTransaction_ActionType releaseActionForItem(final ItemRewriter itemRewriter, final BedrockItem item, final int usingTicks) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.releaseAction(identifier, itemTags, itemRewriter.itemUseDefinition(item), usingTicks);
    }

    private static boolean isContinuousUseItem(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.isContinuousUseItem(identifier, itemTags, itemRewriter.itemUseDefinition(item), isChargedCrossbow(item));
    }

    private static boolean isBow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return "minecraft:bow".equals(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isCrossbow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return "minecraft:crossbow".equals(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isChargedCrossbow(final ItemRewriter itemRewriter, final BedrockItem item) {
        return isCrossbow(itemRewriter, item) && isChargedCrossbow(item);
    }

    private static boolean isChargedCrossbow(final BedrockItem item) {
        return item.tag() != null && (item.tag().get("chargedItem") != null || item.tag().get("ChargedProjectiles") != null);
    }

    private static boolean shouldSendStandaloneUseTransaction(final ItemRewriter itemRewriter, final BedrockItem item) {
        return isBow(itemRewriter, item) || isCrossbow(itemRewriter, item);
    }

    private static BedrockInventoryTransaction createUseItemTransaction(final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        return new BedrockInventoryTransaction(
                USE_ITEM_LEGACY_REQUEST_ID,
                null,
                null,
                ComplexInventoryTransaction_Type.ItemUseTransaction,
                new InventoryTransactionData.UseItemTransactionData(
                        ItemUseInventoryTransaction_ActionType.Use,
                        ItemUseInventoryTransaction_TriggerType.Unknown,
                        AIR_USE_BLOCK_POSITION,
                        AIR_USE_BLOCK_FACE,
                        handContext.transactionHotbarSlot(),
                        handContext.item(),
                        clientPlayer.position(),
                        Position3f.ZERO,
                        AIR_USE_BLOCK_RUNTIME_ID,
                        ItemUseInventoryTransaction_PredictedResult.Success,
                        DEFAULT_COOLDOWN_STATE
                )
        );
    }

    // --- Empty bucket / glass bottle fluid interaction (Java USE_ITEM -> Bedrock CLICK_BLOCK) ---

    private static final String EMPTY_BUCKET_IDENTIFIER = "minecraft:bucket";
    private static final String GLASS_BOTTLE_IDENTIFIER = "minecraft:glass_bottle";
    private static final double FLUID_REACH_SURVIVAL = 4.5D;
    private static final double FLUID_REACH_CREATIVE = 5.0D;

    private enum Fluid {
        WATER,
        LAVA
    }

    private record FluidHitResult(BlockPosition pos, int faceInt, BlockFace face, Position3f clickPosition) {
    }

    /**
     * Java sends a plain USE_ITEM (click air) when an empty bucket / glass bottle is used on a fluid: the
     * crosshair ray misses non-pickable fluids, so the client falls back to {@code BucketItem#use}, which
     * does its own SOURCE_ONLY fluid ray trace server-side. A Bedrock server (client authoritative) instead
     * expects the client to resolve the targeted fluid source and send a CLICK_BLOCK ItemUseTransaction.
     * Returns the set of fluids the held item can pick up, or {@code null} if it is not such an item.
     */
    private static Set<Fluid> fluidInteractionItem(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        if (EMPTY_BUCKET_IDENTIFIER.equals(identifier)) {
            return Set.of(Fluid.WATER, Fluid.LAVA);
        }
        if (GLASS_BOTTLE_IDENTIFIER.equals(identifier)) {
            return Set.of(Fluid.WATER);
        }
        return null;
    }

    private static boolean isLiquidSource(final BlockStateRewriter blockStateRewriter, final int bedrockBlockStateId, final Set<Fluid> accepted) {
        final BlockState state = blockStateRewriter.blockState(bedrockBlockStateId);
        if (state == null) {
            return false;
        }
        final String identifier = state.identifier();
        // Bedrock represents both still and flowing liquids with a liquid_depth property; depth 0 is the full
        // "source" level. Pooled liquids on servers are frequently flowing_water/flowing_lava even at depth 0,
        // and Nukkit's ItemBucket fills any block where isLava()/isWaterSource() && isLiquidSource() (== depth 0),
        // so we must accept the flowing_* identifiers too (not just water/lava).
        final boolean isWater = "water".equals(identifier) || "flowing_water".equals(identifier);
        final boolean isLava = "lava".equals(identifier) || "flowing_lava".equals(identifier);
        final boolean matches = (isWater && accepted.contains(Fluid.WATER))
                || (isLava && accepted.contains(Fluid.LAVA));
        if (!matches) {
            return false;
        }
        final String liquidDepth = state.properties().get("liquid_depth");
        return liquidDepth == null || "0".equals(liquidDepth); // depth 0 = full source block, matching Java SOURCE_ONLY and Nukkit getDamage()==0
    }

    /**
     * Voxel ray trace (Amanatides &amp; Woo) from the player's eye along their look direction, returning the
     * first accepted fluid source block within reach, or {@code null} if none is hit.
     */
    private static FluidHitResult raytraceFluidSource(final UserConnection user, final ClientPlayerEntity clientPlayer, final float yaw, final float pitch, final Set<Fluid> accepted) {
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final BlockStateRewriter blockStateRewriter = user.get(BlockStateRewriter.class);

        // NOTE: ClientPlayerEntity.position() already stores the EYE position (feet y + eyeOffset), so we must
        // NOT add eyeOffset again here.
        final Position3f eye = clientPlayer.position();
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        final double dy = -Math.sin(pitchRad);
        final double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        final double reach = clientPlayer.javaGameMode() == GameMode.CREATIVE ? FLUID_REACH_CREATIVE : FLUID_REACH_SURVIVAL;

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

        int crossedAxis = -1; // 0=x, 1=y, 2=z, -1 = origin block (eye already inside it)
        int crossedStep = 0;
        double entryT = 0.0;

        for (int i = 0; i < 256; i++) {
            final BlockPosition pos = new BlockPosition(bx, by, bz);
            if (isLiquidSource(blockStateRewriter, chunkTracker.getBlockState(pos), accepted)) {
                final Direction direction = switch (crossedAxis) {
                    case 0 -> crossedStep > 0 ? Direction.WEST : Direction.EAST;
                    case 1 -> crossedStep > 0 ? Direction.DOWN : Direction.UP;
                    case 2 -> crossedStep > 0 ? Direction.NORTH : Direction.SOUTH;
                    default -> Direction.UP; // eye inside the source; face is only cosmetic for the fill
                };
                final Position3f clickPosition = new Position3f(
                        clamp01((float) (ox + dx * entryT - bx)),
                        clamp01((float) (oy + dy * entryT - by)),
                        clamp01((float) (oz + dz * entryT - bz))
                );
                return new FluidHitResult(pos, direction.verticalId(), direction.blockFace(), clickPosition);
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > reach) break;
                entryT = tMaxX;
                bx += stepX;
                tMaxX += tDeltaX;
                crossedAxis = 0;
                crossedStep = stepX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > reach) break;
                entryT = tMaxY;
                by += stepY;
                tMaxY += tDeltaY;
                crossedAxis = 1;
                crossedStep = stepY;
            } else {
                if (tMaxZ > reach) break;
                entryT = tMaxZ;
                bz += stepZ;
                tMaxZ += tDeltaZ;
                crossedAxis = 2;
                crossedStep = stepZ;
            }
        }
        return null;
    }

    private static float clamp01(final float value) {
        if (value < 0F) return 0F;
        if (value > 1F) return 1F;
        return value;
    }

    /**
     * Translates a Java right-click on a fake item frame entity into the Bedrock block interaction the server expects.
     * Item frames are blocks on Bedrock (handled by {@code BlockItemFrame.onActivate}: empty frame -> place held item,
     * filled frame -> rotate), but ViaBedrock exposes them to the Java client as entities, so the entity interaction
     * never reaches the server on its own. Returns false (no-op) when experimental features are off or the interacted
     * entity is not a tracked item frame, letting the caller fall back to its default handling.
     */
    public static boolean tryHandleItemFrameInteract(final UserConnection user, final int javaEntityId, final InteractionHand hand) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return false;
        }
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final EntityTracker.ItemFrameInfo info = entityTracker.getItemFrameInfo(javaEntityId);
        if (info == null) {
            return false;
        }

        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        final ItemUseHandContext handContext = ItemUseHandContext.resolve(user.get(InventoryTracker.class), hand);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final InventoryTransactionRewriter inventoryTransactionRewriter = user.get(InventoryTransactionRewriter.class);

        final int faceInt = info.facingDirection();
        final Direction direction = Direction.getFromVerticalId(faceInt);
        final BlockFace face = direction != null ? direction.blockFace() : BlockFace.NORTH;
        // Center of the block. Nukkit's BlockItemFrame.onActivate doesn't depend on the precise click position.
        final Position3f clickPosition = new Position3f(0.5F, 0.5F, 0.5F);

        sendItemUseOnBlock(user, clientPlayer, handContext, inventoryTransactionRewriter, chunkTracker,
                info.position(), faceInt, face, clickPosition, false, 0); // sequence 0: entity interaction, no Java ack
        return true;
    }

    public static boolean tryHandleSwapHands(final UserConnection user) {
        return ViaBedrock.getConfig().shouldEnableExperimentalFeatures()
                && ClientAuthInventoryModule.tryHandleSwapHands(user);
    }

    /**
     * Builds and sends the CLICK_BLOCK ItemUseTransaction (preceded/followed by Start/StopItemUseOn) that a
     * Bedrock client emits when interacting with a block. Shared by the USE_ITEM_ON handler and the empty
     * bucket / glass bottle fluid branch of USE_ITEM.
     */
    private static void sendItemUseOnBlock(final UserConnection user, final ClientPlayerEntity clientPlayer, final ItemUseHandContext handContext, final InventoryTransactionRewriter inventoryTransactionRewriter, final ChunkTracker chunkTracker, final BlockPosition position, final int faceInt, final BlockFace face, final Position3f clickPosition, final boolean insideBlock, final int sequence) {
        clientPlayer.swingSourceTracker().recordUse(handContext.hand(),
                handContext.item().blockRuntimeId() != 0 ? ActorSwingSource.Build : ActorSwingSource.Interact);
        // insideBlock 仅表示射线起点在方块内，不决定替换点击格还是在相邻格放置。
        // 半砖合并的目标、真实形状的自碰撞结果、物品预测与 ACK 必须使用同一次计算。
        final PlacementPrediction.Result placement = handContext.item().blockRuntimeId() != 0
                ? PlacementPrediction.predict(user, clientPlayer, handContext.item(), chunkTracker, position, face, clickPosition)
                : new PlacementPrediction.Result(insideBlock ? position : position.getRelative(face), true);
        final BlockPosition expectedPos = placement.target();
        final boolean predictedSuccess = placement.success();
        // sequence 0 means there is no Java block-change to acknowledge (e.g. item frame entity interaction). Registering
        // a pending ack for it would leave a phantom sequence-0 ack that flushExpired emits after the timeout.
        if (sequence > 0) {
            user.get(BlockPlacementAckTracker.class).addPendingAck(expectedPos, sequence);
        }

        // The bedrock client will send a start item use on action to the server first.
        ExperimentalPacketFactory.sendBedrockPlayerAction(
                user,
                clientPlayer.runtimeId(),
                PlayerActionType.StartItemUseOn,
                position,
                expectedPos,
                faceInt
        );

        // This is the main packet that the bedrock client use to interact with block.
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);

        BedrockItem predictedToItem = handContext.item().copy();
        // This is not entirely correct, but at least it's more accurate than not sending actions or sending the original item data.
        if (predictedToItem.blockRuntimeId() != 0 && clientPlayer.javaGameMode() != GameMode.CREATIVE
                && predictedSuccess) {
            predictedToItem.setAmount(predictedToItem.amount() - 1);
        }
        if (predictedToItem.amount() <= 0) {
            predictedToItem = BedrockItem.empty();
        }

        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // legacy request id
                null,
                List.of(
                        new InventoryActionData(
                                new InventorySource(InventorySourceType.ContainerInventory, handContext.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                                handContext.containerSlot(),
                                handContext.item(),
                                predictedToItem
                        )
                ),
                ComplexInventoryTransaction_Type.ItemUseTransaction,
                new InventoryTransactionData.UseItemTransactionData(
                        ItemUseInventoryTransaction_ActionType.Place,
                        ItemUseInventoryTransaction_TriggerType.PlayerInput,
                        position,
                        faceInt,
                        handContext.transactionHotbarSlot(),
                        handContext.item(),
                        clientPlayer.position(),
                        clickPosition,
                        chunkTracker.getBlockState(position),
                        predictedSuccess
                                ? ItemUseInventoryTransaction_PredictedResult.Success
                                : ItemUseInventoryTransaction_PredictedResult.Failure,
                        (byte) 0 // TODO: client cooldown state
                )
        );
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);
        transactionPacket.sendToServer(BedrockProtocol.class);

        // Bedrock sends a stop item use on after the transaction packet
        ExperimentalPacketFactory.sendBedrockPlayerAction(
                user,
                clientPlayer.runtimeId(),
                PlayerActionType.StopItemUseOn,
                position,
                new BlockPosition(0, 0, 0),
                0
        );
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer, final ItemReleaseInventoryTransaction_ActionType actionType) {
        sendReleaseItemTransaction(user, inventoryTransactionRewriter, createReleaseItemSnapshot(handContext, clientPlayer), actionType);
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                handContext.transactionHotbarSlot(),
                handContext.item().copy(),
                clientPlayer.position().add(0F, clientPlayer.eyeOffset(), 0F)
        );
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final ItemUseSnapshot itemUseSnapshot, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                itemUseSnapshot.transactionHotbarSlot(),
                itemUseSnapshot.item().copy(),
                clientPlayer.position().add(0F, clientPlayer.eyeOffset(), 0F)
        );
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ReleaseItemSnapshot snapshot, final ItemReleaseInventoryTransaction_ActionType actionType) {
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // legacy request id
                null,
                null,
                ComplexInventoryTransaction_Type.ItemReleaseTransaction,
                new InventoryTransactionData.ReleaseItemTransactionData(
                        actionType,
                        snapshot.hotbarSlot(),
                        snapshot.itemInHand(),
                        snapshot.headPosition()
                )
        );
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);
        transactionPacket.sendToServer(BedrockProtocol.class);
    }

    private static boolean matchesUseItem(final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        return snapshot != null && snapshot.matches(
                handContext.hand(),
                handContext.containerId(),
                handContext.containerSlot(),
                handContext.transactionHotbarSlot(),
                handContext.item()
        );
    }

    private static void cancelUsingItem(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ClientPlayerEntity clientPlayer) {
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        if (snapshot != null) {
            sendReleaseItemTransaction(user, inventoryTransactionRewriter, createReleaseItemSnapshot(snapshot, clientPlayer), ItemReleaseInventoryTransaction_ActionType.Release);
        }
        clientPlayer.setUsingItem(false);
    }

    private static ItemUseHandContext createHandContext(final ItemUseSnapshot snapshot) {
        return new ItemUseHandContext(
                snapshot.hand(),
                snapshot.containerId(),
                snapshot.containerSlot(),
                snapshot.transactionHotbarSlot(),
                snapshot.item().copy()
        );
    }

    private static void sendUseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        sendUseItemTransaction(user, inventoryTransactionRewriter, handContext, clientPlayer, true);
    }

    private static void sendUseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer, final boolean sendEquipment) {
        if (sendEquipment && handContext.isMainHand()) {
            sendSelectedHotbarSlot(user, handContext, clientPlayer);
        }
        if (isBow(user.get(ItemRewriter.class), handContext.item())) {
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    user,
                    clientPlayer.runtimeId(),
                    PlayerActionType.StartItemUseOn,
                    AIR_USE_BLOCK_POSITION,
                    AIR_USE_BLOCK_POSITION,
                    AIR_USE_BLOCK_FACE
            );
        }
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), createUseItemTransaction(handContext, clientPlayer));
        transactionPacket.sendToServer(BedrockProtocol.class);
    }

    private static void finishCrossbowCharge(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        sendUseItemTransaction(user, inventoryTransactionRewriter, handContext, clientPlayer, false);
        final ReleaseItemSnapshot releaseSnapshot = createReleaseItemSnapshot(handContext, clientPlayer);
        user.getChannel().eventLoop().schedule(() -> sendReleaseItemTransaction(user, inventoryTransactionRewriter, releaseSnapshot, ItemReleaseInventoryTransaction_ActionType.Release), FINISH_USE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void finishConsumableUse(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ClientPlayerEntity clientPlayer) {
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        if (snapshot == null) {
            return;
        }
        sendUseItemTransaction(user, inventoryTransactionRewriter, createHandContext(snapshot), clientPlayer, false);
        final ReleaseItemSnapshot releaseSnapshot = createReleaseItemSnapshot(snapshot, clientPlayer);
        user.getChannel().eventLoop().schedule(() -> sendReleaseItemTransaction(user, inventoryTransactionRewriter, releaseSnapshot, ItemReleaseInventoryTransaction_ActionType.Release), FINISH_USE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void sendSelectedHotbarSlot(final UserConnection user, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        sendMobEquipment(user, clientPlayer, (byte) handContext.containerSlot(), handContext.item(), handContext.containerId());
    }

    private static void sendMobEquipment(final UserConnection user, final ClientPlayerEntity clientPlayer, final byte slot, final BedrockItem item, final byte containerId) {
        final PacketWrapper mobEquipmentPacket = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, user);
        mobEquipmentPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
        mobEquipmentPacket.write(user.get(ItemRewriter.class).newItemType(), item); // item
        mobEquipmentPacket.write(Types.BYTE, slot); // slot
        mobEquipmentPacket.write(Types.BYTE, slot); // selected slot
        mobEquipmentPacket.write(Types.BYTE, containerId); // container id
        mobEquipmentPacket.sendToServer(BedrockProtocol.class);
    }

    // --- Existing experimental features ---

    private static final int MAP_FLAGS_ALL = ClientboundMapItemDataPacket_Type.Creation.getValue() | ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue() | ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();

    public static void registerPacketTranslators(final BedrockProtocol protocol) {
        // Dispatch to feature modules before the built-in experimental hooks are installed.
        for (final FeatureModule module : MODULES) {
            try {
                module.onPacketRegistration(protocol);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onPacketRegistration", e);
            }
        }

        MultilineNametagTracker.registerHandlers(protocol);
        ScriptDebugTextTracker.registerHandlers(protocol);

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_ACTION, wrapper -> {
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            final PlayerActionAction action = PlayerActionAction.values()[wrapper.passthrough(Types.VAR_INT)]; // action
            wrapper.passthrough(Types.BLOCK_POSITION1_14); // block position
            wrapper.passthrough(Types.UNSIGNED_BYTE); // face
            final int sequence = wrapper.passthrough(Types.VAR_INT); // sequence number

            if (action == PlayerActionAction.RELEASE_USE_ITEM) {
                final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();

                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }

                if (!clientPlayer.isUsingItem()) {
                    if (clientPlayer.itemUseSnapshot() != null) {
                        cancelUsingItem(wrapper.user(), inventoryTransactionRewriter, clientPlayer);
                        return;
                    }
                    // The injected Java consumable produces a release packet, but Bedrock swords have no
                    // corresponding continuous-use state to release.
                    if (ExperimentalItemRewriter.isSwordBlockingAnimationItem(wrapper.user().get(ItemRewriter.class), inventoryTracker.getInventoryContainer().getSelectedHotbarItem())) {
                        return;
                    }
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                    return;
                }

                final ItemUseHandContext handContext = ItemUseHandContext.resolve(inventoryTracker, clientPlayer.usingItemHand());
                final BedrockItem selectedItem = handContext.item();
                if (!matchesUseItem(handContext, clientPlayer)) {
                    cancelUsingItem(wrapper.user(), inventoryTransactionRewriter, clientPlayer);
                    return;
                }
                final ItemReleaseInventoryTransaction_ActionType releaseAction = releaseActionForItem(
                        wrapper.user().get(ItemRewriter.class),
                        selectedItem,
                        clientPlayer.usingItemTicks()
                );
                if (isCrossbow(wrapper.user().get(ItemRewriter.class), selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_CHARGE_TICKS) {
                    finishCrossbowCharge(wrapper.user(), inventoryTransactionRewriter, handContext, clientPlayer);
                    clientPlayer.setUsingItem(false);
                    return;
                }
                if (releaseAction == ItemReleaseInventoryTransaction_ActionType.Use) {
                    finishConsumableUse(wrapper.user(), inventoryTransactionRewriter, clientPlayer);
                    clientPlayer.setUsingItem(false);
                    return;
                }
                clientPlayer.setUsingItem(false);

                sendReleaseItemTransaction(wrapper.user(), inventoryTransactionRewriter, handContext, clientPlayer, releaseAction);
            } else if (action == PlayerActionAction.DROP_ITEM || action == PlayerActionAction.DROP_ALL_ITEMS) {
                final BedrockItem currentItem = inventoryTracker.getInventoryContainer().getSelectedHotbarItem();

                wrapper.cancel();
                if (currentItem.isEmpty()) {
                    return;
                }
                if (!BedrockItemLockPolicy.canDrop(currentItem)) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                    return;
                }

                BedrockItem predictedAmount = currentItem.copy();
                if (action == PlayerActionAction.DROP_ITEM) {
                    predictedAmount.setAmount(1); // Drop a single item
                }

                BedrockItem predictedToItem = currentItem.copy();
                if (action == PlayerActionAction.DROP_ITEM) {
                    if (predictedToItem.amount() > 1) {
                        predictedToItem.setAmount(currentItem.amount() - 1);
                    } else {
                        predictedToItem = BedrockItem.empty();
                    }
                } else {
                    predictedToItem = BedrockItem.empty();
                }

                final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper.user());

                BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                        0,
                        null,
                        List.of(
                                new InventoryActionData(
                                        new InventorySource(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                                        0,
                                        BedrockItem.empty(),
                                        predictedAmount
                                ),
                                new InventoryActionData(
                                        new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                                        inventoryTracker.getInventoryContainer().getSelectedHotbarSlot(),
                                        currentItem,
                                        predictedToItem
                                )
                        ),

                        ComplexInventoryTransaction_Type.NormalTransaction,
                        new InventoryTransactionData.NormalTransactionData()
                );

                transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);

                transactionPacket.sendToServer(BedrockProtocol.class);

                // Update mirror optimistically so rapid consecutive drops read the correct count
                inventoryTracker.getInventoryContainer().setItemSilent(
                        inventoryTracker.getInventoryContainer().getSelectedHotbarSlot(),
                        predictedToItem
                );

                //TODO: I think vanilla client also sends these and im not sure what their purposes are but it works without them
                    /*final PacketWrapper interactPacket = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());

                    interactPacket.write(Types.BYTE, (byte) InteractPacket_Action.InteractUpdate.getValue());
                    interactPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                    interactPacket.write(BedrockTypes.POSITION_3F, new Position3f(0, 0, 0));

                    interactPacket.sendToServer(BedrockProtocol.class);

                    final PacketWrapper mobEquipPacket = PacketWrapper.create(ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper.user());

                    mobEquipPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                    mobEquipPacket.write(itemRewriter.newItemType(), predictedToItem);
                    mobEquipPacket.write(Types.BYTE, inventoryTracker.getInventoryContainer().getSelectedHotbarSlot());
                    mobEquipPacket.write(Types.BYTE, inventoryTracker.getInventoryContainer().getSelectedHotbarSlot());
                    mobEquipPacket.write(Types.BYTE, (byte) ContainerID.CONTAINER_ID_INVENTORY.getValue());

                    mobEquipPacket.sendToServer(BedrockProtocol.class);*/
            }


        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CLIENT_TICK_END, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (clientPlayer.itemUseSnapshot() == null) {
                return;
            }

            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(inventoryTracker, clientPlayer.usingItemHand());
            final BedrockItem selectedItem = handContext.item();
            if (inventoryTracker.isAnyScreenOpen() || !matchesUseItem(handContext, clientPlayer) || !isContinuousUseItem(itemRewriter, selectedItem)) {
                cancelUsingItem(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), clientPlayer);
                return;
            }

            if (!clientPlayer.isUsingItem()) {
                return;
            }

            final ItemReleaseInventoryTransaction_ActionType actionType = releaseActionForItem(itemRewriter, selectedItem, clientPlayer.usingItemTicks());
            if (isCrossbow(itemRewriter, selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_AUTO_FINISH_TICKS) {
                if (clientPlayer.isCrossbowChargeFinishSent()) {
                    return;
                }
                finishCrossbowCharge(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), handContext, clientPlayer);
                clientPlayer.setCrossbowChargeFinishSent(true);
                return;
            }
            if (actionType == ItemReleaseInventoryTransaction_ActionType.Use) {
                finishConsumableUse(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), clientPlayer);
                clientPlayer.setUsingItem(false);
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.USE_ITEM, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);

            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand
            final int sequence = wrapper.read(Types.VAR_INT); // sequence
            final float yaw = wrapper.read(Types.FLOAT); // yaw
            final float pitch = wrapper.read(Types.FLOAT); // pitch

            // Mark as using item and send StartUsingItem flag in the current tick's PlayerAuthInput
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            clientPlayer.swingSourceTracker().recordUse(hand, ActorSwingSource.UseItem);
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(inventoryTracker, hand);
            final BedrockItem selectedItem = handContext.item();
            if (isContinuousUseItem(itemRewriter, selectedItem)) {
                clientPlayer.startUsingItem(handContext.hand(), handContext.containerId(), handContext.containerSlot(), handContext.transactionHotbarSlot(), selectedItem);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);
                clientPlayer.setAuthInputItemInteraction(createUseItemTransaction(handContext, clientPlayer));
                if (shouldSendStandaloneUseTransaction(itemRewriter, selectedItem)) {
                    sendUseItemTransaction(wrapper.user(), inventoryTransactionRewriter, handContext, clientPlayer);
                }
                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            // Empty bucket / glass bottle on a fluid: Java sends a plain USE_ITEM (click air) and relies on a
            // server-side SOURCE_ONLY ray trace, which a client authoritative Bedrock server (e.g. Nukkit's
            // ItemBucket/ItemGlassBottle, which only implement onActivate) never performs. Resolve the targeted
            // fluid source here and translate to a CLICK_BLOCK ItemUseTransaction like a real Bedrock client.
            final Set<Fluid> acceptedFluids = fluidInteractionItem(itemRewriter, selectedItem);
            if (acceptedFluids != null) {
                final FluidHitResult hit = raytraceFluidSource(wrapper.user(), clientPlayer, yaw, pitch, acceptedFluids);
                if (hit != null) {
                    wrapper.cancel();
                    // insideBlock=true so the deferred ack is keyed on the fluid block itself (water/lava -> air),
                    // which is exactly where the server's block update lands when the fluid is picked up.
                    sendItemUseOnBlock(wrapper.user(), clientPlayer, handContext, inventoryTransactionRewriter, wrapper.user().get(ChunkTracker.class), hit.pos(), hit.faceInt(), hit.face(), hit.clickPosition(), true, sequence);
                    return;
                }
                // No fluid source hit: fall through to the normal click-air use (Java would also no-op here).
            }

            if (isChargedCrossbow(itemRewriter, selectedItem) && handContext.isMainHand()) {
                sendSelectedHotbarSlot(wrapper.user(), handContext, clientPlayer);
            }
            wrapper.write(inventoryTransactionRewriter.getInventoryTransactionType(), createUseItemTransaction(handContext, clientPlayer));
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });

        // Handle COMPLETED_USING_ITEM from server (sent when item use completes, e.g. eating)
        protocol.registerClientbound(ClientboundBedrockPackets.COMPLETED_USING_ITEM, null, wrapper -> {
            wrapper.cancel();
            wrapper.user().get(EntityTracker.class).getClientPlayer().setUsingItem(false);
        });

        protocol.registerServerbound(ServerboundPackets26_1.USE_ITEM_ON, null, wrapper -> {
            wrapper.cancel();

            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);

            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand

            BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // block position
            int faceInt = wrapper.read(Types.UNSIGNED_BYTE); // face
            Direction direction = Direction.getFromVerticalId(faceInt);
            if (direction == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown block face id: " + faceInt);
                return;
            }
            BlockFace face = direction.blockFace();
            Position3f clickPosition = new Position3f(
                    wrapper.read(Types.FLOAT), // x
                    wrapper.read(Types.FLOAT), // y
                    wrapper.read(Types.FLOAT)  // z
            );
            boolean insideBlock = wrapper.read(Types.BOOLEAN); // inside block
            wrapper.read(Types.BOOLEAN); // world border, this doesn't exist on Bedrock.

            // Defer block changed ack until the server confirms via UPDATE_BLOCK.
            // Sending ack immediately would clear the Java client's prediction before any BLOCK_UPDATE arrives,
            // causing the placed block to flicker (disappear then reappear).
            final int sequence = wrapper.read(Types.VAR_INT);

            final ItemUseHandContext handContext = ItemUseHandContext.resolve(inventoryTracker, hand);
            final BedrockItem selectedItem = handContext.item();
            if (isContinuousUseItem(wrapper.user().get(ItemRewriter.class), selectedItem)) {
                // 放置型食物失败时 Java 不再发送 USE_ITEM，等待服务端确认是否开始食用。
                clientPlayer.prepareBlockItemUse(handContext.hand(), handContext.containerId(), handContext.containerSlot(), handContext.transactionHotbarSlot(), selectedItem);
            } else {
                clientPlayer.setUsingItem(false);
            }
            sendItemUseOnBlock(wrapper.user(), clientPlayer, handContext, inventoryTransactionRewriter, chunkTracker, position, faceInt, face, clickPosition, insideBlock, sequence);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_TRANSACTION, null, wrapper -> {
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            wrapper.cancel();
            BedrockInventoryTransaction inventoryTransaction = wrapper.read(inventoryTransactionRewriter.getInventoryTransactionType());

            if (inventoryTransaction.legacyRequestId() != 0) {
                // Ignore legacy inventory transactions for now
                return;
            }

            if (inventoryTransaction.actions() != null && !inventoryTransaction.actions().isEmpty()) {
                for (InventoryActionData action : inventoryTransaction.actions()) {
                    if (action.source().type() == InventorySourceType.ContainerInventory) {
                        Container container = inventoryTracker.getContainerClientbound((byte) action.source().containerId(), null, null);

                        if (container != null) {
                            container.setItem(action.slot(), action.toItem());
                            PacketFactory.sendJavaContainerSetContent(wrapper.user(),  container);
                        } else {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received inventory action for unknown container ID: " + action.source().containerId());
                        }
                    }
                }
            }

            switch (inventoryTransaction.transactionType()) {
                case NormalTransaction -> {
                    break; // Nothing to do here for now
                }
                default -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unsupported inventory transaction type: " + inventoryTransaction.transactionType());
                }
            }
        });

        protocol.registerClientbound(ClientboundBedrockPackets.MAP_ITEM_DATA, ClientboundPackets26_1.MAP_ITEM_DATA, wrapper -> {
            MapTracker mapTracker = wrapper.user().get(MapTracker.class);

            final long mapId = wrapper.read(BedrockTypes.VAR_LONG); // map id
            final int typeFlags = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // type flags
            final byte dimension = wrapper.read(Types.BYTE); // dimension
            final boolean locked = wrapper.read(Types.BOOLEAN); // locked
            final BlockPosition origin = wrapper.read(BedrockTypes.BLOCK_POSITION); // origin

            final LongList trackedEntities = new LongArrayList();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.Creation.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < length; i++) {
                    trackedEntities.add(wrapper.read(BedrockTypes.VAR_LONG).longValue());
                }
            }

            byte scale = 0;
            if ((typeFlags & MAP_FLAGS_ALL) != 0) {
                scale = wrapper.read(Types.BYTE); // scale
            }

            final List<MapDecoration> decorations = new ArrayList<>();
            final List<MapTrackedObject> trackedObjects = new ArrayList<>();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < length; i++) {
                    MapTrackedObject.Type objectType = MapTrackedObject.Type.values()[wrapper.read(BedrockTypes.INT_LE)]; //TODO: Error logging
                    switch (objectType) {
                        case BLOCK:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.BLOCK_POSITION)));
                            break;
                        case ENTITY:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.VAR_LONG)));
                            break;
                    }
                }

                final int decorLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                for (int i = 0; i < decorLength; i++) {
                    final byte iconType = wrapper.read(Types.BYTE);
                    final byte rotation = wrapper.read(Types.BYTE);
                    final byte x = wrapper.read(Types.BYTE);
                    final byte y = wrapper.read(Types.BYTE);
                    final String name = wrapper.read(BedrockTypes.STRING); // name
                    final int color = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // color

                    decorations.add(new MapDecoration(iconType, rotation, x, y, name, color));
                }
            }

            int width = 0;
            int height = 0;
            int xOffset = 0;
            int yOffset = 0;
            int[] colors = new int[0];
            if ((typeFlags & ClientboundMapItemDataPacket_Type.TextureUpdate.getValue()) != 0) {
                width = wrapper.read(BedrockTypes.VAR_INT); // width
                height = wrapper.read(BedrockTypes.VAR_INT); // height
                xOffset = wrapper.read(BedrockTypes.VAR_INT); // x offset
                yOffset = wrapper.read(BedrockTypes.VAR_INT); // y offset

                final int colorsLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // colors length
                colors = new int[colorsLength];
                for (int i = 0; i < colorsLength; i++) {
                    colors[i] = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                }
            }

            //TODO: Clean this up
            int nextJavaId = mapTracker.getNextMapId();
            if ((typeFlags & ClientboundMapItemDataPacket_Type.Creation.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.getTrackedEntities().clear();
                    existingMap.getTrackedEntities().addAll(trackedEntities);
                } else {
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            trackedObjects,
                            decorations,
                            width,
                            height,
                            xOffset,
                            yOffset,
                            colors,
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }
            if ((typeFlags & ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.getTrackedObjects().clear();
                    existingMap.getTrackedObjects().addAll(trackedObjects);
                    existingMap.getDecorations().clear();
                    existingMap.getDecorations().addAll(decorations);
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map decoration update for unknown map id: " + mapId);
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            trackedObjects,
                            decorations,
                            0,
                            0,
                            0,
                            0,
                            new int[0],
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }
            if ((typeFlags & ClientboundMapItemDataPacket_Type.TextureUpdate.getValue()) != 0) {
                MapObject existingMap = mapTracker.getMapObjects().get(mapId);
                if (existingMap != null) {
                    existingMap.setWidth(width);
                    existingMap.setHeight(height);
                    existingMap.setXOffset(xOffset);
                    existingMap.setYOffset(yOffset);
                    existingMap.setColors(colors);
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map texture update for unknown map id: " + mapId);
                    MapObject mapObject = new MapObject(
                            mapId,
                            dimension,
                            locked,
                            origin,
                            trackedEntities,
                            scale,
                            new ArrayList<>(),
                            new ArrayList<>(),
                            width,
                            height,
                            xOffset,
                            yOffset,
                            colors,
                            nextJavaId
                    );
                    mapTracker.getMapObjects().put(mapId, mapObject);
                }
            }

            MapObject mapObject = mapTracker.getMapObjects().get(mapId);
            if (mapObject == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received map item data for unknown map id: " + mapId);
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, mapObject.getJavaId()); // map id
            wrapper.write(Types.BYTE, mapObject.getScale()); // scale
            wrapper.write(Types.BOOLEAN, mapObject.isLocked()); // locked

            writeMapIcons(wrapper, mapObject.getDecorations()); // Icons (Prefixed Optional)
            wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getWidth()); // width
            if (mapObject.getWidth() > 0) {
                wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getHeight()); // height
                wrapper.write(Types.BYTE, (byte) mapObject.getXOffset()); // xOffset
                wrapper.write(Types.BYTE, (byte) mapObject.getYOffset()); // yOffset

                wrapper.write(Types.VAR_INT, mapObject.getColors().length);
                final short[] javaColors = ViaBedrock.getConfig().shouldDitherMaps()
                        ? JavaMapPaletteUtil.convertToJavaPaletteDithered(mapObject.getColors(), mapObject.getWidth(), mapObject.getHeight())
                        : JavaMapPaletteUtil.convertToJavaPalette(mapObject.getColors());
                for (short color : javaColors) {
                    wrapper.write(Types.UNSIGNED_BYTE, color);
                }

            } else {
                //ViaBedrock.getPlatform().getLogger().warning("Sent empty map data for map id: " + mapId);
                //TODO: Bedrock requests map data if it doesnt have it, so we need to send something
            }
        });
    }

    public static void registerTasks() {
        Via.getPlatform().runRepeatingSync(new ScriptDebugTextTickTask(), 1L);
        Via.getPlatform().runRepeatingSync(new BlockBreakingProgressTickTask(), 1L);
        Via.getPlatform().runRepeatingSync(new MultilineNametagTickTask(), 1L);
    }

    // Maps a Bedrock MapDecoration_Type value (index) to a Java minecraft:map_decoration_type registry id.
    // The Java registry is a built-in (non-synced) registry, so the indices follow vanilla 1.21 order. -1 = don't draw.
    private static final int[] BEDROCK_TO_JAVA_MAP_DECORATION = buildMapDecorationTable();

    private static int[] buildMapDecorationTable() {
        final int[] table = new int[25];
        table[0] = 0;    // MarkerWhite      -> player
        table[1] = 1;    // MarkerGreen      -> frame
        table[2] = 2;    // MarkerRed        -> red_marker
        table[3] = 3;    // MarkerBlue       -> blue_marker
        table[4] = 4;    // XWhite           -> target_x
        table[5] = 5;    // TriangleRed      -> target_point
        table[6] = 6;    // SquareWhite      -> player_off_map
        table[7] = 7;    // MarkerSign       -> player_off_limits
        table[8] = 16;   // MarkerPink       -> banner_pink
        table[9] = 11;   // MarkerOrange     -> banner_orange
        table[10] = 14;  // MarkerYellow     -> banner_yellow
        table[11] = 19;  // MarkerTeal       -> banner_cyan
        table[12] = 23;  // TriangleGreen    -> banner_green
        table[13] = 7;   // SmallSquareWhite -> player_off_limits
        table[14] = 8;   // Mansion          -> mansion
        table[15] = 9;   // Monument         -> monument
        table[16] = -1;  // NoDraw           -> (skip)
        table[17] = 27;  // VillageDesert    -> village_desert
        table[18] = 28;  // VillagePlains    -> village_plains
        table[19] = 29;  // VillageSavanna   -> village_savanna
        table[20] = 30;  // VillageSnowy     -> village_snowy
        table[21] = 31;  // VillageTaiga     -> village_taiga
        table[22] = 32;  // JungleTemple     -> jungle_temple
        table[23] = 33;  // WitchHut         -> swamp_hut
        table[24] = 34;  // TrialChambers    -> trial_chambers
        return table;
    }

    private static int javaMapDecorationType(final int bedrockType) {
        if (bedrockType < 0 || bedrockType >= BEDROCK_TO_JAVA_MAP_DECORATION.length) {
            return 0; // unknown -> fall back to player marker rather than dropping silently
        }
        return BEDROCK_TO_JAVA_MAP_DECORATION[bedrockType];
    }

    private static void writeMapIcons(final PacketWrapper wrapper, final List<MapDecoration> decorations) {
        final List<MapDecoration> visible = new ArrayList<>();
        for (final MapDecoration decoration : decorations) {
            if (javaMapDecorationType(decoration.image()) != -1) {
                visible.add(decoration);
            }
        }

        if (visible.isEmpty()) {
            wrapper.write(Types.BOOLEAN, false); // no icons
            return;
        }

        wrapper.write(Types.BOOLEAN, true); // has icons
        wrapper.write(Types.VAR_INT, visible.size());
        for (final MapDecoration decoration : visible) {
            wrapper.write(Types.VAR_INT, javaMapDecorationType(decoration.image())); // type
            wrapper.write(Types.BYTE, (byte) decoration.xOffset()); // x
            wrapper.write(Types.BYTE, (byte) decoration.yOffset()); // z
            wrapper.write(Types.BYTE, (byte) (decoration.rotation() & 15)); // rotation
            wrapper.write(Types.BOOLEAN, false); // no custom display name
        }
    }

    public static void registerStorages(final UserConnection user) {
        user.put(new InventoryTransactionRewriter(user));
        user.put(new MapTracker(user));
        user.put(new MultilineNametagTracker(user));
        user.put(new ScriptDebugTextTracker(user));
        user.put(new BlockPlacementAckTracker(user));

        // Dispatch to feature modules
        for (final FeatureModule module : MODULES) {
            try {
                module.onStorageRegistration(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onStorageRegistration", e);
            }
        }
    }
}
