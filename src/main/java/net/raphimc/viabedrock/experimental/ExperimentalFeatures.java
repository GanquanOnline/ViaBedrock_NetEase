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
import io.netty.channel.Channel;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity.ItemUseSnapshot;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.InstantBreakBlocks;
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
import net.raphimc.viabedrock.experimental.block.CustomBlockDisplayTracker;
import net.raphimc.viabedrock.experimental.blockbreak.BlockBreakingProgressModule;
import net.raphimc.viabedrock.experimental.camera.CameraModule;
import net.raphimc.viabedrock.experimental.inventory.BedrockItemLockPolicy;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.experimental.inventory.CraftingDataModule;
import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.experimental.dimension.AlternateDimensionModule;
import net.raphimc.viabedrock.experimental.entity.CustomEntityTypeResolver;
import net.raphimc.viabedrock.experimental.npc.NpcDialogueModule;
import net.raphimc.viabedrock.experimental.modinterface.ModUIClientModule;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.rewriter.EntityMetadataRewriter;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import net.raphimc.viabedrock.experimental.riding.RidingModule;
import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;
import net.raphimc.viabedrock.experimental.tablist.TabListLatencyModule;
import net.raphimc.viabedrock.experimental.storage.BlockPlacementAckTracker;
import net.raphimc.viabedrock.experimental.storage.MapTracker;
import net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker;
import net.raphimc.viabedrock.experimental.storage.ScriptDebugTextTracker;
import net.raphimc.viabedrock.experimental.task.BlockBreakingProgressTickTask;
import net.raphimc.viabedrock.experimental.task.MapInfoRequestTickTask;
import net.raphimc.viabedrock.experimental.task.MultilineNametagTickTask;
import net.raphimc.viabedrock.experimental.task.ScriptDebugTextTickTask;
import net.raphimc.viabedrock.experimental.util.JavaMapPaletteUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.packet.ClientPlayerPackets;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ItemUseInventoryTransaction_TriggerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.EntityInteractionPacketSender;
import net.raphimc.viabedrock.protocol.packet.ItemStackResponseLayout;
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

    public static void dispatchPlayerIdentitiesUpdated(final UserConnection user, final Map<UUID, PlayerIdentity> identities) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onPlayerIdentitiesUpdated(user, identities);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onPlayerIdentitiesUpdated", e);
            }
        }
    }

    public static void dispatchDimensionChange(final UserConnection user) {
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker != null && entityTracker.getClientPlayer() != null) {
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            clientPlayer.deferredEntityActions().clear();
            clientPlayer.setUsingItem(false);
            restorePromotedOffhand(user, clientPlayer);
        }
        for (final FeatureModule module : MODULES) {
            try {
                module.onDimensionChange(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onDimensionChange", e);
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

    public static void dispatchJavaResourcePackLoaded(final UserConnection user) {
        for (final FeatureModule module : MODULES) {
            try {
                module.onJavaResourcePackLoaded(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onJavaResourcePackLoaded", e);
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

        // Resolve lock-policy helpers at startup so a later drop/click cannot fail with NoClassDefFoundError.
        canDropLockedItem(BedrockItem.empty());

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
        return ItemUseSemantics.releaseAction(identifier, itemTags, itemRewriter.itemUseDefinition(item), usingTicks, ViaBedrock.getConfig().shouldEmulateNetEaseClient());
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

    private static boolean isTrident(final ItemRewriter itemRewriter, final BedrockItem item) {
        return "minecraft:trident".equals(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isSpear(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.isSpear(identifier, itemTags);
    }

    private static boolean isShield(final ItemRewriter itemRewriter, final BedrockItem item) {
        return ItemUseSemantics.isShield(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isHoldToUseWeapon(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        return "minecraft:crossbow".equals(identifier)
                || "minecraft:trident".equals(identifier)
                || isSpear(itemRewriter, item)
                || "minecraft:shield".equals(identifier)
                || "minecraft:spyglass".equals(identifier);
    }

    private static boolean isSpyglass(final ItemRewriter itemRewriter, final BedrockItem item) {
        return ItemUseSemantics.isSpyglass(itemRewriter.bedrockIdentifier(item));
    }

    private static boolean isChargedCrossbow(final BedrockItem item) {
        if (item == null || item.tag() == null) {
            return false;
        }
        return ItemUseSemantics.chargedCrossbowUsesMotTag(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                item.tag().get("chargedItem") != null,
                item.tag().get("ChargedProjectiles") != null
        );
    }

    private static boolean isConsumableUseItem(final ItemRewriter itemRewriter, final BedrockItem item) {
        final String identifier = itemRewriter.bedrockIdentifier(item);
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.isConsumableUseItem(identifier, itemTags, itemRewriter.itemUseDefinition(item));
    }

    private static boolean shouldSendStandaloneUseTransaction(final ItemRewriter itemRewriter, final BedrockItem item) {
        if (ItemUseSemantics.emulateShieldAsSneak(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), isShield(itemRewriter, item))) {
            return false;
        }
        return ItemUseSemantics.needsStandaloneUseTransaction(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                isConsumableUseItem(itemRewriter, item),
                isBow(itemRewriter, item),
                isCrossbow(itemRewriter, item),
                isTrident(itemRewriter, item),
                isSpear(itemRewriter, item),
                isSpyglass(itemRewriter, item)
        );
    }

    private static boolean shouldSkipMobEquipmentBeforeUse(final ItemRewriter itemRewriter, final BedrockItem item) {
        return ItemUseSemantics.skipMobEquipmentBeforeUse(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                isConsumableUseItem(itemRewriter, item)
        );
    }

    private static boolean shouldAttachAuthInputItemInteraction(final ItemRewriter itemRewriter, final BedrockItem item) {
        return ItemUseSemantics.attachAuthInputItemInteraction(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                isConsumableUseItem(itemRewriter, item),
                isBow(itemRewriter, item),
                isHoldToUseWeapon(itemRewriter, item)
        );
    }

    private static void beginContinuousItemUse(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter,
                                               final ClientPlayerEntity clientPlayer, final ItemRewriter itemRewriter,
                                               final ItemUseHandContext handContext) {
        final BedrockItem selectedItem = handContext.item();
        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        if (ItemUseSemantics.emulateShieldAsSneak(emulateNetEase, isShield(itemRewriter, selectedItem))) {
            if (ItemUseSemantics.ignoreDuplicateUseStart(clientPlayer.isUsingItem(), matchesUseItem(handContext, clientPlayer))) {
                return;
            }
            clientPlayer.startUsingItem(handContext.hand(), handContext.containerId(), handContext.containerSlot(), handContext.transactionHotbarSlot(), selectedItem);
            if (!clientPlayer.isSneaking()) {
                clientPlayer.setSneaking(true);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartSneaking, PlayerAuthInputPacket_InputData.SneakPressedRaw);
                clientPlayer.setShieldSneakEmulated(true);
            }
            // MOT processes START_SPRINTING before START_SNEAKING and does not
            // clear an already-sprinting player. Java shield-block is standing;
            // emit StopSprinting so MOT is not sprinting+sneaking.
            if (ItemUseSemantics.stopSprintingOnShieldSneakStart(emulateNetEase, true, clientPlayer.isSprinting())) {
                clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.StartSprinting);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StopSprinting);
                clientPlayer.setSprinting(false);
            }
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakDown, PlayerAuthInputPacket_InputData.Sneaking, PlayerAuthInputPacket_InputData.WantDown, PlayerAuthInputPacket_InputData.SneakCurrentRaw);
            return;
        }
        if (!ItemUseSemantics.canStartConsumable(
                emulateNetEase,
                itemRewriter.bedrockIdentifier(selectedItem),
                isConsumableUseItem(itemRewriter, selectedItem),
                clientPlayer.javaGameMode() == GameMode.CREATIVE,
                isHungry(clientPlayer)
        )) {
            PacketFactory.sendJavaContainerSetContent(user, user.get(InventoryTracker.class).getInventoryContainer());
            syncJavaUsingItem(user, clientPlayer);
            return;
        }
        if (!ItemUseSemantics.canStartBow(
                emulateNetEase,
                isBow(itemRewriter, selectedItem),
                clientPlayer.javaGameMode() == GameMode.CREATIVE,
                hasRegularArrow(user, itemRewriter)
        )) {
            PacketFactory.sendJavaContainerSetContent(user, user.get(InventoryTracker.class).getInventoryContainer());
            syncJavaUsingItem(user, clientPlayer);
            return;
        }
        if (ItemUseSemantics.ignoreDuplicateUseStart(clientPlayer.isUsingItem(), matchesUseItem(handContext, clientPlayer))) {
            return;
        }
        final ItemUseHandContext motHandContext = motContextForOffhandUse(user, clientPlayer, handContext, itemRewriter);
        if (motHandContext == null) {
            PacketFactory.sendJavaContainerSetContent(user, user.get(InventoryTracker.class).getInventoryContainer());
            PacketFactory.sendJavaContainerSetContent(user, user.get(InventoryTracker.class).getOffhandContainer());
            syncJavaUsingItem(user, clientPlayer);
            return;
        }
        // Keep the Java hand for the use animation. MOT slots come from the
        // silent F-swap so CLICK_AIR / RELEASE see the stack in the main hand.
        clientPlayer.startUsingItem(
                handContext.hand(),
                motHandContext.containerId(),
                motHandContext.containerSlot(),
                motHandContext.transactionHotbarSlot(),
                selectedItem);
        clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);
        if (shouldAttachAuthInputItemInteraction(itemRewriter, selectedItem)) {
            clientPlayer.setAuthInputItemInteraction(createUseItemTransaction(motHandContext, clientPlayer));
        }
        if (ItemUseSemantics.suppressStartSprintingWhileUsingItem(emulateNetEase, true)) {
            clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.StartSprinting);
            if (clientPlayer.isSprinting()) {
                // MOT START_SPRINTING sets sprinting=true and usingItem=false. Java eat/draw
                // cancels sprint; dropping StartSprinting is not enough — emit StopSprinting
                // so MOT/GanAC SprintCheck sees the use-start edge.
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StopSprinting);
            }
            clientPlayer.setSprinting(false);
        }
        if (shouldSendStandaloneUseTransaction(itemRewriter, selectedItem)) {
            sendUseItemTransaction(user, inventoryTransactionRewriter, motHandContext, clientPlayer);
        }
    }

    private static boolean isHungry(final ClientPlayerEntity clientPlayer) {
        final EntityAttribute hunger = clientPlayer.attributes().get("minecraft:player.hunger");
        return hunger != null && hunger.computeClampedValue() < hunger.computeMaxValue();
    }

    private static boolean hasRegularArrow(final UserConnection user, final ItemRewriter itemRewriter) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker == null) {
            return false;
        }
        for (final BedrockItem item : tracker.getInventoryContainer().getItems()) {
            if (item != null && !item.isEmpty() && ItemUseSemantics.isRegularArrow(itemRewriter.bedrockIdentifier(item))) {
                return true;
            }
        }
        final BedrockItem offhand = tracker.getOffhandContainer().getItem(0);
        return offhand != null && !offhand.isEmpty() && ItemUseSemantics.isRegularArrow(itemRewriter.bedrockIdentifier(offhand));
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

    // --- Java USE_ITEM air-click items that MOT only handles as CLICK_BLOCK ---

    private static ItemUseAirClickTarget.WorldView airClickWorld(final UserConnection user) {
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final BlockStateRewriter blockStateRewriter = user.get(BlockStateRewriter.class);
        return new ItemUseAirClickTarget.WorldView() {
            @Override
            public int blockStateId(final int layer, final BlockPosition position) {
                return chunkTracker.getBlockState(layer, position);
            }

            @Override
            public BlockState blockState(final int bedrockBlockStateId) {
                return blockStateRewriter.blockState(bedrockBlockStateId);
            }

            @Override
            public int airId() {
                return chunkTracker.bedrockAirId();
            }
        };
    }

    private static double airClickReach(final ClientPlayerEntity clientPlayer) {
        return clientPlayer.javaGameMode() == GameMode.CREATIVE
                ? ItemUseAirClickTarget.REACH_CREATIVE
                : ItemUseAirClickTarget.REACH_SURVIVAL;
    }

    private static boolean trySendAirClickAsBlockUse(final UserConnection user, final ClientPlayerEntity clientPlayer,
                                                     final ItemUseHandContext handContext, final ItemRewriter itemRewriter,
                                                     final InventoryTransactionRewriter inventoryTransactionRewriter,
                                                     final float yaw, final float pitch, final int sequence) {
        final String identifier = itemRewriter.bedrockIdentifier(handContext.item());
        if (identifier == null) {
            return false;
        }
        final Set<String> itemTags = BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier);
        final ItemUseAirClickTarget.WorldView world = airClickWorld(user);
        final double reach = airClickReach(clientPlayer);
        ItemUseAirClickTarget.Hit hit = null;
        final Set<ItemUseAirClickTarget.Fluid> pickupFluids = ItemUseAirClickTarget.pickupFluids(identifier);
        if (pickupFluids != null) {
            hit = ItemUseAirClickTarget.raytraceFluidSource(world, clientPlayer.position(), yaw, pitch, reach, pickupFluids);
        }
        if (hit == null) {
            hit = ItemUseAirClickTarget.raytracePlaceClick(world, clientPlayer.position(), yaw, pitch, reach, identifier, itemTags);
        }
        if (hit == null) {
            return false;
        }
        sendItemUseOnBlock(
                user,
                clientPlayer,
                handContext,
                inventoryTransactionRewriter,
                user.get(ChunkTracker.class),
                hit.pos(),
                hit.faceInt(),
                hit.face(),
                hit.clickPosition(),
                hit.insideBlock(),
                sequence
        );
        return true;
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
        final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                user.get(InventoryTracker.class), hand, clientPlayer.isOffhandPromoted());
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

    /**
     * Custom cubes are shown as a Java placeholder block plus an item-display overlay.
     * If the display still intercepts a right-click, translate it into the Bedrock
     * block use the server expects instead of dropping the packet.
     */
    public static boolean tryHandleCustomBlockOverlayInteract(final UserConnection user, final int javaEntityId, final InteractionHand hand) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return false;
        }
        final CustomBlockDisplayTracker overlay = user.get(CustomBlockDisplayTracker.class);
        if (overlay == null) {
            return false;
        }
        final BlockPosition position = overlay.getOverlayPosition(javaEntityId);
        if (position == null) {
            return false;
        }

        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        final Direction direction = CustomBlockDisplayTracker.facingFromLook(clientPlayer.rotation());
        sendItemUseOnBlock(
                user,
                clientPlayer,
                ItemUseHandContext.resolve(user.get(InventoryTracker.class), hand, clientPlayer.isOffhandPromoted()),
                user.get(InventoryTransactionRewriter.class),
                user.get(ChunkTracker.class),
                position,
                direction.verticalId(),
                direction.blockFace(),
                new Position3f(0.5F, 0.5F, 0.5F),
                false,
                0);
        return true;
    }

    /**
     * Left-clicking an overlay display must start the Bedrock block break at that
     * position. Returns true when the attack was consumed as a block action.
     */
    public static boolean tryHandleCustomBlockOverlayAttack(final UserConnection user, final int javaEntityId) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return false;
        }
        final CustomBlockDisplayTracker overlay = user.get(CustomBlockDisplayTracker.class);
        if (overlay == null) {
            return false;
        }
        final BlockPosition position = overlay.getOverlayPosition(javaEntityId);
        if (position == null) {
            return false;
        }
        startFakeEntityBlockBreak(user, position, CustomBlockDisplayTracker.facingFromLook(user.get(EntityTracker.class).getClientPlayer().rotation()));
        return true;
    }

    /**
     * Java left-clicking a fake item-frame entity must become Bedrock
     * {@code StartDestroyBlock}. Nukkit's {@code BlockItemFrame.onTouch} drops the
     * displayed item on LEFT_CLICK_BLOCK and only then lets a later destroy break
     * the frame. Cancelling ATTACK with no translation swallowed both.
     */
    public static boolean tryHandleItemFrameAttack(final UserConnection user, final int javaEntityId) {
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return false;
        }
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker == null) {
            return false;
        }
        final EntityTracker.ItemFrameInfo info = entityTracker.getItemFrameInfo(javaEntityId);
        if (info == null) {
            return false;
        }
        final Direction direction = Direction.getFromVerticalId(info.facingDirection(), Direction.NORTH);
        startFakeEntityBlockBreak(user, info.position(), direction);
        return true;
    }

    public static BlockPosition resolveFakeBlockEntityPickPosition(final UserConnection user, final int javaEntityId) {
        final CustomBlockDisplayTracker overlay = user.get(CustomBlockDisplayTracker.class);
        if (overlay != null) {
            final BlockPosition overlayPosition = overlay.getOverlayPosition(javaEntityId);
            if (overlayPosition != null) {
                return overlayPosition;
            }
        }
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker == null) {
            return null;
        }
        final EntityTracker.ItemFrameInfo info = entityTracker.getItemFrameInfo(javaEntityId);
        return info != null ? info.position() : null;
    }

    private static void startFakeEntityBlockBreak(final UserConnection user, final BlockPosition position, final Direction direction) {
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        clientPlayer.sendSwingPacketToServer();
        clientPlayer.cancelNextSwingPacket();
        clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(
                PlayerActionType.StartDestroyBlock, position, direction.ordinal()));
        if (clientPlayer.javaGameMode() == GameMode.CREATIVE) {
            // MOT PredictDestroy already aborts then completes. A trailing Abort
            // is stored in MOT's EnumMap before Predict, so GanAC/MOT would see
            // Abort first and drop the break. Mirror finishBlockBreak: Start+Predict.
            clientPlayer.clearDelayedMotBreak();
            for (PlayerActionType type : overlayCreativeBreakActions()) {
                clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(
                        type, position, direction.ordinal()));
            }
        } else if (overlaySurvivalInstantBreak(user, position)) {
            // Survival overlay is Java ATTACK on a fake display — there is no STOP_DESTROY.
            // MOT only finishes on PredictDestroyBlock. Instant (hardness 0) overlays must
            // Start+Predict like creative. Non-instant overlays keep CrackBlock via SWING.
            clientPlayer.clearDelayedMotBreak();
            for (PlayerActionType type : overlayCreativeBreakActions()) {
                clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(
                        type, position, direction.ordinal()));
            }
        } else {
            clientPlayer.setBlockBreakingInfo(new ClientPlayerEntity.BlockBreakingInfo(position, direction));
            ClientPlayerPackets.scheduleDelayedMotBreak(clientPlayer, user.get(ChunkTracker.class), position, direction);
        }
    }

    static boolean overlaySurvivalInstantBreak(final UserConnection user, final BlockPosition position) {
        if (user == null || position == null) {
            return false;
        }
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        if (chunkTracker == null) {
            return false;
        }
        final int javaBlockStateId = chunkTracker.getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        final String javaIdentifier = javaBlockState != null ? javaBlockState.identifier() : null;
        return InstantBreakBlocks.shouldCompleteOnJavaStart(false, javaIdentifier, null, null, null);
    }

    /**
     * MOT PredictDestroy already aborts then completes. Do not append Abort after
     * Predict: MOT EnumMap would iterate Abort first and drop the break.
     */
    static List<PlayerActionType> overlayCreativeBreakActions() {
        return List.of(PlayerActionType.PredictDestroyBlock);
    }

    static List<String> overlayCreativeBreakActionNames() {
        final List<String> names = new ArrayList<>();
        names.add(PlayerActionType.StartDestroyBlock.name());
        for (PlayerActionType type : overlayCreativeBreakActions()) {
            names.add(type.name());
        }
        return names;
    }

    public static boolean tryHandleSwapHands(final UserConnection user) {
        return ViaBedrock.getConfig().shouldEnableExperimentalFeatures()
                && ClientAuthInventoryModule.tryHandleSwapHands(user);
    }

    /**
     * MOT only consumes {@code getItemInHand()}. Promote a Java offhand stack
     * with a silent F-swap so CLICK_AIR / CLICK_BLOCK see it in the main hand.
     * Shield stays sneak-emulation and is not swapped.
     */
    private static ItemUseHandContext motContextForOffhandUse(final UserConnection user, final ClientPlayerEntity clientPlayer,
                                                              final ItemUseHandContext handContext, final ItemRewriter itemRewriter) {
        if (!ItemUseSemantics.promoteOffhandUse(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                !handContext.isMainHand(),
                isShield(itemRewriter, handContext.item()))) {
            return handContext;
        }
        if (handContext.item() == null || handContext.item().isEmpty()) {
            return null;
        }
        return promoteOffhandContext(user, clientPlayer);
    }

    /**
     * MOT transaction type 3 also compares and consumes the selected main-hand
     * stack. Entity interaction allows an empty offhand, so it intentionally does
     * not apply the item-use empty-stack guard above.
     */
    public static ItemUseHandContext motContextForEntityInteraction(final UserConnection user,
                                                                    final ClientPlayerEntity clientPlayer,
                                                                    final InteractionHand hand) {
        final ItemUseHandContext handContext = ItemUseHandContext.resolve(user.get(InventoryTracker.class), hand);
        if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient() || hand == InteractionHand.MAIN_HAND) {
            return handContext;
        }
        if (!ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return null;
        }
        return promoteOffhandContext(user, clientPlayer);
    }

    private static ItemUseHandContext promoteOffhandContext(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (clientPlayer.isOffhandPromotionPending()) {
            return null;
        }
        if (clientPlayer.isOffhandPromoted()) {
            // A new action may reuse a promotion whose previous restore is pending.
            clientPlayer.markOffhandPromoted();
            return ItemUseHandContext.resolve(user.get(InventoryTracker.class), InteractionHand.MAIN_HAND);
        }
        final ClientAuthInventoryModule.SwapHandsResult swap = ClientAuthInventoryModule.tryHandleSwapHandsTracked(user, false);
        if (!swap.submitted()) {
            return null;
        }
        if (swap.requestId() == null) {
            // Legacy TYPE_NORMAL has no ITEM_STACK_RESPONSE ledger. The MOT
            // transaction is already applied, so treat it as immediately promoted.
            clientPlayer.markOffhandPromoted();
            return ItemUseHandContext.resolve(user.get(InventoryTracker.class), InteractionHand.MAIN_HAND);
        }
        clientPlayer.markOffhandPromotionPending(swap.requestId());
        return null;
    }

    public static void restorePromotedOffhand(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (!clientPlayer.scheduleOffhandRestore()) {
            return;
        }

        final Channel channel = user.getChannel();
        if (channel == null) {
            clientPlayer.markOffhandRestoreSubmissionFailed();
            return;
        }

        try {
            channel.eventLoop().execute(() -> {
                if (!clientPlayer.isOffhandRestoreScheduled()) {
                    return;
                }

                final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
                if (inventoryTracker == null) {
                    clientPlayer.markOffhandRestoreSubmissionFailed();
                    return;
                }
                final BedrockItem promotedMainHand = inventoryTracker.getInventoryContainer().getSelectedHotbarItem();
                final BedrockItem promotedOffhand = inventoryTracker.getOffhandContainer().getItem(0);
                // Capture before ClickSimulator/applyMirrorUpdates can replace or mutate the slots.
                clientPlayer.captureOffhandRestoreIdentity(promotedMainHand, promotedOffhand);
                final ClientAuthInventoryModule.SwapHandsResult restore;
                try {
                    restore = ClientAuthInventoryModule.tryHandleSwapHandsTracked(user, false);
                } catch (final RuntimeException e) {
                    clientPlayer.markOffhandRestoreSubmissionFailed();
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to restore promoted offhand", e);
                    return;
                }

                if (!restore.submitted()) {
                    clientPlayer.markOffhandRestoreSubmissionFailed();
                    return;
                }
                if (restore.requestId() != null) {
                    clientPlayer.markOffhandRestoreRequestSent(restore.requestId());
                    return;
                }

                // A legacy transaction or an empty action list has no response ledger entry.
                // Ordered emission is not confirmation, so never drain the deferred queue here.
                abandonOffhandRestoreAndResync(user, clientPlayer, inventoryTracker,
                        "Reverse hand swap emitted no tracked ItemStackRequest response");
            });
        } catch (final RuntimeException e) {
            clientPlayer.markOffhandRestoreSubmissionFailed();
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to schedule promoted offhand restore", e);
        }
    }

    public static void retryPromotedOffhandRestore(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (clientPlayer.shouldRetryOffhandRestore()) {
            restorePromotedOffhand(user, clientPlayer);
        }
    }

    public static void handleItemStackResponse(final UserConnection user,
                                               final ItemStackResponseLayout.DecodedResponse decoded) {
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        if (entityTracker == null || inventoryTracker == null) {
            return;
        }
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer == null) {
            return;
        }
        if (clientPlayer.isOffhandPromotionPending()) {
            handleOffhandPromotionResponse(user, clientPlayer, inventoryTracker, decoded);
            return;
        }
        if (!clientPlayer.isOffhandRestoring()) {
            return;
        }
        final Integer requestId = clientPlayer.offhandRestoreRequestId();
        if (requestId == null || inventoryTracker.peekPendingItemStackRequest(requestId) != null) {
            return;
        }

        final Boolean accepted = restoreResponseAccepted(decoded, requestId);
        if (Boolean.FALSE.equals(accepted)) {
            if (!promotedHandsMatch(inventoryTracker, clientPlayer)) {
                abandonOffhandRestoreAndResync(user, clientPlayer, inventoryTracker,
                        "Rejected reverse hand swap did not roll the mirror back to the promoted layout");
                return;
            }
            if (clientPlayer.markOffhandRestoreRejected(requestId)) {
                retryPromotedOffhandRestore(user, clientPlayer);
            }
            return;
        }
        if (!Boolean.TRUE.equals(accepted) || !restoredHandsMatch(inventoryTracker, clientPlayer)) {
            abandonOffhandRestoreAndResync(user, clientPlayer, inventoryTracker,
                    accepted == null
                            ? "Reverse hand swap left the pending ledger without a matching response"
                            : "Reverse hand swap was accepted but the inventory mirror did not restore");
            return;
        }
        if (clientPlayer.markOffhandRestoreConfirmed(requestId)) {
            scheduleDeferredEntityFlush(user, clientPlayer);
        }
    }

    private static void handleOffhandPromotionResponse(final UserConnection user,
                                                       final ClientPlayerEntity clientPlayer,
                                                       final InventoryTracker inventoryTracker,
                                                       final ItemStackResponseLayout.DecodedResponse decoded) {
        final Integer requestId = clientPlayer.offhandPromotionRequestId();
        if (requestId == null || inventoryTracker.peekPendingItemStackRequest(requestId) != null) {
            return;
        }
        final Boolean accepted = restoreResponseAccepted(decoded, requestId);
        if (Boolean.TRUE.equals(accepted)) {
            if (clientPlayer.markOffhandPromotionConfirmed(requestId)) {
                // Flush queued ATTACK/INTERACT now that MOT has the promoted
                // main-hand. Stay PROMOTED so a follow-up USE_ITEM can still
                // remap OFF_HAND; restore happens when that use ends.
                EntityInteractionPacketSender.flushDeferred(user, clientPlayer);
            }
            return;
        }
        if (Boolean.FALSE.equals(accepted)) {
            clientPlayer.markOffhandPromotionRejected(requestId);
            PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
            PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getOffhandContainer());
            return;
        }
        clientPlayer.markOffhandPromotionRejected(requestId);
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getOffhandContainer());
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                "Offhand promotion left the pending ledger without a matching ItemStackResponse; resynced Java inventory");
    }

    static Boolean restoreResponseAccepted(final ItemStackResponseLayout.DecodedResponse decoded, final int requestId) {
        if (decoded == null) {
            return null;
        }
        for (final ItemStackResponseLayout.DecodedEntry entry : decoded.entries()) {
            if (entry.requestId() == requestId) {
                return entry.ok();
            }
        }
        for (final int decodedRequestId : decoded.requestIds()) {
            if (decodedRequestId == requestId) {
                return !decoded.anyRejected();
            }
        }
        if (!decoded.entries().isEmpty() || decoded.requestIds().length != 0) {
            return null;
        }
        return decoded.entryCount() == 1 ? !decoded.anyRejected() : null;
    }

    private static boolean promotedHandsMatch(final InventoryTracker inventoryTracker,
                                              final ClientPlayerEntity clientPlayer) {
        return clientPlayer.promotedHandsMatch(
                inventoryTracker.getInventoryContainer().getSelectedHotbarItem(),
                inventoryTracker.getOffhandContainer().getItem(0));
    }

    private static boolean restoredHandsMatch(final InventoryTracker inventoryTracker,
                                              final ClientPlayerEntity clientPlayer) {
        return clientPlayer.restoredHandsMatch(
                inventoryTracker.getInventoryContainer().getSelectedHotbarItem(),
                inventoryTracker.getOffhandContainer().getItem(0));
    }

    private static void scheduleDeferredEntityFlush(final UserConnection user,
                                                    final ClientPlayerEntity clientPlayer) {
        final Channel channel = user.getChannel();
        if (channel == null) {
            clientPlayer.deferredEntityActions().clear();
            return;
        }
        try {
            channel.eventLoop().execute(() -> flushDeferredEntityActions(user, clientPlayer));
        } catch (final RuntimeException e) {
            clientPlayer.deferredEntityActions().clear();
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "Failed to schedule deferred entity action flush", e);
        }
    }

    private static void flushDeferredEntityActions(final UserConnection user,
                                                   final ClientPlayerEntity clientPlayer) {
        try {
            clientPlayer.completeOffhandRestoreFlush();
            EntityInteractionPacketSender.flushDeferred(user, clientPlayer);
        } catch (final RuntimeException e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "Failed to flush deferred entity actions", e);
        }
    }

    private static void abandonOffhandRestoreAndResync(final UserConnection user,
                                                       final ClientPlayerEntity clientPlayer,
                                                       final InventoryTracker inventoryTracker,
                                                       final String reason) {
        clientPlayer.deferredEntityActions().clear();
        clientPlayer.abandonOffhandRestore();
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getOffhandContainer());
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, reason + "; discarded deferred hand actions and resynced Java inventory");
    }

    private static void registerHandSensitivePacketGates(final BedrockProtocol protocol) {
        final ServerboundPackets26_1[] packets = {
                ServerboundPackets26_1.CONTAINER_CLICK,
                ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT,
                ServerboundPackets26_1.PICK_ITEM_FROM_BLOCK,
                ServerboundPackets26_1.PICK_ITEM_FROM_ENTITY,
                ServerboundPackets26_1.EDIT_BOOK
        };
        for (final ServerboundPackets26_1 packet : packets) {
            ProtocolUtil.prependServerbound(protocol, packet, ExperimentalFeatures::gateHandSensitiveInventoryPacket);
        }
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLOSE,
                wrapper -> gateHandSensitiveInventoryPacket(wrapper, true));
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.SWING, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker == null ? null : entityTracker.getClientPlayer();
            if (clientPlayer == null || clientPlayer.canProcessHandSensitiveAction(InteractionHand.MAIN_HAND, false, false)) {
                return;
            }
            wrapper.clearPacket();
            wrapper.cancel();
            retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
        });
    }

    private static void gateHandSensitiveInventoryPacket(final PacketWrapper wrapper) {
        gateHandSensitiveInventoryPacket(wrapper, false);
    }

    private static void gateHandSensitiveInventoryPacket(final PacketWrapper wrapper, final boolean cursorReturn) {
        final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker == null ? null : entityTracker.getClientPlayer();
        if (clientPlayer == null || clientPlayer.canProcessHandSensitiveAction(InteractionHand.MAIN_HAND, false, false)) {
            return;
        }
        if (cursorReturn) {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker == null || inventoryTracker.getHudContainer().getItem(0).isEmpty()) {
                return;
            }
        }
        wrapper.clearPacket();
        wrapper.cancel();
        retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
    }

    private static boolean canProcessHandSensitiveAction(final UserConnection user, final PacketWrapper wrapper,
                                                         final ClientPlayerEntity clientPlayer, final InteractionHand hand,
                                                         final boolean allowOriginalOffhandUseContinuation,
                                                         final boolean allowPromotedOffhandReuse, final int sequence) {
        if (clientPlayer.canProcessHandSensitiveAction(
                hand, allowOriginalOffhandUseContinuation, allowPromotedOffhandReuse)) {
            return true;
        }

        wrapper.clearPacket();
        wrapper.cancel();
        if (sequence > 0) {
            PacketFactory.sendJavaBlockChangedAck(user, sequence);
        }
        retryPromotedOffhandRestore(user, clientPlayer);
        return false;
    }

    /**
     * Java inventory is not swapped, so match the original offhand stack by item.
     * MOT packets still use the snapshot's main-hand hotbar slot.
     */
    private static ItemUseHandContext motPacketContext(final ItemUseHandContext javaContext, final ClientPlayerEntity clientPlayer) {
        if (!clientPlayer.isOffhandPromoted() || clientPlayer.itemUseSnapshot() == null) {
            return javaContext;
        }
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        return new ItemUseHandContext(
                InteractionHand.MAIN_HAND,
                snapshot.containerId(),
                snapshot.containerSlot(),
                snapshot.transactionHotbarSlot(),
                snapshot.item().copy()
        );
    }

    /**
     * Builds and sends the CLICK_BLOCK ItemUseTransaction (preceded/followed by Start/StopItemUseOn) that a
     * Bedrock client emits when interacting with a block. Shared by the USE_ITEM_ON handler and the empty
     * bucket / glass bottle fluid branch of USE_ITEM.
     */
    static BlockPosition blockPlacementAckPosition(final BlockPosition clickedPosition, final BlockFace face,
                                                   final boolean clickedBlockReplaceable) {
        return clickedBlockReplaceable ? clickedPosition : clickedPosition.getRelative(face);
    }

    private static void sendItemUseOnBlock(final UserConnection user, final ClientPlayerEntity clientPlayer, final ItemUseHandContext handContext, final InventoryTransactionRewriter inventoryTransactionRewriter, final ChunkTracker chunkTracker, final BlockPosition position, final int faceInt, final BlockFace face, final Position3f clickPosition, final boolean insideBlock, final int sequence) {
        // MOT USE_ITEM CLICK_BLOCK always setUsingItem(false) (Player.java:4528).
        // Java Fabric keeps sending USE_ITEM_ON / item-frame / overlay / empty-bucket
        // while chewing or drawing. Skip the whole CLICK_BLOCK (and 28/29) unless
        // shield-as-sneak still needs to place/activate.
        if (ItemUseSemantics.skipClickBlockWhileUsing(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                clientPlayer.isUsingItem(),
                clientPlayer.isShieldSneakEmulated())) {
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(user, sequence);
            }
            return;
        }

        // Official Bedrock sends StartItemUseOn before CLICK_BLOCK. MOT 860 has no
        // case 28/29; the PlayerAction default calls setUsingItem(false) and would
        // cancel an in-progress eat/draw if Java also looks at a block this tick.
        final boolean sendItemUseOnActions = ItemUseSemantics.sendItemUseOnPlayerActions(ViaBedrock.getConfig().shouldEmulateNetEaseClient());
        if (sendItemUseOnActions) {
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    user,
                    clientPlayer.runtimeId(),
                    PlayerActionType.StartItemUseOn,
                    position,
                    insideBlock ? position : position.getRelative(face),
                    faceInt
            );
        }

        // This is the main packet that the bedrock client use to interact with block.
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);

        final List<InventoryActionData> clickBlockActions = predictedClickBlockActions(handContext, clientPlayer);
        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // legacy request id
                null,
                clickBlockActions,
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
                        ItemUseInventoryTransaction_PredictedResult.Success,
                        (byte) 0 // TODO: client cooldown state
                )
        );
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);
        transactionPacket.sendToServer(BedrockProtocol.class);

        // sequence 0 means there is no Java block-change to acknowledge (for example item frames).
        // Only track operations that actually emitted CLICK_BLOCK; skipped uses were acknowledged above.
        if (sequence > 0) {
            final boolean clickedBlockReplaceable = ItemUseAirClickTarget.isReplaceable(airClickWorld(user), position);
            user.get(BlockPlacementAckTracker.class).addPendingAck(
                    blockPlacementAckPosition(position, face, clickedBlockReplaceable), sequence);
        }

        // Bedrock sends a stop item use on after the transaction packet
        if (sendItemUseOnActions) {
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    user,
                    clientPlayer.runtimeId(),
                    PlayerActionType.StopItemUseOn,
                    position,
                    new BlockPosition(0, 0, 0),
                    0
            );
        }
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer, final ItemReleaseInventoryTransaction_ActionType actionType) {
        sendReleaseItemTransaction(user, inventoryTransactionRewriter, createReleaseItemSnapshot(handContext, clientPlayer), actionType);
    }

    static List<InventoryActionData> predictedClickBlockActions(final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        return predictedClickBlockActions(handContext, clientPlayer, ViaBedrock.getConfig().shouldEmulateNetEaseClient());
    }

    /**
     * Official 975 still sends a predicted SOURCE_CONTAINER decrement with CLICK_BLOCK.
     * NetEase 860 native clients leave {@code actions[]} empty and let MOT apply the
     * decrement after {@code Level.useItemOn}.
     */
    static List<InventoryActionData> predictedClickBlockActions(final ItemUseHandContext handContext,
                                                                final ClientPlayerEntity clientPlayer,
                                                                final boolean emulateNetEase) {
        if (!ItemUseSemantics.sendPredictedClickBlockSlotDelta(emulateNetEase) || handContext == null) {
            return null;
        }
        BedrockItem predictedToItem = handContext.item().copy();
        if (predictedToItem.blockRuntimeId() != 0 && (clientPlayer == null || clientPlayer.javaGameMode() != GameMode.CREATIVE)) {
            predictedToItem.setAmount(predictedToItem.amount() - 1);
        }
        if (predictedToItem.amount() <= 0) {
            predictedToItem = BedrockItem.empty();
        }
        return List.of(
                new InventoryActionData(
                        new InventorySource(InventorySourceType.ContainerInventory, handContext.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                        handContext.containerSlot(),
                        handContext.item(),
                        predictedToItem
                )
        );
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final ItemUseHandContext handContext, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                handContext.transactionHotbarSlot(),
                handContext.item().copy(),
                releaseHeadPosition(clientPlayer)
        );
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final ItemUseSnapshot itemUseSnapshot, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                itemUseSnapshot.transactionHotbarSlot(),
                itemUseSnapshot.item().copy(),
                releaseHeadPosition(clientPlayer)
        );
    }

    /**
     * MOT ReleaseItem.headRot is the player eye/head position. ClientPlayerEntity.position()
     * already stores that eye position, so adding eyeOffset again would sit 1.62 blocks too high.
     */
    static Position3f releaseHeadPosition(final ClientPlayerEntity clientPlayer) {
        return clientPlayer == null ? Position3f.ZERO : clientPlayer.position();
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
        if (snapshot == null) {
            return false;
        }
        // Check slot first
        if (snapshot.hand() != handContext.hand()
                || snapshot.containerId() != handContext.containerId()
                || snapshot.containerSlot() != handContext.containerSlot()
                || snapshot.transactionHotbarSlot() != handContext.transactionHotbarSlot()) {
            return false;
        }
        
        // Current item must exist
        if (handContext.item() == null || handContext.item().isEmpty()) {
            return false;
        }
        
        // Only compare numeric item ID - allow data/NBT changes from server resyncs
        return snapshot.item().identifier() == handContext.item().identifier();
    }

    private static void syncJavaUsingItem(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        final List<EntityData> javaEntityData = new ArrayList<>();
        javaEntityData.add(new EntityData(
                clientPlayer.getJavaEntityDataIndex(EntityDataFields.LIVING_ENTITY_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                EntityMetadataRewriter.localPlayerLivingFlags(clientPlayer.entityFlags(), clientPlayer, user.get(ItemRewriter.class))
        ));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, user);
        setEntityData.write(Types.VAR_INT, clientPlayer.javaId());
        setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData);
        setEntityData.send(BedrockProtocol.class);
    }

    private static void stopUsingItem(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (!clientPlayer.isUsingItem()) {
            restorePromotedOffhand(user, clientPlayer);
            return;
        }
        if (clientPlayer.isShieldSneakEmulated() && !clientPlayer.inputFlags().contains(InputFlag.SHIFT)) {
            clientPlayer.setSneaking(false);
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakReleasedRaw, PlayerAuthInputPacket_InputData.StopSneaking);
        }
        restorePromotedOffhand(user, clientPlayer);
        clientPlayer.setUsingItem(false);
        syncJavaUsingItem(user, clientPlayer);
    }

    private static void cancelUsingItem(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ClientPlayerEntity clientPlayer) {
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        if (snapshot != null
                && !ItemUseSemantics.emulateShieldAsSneak(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), ItemUseSemantics.isShield(itemRewriter.bedrockIdentifier(snapshot.item())))
                && ItemUseSemantics.sendCancelRelease(
                        ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                        isConsumableUseItem(itemRewriter, snapshot.item()),
                        clientPlayer.usingItemTicks())) {
            sendReleaseItemTransaction(user, inventoryTransactionRewriter, createReleaseItemSnapshot(snapshot, clientPlayer), ItemReleaseInventoryTransaction_ActionType.Release);
        }
        stopUsingItem(user, clientPlayer);
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
        if (sendEquipment && handContext.isMainHand() && !shouldSkipMobEquipmentBeforeUse(user.get(ItemRewriter.class), handContext.item())) {
            sendSelectedHotbarSlot(user, handContext, clientPlayer);
        }
        if (isBow(user.get(ItemRewriter.class), handContext.item())
                && ItemUseSemantics.sendStartItemUseOnForBow(ViaBedrock.getConfig().shouldEmulateNetEaseClient())) {
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
        clientPlayer.markCrossbowChargeFinished();
        final ReleaseItemSnapshot releaseSnapshot = createReleaseItemSnapshot(handContext, clientPlayer);
        user.getChannel().eventLoop().schedule(() -> sendReleaseItemTransaction(user, inventoryTransactionRewriter, releaseSnapshot, ItemReleaseInventoryTransaction_ActionType.Release), FINISH_USE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void finishConsumableUse(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ClientPlayerEntity clientPlayer) {
        final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        if (snapshot == null || clientPlayer.isConsumableFinishSent()) {
            return;
        }
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final String identifier = itemRewriter.bedrockIdentifier(snapshot.item());
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        if (!ItemUseSemantics.sendConsumableFinishTransaction(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                true,
                ItemUseSemantics.motAutoCompletesConsumable(identifier, itemTags))) {
            return;
        }
        sendUseItemTransaction(user, inventoryTransactionRewriter, createHandContext(snapshot), clientPlayer, false);
        clientPlayer.setConsumableFinishSent(true);
        if (!ItemUseSemantics.delayReleaseAfterConsumableFinish(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), true)) {
            return;
        }
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
        registerHandSensitivePacketGates(protocol);

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_ACTION, wrapper -> {
            final InventoryTransactionRewriter inventoryTransactionRewriter = wrapper.user().get(InventoryTransactionRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();

            final PlayerActionAction action = PlayerActionAction.values()[wrapper.passthrough(Types.VAR_INT)]; // action
            wrapper.passthrough(Types.BLOCK_POSITION1_14); // block position
            wrapper.passthrough(Types.UNSIGNED_BYTE); // face
            final int sequence = wrapper.passthrough(Types.VAR_INT); // sequence number

            // Mining, drops, F/Q swaps, and riptide all consume the selected main-hand
            // mirror. Keep them behind the same promotion boundary as entity actions.
            if (action != PlayerActionAction.RELEASE_USE_ITEM
                    && !canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer,
                    InteractionHand.MAIN_HAND, false, false, sequence)) {
                return;
            }

            if (action == PlayerActionAction.RELEASE_USE_ITEM) {
                if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, null, true, false, sequence)) {
                    return;
                }

                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }

                if (!clientPlayer.isUsingItem()) {
                    // The injected Java consumable produces a release packet, but Bedrock swords have no
                    // corresponding continuous-use state to release.
                    if (ExperimentalItemRewriter.isSwordBlockingAnimationItem(wrapper.user().get(ItemRewriter.class), inventoryTracker.getInventoryContainer().getSelectedHotbarItem())) {
                        return;
                    }
                    // After a successful NetEase finish, CLIENT_TICK_END already cleared using-state
                    // while the local inventory mirror still holds the pre-eat stack. Resyncing that
                    // snapshot would put the eaten item back into the Java client.
                    if (isConsumableUseItem(wrapper.user().get(ItemRewriter.class), inventoryTracker.getInventoryContainer().getSelectedHotbarItem())) {
                        return;
                    }
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                    return;
                }

                // After a silent offhand promote the tracker main hand holds the
                // used stack. Java still reports OFF_HAND for the animation.
                final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                        inventoryTracker,
                        clientPlayer.isOffhandPromoted() ? InteractionHand.MAIN_HAND : clientPlayer.usingItemHand());
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
                final ItemUseHandContext motHandContext = motPacketContext(handContext, clientPlayer);
                if (isCrossbow(wrapper.user().get(ItemRewriter.class), selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_CHARGE_TICKS) {
                    finishCrossbowCharge(wrapper.user(), inventoryTransactionRewriter, motHandContext, clientPlayer);
                    stopUsingItem(wrapper.user(), clientPlayer);
                    return;
                }
                // Duration-ready Java RELEASE_USE_ITEM is completed by CLIENT_TICK_END.
                // Check that before the Use branch so this skip actually runs. Early
                // releases are not duration-ready and must still reach MOT.
                if (ItemUseSemantics.ignoreJavaConsumableRelease(
                        ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                        isConsumableUseItem(wrapper.user().get(ItemRewriter.class), selectedItem),
                        releaseAction == ItemReleaseInventoryTransaction_ActionType.Use,
                        clientPlayer.usingItemTicks())) {
                    return;
                }
                if (releaseAction == ItemReleaseInventoryTransaction_ActionType.Use) {
                    finishConsumableUse(wrapper.user(), inventoryTransactionRewriter, clientPlayer);
                    if (!ItemUseSemantics.keepLocalUsingAfterConsumableFinish(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), true, clientPlayer.isConsumableFinishSent())) {
                        stopUsingItem(wrapper.user(), clientPlayer);
                    }
                    return;
                }
                if (ItemUseSemantics.emulateShieldAsSneak(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), isShield(wrapper.user().get(ItemRewriter.class), selectedItem))) {
                    stopUsingItem(wrapper.user(), clientPlayer);
                    return;
                }

                sendReleaseItemTransaction(wrapper.user(), inventoryTransactionRewriter, motHandContext, clientPlayer, releaseAction);
                stopUsingItem(wrapper.user(), clientPlayer);
            } else if (action == PlayerActionAction.DROP_ITEM || action == PlayerActionAction.DROP_ALL_ITEMS) {
                if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, InteractionHand.MAIN_HAND, false, false, sequence)) {
                    return;
                }
                // MOT 860 SAI drops TYPE_NORMAL InventoryTransaction. Q/Ctrl-Q must
                // travel as ITEM_STACK_REQUEST Drop, matching inventory-window drops.
                // Ref: MOT Player.java isInventorySAIGateActive; DropActionProcessor.
                wrapper.cancel();
                ClientAuthInventoryModule.tryHandleHotbarDrop(wrapper.user(), action == PlayerActionAction.DROP_ALL_ITEMS);
            }


        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CLIENT_TICK_END, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (!clientPlayer.isUsingItem()) {
                return;
            }

            // Keep sending START_USING_ITEM every tick while using item
            // This mirrors official Bedrock client behavior
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);

            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                    inventoryTracker,
                    clientPlayer.isOffhandPromoted() ? InteractionHand.MAIN_HAND : clientPlayer.usingItemHand());
            final BedrockItem selectedItem = handContext.item();
            if (!matchesUseItem(handContext, clientPlayer)) {
                cancelUsingItem(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), clientPlayer);
                return;
            }

            final ItemReleaseInventoryTransaction_ActionType actionType = releaseActionForItem(itemRewriter, selectedItem, clientPlayer.usingItemTicks());
            if (isCrossbow(itemRewriter, selectedItem) && clientPlayer.usingItemTicks() >= CROSSBOW_AUTO_FINISH_TICKS) {
                if (clientPlayer.isCrossbowChargeFinishSent()) {
                    return;
                }
                finishCrossbowCharge(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), motPacketContext(handContext, clientPlayer), clientPlayer);
                clientPlayer.setCrossbowChargeFinishSent(true);
                return;
            }
            if (actionType == ItemReleaseInventoryTransaction_ActionType.Use) {
                finishConsumableUse(wrapper.user(), wrapper.user().get(InventoryTransactionRewriter.class), clientPlayer);
                if (!ItemUseSemantics.keepLocalUsingAfterConsumableFinish(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), true, clientPlayer.isConsumableFinishSent())) {
                    stopUsingItem(wrapper.user(), clientPlayer);
                    return;
                }
            }
            if (ItemUseSemantics.keepLocalUsingAfterConsumableFinish(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), isConsumableUseItem(itemRewriter, selectedItem), clientPlayer.isConsumableFinishSent())) {
                final ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
                if (snapshot != null) {
                    final String snapshotIdentifier = itemRewriter.bedrockIdentifier(snapshot.item());
                    final Set<String> snapshotTags = snapshotIdentifier != null
                            ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(snapshotIdentifier)
                            : null;
                    if (ItemUseSemantics.consumableConsumedByServer(
                            snapshot.item().identifier(),
                            snapshot.item().amount(),
                            selectedItem.isEmpty(),
                            selectedItem.identifier(),
                            selectedItem.amount())
                            || ItemUseSemantics.localUsingTimedOut(
                                    true,
                                    true,
                                    clientPlayer.usingItemTicks(),
                                    ItemUseSemantics.consumableUseTicks(
                                            snapshotIdentifier,
                                            snapshotTags,
                                            itemRewriter.itemUseDefinition(snapshot.item())))) {
                        stopUsingItem(wrapper.user(), clientPlayer);
                    }
                }
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
            if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, hand, true, true, sequence)) {
                return;
            }
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                    inventoryTracker, hand, clientPlayer.isOffhandPromoted());
            final BedrockItem selectedItem = handContext.item();
            if (ItemUseSemantics.dropDuplicateAirClickAfterUseOn(
                    ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                    itemRewriter.bedrockIdentifier(selectedItem),
                    itemRewriter.bedrockIdentifier(selectedItem) != null
                            ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(itemRewriter.bedrockIdentifier(selectedItem))
                            : null,
                    clientPlayer.lastUseOnAge() == clientPlayer.age())) {
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }
            if (isContinuousUseItem(itemRewriter, selectedItem)) {
                beginContinuousItemUse(wrapper.user(), inventoryTransactionRewriter, clientPlayer, itemRewriter, handContext);
                wrapper.clearPacket();
                wrapper.cancel();
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            final ItemUseHandContext motHandContext = motContextForOffhandUse(wrapper.user(), clientPlayer, handContext, itemRewriter);
            if (motHandContext == null) {
                wrapper.cancel();
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getOffhandContainer());
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            // Java USE_ITEM for empty buckets/bottles, filled buckets, boats and lily pads is an air click.
            // MOT only runs onActivate from CLICK_BLOCK, so resolve the looked-at block here.
            // Same-tick USE_ITEM_ON already cancelled above via dropDuplicateAirClickAfterUseOn.
            if (trySendAirClickAsBlockUse(wrapper.user(), clientPlayer, motHandContext, itemRewriter, inventoryTransactionRewriter, yaw, pitch, sequence)) {
                restorePromotedOffhand(wrapper.user(), clientPlayer);
                wrapper.cancel();
                return;
            }

            if (isChargedCrossbow(itemRewriter, selectedItem) && motHandContext.isMainHand()) {
                if (!ItemUseSemantics.crossbowFireReady(
                        ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                        true,
                        clientPlayer.ticksSinceCrossbowChargeFinish())) {
                    restorePromotedOffhand(wrapper.user(), clientPlayer);
                    wrapper.cancel();
                    if (sequence > 0) {
                        PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                    }
                    return;
                }
                sendSelectedHotbarSlot(wrapper.user(), motHandContext, clientPlayer);
            }
            // Send now, then restore. Writing the wrapper and restoring first
            // would let MOT see the original main hand before this USE_ITEM.
            sendUseItemTransaction(wrapper.user(), inventoryTransactionRewriter, motHandContext, clientPlayer, false);
            restorePromotedOffhand(wrapper.user(), clientPlayer);
            wrapper.cancel();
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });

        // Handle COMPLETED_USING_ITEM from server (sent when item use completes, e.g. eating)
        protocol.registerClientbound(ClientboundBedrockPackets.COMPLETED_USING_ITEM, null, wrapper -> {
            wrapper.cancel();
            stopUsingItem(wrapper.user(), wrapper.user().get(EntityTracker.class).getClientPlayer());
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

            if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, hand, true, true, sequence)) {
                return;
            }
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                    inventoryTracker, hand, clientPlayer.isOffhandPromoted());
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final BedrockItem selectedItem = handContext.item();
            // MOT CLICK_BLOCK always setUsingItem(false). Keep eating/drawing from
            // being cancelled by a later Java USE_ITEM_ON at the same crosshair.
            // Ref: MOT Player.java case 2 / actionType 0.
            if (ItemUseSemantics.skipClickBlockWhileUsing(
                    ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                    clientPlayer.isUsingItem(),
                    clientPlayer.isShieldSneakEmulated())) {
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }
            // MOT Level.useItemOn runs block.onActivate first unless sneaking.
            // Starting hold-to-use here swallows chests/tables while holding food/bow.
            if (ItemUseSemantics.shouldStartContinuousUseFromUseItemOn(ViaBedrock.getConfig().shouldEmulateNetEaseClient())
                    && isContinuousUseItem(itemRewriter, selectedItem)) {
                beginContinuousItemUse(wrapper.user(), inventoryTransactionRewriter, clientPlayer, itemRewriter, handContext);

                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            final ItemUseHandContext motHandContext = motContextForOffhandUse(wrapper.user(), clientPlayer, handContext, itemRewriter);
            if (motHandContext == null) {
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getOffhandContainer());
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }
            sendItemUseOnBlock(wrapper.user(), clientPlayer, motHandContext, inventoryTransactionRewriter, chunkTracker, position, faceInt, face, clickPosition, insideBlock, sequence);
            restorePromotedOffhand(wrapper.user(), clientPlayer);
            clientPlayer.markUseOnThisTick();
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
                        Container container = inventoryTracker.getContainerClientbound(action.source().containerId(), null, null);

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
        Via.getPlatform().runRepeatingSync(new MapInfoRequestTickTask(), 20L);
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
        user.put(new CustomBlockDisplayTracker(user));

        // Dispatch to feature modules
        for (final FeatureModule module : MODULES) {
            try {
                module.onStorageRegistration(user);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error in module onStorageRegistration", e);
            }
        }
    }

    private static boolean canDropLockedItem(final BedrockItem item) {
        try {
            return BedrockItemLockPolicy.canDrop(item);
        } catch (final NoClassDefFoundError | Exception e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to evaluate Bedrock item lock policy; allowing drop", e);
            return true;
        }
    }
}



