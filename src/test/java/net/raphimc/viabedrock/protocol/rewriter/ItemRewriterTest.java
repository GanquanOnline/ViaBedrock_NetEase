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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.HolderSet;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.AdventureModePredicate;
import com.viaversion.viaversion.api.minecraft.item.data.BlockPredicate;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
import com.viaversion.viaversion.api.minecraft.item.data.PotionContents;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffect;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffectData;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.configuration.AbstractViaConfig;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.PotionEffects1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.Potions1_20_5;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.custommapping.RuntimeProjection;
import net.raphimc.viabedrock.experimental.custommapping.SnapshotProfile;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.platform.ViaBedrockPlatform;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.model.ItemEntry;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRewriterTest {

    private static final int PAPER_ID = 101;
    private static final int IRON_SWORD_ID = 102;
    private static final int STONE_ID = 103;
    private static final int WOOL_ID = 104;
    private static final int CUSTOM_ITEM_ID = 105;
    private static final int POTION_ID = 106;
    private static final int BED_ID = 107;
    private static final int CROSSBOW_ID = 108;
    private static final int ARROW_ID = 109;
    private static final int SYNCED_CUSTOM_JAVA_ID = 200_000;
    private static final String CUSTOM_IDENTIFIER = "example:wand";
    private static final int MOT_ARROW_LEGACY_ID = 262;
    private static final int MOT_IRON_SWORD_LEGACY_ID = 267;

    private EmbeddedChannel channel;
    private UserConnectionImpl user;
    private BlockStateRewriter blockStateRewriter;
    private ItemRewriter rewriter;

    @BeforeAll
    static void loadMappings() throws InterruptedException {
        ensureViaLoaded();
        if (ViaBedrock.getConfig() == null) {
            ViaBedrock.init(new TestBedrockPlatform(), testBedrockConfig());
        }
        if (BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems() == null) {
            BedrockProtocol.MAPPINGS.load();
        }
    }

    @BeforeEach
    void setUp() {
        this.channel = new EmbeddedChannel();
        this.user = new UserConnectionImpl(this.channel);
        new ProtocolPipelineImpl(this.user);
        this.blockStateRewriter = new BlockStateRewriter(this.user, new BlockProperties[0], false);
        this.user.put(this.blockStateRewriter);
        this.user.put(ResourcePackStorage.createUnshared(List.of()));
        this.rewriter = new ItemRewriter(this.user, new ItemEntry[]{
                itemEntry("minecraft:paper", PAPER_ID),
                itemEntry("minecraft:iron_sword", IRON_SWORD_ID),
                itemEntry("minecraft:stone", STONE_ID),
                itemEntry("minecraft:wool", WOOL_ID),
                itemEntry(CUSTOM_IDENTIFIER, CUSTOM_ITEM_ID),
                itemEntry("minecraft:potion", POTION_ID),
                itemEntry("minecraft:bed", BED_ID),
                itemEntry("minecraft:crossbow", CROSSBOW_ID),
                itemEntry("minecraft:arrow", ARROW_ID)
        });
        this.user.put(this.rewriter);
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void emptyJavaItemBecomesEmptyBedrockItem() {
        assertTrue(assertDoesNotThrow(() -> this.rewriter.bedrockItem(null)).isEmpty());
        assertTrue(assertDoesNotThrow(() -> this.rewriter.bedrockItem(StructuredItem.empty())).isEmpty());
    }

    @Test
    void knownVanillaMetaItemPreservesIdentifierAndAmount() {
        final BedrockItem bedrockItem = this.rewriter.bedrockItem(creativeJavaItem("minecraft:iron_sword", 7));

        assertFalse(bedrockItem.isEmpty());
        assertEquals(IRON_SWORD_ID, bedrockItem.identifier());
        assertEquals(7, bedrockItem.amount());
        assertEquals(0, bedrockItem.data());
        assertEquals(0, bedrockItem.blockRuntimeId());
        assertNull(bedrockItem.tag());
    }

    @Test
    void unsignedBedrockAmountPreservesTwoHundredCountStacks() {
        final BedrockItem bedrockItem = this.rewriter.bedrockItem(creativeJavaItem("minecraft:iron_sword", 200));

        assertFalse(bedrockItem.isEmpty());
        assertEquals(200, bedrockItem.amount());

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        assertEquals(200, roundTrip.amount());
    }

    @Test
    void unsignedAmountsSurviveJavaBedrockRoundTripAtByteBoundaries() {
        for (int amount : new int[]{128, 200, 255}) {
            final BedrockItem bedrockItem = this.rewriter.bedrockItem(creativeJavaItem("minecraft:iron_sword", amount));
            assertFalse(bedrockItem.isEmpty());
            assertEquals(amount, bedrockItem.amount());

            final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
            assertEquals(amount, roundTrip.amount());
        }
    }

    @Test
    void legacyMetaAliasIsUsedWhenItIsTheOnlyCandidate() {
        final BedrockItem bedrockItem = this.rewriter.bedrockItem(creativeJavaItem("minecraft:white_wool", 3));

        assertFalse(bedrockItem.isEmpty());
        assertEquals(WOOL_ID, bedrockItem.identifier());
        assertEquals(0, bedrockItem.data());
        assertEquals(3, bedrockItem.amount());
    }

    @Test
    void coloredBedsKeepMetaInsteadOfUndyedBlockRuntime() {
        final StructuredItem javaRedBed = creativeJavaItem("minecraft:red_bed", 1);
        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaRedBed);

        assertFalse(bedrockItem.isEmpty());
        assertEquals(BED_ID, bedrockItem.identifier());
        assertEquals(14, bedrockItem.data());
        assertEquals(0, bedrockItem.blockRuntimeId());

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        assertEquals(javaRedBed.identifier(), roundTrip.identifier());
        assertEquals(1, roundTrip.amount());

        final BedrockItem onWire = new BedrockItem(BED_ID, (short) 14, (byte) 1);
        onWire.setBlockRuntimeId(12345);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            this.rewriter.itemType().write(buffer, onWire);
            final BedrockItem decoded = this.rewriter.itemType().read(buffer);
            assertEquals(14, decoded.data());
            assertEquals(0, decoded.blockRuntimeId());
            assertEquals(javaRedBed.identifier(), this.rewriter.javaItem(decoded).identifier());
        } finally {
            buffer.release();
        }
    }

    @Test
    void knownBlockItemGetsAValidRuntimeAndRoundTrips() {
        final StructuredItem javaStone = creativeJavaItem("minecraft:stone", 4);
        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaStone);

        assertFalse(bedrockItem.isEmpty());
        assertEquals(STONE_ID, bedrockItem.identifier());
        assertTrue(bedrockItem.blockRuntimeId() > 0);

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        assertEquals(javaStone.identifier(), roundTrip.identifier());
        assertEquals(4, roundTrip.amount());
    }

    @Test
    void paperFallbackIdentityRestoresCustomBedrockItem() {
        final StructuredItem javaPaper = creativeJavaItem("minecraft:paper", 2);
        CustomItemDataComponents.applyPaperFallbackIdentity(javaPaper, CUSTOM_IDENTIFIER);

        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaPaper);

        assertFalse(bedrockItem.isEmpty());
        assertEquals(CUSTOM_ITEM_ID, bedrockItem.identifier());
        assertEquals(2, bedrockItem.amount());
    }

    @Test
    void syncedCustomRawIdRoundTripsWithoutPaperFallbackIdentity() {
        this.installSyncedCustomItem();
        final BedrockItem source = new BedrockItem(CUSTOM_ITEM_ID);
        source.setAmount(6);

        final Item javaItem = this.rewriter.javaItem(source);
        assertEquals(SYNCED_CUSTOM_JAVA_ID, javaItem.identifier());
        assertEquals(6, javaItem.amount());
        final CompoundTag customData = javaItem.dataContainer().get(StructuredDataKey.CUSTOM_DATA);
        assertNotNull(customData);
        assertNotNull(customData.getCompoundTag(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY));
        assertFalse(customData.contains(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));

        final BedrockItem roundTrip = this.rewriter.bedrockItem(javaItem);
        assertFalse(roundTrip.isEmpty());
        assertEquals(CUSTOM_ITEM_ID, roundTrip.identifier());
        assertEquals(6, roundTrip.amount());
    }

    @Test
    void syncedCustomRawIdRoundTripsLosslessBedrockStateWithoutPaperFallback() {
        this.installSyncedCustomItem();
        final CompoundTag sourceTag = new CompoundTag();
        sourceTag.putString("custom:opaque", "preserved");
        final CompoundTag nested = new CompoundTag();
        nested.putInt("value", 17);
        sourceTag.put("nested", nested);
        final BedrockItem source = new BedrockItem(
                CUSTOM_ITEM_ID,
                (short) 12,
                (byte) 6,
                sourceTag,
                new String[]{"example:place"},
                new String[]{"example:break"},
                37L,
                -654321,
                991
        );

        final Item javaItem = this.rewriter.javaItem(source.copy());
        assertEquals(SYNCED_CUSTOM_JAVA_ID, javaItem.identifier());
        final CompoundTag customData = javaItem.dataContainer().get(StructuredDataKey.CUSTOM_DATA);
        assertNotNull(customData);
        assertNotNull(customData.getCompoundTag(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY));
        assertFalse(customData.contains(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));

        final BedrockItem roundTrip = this.rewriter.bedrockItem(javaItem);
        assertFalse(roundTrip.isEmpty());
        assertEquals(CUSTOM_ITEM_ID, roundTrip.identifier());
        assertEquals(12, roundTrip.data());
        assertEquals(6, roundTrip.amount());
        assertEquals(-654321, roundTrip.blockRuntimeId());
        assertEquals(37L, roundTrip.blockingTicks());
        assertNull(roundTrip.netId());
        assertArrayEquals(new String[]{"example:place"}, roundTrip.canPlace());
        assertArrayEquals(new String[]{"example:break"}, roundTrip.canBreak());
        assertEquals(sourceTag, roundTrip.tag());
    }

    @Test
    void syncedCustomRawIdIdentifierMarkerIsDecodedBeforeVanillaLookup() {
        this.installSyncedCustomItem();
        assertNull(BedrockProtocol.MAPPINGS.getJavaItems().inverse().get(SYNCED_CUSTOM_JAVA_ID));

        final StructuredItem javaItem = new StructuredItem(
                SYNCED_CUSTOM_JAVA_ID, 6, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag customData = new CompoundTag();
        customData.putString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY, CUSTOM_IDENTIFIER);
        javaItem.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData);

        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaItem);
        assertFalse(bedrockItem.isEmpty());
        assertEquals(CUSTOM_ITEM_ID, bedrockItem.identifier());
        assertEquals(6, bedrockItem.amount());
    }

    @Test
    void duplicateSyncedCustomRawIdsAreNotReversed() {
        final CustomMappingAccess.Builder builder = new CustomMappingAccess.Builder();
        builder.addItem("example:first", SYNCED_CUSTOM_JAVA_ID, 1);
        builder.addItem("example:second", SYNCED_CUSTOM_JAVA_ID, 1);

        assertNull(builder.build().customItemIdentifier(SYNCED_CUSTOM_JAVA_ID));
    }

    @Test
    void exactCanPlaceAndCanBreakPredicatesRoundTripWithoutBroadeningTags() {
        final BedrockItem source = new BedrockItem(IRON_SWORD_ID);
        source.setAmount(1);
        source.setCanPlace(new String[]{"minecraft:stone", "minecraft:oak_log"});
        source.setCanBreak(new String[]{"minecraft:stone"});

        final Item javaItem = this.rewriter.javaItem(source);
        assertNotNull(javaItem.dataContainer().get(VersionedTypes.V26_1.structuredDataKeys().canPlaceOn));
        assertNotNull(javaItem.dataContainer().get(VersionedTypes.V26_1.structuredDataKeys().canBreak));

        final BedrockItem roundTrip = this.rewriter.bedrockItem(javaItem);
        assertArrayEquals(source.canPlace(), roundTrip.canPlace());
        assertArrayEquals(source.canBreak(), roundTrip.canBreak());

        final StructuredItem taggedItem = creativeJavaItem("minecraft:iron_sword", 1);
        taggedItem.dataContainer().set(VersionedTypes.V26_1.structuredDataKeys().canPlaceOn, new AdventureModePredicate(
                new BlockPredicate[]{new BlockPredicate(HolderSet.of("minecraft:logs"), null, null)}));
        assertArrayEquals(new String[0], this.rewriter.bedrockItem(taggedItem).canPlace());
    }

    @Test
    void bedrockShadowIsDeterministicDeepAndRestoresOpaqueStateWithMutableOverlays() {
        final CompoundTag nested = new CompoundTag();
        nested.putString("value", "original");
        final CompoundTag display = new CompoundTag();
        display.putString("Name", "Original name");
        display.putString("opaque_display_field", "kept");
        final CompoundTag sourceTag = new CompoundTag();
        sourceTag.put("nested", nested);
        sourceTag.put("display", display);
        sourceTag.putInt("Damage", 2);
        sourceTag.putString("arbitrary", "bedrock-only");
        final BedrockItem source = new BedrockItem(
                CUSTOM_ITEM_ID,
                (short) 12,
                (byte) 3,
                sourceTag,
                new String[]{"example:opaque_place"},
                new String[]{"example:opaque_break"},
                91L,
                -1_234_567,
                777
        );

        final Item javaItem = this.rewriter.javaItem(source.copy());
        final Item duplicate = this.rewriter.javaItem(source.copy());
        final CompoundTag customData = javaItem.dataContainer().get(StructuredDataKey.CUSTOM_DATA);
        final CompoundTag duplicateCustomData = duplicate.dataContainer().get(StructuredDataKey.CUSTOM_DATA);
        assertEquals(customData, duplicateCustomData);
        assertEquals(customData.hashCode(), duplicateCustomData.hashCode());
        final CompoundTag shadow = customData.getCompoundTag(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY);
        assertNotNull(shadow);
        assertFalse(shadow.contains("network_id"));
        assertFalse(shadow.contains("net_id"));

        nested.putString("value", "mutated after conversion");
        javaItem.setAmount(9);
        javaItem.dataContainer().set(StructuredDataKey.DAMAGE, 7);
        javaItem.dataContainer().set(StructuredDataKey.CUSTOM_NAME, TextUtil.stringToNbt("Edited name"));
        javaItem.dataContainer().set(StructuredDataKey.LORE, new Tag[]{TextUtil.stringToNbt("Edited lore")});
        javaItem.dataContainer().set(VersionedTypes.V26_1.structuredDataKeys().canPlaceOn, new AdventureModePredicate(
                new BlockPredicate[]{new BlockPredicate(HolderSet.of("minecraft:stone"), null, null)}));
        javaItem.dataContainer().set(VersionedTypes.V26_1.structuredDataKeys().canBreak, new AdventureModePredicate(
                new BlockPredicate[]{new BlockPredicate(HolderSet.of("minecraft:oak_log"), null, null)}));

        final BedrockItem restored = this.rewriter.bedrockItem(javaItem);
        assertEquals(CUSTOM_ITEM_ID, restored.identifier());
        assertEquals(12, restored.data());
        assertEquals(9, restored.amount());
        assertEquals(-1_234_567, restored.blockRuntimeId());
        assertEquals(91L, restored.blockingTicks());
        assertNull(restored.netId());
        assertArrayEquals(new String[]{"minecraft:stone"}, restored.canPlace());
        assertArrayEquals(new String[]{"minecraft:oak_log"}, restored.canBreak());
        assertEquals("original", restored.tag().getCompoundTag("nested").getString("value", null));
        assertEquals("bedrock-only", restored.tag().getString("arbitrary", null));
        assertEquals(7, restored.tag().getInt("Damage", -1));
        final CompoundTag restoredDisplay = restored.tag().getCompoundTag("display");
        assertEquals("Edited name", restoredDisplay.getString("Name", null));
        assertEquals("kept", restoredDisplay.getString("opaque_display_field", null));
        assertEquals("Edited lore", restoredDisplay.getListTag("Lore", StringTag.class).get(0).getValue());
    }

    @Test
    void shadowPreservesEmptyAndOpaqueTagsAndRejectsMalformedPayloads() {
        final BedrockItem emptyTagged = new BedrockItem(CUSTOM_ITEM_ID);
        emptyTagged.setAmount(1);
        emptyTagged.setTag(new CompoundTag());
        final BedrockItem emptyRestored = this.rewriter.bedrockItem(this.rewriter.javaItem(emptyTagged));
        assertNotNull(emptyRestored.tag());
        assertTrue(emptyRestored.tag().isEmpty());

        final CompoundTag opaqueTag = new CompoundTag();
        opaqueTag.putString("display", "opaque");
        final BedrockItem opaqueTagged = new BedrockItem(CUSTOM_ITEM_ID);
        opaqueTagged.setAmount(1);
        opaqueTagged.setTag(opaqueTag);
        final BedrockItem opaqueRestored = this.rewriter.bedrockItem(this.rewriter.javaItem(opaqueTagged));
        assertEquals("opaque", ((StringTag) opaqueRestored.tag().get("display")).getValue());

        final StructuredItem malformed = creativeJavaItem("minecraft:paper", 1);
        final CompoundTag malformedData = new CompoundTag();
        malformedData.putString(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY, "not a compound");
        malformed.dataContainer().set(StructuredDataKey.CUSTOM_DATA, malformedData);
        assertTrue(assertDoesNotThrow(() -> this.rewriter.bedrockItem(malformed)).isEmpty());
    }

    @Test
    void creativeKnownEnchantmentsRoundTripAndUnknownEntriesAreSkipped() {
        final int sharpnessJavaId = registryId("minecraft:enchantment", "minecraft:sharpness");
        final Int2IntOpenHashMap levels = new Int2IntOpenHashMap();
        levels.put(sharpnessJavaId, 5);
        levels.put(Integer.MAX_VALUE, 3);
        final StructuredItem javaSword = creativeJavaItem("minecraft:iron_sword", 1);
        javaSword.dataContainer().set(StructuredDataKey.ENCHANTMENTS1_21_5, new Enchantments(levels));

        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaSword);
        final ListTag<CompoundTag> enchantments = bedrockItem.tag().getListTag("ench", CompoundTag.class);
        assertNotNull(enchantments);
        assertEquals(1, enchantments.size());
        assertEquals(Enchant_Type.Sharpness.getValue(), enchantments.get(0).getNumberTag("id").asInt());
        assertEquals(5, enchantments.get(0).getNumberTag("lvl").asInt());

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        ExperimentalItemRewriter.handleItem(this.user, bedrockItem, bedrockItem.tag(), roundTrip);
        final Enchantments roundTripEnchantments = roundTrip.dataContainer().get(StructuredDataKey.ENCHANTMENTS1_21_5);
        assertNotNull(roundTripEnchantments);
        assertEquals(5, roundTripEnchantments.getLevel(sharpnessJavaId));
        assertEquals(-1, roundTripEnchantments.getLevel(Integer.MAX_VALUE));
    }

    @Test
    void numericEnchantmentTagsMapToJavaEnchantments() {
        final int sharpnessJavaId = registryId("minecraft:enchantment", "minecraft:sharpness");
        final CompoundTag tag = new CompoundTag();
        final ListTag<CompoundTag> ench = new ListTag<>(CompoundTag.class);
        final CompoundTag entry = new CompoundTag();
        entry.putInt("id", Enchant_Type.Sharpness.getValue());
        entry.putInt("lvl", 5);
        ench.add(entry);
        tag.put("ench", ench);
        final BedrockItem source = new BedrockItem(IRON_SWORD_ID, (short) 0, (byte) 1, tag);

        final Item javaItem = this.rewriter.javaItem(source.copy());
        ExperimentalItemRewriter.handleItem(this.user, source, source.tag(), javaItem);

        final Enchantments enchantments = javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENTS1_21_5);
        assertNotNull(enchantments);
        assertEquals(5, enchantments.getLevel(sharpnessJavaId));
        assertNull(javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE));
    }

    @Test
    void mixedShortAndIntEnchantmentTagsStillMap() {
        final int sharpnessJavaId = registryId("minecraft:enchantment", "minecraft:sharpness");
        final CompoundTag tag = new CompoundTag();
        final ListTag<CompoundTag> ench = new ListTag<>(CompoundTag.class);
        final CompoundTag entry = new CompoundTag();
        entry.putShort("id", (short) Enchant_Type.Sharpness.getValue());
        entry.putInt("lvl", 3);
        ench.add(entry);
        tag.put("ench", ench);
        final BedrockItem source = new BedrockItem(IRON_SWORD_ID, (short) 0, (byte) 1, tag);

        final Item javaItem = this.rewriter.javaItem(source.copy());
        ExperimentalItemRewriter.handleItem(this.user, source, source.tag(), javaItem);

        final Enchantments enchantments = javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENTS1_21_5);
        assertNotNull(enchantments);
        assertEquals(3, enchantments.getLevel(sharpnessJavaId));
        assertNull(javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE));
    }

    @Test
    void unknownEnchantmentIdsStillRequestJavaGlint() {
        final CompoundTag tag = new CompoundTag();
        final ListTag<CompoundTag> ench = new ListTag<>(CompoundTag.class);
        final CompoundTag entry = new CompoundTag();
        entry.putInt("id", 32_767);
        entry.putInt("lvl", 1);
        ench.add(entry);
        tag.put("ench", ench);
        final BedrockItem source = new BedrockItem(IRON_SWORD_ID, (short) 0, (byte) 1, tag);

        final Item javaItem = this.rewriter.javaItem(source.copy());
        ExperimentalItemRewriter.handleItem(this.user, source, source.tag(), javaItem);

        final Enchantments enchantments = javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENTS1_21_5);
        assertTrue(enchantments == null || enchantments.size() == 0);
        assertEquals(Boolean.TRUE, javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE));
    }

    @Test
    void emptyEnchantmentListRequestsJavaGlint() {
        final CompoundTag tag = new CompoundTag();
        tag.put("ench", new ListTag<>(CompoundTag.class));
        final BedrockItem source = new BedrockItem(IRON_SWORD_ID, (short) 0, (byte) 1, tag);

        final Item javaItem = this.rewriter.javaItem(source.copy());
        ExperimentalItemRewriter.handleItem(this.user, source, source.tag(), javaItem);

        assertEquals(Boolean.TRUE, javaItem.dataContainer().get(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE));
    }

    @Test
    void creativePotionUsesBedrockAuxAndRoundTripsKnownCustomNbt() {
        final int nightVision = Potions1_20_5.keyToId("minecraft:night_vision");
        final int wither = PotionEffects1_20_5.keyToId("minecraft:wither");
        final PotionEffectData effectData = new PotionEffectData(2, 600, true, false, true, null);
        final StructuredItem javaPotion = creativeJavaItem("minecraft:potion", 2);
        javaPotion.dataContainer().set(StructuredDataKey.POTION_CONTENTS1_21_2, new PotionContents(
                nightVision,
                0x123456,
                new PotionEffect[]{
                        new PotionEffect(wither, effectData),
                        new PotionEffect(Integer.MAX_VALUE, effectData)
                }
        ));

        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaPotion);
        assertEquals(POTION_ID, bedrockItem.identifier());
        assertEquals(5, bedrockItem.data());
        assertEquals(2, bedrockItem.amount());
        assertEquals("minecraft:night_vision", bedrockItem.tag().getString("Potion", null));
        assertEquals(0x123456, bedrockItem.tag().getInt("CustomPotionColor", -1));
        final ListTag<CompoundTag> customEffects = bedrockItem.tag().getListTag("custom_potion_effects", CompoundTag.class);
        assertNotNull(customEffects);
        assertEquals(1, customEffects.size());
        assertEquals("minecraft:wither", customEffects.get(0).getString("id", null));

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        final PotionContents roundTripContents = roundTrip.dataContainer().get(StructuredDataKey.POTION_CONTENTS1_21_2);
        assertNotNull(roundTripContents);
        assertEquals(nightVision, roundTripContents.potion());
        assertEquals(0x123456, roundTripContents.customColor());
        assertEquals(1, roundTripContents.customEffects().length);
        assertEquals(wither, roundTripContents.customEffects()[0].effect());
        assertEquals(effectData, roundTripContents.customEffects()[0].effectData());
    }

    @Test
    void unknownFallbackIdentityAndUnknownJavaIdStaySafe() {
        final StructuredItem javaPaper = creativeJavaItem("minecraft:paper", 1);
        CustomItemDataComponents.applyPaperFallbackIdentity(javaPaper, "example:missing");

        assertTrue(assertDoesNotThrow(() -> this.rewriter.bedrockItem(javaPaper)).isEmpty());
        assertTrue(assertDoesNotThrow(() -> this.rewriter.bedrockItem(
                new StructuredItem(Integer.MAX_VALUE, 1, ProtocolConstants.createStructuredDataContainer()))).isEmpty());
    }

    @Test
    void amountDamageNameAndLoreRoundTripThroughRepresentableTags() {
        final StructuredItem javaSword = creativeJavaItem("minecraft:iron_sword", 5);
        javaSword.dataContainer().set(StructuredDataKey.DAMAGE, 23);
        javaSword.dataContainer().set(StructuredDataKey.CUSTOM_NAME, TextUtil.stringToNbt("Forged Blade"));
        javaSword.dataContainer().set(StructuredDataKey.LORE, new Tag[]{
                TextUtil.stringToNbt("First line"),
                TextUtil.stringToNbt("Second line")
        });
        javaSword.dataContainer().set(StructuredDataKey.MAX_STACK_SIZE, 1); // Not representable in legacy Bedrock item NBT.

        final BedrockItem bedrockItem = this.rewriter.bedrockItem(javaSword);
        final CompoundTag bedrockTag = assertDoesNotThrow(bedrockItem::tag);
        assertNotNull(bedrockTag);
        assertEquals(23, bedrockTag.getInt("Damage", -1));
        assertFalse(bedrockTag.contains("max_stack_size"));

        final CompoundTag display = (CompoundTag) bedrockTag.get("display");
        assertNotNull(display);
        assertEquals("Forged Blade", display.getString("Name", null));
        final ListTag<?> lore = (ListTag<?>) display.get("Lore");
        assertNotNull(lore);
        assertEquals(List.of("First line", "Second line"), lore.stream()
                .map(StringTag.class::cast)
                .map(StringTag::getValue)
                .toList());

        final Item roundTrip = this.rewriter.javaItem(bedrockItem.copy());
        ExperimentalItemRewriter.handleItem(this.user, bedrockItem, bedrockTag, roundTrip);
        assertEquals(5, roundTrip.amount());
        assertEquals(23, roundTrip.dataContainer().get(StructuredDataKey.DAMAGE));
        assertEquals("Forged Blade", legacy(roundTrip.dataContainer().get(StructuredDataKey.CUSTOM_NAME)));
        assertEquals(List.of("First line", "Second line"), List.of(roundTrip.dataContainer().get(StructuredDataKey.LORE)).stream()
                .map(ItemRewriterTest::legacy)
                .toList());
    }

    @Test
    void motNumericNbtIdResolvesThroughLegacyTable() {
        // MOT NBTIO.putItemHelper writes Count (byte), Damage (short aux/meta), and
        // unsigned short id. Java durability lives in the nested tag compound, not root Damage.
        final CompoundTag motArrow = new CompoundTag();
        motArrow.putShort("id", (short) MOT_ARROW_LEGACY_ID);
        motArrow.putByte("Count", (byte) 1);
        motArrow.putShort("Damage", (short) 0);

        final Item javaArrow = this.rewriter.javaItemFromNbt(motArrow);
        assertFalse(javaArrow.isEmpty());
        assertEquals(BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:arrow"), javaArrow.identifier());

        final CompoundTag motSword = new CompoundTag();
        motSword.putShort("id", (short) MOT_IRON_SWORD_LEGACY_ID);
        motSword.putByte("Count", (byte) 1);
        motSword.putShort("Damage", (short) 0);

        final Item javaSword = this.rewriter.javaItemFromNbt(motSword);
        assertFalse(javaSword.isEmpty());
        assertEquals(BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:iron_sword"), javaSword.identifier());
        assertNull(javaSword.dataContainer().get(StructuredDataKey.DAMAGE));
    }

    @Test
    void chargedCrossbowWritesMotNumericChargedItemAndRoundTrips() {
        final StructuredItem javaArrow = creativeJavaItem("minecraft:arrow", 1);
        final StructuredItem javaCrossbow = creativeJavaItem("minecraft:crossbow", 1);
        javaCrossbow.dataContainer().set(VersionedTypes.V26_1.structuredDataKeys().chargedProjectiles, new Item[]{javaArrow});

        final BedrockItem bedrockCrossbow = this.rewriter.bedrockItem(javaCrossbow);
        assertEquals(CROSSBOW_ID, bedrockCrossbow.identifier());
        final CompoundTag chargedItem = bedrockCrossbow.tag().getCompoundTag("chargedItem");
        assertNotNull(chargedItem);
        assertTrue(bedrockCrossbow.tag().getBoolean("Charged"));
        assertEquals(MOT_ARROW_LEGACY_ID, chargedItem.getShort("id"));
        assertNull(chargedItem.getString("Name", null));
        assertEquals(1, chargedItem.getByte("Count"));
        assertEquals(0, chargedItem.getShort("Damage"));

        final Item roundTrip = this.rewriter.javaItem(bedrockCrossbow.copy());
        ExperimentalItemRewriter.handleItem(this.user, bedrockCrossbow, bedrockCrossbow.tag(), roundTrip);
        final Item[] projectiles = roundTrip.dataContainer().get(VersionedTypes.V26_1.structuredDataKeys().chargedProjectiles);
        assertNotNull(projectiles);
        assertEquals(1, projectiles.length);
        assertEquals(javaArrow.identifier(), projectiles[0].identifier());
    }

    private void installSyncedCustomItem() {
        final RuntimeProjection projection = new RuntimeProjection(
                List.of(),
                List.of(),
                List.of(new SnapshotProfile.ItemMapping(
                        CUSTOM_IDENTIFIER, SYNCED_CUSTOM_JAVA_ID, SYNCED_CUSTOM_JAVA_ID, 16)));
        final CustomMappingSyncStorage storage = new CustomMappingSyncStorage(this.user);
        this.user.put(storage);
        storage.installProjection(projection);
    }

    private static ItemEntry itemEntry(final String identifier, final int id) {
        return new ItemEntry(identifier, id, false, ItemVersion.None, null);
    }

    private static StructuredItem creativeJavaItem(final String identifier, final int amount) {
        final Integer javaId = BedrockProtocol.MAPPINGS.getJavaItems().get(identifier);
        assertNotNull(javaId);
        return new StructuredItem(javaId, amount, ProtocolConstants.createStructuredDataContainer());
    }

    private static int registryId(final String registryIdentifier, final String entryIdentifier) {
        final CompoundTag registry = BedrockProtocol.MAPPINGS.getJavaRegistries().getCompoundTag(registryIdentifier);
        assertNotNull(registry);
        int id = 0;
        for (String identifier : registry.keySet()) {
            if (entryIdentifier.equals(identifier)) {
                return id;
            }
            id++;
        }
        throw new AssertionError("Missing registry entry " + entryIdentifier);
    }

    private static String legacy(final Tag text) {
        return ProtocolConstants.JAVA_TEXT_COMPONENT_SERIALIZER.deserializeNbtTree(text).asLegacyFormatString();
    }

    private static void ensureViaLoaded() throws InterruptedException {
        try {
            if (!Via.isLoaded()) {
                ViaManagerImpl.initAndLoad(new TestViaPlatform(), new NoopInjector(),
                        new ViaCommandHandler(false), ViaPlatformLoader.NOOP);
            }
            awaitMappingCompletion();
        } catch (final CompletionException e) {
            throw new AssertionError("Async ViaVersion mapping loading failed", e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static void awaitMappingCompletion() throws InterruptedException {
        final var protocolManager = Via.getManager().getProtocolManager();
        if (protocolManager.hasLoadedMappings()) {
            return;
        }
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (!protocolManager.hasLoadedMappings() && !protocolManager.checkForMappingCompletion(true)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for ViaVersion mapping completion");
            }
            Thread.sleep(100L);
        }
    }

    private static ViaBedrockConfig testBedrockConfig() {
        return (ViaBedrockConfig) Proxy.newProxyInstance(
                ItemRewriterTest.class.getClassLoader(),
                new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> configValue(method.getName(), method.getReturnType()));
    }

    private static Object configValue(final String name, final Class<?> type) {
        return switch (name) {
            case "getResourcePackMaxArchiveMiB" -> 2_048;
            case "getResourcePackMaxExpandedMiB" -> 4_096;
            case "getResourcePackMaxEntryMiB" -> 512;
            case "getResourcePackMaxEntries" -> 100_000;
            case "getResourcePackMaxCompressionRatio" -> 200;
            default -> defaultValue(type);
        };
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static final class TestBedrockPlatform implements ViaBedrockPlatform {

        @Override
        public Logger getLogger() {
            return Logger.getGlobal();
        }

        @Override
        public File getDataFolder() {
            return new File("build/tmp/item-rewriter-test");
        }
    }

    private static final class TestViaPlatform extends UserConnectionViaVersionPlatform {

        private TestViaPlatform() {
            super(null);
        }

        @Override
        public String getPlatformName() {
            return "ViaBedrock Test";
        }

        @Override
        public String getPlatformVersion() {
            return "test";
        }

        @Override
        public Logger createLogger(final String name) {
            return Logger.getGlobal();
        }

        @Override
        protected AbstractViaConfig createConfig() {
            return new AbstractViaConfig(null, null) {
                @Override
                public void reload() {
                }
            };
        }
    }
}
