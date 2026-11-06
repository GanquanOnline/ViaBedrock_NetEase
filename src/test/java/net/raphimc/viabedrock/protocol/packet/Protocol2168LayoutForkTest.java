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

import com.google.common.collect.HashBiMap;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.experimental.types.inventory.RecipeIngredientType;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.IdentityDefinition_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemStackRequestActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ScorePacketType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.model.SkinType;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol2168LayoutForkTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    private static final List<BedrockDeviation> PROTOCOL_2168_DEVIATIONS = List.of(
            BedrockDeviation.SKIN_ANIMATION_VARINTS,
            BedrockDeviation.SKIN_ARM_SIZE_BYTE,
            BedrockDeviation.SKIN_PERSONA_V2168,
            BedrockDeviation.SKIN_TRUSTED_STRING,
            BedrockDeviation.ITEM_DESCRIPTOR_V2168,
            BedrockDeviation.RECIPE_INGREDIENT_TYPED,
            BedrockDeviation.ITEM_STACK_REQUEST_TYPED_ACTIONS,
            BedrockDeviation.ITEM_STACK_RESPONSE_OPTIONAL_FILTERED_NAME,
            BedrockDeviation.MOVE_PLAYER_TELEPORT_FLAG,
            BedrockDeviation.MOVE_ENTITY_DELTA_AXIS_BOOLS,
            BedrockDeviation.PLAYER_LIST_NO_TRUSTED,
            BedrockDeviation.PLAYER_SKIN_NO_TRUSTED,
            BedrockDeviation.RESOURCE_PACK_VARINT_COUNTS,
            BedrockDeviation.RESOURCE_PACK_STATUS_VARINT,
            BedrockDeviation.START_GAME_BYTE_FIELDS,
            BedrockDeviation.SUB_CHUNK_PACKET_V2168,
            BedrockDeviation.LEVEL_CHUNK_PACKET_V2168,
            BedrockDeviation.DIMENSION_DATA_PACK_ID,
            BedrockDeviation.PLAY_SOUND_V2168,
            BedrockDeviation.SET_SCORE_V2168,
            BedrockDeviation.MAP_ITEM_DATA_V2168,
            BedrockDeviation.PLAYER_AUTH_INPUT_INPUT_LIST,
            BedrockDeviation.PLAYER_AUTH_INPUT_OPTIONAL_SECTIONS,
            BedrockDeviation.CRAFTING_DATA_BUCKETS,
            BedrockDeviation.ITEM_STACK_RESPONSE_CONTAINER_PRESENCE_PAIR
    );

    @Test
    void profileIsRegisteredAsTheNewestLayout() {
        assertEquals(2168, BedrockProtocolProfile.V1_26_40.protocolVersion());
        assertEquals("1.26.40", BedrockProtocolProfile.V1_26_40.gameVersion());
        assertEquals(2168, BedrockProtocolProfile.newest().protocolVersion());
        assertEquals(2168, BedrockProtocolProfile.resolveProtocol(BedrockProtocolProfile.AUTO));
        assertTrue(BedrockProtocolProfile.isSupported(2168));
        assertTrue(BedrockProtocolProfile.isSelectable(2168));
        assertTrue(BedrockProtocolProfile.supportedList().contains("2168"));
        assertFalse(BedrockWire.inferred(2168));
    }

    @Test
    void deviationSetIsPinned() {
        final List<BedrockDeviation> active = BedrockWire.deviations(2168);
        assertTrue(active.containsAll(PROTOCOL_2168_DEVIATIONS));
        assertEquals(PROTOCOL_2168_DEVIATIONS.size(),
                active.stream().filter(deviation -> deviation.introducedAt() == 2168).count());
        assertFalse(active.contains(BedrockDeviation.START_GAME_LOGGING_CHAT),
                "START_GAME 的 loggingChat 在 2168 消失");
        assertTrue(BedrockWire.deviations(1001).contains(BedrockDeviation.START_GAME_LOGGING_CHAT));
        assertEquals(BedrockWire.deviations(2167).size() + PROTOCOL_2168_DEVIATIONS.size() - 1,
                BedrockWire.deviations(2168).size(),
                "2168 新增 20 条，同时 START_GAME 的 loggingChat 在 2168 消失");
    }

    @Test
    void everyForkStartsAt2168AndStaysActiveUntilRemoved() {
        for (final BedrockDeviation deviation : PROTOCOL_2168_DEVIATIONS) {
            assertEquals(2168, deviation.introducedAt(), deviation.name());
            assertTrue(BedrockWire.applies(2168, deviation), deviation.name());
            assertTrue(BedrockWire.applies(2169, deviation), deviation.name());
            assertFalse(BedrockWire.applies(2167, deviation), deviation.name());
            assertFalse(BedrockWire.applies(1001, deviation), deviation.name());
        }
    }

    @Test
    void itemStackResponseFilteredNameIsOptionalAt2168() {
        assertEquals(2168, ItemStackResponseLayout.OPTIONAL_FILTERED_NAME_PROTOCOL);
        assertEquals(BedrockDeviation.ITEM_STACK_RESPONSE_OPTIONAL_FILTERED_NAME.introducedAt(),
                ItemStackResponseLayout.OPTIONAL_FILTERED_NAME_PROTOCOL);
        assertTrue(ItemStackResponseLayout.usesOptionalFilteredCustomName(2168));
        assertFalse(ItemStackResponseLayout.usesOptionalFilteredCustomName(2167));

        for (final String filtered : new String[]{null, "", "hidden"}) {
            final ByteBuf buffer = Unpooled.buffer();
            ItemStackResponseLayout.writeOkEntry(buffer, false, 2168, 7, 0, 3, 1, 42,
                    "visible", filtered, 0);
            final ItemStackResponseLayout.DecodedEntry entry =
                    ItemStackResponseLayout.decodeEntry(buffer, false, 2168);
            assertEquals(1, entry.containers().size());
            final List<ItemStackResponseLayout.DecodedSlot> slots = entry.containers().get(0).slots();
            assertEquals(1, slots.size());
            assertEquals("visible", slots.get(0).customName());
            assertEquals(filtered, slots.get(0).filteredCustomName());
            assertEquals(42, slots.get(0).stackNetworkId());
            assertEquals(0, buffer.readableBytes());
            buffer.release();
        }
    }

    @Test
    void itemStackResponseKeepsTheFixedFilteredNameBelow2168() {
        final ByteBuf buffer = Unpooled.buffer();
        ItemStackResponseLayout.writeOkEntry(buffer, false, 2167, 7, 0, 3, 1, 42,
                "visible", null, 0);
        final ItemStackResponseLayout.DecodedEntry entry =
                ItemStackResponseLayout.decodeEntry(buffer, false, 2167);
        assertEquals(1, entry.containers().size());
        final List<ItemStackResponseLayout.DecodedSlot> slots = entry.containers().get(0).slots();
        assertEquals(1, slots.size());
        assertEquals("", slots.get(0).filteredCustomName());
        assertEquals(0, buffer.readableBytes());
        buffer.release();
    }

    @Test
    void skinBlockRoundTripsAt2168() {
        final ByteBuf buffer = Unpooled.buffer();
        SkinType.write(buffer, skinFixture(), 2168);
        final SkinData decoded = SkinType.read(buffer, 2168);
        assertEquals(0, buffer.readableBytes());

        assertEquals("wide", decoded.armSize());
        assertEquals("#FF00FF00", decoded.skinColor());
        assertEquals(1, decoded.animations().size());
        assertEquals(2, decoded.animations().get(0).type());
        assertEquals(1.5F, decoded.animations().get(0).frames());
        assertEquals(3, decoded.animations().get(0).expression());
        assertEquals("body", decoded.personaPieces().get(0).type());
        assertEquals("12345678-1234-1234-1234-123456789012", decoded.personaPieces().get(0).packId());
        assertEquals(List.of("#FF112233", "#FF445566", "#00000000", "#00000000"), decoded.tintColors().get(0).colors());
        assertTrue(decoded.trusted());
        assertEquals("hash", decoded.profileHash());
        assertEquals("full-skin", decoded.fullSkinId());
        assertEquals("cape", decoded.capeId());
        buffer.release();
    }

    @Test
    void skinBlockKeepsTheFixedLayoutBelow2168() {
        final ByteBuf baseline = Unpooled.buffer();
        SkinType.write(baseline, skinFixture(), 975);
        final ByteBuf older = Unpooled.buffer();
        SkinType.write(older, skinFixture(), 2167);
        assertTrue(ByteBufUtil.equals(baseline, older), "975 与 2167 共用旧布局，必须逐字节一致");
        older.release();

        final SkinData decoded = SkinType.read(baseline, 1001);
        assertEquals(0, baseline.readableBytes());
        assertEquals("wide", decoded.armSize());
        assertEquals("#00FF00", decoded.skinColor(), "旧布局的皮肤颜色是字符串，原样往返");
        assertEquals("body", decoded.personaPieces().get(0).type());
        assertEquals(List.of("#112233", "#445566"), decoded.tintColors().get(0).colors());
        assertFalse(decoded.trusted(), "旧布局尾部没有可信皮肤，保持默认值");
        assertNull(decoded.profileHash());
        baseline.release();
    }

    @Test
    void readsTheMot2168SkinBlock() {
        final ByteBuf buffer = Unpooled.buffer();
        BedrockTypes.STRING.write(buffer, "skin-id");
        BedrockTypes.STRING.write(buffer, "playfab");
        BedrockTypes.STRING.write(buffer, "{}");
        buffer.writeIntLE(0);
        buffer.writeIntLE(0);
        BedrockTypes.BYTE_ARRAY.write(buffer, new byte[0]);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 动画数
        buffer.writeIntLE(0);
        buffer.writeIntLE(0);
        BedrockTypes.BYTE_ARRAY.write(buffer, new byte[0]);
        BedrockTypes.STRING.write(buffer, "{}");
        BedrockTypes.STRING.write(buffer, "1.26.40");
        BedrockTypes.STRING.write(buffer, "");
        BedrockTypes.STRING.write(buffer, "cape");
        BedrockTypes.STRING.write(buffer, "full-skin");
        buffer.writeByte(1); // armSize = wide
        buffer.writeIntLE(0xFF123456); // skinColor
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1); // 人格件数
        BedrockTypes.STRING.write(buffer, "piece");
        buffer.writeIntLE(9); // 人格件类型：hands
        BedrockTypes.UUID.write(buffer, new UUID(1L, 2L));
        buffer.writeBoolean(true);
        BedrockTypes.STRING.write(buffer, "product");
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1); // 染色组数
        BedrockTypes.STRING.write(buffer, "persona_skin");
        for (int i = 0; i < 4; i++) {
            buffer.writeIntLE(i == 0 ? 0xFF00FF00 : 0);
        }
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeBoolean(true);
        BedrockTypes.STRING.write(buffer, "true"); // trusted
        BedrockTypes.STRING.write(buffer, "abcdef"); // profileHash

        final SkinData skin = SkinType.read(buffer, 2168);
        assertEquals(0, buffer.readableBytes());
        assertEquals("wide", skin.armSize());
        assertEquals("#FF123456", skin.skinColor());
        assertEquals("hands", skin.personaPieces().get(0).type());
        assertEquals("00000000-0000-0001-0000-000000000002", skin.personaPieces().get(0).packId());
        assertEquals(List.of("#FF00FF00", "#00000000", "#00000000", "#00000000"), skin.tintColors().get(0).colors());
        assertTrue(skin.premium());
        assertFalse(skin.persona());
        assertTrue(skin.overridingPlayerAppearance());
        assertTrue(skin.trusted());
        assertEquals("abcdef", skin.profileHash());
        buffer.release();
    }

    @Test
    void playerListEntryFlagsFollow2168() {
        assertTrue(PlayerListLayout.usesVarintEntryFlags(2168));
        assertTrue(PlayerListLayout.usesVarintEntryFlags(2192));
        assertFalse(PlayerListLayout.usesVarintEntryFlags(2167));
        assertFalse(PlayerListLayout.usesVarintEntryFlags(975));
        assertFalse(PlayerListLayout.usesTrailingTrustedArray(2168));
        assertTrue(PlayerListLayout.usesTrailingTrustedArray(2167));
    }

    @Test
    void typedActionIdsFollowThe2168Table() {
        // 2168 的 action id 表去掉了 7/8（两个已废弃的物品容器动作），其余整体前移两位。
        assertEquals(0, typedActionId(ItemStackRequestActionType.Take));
        assertEquals(1, typedActionId(ItemStackRequestActionType.Place));
        assertEquals(2, typedActionId(ItemStackRequestActionType.Swap));
        assertEquals(3, typedActionId(ItemStackRequestActionType.Drop));
        assertEquals(4, typedActionId(ItemStackRequestActionType.Destroy));
        assertEquals(5, typedActionId(ItemStackRequestActionType.Consume));
        assertEquals(6, typedActionId(ItemStackRequestActionType.Create));
        assertEquals(7, typedActionId(ItemStackRequestActionType.ScreenLabTableCombine));
        assertEquals(8, typedActionId(ItemStackRequestActionType.ScreenBeaconPayment));
        assertEquals(9, typedActionId(ItemStackRequestActionType.ScreenHUDMineBlock));
        assertEquals(10, typedActionId(ItemStackRequestActionType.CraftRecipe));
        assertEquals(11, typedActionId(ItemStackRequestActionType.CraftRecipeAuto));
        assertEquals(12, typedActionId(ItemStackRequestActionType.CraftCreative));
        assertEquals(13, typedActionId(ItemStackRequestActionType.CraftRecipeOptional));
        assertEquals(14, typedActionId(ItemStackRequestActionType.CraftRepairAndDisenchant));
        assertEquals(15, typedActionId(ItemStackRequestActionType.CraftLoom));
        assertEquals(17, typedActionId(ItemStackRequestActionType.CraftResults_DEPRECATEDASKTYLAING));
        assertThrows(IllegalStateException.class,
                () -> typedActionId(ItemStackRequestActionType.PlaceInItemContainer_DEPRECATED));
        assertThrows(IllegalStateException.class,
                () -> typedActionId(ItemStackRequestActionType.TakeFromItemContainer_DEPRECATED));
    }

    /** 2168 的 action id 表去掉了 7/8（两个已废弃的物品容器动作），其后的动作整体前移两位。 */
    private static int typedActionId(final ItemStackRequestActionType type) {
        return ItemStackRequestLayout.typedActionId(type, 2168);
    }

    @Test
    void actionIdTableStopsAtTheCraftReservedFork() {
        assertEquals(16, ItemStackRequestLayout.typedActionId(ItemStackRequestActionType.CraftNonImplemented_DEPRECATEDASKTYLAING, 2168));
        assertThrows(UnsupportedOperationException.class,
                () -> ItemStackRequestLayout.typedActionId(ItemStackRequestActionType.CraftCreative, 2192));
    }

    @Test
    void craftRecipeAutoWritesTypedIngredientsAt2168() {
        final ByteBuf typed = Unpooled.buffer();
        final BedrockItem first = new BedrockItem((short) 5);
        first.setAmount(2);
        final BedrockItem second = new BedrockItem((short) 7);
        second.setAmount(1);
        second.setData((short) 3);
        ItemStackRequestLayout.writeCraftRecipeAuto(typed, 42, 1, 1, List.of(first, second),
                List.of("minecraft:planks", "minecraft:wool"), false, 2168);
        assertEquals(11, BedrockTypes.UNSIGNED_VAR_INT.read(typed)); // CraftRecipeAuto
        assertEquals(11, typed.readUnsignedByte());
        assertEquals(42, BedrockTypes.UNSIGNED_VAR_INT.read(typed)); // recipeNetworkId
        assertEquals(1, typed.readUnsignedByte()); // numberOfRequestedCrafts
        assertEquals(2, BedrockTypes.UNSIGNED_VAR_INT.read(typed)); // 配料数
        assertEquals(1, BedrockTypes.UNSIGNED_VAR_INT.read(typed));
        assertEquals("name", BedrockTypes.STRING.read(typed));
        assertEquals("minecraft:planks", BedrockTypes.STRING.read(typed));
        assertEquals(0, BedrockTypes.VAR_INT.read(typed)); // 无 meta 写 0
        assertEquals(2, BedrockTypes.VAR_INT.read(typed));
        assertEquals(1, BedrockTypes.UNSIGNED_VAR_INT.read(typed));
        assertEquals("name", BedrockTypes.STRING.read(typed));
        assertEquals("minecraft:wool", BedrockTypes.STRING.read(typed));
        assertEquals(3, BedrockTypes.VAR_INT.read(typed));
        assertEquals(1, BedrockTypes.VAR_INT.read(typed));
        assertEquals(0, typed.readableBytes());
        typed.release();

        final ByteBuf legacy = Unpooled.buffer();
        ItemStackRequestLayout.writeCraftRecipeAuto(legacy, 42, 1, 1, List.of(first),
                List.of("minecraft:planks"), false, 2167);
        legacy.readUnsignedByte(); // action 类型
        BedrockTypes.UNSIGNED_VAR_INT.read(legacy);
        legacy.readUnsignedByte(); // 请求次数
        legacy.readUnsignedByte(); // timesCrafted
        assertEquals(1, legacy.readUnsignedByte()); // 旧布局的配料计数带一字节
        assertEquals(1, legacy.readUnsignedByte()); // DEFAULT
        assertEquals(5, legacy.readShortLE());
        assertEquals(0, legacy.readShortLE());
        assertEquals(2, BedrockTypes.VAR_INT.read(legacy));
        assertEquals(0, legacy.readableBytes());
        legacy.release();
    }

    @Test
    void craftRepairWritesLittleEndianRecipeIdAt2168() {
        final ByteBuf typed = Unpooled.buffer();
        ItemStackRequestLayout.writeCraftRepairAndDisenchant(typed, 0x01020304, 1, 7, false, 2168);
        assertEquals(14, BedrockTypes.UNSIGNED_VAR_INT.read(typed));
        assertEquals(14, typed.readUnsignedByte());
        assertEquals(0x01020304, typed.readIntLE());
        assertEquals(1, typed.readUnsignedByte());
        assertEquals(7, BedrockTypes.VAR_INT.read(typed));
        assertEquals(0, typed.readableBytes());
        typed.release();
    }

    @Test
    void actionTypeCarriesTheRemappedIdTwiceAt2168() {
        final ByteBuf typed = Unpooled.buffer();
        ItemStackRequestLayout.writeActionType(typed, ItemStackRequestActionType.CraftCreative, false, 2168);
        assertEquals(2, typed.readableBytes());
        assertEquals(12, BedrockTypes.UNSIGNED_VAR_INT.read(typed));
        assertEquals(12, typed.readUnsignedByte());
        typed.release();

        final ByteBuf legacy = Unpooled.buffer();
        ItemStackRequestLayout.writeActionType(legacy, ItemStackRequestActionType.CraftCreative, false, 2167);
        assertEquals(1, legacy.readableBytes());
        assertEquals(14, legacy.readByte());
        legacy.release();
    }

    @Test
    void typedIngredientResolvesTheNamespacedId() {
        final HashBiMap<String, Integer> items = HashBiMap.create();
        items.put("minecraft:stone", 7);
        final RecipeIngredientType typed = new RecipeIngredientType(items, Boolean.TRUE);

        final ByteBuf buffer = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1); // DEFAULT 压缩后的序数
        BedrockTypes.STRING.write(buffer, "name");
        BedrockTypes.STRING.write(buffer, "minecraft:stone");
        BedrockTypes.VAR_INT.write(buffer, 0); // 无 meta
        BedrockTypes.VAR_INT.write(buffer, 3);

        final RecipeIngredient ingredient = typed.read(buffer);
        assertEquals(0, buffer.readableBytes());
        assertEquals(7, ingredient.runtimeId());
        assertEquals(RecipeIngredient.ANY_DAMAGE, ingredient.damage(), "2168 用 0 表示无 meta，匹配时按通配符处理");
        assertEquals(3, ingredient.count());
        buffer.release();
    }

    @Test
    void typedIngredientConsumesTagMolangAndInvalidPayloads() {
        final RecipeIngredientType typed = new RecipeIngredientType(null, Boolean.TRUE);

        final ByteBuf tag = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.write(tag, 1);
        BedrockTypes.STRING.write(tag, "item_tag");
        BedrockTypes.STRING.write(tag, "minecraft:planks");
        BedrockTypes.VAR_INT.write(tag, 0);
        BedrockTypes.VAR_INT.write(tag, 2);
        assertEquals(new RecipeIngredient(0, 0, 0), typed.read(tag));
        assertEquals(0, tag.readableBytes());
        tag.release();

        final ByteBuf molang = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.write(molang, 1);
        BedrockTypes.STRING.write(molang, "molang");
        BedrockTypes.STRING.write(molang, "q.is_item_name_any('slot.weapon.mainhand')");
        molang.writeShortLE(1);
        BedrockTypes.VAR_INT.write(molang, 1);
        assertEquals(new RecipeIngredient(0, 0, 0), typed.read(molang));
        assertEquals(0, molang.readableBytes());
        molang.release();

        final ByteBuf invalid = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.write(invalid, 0);
        BedrockTypes.VAR_INT.write(invalid, 0);
        BedrockTypes.VAR_INT.write(invalid, 0);
        assertEquals(new RecipeIngredient(0, 0, 0), typed.read(invalid));
        assertEquals(0, invalid.readableBytes());
        invalid.release();
    }

    @Test
    void legacyIngredientKeepsTheShortPayload() {
        final RecipeIngredientType legacy = new RecipeIngredientType(null, Boolean.FALSE);
        final ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(1); // ItemDescriptorType.DEFAULT
        buffer.writeShortLE(7);
        buffer.writeShortLE(0xFFFF);
        BedrockTypes.VAR_INT.write(buffer, 1);

        final RecipeIngredient ingredient = legacy.read(buffer);
        assertEquals(0, buffer.readableBytes());
        assertEquals(7, ingredient.runtimeId());
        assertEquals(0xFFFF, ingredient.damage());
        assertEquals(1, ingredient.count());
        buffer.release();
    }

    @Test
    void unlockingRequirementFollowsTheProtocolFork() {
        final ByteBuf typed = Unpooled.buffer();
        BedrockTypes.VAR_INT.write(typed, 0); // 上下文 NONE
        typed.writeBoolean(true);
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, 1); // 额外配料数
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, 1);
        BedrockTypes.STRING.write(typed, "name");
        BedrockTypes.STRING.write(typed, "minecraft:stone");
        BedrockTypes.VAR_INT.write(typed, 0);
        BedrockTypes.VAR_INT.write(typed, 1);
        assertEquals(0, CraftingDataLayout.skipUnlockingRequirement(typed, false, 2168));
        assertEquals(0, typed.readableBytes());
        typed.release();

        final ByteBuf legacy = Unpooled.buffer();
        legacy.writeByte(0); // 上下文 NONE
        BedrockTypes.UNSIGNED_VAR_INT.write(legacy, 1);
        legacy.writeByte(1);
        legacy.writeShortLE(7);
        legacy.writeShortLE(0);
        BedrockTypes.VAR_INT.write(legacy, 1);
        assertEquals(0, CraftingDataLayout.skipUnlockingRequirement(legacy, false, 2167));
        assertEquals(0, legacy.readableBytes());
        legacy.release();
    }

    @Test
    void moveEntityDeltaReadsAxisBooleansAt2168() {
        final ByteBuf typed = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_LONG.write(typed, 7L); // 实体运行时 ID
        typed.writeBoolean(true); // 有 x
        BedrockTypes.FLOAT_LE.write(typed, 1.5F);
        typed.writeBoolean(false); // 无 y
        typed.writeBoolean(true); // 有 z
        BedrockTypes.FLOAT_LE.write(typed, -2.5F);
        typed.writeBoolean(false); // 无俯仰角
        typed.writeBoolean(false); // 无偏航角
        typed.writeBoolean(false); // 无头部偏航角
        typed.writeBoolean(true); // 在地面
        typed.writeBoolean(true); // forceMove
        typed.writeBoolean(true); // forceMoveLocalEntity
        typed.writeBoolean(false); // forceCompletion

        final MoveEntityDeltaLayout.Delta delta = MoveEntityDeltaLayout.read(
                wrapper(ClientboundBedrockPackets.MOVE_ENTITY_DELTA, typed), 2168);
        assertEquals(0, typed.readableBytes());
        assertEquals(7L, delta.entityRuntimeId());
        assertEquals(1.5F, delta.x());
        assertEquals(0F, delta.y());
        assertEquals(-2.5F, delta.z());
        assertTrue(delta.has(MoveEntityDeltaLayout.FLAG_HAS_X));
        assertFalse(delta.has(MoveEntityDeltaLayout.FLAG_HAS_Y));
        assertTrue(delta.has(MoveEntityDeltaLayout.FLAG_HAS_Z));
        assertTrue(delta.onGround());
        assertTrue(delta.forceMove());
        assertTrue(delta.forceMoveLocalEntity());
        assertFalse(delta.forceCompletion());
        assertFalse(delta.has(MoveEntityDeltaLayout.FLAG_TELEPORTING), "2168 取消了 TELEPORTING 位");
        typed.release();
    }

    @Test
    void moveEntityDeltaKeepsTheFlagMaskBelow2168() {
        final ByteBuf legacy = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_LONG.write(legacy, 9L);
        legacy.writeShortLE(MoveEntityDeltaLayout.FLAG_HAS_X | MoveEntityDeltaLayout.FLAG_HAS_Z
                | MoveEntityDeltaLayout.FLAG_ON_GROUND | MoveEntityDeltaLayout.FLAG_TELEPORTING); // 标志位
        BedrockTypes.FLOAT_LE.write(legacy, 4F);
        BedrockTypes.FLOAT_LE.write(legacy, 6F);

        final MoveEntityDeltaLayout.Delta delta = MoveEntityDeltaLayout.read(
                wrapper(ClientboundBedrockPackets.MOVE_ENTITY_DELTA, legacy), 2167);
        assertEquals(0, legacy.readableBytes());
        assertEquals(9L, delta.entityRuntimeId());
        assertEquals(4F, delta.x());
        assertEquals(0F, delta.y());
        assertEquals(6F, delta.z());
        assertTrue(delta.onGround());
        assertTrue(delta.has(MoveEntityDeltaLayout.FLAG_TELEPORTING));
        assertFalse(delta.forceMoveLocalEntity());
        legacy.release();
    }

    @Test
    void setScoreReadsTypedEntriesAt2168() {
        final ByteBuf typed = Unpooled.buffer();
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, 3); // 条目数
        // 变更假玩家
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, SetScoreLayout.TYPE_CHANGE_FAKE_PLAYER);
        BedrockTypes.STRING.write(typed, "CHANGE_FAKE_PLAYER");
        BedrockTypes.VAR_LONG.write(typed, 7L);
        BedrockTypes.STRING.write(typed, "objective");
        BedrockTypes.INT_LE.write(typed, 42);
        BedrockTypes.STRING.write(typed, "Bot");
        // 变更实体
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, SetScoreLayout.TYPE_CHANGE_ENTITY);
        BedrockTypes.STRING.write(typed, "CHANGE_ENTITY");
        BedrockTypes.VAR_LONG.write(typed, 8L);
        BedrockTypes.STRING.write(typed, "objective");
        BedrockTypes.INT_LE.write(typed, 11);
        BedrockTypes.VAR_LONG.write(typed, 1234L);
        // 移除，目标名称可选且缺省
        BedrockTypes.UNSIGNED_VAR_INT.write(typed, SetScoreLayout.TYPE_REMOVE);
        BedrockTypes.STRING.write(typed, "REMOVE");
        BedrockTypes.VAR_LONG.write(typed, 9L);
        typed.writeBoolean(false);

        final List<SetScoreLayout.Entry> entries = SetScoreLayout.read(
                wrapper(ClientboundBedrockPackets.SET_SCORE, typed), 2168);
        assertEquals(0, typed.readableBytes());
        assertEquals(3, entries.size());
        assertEquals(7L, entries.get(0).scoreboardId());
        assertEquals("objective", entries.get(0).objectiveName());
        assertEquals(42, entries.get(0).entry().score());
        assertEquals(IdentityDefinition_Type.FakePlayer, entries.get(0).entry().type());
        assertEquals("Bot", entries.get(0).entry().fakePlayerName());
        assertEquals(11, entries.get(1).entry().score());
        assertEquals(IdentityDefinition_Type.Entity, entries.get(1).entry().type());
        assertEquals(1234L, entries.get(1).entry().entityUniqueId());
        assertNull(entries.get(2).entry());
        assertEquals("", entries.get(2).objectiveName());
        typed.release();
    }

    @Test
    void setScoreKeepsThePacketPrefixBelow2168() {
        final ByteBuf legacy = Unpooled.buffer();
        legacy.writeByte(ScorePacketType.Change.getValue()); // 包级动作
        BedrockTypes.UNSIGNED_VAR_INT.write(legacy, 1); // 条目数
        BedrockTypes.VAR_LONG.write(legacy, 5L);
        BedrockTypes.STRING.write(legacy, "objective");
        BedrockTypes.INT_LE.write(legacy, 3);
        legacy.writeByte(IdentityDefinition_Type.Player.getValue()); // 条目类型
        BedrockTypes.VAR_LONG.write(legacy, 77L);

        final List<SetScoreLayout.Entry> entries = SetScoreLayout.read(
                wrapper(ClientboundBedrockPackets.SET_SCORE, legacy), 2167);
        assertEquals(0, legacy.readableBytes());
        assertEquals(1, entries.size());
        assertEquals(5L, entries.get(0).scoreboardId());
        assertEquals(3, entries.get(0).entry().score());
        assertEquals(IdentityDefinition_Type.Player, entries.get(0).entry().type());
        assertEquals(77L, entries.get(0).entry().entityUniqueId());
        legacy.release();
    }

    @Test
    void playerAuthInputShapeFollowsThe2168Deviations() {
        assertFalse(PlayerAuthInputLayout.usesInputDataList(2167));
        assertTrue(PlayerAuthInputLayout.usesInputDataList(2168));
        assertTrue(PlayerAuthInputLayout.usesInputDataList(2192));

        assertFalse(PlayerAuthInputLayout.usesOptionalSectionOuterBool(2167));
        assertTrue(PlayerAuthInputLayout.usesOptionalSectionOuterBool(2168));
        assertTrue(PlayerAuthInputLayout.usesOptionalSectionOuterBool(2169));
        assertFalse(PlayerAuthInputLayout.usesOptionalSectionOuterBool(2192), "2192 只剩内层存在性 bool");

        assertFalse(PlayerAuthInputLayout.usesUnconditionalBlockActions(2167));
        assertTrue(PlayerAuthInputLayout.usesUnconditionalBlockActions(2168));

        assertEquals(0, PlayerAuthInputLayout.extraInputFlags(false, 2168));
        assertEquals(0, PlayerAuthInputLayout.extraInputFlags(true, 2168), "改用条目列表后不再有网易额外位");
        assertEquals(2, PlayerAuthInputLayout.extraInputFlags(true, 860));
    }

    private PacketWrapper wrapper(final PacketType type, final ByteBuf payload) {
        return new PacketWrapperImpl(type, payload, this.user);
    }

    private static SkinData skinFixture() {
        final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        return new SkinData(
                "skin-id", "playfab", "{}", image,
                List.of(new SkinData.AnimationData(image, 2, 1.5F, 3)),
                image, "{}", "1.26.40", "",
                true, false, true, true,
                "cape", "full-skin", "wide", "#00FF00",
                List.of(new SkinData.PersonaPieceData("piece", "body", "12345678-1234-1234-1234-123456789012", true, "product")),
                List.of(new SkinData.PersonaPieceTintData("persona_skin", List.of("#112233", "#445566"))),
                true, true, "hash");
    }

}
