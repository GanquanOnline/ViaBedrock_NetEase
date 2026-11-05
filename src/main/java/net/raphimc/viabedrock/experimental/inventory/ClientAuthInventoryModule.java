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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.util.Limit;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.api.model.container.TradeContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.packet.CreativeContentLayout;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.packet.ItemStackResponseLayout;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.AnvilSessionStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.storage.TradeSessionStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public class ClientAuthInventoryModule implements FeatureModule {

    public record SwapHandsResult(boolean submitted, Integer requestId) {

        private static SwapHandsResult failed() {
            return new SwapHandsResult(false, null);
        }

        private static SwapHandsResult withoutRequest() {
            return new SwapHandsResult(true, null);
        }

    }

    private record PredictedActionsResult(boolean submitted, Integer requestId) {

        private static PredictedActionsResult failed() {
            return new PredictedActionsResult(false, null);
        }

        private static PredictedActionsResult withoutRequest() {
            return new PredictedActionsResult(true, null);
        }

    }

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new DragState(user));
        user.put(new CreativeContentCache(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        registerContainerClickHandler(protocol);
        registerCreativeContentHandler(protocol);
        registerSelectTradeHandler(protocol);
        registerCreativeModeSlotHandler(protocol);
        registerSetBeaconHandler(protocol);
        registerCrafterSlotStateHandler(protocol);
        // Java 期望合成输出预览由服务器推送，但基岩在客户端计算且从不发送。
        // 每当（服务端权威的）合成网格内容变化时在本地重新计算，使 Java 输出槽反映匹配配方的结果。
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_SLOT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_CONTENT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        // 客户端权威的服务器不会为客户端主动发起的关闭回发服务端→客户端的 CONTAINER_CLOSE，因此 pendingCloseContainer 永远不会被清除，容器也无法再次打开（直到玩家走远、服务器强制关闭它）。
        // 在关闭被转发给服务器后立即清除它。
        ProtocolUtil.appendServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLOSE, wrapper -> {
            final InventoryTracker tracker = wrapper.user().get(InventoryTracker.class);
            final Container pending = tracker.completePendingCloseWithoutConfirmation();
            // Java 可以打开和关闭玩家物品栏，而不产生对应的基岩 CONTAINER_OPEN。
            // 这种情况下没有待处理的容器，但预测的 HUD 光标仍必须丢弃，以免它在下一次完整物品栏同步时被恢复。
            if (tracker.clearCursorIfContainerClosed()) {
                sendJavaCursor(wrapper.user(), tracker);
                clearPlayerCraftingGrid(tracker);
            }
            wrapper.user().get(DragState.class).reset();
            // 服务器（Nukkit）在关闭时把合成网格物品还给物品栏（resetCraftingGridType -> inventory.addItem）并回发物品栏，但并不回发 UI 网格被清空。
            // 在此处清空 2x2/3x3 合成网格加输出的镜像，这样残留的 3x3 物品不会滞留，也不会在下次打开合成界面时泄漏到 2x2 视图中。
            if (pending instanceof CraftingTableContainer) {
                final Container hud = tracker.getHudContainer();
                for (int slot = 28; slot <= 40; slot++) {
                    hud.setItemSilent(slot, BedrockItem.empty());
                }
                hud.setItemSilent(50, BedrockItem.empty());
            }
            if (pending instanceof AnvilContainer) {
                final AnvilSessionStorage session = wrapper.user().get(AnvilSessionStorage.class);
                if (session != null) {
                    session.clear();
                }
            }
            if (pending instanceof TradeContainer) {
                final TradeSessionStorage session = wrapper.user().get(TradeSessionStorage.class);
                if (session != null) {
                    session.clear();
                }
            }
        });
    }

    private void registerContainerClickHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLICK, wrapper -> {
            final int containerId = wrapper.read(Types.VAR_INT); // 容器 id
            final int revision = wrapper.read(Types.VAR_INT); // 修订号
            final short slot = wrapper.read(Types.SHORT); // 槽位
            final byte button = wrapper.read(Types.BYTE); // 按钮
            final int actionId = wrapper.read(Types.VAR_INT); // 操作
            if (actionId < 0 || actionId >= ContainerInput.values().length) {
                wrapper.cancel();
                return;
            }
            final ContainerInput action = ContainerInput.values()[actionId];
            final int changedSlotCount = Limit.max(wrapper.read(Types.VAR_INT), 128);
            if (changedSlotCount < 0) {
                wrapper.cancel();
                return;
            }
            final Map<Short, HashedItem> changedSlots = new LinkedHashMap<>(changedSlotCount);
            boolean validPrediction = true;
            for (int i = 0; i < changedSlotCount; i++) {
                final short changedSlot = wrapper.read(Types.SHORT);
                final HashedItem changedItem = wrapper.read(Types.HASHED_ITEM);
                if (changedSlots.put(changedSlot, changedItem) != null) {
                    validPrediction = false;
                }
            }
            final HashedItem carriedItem = wrapper.read(Types.HASHED_ITEM);

            wrapper.cancel(); // 阻止原处理器执行

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            if (inventoryTracker.getPendingCloseContainer() != null) {
                return;
            }

            // 解析容器引用
            final Container container;
            if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                container = inventoryTracker.getInventoryContainer();
            } else {
                container = inventoryTracker.getContainerServerbound(containerId);
                if (container == null) {
                    return;
                }
            }

            // Nukkit 通过 INTERACT.OpenInventory 打开玩家物品栏的 SAI。
            // 非实验性的回退路径已经发送该握手，但这个处理器负责玩家物品栏点击且此前跳过了它，
            // 因此 Take/Place 从未有已打开的容器，光标返回也无法关闭容器 -1。
            if (needsBedrockPlayerInventoryOpen(containerId, inventoryTracker.isBedrockPlayerInventoryOpen())) {
                PacketFactory.sendBedrockOpenInventory(wrapper.user());
            }

            final DragState dragState = wrapper.user().get(DragState.class);
            final List<InventoryActionData> actions = validPrediction ? runOrRollback(
                    () -> ClickSimulator.validateArmorActions(
                            ClickSimulator.simulate(containerId, slot, button, action, inventoryTracker, dragState, changedSlots, carriedItem),
                            inventoryTracker),
                    error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                            "Failed to simulate Java container click; rolling back to the authoritative inventory", error)) : null;

            if (actions == null) {
                // 不支持的操作 — 把容器内容回滚到权威镜像
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
                return;
            }
            if (actions.isEmpty()) {
                return; // 空操作，无需发送数据包
            }
            if (!allowsLockedActions(actions)) {
                dragState.reset();
                resyncAfterRejectedClick(wrapper.user(), inventoryTracker, containerId, container);
                return;
            }

            final InventorySnapshot snapshot = InventorySnapshot.capture(inventoryTracker);
            if (!sendPredictedActions(wrapper.user(), actions, snapshot)) {
                dragState.reset();
                resyncAfterRejectedClick(wrapper.user(), inventoryTracker, containerId, container);
                return;
            }

            // MOT 在服务端计算铁砧结果。保留 Java 的铁砧/光标预测，等待 INVENTORY_SLOT/CONTENT，
            // 而不要把输入物品塞到 HUD 光标上。
            if (AnvilSimulator.isTakeResult(actions)
                    || CartographySimulator.isTakeResult(actions)
                    || GrindstoneSimulator.isTakeResult(actions)
                    || LoomSimulator.isTakeResult(actions)
                    || StonecutterSimulator.isTakeResult(actions)
                    || SmithingSimulator.isTakeResult(actions)
                    || TradeSimulator.isTakeResult(actions)) {
                // MOT 成功的 ItemStackResponse 只携带增量。把期望的结果标识保留在镜像中，
                // 以便空目标也能被重建。
                applyMirrorUpdates(actions, inventoryTracker);
                return;
            }

            // 乐观地把预测结果提交到我们的镜像，再推送给 Java。此前我们在不提交预测的情况下把 Java 重置回点击前状态，
            // 导致每个操作在视觉上都会回滚，用户不得不点击两次（第二次点击又会失去同步，因为服务器已经应用了第一次）。
            // 服务器仍是权威来源，若它拒绝该事务，会通过服务端→客户端的物品栏数据包纠正我们。
            applyMirrorUpdates(actions, inventoryTracker);
            if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            sendJavaCursor(wrapper.user(), inventoryTracker);
            updateCraftingOutputPreview(wrapper.user());
            // 注意：真实基岩客户端在正常事务后从不会发送 InventoryMismatch。
        });
    }

    public static boolean tryHandleSwapHands(final UserConnection user) {
        return tryHandleSwapHands(user, true);
    }

    /**
     * MOT 的 CLICK_AIR / CLICK_BLOCK 只读取 {@code getItemInHand()}。Java 的副手使用通过在 MOT 上交换这两个槽位来实现，之后可选择恢复。
     * {@code syncJava=false} 让 Java 客户端继续显示原来的双手物品。
     */
    public static boolean tryHandleSwapHands(final UserConnection user, final boolean syncJava) {
        return tryHandleSwapHandsTracked(user, syncJava).submitted();
    }

    public static SwapHandsResult tryHandleSwapHandsTracked(final UserConnection user, final boolean syncJava) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker.getPendingCloseContainer() != null) {
            if (syncJava) {
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            }
            return SwapHandsResult.failed();
        }

        final List<InventoryActionData> actions = runOrRollback(
                () -> ClickSimulator.simulateSwapHands(tracker),
                error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Failed to simulate Java hand swap; rolling back to the authoritative inventory", error));
        if (actions == null) {
            if (syncJava) {
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            }
            return SwapHandsResult.failed();
        }
        if (actions.isEmpty()) {
            return SwapHandsResult.withoutRequest();
        }
        if (!allowsLockedActions(actions)) {
            if (syncJava) {
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            }
            return SwapHandsResult.failed();
        }
        final boolean openedForThisAction = needsBedrockPlayerInventoryOpen(tracker, ContainerID.CONTAINER_ID_INVENTORY.getValue());
        if (openedForThisAction) {
            PacketFactory.sendBedrockOpenInventory(user);
        }

        final PredictedActionsResult submission = sendPredictedActionsTracked(user, actions);
        if (!submission.submitted()) {
            if (syncJava) {
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            }
            if (openedForThisAction) {
                closeTransientBedrockPlayerInventory(tracker);
            }
            return SwapHandsResult.failed();
        }
        applyMirrorUpdates(actions, tracker);
        if (syncJava) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        }
        // Java 物品栏仍打开时 F 也能用。只有当这次交换是 SAI 握手、而不是已经打开的 E 界面时，才回退 MOT。
        if (openedForThisAction) {
            closeTransientBedrockPlayerInventory(tracker);
        }
        return new SwapHandsResult(true, submission.requestId());
    }

    /**
     * Java 的 Q / Ctrl-Q 是 PLAYER_ACTION 的 DROP_ITEM / DROP_ALL_ITEMS。MOT 860 在启用 SAI 时会丢弃旧版 TYPE_NORMAL InventoryTransaction，
     * 因此同一个预测的槽位增量必须以 ITEM_STACK_REQUEST Drop 的形式发送。
     * 参考：MOT Player.java 的 isInventorySAIGateActive；DropActionProcessor。
     */
    public static boolean tryHandleHotbarDrop(final UserConnection user, final boolean dropAll) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker == null) {
            return false;
        }
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
        if (clientPlayer != null && GameTypeRewriter.isMotSpectator(clientPlayer.gameType())) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }
        if (tracker.getPendingCloseContainer() != null) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }
        final BedrockItem currentItem = tracker.getInventoryContainer().getSelectedHotbarItem();
        if (currentItem == null || currentItem.isEmpty()) {
            return true;
        }
        final int dropped = dropAll ? currentItem.amount() : 1;
        if (dropped <= 0) {
            return true;
        }
        BedrockItem remaining = currentItem.copy();
        if (dropAll || remaining.amount() <= dropped) {
            remaining = BedrockItem.empty();
        } else {
            remaining.setAmount(currentItem.amount() - dropped);
        }
        final BedrockItem droppedStack = currentItem.copy();
        droppedStack.setAmount(dropped);
        final List<InventoryActionData> actions = List.of(
                new InventoryActionData(
                        new InventorySource(InventorySourceType.WorldInteraction, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                        0,
                        BedrockItem.empty(),
                        droppedStack
                ),
                new InventoryActionData(
                        new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                        tracker.getInventoryContainer().getSelectedHotbarSlot(),
                        currentItem,
                        remaining
                )
        );
        if (!allowsLockedActions(actions)) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            return true;
        }
        final boolean openedForThisAction = needsBedrockPlayerInventoryOpen(tracker, ContainerID.CONTAINER_ID_INVENTORY.getValue());
        if (openedForThisAction) {
            PacketFactory.sendBedrockOpenInventory(user);
        }
        if (!sendPredictedActions(user, actions)) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            if (openedForThisAction) {
                closeTransientBedrockPlayerInventory(tracker);
            }
            return true;
        }
        applyMirrorUpdates(actions, tracker);
        PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        // 快捷栏的 Q 从不会打开 Java GUI。除非 Java 的 E 界面已经打开（Interact.OpenInventory 已确认），否则保持 MOT 关闭。
        if (openedForThisAction) {
            closeTransientBedrockPlayerInventory(tracker);
        }
        return true;
    }

    public static boolean returnCursorBeforeClose(final UserConnection user) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final List<InventoryActionData> actions = runOrRollback(
                () -> ClickSimulator.simulateCursorReturn(
                        tracker, JavaItemStackLimits.forTracker(tracker)),
                error -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Failed to return the Java cursor before closing the Bedrock container", error));
        if (actions == null || !allowsLockedActions(actions)) {
            return false;
        }
        if (!actions.isEmpty()) {
            if (needsBedrockPlayerInventoryOpen(tracker, ContainerID.CONTAINER_ID_INVENTORY.getValue())) {
                PacketFactory.sendBedrockOpenInventory(user);
            }
            if (!sendPredictedActions(user, actions)) {
                return false;
            }
            applyMirrorUpdates(actions, tracker);
            sendChangedJavaInventorySlots(user, tracker, actions);
            scheduleJavaCursor(user, tracker);
        }
        return true;
    }

    private static void resyncAfterRejectedClick(final UserConnection user, final InventoryTracker tracker,
                                                 final int containerId, final Container container) {
        if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        }
        PacketFactory.sendJavaContainerSetContent(user, container);
        sendJavaCursor(user, tracker);
    }

    public static void handleItemStackResponse(final UserConnection user, final ItemStackResponseLayout.DecodedResponse decoded) {
        if (user == null || decoded == null) {
            return;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker == null) {
            return;
        }
        if (decoded.entries() != null && !decoded.entries().isEmpty()) {
            boolean javaResyncRequired = false;
            for (final ItemStackResponseLayout.DecodedEntry entry : decoded.entries()) {
                if (entry.ok()) {
                    tracker.takePendingItemStackRequest(entry.requestId());
                    continue;
                }
                if (restoreRejectedItemStackRequest(tracker, entry.requestId())
                        && !isSilentOffhandRestoreRequest(user, entry.requestId())) {
                    javaResyncRequired = true;
                }
            }
            applyStackResponse(tracker, decoded);
            if (javaResyncRequired) {
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
                if (tracker.getCurrentContainer() != null && tracker.getCurrentContainer() != tracker.getInventoryContainer()) {
                    PacketFactory.sendJavaContainerSetContent(user, tracker.getCurrentContainer());
                }
                sendJavaCursor(user, tracker);
            }
            return;
        }
        if (!decoded.anyRejected()) {
            // MOT 的成功响应包含权威的 stackNetworkId。
            // actor 不会重发 INVENTORY_SLOT/CONTENT，所以预测的镜像必须打上这些 id，否则下一次点击会过不了 netId 校验。
            // 参考：MOT ItemStackRequestHandler.handleRequests 的 OK 路径与 TransferItemActionProcessor.buildContainer。
            if (decoded.requestIds().length != 0) {
                for (final int requestId : decoded.requestIds()) {
                    tracker.takePendingItemStackRequest(requestId);
                }
            } else {
                tracker.takeLatestPendingItemStackRequest();
            }
            applyStackResponse(tracker, decoded);
            return;
        }
        InventorySnapshot snapshot = null;
        boolean silentOffhandRestore = false;
        if (decoded.requestIds().length != 0) {
            for (final int requestId : decoded.requestIds()) {
                snapshot = tracker.takePendingItemStackRequest(requestId);
                if (snapshot != null) {
                    silentOffhandRestore = isSilentOffhandRestoreRequest(user, requestId);
                    break;
                }
            }
        }
        if (snapshot == null) {
            final Integer restoringRequestId = offhandRestoreRequestId(user);
            snapshot = tracker.takeLatestPendingItemStackRequest();
            silentOffhandRestore = snapshot != null && restoringRequestId != null;
        }
        if (snapshot == null) {
            return;
        }
        if (!snapshot.restore(tracker) || silentOffhandRestore) {
            return;
        }
        PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        if (tracker.getCurrentContainer() != null && tracker.getCurrentContainer() != tracker.getInventoryContainer()) {
            PacketFactory.sendJavaContainerSetContent(user, tracker.getCurrentContainer());
        }
        sendJavaCursor(user, tracker);
    }

    static boolean restoreRejectedItemStackRequest(final InventoryTracker tracker, final int requestId) {
        if (tracker == null) {
            return false;
        }
        final InventorySnapshot snapshot = tracker.takePendingItemStackRequest(requestId);
        return snapshot != null && snapshot.restore(tracker);
    }

    private static boolean isSilentOffhandRestoreRequest(final UserConnection user, final int requestId) {
        return Integer.valueOf(requestId).equals(offhandRestoreRequestId(user));
    }

    private static Integer offhandRestoreRequestId(final UserConnection user) {
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker == null) {
            return null;
        }
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        return clientPlayer != null && clientPlayer.isOffhandRestoring()
                ? clientPlayer.offhandRestoreRequestId()
                : null;
    }

    /**
     * 把 MOT ITEM_STACK_RESPONSE 的 netId/数量写入 SAI 镜像。
     * 不推送 Java CONTAINER_SET_CONTENT：Java 已经有预测的物品，而 ISR 没有可用于重建的物品 id/NBT。
     */
    static void applyStackResponse(final InventoryTracker tracker, final ItemStackResponseLayout.DecodedResponse decoded) {
        if (tracker == null || decoded == null || decoded.entries() == null) {
            return;
        }
        for (final ItemStackResponseLayout.DecodedEntry entry : decoded.entries()) {
            if (entry == null || !entry.ok() || entry.containers() == null) {
                continue;
            }
            for (final ItemStackResponseLayout.DecodedContainer container : entry.containers()) {
                if (container == null || container.slots() == null) {
                    continue;
                }
                for (final ItemStackResponseLayout.DecodedSlot slot : container.slots()) {
                    applyStackResponseSlot(tracker, container, slot);
                }
            }
        }
    }

    static void applyStackResponseSlot(final InventoryTracker tracker, final ItemStackResponseLayout.DecodedContainer container,
                                       final ItemStackResponseLayout.DecodedSlot slot) {
        if (tracker == null || container == null || slot == null) {
            return;
        }
        final SlotMapper.BedrockSlotRef ref = ItemStackSlotMapper.resolveResponseSlot(tracker, container.container(), slot.slot());
        if (ref == null || ref.container() == null) {
            return;
        }
        if (slot.count() <= 0) {
            ref.container().setItemSilent(ref.slot(), BedrockItem.empty());
            return;
        }
        final BedrockItem current = safeItem(ref.container(), ref.slot());
        if (current == null || current.isEmpty()) {
            // ISR 没有标识符/NBT。保留预测的空槽位，而不是虚构一个物品；后续的 CONTENT/SLOT 仍可填充它。
            return;
        }
        final BedrockItem updated = current.copy();
        updated.setAmount(slot.count());
        // MOT 把客户端 netId 0 视为「跳过校验」。当成功条目省略了真实的 stackNetworkId 时，保留预测的 id。
        if (slot.stackNetworkId() > 0) {
            updated.setNetId(slot.stackNetworkId());
        }
        applyStackResponseItemData(updated, slot);
        ref.container().setItemSilent(ref.slot(), updated);
    }

    static void applyStackResponseItemData(final BedrockItem item, final ItemStackResponseLayout.DecodedSlot slot) {
        if (item == null || slot == null) {
            return;
        }
        if (slot.durabilityCorrection() != null) {
            // MOT 用 Item#getDamage() 填充此字段，因此这是一个绝对值。
            item.setData(slot.durabilityCorrection());
        }
        if (slot.customName() == null) {
            return;
        }

        final String customName = slot.filteredCustomName() != null && !slot.filteredCustomName().isEmpty()
                ? slot.filteredCustomName() : slot.customName();
        final CompoundTag tag = item.tag() != null ? item.tag().copy() : new CompoundTag();
        CompoundTag display = tag.getCompoundTag("display");
        if (customName.isEmpty()) {
            if (display != null) {
                display.remove("Name");
                if (display.isEmpty()) {
                    tag.remove("display");
                }
            }
        } else {
            if (display == null) {
                display = new CompoundTag();
                tag.put("display", display);
            }
            display.putString("Name", customName);
        }
        item.setTag(tag.isEmpty() ? null : tag);
    }

    private static BedrockItem safeItem(final Container container, final int slot) {
        if (container == null || slot < 0 || slot >= container.size()) {
            return BedrockItem.empty();
        }
        final BedrockItem item = container.getItem(slot);
        return item != null ? item : BedrockItem.empty();
    }

    private void registerSelectTradeHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.SELECT_TRADE, wrapper -> {
            wrapper.cancel();
            final int selectedSlot = wrapper.read(Types.VAR_INT);
            final TradeSessionStorage session = wrapper.user().get(TradeSessionStorage.class);
            if (session == null) {
                return;
            }
            if (selectedSlot < 0 || selectedSlot >= session.offers().size()) {
                session.setSelectedSlot(-1);
                return;
            }
            session.setSelectedSlot(selectedSlot);
        });
    }

    private void registerCrafterSlotStateHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CONTAINER_SLOT_STATE_CHANGED, wrapper -> {
            wrapper.cancel();
            final int slot = wrapper.read(Types.VAR_INT);
            final int windowId = wrapper.read(Types.VAR_INT);
            final boolean enabled = wrapper.read(Types.BOOLEAN);
            sendCrafterSlotToggle(wrapper.user(), windowId, slot, enabled);
        });
    }

    static void sendCrafterSlotToggle(final UserConnection user, final int javaWindowId, final int slot, final boolean enabled) {
        if (slot < 0 || slot >= 9) {
            return;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker == null) {
            return;
        }
        final Container container = tracker.getContainerServerbound(javaWindowId);
        if (container == null || container.type() != ContainerType.CRAFTER || container.position() == null) {
            return;
        }
        // MOT ToggleCrafterSlotRequestPacket：3 个小端 int 方块坐标、byte 槽位、boolean 禁用标记。
        final PacketWrapper toggle = PacketWrapper.create(ServerboundBedrockPackets.TOGGLE_CRAFTER_SLOT_REQUEST, user);
        toggle.write(BedrockTypes.INT_LE, container.position().x());
        toggle.write(BedrockTypes.INT_LE, container.position().y());
        toggle.write(BedrockTypes.INT_LE, container.position().z());
        toggle.write(Types.BYTE, (byte) slot);
        toggle.write(Types.BOOLEAN, !enabled);
        toggle.sendToServer(BedrockProtocol.class);
    }

    private void registerSetBeaconHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.SET_BEACON, wrapper -> {
            wrapper.cancel();
            Integer primary = null;
            Integer secondary = null;
            if (wrapper.read(Types.BOOLEAN)) {
                primary = wrapper.read(Types.VAR_INT);
            }
            if (wrapper.read(Types.BOOLEAN)) {
                secondary = wrapper.read(Types.VAR_INT);
            }
            if (!BeaconPayment.send(wrapper.user(), primary, secondary)) {
                final InventoryTracker tracker = wrapper.user().get(InventoryTracker.class);
                final Container container = tracker.getCurrentContainer();
                if (container != null) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
                }
            }
        });
    }

    private void registerCreativeContentHandler(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CREATIVE_CONTENT, null, wrapper -> {
            wrapper.cancel();
            final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
            final int protocolVersion = emulateNetEase ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : net.raphimc.viabedrock.protocol.data.ProtocolConstants.BEDROCK_PROTOCOL_VERSION;
            final List<CreativeContentCache.Entry> entries = CreativeContentLayout.read(
                    wrapper, CreativeContentLayout.itemType(), emulateNetEase, protocolVersion);
            wrapper.user().get(CreativeContentCache.class).replace(entries);
        });
    }

    private void registerCreativeModeSlotHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT, wrapper -> {
            if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                return;
            }
            wrapper.cancel();
            final short slot = wrapper.read(Types.SHORT);
            final Item item = wrapper.read(VersionedTypes.V26_1.lengthPrefixedItem);
            final InventoryTracker tracker = wrapper.user().get(InventoryTracker.class);
            if (tracker.getPendingCloseContainer() != null) {
                return;
            }
            if (!wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), tracker.getInventoryContainer());
                sendJavaCursor(wrapper.user(), tracker);
                return;
            }
            if (needsBedrockPlayerInventoryOpen(ContainerID.CONTAINER_ID_INVENTORY.getValue(), tracker.isBedrockPlayerInventoryOpen())) {
                PacketFactory.sendBedrockOpenInventory(wrapper.user());
            }
            final CreativeSlotSemantics.Plan plan = CreativeSlotSemantics.plan(
                    slot, item, tracker, wrapper.user().get(ItemRewriter.class), wrapper.user().get(CreativeContentCache.class));
            if (plan.isUnsupported()) {
                // 不要干预 JE 乐观的创造模式预测。强制从基岩镜像重新同步正是让被点击物品消失的原因（#1-1）。
                // 不要在这里虚构「拿到光标」：清空槽位就是 Destroy。
                return;
            }
            if (plan.isEmpty()) {
                return;
            }
            final ItemStackRequestEncoder.EncodedRequest encoded = encodeCreativePlan(plan, tracker);
            if (encoded.unsupported() || encoded.isEmpty()) {
                // 与 Plan.unsupported() 相同：不要清空光标或物品栏。
                return;
            }
            final InventorySnapshot snapshot = InventorySnapshot.capture(tracker);
            sendItemStackRequest(wrapper.user(), encoded, snapshot);
            CreativeSlotSemantics.applyPredictedPlan(slot, plan, tracker);
        });
    }

    static ItemStackRequestEncoder.EncodedRequest encodeCreativePlan(final CreativeSlotSemantics.Plan plan, final InventoryTracker tracker) {
        if (plan == null || plan.isEmpty()) {
            return ItemStackRequestEncoder.EncodedRequest.empty();
        }
        if (plan.isUnsupported()) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final List<InventoryActionData> actions = new ArrayList<>();
        if (plan.kind() == CreativeSlotSemantics.Kind.DESTROY) {
            return encodeDestroy(plan, tracker);
        }
        final BedrockItem spawned = plan.predicted() == null ? BedrockItem.empty() : plan.predicted().copy();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.CreativeInventory, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), spawned));
        final CreativeDestination destination = resolveCreativeDestination(plan.destination());
        if (destination != null) {
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, destination.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    destination.slot(), CreativeSlotSemantics.currentItem(destination.javaSlot(), tracker), spawned));
        }
        return ItemStackRequestEncoder.encode(actions, tracker);
    }

    static CreativeDestination resolveCreativeDestination(final ItemStackRequestLayout.SlotInfo destination) {
        if (destination == null || destination.container() == null) {
            return null;
        }
        return switch (destination.container()) {
            case CursorContainer -> new CreativeDestination(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 0, CreativeSlotSemantics.JAVA_CURSOR_SLOT);
            case HotbarContainer -> new CreativeDestination(ContainerID.CONTAINER_ID_INVENTORY.getValue(), destination.slot(), destination.slot() + 36);
            case InventoryContainer -> new CreativeDestination(ContainerID.CONTAINER_ID_INVENTORY.getValue(), destination.slot(), destination.slot());
            case ArmorContainer -> new CreativeDestination(ContainerID.CONTAINER_ID_ARMOR.getValue(), destination.slot(), destination.slot() + 5);
            case OffhandContainer -> new CreativeDestination(ContainerID.CONTAINER_ID_OFFHAND.getValue(), 0, 45);
            default -> null;
        };
    }

    record CreativeDestination(int containerId, int slot, int javaSlot) {
    }

    private static ItemStackRequestEncoder.EncodedRequest encodeDestroy(final CreativeSlotSemantics.Plan plan, final InventoryTracker tracker) {
        final io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        try {
            net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            final int requestId = tracker.nextItemStackRequestId();
            net.raphimc.viabedrock.protocol.types.BedrockTypes.VAR_INT.write(buffer, requestId);
            net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            final int protocol = creativeEncodeProtocol();
            ItemStackRequestLayout.writeDestroy(buffer, plan.count(), plan.destination(), true, protocol);
            ItemStackRequestLayout.writeRequestTrailer(buffer, true, protocol);
            final byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return ItemStackRequestEncoder.EncodedRequest.of(payload, requestId);
        } finally {
            buffer.release();
        }
    }

    private static int creativeEncodeProtocol() {
        if (ViaBedrock.getConfig() != null && ViaBedrock.getConfig().getNetEaseProtocolVersion() > 0) {
            return ViaBedrock.getConfig().getNetEaseProtocolVersion();
        }
        return 860;
    }

    private static boolean sendPredictedActions(final UserConnection user, final List<InventoryActionData> actions) {
        return sendPredictedActionsTracked(user, actions).submitted();
    }

    private static PredictedActionsResult sendPredictedActionsTracked(final UserConnection user,
                                                                      final List<InventoryActionData> actions) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        return sendPredictedActionsTracked(user, actions, InventorySnapshot.capture(tracker));
    }

    private static boolean sendPredictedActions(final UserConnection user, final List<InventoryActionData> actions,
                                                final InventorySnapshot snapshot) {
        return sendPredictedActionsTracked(user, actions, snapshot).submitted();
    }

    private static PredictedActionsResult sendPredictedActionsTracked(final UserConnection user,
                                                                      final List<InventoryActionData> actions,
                                                                      final InventorySnapshot snapshot) {
        if (actions == null || actions.isEmpty()) {
            return PredictedActionsResult.withoutRequest();
        }
        final GameSessionStorage session = user.get(GameSessionStorage.class);
        if (session == null || !session.isInventoryServerAuthoritative()) {
            sendNormalTransaction(user, actions);
            return PredictedActionsResult.withoutRequest();
        }

        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (!canSendItemStackRequest(tracker)) {
            return PredictedActionsResult.failed();
        }
        final ItemStackRequestEncoder.EncodedRequest specialRequest = encodeSpecialTakeResult(user, actions, tracker);
        final ItemStackRequestEncoder.EncodedRequest encoded = specialRequest != null
                ? specialRequest
                : ItemStackRequestEncoder.encode(actions, tracker);
        if (encoded.unsupported()) {
            return PredictedActionsResult.failed();
        }
        if (encoded.isEmpty()) {
            return PredictedActionsResult.withoutRequest();
        }
        sendItemStackRequest(user, encoded, snapshot);
        return new PredictedActionsResult(true, encoded.requestId());
    }

    static boolean canSendItemStackRequest(final InventoryTracker tracker) {
        return tracker != null && tracker.pendingItemStackRequestCount() == 0;
    }

    private static ItemStackRequestEncoder.EncodedRequest encodeSpecialTakeResult(final UserConnection user,
                                                                                  final List<InventoryActionData> actions,
                                                                                  final InventoryTracker tracker) {
        if (AnvilSimulator.isTakeResult(actions)) {
            return AnvilSimulator.encodeTakeResult(user, tracker);
        }
        if (CartographySimulator.isTakeResult(actions)) {
            return CartographySimulator.encodeTakeResult(tracker);
        }
        if (GrindstoneSimulator.isTakeResult(actions)) {
            return GrindstoneSimulator.encodeTakeResult(tracker);
        }
        if (LoomSimulator.isTakeResult(actions)) {
            return LoomSimulator.encodeTakeResult(user, tracker);
        }
        if (StonecutterSimulator.isTakeResult(actions)) {
            return StonecutterSimulator.encodeTakeResult(tracker);
        }
        if (SmithingSimulator.isTakeResult(actions)) {
            return SmithingSimulator.encodeTakeResult(tracker);
        }
        if (TradeSimulator.isTakeResult(actions)) {
            return TradeSimulator.encodeTakeResult(tracker);
        }
        return null;
    }

    private static void sendItemStackRequest(final UserConnection user, final ItemStackRequestEncoder.EncodedRequest encoded,
                                             final InventorySnapshot snapshot) {
        if (encoded == null || encoded.payload() == null) {
            return;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        if (tracker != null && encoded.requestId() != 0 && snapshot != null) {
            tracker.rememberPendingItemStackRequest(encoded.requestId(), snapshot);
        }
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(Types.REMAINING_BYTES, encoded.payload());
        request.sendToServer(BedrockProtocol.class);
    }

    private static void sendNormalTransaction(final UserConnection user, final List<InventoryActionData> actions) {
        final InventoryTransactionRewriter txRewriter = user.get(InventoryTransactionRewriter.class);
        final PacketWrapper txPacket = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        txPacket.write(txRewriter.getInventoryTransactionType(),
                new BedrockInventoryTransaction(
                        0,
                        null,
                        actions,
                        ComplexInventoryTransaction_Type.NormalTransaction,
                        new InventoryTransactionData.NormalTransactionData()
                ));
        txPacket.sendToServer(BedrockProtocol.class);
    }

    private static void sendChangedJavaInventorySlots(final UserConnection user, final InventoryTracker tracker,
                                                      final List<InventoryActionData> actions) {
        final Container inventory = tracker.getInventoryContainer();
        for (final int slot : changedJavaInventorySlots(actions, tracker)) {
            final int bedrockSlot = slot >= 36 ? slot - 36 : slot;
            final PacketWrapper setSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
            setSlot.write(Types.VAR_INT, ContainerID.CONTAINER_ID_INVENTORY.getValue());
            setSlot.write(Types.VAR_INT, 0);
            setSlot.write(Types.SHORT, (short) slot);
            setSlot.write(VersionedTypes.V26_1.item, inventory.getJavaItem(bedrockSlot));
            setSlot.send(BedrockProtocol.class);
        }
    }

    static List<Integer> changedJavaInventorySlots(final List<InventoryActionData> actions,
                                                   final InventoryTracker tracker) {
        final LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.ContainerInventory
                    && action.source().containerId() == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                slots.add(tracker.getInventoryContainer().javaSlot(action.slot()));
            }
        }
        return List.copyOf(slots);
    }

    static <T> T runOrRollback(final Supplier<T> simulation, final Consumer<RuntimeException> failureHandler) {
        try {
            return simulation.get();
        } catch (final RuntimeException e) {
            failureHandler.accept(e);
            return null;
        }
    }

    static boolean needsBedrockPlayerInventoryOpen(final int containerId, final boolean bedrockInventoryOpen) {
        return needsBedrockPlayerInventoryOpen(containerId, bedrockInventoryOpen, false);
    }

    /**
     * Interact.OpenInventory 只在当前没有 MOT 窗口时用于临时的 SAI 握手。
     * 在箱子/工作台打开时，Java 的 F/Q 不能发送 action 6：那样 MOT 会发出 CONTAINER_OPEN type=-1，
     * ViaBedrock 以前会回弹 CONTAINER_CLOSE -1，而 Player.java:4065-4076 会关闭箱子。
     * DropActionProcessor / SwapActionProcessor 并不要求 inventoryOpen。
     */
    static boolean needsBedrockPlayerInventoryOpen(final InventoryTracker tracker, final int containerId) {
        if (tracker == null) {
            return false;
        }
        return needsBedrockPlayerInventoryOpen(
                containerId,
                tracker.isBedrockPlayerInventoryOpen(),
                tracker.getCurrentContainer() != null || tracker.getPendingCloseContainer() != null);
    }

    static boolean needsBedrockPlayerInventoryOpen(final int containerId, final boolean bedrockInventoryOpen,
                                                   final boolean containerAlreadyOpen) {
        return containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()
                && !bedrockInventoryOpen
                && !containerAlreadyOpen;
    }

    /**
     * Java 的 Q / F 从不打开 GUI。Interact.OpenInventory 仍会为 SAI 的 Drop/Swap 打开 MOT 玩家物品栏。
     * 立即关闭窗口 -1，这样 {@code inventoryOpen} 不会保持为真而导致后续攻击/箱子操作失败。
     * DropActionProcessor 本身并不要求 inventoryOpen。
     * 参考：MOT Player.java 的 Interact action 6、inventoryOpen 时的 USE_ITEM_ON_ENTITY ATTACK、addWindow。
     */
    static void closeTransientBedrockPlayerInventory(final InventoryTracker tracker) {
        if (tracker != null) {
            tracker.closeTransientBedrockPlayerInventory();
        }
    }

    private static final int HUD_OUTPUT_SLOT = 50;

    /**
     * Java 依赖服务器把合成结果推入输出槽，但基岩在客户端计算预览且从不发送。
     * 这里通过已加载的配方表从（镜像中的）合成网格重新计算结果，并推入 Java 输出槽（槽位 0），2x2 物品栏网格与 3x3 工作台都适用。
     * 结果同时镜像到 HUD 槽位 50，使容器的 getJavaItems（对槽位 0 读取 HUD 50）保持一致。
     */
    public static void updateCraftingOutputPreview(final UserConnection user) {
        final RecipeRegistry registry = user.get(RecipeRegistry.class);
        if (registry == null) {
            return;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final Container current = tracker.getCurrentContainer();

        final boolean is3x3;
        final int javaWindowId;
        if (current instanceof CraftingTableContainer) {
            is3x3 = true;
            javaWindowId = current.javaContainerId();
        } else if (current == null || current.type() == ContainerType.INVENTORY) {
            is3x3 = false;
            javaWindowId = ContainerID.CONTAINER_ID_INVENTORY.getValue();
        } else {
            return; // 此界面没有合成网格
        }

        final BedrockItem[] gridItems = CraftingSimulator.getGridItems(is3x3, tracker);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        final BedrockItem output = recipe != null ? recipe.primaryOutput().copy() : BedrockItem.empty();

        final Container hud = tracker.getHudContainer();
        final BedrockItem previous = hud.getItem(HUD_OUTPUT_SLOT);
        if (!previous.isDifferent(output) && previous.amount() == output.amount()) {
            return; // 无变化，避免冗余数据包
        }
        hud.setItemSilent(HUD_OUTPUT_SLOT, output);

        final Item javaOutput = user.get(ItemRewriter.class).javaItem(output);
        final PacketWrapper setSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        setSlot.write(Types.VAR_INT, javaWindowId); // 窗口 id
        setSlot.write(Types.VAR_INT, 0); // 修订号
        setSlot.write(Types.SHORT, (short) 0); // 槽位 0 = 合成输出
        setSlot.write(VersionedTypes.V26_1.item, javaOutput);
        setSlot.send(BedrockProtocol.class);
    }

    /**
     * 把当前光标物品（HUD 槽位 0）以 SET_CURSOR_ITEM 推送给 Java 客户端。
     * sendJavaContainerSetContent 不包含光标，因此在乐观预测移动了光标之后必须显式同步。
     */
    private static void sendJavaCursor(final UserConnection user, final InventoryTracker tracker) {
        final BedrockItem cursor = tracker.getHudContainer().getItem(0);
        final Item javaCursor = user.get(ItemRewriter.class).javaItem(cursor);
        final PacketWrapper setCursor = PacketWrapper.create(ClientboundPackets26_1.SET_CURSOR_ITEM, user);
        setCursor.write(VersionedTypes.V26_1.item, javaCursor);
        setCursor.send(BedrockProtocol.class);
    }

    private static void scheduleJavaCursor(final UserConnection user, final InventoryTracker tracker) {
        final BedrockItem cursor = tracker.getHudContainer().getItem(0);
        final Item javaCursor = user.get(ItemRewriter.class).javaItem(cursor);
        final PacketWrapper setCursor = PacketWrapper.create(ClientboundPackets26_1.SET_CURSOR_ITEM, user);
        setCursor.write(VersionedTypes.V26_1.item, javaCursor);
        setCursor.scheduleSend(BedrockProtocol.class);
    }

    static void clearPlayerCraftingGrid(final InventoryTracker tracker) {
        final Container hud = tracker.getHudContainer();
        for (int slot = 28; slot <= 31; slot++) {
            hud.setItemSilent(slot, BedrockItem.empty());
        }
        hud.setItemSilent(HUD_OUTPUT_SLOT, BedrockItem.empty());
    }

    /**
     * 把预期的物品栏状态变更应用到容器镜像。
     * 在客户端权威模式下，客户端乐观地应用变更。
     * 如果服务器拒绝该事务，它会发送回滚数据包来纠正镜像。
     */
    private static void applyMirrorUpdates(final List<InventoryActionData> actions, final InventoryTracker tracker) {
        for (final InventoryActionData action : actions) {
            // 只有 ContainerInventory 操作会改我们镜像中的槽位（合成网格槽位、光标、物品栏、盔甲、副手）。
            // SOURCE_TODO 合成标记（-5 USE_INGREDIENT / -4 CRAFTING_RESULT）不带来镜像变化 — 网格扣减现在作为显式的 ContainerInventory 网格 SlotChange 发送，
            // 因此在这里也消费它们会导致网格被重复扣减。
            if (action.source().type() == InventorySourceType.ContainerInventory) {
                final Container container = resolveContainerById(action.source().containerId(), tracker);
                if (container != null) {
                    container.setItemSilent(action.slot(), action.toItem());
                }
            }
            // 跳过 WorldInteraction（丢弃）与 CreativeInventory 操作
        }
    }

    private static Container resolveContainerById(final int containerId, final InventoryTracker tracker) {
        if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) return tracker.getInventoryContainer();
        if (containerId == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()) return tracker.getHudContainer();
        if (containerId == ContainerID.CONTAINER_ID_ARMOR.getValue()) return tracker.getArmorContainer();
        if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) return tracker.getOffhandContainer();
        if (tracker.getCurrentContainer() != null
                && InventoryTracker.matchesBedrockContainerId(tracker.getCurrentContainer(), containerId)) {
            return tracker.getCurrentContainer();
        }
        return tracker.getContainerServerbound(containerId);
    }

    // --- DragState（QUICK_CRAFT 的按连接存储）---

    public static class DragState extends StoredObject {
        private int dragMode = -1;
        private final List<Short> dragSlots = new ArrayList<>();

        public DragState(final UserConnection user) {
            super(user);
        }

        public void begin(int mode) {
            this.dragMode = mode;
            this.dragSlots.clear();
        }

        public void addSlot(short javaSlot) {
            this.dragSlots.add(javaSlot);
        }

        public void reset() {
            this.dragMode = -1;
            this.dragSlots.clear();
        }

        public int getDragMode() {
            return dragMode;
        }

        public List<Short> getDragSlots() {
            return new ArrayList<>(dragSlots);
        }

    }


    private static boolean allowsLockedActions(final List<InventoryActionData> actions) {
        try {
            return BedrockItemLockPolicy.allows(actions);
        } catch (final NoClassDefFoundError | Exception e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "Failed to evaluate Bedrock item lock policy; allowing inventory action", e);
            return true;
        }
    }

}






