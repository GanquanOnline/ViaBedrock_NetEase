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
import net.raphimc.viabedrock.protocol.packet.PlayerEnchantOptionsLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前打开的附魔台最近一次 PLAYER_ENCHANT_OPTIONS 负载。
 * Java 只保存三个按钮（id 0-2）；MOT 最多发布三个选项，
 * 之后的 CraftRecipe 会使用匹配的 {@code enchantNetId}。
 */
public final class EnchantingSessionStorage extends StoredObject {

    private List<PlayerEnchantOptionsLayout.EnchantOption> options = List.of();
    private int seed;

    public EnchantingSessionStorage(final UserConnection user) {
        super(user);
    }

    public void setOptions(final List<PlayerEnchantOptionsLayout.EnchantOption> options) {
        this.options = options == null || options.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(options));
        this.seed = hashSeed(this.options);
    }

    public void clear() {
        this.options = List.of();
        this.seed = 0;
    }

    public List<PlayerEnchantOptionsLayout.EnchantOption> options() {
        return this.options;
    }

    public PlayerEnchantOptionsLayout.EnchantOption option(final int javaButtonId) {
        if (javaButtonId < 0 || javaButtonId >= this.options.size()) {
            return null;
        }
        return this.options.get(javaButtonId);
    }

    public int seed() {
        return this.seed;
    }

    private static int hashSeed(final List<PlayerEnchantOptionsLayout.EnchantOption> options) {
        int hash = 1;
        for (PlayerEnchantOptionsLayout.EnchantOption option : options) {
            hash = 31 * hash + option.enchantNetId();
            hash = 31 * hash + option.minLevel();
            hash = 31 * hash + option.primarySlot();
        }
        return hash;
    }
}
