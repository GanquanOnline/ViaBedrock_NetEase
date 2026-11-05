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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * 工作台容器。3x3 网格存放在 HUD 容器中（槽位 32-40），
 * 输出为 HUD 槽位 50。此容器向 Java 暴露 10 个槽位（输出 + 9 个网格槽位）。
 * minecraft:crafting 的 Java 槽位布局（共 46 个）：
 * 0 = 输出，1-9 = 网格，10-36 = 主物品栏，37-45 = 快捷栏
 */
public class CraftingTableContainer extends Container {

    public CraftingTableContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.WORKBENCH, title, position, 10, CustomBlockTags.CRAFTING_TABLE);
    }

    @Override
    public int javaContainerId() {
        return 1;
    }

    @Override
    public Item[] getJavaItems() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final InventoryTracker tracker = this.user.get(InventoryTracker.class);
        final Container hudContainer = tracker.getHudContainer();
        final Container inventoryContainer = tracker.getInventoryContainer();

        // Java 工作台窗口：46 个槽位
        // 槽位 0 = 输出（HUD 50）
        // 槽位 1-9 = 3x3 网格（HUD 32-40）
        // 槽位 10-36 = 主物品栏（物品栏 9-35）
        // 槽位 37-45 = 快捷栏（物品栏 0-8）
        final Item[] javaItems = new Item[46];

        // 输出
        javaItems[0] = itemRewriter.javaItem(hudContainer.getItem(50));

        // 3x3 网格
        for (int i = 0; i < 9; i++) {
            javaItems[1 + i] = itemRewriter.javaItem(hudContainer.getItem(32 + i));
        }

        // 主物品栏（9-35）
        for (int i = 9; i <= 35; i++) {
            javaItems[1 + i] = itemRewriter.javaItem(inventoryContainer.getItem(i));
        }

        // 快捷栏（0-8）
        for (int i = 0; i < 9; i++) {
            javaItems[37 + i] = itemRewriter.javaItem(inventoryContainer.getItem(i));
        }

        return javaItems;
    }

}
