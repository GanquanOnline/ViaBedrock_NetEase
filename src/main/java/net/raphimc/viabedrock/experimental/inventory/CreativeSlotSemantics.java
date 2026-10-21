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

import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.experimental.inventory.SlotMapper.BedrockSlotRef;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * Turns Java SET_CREATIVE_MODE_SLOT into Nukkit 860 SAI CraftCreative / Destroy.
 * Official 975 keeps the old cancel-and-resync path; this helper is NetEase-only.
 */
public final class CreativeSlotSemantics {

    public static final int JAVA_CURSOR_SLOT = -1;
    public static final int CREATED_OUTPUT_SLOT = 50;

    private CreativeSlotSemantics() {
    }

    public static boolean isJavaCursorSlot(final int javaSlot) {
        return javaSlot == JAVA_CURSOR_SLOT;
    }

    public static ItemStackRequestLayout.SlotInfo destinationSlot(final int javaSlot, final InventoryTracker tracker) {
        if (isJavaCursorSlot(javaSlot)) {
            return ItemStackSlotMapper.hud(0);
        }
        final BedrockSlotRef ref = SlotMapper.resolvePlayerInventory(javaSlot, tracker);
        return ItemStackSlotMapper.fromRef(ref);
    }

    public static BedrockItem currentItem(final int javaSlot, final InventoryTracker tracker) {
        if (isJavaCursorSlot(javaSlot)) {
            return SlotMapper.getCursorItem(tracker);
        }
        final BedrockSlotRef ref = SlotMapper.resolvePlayerInventory(javaSlot, tracker);
        if (ref == null || ref.container() == null) {
            return BedrockItem.empty();
        }
        return ref.container().getItem(ref.slot());
    }

    public static void applyPredictedItem(final int javaSlot, final BedrockItem item, final InventoryTracker tracker) {
        final BedrockItem next = item == null ? BedrockItem.empty() : item.copy();
        if (isJavaCursorSlot(javaSlot)) {
            tracker.getHudContainer().setItemSilent(0, next);
            return;
        }
        final BedrockSlotRef ref = SlotMapper.resolvePlayerInventory(javaSlot, tracker);
        if (ref != null && ref.container() != null) {
            ref.container().setItemSilent(ref.slot(), next);
        }
    }

    public static void applyPredictedPlan(final int javaSlot, final Plan plan, final InventoryTracker tracker) {
        if (plan == null || plan.isEmpty() || plan.isUnsupported()) {
            return;
        }
        applyPredictedItem(javaSlot, plan.predicted(), tracker);
    }

    public static Plan plan(final int javaSlot, final Item javaItem, final InventoryTracker tracker,
                            final ItemRewriter itemRewriter, final CreativeContentCache cache) {
        final ItemStackRequestLayout.SlotInfo destination = destinationSlot(javaSlot, tracker);
        if (destination == null) {
            return Plan.unsupported();
        }
        final BedrockItem current = currentItem(javaSlot, tracker);
        final boolean spawn = javaItem != null && !javaItem.isEmpty();
        if (!spawn) {
            if (current.isEmpty()) {
                return Plan.empty();
            }
            // Java SET_CREATIVE_MODE_SLOT is an absolute slot assignment. Empty item
            // means destroy that slot (cursor, hotbar, backpack, armor, offhand).
            // Pickup/move is CONTAINER_CLICK; treating empties as Take duplicated stacks
            // and made creative-tab deletes a no-op.
            return Plan.destroy(current.amount(), withNetId(destination, current));
        }
        if (cache == null) {
            return Plan.unsupported();
        }
        final Integer netId = cache.findNetIdForJavaItem(itemRewriter, javaItem);
        if (netId == null) {
            return Plan.unsupported();
        }
        final BedrockItem spawned = spawnedItem(cache, netId, javaItem.amount());
        if (spawned == null) {
            return Plan.unsupported();
        }
        return Plan.spawn(netId, spawned.amount(), withNetId(destination, current), spawned);
    }

    public static Plan planClone(final BedrockItem cloned, final BedrockItem cursorItem, final CreativeContentCache cache) {
        if (cloned == null || cloned.isEmpty() || cache == null) {
            return Plan.unsupported();
        }
        final Integer netId = cache.findNetId(cloned);
        if (netId == null) {
            return Plan.unsupported();
        }
        final ItemStackRequestLayout.SlotInfo destination = ItemStackSlotMapper.hud(0);
        return Plan.spawn(netId, cloned.amount(), withNetId(destination, cursorItem), cloned.copy());
    }

    private static BedrockItem spawnedItem(final CreativeContentCache cache, final int netId, final int amount) {
        final BedrockItem item = cache.itemByNetId(netId);
        if (item == null || item.isEmpty()) {
            return null;
        }
        item.setAmount(Math.max(1, amount));
        item.setNetId(null);
        return item;
    }

    private static ItemStackRequestLayout.SlotInfo withNetId(final ItemStackRequestLayout.SlotInfo info, final BedrockItem item) {
        final int netId = item != null && item.netId() != null ? item.netId() : 0;
        return new ItemStackRequestLayout.SlotInfo(info.container(), info.slot(), netId, info.dynamicId());
    }

    public record Plan(Kind kind, int creativeNetId, int count, ItemStackRequestLayout.SlotInfo destination, BedrockItem predicted) {
        public static Plan empty() {
            return new Plan(Kind.EMPTY, 0, 0, null, BedrockItem.empty());
        }

        public static Plan unsupported() {
            return new Plan(Kind.UNSUPPORTED, 0, 0, null, null);
        }

        public static Plan destroy(final int count, final ItemStackRequestLayout.SlotInfo destination) {
            return new Plan(Kind.DESTROY, 0, count, destination, BedrockItem.empty());
        }

        public static Plan spawn(final int creativeNetId, final int count, final ItemStackRequestLayout.SlotInfo destination,
                                 final BedrockItem predicted) {
            return new Plan(Kind.SPAWN, creativeNetId, count, destination, predicted);
        }

        public boolean isEmpty() {
            return kind == Kind.EMPTY;
        }

        public boolean isUnsupported() {
            return kind == Kind.UNSUPPORTED;
        }
    }

    public enum Kind {
        EMPTY,
        UNSUPPORTED,
        DESTROY,
        SPAWN
    }
}
