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
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;

/**
 * MOT 的 {@code TradeInventory} 有三个槽位（buyA、buyB、未使用的结果）。
 * 村民通过 {@code UPDATE_TRADE} 而非 {@code CONTAINER_OPEN} 打开，
 * 因此此容器没有方块位置，也不会因方块标签而按距离关闭。
 */
public class TradeContainer extends Container {

    public static final byte MOT_CLOSE_CONTAINER_ID = -1;
    public static final int JAVA_WINDOW_ID = 500;

    public TradeContainer(final UserConnection user, final byte containerId, final TextComponent title) {
        super(user, unsignedWindowId(containerId), ContainerType.TRADE, title, null, 3);
    }

    static byte unsignedWindowId(final byte windowId) {
        // MOT 的 UPDATE_TRADE/INVENTORY_CONTENT 使用无符号 500，而包中的 BYTE 为 -12。
        return windowId == 0 ? (byte) JAVA_WINDOW_ID : windowId;
    }

    @Override
    public int javaContainerId() {
        // MOT 分配窗口 500。Java 的 MERCHANT_OFFERS/CONTAINER_CLICK 是 VAR_INT，
        // 因此保留未截断的 id，而不是有符号字节的 244/-12 别名。
        return JAVA_WINDOW_ID;
    }

    @Override
    public byte bedrockCloseContainerId() {
        // MOT 查找窗口 500，但 CONTAINER_CLOSE 只携带一个有符号字节。
        // 因此 860 的村民路径把客户端关闭 -1 视为「关闭交易」。
        return MOT_CLOSE_CONTAINER_ID;
    }
}
