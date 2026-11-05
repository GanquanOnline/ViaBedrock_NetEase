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
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * 基岩把潜影盒的朝向存在方块实体 NBT 的 {@code facing} 中，而不是方块状态里。
 * Java 把它作为 {@code facing} 方块状态保存，因此必须在此重写已映射的 Java 状态。
 */
public class ShulkerBoxBlockEntityRewriter extends LootableContainerBlockEntityRewriter {

    @Override
    public BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity) {
        final BlockEntity javaBlockEntity = super.toJava(user, bedrockBlockEntity);
        final int javaBlockState = applyFacing(
                user.get(ChunkTracker.class).getJavaBlockState(bedrockBlockEntity.position()),
                bedrockBlockEntity.tag());
        return new BlockEntityWithBlockState(javaBlockEntity, javaBlockState);
    }

    static int applyFacing(final int javaBlockState, final CompoundTag bedrockTag) {
        final BlockState current = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockState);
        if (current == null || !current.properties().containsKey("facing")) {
            return javaBlockState;
        }
        final BlockState updated = current.withProperty("facing", javaFacingName(bedrockTag));
        return BedrockProtocol.MAPPINGS.getJavaBlockStates().getOrDefault(updated, javaBlockState);
    }

    static String javaFacingName(final CompoundTag bedrockTag) {
        final int facing = bedrockTag.get("facing") instanceof NumberTag facingTag ? facingTag.asInt() : 0;
        return switch (facing) {
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "down";
        };
    }

}
