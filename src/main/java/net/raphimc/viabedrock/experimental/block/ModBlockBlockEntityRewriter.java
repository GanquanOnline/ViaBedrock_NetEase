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
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntityImpl;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;

/**
 * Nukkit ModBlock 方块实体的重写器。
 * 生成最小的 Java NBT，以触发 FabricRock 的 BlockEntityDataDriven 渲染。
 */
public class ModBlockBlockEntityRewriter implements BlockEntityRewriter.Rewriter {

    @Override
    public BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity) {
        final CompoundTag javaTag = new CompoundTag();

        // Extension point: pass through exData when FabricRock implements readNbt
        // final CompoundTag bedrockTag = bedrockBlockEntity.tag();
        // if (bedrockTag.get("exData") instanceof CompoundTag exData) {
        //     javaTag.put("exData", exData.copy());
        // }

        return new BlockEntityImpl(bedrockBlockEntity.packedXZ(), bedrockBlockEntity.y(), -1, javaTag);
    }

}
