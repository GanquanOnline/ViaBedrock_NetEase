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
package net.raphimc.viabedrock.api.model.container.player;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

public class HudContainer extends InventoryRedirectContainer {

    public HudContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), ContainerType.HUD, 54);
    }

    @Override
    public boolean setItem(final int slot, final BedrockItem item) {
        if (super.setItem(slot, item)) {
            if (slot == 0) return true; // 光标
            if (slot >= 28 && slot <= 31) return true; // 2x2 合成网格
            if (slot >= 32 && slot <= 40) {
                // 3x3 合成网格：无法使用 SET_SLOT，因为 javaContainerId() 返回的是
                // 玩家物品栏（0），而这些槽位属于工作台窗口（1）。
                // 改为对工作台发送完整的 CONTAINER_SET_CONTENT。
                final InventoryTracker tracker = this.user.get(InventoryTracker.class);
                if (tracker != null) {
                    final Container current = tracker.getCurrentContainer();
                    if (current instanceof CraftingTableContainer) {
                        PacketFactory.sendJavaContainerSetContent(this.user, current);
                    }
                }
                return false;
            }
            return false;
        } else {
            return false;
        }
    }

    @Override
    public int javaSlot(final int slot) {
        if (slot >= 28 && slot <= 31) {
            // 2x2 合成输入 → Java 玩家物品栏槽位 1-4
            return slot - 27;
        } else if (slot >= 32 && slot <= 40) {
            // 3x3 合成输入 → Java 工作台槽位 1-9
            return slot - 31;
        } else {
            return super.javaSlot(slot);
        }
    }

}
