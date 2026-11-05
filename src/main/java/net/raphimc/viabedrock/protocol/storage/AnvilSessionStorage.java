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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.packet.FilterTextLayout;

/**
 * 当前打开的铁砧最近一次 Java 铁砧重命名字符串。
 * MOT 在玩家取走结果时从 {@code ItemStackRequest.filterStrings} 应用名称，
 * 而不是从 FILTER_TEXT 本身。
 */
public final class AnvilSessionStorage extends StoredObject {

    private String renameText = "";

    public AnvilSessionStorage(final UserConnection user) {
        super(user);
    }

    public void setRenameText(final String renameText) {
        this.renameText = FilterTextLayout.sanitize(renameText);
    }

    public String renameText() {
        return this.renameText;
    }

    public void clear() {
        this.renameText = "";
    }
}
