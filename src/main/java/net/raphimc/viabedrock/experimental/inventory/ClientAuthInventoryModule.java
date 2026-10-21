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
        // Java expects the crafting output preview to be pushed by the server, but Bedrock computes it
        // client-side and never sends it. Recompute it locally whenever the (server-authoritative) grid
        // contents change, so the Java output slot reflects the matched recipe's result.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_SLOT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.INVENTORY_CONTENT, wrapper -> updateCraftingOutputPreview(wrapper.user()));
        // Client-authoritative servers don't echo a clientbound CONTAINER_CLOSE for client-initiated
        // closes, so pendingCloseContainer would never clear and the container could not be reopened
        // (until the player walked away and the server force-closed it). Clear it right after the close
        // is forwarded to the server.
        ProtocolUtil.appendServerbound(protocol, ServerboundPackets26_1.CONTAINER_CLOSE, wrapper -> {
            final InventoryTracker tracker = wrapper.user().get(InventoryTracker.class);
            final Container pending = tracker.completePendingCloseWithoutConfirmation();
            // Java can open and close its player inventory without a matching Bedrock CONTAINER_OPEN.
            // In that case there is no pending container, but the predicted HUD cursor still has to be
            // discarded so it cannot be restored by the next full inventory sync.
            if (tracker.clearCursorIfContainerClosed()) {
                sendJavaCursor(wrapper.user(), tracker);
                clearPlayerCraftingGrid(tracker);
            }
            wrapper.user().get(DragState.class).reset();
            // The server (Nukkit) returns the crafting grid items to the inventory on close
            // (resetCraftingGridType -> inventory.addItem) and echoes the inventory, but it does NOT echo the
            // UI grid being emptied. Clear the 2x2/3x3 crafting grid + output mirror here so stale 3x3 items
            // don't linger and leak into the 2x2 view the next time a crafting screen is opened.
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
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final int actionId = wrapper.read(Types.VAR_INT); // action
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

            wrapper.cancel(); // Prevent original handler from executing

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            if (inventoryTracker.getPendingCloseContainer() != null) {
                return;
            }

            // Resolve container reference
            final Container container;
            if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                container = inventoryTracker.getInventoryContainer();
            } else {
                container = inventoryTracker.getContainerServerbound(containerId);
                if (container == null) {
                    return;
                }
            }

            // Nukkit opens player-inventory SAI through INTERACT.OpenInventory. The non-experimental
            // fallback already sends that handshake, but this handler owns player-inventory clicks
            // and previously skipped it, so Take/Place never had an open inventory and cursor return
            // could not close container -1.
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
                // Unsupported operation — roll back container contents to the authoritative mirror
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
                return;
            }
            if (actions.isEmpty()) {
                return; // No-op, no packet needed
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

            // MOT computes the anvil result server-side. Leave the Java anvil/cursor
            // prediction in place and wait for INVENTORY_SLOT/CONTENT instead of
            // stuffing the input item onto the HUD cursor.
            if (AnvilSimulator.isTakeResult(actions)
                    || CartographySimulator.isTakeResult(actions)
                    || GrindstoneSimulator.isTakeResult(actions)
                    || LoomSimulator.isTakeResult(actions)
                    || StonecutterSimulator.isTakeResult(actions)
                    || SmithingSimulator.isTakeResult(actions)
                    || TradeSimulator.isTakeResult(actions)) {
                // MOT's successful ItemStackResponse only carries deltas. Preserve the expected
                // result identity in the mirror so an empty target can be reconstructed.
                applyMirrorUpdates(actions, inventoryTracker);
                return;
            }

            // Optimistically commit the predicted result to our mirror, then push it to Java. Previously we
            // reset Java to the pre-click state without committing the prediction, which made every action
            // visually roll back and forced the user to click twice (the second click then desynced because
            // the server had already applied the first). The server stays authoritative and will correct us
            // via clientbound inventory packets if it rejects the transaction.
            applyMirrorUpdates(actions, inventoryTracker);
            if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            sendJavaCursor(wrapper.user(), inventoryTracker);
            updateCraftingOutputPreview(wrapper.user());
            // NOTE: real Bedrock clients never send an InventoryMismatch after a normal transaction.
        });
    }

    public static boolean tryHandleSwapHands(final UserConnection user) {
        return tryHandleSwapHands(user, true);
    }

    /**
     * MOT CLICK_AIR / CLICK_BLOCK only read {@code getItemInHand()}. Java offhand
     * use is promoted by swapping the two slots on MOT, then optionally restoring.
     * {@code syncJava=false} keeps the Java client showing the original hands.
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
        // F works with Java inventory still open. Only unwind MOT when this
        // swap was the SAI handshake, not an already-open E screen.
        if (openedForThisAction) {
            closeTransientBedrockPlayerInventory(tracker);
        }
        return new SwapHandsResult(true, submission.requestId());
    }

    /**
     * Java Q / Ctrl-Q is PLAYER_ACTION DROP_ITEM / DROP_ALL_ITEMS. MOT 860 with
     * SAI enabled drops legacy TYPE_NORMAL InventoryTransaction, so the same
     * predicted slot delta has to travel as ITEM_STACK_REQUEST Drop.
     * Ref: MOT Player.java isInventorySAIGateActive; DropActionProcessor.
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
        // Hotbar Q never opens a Java GUI. Keep MOT closed unless Java E was
        // already open (Interact.OpenInventory already acknowledged).
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
            // MOT success responses include the authoritative stackNetworkId.
            // The actor is not resent INVENTORY_SLOT/CONTENT, so the predicted
            // mirror must stamp those ids or the next click fails netId check.
            // Ref: MOT ItemStackRequestHandler.handleRequests OK path and
            // TransferItemActionProcessor.buildContainer.
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
     * Writes MOT ITEM_STACK_RESPONSE netIds/counts into the SAI mirror.
     * Does not push Java CONTAINER_SET_CONTENT: Java already has the predicted
     * items, and ISR has no item id/NBT to rebuild from.
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
            // ISR has no identifier/NBT. Keep the predicted empty slot rather
            // than inventing an item; later CONTENT/SLOT can still fill it.
            return;
        }
        final BedrockItem updated = current.copy();
        updated.setAmount(slot.count());
        // MOT treats client netId 0 as "skip check". Keep the predicted id when
        // the success entry omitted a real stackNetworkId.
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
            // MOT fills this with Item#getDamage(), so this is an absolute value.
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
        // MOT ToggleCrafterSlotRequestPacket: 3x LE int block pos, byte slot, boolean disabled.
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
                // Leave JE's optimistic creative prediction alone. Force-resyncing
                // from the Bedrock mirror is what made clicked items vanish (#1-1).
                // Do not invent Take-to-cursor here: emptying a slot is Destroy.
                return;
            }
            if (plan.isEmpty()) {
                return;
            }
            final ItemStackRequestEncoder.EncodedRequest encoded = encodeCreativePlan(plan, tracker);
            if (encoded.unsupported() || encoded.isEmpty()) {
                // Same as Plan.unsupported(): do not wipe the cursor or inventory.
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
     * Interact.OpenInventory is only for a transient SAI handshake when no
     * MOT window is already current. Java F/Q with a chest/table open must not
     * send action 6: MOT then emits CONTAINER_OPEN type=-1, ViaBedrock used to
     * bounce CONTAINER_CLOSE -1, and Player.java:4065-4076 closes the chest.
     * DropActionProcessor / SwapActionProcessor do not require inventoryOpen.
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
     * Java Q / F never open a GUI. Interact.OpenInventory still opens MOT player
     * inventory for SAI Drop/Swap. Close window -1 immediately so
     * {@code inventoryOpen} does not stick and later attacks / chests fail.
     * DropActionProcessor itself does not require inventoryOpen.
     * Ref: MOT Player.java Interact action 6, USE_ITEM_ON_ENTITY ATTACK while
     * inventoryOpen, addWindow.
     */
    static void closeTransientBedrockPlayerInventory(final InventoryTracker tracker) {
        if (tracker != null) {
            tracker.closeTransientBedrockPlayerInventory();
        }
    }

    private static final int HUD_OUTPUT_SLOT = 50;

    /**
     * Java relies on the server to push the crafting result into the output slot, but Bedrock computes the
     * preview client-side and never sends it. This recomputes the result from the (mirror) grid via the
     * loaded recipe table and pushes it into the Java output slot (slot 0) for both the 2x2 inventory grid
     * and the 3x3 crafting table. The result is mirrored into HUD slot 50 so the container's getJavaItems
     * (which reads HUD 50 for slot 0) stays consistent.
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
            return; // No crafting grid in this screen
        }

        final BedrockItem[] gridItems = CraftingSimulator.getGridItems(is3x3, tracker);
        final BedrockRecipe recipe = registry.matchRecipe(gridItems, is3x3);
        final BedrockItem output = recipe != null ? recipe.primaryOutput().copy() : BedrockItem.empty();

        final Container hud = tracker.getHudContainer();
        final BedrockItem previous = hud.getItem(HUD_OUTPUT_SLOT);
        if (!previous.isDifferent(output) && previous.amount() == output.amount()) {
            return; // No change, avoid redundant packets
        }
        hud.setItemSilent(HUD_OUTPUT_SLOT, output);

        final Item javaOutput = user.get(ItemRewriter.class).javaItem(output);
        final PacketWrapper setSlot = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_SLOT, user);
        setSlot.write(Types.VAR_INT, javaWindowId); // window id
        setSlot.write(Types.VAR_INT, 0); // revision
        setSlot.write(Types.SHORT, (short) 0); // slot 0 = crafting output
        setSlot.write(VersionedTypes.V26_1.item, javaOutput);
        setSlot.send(BedrockProtocol.class);
    }

    /**
     * Pushes the current cursor item (HUD slot 0) to the Java client as SET_CURSOR_ITEM. sendJavaContainerSetContent
     * does not include the cursor, so after an optimistic prediction that moved the cursor we must sync it explicitly.
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
     * Applies the expected inventory state changes to the container mirror.
     * In client-authoritative mode, the client applies changes optimistically.
     * If the server rejects the transaction, it will send revert packets to correct the mirror.
     */
    private static void applyMirrorUpdates(final List<InventoryActionData> actions, final InventoryTracker tracker) {
        for (final InventoryActionData action : actions) {
            // Only ContainerInventory actions mutate slots in our mirror (grid slots, cursor, inventory,
            // armor, offhand). SOURCE_TODO craft markers (-5 USE_INGREDIENT / -4 CRAFTING_RESULT) carry no
            // mirror change — the grid decrement is now sent as an explicit ContainerInventory grid SlotChange,
            // so consuming them here too would double-decrement the grid.
            if (action.source().type() == InventorySourceType.ContainerInventory) {
                final Container container = resolveContainerById(action.source().containerId(), tracker);
                if (container != null) {
                    container.setItemSilent(action.slot(), action.toItem());
                }
            }
            // Skip WorldInteraction (drops) and CreativeInventory actions
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

    // --- DragState (per-connection storage for QUICK_CRAFT) ---

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






