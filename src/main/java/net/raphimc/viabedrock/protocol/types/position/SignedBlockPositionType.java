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

package net.raphimc.viabedrock.protocol.types.position;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Nukkit 带符号 BlockVector3：X/Y/Z 用 zigzag。用于 AUTH_INPUT 方块动作、
 * SUB_CHUNK、NETWORK_CHUNK_PUBLISHER_UPDATE、BLOCK_PICK_REQUEST 和实体 POS。
 * 官方 944+ 的 BlockVector3 恰好与此编码一致；网易 860 并不一致。
 */
public class SignedBlockPositionType extends Type<BlockPosition> {

    public SignedBlockPositionType() {
        super("SignedBlockPosition", BlockPosition.class);
    }

    @Override
    public BlockPosition read(ByteBuf buffer) {
        final int x = BedrockTypes.VAR_INT.readPrimitive(buffer);
        final int y = BedrockTypes.VAR_INT.readPrimitive(buffer);
        final int z = BedrockTypes.VAR_INT.readPrimitive(buffer);
        return new BlockPosition(x, y, z);
    }

    @Override
    public void write(ByteBuf buffer, BlockPosition value) {
        BedrockTypes.VAR_INT.writePrimitive(buffer, value.x());
        BedrockTypes.VAR_INT.writePrimitive(buffer, value.y());
        BedrockTypes.VAR_INT.writePrimitive(buffer, value.z());
    }
}

