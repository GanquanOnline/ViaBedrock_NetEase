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

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * Chests (including trapped and copper) store double-chest pairing only in Bedrock NBT. Java needs that pairing as
 * the {@code type} block-state property, so this rewriter bakes {@code left}/{@code right}/{@code single} into the
 * Java block state the same way beds bake dye colour.
 */
public class ChestBlockEntityRewriter extends LootableContainerBlockEntityRewriter {

    @Override
    public BlockEntity toJava(final UserConnection user, final BedrockBlockEntity bedrockBlockEntity) {
        final BlockEntity javaBlockEntity = super.toJava(user, bedrockBlockEntity);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        if (chunkTracker == null) {
            return javaBlockEntity;
        }

        final int javaBlockState = chunkTracker.getJavaBlockState(bedrockBlockEntity.position());
        final BiMap<BlockState, Integer> javaBlockStates = BedrockProtocol.MAPPINGS.getJavaBlockStates();
        final BlockState current = javaBlockStates.inverse().get(javaBlockState);
        if (current == null || !current.properties().containsKey("type")) {
            return javaBlockEntity;
        }

        final String type = ChestPairing.javaType(
                current.properties().get("facing"),
                bedrockBlockEntity.position().x(),
                bedrockBlockEntity.position().z(),
                bedrockBlockEntity.tag()
        );
        final BlockState fixed = current.replaceProperty("type", type);
        final Integer remapped = javaBlockStates.get(fixed);
        if (remapped == null) {
            return javaBlockEntity;
        }
        return new BlockEntityWithBlockState(javaBlockEntity, remapped);
    }

}
