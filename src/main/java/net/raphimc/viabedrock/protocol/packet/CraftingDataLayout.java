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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.experimental.types.inventory.RecipeIngredientType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock CRAFTING_DATA（数据包 0x34）的线上布局辅助方法。
 * <p>
 * 官方 975 与网易 860 仍然把无序／有序配方的解锁
 * 需求编码为单个 context 字节。context 为 {@code NONE}（0）时会再追加
 * 一个原料数组。协议 2168 把该字段换成
 * {@code varint context + boolean + optional ingredients}。
 * <p>
 * 只读 context 字节会把那些额外原料留在缓冲里，
 * 使之后每个配方都失步。官方 975 必须保持 685+ 字节的
 * 布局；不要在那里套用 2168 的 varint/bool 形状。
 */
public final class CraftingDataLayout {

    public static final int SHAPELESS_FURNACE_PROTOCOL = 974;
    public static final int FURNACE_RECIPE_TYPE = 2;
    public static final int FURNACE_AUX_RECIPE_TYPE = 3;
    public static final int UNLOCKING_REQUIREMENT_PROTOCOL = 685;
    public static final int UNLOCKING_VARINT_PROTOCOL = 2168;
    public static final int UNLOCKING_CONTEXT_NONE = 0;

    private CraftingDataLayout() {
    }

    public static boolean usesShapelessFurnaceLayout(final boolean emulateNetEase, final int protocol) {
        return protocol >= SHAPELESS_FURNACE_PROTOCOL;
    }

    public static boolean isLegacyFurnaceType(final int rawType) {
        return rawType == FURNACE_RECIPE_TYPE || rawType == FURNACE_AUX_RECIPE_TYPE;
    }

    public static boolean usesUnlockingRequirement(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= UNLOCKING_REQUIREMENT_PROTOCOL;
    }

    public static boolean usesVarIntUnlockingRequirement(final boolean emulateNetEase, final int protocol) {
        return protocol >= UNLOCKING_VARINT_PROTOCOL;
    }

    public static int skipUnlockingRequirement(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        if (!usesUnlockingRequirement(emulateNetEase, protocol)) {
            return -1;
        }
        if (usesVarIntUnlockingRequirement(emulateNetEase, protocol)) {
            final int context = wrapper.read(BedrockTypes.VAR_INT);
            if (wrapper.read(Types.BOOLEAN)) {
                skipUnlockingIngredients(wrapper, ingredientType(true));
            }
            return context;
        }
        final int context = wrapper.read(Types.UNSIGNED_BYTE);
        if (context == UNLOCKING_CONTEXT_NONE) {
            skipUnlockingIngredients(wrapper, RecipeIngredientType.INSTANCE);
        }
        return context;
    }

    public static int skipUnlockingRequirement(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesUnlockingRequirement(emulateNetEase, protocol)) {
            return -1;
        }
        if (usesVarIntUnlockingRequirement(emulateNetEase, protocol)) {
            final int context = BedrockTypes.VAR_INT.read(buffer);
            if (buffer.readBoolean()) {
                skipUnlockingIngredients(buffer, ingredientType(true));
            }
            return context;
        }
        final int context = buffer.readUnsignedByte();
        if (context == UNLOCKING_CONTEXT_NONE) {
            skipUnlockingIngredients(buffer, RecipeIngredientType.INSTANCE);
        }
        return context;
    }

    public static void writeUnlockingRequirement(final ByteBuf buffer, final boolean emulateNetEase, final int protocol,
                                                 final int context, final RecipeIngredient... extraIngredients) {
        if (!usesUnlockingRequirement(emulateNetEase, protocol)) {
            return;
        }
        if (usesVarIntUnlockingRequirement(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, context);
            final boolean present = context == UNLOCKING_CONTEXT_NONE;
            buffer.writeBoolean(present);
            if (present) {
                writeUnlockingIngredients(buffer, true, extraIngredients);
            }
            return;
        }
        buffer.writeByte(context);
        if (context == UNLOCKING_CONTEXT_NONE) {
            writeUnlockingIngredients(buffer, false, extraIngredients);
        }
    }

    private static Type<RecipeIngredient> ingredientType(final boolean typed) {
        return typed ? new RecipeIngredientType(null, Boolean.TRUE) : RecipeIngredientType.INSTANCE;
    }

    private static void skipUnlockingIngredients(final PacketWrapper wrapper, final Type<RecipeIngredient> ingredientType) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < count; i++) {
            wrapper.read(ingredientType);
        }
    }

    private static void skipUnlockingIngredients(final ByteBuf buffer, final Type<RecipeIngredient> ingredientType) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        for (int i = 0; i < count; i++) {
            ingredientType.read(buffer);
        }
    }

    private static void writeUnlockingIngredients(final ByteBuf buffer, final boolean typed, final RecipeIngredient[] extraIngredients) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, extraIngredients.length);
        for (RecipeIngredient ingredient : extraIngredients) {
            writeDefaultIngredient(buffer, typed, ingredient);
        }
    }

    /**
     * 2168 的 DEFAULT 配料需要命名空间 id，而 {@link RecipeIngredient} 只有网络 id，
     * 因此这里退化成 INVALID 描述符（aux + 数量），读回时的字节数与之一致。
     */
    private static void writeDefaultIngredient(final ByteBuf buffer, final boolean typed, final RecipeIngredient ingredient) {
        if (typed) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
            BedrockTypes.VAR_INT.write(buffer, ingredient.damage());
            BedrockTypes.VAR_INT.write(buffer, ingredient.count());
            return;
        }
        buffer.writeByte(1);
        buffer.writeShortLE(ingredient.runtimeId());
        buffer.writeShortLE(ingredient.damage());
        BedrockTypes.VAR_INT.write(buffer, ingredient.count());
    }
}
