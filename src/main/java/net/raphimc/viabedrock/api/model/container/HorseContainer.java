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
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;

/**
 * MOT 的 {@code InventoryType.HORSE}（网络类型 12）对应 {@code HorseInventory}：
 * 槽位 0 为鞍，槽位 1 为护甲，槽位 2+ 为箱子货物（{@code 2 + chestSize}）。
 * Java 1.21.11 通过 {@code MOUNT_SCREEN_OPEN} 打开该界面，而不是 {@code OPEN_SCREEN}。
 * 参考：MOT {@code HorseInventory}、{@code ContainerInventory.onOpen}、
 * {@code NetworkMapping} 的 {@code HORSE_EQUIP}。
 */
public class HorseContainer extends Container {

    public static final int SLOT_SADDLE = 0;
    public static final int SLOT_ARMOR = 1;
    public static final int SLOT_CHEST_BASE = 2;
    public static final int DEFAULT_SIZE = 2;
    public static final int CHESTED_CARGO_SLOTS = 15;

    private final long entityUniqueId;
    private final int javaEntityId;

    public HorseContainer(final UserConnection user, final byte containerId, final TextComponent title,
                          final BlockPosition position, final int size,
                          final long entityUniqueId, final int javaEntityId) {
        super(user, containerId, ContainerType.HORSE, title, position, Math.max(DEFAULT_SIZE, size));
        this.entityUniqueId = entityUniqueId;
        this.javaEntityId = javaEntityId;
    }

    public long entityUniqueId() {
        return this.entityUniqueId;
    }

    public int javaEntityId() {
        return this.javaEntityId;
    }

    public int chestSize() {
        return Math.max(0, this.size() - DEFAULT_SIZE);
    }

    /**
     * Java 1.21+ {@code MOUNT_SCREEN_OPEN} 的物品栏列数。
     * 货物槽位数为鞍 / 护甲两格之后的 {@code columns * 3}。
     * 参考：ViaVersion 1.20.5→1.21 的 {@code HORSE_SCREEN_OPEN} 重写对旧槽位数字段
     * 使用 {@code (size - 1) / 3}；MOT 的货物数为 {@code size - 2}，在 {@code size = 2 + 3n}
     * 时两者列数相同。
     */
    public int javaColumns() {
        return javaColumns(this.size());
    }

    public static int javaColumns(final int size) {
        return Math.max(0, (Math.max(DEFAULT_SIZE, size) - DEFAULT_SIZE) / 3);
    }

    public HorseContainer withSize(final int newSize) {
        final int normalized = Math.max(DEFAULT_SIZE, newSize);
        if (normalized == this.size()) {
            return this;
        }
        final HorseContainer resized = new HorseContainer(this.user, this.containerId(), this.title(),
                this.position(), normalized, this.entityUniqueId, this.javaEntityId);
        final int copied = Math.min(this.size(), resized.size());
        for (int slot = 0; slot < copied; slot++) {
            resized.setItemSilent(slot, this.getItem(slot));
        }
        return resized;
    }

    /**
     * 在 {@code INVENTORY_CONTENT} 到达之前推断 MOT {@code HorseInventory} 的长度。
     * 驴使用 {@code CHESTED} → 15 个货物槽位；羊驼使用 {@code STRENGTH * 3}；
     * 当 MOT 实际写入 {@code CONTAINER_SIZE} 时以其为准。
     */
    public static int sizeFor(final Entity entity) {
        if (entity == null) {
            return DEFAULT_SIZE;
        }
        final Integer containerSize = intData(entity, ActorDataIDs.CONTAINER_SIZE);
        if (containerSize != null && containerSize >= DEFAULT_SIZE) {
            return containerSize;
        }
        final Integer strength = intData(entity, ActorDataIDs.STRENGTH);
        if (strength != null && strength > 0) {
            return DEFAULT_SIZE + strength * 3;
        }
        if (entity.hasEntityFlag(ActorFlags.CHESTED)) {
            return DEFAULT_SIZE + CHESTED_CARGO_SLOTS;
        }
        return DEFAULT_SIZE;
    }

    private static Integer intData(final Entity entity, final ActorDataIDs id) {
        final EntityData data = entity.entityData().get(id);
        if (data == null || data.value() == null) {
            return null;
        }
        if (data.value() instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

}
