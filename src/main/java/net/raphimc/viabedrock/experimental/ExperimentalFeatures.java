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
import net.raphimc.viabedrock.experimental.inventory.PlacementPrediction;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.experimental.dimension.AlternateDimensionModule;
import net.raphimc.viabedrock.experimental.entity.CustomEntityTypeResolver;
import net.raphimc.viabedrock.experimental.npc.NpcDialogueModule;
import net.raphimc.viabedrock.experimental.modinterface.ModUIClientModule;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.rewriter.EntityMetadataRewriter;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
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
 * 该类用于注册尚未足够稳定/经过测试、还不能纳入主协议的实验性功能。
 * 这些功能在后续版本中可能变更或被移除。
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

    private record ReleaseItemSnapshot(int hotbarSlot, BedrockItem itemInHand, Position3f headPosition, int hand) {
    }

    public static List<FeatureModule> getModules() {
        return Collections.unmodifiableList(MODULES);
    }

    public static void registerModule(final FeatureModule module) {
        MODULES.add(module);
    }

    // --- 模块分发方法 ---

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

        // 在启动时解析锁策略辅助类，避免之后的丢弃/点击因 NoClassDefFoundError 失败。
        canDropLockedItem(BedrockItem.empty());

        registerModule(new PyRpcDispatcherModule());  // 必须放在最前（拥有 PY_RPC 处理器）
        registerModule(new ModUIClientModule());      // PY_RPC 消费者
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
            // MOT 先处理 START_SPRINTING 再处理 START_SNEAKING，且不会
            // 清除已在疾跑的玩家。Java 的盾牌格挡是站立姿态；
            // 因此发送 StopSprinting，避免 MOT 处于疾跑加潜行状态。
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
        // 使用动画保留 Java 侧的手。MOT 的槽位来自
        // 静默 F 交换，好让 CLICK_AIR / RELEASE 看到主手里的那堆物品。
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
                // MOT 的 START_SPRINTING 会把 sprinting 置为 true、usingItem 置为 false。Java 进食/拉弓
                // 会取消疾跑；只丢弃 StartSprinting 不够，要发送 StopSprinting，
                // 好让 MOT/GanAC 的 SprintCheck 看到使用开始这一边沿。
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
                        DEFAULT_COOLDOWN_STATE,
                        handContext.hand().ordinal()
                )
        );
    }

    // --- MOT 只按 CLICK_BLOCK 处理的 Java USE_ITEM 空点击物品 ---

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
     * 把 Java 对伪物品展示框实体的右键转换为服务端期望的基岩方块交互。
     * 物品展示框在基岩中是方块（由 {@code BlockItemFrame.onActivate} 处理：空框 -> 放置手持物品，
     * 有物品的框 -> 旋转），但 ViaBedrock 以实体形式把它暴露给 Java 客户端，因此实体交互
     * 本身永远到不了服务端。当实验性功能关闭，或交互的实体不是被跟踪的物品展示框时，
     * 返回 false（空操作），让调用方回退到默认处理。
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
        // 方块中心。Nukkit 的 BlockItemFrame.onActivate 不依赖精确的点击位置。
        final Position3f clickPosition = new Position3f(0.5F, 0.5F, 0.5F);

        sendItemUseOnBlock(user, clientPlayer, handContext, inventoryTransactionRewriter, chunkTracker,
                info.position(), faceInt, face, clickPosition, false, 0); // sequence 0：实体交互，没有 Java 确认
        return true;
    }

    /**
     * 自定义方块以 Java 占位方块加物品展示叠加层的形式呈现。
     * 如果展示层仍然拦截了右键，就把它转换为服务端期望的基岩
     * 方块使用操作，而不是丢弃该数据包。
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
     * 左键点击叠加展示层必须在对应位置开始基岩方块破坏。
     * 当该攻击被当作方块操作处理时返回 true。
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
     * Java 左键点击伪物品展示框实体必须变成基岩的
     * {@code StartDestroyBlock}。Nukkit 的 {@code BlockItemFrame.onTouch} 会在 LEFT_CLICK_BLOCK 时
     * 丢出展示的物品，之后才允许后续的破坏打破展示框。
     * 而取消 ATTACK 又不做转换会把两者都吞掉。
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
            // MOT 的 PredictDestroy 本身会先中止再完成。若在 Predict 之前还排了一个 Abort，
            // 它会被存进 MOT 的 EnumMap，于是 GanAC/MOT 会先看到 Abort 并丢弃该破坏。
            // 与 finishBlockBreak 保持一致：Start+Predict。
            clientPlayer.clearDelayedMotBreak();
            for (PlayerActionType type : overlayCreativeBreakActions()) {
                clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(
                        type, position, direction.ordinal()));
            }
        } else if (overlaySurvivalInstantBreak(user, position)) {
            // 生存模式下的叠加层是 Java 对伪展示层的 ATTACK，没有 STOP_DESTROY。
            // MOT 只在 PredictDestroyBlock 时完成。瞬破（硬度 0）的叠加层必须像创造模式那样
            // Start+Predict。非瞬破的叠加层则通过 SWING 保留 CrackBlock。
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
     * MOT 的 PredictDestroy 本身会先中止再完成。不要在 Predict 之后补 Abort：
     * MOT 的 EnumMap 会先迭代到 Abort 并丢弃该破坏。
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
     * MOT 只消费 {@code getItemInHand()}。用静默 F 交换把 Java 的副手物品堆
     * 提升到主手，好让 CLICK_AIR / CLICK_BLOCK 看到它。
     * 盾牌仍走潜行模拟，不做交换。
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
     * MOT 事务类型 3 还会比较并消耗选中的主手物品堆。实体交互允许副手为空，
     * 因此它有意不套用上面物品使用的空堆校验。
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
            // 新动作可以复用一次仍在等待恢复的提升。
            clientPlayer.markOffhandPromoted();
            return ItemUseHandContext.resolve(user.get(InventoryTracker.class), InteractionHand.MAIN_HAND);
        }
        final ClientAuthInventoryModule.SwapHandsResult swap = ClientAuthInventoryModule.tryHandleSwapHandsTracked(user, false);
        if (!swap.submitted()) {
            return null;
        }
        if (swap.requestId() == null) {
            // 旧式 TYPE_NORMAL 没有 ITEM_STACK_RESPONSE 记录。MOT 事务
            // 已经生效，因此视为已立即提升。
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
                // 在 ClickSimulator/applyMirrorUpdates 可能替换或改动槽位之前先捕获。
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

                // 旧式事务或空动作列表没有对应的响应记录。
                // 按顺序发出并不等于确认，所以绝不在这里排空延迟队列。
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
                // MOT 已拿到提升后的主手，现在刷出排队的 ATTACK/INTERACT。
                // 保持 PROMOTED，好让后续的 USE_ITEM 仍能重映射 OFF_HAND；
                // 该次使用结束时再做恢复。
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
     * Java 的物品栏没有交换，因此按物品匹配原始的副手物品堆。
     * MOT 数据包仍使用快照中的主手快捷栏槽位。
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
     * 构建并发送 CLICK_BLOCK 的 ItemUseTransaction（前后伴随 Start/StopItemUseOn），
     * 即基岩客户端与方块交互时发出的包。由 USE_ITEM_ON 处理器与 USE_ITEM 的
     * 空桶 / 玻璃瓶流体分支共用。
     */
    static BlockPosition blockPlacementAckPosition(final BlockPosition clickedPosition, final BlockFace face,
                                                   final boolean clickedBlockReplaceable) {
        return clickedBlockReplaceable ? clickedPosition : clickedPosition.getRelative(face);
    }

    private static void sendItemUseOnBlock(final UserConnection user, final ClientPlayerEntity clientPlayer, final ItemUseHandContext handContext, final InventoryTransactionRewriter inventoryTransactionRewriter, final ChunkTracker chunkTracker, final BlockPosition position, final int faceInt, final BlockFace face, final Position3f clickPosition, final boolean insideBlock, final int sequence) {
        // MOT 的 USE_ITEM CLICK_BLOCK 总会 setUsingItem(false)（Player.java:4528）。
        // 而 Java Fabric 在咀嚼或拉弓期间仍会发送 USE_ITEM_ON / 物品展示框 / 叠加层 / 空桶。
        // 除非盾牌当潜行仍需放置/激活，否则整段 CLICK_BLOCK（以及 28/29）都跳过。
        if (ItemUseSemantics.skipClickBlockWhileUsing(
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                clientPlayer.isUsingItem(),
                clientPlayer.isShieldSneakEmulated())) {
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(user, sequence);
            }
            return;
        }

        clientPlayer.swingSourceTracker().recordUse(handContext.hand(),
                handContext.item().blockRuntimeId() != 0 ? ActorSwingSource.Build : ActorSwingSource.Interact);
        // insideBlock 仅表示射线起点在方块内，不决定替换点击格还是在相邻格放置。
        // 半砖合并的目标、真实形状的自碰撞结果、物品预测与 ACK 必须使用同一次计算。
        final PlacementPrediction.Result placement = handContext.item().blockRuntimeId() != 0
                ? PlacementPrediction.predict(user, clientPlayer, handContext.item(), chunkTracker, position, face, clickPosition)
                : new PlacementPrediction.Result(blockPlacementAckPosition(position, face, insideBlock), true);
        final BlockPosition expectedPos = placement.target();
        final boolean predictedSuccess = placement.success();
        // sequence 为 0 表示没有需要确认的 Java 方块变更（例如物品展示框）。
        // 只跟踪真正发出了 CLICK_BLOCK 的操作；被跳过的使用已在上面确认过。
        if (sequence > 0) {
            user.get(BlockPlacementAckTracker.class).addPendingAck(expectedPos, sequence);
        }

        // 官方基岩在 CLICK_BLOCK 之前发送 StartItemUseOn。MOT 860 没有
        // case 28/29；PlayerAction 的默认分支会调用 setUsingItem(false)，若 Java 本刻
        // 又注视了方块，就会取消正在进行的进食/拉弓。
        final boolean sendItemUseOnActions = ItemUseSemantics.sendItemUseOnPlayerActions(ViaBedrock.getConfig().shouldEmulateNetEaseClient());
        if (sendItemUseOnActions) {
            ExperimentalPacketFactory.sendBedrockPlayerAction(
                    user,
                    clientPlayer.runtimeId(),
                    PlayerActionType.StartItemUseOn,
                    position,
                    expectedPos,
                    faceInt
            );
        }

        // 这是基岩客户端与方块交互时使用的主要数据包。
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);

        final List<InventoryActionData> clickBlockActions = predictedClickBlockActions(handContext, clientPlayer,
                ViaBedrock.getConfig().shouldEmulateNetEaseClient(), predictedSuccess);
        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // 旧式请求 id
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
                        predictedSuccess
                                ? ItemUseInventoryTransaction_PredictedResult.Success
                                : ItemUseInventoryTransaction_PredictedResult.Failure,
                        (byte) 0, // TODO: 客户端冷却状态
                        handContext.hand().ordinal()
                )
        );
        transactionPacket.write(inventoryTransactionRewriter.getInventoryTransactionType(), inventoryTransaction);
        transactionPacket.sendToServer(BedrockProtocol.class);

        // 基岩在事务包之后会发送一个停止物品使用
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
     * 官方 975 仍会在 CLICK_BLOCK 中携带一个预测的 SOURCE_CONTAINER 扣减。
     * 网易 860 原生客户端会把 {@code actions[]} 留空，让 MOT 在
     * {@code Level.useItemOn} 之后自行扣减。
     */
    static List<InventoryActionData> predictedClickBlockActions(final ItemUseHandContext handContext,
                                                                final ClientPlayerEntity clientPlayer,
                                                                final boolean emulateNetEase) {
        return predictedClickBlockActions(handContext, clientPlayer, emulateNetEase, true);
    }

    /**
     * {@code predictedSuccess} 来自同一次放置预测：预测失败时不得在 actions[] 里扣减物品，
     * 否则服务端按失败处理时会与 Java 侧的预测数量对不上。
     */
    static List<InventoryActionData> predictedClickBlockActions(final ItemUseHandContext handContext,
                                                                final ClientPlayerEntity clientPlayer,
                                                                final boolean emulateNetEase,
                                                                final boolean predictedSuccess) {
        if (!ItemUseSemantics.sendPredictedClickBlockSlotDelta(emulateNetEase) || handContext == null) {
            return null;
        }
        BedrockItem predictedToItem = handContext.item().copy();
        if (predictedToItem.blockRuntimeId() != 0 && predictedSuccess
                && (clientPlayer == null || clientPlayer.javaGameMode() != GameMode.CREATIVE)) {
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
                releaseHeadPosition(clientPlayer),
                handContext.hand().ordinal()
        );
    }

    private static ReleaseItemSnapshot createReleaseItemSnapshot(final ItemUseSnapshot itemUseSnapshot, final ClientPlayerEntity clientPlayer) {
        return new ReleaseItemSnapshot(
                itemUseSnapshot.transactionHotbarSlot(),
                itemUseSnapshot.item().copy(),
                releaseHeadPosition(clientPlayer),
                itemUseSnapshot.hand().ordinal()
        );
    }

    /**
     * MOT 的 ReleaseItem.headRot 是玩家的眼睛/头部位置。ClientPlayerEntity.position()
     * 已经存有该眼睛位置，再次加上 eyeOffset 会高出 1.62 个方块。
     */
    static Position3f releaseHeadPosition(final ClientPlayerEntity clientPlayer) {
        return clientPlayer == null ? Position3f.ZERO : clientPlayer.position();
    }

    private static void sendReleaseItemTransaction(final UserConnection user, final InventoryTransactionRewriter inventoryTransactionRewriter, final ReleaseItemSnapshot snapshot, final ItemReleaseInventoryTransaction_ActionType actionType) {
        final PacketWrapper transactionPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        final BedrockInventoryTransaction inventoryTransaction = new BedrockInventoryTransaction(
                0, // 旧式请求 id
                null,
                null,
                ComplexInventoryTransaction_Type.ItemReleaseTransaction,
                new InventoryTransactionData.ReleaseItemTransactionData(
                        actionType,
                        snapshot.hotbarSlot(),
                        snapshot.itemInHand(),
                        snapshot.headPosition(),
                        snapshot.hand()
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
        // 先检查槽位
        if (snapshot.hand() != handContext.hand()
                || snapshot.containerId() != handContext.containerId()
                || snapshot.containerSlot() != handContext.containerSlot()
                || snapshot.transactionHotbarSlot() != handContext.transactionHotbarSlot()) {
            return false;
        }
        
        // 当前物品必须存在
        if (handContext.item() == null || handContext.item().isEmpty()) {
            return false;
        }
        
        // 只比较数值物品 ID，允许服务端重同步带来的 data/NBT 变化
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
        mobEquipmentPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // 实体运行时 ID
        mobEquipmentPacket.write(user.get(ItemRewriter.class).newItemType(), item); // 物品
        mobEquipmentPacket.write(Types.BYTE, slot); // 槽位
        mobEquipmentPacket.write(Types.BYTE, slot); // 选中槽位
        mobEquipmentPacket.write(Types.BYTE, containerId); // 容器 ID
        mobEquipmentPacket.sendToServer(BedrockProtocol.class);
    }

    // --- 现有实验性功能 ---

    private static final int MAP_FLAGS_ALL = ClientboundMapItemDataPacket_Type.Creation.getValue() | ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue() | ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();

    public static void registerPacketTranslators(final BedrockProtocol protocol) {
        // 在内置实验性钩子安装之前，把包分发到各功能模块。
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

            final PlayerActionAction action = PlayerActionAction.values()[wrapper.passthrough(Types.VAR_INT)]; // 动作
            wrapper.passthrough(Types.BLOCK_POSITION1_14); // 方块位置
            wrapper.passthrough(Types.UNSIGNED_BYTE); // 面
            final int sequence = wrapper.passthrough(Types.VAR_INT); // 序列号

            // 挖掘、掉落、F/Q 交换与激流都会消耗选中的主手
            // 镜像。让它们与实体动作处在同一条提升边界之后。
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
                    if (clientPlayer.itemUseSnapshot() != null) {
                        cancelUsingItem(wrapper.user(), inventoryTransactionRewriter, clientPlayer);
                        return;
                    }
                    // 注入的 Java 可消耗物品会产生一个释放包，但基岩的剑没有
                    // 对应的持续使用状态可供释放。
                    if (ExperimentalItemRewriter.isSwordBlockingAnimationItem(wrapper.user().get(ItemRewriter.class), inventoryTracker.getInventoryContainer().getSelectedHotbarItem())) {
                        return;
                    }
                    // 网易侧成功完成后，CLIENT_TICK_END 已经清掉了使用状态，
                    // 而本地物品栏镜像仍持有进食前的物品堆。重新同步该
                    // 快照会把已吃掉的食物放回 Java 客户端。
                    if (isConsumableUseItem(wrapper.user().get(ItemRewriter.class), inventoryTracker.getInventoryContainer().getSelectedHotbarItem())) {
                        return;
                    }
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                    return;
                }

                // 静默提升副手之后，跟踪器的主手持有
                // 实际使用的物品堆。而 Java 在播放动画时仍上报 OFF_HAND。
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
                // 达到时长的 Java RELEASE_USE_ITEM 由 CLIENT_TICK_END 完成。
                // 要在 Use 分支之前检查这一点，这段跳过逻辑才会真正执行。过早的
                // 释放并未达到时长，必须仍然发往 MOT。
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
                // MOT 860 的 SAI 会丢弃 TYPE_NORMAL InventoryTransaction。Q/Ctrl-Q 必须
                // 以 ITEM_STACK_REQUEST Drop 的形式发送，与物品栏窗口内的丢弃一致。
                // 参考：MOT Player.java 的 isInventorySAIGateActive；DropActionProcessor。
                wrapper.cancel();
                ClientAuthInventoryModule.tryHandleHotbarDrop(wrapper.user(), action == PlayerActionAction.DROP_ALL_ITEMS);
            }


        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CLIENT_TICK_END, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (clientPlayer.itemUseSnapshot() == null) {
                return;
            }

            // 使用物品期间每刻都继续发送 START_USING_ITEM
            // 这与官方基岩客户端行为一致
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartUsingItem);

            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                    inventoryTracker,
                    clientPlayer.isOffhandPromoted() ? InteractionHand.MAIN_HAND : clientPlayer.usingItemHand());
            final BedrockItem selectedItem = handContext.item();
            if (inventoryTracker.isAnyScreenOpen() || !matchesUseItem(handContext, clientPlayer)
                    || (!ViaBedrock.getConfig().shouldEmulateNetEaseClient() && !isContinuousUseItem(itemRewriter, selectedItem))) {
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

            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // 手
            final int sequence = wrapper.read(Types.VAR_INT); // 序列
            final float yaw = wrapper.read(Types.FLOAT); // 偏航角
            final float pitch = wrapper.read(Types.FLOAT); // 俯仰角

            // 标记为正在使用物品，并在本刻的 PlayerAuthInput 中发送 StartUsingItem 标志
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, hand, true, true, sequence)) {
                return;
            }
            clientPlayer.swingSourceTracker().recordUse(hand, ActorSwingSource.UseItem);
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

            // Java 对空桶/空瓶、已装满的桶、船和睡莲的 USE_ITEM 属于空点击。
            // MOT 只从 CLICK_BLOCK 触发 onActivate，因此在这里解析注视的方块。
            // 同一刻的 USE_ITEM_ON 已在上面通过 dropDuplicateAirClickAfterUseOn 取消。
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
            // 先发送，再恢复。若先写入包装并恢复，
            // MOT 会在本次 USE_ITEM 之前就看到原始主手。
            sendUseItemTransaction(wrapper.user(), inventoryTransactionRewriter, motHandContext, clientPlayer, false);
            restorePromotedOffhand(wrapper.user(), clientPlayer);
            wrapper.cancel();
            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });

        // 处理来自服务端的 COMPLETED_USING_ITEM（物品使用完成时发送，例如进食）
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

            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // 手

            BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // 方块位置
            int faceInt = wrapper.read(Types.UNSIGNED_BYTE); // 面
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
            boolean insideBlock = wrapper.read(Types.BOOLEAN); // 在方块内
            wrapper.read(Types.BOOLEAN); // 世界边界，基岩上没有该字段。

            // 把方块变更确认推迟到服务端通过 UPDATE_BLOCK 确认之后。
            // 立即发送确认会在任何 BLOCK_UPDATE 到达之前清掉 Java 客户端的预测，
            // 导致已放置的方块闪烁（先消失再出现）。
            final int sequence = wrapper.read(Types.VAR_INT);

            if (!canProcessHandSensitiveAction(wrapper.user(), wrapper, clientPlayer, hand, true, true, sequence)) {
                return;
            }
            final ItemUseHandContext handContext = ItemUseHandContext.resolve(
                    inventoryTracker, hand, clientPlayer.isOffhandPromoted());
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final BedrockItem selectedItem = handContext.item();
            // MOT 的 CLICK_BLOCK 总会 setUsingItem(false)。要避免进食/拉弓
            // 被随后同一准星的 Java USE_ITEM_ON 取消。
            // 参考：MOT Player.java case 2 / actionType 0。
            if (ItemUseSemantics.skipClickBlockWhileUsing(
                    ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                    clientPlayer.isUsingItem(),
                    clientPlayer.isShieldSneakEmulated())) {
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }
            // MOT 的 Level.useItemOn 除非潜行，否则会先执行 block.onActivate。
            // 在这里开始按住使用，会在手持食物/弓时吞掉箱子与工作台。
            if (ItemUseSemantics.shouldStartContinuousUseFromUseItemOn(ViaBedrock.getConfig().shouldEmulateNetEaseClient())
                    && isContinuousUseItem(itemRewriter, selectedItem)) {
                beginContinuousItemUse(wrapper.user(), inventoryTransactionRewriter, clientPlayer, itemRewriter, handContext);

                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }
            if (isContinuousUseItem(itemRewriter, selectedItem)) {
                // EaseCation #600：可放置食物的点击失败后不会再次发送 USE_ITEM。
                // 改为等待服务端确认该次使用，而不是在本地开始使用。
                clientPlayer.prepareBlockItemUse(handContext.hand(), handContext.containerId(), handContext.containerSlot(), handContext.transactionHotbarSlot(), selectedItem);
            } else {
                clientPlayer.setUsingItem(false);
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
                // 暂时忽略旧式物品栏事务
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
                    break; // 此处暂时无事可做
                }
                default -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unsupported inventory transaction type: " + inventoryTransaction.transactionType());
                }
            }
        });

        protocol.registerClientbound(ClientboundBedrockPackets.MAP_ITEM_DATA, ClientboundPackets26_1.MAP_ITEM_DATA, wrapper -> {
            MapTracker mapTracker = wrapper.user().get(MapTracker.class);

            // 2168 去掉类型标志，改成每段一个存在性 bool，颜色改小端 int32
            final boolean mapV2168 = BedrockWire.applies(BedrockDeviation.MAP_ITEM_DATA_V2168);
            final long mapId = wrapper.read(BedrockTypes.VAR_LONG); // 地图 id
            int typeFlags = 0;
            if (!mapV2168) {
                typeFlags = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 类型标志
            }
            final byte dimension = wrapper.read(Types.BYTE); // 维度
            final boolean locked = wrapper.read(Types.BOOLEAN); // 已锁定
            final BlockPosition origin = wrapper.read(BedrockTypes.BLOCK_POSITION); // 原点

            final LongList trackedEntities = new LongArrayList();
            if (mapV2168 ? wrapper.read(Types.BOOLEAN) : (typeFlags & ClientboundMapItemDataPacket_Type.Creation.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                for (int i = 0; i < length; i++) {
                    trackedEntities.add(wrapper.read(BedrockTypes.VAR_LONG).longValue());
                }
                typeFlags |= ClientboundMapItemDataPacket_Type.Creation.getValue();
            }

            byte scale = 0;
            if (mapV2168) {
                wrapper.read(Types.BOOLEAN); // MOT 2168 在这里恒写一个 true 占位，必须无条件消费，改成条件读就会失步
                scale = wrapper.read(Types.BYTE); // 缩放
            } else if ((typeFlags & MAP_FLAGS_ALL) != 0) {
                scale = wrapper.read(Types.BYTE); // 缩放
            }

            final List<MapDecoration> decorations = new ArrayList<>();
            final List<MapTrackedObject> trackedObjects = new ArrayList<>();
            if (mapV2168) {
                // 2168 把“装饰更新”拆成两个独立可选段，且追踪对象每条带实体/方块两个 bool
                if (wrapper.read(Types.BOOLEAN)) {
                    final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                    for (int i = 0; i < length; i++) {
                        wrapper.read(BedrockTypes.INT_LE); // 冗余的类型字段，实际以两个 bool 为准
                        final boolean isEntity = wrapper.read(Types.BOOLEAN);
                        final boolean isBlock = wrapper.read(Types.BOOLEAN);
                        // 服务端保证二者恰有一个为真；都为假时整条丢弃
                        if (isEntity) {
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.VAR_LONG)));
                        } else if (isBlock) {
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.BLOCK_POSITION)));
                        }
                    }
                    typeFlags |= ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue();
                }
                if (wrapper.read(Types.BOOLEAN)) {
                    final int decorLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                    for (int i = 0; i < decorLength; i++) {
                        final byte iconType = wrapper.read(Types.BYTE);
                        final byte rotation = wrapper.read(Types.BYTE);
                        final byte x = wrapper.read(Types.BYTE);
                        final byte y = wrapper.read(Types.BYTE);
                        final String name = wrapper.read(BedrockTypes.STRING); // 名称
                        final int color = wrapper.read(BedrockTypes.INT_LE); // 颜色

                        decorations.add(new MapDecoration(iconType, rotation, x, y, name, color));
                    }
                    typeFlags |= ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue();
                }
            } else if ((typeFlags & ClientboundMapItemDataPacket_Type.DecorationUpdate.getValue()) != 0) {
                final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                for (int i = 0; i < length; i++) {
                    MapTrackedObject.Type objectType = MapTrackedObject.Type.values()[wrapper.read(BedrockTypes.INT_LE)]; // TODO: 错误日志记录
                    switch (objectType) {
                        case BLOCK:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.BLOCK_POSITION)));
                            break;
                        case ENTITY:
                            trackedObjects.add(new MapTrackedObject(wrapper.read(BedrockTypes.VAR_LONG)));
                            break;
                    }
                }

                final int decorLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                for (int i = 0; i < decorLength; i++) {
                    final byte iconType = wrapper.read(Types.BYTE);
                    final byte rotation = wrapper.read(Types.BYTE);
                    final byte x = wrapper.read(Types.BYTE);
                    final byte y = wrapper.read(Types.BYTE);
                    final String name = wrapper.read(BedrockTypes.STRING); // 名称
                    final int color = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 颜色

                    decorations.add(new MapDecoration(iconType, rotation, x, y, name, color));
                }
            }

            int width = 0;
            int height = 0;
            int xOffset = 0;
            int yOffset = 0;
            int[] colors = new int[0];
            if (mapV2168) {
                // 四个尺寸字段与颜色数组各自独立可选
                if (wrapper.read(Types.BOOLEAN)) {
                    width = wrapper.read(BedrockTypes.VAR_INT); // 宽度
                    typeFlags |= ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();
                }
                if (wrapper.read(Types.BOOLEAN)) {
                    height = wrapper.read(BedrockTypes.VAR_INT); // 高度
                    typeFlags |= ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();
                }
                if (wrapper.read(Types.BOOLEAN)) {
                    xOffset = wrapper.read(BedrockTypes.VAR_INT); // x 偏移
                    typeFlags |= ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();
                }
                if (wrapper.read(Types.BOOLEAN)) {
                    yOffset = wrapper.read(BedrockTypes.VAR_INT); // y 偏移
                    typeFlags |= ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();
                }
                if (wrapper.read(Types.BOOLEAN)) {
                    final int colorsLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 颜色数量
                    colors = new int[colorsLength];
                    for (int i = 0; i < colorsLength; i++) {
                        colors[i] = wrapper.read(BedrockTypes.INT_LE);
                    }
                    typeFlags |= ClientboundMapItemDataPacket_Type.TextureUpdate.getValue();
                }
            } else if ((typeFlags & ClientboundMapItemDataPacket_Type.TextureUpdate.getValue()) != 0) {
                width = wrapper.read(BedrockTypes.VAR_INT); // 宽度
                height = wrapper.read(BedrockTypes.VAR_INT); // 高度
                xOffset = wrapper.read(BedrockTypes.VAR_INT); // x 偏移
                yOffset = wrapper.read(BedrockTypes.VAR_INT); // y 偏移

                final int colorsLength = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 颜色数量
                colors = new int[colorsLength];
                for (int i = 0; i < colorsLength; i++) {
                    colors[i] = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                }
            }

            // TODO: 清理这里
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

            wrapper.write(Types.VAR_INT, mapObject.getJavaId()); // 地图 id
            wrapper.write(Types.BYTE, mapObject.getScale()); // 缩放
            wrapper.write(Types.BOOLEAN, mapObject.isLocked()); // 已锁定

            writeMapIcons(wrapper, mapObject.getDecorations()); // 图标（带前缀的可选字段）
            wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getWidth()); // 宽度
            if (mapObject.getWidth() > 0) {
                wrapper.write(Types.UNSIGNED_BYTE, (short) mapObject.getHeight()); // 高度
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

    // 把基岩的 MapDecoration_Type 值（索引）映射到 Java minecraft:map_decoration_type 注册表 id。
    // Java 注册表是内置（不同步）注册表，因此索引遵循原版 1.21 顺序。-1 = 不绘制。
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
            return 0; // unknown -> 回退到玩家标记，而不是静默丢弃
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
            wrapper.write(Types.BOOLEAN, false); // 无图标
            return;
        }

        wrapper.write(Types.BOOLEAN, true); // 有图标
        wrapper.write(Types.VAR_INT, visible.size());
        for (final MapDecoration decoration : visible) {
            wrapper.write(Types.VAR_INT, javaMapDecorationType(decoration.image())); // 类型
            wrapper.write(Types.BYTE, (byte) decoration.xOffset()); // x
            wrapper.write(Types.BYTE, (byte) decoration.yOffset()); // z
            wrapper.write(Types.BYTE, (byte) (decoration.rotation() & 15)); // 旋转
            wrapper.write(Types.BOOLEAN, false); // 没有自定义显示名称
        }
    }

    public static void registerStorages(final UserConnection user) {
        user.put(new InventoryTransactionRewriter(user));
        user.put(new MapTracker(user));
        user.put(new MultilineNametagTracker(user));
        user.put(new ScriptDebugTextTracker(user));
        user.put(new BlockPlacementAckTracker(user));
        user.put(new CustomBlockDisplayTracker(user));

        // 分发到各功能模块
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



