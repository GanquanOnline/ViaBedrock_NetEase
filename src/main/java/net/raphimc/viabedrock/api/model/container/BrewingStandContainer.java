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
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

public class BrewingStandContainer extends Container {

    public BrewingStandContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.BREWING_STAND, title, position, 5, CustomBlockTags.BREWING_STAND);
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 0 -> 3; // 原料 -> Java 槽位 3
            case 1 -> 0; // 药水 1 -> Java 槽位 0
            case 2 -> 1; // 药水 2 -> Java 槽位 1
            case 3 -> 2; // 药水 3 -> Java 槽位 2
            default -> slot; // 燃料 (4) -> Java 槽位 4
        };
    }

    @Override
    public int bedrockSlot(final int javaSlot) {
        return switch (javaSlot) {
            case 0 -> 1; // Java 药水 1 -> 基岩槽位 1
            case 1 -> 2; // Java 药水 2 -> 基岩槽位 2
            case 2 -> 3; // Java 药水 3 -> 基岩槽位 3
            case 3 -> 0; // Java 原料 -> 基岩槽位 0
            default -> javaSlot; // 燃料仍为 4
        };
    }

    @Override
    public Item[] getJavaItems() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final Item[] javaItems = new Item[5];
        javaItems[0] = itemRewriter.javaItem(this.items[1]); // 药水 1
        javaItems[1] = itemRewriter.javaItem(this.items[2]); // 药水 2
        javaItems[2] = itemRewriter.javaItem(this.items[3]); // 药水 3
        javaItems[3] = itemRewriter.javaItem(this.items[0]); // 原料
        javaItems[4] = itemRewriter.javaItem(this.items[4]); // 燃料
        return javaItems;
    }

}
