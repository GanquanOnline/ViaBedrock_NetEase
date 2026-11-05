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
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public class RecipeIngredientType extends Type<RecipeIngredient> {

    public static final RecipeIngredientType INSTANCE = new RecipeIngredientType();

    public RecipeIngredientType() {
        super(RecipeIngredient.class);
    }

    @Override
    public RecipeIngredient read(final ByteBuf buffer) {
        final byte type = buffer.readByte(); // ItemDescriptorType
        switch (type) {
            case 0: // INVALID
                BedrockTypes.VAR_INT.read(buffer); // 数量（始终为 0）
                return new RecipeIngredient(0, 0, 0);
            case 1: // DEFAULT（int_id_meta）
                // 服务端用 putLShort(networkId) 写入配料的网络 id；networkId 可能为
                // 负数（新方块，在 >>=1 扁平化升级位移之后）。物品栏中的物品通过 putVarInt
                // 携带同一个 networkId，读回时是有符号值。因此我们必须按有符号 short 读取
                // 才能与之匹配——用 & 0xFFFF 掩码会把例如 -571 变成 64965，导致所有负 id 物品
                // （原木、现代方块）失效，而小的正 id（木板、木棍）恰好仍能匹配。
                final int runtimeId = buffer.readShortLE();
                final int damage = buffer.readShortLE() & 0xFFFF;
                final int count = BedrockTypes.VAR_INT.read(buffer);
                return new RecipeIngredient(runtimeId, damage, count);
            case 3: // TAG
                BedrockTypes.STRING.read(buffer); // 标签表达式
                BedrockTypes.VAR_INT.read(buffer); // 数量
                return new RecipeIngredient(0, 0, 0);
            default:
                throw new IllegalStateException("Unknown ItemDescriptorType: " + type);
        }
    }

    @Override
    public void write(final ByteBuf buffer, final RecipeIngredient value) {
        throw new UnsupportedOperationException();
    }

}
