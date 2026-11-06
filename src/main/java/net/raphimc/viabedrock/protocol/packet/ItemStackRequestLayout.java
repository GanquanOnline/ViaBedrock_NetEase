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

import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.types.inventory.RecipeIngredientType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextProcessingEventOrigin;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.model.ContainerSlotTypeLayout;

import java.util.List;

/**
 * 基岩 ITEM_STACK_REQUEST（数据包 0x93）的线上布局辅助工具。
 * <p>
 * Nukkit 只在协议 2168 才把动作类型切换为无符号 varint（外加一个尾部字节），
 * 并把 stack-network id 切换为小端 int。官方 975 和
 * 网易 / Nukkit-MOT 860 都仍使用 2168 之前的布局：动作类型为
 * 原始字节，stack-network id 为有符号 varint。
 * <p>
 * 把 2168 的形状写入 860/975 的 SAI 服务器会让 Nukkit 以未知动作类型
 * 拒绝该请求，Java 的点击就永远离不开光标。官方 975
 * 必须保留字节动作类型布局；只有协议 2168+ 才用新形状。
 */
public final class ItemStackRequestLayout {

    public static final int UNSIGNED_ACTION_TYPE_PROTOCOL = 2168;
    /** 2192（v1_26_60）在 CRAFT_LOOM 之后插入 CRAFT_RESERVED，action id 表需要另一张。 */
    public static final int CRAFT_RESERVED_PROTOCOL = 2192;
    public static final int OPTIONAL_DYNAMIC_ID_PROTOCOL = 729;
    public static final int FILTER_STRINGS_PROTOCOL = 422;
    public static final int TEXT_ORIGIN_PROTOCOL = 554;

    private ItemStackRequestLayout() {
    }

    public static boolean usesUnsignedActionType() {
        return usesUnsignedActionType(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesUnsignedActionType(final boolean emulateNetEase, final int protocol) {
        // Nukkit 只在协议 2168 才切换为无符号 varint 动作类型。
        // 官方 975 和网易 860 都仍写入原始字节。
        return protocol >= UNSIGNED_ACTION_TYPE_PROTOCOL;
    }

    public static boolean usesLittleEndianStackNetworkId() {
        return usesLittleEndianStackNetworkId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesLittleEndianStackNetworkId(final boolean emulateNetEase, final int protocol) {
        return usesUnsignedActionType(emulateNetEase, protocol);
    }

    public static boolean usesOptionalDynamicId() {
        return usesOptionalDynamicId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesOptionalDynamicId(final boolean emulateNetEase, final int protocol) {
        return protocol >= OPTIONAL_DYNAMIC_ID_PROTOCOL;
    }

    public static void writeActionType(final ByteBuf buffer, final ItemStackRequestActionType type) {
        writeActionType(buffer, type, emulateNetEase(), netEaseProtocol());
    }

    public static void writeActionType(final ByteBuf buffer, final ItemStackRequestActionType type,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesUnsignedActionType(emulateNetEase, protocol)) {
            final int id = typedActionId(type, protocol);
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, id);
            // 2168 在类型序数后重复一个类型字节。MOT 读取端会消费它（丢弃），
            // CloudburstMC 的 writeIngredient2/readIngredient2 系列也是同一个值。
            buffer.writeByte(id);
            return;
        }
        buffer.writeByte(type.getValue());
    }

    /**
     * 2168 的 action id 表去掉了 7/8（两个已废弃的物品容器动作），其后的动作整体前移两位。
     * 与 MOT {@code ItemStackRequestActionType.fromId} 的 2168 分支和 CloudburstMC 的
     * ITEM_STACK_REQUEST_TYPES 一致。
     */
    static int typedActionId(final ItemStackRequestActionType type, final int protocol) {
        if (protocol >= CRAFT_RESERVED_PROTOCOL) {
            // 2192 起 CRAFT_RESERVED 占 16，其余后移；本仓还没有 2192 档案，
            // 不能沿用 2168 的表静默写错字节。
            throw new UnsupportedOperationException("Action id table for protocol " + protocol + " is not implemented");
        }
        final int legacyId = type.getValue();
        if (legacyId == ItemStackRequestActionType.PlaceInItemContainer_DEPRECATED.getValue()
                || legacyId == ItemStackRequestActionType.TakeFromItemContainer_DEPRECATED.getValue()) {
            throw new IllegalStateException("v2168 has no item container stack request action: " + type);
        }
        return legacyId <= 6 ? legacyId : legacyId - 2;
    }

    public static void writeSlotInfo(final ByteBuf buffer, final ContainerEnumName container, final int slot, final int stackNetworkId) {
        writeSlotInfo(buffer, container, slot, stackNetworkId, null, emulateNetEase(), netEaseProtocol());
    }

    public static void writeSlotInfo(final ByteBuf buffer, final ContainerEnumName container, final int slot, final int stackNetworkId,
                                     final Integer dynamicId, final boolean emulateNetEase, final int protocol) {
        buffer.writeByte(ContainerSlotTypeLayout.toWire(container, ContainerSlotTypeLayout.usesNetEaseIdShift(emulateNetEase)));
        if (usesOptionalDynamicId(emulateNetEase, protocol)) {
            buffer.writeBoolean(dynamicId != null);
            if (dynamicId != null) {
                buffer.writeIntLE(dynamicId);
            }
        }
        buffer.writeByte(slot);
        writeStackNetworkId(buffer, stackNetworkId, emulateNetEase, protocol);
    }

    public static void writeStackNetworkId(final ByteBuf buffer, final int stackNetworkId) {
        writeStackNetworkId(buffer, stackNetworkId, emulateNetEase(), netEaseProtocol());
    }

    public static void writeStackNetworkId(final ByteBuf buffer, final int stackNetworkId,
                                           final boolean emulateNetEase, final int protocol) {
        if (usesLittleEndianStackNetworkId(emulateNetEase, protocol)) {
            buffer.writeIntLE(stackNetworkId);
            return;
        }
        BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
    }

    public static void writeRequestTrailer(final ByteBuf buffer) {
        writeRequestTrailer(buffer, emulateNetEase(), netEaseProtocol());
    }

    public static void writeRequestTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        writeRequestTrailer(buffer, emulateNetEase, protocol, new String[0], TextProcessingEventOrigin.BlockActorDataText);
    }

    public static void writeRequestTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol,
                                           final String[] filterStrings, final TextProcessingEventOrigin origin) {
        if (protocol >= FILTER_STRINGS_PROTOCOL) {
            final String[] strings = filterStrings != null ? filterStrings : new String[0];
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, strings.length);
            for (final String filterString : strings) {
                BedrockTypes.STRING.write(buffer, filterString != null ? filterString : "");
            }
        }
        if (protocol >= TEXT_ORIGIN_PROTOCOL) {
            final TextProcessingEventOrigin resolved = origin != null ? origin : TextProcessingEventOrigin.BlockActorDataText;
            buffer.writeIntLE(resolved.getValue());
        }
    }

    public static void writeCraftRecipeOptional(final ByteBuf buffer, final int recipeNetworkId, final int filteredStringIndex,
                                                final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftRecipeOptional, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, recipeNetworkId);
        buffer.writeIntLE(filteredStringIndex);
    }

    public static void writeTransfer(final ByteBuf buffer, final ItemStackRequestActionType type, final int count,
                                     final SlotInfo source, final SlotInfo destination,
                                     final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, type, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        writeSlotInfo(buffer, destination.container(), destination.slot(), destination.stackNetworkId(), destination.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeSwap(final ByteBuf buffer, final SlotInfo source, final SlotInfo destination,
                                 final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Swap, emulateNetEase, protocol);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        writeSlotInfo(buffer, destination.container(), destination.slot(), destination.stackNetworkId(), destination.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeDrop(final ByteBuf buffer, final int count, final SlotInfo source, final boolean randomly,
                                 final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Drop, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
        buffer.writeBoolean(randomly);
    }

    public static void writeDestroy(final ByteBuf buffer, final int count, final SlotInfo source,
                                    final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Destroy, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeConsume(final ByteBuf buffer, final int count, final SlotInfo source,
                                    final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.Consume, emulateNetEase, protocol);
        buffer.writeByte(count);
        writeSlotInfo(buffer, source.container(), source.slot(), source.stackNetworkId(), source.dynamicId(), emulateNetEase, protocol);
    }

    public static void writeCraftRecipe(final ByteBuf buffer, final int recipeNetworkId, final int timesCrafted,
                                        final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftRecipe, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, recipeNetworkId);
        if (protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
    }

    public static void writeCraftRecipeAuto(final ByteBuf buffer, final int recipeNetworkId, final int numberOfRequestedCrafts,
                                            final int timesCrafted, final List<BedrockItem> ingredients,
                                            final boolean emulateNetEase, final int protocol) {
        writeCraftRecipeAuto(buffer, recipeNetworkId, numberOfRequestedCrafts, timesCrafted, ingredients, null, emulateNetEase, protocol);
    }

    /**
     * @param ingredientIdentifiers 与 {@code ingredients} 等长的命名空间 id；2168 的 DEFAULT 配料必须写字符串
     */
    public static void writeCraftRecipeAuto(final ByteBuf buffer, final int recipeNetworkId, final int numberOfRequestedCrafts,
                                            final int timesCrafted, final List<BedrockItem> ingredients,
                                            final List<String> ingredientIdentifiers,
                                            final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftRecipeAuto, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, recipeNetworkId);
        if (protocol >= 712) {
            buffer.writeByte(numberOfRequestedCrafts);
        }
        if (protocol >= 448) {
            // 2168 起不再有独立的 timesCrafted 字节，读取端用 numberOfRequestedCrafts 回填。
            if (protocol < UNSIGNED_ACTION_TYPE_PROTOCOL) {
                buffer.writeByte(timesCrafted);
            }
        }
        if (protocol >= 557) {
            final List<BedrockItem> descriptors = ingredients == null ? List.of() : ingredients;
            if (protocol >= UNSIGNED_ACTION_TYPE_PROTOCOL) {
                // 2168 的计数也改成 VarUInt，每个配料是类型化描述符。
                // 不能发空数组：MOT 的 CraftRecipeAutoProcessor 会按配料逐条核对 CONSUME，
                // 空配料列表会让带 CONSUME 的自动合成整请求报错。
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, descriptors.size());
                for (int i = 0; i < descriptors.size(); i++) {
                    final String identifier = ingredientIdentifiers != null && i < ingredientIdentifiers.size()
                            ? ingredientIdentifiers.get(i) : null;
                    if (identifier == null) {
                        throw new IllegalStateException("v2168 auto-craft ingredient #" + i + " has no namespaced identifier");
                    }
                    final int data = descriptors.get(i).data() & 0xFFFF;
                    final int aux = data == 0x7FFF || data == 0xFFFF ? 0 : data;
                    RecipeIngredientType.writeTypedDefault(buffer, identifier, aux, descriptors.get(i).amount());
                }
                return;
            }
            buffer.writeByte(descriptors.size());
            for (final BedrockItem ingredient : descriptors) {
                writeDefaultIngredientDescriptor(buffer, ingredient, emulateNetEase, protocol);
            }
        }
    }

    static void writeDefaultIngredientDescriptor(final ByteBuf buffer, final BedrockItem ingredient,
                                                 final boolean emulateNetEase, final int protocol) {
        if (protocol >= UNSIGNED_ACTION_TYPE_PROTOCOL) {
            throw new UnsupportedOperationException("v2168 auto-craft descriptors are not emitted");
        }
        if (ingredient == null || ingredient.isEmpty()) {
            buffer.writeByte(0); // INVALID
            BedrockTypes.VAR_INT.write(buffer, 0);
            return;
        }
        buffer.writeByte(1); // DEFAULT
        buffer.writeShortLE(ingredient.identifier());
        buffer.writeShortLE(ingredient.data());
        BedrockTypes.VAR_INT.write(buffer, ingredient.amount());
    }

    public static void writeCraftCreative(final ByteBuf buffer, final int creativeItemNetworkId, final int timesCrafted,
                                          final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftCreative, emulateNetEase, protocol);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, creativeItemNetworkId);
        if (protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
    }

    public static void writeCraftRepairAndDisenchant(final ByteBuf buffer, final int recipeNetworkId, final int timesCrafted,
                                                     final int repairCost, final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftRepairAndDisenchant, emulateNetEase, protocol);
        if (protocol >= UNSIGNED_ACTION_TYPE_PROTOCOL) {
            buffer.writeIntLE(recipeNetworkId);
        } else {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, recipeNetworkId);
        }
        if (protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
        BedrockTypes.VAR_INT.write(buffer, repairCost);
    }

    public static void writeBeaconPayment(final ByteBuf buffer, final int primaryEffect, final int secondaryEffect,
                                          final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.ScreenBeaconPayment, emulateNetEase, protocol);
        BedrockTypes.VAR_INT.write(buffer, primaryEffect);
        BedrockTypes.VAR_INT.write(buffer, secondaryEffect);
    }

    public static void writeCraftLoom(final ByteBuf buffer, final String patternId, final int timesCrafted,
                                      final boolean emulateNetEase, final int protocol) {
        writeActionType(buffer, ItemStackRequestActionType.CraftLoom, emulateNetEase, protocol);
        BedrockTypes.STRING.write(buffer, patternId != null ? patternId : "");
        if (protocol >= 712) {
            buffer.writeByte(timesCrafted);
        }
    }

    public static DecodedRequestTrailer readRequestTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final String[] filterStrings;
        if (protocol >= FILTER_STRINGS_PROTOCOL) {
            filterStrings = BedrockTypes.STRING_ARRAY.read(buffer);
        } else {
            filterStrings = new String[0];
        }
        Integer origin = null;
        if (protocol >= TEXT_ORIGIN_PROTOCOL) {
            origin = buffer.readIntLE();
        }
        return new DecodedRequestTrailer(filterStrings, origin);
    }

    public static DecodedSlotInfo readSlotInfo(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int wireId = buffer.readUnsignedByte();
        final ContainerEnumName container = ContainerSlotTypeLayout.fromWire(wireId, ContainerSlotTypeLayout.usesNetEaseIdShift(emulateNetEase));
        Integer dynamicId = null;
        if (usesOptionalDynamicId(emulateNetEase, protocol) && buffer.readBoolean()) {
            dynamicId = buffer.readIntLE();
        }
        final int slot = buffer.readUnsignedByte();
        final int stackNetworkId = usesLittleEndianStackNetworkId(emulateNetEase, protocol)
                ? buffer.readIntLE()
                : BedrockTypes.VAR_INT.read(buffer);
        return new DecodedSlotInfo(container, slot, stackNetworkId, dynamicId);
    }

    public static FullContainerName containerName(final ContainerEnumName container, final Integer dynamicId) {
        return new FullContainerName(container, dynamicId);
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig() != null ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
    }

    public record SlotInfo(ContainerEnumName container, int slot, int stackNetworkId, Integer dynamicId) {
        public SlotInfo(final ContainerEnumName container, final int slot, final int stackNetworkId) {
            this(container, slot, stackNetworkId, null);
        }
    }

    public record DecodedSlotInfo(ContainerEnumName container, int slot, int stackNetworkId, Integer dynamicId) {
    }

    public record DecodedRequestTrailer(String[] filterStrings, Integer textOrigin) {
        public int filterStringCount() {
            return this.filterStrings != null ? this.filterStrings.length : 0;
        }
    }
}
