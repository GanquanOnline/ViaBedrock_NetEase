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

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeIngredientType extends Type<RecipeIngredient> {

    public static final RecipeIngredientType INSTANCE = new RecipeIngredientType();

    /** 2168 起配料不再携带 32767 通配符，无 meta 一律写 0，读回时按通配符处理。 */
    private static final int V2168_WILDCARD_DAMAGE = 0x7fff;

    /** 每个未命中的物品 id 只告警一次，避免整张合成表刷屏。 */
    private static final Set<String> WARNED_MISSES = ConcurrentHashMap.newKeySet();

    private final BiMap<String, Integer> items;
    private final Boolean typed;

    public RecipeIngredientType() {
        this(null);
    }

    /**
     * @param items 命名空间 id → 网络物品 id；提供时 2168 的 DEFAULT 配料才能参与配方匹配
     */
    public RecipeIngredientType(final BiMap<String, Integer> items) {
        this(items, null);
    }

    /**
     * @param typed {@code true} 强制 2168 布局，{@code false} 强制旧布局，{@code null} 跟随当前线上协议
     */
    public RecipeIngredientType(final BiMap<String, Integer> items, final Boolean typed) {
        super(RecipeIngredient.class);
        this.items = items;
        this.typed = typed;
    }

    @Override
    public RecipeIngredient read(final ByteBuf buffer) {
        if (this.typed != null ? this.typed : BedrockWire.applies(BedrockDeviation.RECIPE_INGREDIENT_TYPED)) {
            return readTyped(buffer, this.items);
        }
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

    /**
     * 2168 的配料描述符：VarUInt(min(序数,1)) + serializeName + 载荷 + VarInt(数量)。
     * 序数被压成 0/1，DEFAULT、ITEM_TAG 与 MOLANG 都写 1，真实类型只能靠名称串区分。
     * 与 MOT {@code BinaryStream#putRecipeIngredient} 和 CloudburstMC 的 writeIngredient 一致。
     */
    private static RecipeIngredient readTyped(final ByteBuf buffer, final BiMap<String, Integer> items) {
        final int type = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (type == 0) { // INVALID：只有 aux 和数量，没有名称串
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            return new RecipeIngredient(0, 0, 0);
        }
        final String typeName = BedrockTypes.STRING.read(buffer);
        switch (typeName) {
            case "item_tag": {
                BedrockTypes.STRING.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                BedrockTypes.VAR_INT.read(buffer);
                return new RecipeIngredient(0, 0, 0);
            }
            case "molang": {
                BedrockTypes.STRING.read(buffer);
                buffer.readShortLE();
                BedrockTypes.VAR_INT.read(buffer);
                return new RecipeIngredient(0, 0, 0);
            }
            default: { // DEFAULT
                final String identifier = BedrockTypes.STRING.read(buffer);
                final int aux = BedrockTypes.VAR_INT.read(buffer);
                final int count = BedrockTypes.VAR_INT.read(buffer);
                final Integer runtimeId = items != null ? items.get(identifier) : null;
                if (runtimeId == null) {
                    warnUnresolved(identifier);
                }
                return new RecipeIngredient(runtimeId != null ? runtimeId : 0,
                        aux == 0 ? V2168_WILDCARD_DAMAGE : aux, count);
            }
        }
    }

    /**
     * 未命中的物品 id 会让该格永远匹配不上（runtimeId 0 在匹配时表示“必须为空”），
     * 每个 id 只告警一次，避免整张合成表刷屏。
     */
    private static void warnUnresolved(final String identifier) {
        if (!WARNED_MISSES.add(identifier)) {
            return;
        }
        try {
            Via.getPlatform().getLogger().warning("未解析的配方配料物品 id: " + identifier + "（该配料按空位匹配）");
        } catch (IllegalArgumentException ignored) {
            // 单测里没有加载 ViaVersion 平台
        }
    }

    @Override
    public void write(final ByteBuf buffer, final RecipeIngredient value) {
        throw new UnsupportedOperationException();
    }

    /**
     * 2168 的 DEFAULT 配料：VarUInt(1) + "name" + 命名空间 id + VarInt(aux) + VarInt(数量)。
     * 与 MOT {@code BinaryStream#putRecipeIngredient} 的 2168 分支逐字节一致。
     */
    public static void writeTypedDefault(final ByteBuf buffer, final String identifier, final int aux, final int count) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
        BedrockTypes.STRING.write(buffer, "name");
        BedrockTypes.STRING.write(buffer, identifier);
        BedrockTypes.VAR_INT.write(buffer, aux);
        BedrockTypes.VAR_INT.write(buffer, count);
    }

    /** 2168 的 INVALID 配料：VarUInt(0) + VarInt(aux) + VarInt(数量)，没有名称串。 */
    public static void writeTypedInvalid(final ByteBuf buffer, final int aux, final int count) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
        BedrockTypes.VAR_INT.write(buffer, aux);
        BedrockTypes.VAR_INT.write(buffer, count);
    }

}
