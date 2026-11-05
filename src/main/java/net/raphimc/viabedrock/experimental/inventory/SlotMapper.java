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
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public class SlotMapper {

    public record BedrockSlotRef(int containerId, int slot, Container container) {}

    /**
     * 把玩家物品栏窗口（containerId=0）中的 Java 槽位解析为对应的基岩槽位。
     * 对不受支持的槽位返回 null（例如合成输出槽位 0）。
     */
    public static BedrockSlotRef resolvePlayerInventory(int javaSlot, InventoryTracker tracker) {
        if (javaSlot == 0) {
            // 合成输出槽位 — 由 CraftingSimulator 处理
            return null;
        } else if (javaSlot >= 1 && javaSlot <= 4) {
            // 合成输入槽位 — HUD 容器槽位 28-31
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 27 + javaSlot, tracker.getHudContainer());
        } else if (javaSlot >= 5 && javaSlot <= 8) {
            // 盔甲槽位 — Armor 容器槽位 0-3
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_ARMOR.getValue(), javaSlot - 5, tracker.getArmorContainer());
        } else if (javaSlot >= 9 && javaSlot <= 35) {
            // 主物品栏槽位 9-35 — Inventory 容器（直接映射）
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), javaSlot, tracker.getInventoryContainer());
        } else if (javaSlot >= 36 && javaSlot <= 44) {
            // 快捷栏槽位 36-44 — Inventory 容器槽位 0-8
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), javaSlot - 36, tracker.getInventoryContainer());
        } else if (javaSlot == 45) {
            // 副手槽位 — Offhand 容器槽位 0
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_OFFHAND.getValue(), 0, tracker.getOffhandContainer());
        }
        return null;
    }

    /**
     * 把工作台窗口中的 Java 槽位解析为对应的基岩槽位。
     * Java 合成窗口：槽位 0=输出，1-9=3x3 网格，10-36=主物品栏，37-45=快捷栏
     * 输出槽位 0 返回 null（由 CraftingSimulator 处理）。
     */
    public static BedrockSlotRef resolveCraftingTable(int javaSlot, InventoryTracker tracker) {
        if (javaSlot == 0) {
            // 输出槽位 — 由 CraftingSimulator 处理
            return null;
        } else if (javaSlot >= 1 && javaSlot <= 9) {
            // 3x3 网格 — HUD 容器槽位 32-40
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 31 + javaSlot, tracker.getHudContainer());
        } else if (javaSlot >= 10 && javaSlot <= 36) {
            // 主物品栏 — Inventory 容器槽位 9-35
            int inventorySlot = 9 + (javaSlot - 10);
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), inventorySlot, tracker.getInventoryContainer());
        } else if (javaSlot >= 37 && javaSlot <= 45) {
            // 快捷栏 — Inventory 容器槽位 0-8
            int hotbarSlot = javaSlot - 37;
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbarSlot, tracker.getInventoryContainer());
        }
        return null;
    }

    /**
     * 根据容器上下文把 Java 槽位解析为对应的基岩槽位。
     * containerId=0 时委托给 resolvePlayerInventory。
     * CraftingTableContainer 时委托给 resolveCraftingTable。
     * 其他容器按容器大小映射。
     * 对不受支持的槽位返回 null。
     */
    public static BedrockSlotRef resolve(int javaContainerId, int javaSlot, InventoryTracker tracker) {
        if (javaContainerId == 0) {
            return resolvePlayerInventory(javaSlot, tracker);
        }

        // 容器窗口
        final Container currentContainer = tracker.getCurrentContainer();
        if (currentContainer == null) {
            return null;
        }

        if (currentContainer instanceof CraftingTableContainer) {
            return resolveCraftingTable(javaSlot, tracker);
        }

        final int containerSize = currentContainer.size();

        if (javaSlot >= 0 && javaSlot < containerSize) {
            // 容器自身的槽位。酿造 / 锻造 Java 菜单不会等同映射到 MOT 的基岩数组上。
            return new BedrockSlotRef(currentContainer.containerId(), currentContainer.bedrockSlot(javaSlot), currentContainer);
        } else if (javaSlot >= containerSize && javaSlot < containerSize + 27) {
            // 玩家物品栏槽位 9-35（容器下方的主物品栏区域）
            int inventorySlot = 9 + (javaSlot - containerSize);
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), inventorySlot, tracker.getInventoryContainer());
        } else if (javaSlot >= containerSize + 27 && javaSlot < containerSize + 36) {
            // 快捷栏槽位 0-8
            int hotbarSlot = javaSlot - containerSize - 27;
            return new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), hotbarSlot, tracker.getInventoryContainer());
        }
        return null;
    }

    /** 从 HUD 容器槽位 0 获取光标物品。 */
    public static BedrockItem getCursorItem(InventoryTracker tracker) {
        return tracker.getHudContainer().getItem(0);
    }

}
