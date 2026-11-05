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
package net.raphimc.viabedrock.experimental.types.inventory;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.packet.InventoryTransactionLayout;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public class InventorySourcePacketType extends Type<InventorySource> {

    public InventorySourcePacketType() {
        super(InventorySource.class);
    }

    @Override
    public InventorySource read(ByteBuf buffer) {
        return read(buffer, InventoryTransactionLayout.usesSourcePresencePairs(BedrockWire.current()));
    }

    /**
     * 1001 起动作来源不再按类型分支，而是两组“外层存在性 + 内层存在性”的 bool 对：
     * 第一组内层为 true 时有符号单字节 windowId，第二组内层为 true 时是无符号 varint 旗标。
     * 外层两个 bool 跟 MOT 写侧一样固定为 true，但读侧仍要把它读掉。
     */
    public static InventorySource read(ByteBuf buffer, final boolean presencePairs) {
        final int rawTypeId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final InventorySourceType type = InventorySourceType.getByValue(rawTypeId);
        if (type == null) {
            throw new IllegalStateException("Invalid inventory source type id: " + rawTypeId);
        }

        if (presencePairs) {
            final boolean hasContainerOuter = buffer.readBoolean();
            final boolean hasContainerInner = buffer.readBoolean();
            final int containerId = hasContainerOuter && hasContainerInner
                    ? buffer.readByte() : ContainerID.CONTAINER_ID_NONE.getValue();
            final boolean hasWorldOuter = buffer.readBoolean();
            final boolean hasWorldInner = buffer.readBoolean();
            final InventorySource_InventorySourceFlags flags;
            if (hasWorldOuter && hasWorldInner) {
                final int rawSourceFlagId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                flags = InventorySource_InventorySourceFlags.getByValue(rawSourceFlagId);
                if (flags == null) {
                    throw new IllegalStateException("Invalid inventory source flag id: " + rawSourceFlagId);
                }
            } else {
                flags = InventorySource_InventorySourceFlags.NoFlag;
            }
            return new InventorySource(type, containerId, flags);
        }

        switch (type) {
            case ContainerInventory, NonImplementedFeatureTODO -> {
                return new InventorySource(type, BedrockTypes.VAR_INT.read(buffer), InventorySource_InventorySourceFlags.NoFlag);
            }
            case WorldInteraction -> {
                int rawSourceFlagId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
                InventorySource_InventorySourceFlags flag = InventorySource_InventorySourceFlags.getByValue(rawSourceFlagId);
                if (flag == null) {
                    throw new IllegalStateException("Invalid inventory source flag id: " + rawSourceFlagId);
                }

                return new InventorySource(type, ContainerID.CONTAINER_ID_NONE.getValue(), flag);
            }
            default -> {
                return new InventorySource(type, ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.NoFlag);
            }
        }
    }

    @Override
    public void write(ByteBuf buffer, InventorySource value) {
        write(buffer, value, InventoryTransactionLayout.usesSourcePresencePairs(BedrockWire.current()));
    }

    public static void write(ByteBuf buffer, final InventorySource value, final boolean presencePairs) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.type().getValue());

        if (presencePairs) {
            buffer.writeBoolean(true);
            switch (value.type()) {
                case ContainerInventory, NonImplementedFeatureTODO -> {
                    buffer.writeBoolean(true);
                    buffer.writeByte(value.containerId());
                }
                default -> buffer.writeBoolean(false);
            }
            buffer.writeBoolean(true);
            if (value.type() == InventorySourceType.WorldInteraction) {
                buffer.writeBoolean(true);
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.flags().getValue());
            } else {
                buffer.writeBoolean(false);
            }
            return;
        }

        switch (value.type()) {
            case ContainerInventory, NonImplementedFeatureTODO -> BedrockTypes.VAR_INT.write(buffer, value.containerId());
            case WorldInteraction -> BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.flags().getValue());
        }
    }
}