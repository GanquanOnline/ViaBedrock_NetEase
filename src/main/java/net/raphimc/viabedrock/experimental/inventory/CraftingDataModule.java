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
package net.raphimc.viabedrock.experimental.inventory;

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.experimental.types.inventory.InstanceItemType;
import net.raphimc.viabedrock.experimental.types.inventory.RecipeIngredientType;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CraftingDataEntryType;
import net.raphimc.viabedrock.protocol.packet.CraftingDataLayout;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class CraftingDataModule implements FeatureModule {

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new RecipeRegistry(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CRAFTING_DATA, null, wrapper -> {
            wrapper.cancel();

            final RecipeRegistry registry = wrapper.user().get(RecipeRegistry.class);

            try {
                readCraftingData(wrapper, registry);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error parsing CRAFTING_DATA packet", e);
                registry.clear();
            }
        });
    }

    private static void readCraftingData(final PacketWrapper wrapper, final RecipeRegistry registry) {
        final List<BedrockRecipe> parsedRecipes = new ArrayList<>();
        final List<RecipeRegistry.SmithingRecipe> parsedSmithing = new ArrayList<>();

        final Boolean cleanRecipes = BedrockWire.applies(BedrockDeviation.CRAFTING_DATA_BUCKETS)
                ? readCraftingDataBuckets(wrapper, parsedRecipes, parsedSmithing)
                : readCraftingDataLegacy(wrapper, parsedRecipes, parsedSmithing);
        if (cleanRecipes == null) {
            return;
        }

        if (cleanRecipes) {
            registry.clear();
        }
        for (final BedrockRecipe recipe : parsedRecipes) {
            registry.addRecipe(recipe);
        }
        for (final RecipeRegistry.SmithingRecipe recipe : parsedSmithing) {
            registry.addSmithingRecipe(recipe);
        }

        ViaBedrock.getPlatform().getLogger().fine("[CraftingData] Parsed " + parsedRecipes.size() + " crafting recipes (cleanRecipes=" + cleanRecipes + ", total=" + registry.recipeCount() + ")");
    }

    /**
     * 2168 之前的布局：所有配方共用一个 VarInt 类型前缀。
     *
     * @return cleanRecipes；遇到未知配方类型时返回 null，整包作废
     */
    private static Boolean readCraftingDataLegacy(final PacketWrapper wrapper, final List<BedrockRecipe> parsedRecipes,
                                                  final List<RecipeRegistry.SmithingRecipe> parsedSmithing) {
        final int recipeCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        for (int i = 0; i < recipeCount; i++) {
            final int rawType = wrapper.read(BedrockTypes.VAR_INT);
            final CraftingDataEntryType type = CraftingDataEntryType.getByValue(rawType);

            if (type == null) {
                // 生成的枚举中缺少 FurnaceRecipe(2) 与 FurnaceAuxRecipe(3)。
                // 网易 860 仍会发出旧的熔炉载荷。官方 974+ 会把它们重映射为无形状配方，因此只有 860 路径会跳过旧布局。
                if (CraftingDataLayout.isLegacyFurnaceType(rawType)) {
                    if (CraftingDataLayout.usesShapelessFurnaceLayout(
                            ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                            ViaBedrock.getConfig().getNetEaseProtocolVersion())) {
                        readShapelessRecipe(wrapper, parsedRecipes);
                    } else if (rawType == CraftingDataLayout.FURNACE_RECIPE_TYPE) {
                        skipFurnaceRecipe(wrapper);
                    } else {
                        skipFurnaceAuxRecipe(wrapper);
                    }
                    continue;
                }
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown CraftingDataEntryType: " + rawType + ", stopping parse at recipe " + i + "/" + recipeCount);
                return null;
            }

            switch (type) {
                case ShapelessRecipe, UserDataShapelessRecipe, ShapelessChemistryRecipe -> readShapelessRecipe(wrapper, parsedRecipes);
                case ShapedRecipe, ShapedChemistryRecipe -> readShapedRecipe(wrapper, parsedRecipes);
                case MultiRecipe -> skipMultiRecipe(wrapper);
                case SmithingTransformRecipe -> readSmithingTransformRecipe(wrapper, parsedSmithing, RecipeIngredientType.INSTANCE);
                case SmithingTrimRecipe -> skipSmithingTrimRecipe(wrapper, RecipeIngredientType.INSTANCE);
            }
        }

        // 跳过酿造数据
        final int brewingCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < brewingCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // 输入 id
            wrapper.read(BedrockTypes.VAR_INT); // 输入 meta
            wrapper.read(BedrockTypes.VAR_INT); // 试剂 id
            wrapper.read(BedrockTypes.VAR_INT); // 试剂 meta
            wrapper.read(BedrockTypes.VAR_INT); // 输出 id
            wrapper.read(BedrockTypes.VAR_INT); // 输出 meta
        }

        // 跳过容器混合数据
        final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < containerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // 输入 id
            wrapper.read(BedrockTypes.VAR_INT); // 试剂 id
            wrapper.read(BedrockTypes.VAR_INT); // 输出 id
        }

        // 跳过材料分解数据
        final int materialReducerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < materialReducerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // 输入 id
            final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int j = 0; j < outputCount; j++) {
                wrapper.read(BedrockTypes.VAR_INT); // 输出 id
                wrapper.read(BedrockTypes.VAR_INT); // 输出数量
            }
        }

        return wrapper.read(Types.BOOLEAN);
    }

    /**
     * 2168 起的 CRAFTING_DATA：配方按类型分桶，每桶先写 VarUInt 计数，条目不再带类型前缀。
     * 桶顺序取自 MOT {@code CraftingDataPacket} 的 2168 编码与 CloudburstMC 的 CraftingDataSerializer_v2168。
     *
     * @return cleanRecipes
     */
    private static boolean readCraftingDataBuckets(final PacketWrapper wrapper, final List<BedrockRecipe> parsedRecipes,
                                                   final List<RecipeRegistry.SmithingRecipe> parsedSmithing) {
        final RecipeIngredientType ingredientType = ingredientType(wrapper);

        final int shapedCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < shapedCount; i++) {
            readShapedRecipeBuckets(wrapper, parsedRecipes, ingredientType);
        }
        final int shapelessCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < shapelessCount; i++) {
            readShapelessRecipeBuckets(wrapper, parsedRecipes, ingredientType);
        }
        final int multiCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < multiCount; i++) {
            skipMultiRecipe(wrapper);
        }
        final int shapelessUserCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < shapelessUserCount; i++) {
            readShapelessRecipeBuckets(wrapper, parsedRecipes, ingredientType);
        }
        // 化学配方桶与普通桶同构，但 BedrockRecipe 不区分配方类型，读完即丢弃
        final List<BedrockRecipe> discarded = new ArrayList<>();
        final int shapelessChemistryCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < shapelessChemistryCount; i++) {
            readShapelessRecipeBuckets(wrapper, discarded, ingredientType);
        }
        final int shapedChemistryCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < shapedChemistryCount; i++) {
            readShapedRecipeBuckets(wrapper, discarded, ingredientType);
        }
        final int smithingTransformCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < smithingTransformCount; i++) {
            readSmithingTransformRecipe(wrapper, parsedSmithing, ingredientType);
        }
        final int smithingTrimCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < smithingTrimCount; i++) {
            skipSmithingTrimRecipe(wrapper, ingredientType);
        }
        final int potionMixCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < potionMixCount; i++) {
            for (int field = 0; field < 6; field++) {
                wrapper.read(BedrockTypes.VAR_INT);
            }
        }
        final int containerMixCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < containerMixCount; i++) {
            for (int field = 0; field < 3; field++) {
                wrapper.read(BedrockTypes.VAR_INT);
            }
        }
        final int materialReducerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < materialReducerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // 输入 id
            final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int j = 0; j < outputCount; j++) {
                wrapper.read(BedrockTypes.VAR_INT); // 输出 id
                wrapper.read(BedrockTypes.VAR_INT); // 输出数量
            }
        }
        return wrapper.read(Types.BOOLEAN);
    }

    /**
     * 2168 的配料携带的是命名空间 id，需要物品映射才能还原成网络 id 参与配方匹配。
     */
    private static RecipeIngredientType ingredientType(final PacketWrapper wrapper) {
        final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
        return itemRewriter != null
                ? new RecipeIngredientType(itemRewriter.getItems(), Boolean.TRUE)
                : new RecipeIngredientType(null, Boolean.TRUE);
    }

    private static void readShapedRecipeBuckets(final PacketWrapper wrapper, final List<BedrockRecipe> recipes,
                                                final RecipeIngredientType ingredientType) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);
        final int width = wrapper.read(BedrockTypes.VAR_INT);
        final int height = wrapper.read(BedrockTypes.VAR_INT);

        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<RecipeIngredient> ingredients = new ArrayList<>(ingredientCount);
        for (int j = 0; j < ingredientCount; j++) {
            ingredients.add(wrapper.read(ingredientType));
        }

        final List<BedrockItem> outputs = readBucketOutputs(wrapper);
        wrapper.read(BedrockTypes.UUID); // uuid（唯一标识符）
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        final boolean assumeSymmetry = wrapper.read(Types.BOOLEAN);
        skipUnlockingRequirement(wrapper);
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPED, width, height,
                ingredients, primaryOutput(outputs), extraOutputs(outputs), tag, priority, networkId, assumeSymmetry
        ));
    }

    /**
     * 2168 的分桶把普通无序配方与切石机配方放进同一个 shapeless 桶，tag（"stonecutter"）
     * 是唯一的来源标记，RecipeRegistry 靠它区分两者。
     */
    private static void readShapelessRecipeBuckets(final PacketWrapper wrapper, final List<BedrockRecipe> recipes,
                                                   final RecipeIngredientType ingredientType) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);

        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<RecipeIngredient> ingredients = new ArrayList<>(ingredientCount);
        for (int j = 0; j < ingredientCount; j++) {
            ingredients.add(wrapper.read(ingredientType));
        }

        final List<BedrockItem> outputs = readBucketOutputs(wrapper);
        wrapper.read(BedrockTypes.UUID); // uuid（唯一标识符）
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        skipUnlockingRequirement(wrapper);
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPELESS, 0, 0,
                ingredients, primaryOutput(outputs), extraOutputs(outputs), tag, priority, networkId, true
        ));
    }

    private static List<BedrockItem> readBucketOutputs(final PacketWrapper wrapper) {
        final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<BedrockItem> outputs = new ArrayList<>(outputCount);
        for (int j = 0; j < outputCount; j++) {
            outputs.add(wrapper.read(InstanceItemType.INSTANCE));
        }
        return outputs;
    }

    private static BedrockItem primaryOutput(final List<BedrockItem> outputs) {
        return outputs.isEmpty() ? BedrockItem.empty() : outputs.get(0);
    }

    private static List<BedrockItem> extraOutputs(final List<BedrockItem> outputs) {
        return outputs.size() > 1 ? new ArrayList<>(outputs.subList(1, outputs.size())) : new ArrayList<>();
    }

    private static void readShapelessRecipe(final PacketWrapper wrapper, final List<BedrockRecipe> recipes) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);

        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<RecipeIngredient> ingredients = new ArrayList<>(ingredientCount);
        for (int j = 0; j < ingredientCount; j++) {
            ingredients.add(wrapper.read(RecipeIngredientType.INSTANCE));
        }

        final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final BedrockItem primaryOutput;
        final List<BedrockItem> extraOutputs = new ArrayList<>();
        if (outputCount > 0) {
            primaryOutput = wrapper.read(InstanceItemType.INSTANCE);
            for (int j = 1; j < outputCount; j++) {
                extraOutputs.add(wrapper.read(InstanceItemType.INSTANCE));
            }
        } else {
            primaryOutput = BedrockItem.empty();
        }

        wrapper.read(BedrockTypes.UUID); // uuid（唯一标识符）
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        skipUnlockingRequirement(wrapper);
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPELESS, 0, 0,
                ingredients, primaryOutput, extraOutputs, tag, priority, networkId, true
        ));
    }

    private static void readShapedRecipe(final PacketWrapper wrapper, final List<BedrockRecipe> recipes) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);
        final int width = wrapper.read(BedrockTypes.VAR_INT);
        final int height = wrapper.read(BedrockTypes.VAR_INT);

        final List<RecipeIngredient> ingredients = new ArrayList<>(width * height);
        for (int j = 0; j < width * height; j++) {
            ingredients.add(wrapper.read(RecipeIngredientType.INSTANCE));
        }

        final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final BedrockItem primaryOutput;
        final List<BedrockItem> extraOutputs = new ArrayList<>();
        if (outputCount > 0) {
            primaryOutput = wrapper.read(InstanceItemType.INSTANCE);
            for (int j = 1; j < outputCount; j++) {
                extraOutputs.add(wrapper.read(InstanceItemType.INSTANCE));
            }
        } else {
            primaryOutput = BedrockItem.empty();
        }

        wrapper.read(BedrockTypes.UUID); // uuid（唯一标识符）
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        final boolean assumeSymmetry = wrapper.read(Types.BOOLEAN);
        skipUnlockingRequirement(wrapper);
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPED, width, height,
                ingredients, primaryOutput, extraOutputs, tag, priority, networkId, assumeSymmetry
        ));
    }


    private static void skipUnlockingRequirement(final PacketWrapper wrapper) {
        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int netEaseProtocol = ViaBedrock.getConfig().getNetEaseProtocolVersion();
        final int protocol = emulateNetEase && netEaseProtocol > 0 ? netEaseProtocol : BedrockWire.current();
        CraftingDataLayout.skipUnlockingRequirement(wrapper, emulateNetEase, protocol);
    }

    private static void skipFurnaceRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.VAR_INT); // 输入 id
        wrapper.read(InstanceItemType.INSTANCE); // 输出
        wrapper.read(BedrockTypes.STRING); // 标签
    }

    private static void skipFurnaceAuxRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.VAR_INT); // 输入 id
        wrapper.read(BedrockTypes.VAR_INT); // 输入数据
        wrapper.read(InstanceItemType.INSTANCE); // 输出
        wrapper.read(BedrockTypes.STRING); // 标签
    }

    private static void skipMultiRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.UUID); // uuid（唯一标识符）
        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 网络 ID
    }

    private static void readSmithingTransformRecipe(final PacketWrapper wrapper, final List<RecipeRegistry.SmithingRecipe> recipes,
                                                    final Type<RecipeIngredient> ingredientType) {
        wrapper.read(BedrockTypes.STRING); // 配方 ID
        final RecipeIngredient template = wrapper.read(ingredientType);
        final RecipeIngredient base = wrapper.read(ingredientType);
        final RecipeIngredient addition = wrapper.read(ingredientType);
        wrapper.read(InstanceItemType.INSTANCE); // 输出
        wrapper.read(BedrockTypes.STRING); // 标签
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        recipes.add(new RecipeRegistry.SmithingRecipe(networkId, false, base, addition, template));
    }

    private static void skipSmithingTrimRecipe(final PacketWrapper wrapper, final Type<RecipeIngredient> ingredientType) {
        wrapper.read(BedrockTypes.STRING); // 配方 ID
        wrapper.read(ingredientType); // 模板
        wrapper.read(ingredientType); // 基底
        wrapper.read(ingredientType); // 附加材料
        wrapper.read(BedrockTypes.STRING); // 标签
        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 网络 ID
    }

}
