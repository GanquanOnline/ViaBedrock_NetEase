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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 的制图台结果点击会变成带 {@code CartographyText} 的 MOT {@code CraftRecipeOptional}。
 * MOT 会把结果写入 HUD 的 created-output 槽位 50。
 */
public final class CartographySimulator {

    static final int TODO_CARTOGRAPHY_RESULT = -7;

    private CartographySimulator() {
    }

    public static boolean isCartography(final Container container) {
        return container != null && container.type() == ContainerType.CARTOGRAPHY;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isCartography(container)) {
            return null;
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem additional = container.getItem(1);
        final int additionalCount = additional != null && !additional.isEmpty() ? Math.min(1, additional.amount()) : 0;
        final BedrockItem result = input.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CARTOGRAPHY_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                0, input.copy(), BedrockItem.empty()));
        if (additionalCount > 0) {
            final BedrockItem leftover = additional.copy();
            leftover.setAmount(additional.amount() - additionalCount);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    1, additional.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover));
        }
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return hasTodoMarker(actions, TODO_CARTOGRAPHY_RESULT) && hasCursorTakeResult(actions);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions) {
        return CartographySimulator.isQuickMoveResult(actions, TODO_CARTOGRAPHY_RESULT);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions, final int marker) {
        return hasTodoMarker(actions, marker) && hasPlayerInventoryTakeResult(actions);
    }

    static int resultAmount(final List<InventoryActionData> actions, final int marker) {
        if (actions == null) return -1;
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.NonImplementedFeatureTODO
                    && action.source().containerId() == marker
                    && !action.fromItem().isEmpty()) {
                return action.fromItem().amount();
            }
        }
        return -1;
    }

    static int destinationAmount(final List<InventoryActionData> actions) {
        if (actions == null) return -1;
        int total = 0;
        for (final InventoryActionData action : actions) {
            if (action.source().type() != InventorySourceType.ContainerInventory
                    || action.source().containerId() != ContainerID.CONTAINER_ID_INVENTORY.getValue()
                    || action.toItem().isEmpty()) {
                continue;
            }
            final int before = action.fromItem().isEmpty() ? 0 : action.fromItem().amount();
            if (action.toItem().amount() <= before) continue;
            total += action.toItem().amount() - before;
        }
        return total;
    }

    static ItemStackRequestEncoder.EncodedRequest encodeQuickMoveResult(
            final InventoryTracker tracker, final List<InventoryActionData> actions,
            final boolean emulateNetEase, final int protocol) {
        final Container container = tracker.getCurrentContainer();
        if (!isCartography(container) || !isQuickMoveResult(actions)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem additional = container.getItem(1);
        final int additionalCount = additional != null && !additional.isEmpty() ? Math.min(1, additional.amount()) : 0;
        final int takeCount = destinationAmount(actions);
        if (takeCount <= 0) return ItemStackRequestEncoder.EncodedRequest.notSupported();
        return ItemStackRequestEncoder.encodeCartographyApplyToDestinations(tracker, 1, additionalCount, takeCount, actions, emulateNetEase, protocol);
    }

    public static ItemStackRequestEncoder.EncodedRequest encodeTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isCartography(container)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem additional = container.getItem(1);
        final int additionalCount = additional != null && !additional.isEmpty() ? Math.min(1, additional.amount()) : 0;
        return ItemStackRequestEncoder.encodeCartographyApply(tracker, 1, additionalCount, 1);
    }

    static boolean hasTodoMarker(final List<InventoryActionData> actions, final int marker) {
        if (actions == null) {
            return false;
        }
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.NonImplementedFeatureTODO
                    && action.source().containerId() == marker) {
                return true;
            }
        }
        return false;
    }

    static boolean hasCursorTakeResult(final List<InventoryActionData> actions) {
        if (actions == null) return false;
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.ContainerInventory
                    && action.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()
                    && action.slot() == 0 && action.fromItem().isEmpty() && !action.toItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasPlayerInventoryTakeResult(final List<InventoryActionData> actions) {
        if (actions == null) return false;
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.ContainerInventory
                    && action.source().containerId() == ContainerID.CONTAINER_ID_INVENTORY.getValue()
                    && !action.toItem().isEmpty()
                    && action.toItem().amount() > action.fromItem().amount()) {
                return true;
            }
        }
        return false;
    }
}
