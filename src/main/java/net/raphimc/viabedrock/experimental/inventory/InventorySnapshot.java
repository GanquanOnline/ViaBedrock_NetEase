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

import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * SAI 镜像在点击前的副本。只有在之后没有更新的权威 INVENTORY_CONTENT/INVENTORY_SLOT 纠正过镜像时，被拒绝的请求才会恢复它。
 * 已打开容器的内容也与捕获时的窗口绑定。
 */
public final class InventorySnapshot {

    private final BedrockItem[] inventory;
    private final BedrockItem[] hud;
    private final BedrockItem[] armor;
    private final BedrockItem[] offhand;
    private final BedrockItem[] current;
    private final long inventoryGeneration;
    private final long hudGeneration;
    private final long armorGeneration;
    private final long offhandGeneration;
    private final long currentGeneration;
    private final Container currentContainer;
    private final byte currentContainerId;
    private final int currentJavaContainerId;

    private InventorySnapshot(final BedrockItem[] inventory, final BedrockItem[] hud, final BedrockItem[] armor,
                              final BedrockItem[] offhand, final BedrockItem[] current,
                              final long inventoryGeneration, final long hudGeneration,
                              final long armorGeneration, final long offhandGeneration,
                              final long currentGeneration, final Container currentContainer,
                              final byte currentContainerId, final int currentJavaContainerId) {
        this.inventory = inventory;
        this.hud = hud;
        this.armor = armor;
        this.offhand = offhand;
        this.current = current;
        this.inventoryGeneration = inventoryGeneration;
        this.hudGeneration = hudGeneration;
        this.armorGeneration = armorGeneration;
        this.offhandGeneration = offhandGeneration;
        this.currentGeneration = currentGeneration;
        this.currentContainer = currentContainer;
        this.currentContainerId = currentContainerId;
        this.currentJavaContainerId = currentJavaContainerId;
    }

    public static InventorySnapshot capture(final InventoryTracker tracker) {
        if (tracker == null) {
            return null;
        }
        final Container inventory = tracker.getInventoryContainer();
        final Container hud = tracker.getHudContainer();
        final Container armor = tracker.getArmorContainer();
        final Container offhand = tracker.getOffhandContainer();
        final long inventoryGeneration = tracker.authoritativeInventoryGeneration(inventory);
        final long hudGeneration = tracker.authoritativeInventoryGeneration(hud);
        final long armorGeneration = tracker.authoritativeInventoryGeneration(armor);
        final long offhandGeneration = tracker.authoritativeInventoryGeneration(offhand);
        final Container open = tracker.getCurrentContainer();
        final boolean captureCurrent = open != null && open != tracker.getInventoryContainer();
        return new InventorySnapshot(
                copy(inventory),
                copy(hud),
                copy(armor),
                copy(offhand),
                captureCurrent ? copy(open) : null,
                inventoryGeneration,
                hudGeneration,
                armorGeneration,
                offhandGeneration,
                captureCurrent ? tracker.authoritativeInventoryGeneration(open) : 0L,
                captureCurrent ? open : null,
                captureCurrent ? open.containerId() : 0,
                captureCurrent ? open.javaContainerId() : -1
        );
    }

    public boolean restore(final InventoryTracker tracker) {
        if (tracker == null) {
            return false;
        }
        boolean restored = false;
        if (tracker.authoritativeInventoryGeneration(tracker.getInventoryContainer()) == this.inventoryGeneration) {
            restored |= restoreInto(tracker.getInventoryContainer(), this.inventory);
        }
        if (tracker.authoritativeInventoryGeneration(tracker.getHudContainer()) == this.hudGeneration) {
            restored |= restoreInto(tracker.getHudContainer(), this.hud);
        }
        if (tracker.authoritativeInventoryGeneration(tracker.getArmorContainer()) == this.armorGeneration) {
            restored |= restoreInto(tracker.getArmorContainer(), this.armor);
        }
        if (tracker.authoritativeInventoryGeneration(tracker.getOffhandContainer()) == this.offhandGeneration) {
            restored |= restoreInto(tracker.getOffhandContainer(), this.offhand);
        }
        final Container open = tracker.getCurrentContainer();
        if (open == this.currentContainer
                && open != null
                && open.containerId() == this.currentContainerId
                && open.javaContainerId() == this.currentJavaContainerId
                && tracker.authoritativeInventoryGeneration(open) == this.currentGeneration) {
            restored |= restoreInto(open, this.current);
        }
        return restored;
    }

    private static BedrockItem[] copy(final Container container) {
        if (container == null) {
            return null;
        }
        final BedrockItem[] items = container.getItems();
        final BedrockItem[] copy = new BedrockItem[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] != null ? items[i].copy() : BedrockItem.empty();
        }
        return copy;
    }

    private static boolean restoreInto(final Container container, final BedrockItem[] items) {
        if (container == null || items == null) {
            return false;
        }
        final int size = Math.min(container.size(), items.length);
        for (int i = 0; i < size; i++) {
            container.setItemSilent(i, items[i] != null ? items[i].copy() : BedrockItem.empty());
        }
        return true;
    }
}
