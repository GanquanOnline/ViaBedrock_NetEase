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
import net.raphimc.viabedrock.api.model.container.TradeContainer;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.TradeOfferLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.storage.TradeSessionStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 的商人结果点击会变成 MOT {@code CraftRecipe}（{@code netId >= 0x20000000}），
 * 加上对 TRADE2 材料槽位 0/1 的 Consume，然后从 HUD created-output 槽位 50 Take。
 */
public final class TradeSimulator {

    static final int TODO_TRADE_RESULT = -12;
    static final int JAVA_RESULT_SLOT = 2;

    private TradeSimulator() {
    }

    public static boolean isTrade(final Container container) {
        return container instanceof TradeContainer;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isTrade(container)) {
            return null;
        }
        final TradeOfferLayout.Offer offer = selectedOffer(tracker);
        if (offer == null) {
            return null;
        }
        final BedrockItem buyA = container.getItem(0);
        final int buyACount = offer.buyACount();
        if (buyACount > 0 && (buyA == null || buyA.isEmpty() || buyA.amount() < buyACount)) {
            return null;
        }
        final int buyBCount = offer.buyBCount();
        final BedrockItem buyB = container.getItem(1);
        if (buyBCount > 0 && (buyB == null || buyB.isEmpty() || buyB.amount() < buyBCount)) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem result = buyA != null && !buyA.isEmpty() ? buyA.copy() : BedrockItem.empty();
        result.setAmount(1);
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_TRADE_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        if (buyACount > 0) {
            final BedrockItem leftoverA = buyA.copy();
            leftoverA.setAmount(buyA.amount() - buyACount);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    0, buyA.copy(), leftoverA.amount() <= 0 ? BedrockItem.empty() : leftoverA));
        }
        if (buyBCount > 0) {
            final BedrockItem leftoverB = buyB.copy();
            leftoverB.setAmount(buyB.amount() - buyBCount);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    1, buyB.copy(), leftoverB.amount() <= 0 ? BedrockItem.empty() : leftoverB));
        }
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return CartographySimulator.hasTodoMarker(actions, TODO_TRADE_RESULT)
                && CartographySimulator.hasCursorTakeResult(actions);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions) {
        return CartographySimulator.isQuickMoveResult(actions, TODO_TRADE_RESULT);
    }

    static ItemStackRequestEncoder.EncodedRequest encodeQuickMoveResult(
            final InventoryTracker tracker, final List<InventoryActionData> actions,
            final boolean emulateNetEase, final int protocol) {
        final Container container = tracker.getCurrentContainer();
        if (!isTrade(container) || !isQuickMoveResult(actions)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final TradeOfferLayout.Offer offer = selectedOffer(tracker);
        final int takeCount = CartographySimulator.destinationAmount(actions);
        if (offer == null || takeCount <= 0) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeTradeApplyToDestinations(
                tracker, offer.netId(), offer.buyACount(), offer.buyBCount(), takeCount, actions, emulateNetEase, protocol);
    }

    public static ItemStackRequestEncoder.EncodedRequest encodeTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isTrade(container)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final TradeOfferLayout.Offer offer = selectedOffer(tracker);
        if (offer == null) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeTradeApply(
                tracker, offer.netId(), offer.buyACount(), offer.buyBCount(), 1);
    }

    private static TradeOfferLayout.Offer selectedOffer(final InventoryTracker tracker) {
        if (tracker == null || tracker.user() == null) {
            return null;
        }
        final TradeSessionStorage session = tracker.user().get(TradeSessionStorage.class);
        return session != null ? session.selectedOffer() : null;
    }
}
