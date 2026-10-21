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
package net.raphimc.viabedrock.protocol.rewriter;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.LongTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.HolderSet;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.AdventureModePredicate;
import com.viaversion.viaversion.api.minecraft.item.data.BlockPredicate;
import com.viaversion.viaversion.api.minecraft.item.data.DyedColor;
import com.viaversion.viaversion.api.minecraft.item.data.Enchantments;
import com.viaversion.viaversion.api.minecraft.item.data.PotionContents;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffect;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffectData;
import com.viaversion.viaversion.api.type.OptionalType;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.PotionEffects1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.Potions1_20_5;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.rewriter.ExperimentalItemRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomItemTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.ItemEntry;
import net.raphimc.viabedrock.protocol.rewriter.item.BundleItemRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomAttachableResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomItemTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomBlockTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.array.ArrayType;
import net.raphimc.viabedrock.protocol.types.item.BedrockItemType;
import net.raphimc.viabedrock.protocol.types.item.NetworkItemStackDescriptorType;

import java.util.*;
import java.util.logging.Level;

public class ItemRewriter extends StoredObject {

    private static final Map<String, NbtRewriter> ITEM_NBT_REWRITERS = new HashMap<>();
    private static final StructuredDataKey<AdventureModePredicate> JAVA_CAN_PLACE_ON = VersionedTypes.V26_1.structuredDataKeys().canPlaceOn;
    private static final StructuredDataKey<AdventureModePredicate> JAVA_CAN_BREAK = VersionedTypes.V26_1.structuredDataKeys().canBreak;
    private static final StructuredDataKey<Item[]> JAVA_CHARGED_PROJECTILES = VersionedTypes.V26_1.structuredDataKeys().chargedProjectiles;
    private static final Set<String> DYEABLE_LEATHER_ITEMS = Set.of(
            "minecraft:leather_helmet",
            "minecraft:leather_chestplate",
            "minecraft:leather_leggings",
            "minecraft:leather_boots",
            "minecraft:leather_horse_armor"
    );

    private final BiMap<String, Integer> items;
    private final Set<String> componentItems;
    private final BlockStateRewriter blockStateRewriter;
    private final Int2ObjectMap<IntSortedSet> blockItemValidBlockStates;
    private final Int2ObjectMap<JavaToBedrockItemMapping> javaToBedrockItems;
    private final Int2ObjectMap<PotionItemMappings> javaToBedrockPotionItems;
    private final Int2ObjectMap<Enchant_Type> javaToBedrockEnchantments;
    private final Type<BedrockItem> itemType;
    private final Type<BedrockItem> optionalItemType;
    private final Type<BedrockItem[]> itemArrayType;
    private final Type<BedrockItem> newItemType;
    private final Type<BedrockItem> optionalNewItemType;
    private final Type<BedrockItem[]> newItemArrayType;

    static {
        // TODO: Add missing item nbt rewriters
        ITEM_NBT_REWRITERS.put(CustomItemTags.BUNDLE, new BundleItemRewriter());
    }

    public ItemRewriter(final UserConnection user, final ItemEntry[] itemEntries) {
        super(user);

        this.items = HashBiMap.create(itemEntries.length);
        this.componentItems = new HashSet<>();
        for (ItemEntry itemEntry : itemEntries) {
            this.items.inverse().remove(itemEntry.id());
            this.items.put(itemEntry.identifier(), itemEntry.id());
            if (itemEntry.version() == ItemVersion.DataDriven || (itemEntry.version() == ItemVersion.None && itemEntry.componentBased())) {
                this.componentItems.add(itemEntry.identifier());
            }
        }
        this.blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final BlockStateRewriter blockStateRewriter = this.blockStateRewriter;
        final Set<String> blockItems = new HashSet<>(BedrockProtocol.MAPPINGS.getBedrockBlockItems());
        if (blockStateRewriter != null) {
            for (String identifier : this.items.keySet()) {
                if (blockStateRewriter.validBlockStates(identifier) != null) {
                    blockItems.add(identifier);
                    continue;
                }
                final String[] components = identifier.split(":", 2);
                if (components.length == 2 && components[1].startsWith("item.")
                        && blockStateRewriter.validBlockStates(components[0] + ":" + components[1].substring(5)) != null) {
                    blockItems.add(identifier);
                }
            }
        }
        // MOT 860 still ships colored beds (and similar) as one identifier + meta 0-15.
        // Those identifiers also name an undyed block, so treating them as block items
        // would zero aux data and collapse every color to white_bed / the undyed Java item.
        blockItems.removeIf(identifier -> !this.items.containsKey(identifier) || isMetaOnlyItem(identifier));

        this.blockItemValidBlockStates = new Int2ObjectOpenHashMap<>(blockItems.size());
        for (String identifier : blockItems) {
            IntSortedSet validBlockStates = null;
            if (blockStateRewriter != null) {
                validBlockStates = blockStateRewriter.validBlockStates(identifier);
                if (validBlockStates == null) {
                    final String[] components = identifier.split(":", 2);
                    if (components.length == 2 && components[1].startsWith("item.")) {
                        validBlockStates = blockStateRewriter.validBlockStates(components[0] + ':' + components[1].substring(5));
                    }
                }
            }
            if (validBlockStates != null) {
                final int itemId = this.items.get(identifier).intValue();
                this.blockItemValidBlockStates.put(itemId, validBlockStates);
                if (itemId < 0) {
                    this.blockItemValidBlockStates.put(itemId + 65536, validBlockStates);
                } else if (itemId > 32767) {
                    this.blockItemValidBlockStates.put(itemId - 65536, validBlockStates);
                }
            } else {
                Via.getPlatform().getLogger().log(Level.WARNING, "Missing block for block item: " + identifier);
            }
        }

        this.javaToBedrockItems = this.createJavaToBedrockItemMappings(blockStateRewriter);
        this.javaToBedrockPotionItems = this.createJavaToBedrockPotionItemMappings();
        this.javaToBedrockEnchantments = createJavaToBedrockEnchantments();
        this.itemType = new BedrockItemType(this.items.getOrDefault("minecraft:shield", 0), this.blockItemValidBlockStates, false);
        this.optionalItemType = new OptionalType<>(this.itemType);
        this.itemArrayType = new ArrayType<>(this.itemType, BedrockTypes.UNSIGNED_VAR_INT);
        // NetEase 860 still serializes NetworkItemStackDescriptor slots with the legacy item layout (varint id)
        final boolean netEaseLegacyItemLayout = ViaBedrock.getConfig().shouldEmulateNetEaseClient()
                && ViaBedrock.getConfig().getNetEaseProtocolVersion() > 0
                && ViaBedrock.getConfig().getNetEaseProtocolVersion() < 898;
        this.newItemType = netEaseLegacyItemLayout
                ? this.itemType
                : new NetworkItemStackDescriptorType(this.items.getOrDefault("minecraft:shield", 0), this.blockItemValidBlockStates, false);
        this.optionalNewItemType = new OptionalType<>(this.newItemType);
        this.newItemArrayType = new ArrayType<>(this.newItemType, BedrockTypes.UNSIGNED_VAR_INT);
    }

    public Item javaItem(final BedrockItem bedrockItem) {
        try {
            return this.javaItem0(bedrockItem);
        } catch (final RuntimeException e) {
            // MOT 860 custom/unmapped block items (askyblockwar:*, cinnabar walls) used to NPE
            // IntSortedSet.firstInt() inside ADD_PLAYER / MOB_EQUIPMENT and kick Java 1.21.11.
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to rewrite Bedrock item id=" + (bedrockItem != null ? bedrockItem.identifier() : -1), e);
            return StructuredItem.empty();
        }
    }

    private Item javaItem0(final BedrockItem bedrockItem) {
        if (bedrockItem == null || bedrockItem.isEmpty()) return StructuredItem.empty();

        final String identifier = this.bedrockIdentifier(bedrockItem.identifier());
        if (identifier == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing item identifier for id: " + bedrockItem.identifier());
            return StructuredItem.empty();
        }

        final BedrockMappingData.JavaItemMapping javaItemMapping;
        final Map<BlockState, BedrockMappingData.JavaItemMapping> blockItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().get(identifier);
        if (blockItemMappings != null) {
            if (bedrockItem.blockRuntimeId() == 0) { // Manually constructed items might not have a valid block state set
                // MOT 860 / NetEase extras can list the identifier without a session palette.
                // firstInt() on a missing IntSortedSet used to NPE ADD_PLAYER / MOB_EQUIPMENT.
                bedrockItem.setBlockRuntimeId(BlockItemMappingLayout.fallbackBlockRuntimeId(
                        this.blockItemValidBlockStates.get(bedrockItem.identifier())));
            }
            javaItemMapping = this.javaBlockItemMapping(blockItemMappings, bedrockItem.blockRuntimeId());
        } else {
            final int meta = bedrockItem.data() & 0xFFFF;
            final String newIdentifier = BedrockProtocol.MAPPINGS.getBedrockItemUpgrader().upgradeMetaItem(identifier, meta);
            if (newIdentifier != null) {
                final Map<BlockState, BedrockMappingData.JavaItemMapping> newBlockItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().get(newIdentifier);
                if (newBlockItemMappings != null) {
                    javaItemMapping = this.javaBlockItemMapping(newBlockItemMappings, BlockItemMappingLayout.fallbackBlockRuntimeId(
                            this.blockItemValidBlockStates.get(bedrockItem.identifier())));
                } else {
                    javaItemMapping = null;
                }
            } else {
                final Map<Integer, BedrockMappingData.JavaItemMapping> metaItemMappings = BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().get(identifier);
                if (metaItemMappings != null) {
                    if (!metaItemMappings.containsKey(meta)) {
                        if (metaItemMappings.size() != 1 || meta != 0) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing meta: " + meta + " for item: " + identifier);
                        }
                        javaItemMapping = metaItemMappings.get(null);
                    } else {
                        javaItemMapping = metaItemMappings.get(meta);
                    }
                } else {
                    javaItemMapping = null;
                }
            }
        }

        final Item javaItem;
        boolean paperFallback = false;
        if (javaItemMapping != null) {
            final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();
            if (javaItemMapping.overrideTag() != null) {
                LegacyItemTagRewriter.apply(data, javaItemMapping.overrideTag());
            }
            if (javaItemMapping.name() != null) {
                final ResourcePackStorage resourcePackStorage = this.user().get(ResourcePackStorage.class);
                data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get(javaItemMapping.name())));
                data.set(StructuredDataKey.LORE, new Tag[]{TextUtil.stringToNbt("§7[ViaBedrock] Mapped item: " + identifier)});
            }
            javaItem = new StructuredItem(javaItemMapping.id(), bedrockItem.amount(), data);
        } else {
            final CustomMappingSyncStorage customMappingSync = this.user().get(CustomMappingSyncStorage.class);
            final CustomMappingAccess.CustomItemMetadata syncedCustomItem = customMappingSync != null ? customMappingSync.access().customItem(identifier) : null;
            if (syncedCustomItem != null) {
                javaItem = new StructuredItem(syncedCustomItem.javaRawId(), bedrockItem.amount(), ProtocolConstants.createStructuredDataContainer());
                CustomItemDataComponents.applyMaxStackSize(javaItem, syncedCustomItem.maxStackSize() > 0 ? syncedCustomItem.maxStackSize() : null, false);
            } else {
                final ResourcePackStorage resourcePackStorage = this.user().get(ResourcePackStorage.class);
                final ItemDefinitions.ItemDefinition itemDefinition = resourcePackStorage.getItems().get(identifier);
                final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();

                if (itemDefinition != null) {
                    if (itemDefinition.displayNameComponent() != null) {
                        data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().translate(itemDefinition.displayNameComponent())));
                    } else if (this.componentItems.contains(identifier)) {
                        data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get("item." + Key.stripMinecraftNamespace(identifier))));
                    } else {
                        data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt(resourcePackStorage.getTexts().get("item." + Key.stripMinecraftNamespace(identifier) + ".name")));
                    }

                    if (resourcePackStorage.getAttachables().attachables().containsKey(identifier) && resourcePackStorage.isLoadedOnJavaClient() && resourcePackStorage.getRuntimeData().containsKey("ca_" + identifier + "_default")) {
                        data.set(StructuredDataKey.ITEM_MODEL, CustomAttachableResourceRewriter.getItemModel(identifier));
                        data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomAttachableResourceRewriter.getCustomModelData("default"));
                    } else if (itemDefinition.iconComponent() != null && resourcePackStorage.isLoadedOnJavaClient()) {
                        data.set(StructuredDataKey.ITEM_MODEL, CustomItemTextureResourceRewriter.getItemModel(itemDefinition.iconComponent()));
                        data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomItemTextureResourceRewriter.getCustomModelData("0"));
                    } else if (CustomBlockTextureResourceRewriter.hasConvertedTexture(resourcePackStorage, identifier)
                            && resourcePackStorage.isLoadedOnJavaClient()) {
                        data.set(StructuredDataKey.ITEM_MODEL, CustomBlockTextureResourceRewriter.getItemModel(identifier));
                        data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomBlockTextureResourceRewriter.getCustomModelData("0"));
                    } else {
                        data.set(StructuredDataKey.LORE, new Tag[]{TextUtil.stringToNbt("§7[ViaBedrock] Custom item: " + identifier)});
                    }
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing bedrock -> java item mapping for " + identifier);
                    data.set(StructuredDataKey.ITEM_NAME, TextUtil.stringToNbt("§cMissing item: " + identifier));
                }
                javaItem = new StructuredItem(BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:paper"), bedrockItem.amount(), data);
                paperFallback = true;
            }
        }

        final ItemDefinitions.ItemDefinition itemDefinition = this.user().get(ResourcePackStorage.class).getItems().get(identifier);
        if (itemDefinition != null) {
            CustomItemDataComponents.applyMaxStackSize(javaItem, itemDefinition.maxStackSize(), true);
        }
        if (paperFallback) {
            CustomItemDataComponents.applyPaperFallbackIdentity(javaItem, identifier);
        }
        if (itemDefinition != null) {
            CustomItemDataComponents.applyConsumable(javaItem, itemDefinition.itemUseDefinition(), ViaBedrock.getConfig().shouldEnableExperimentalFeatures());
        }

        final CompoundTag bedrockTag = bedrockItem.tag();
        this.applyDyedColor(identifier, bedrockTag, javaItem);
        if (bedrockTag != null) {
            if (bedrockTag.get("display") instanceof CompoundTag display) {
                final List<Tag> additionalLore = new ArrayList<>();

                // Handle display.Name (support \n line breaks)
                if (display.contains("Name")) { // Bedrock client defaults to empty string if the type is wrong
                    final String name = display.getString("Name", "");
                    if (name.contains("\n")) {
                        final String[] nameLines = name.split("\n", -1);
                        javaItem.dataContainer().set(StructuredDataKey.CUSTOM_NAME, TextUtil.stringToNbt(nameLines[0]));
                        for (int i = 1; i < nameLines.length; i++) {
                            additionalLore.add(TextUtil.stringToNbt(nameLines[i]));
                        }
                    } else {
                        javaItem.dataContainer().set(StructuredDataKey.CUSTOM_NAME, TextUtil.stringToNbt(name));
                    }
                }

                // Convert display.Lore (Bedrock Lore is ListTag<StringTag>)
                if (display.get("Lore") instanceof ListTag<?> bedrockLore) {
                    for (Tag loreEntry : bedrockLore) {
                        if (loreEntry instanceof StringTag loreString) {
                            additionalLore.add(TextUtil.stringToNbt(TextUtil.toSingleLine(loreString.getValue())));
                        }
                    }
                }

                // Merge: name overflow lines + Bedrock Lore + existing debug Lore
                if (!additionalLore.isEmpty()) {
                    final Tag[] existingLore = javaItem.dataContainer().get(StructuredDataKey.LORE);
                    if (existingLore != null) {
                        Collections.addAll(additionalLore, existingLore);
                    }
                    javaItem.dataContainer().set(StructuredDataKey.LORE, additionalLore.toArray(new Tag[0]));
                }
            }
            LegacyItemTagRewriter.apply(javaItem.dataContainer(), bedrockTag);
        }

        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalItemRewriter.handleItem(this.user(), bedrockItem, bedrockTag, javaItem);
        }

        final String tag = BedrockProtocol.MAPPINGS.getBedrockCustomItemTags().get(identifier);
        if (ITEM_NBT_REWRITERS.containsKey(tag)) {
            ITEM_NBT_REWRITERS.get(tag).toJava(this.user(), bedrockItem, javaItem);
        }

        this.applyAdventureModePredicate(javaItem, JAVA_CAN_PLACE_ON, bedrockItem.canPlace());
        this.applyAdventureModePredicate(javaItem, JAVA_CAN_BREAK, bedrockItem.canBreak());
        CustomItemDataComponents.applyBedrockItemShadow(javaItem, identifier, bedrockItem);
        return javaItem;
    }

    private BedrockMappingData.JavaItemMapping javaBlockItemMapping(
            final Map<BlockState, BedrockMappingData.JavaItemMapping> blockItemMappings,
            final int blockRuntimeId
    ) {
        if (blockItemMappings == null || blockRuntimeId == 0) {
            return null;
        }
        final BlockState blockState = this.user().get(BlockStateRewriter.class).blockState(blockRuntimeId);
        if (blockState == null) {
            return null;
        }
        return blockItemMappings.get(blockState);
    }

    private Integer applyDyedColor(final String identifier, final CompoundTag bedrockTag, final Item javaItem) {
        if (bedrockTag == null || !DYEABLE_LEATHER_ITEMS.contains(identifier)) {
            return null;
        }

        if (bedrockTag.get("customColor") instanceof NumberTag customColor) {
            final int rgb = customColor.asInt() & 0xFFFFFF;
            javaItem.dataContainer().set(StructuredDataKey.DYED_COLOR1_21_5, new DyedColor(rgb));
            return rgb;
        }
        return null;
    }

    private void applyAdventureModePredicate(
            final Item javaItem,
            final StructuredDataKey<AdventureModePredicate> key,
            final String[] bedrockIdentifiers
    ) {
        if (this.blockStateRewriter == null || bedrockIdentifiers == null || bedrockIdentifiers.length == 0) {
            return;
        }

        final BiMap<String, Integer> javaBlocks = BedrockProtocol.MAPPINGS.getJavaBlocks();
        final Set<Integer> javaBlockIds = new LinkedHashSet<>(bedrockIdentifiers.length);
        for (String identifier : bedrockIdentifiers) {
            if (identifier == null) {
                return;
            }
            final String namespacedIdentifier;
            try {
                namespacedIdentifier = Key.namespaced(identifier);
            } catch (final IllegalArgumentException ignored) {
                return;
            }
            final Integer javaBlockId = javaBlocks.get(namespacedIdentifier);
            if (javaBlockId == null || this.blockStateRewriter.validBlockStates(namespacedIdentifier) == null) {
                return;
            }
            javaBlockIds.add(javaBlockId);
        }

        final int[] ids = javaBlockIds.stream().mapToInt(Integer::intValue).toArray();
        final BlockPredicate blockPredicate = new BlockPredicate(HolderSet.of(ids), null, null);
        javaItem.dataContainer().set(key, new AdventureModePredicate(new BlockPredicate[]{blockPredicate}));
    }

    public CompoundTag javaItem(final CompoundTag bedrockTag) {
        final Item javaItem = this.javaItemFromNbt(bedrockTag);
        final CompoundTag javaTag = new CompoundTag();
        if (javaItem == null || javaItem.isEmpty()) {
            javaTag.putString("id", "minecraft:air");
            javaTag.putInt("count", 0);
            return javaTag;
        }
        final com.google.common.collect.BiMap<String, Integer> javaItems = BedrockProtocol.MAPPINGS.getJavaItems();
        final String identifier = javaItems != null ? javaItems.inverse().get(javaItem.identifier()) : null;
        javaTag.putString("id", identifier != null ? identifier : "minecraft:air");
        javaTag.putInt("count", Math.max(1, javaItem.amount()));
        if (bedrockTag != null && bedrockTag.get("Slot") instanceof NumberTag slotTag) {
            javaTag.putByte("Slot", (byte) slotTag.asInt());
        }
        return javaTag;
    }

    public Item javaItemFromNbt(final CompoundTag itemTag) {
        if (itemTag == null) return StructuredItem.empty();

        String name = itemTag.getString("Name", null);
        if (name == null && itemTag.get("id") instanceof NumberTag idTag) {
            name = this.bedrockIdentifierFromNumericNbtId(idTag.asInt());
        }
        if (name == null) return StructuredItem.empty();
        name = Key.namespaced(name);

        Integer id = this.items.get(name);
        if (id == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown nbt item identifier: " + name);
            return StructuredItem.empty();
        }

        final int count = itemTag.get("Count") instanceof NumberTag countTag ? countTag.asInt() : 1;
        final int damage = itemTag.get("Damage") instanceof NumberTag damageTag ? damageTag.asInt() : 0;
        final BedrockItem bedrockItem = new BedrockItem(id, (short) damage, (byte) count);
        if (itemTag.get("tag") instanceof CompoundTag tag) {
            bedrockItem.setTag(tag);
        }
        return this.javaItem(bedrockItem);
    }

    public Item[] javaItems(final BedrockItem[] bedrockItems) {
        final Item[] javaItems = new Item[bedrockItems.length];
        for (int i = 0; i < bedrockItems.length; i++) {
            javaItems[i] = this.javaItem(bedrockItems[i]);
        }
        return javaItems;
    }

    private Int2ObjectMap<JavaToBedrockItemMapping> createJavaToBedrockItemMappings(final BlockStateRewriter blockStateRewriter) {
        final Map<Integer, Set<JavaToBedrockItemMapping>> candidates = new HashMap<>();

        for (Map.Entry<String, Map<Integer, BedrockMappingData.JavaItemMapping>> itemEntry
                : BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().entrySet()) {
            final String bedrockIdentifier = itemEntry.getKey();
            final Integer bedrockId = this.items.get(bedrockIdentifier);
            if (!isVanillaItem(bedrockIdentifier, bedrockId)) {
                continue;
            }

            final Map<Integer, BedrockMappingData.JavaItemMapping> mappings = itemEntry.getValue();
            for (Map.Entry<Integer, BedrockMappingData.JavaItemMapping> mappingEntry : mappings.entrySet()) {
                final Integer meta = mappingEntry.getKey();
                if (meta == null && mappings.containsKey(0)) {
                    continue;
                }
                final int data = meta != null ? meta : 0;
                final BedrockMappingData.JavaItemMapping javaMapping = mappingEntry.getValue();
                if (data < 0 || data > Short.MAX_VALUE || !isSafelyReversible(javaMapping)) {
                    continue;
                }
                addReverseCandidate(candidates, javaMapping.id(),
                        new JavaToBedrockItemMapping(bedrockIdentifier, bedrockId, (short) data, 0));
            }
        }

        if (blockStateRewriter != null) {
            for (Map.Entry<String, Map<BlockState, BedrockMappingData.JavaItemMapping>> itemEntry
                    : BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().entrySet()) {
                final String bedrockIdentifier = itemEntry.getKey();
                final Integer bedrockId = this.items.get(bedrockIdentifier);
                if (!isVanillaItem(bedrockIdentifier, bedrockId)) {
                    continue;
                }

                final IntSortedSet validBlockStates = this.blockItemValidBlockStates.get(bedrockId.intValue());
                if (validBlockStates == null || validBlockStates.isEmpty()) {
                    continue;
                }

                final Map<Integer, JavaToBedrockItemMapping> canonicalMappings = new HashMap<>();
                for (Map.Entry<BlockState, BedrockMappingData.JavaItemMapping> mappingEntry : itemEntry.getValue().entrySet()) {
                    final BedrockMappingData.JavaItemMapping javaMapping = mappingEntry.getValue();
                    if (!isSafelyReversible(javaMapping)) {
                        continue;
                    }
                    final int blockRuntimeId = blockStateRewriter.bedrockId(mappingEntry.getKey());
                    if (blockRuntimeId == 0 || blockRuntimeId == -1 || !validBlockStates.contains(blockRuntimeId)) {
                        continue;
                    }
                    final JavaToBedrockItemMapping candidate = new JavaToBedrockItemMapping(
                            bedrockIdentifier, bedrockId, (short) 0, blockRuntimeId);
                    canonicalMappings.merge(javaMapping.id(), candidate,
                            (first, second) -> first.blockRuntimeId() <= second.blockRuntimeId() ? first : second);
                }
                canonicalMappings.forEach((javaId, candidate) -> addReverseCandidate(candidates, javaId, candidate));
            }
        }

        final Int2ObjectMap<JavaToBedrockItemMapping> mappings = new Int2ObjectOpenHashMap<>();
        final BiMap<String, Integer> javaItems = BedrockProtocol.MAPPINGS.getJavaItems();
        if (javaItems == null) {
            return mappings;
        }
        for (Map.Entry<Integer, Set<JavaToBedrockItemMapping>> entry : candidates.entrySet()) {
            final String javaIdentifier = javaItems.inverse().get(entry.getKey());
            if (javaIdentifier == null || !javaIdentifier.startsWith("minecraft:")) {
                continue;
            }

            JavaToBedrockItemMapping exactMatch = null;
            boolean multipleExactMatches = false;
            for (JavaToBedrockItemMapping candidate : entry.getValue()) {
                if (!candidate.bedrockIdentifier().equals(javaIdentifier)) {
                    continue;
                }
                if (exactMatch != null) {
                    multipleExactMatches = true;
                    break;
                }
                exactMatch = candidate;
            }
            if (exactMatch != null && !multipleExactMatches) {
                mappings.put(entry.getKey().intValue(), exactMatch);
            } else if (exactMatch == null && entry.getValue().size() == 1) {
                mappings.put(entry.getKey().intValue(), entry.getValue().iterator().next());
            }
        }
        return mappings;
    }

    private Int2ObjectMap<PotionItemMappings> createJavaToBedrockPotionItemMappings() {
        final Map<Integer, Map<PotionContentsKey, Set<JavaToBedrockItemMapping>>> exactCandidates = new HashMap<>();
        final Map<Integer, Map<Integer, Set<JavaToBedrockItemMapping>>> potionCandidates = new HashMap<>();
        final Map<Integer, Set<JavaToBedrockItemMapping>> fallbackCandidates = new HashMap<>();

        for (Map.Entry<String, Map<Integer, BedrockMappingData.JavaItemMapping>> itemEntry
                : BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().entrySet()) {
            final String bedrockIdentifier = itemEntry.getKey();
            final Integer bedrockId = this.items.get(bedrockIdentifier);
            if (!isVanillaItem(bedrockIdentifier, bedrockId)) {
                continue;
            }

            final Map<Integer, BedrockMappingData.JavaItemMapping> itemMappings = itemEntry.getValue();
            boolean potionItem = false;
            for (BedrockMappingData.JavaItemMapping mapping : itemMappings.values()) {
                if (mapping != null && mapping.overrideTag() != null
                        && LegacyItemTagRewriter.potionContents(mapping.overrideTag()) != null) {
                    potionItem = true;
                    break;
                }
            }
            if (!potionItem) {
                continue;
            }

            for (Map.Entry<Integer, BedrockMappingData.JavaItemMapping> mappingEntry : itemMappings.entrySet()) {
                final Integer meta = mappingEntry.getKey();
                if (meta == null && itemMappings.containsKey(0)) {
                    continue;
                }
                final int data = meta != null ? meta : 0;
                final BedrockMappingData.JavaItemMapping javaMapping = mappingEntry.getValue();
                if (data < 0 || data > Short.MAX_VALUE || javaMapping == null
                        || !javaMapping.identifier().startsWith("minecraft:")) {
                    continue;
                }

                final JavaToBedrockItemMapping candidate = new JavaToBedrockItemMapping(
                        bedrockIdentifier, bedrockId, (short) data, 0);
                addReverseCandidate(fallbackCandidates, javaMapping.id(), candidate);

                final PotionContents potionContents = javaMapping.overrideTag() != null
                        ? LegacyItemTagRewriter.potionContents(javaMapping.overrideTag()) : null;
                if (potionContents == null) {
                    continue;
                }
                final PotionContentsKey contentsKey = PotionContentsKey.of(potionContents);
                exactCandidates.computeIfAbsent(javaMapping.id(), ignored -> new HashMap<>())
                        .computeIfAbsent(contentsKey, ignored -> new HashSet<>()).add(candidate);
                if (potionContents.potion() != null) {
                    potionCandidates.computeIfAbsent(javaMapping.id(), ignored -> new HashMap<>())
                            .computeIfAbsent(potionContents.potion(), ignored -> new HashSet<>()).add(candidate);
                }
            }
        }

        final Set<Integer> javaIds = new HashSet<>(fallbackCandidates.keySet());
        javaIds.addAll(exactCandidates.keySet());
        javaIds.addAll(potionCandidates.keySet());
        final Int2ObjectMap<PotionItemMappings> mappings = new Int2ObjectOpenHashMap<>(javaIds.size());
        for (int javaId : javaIds) {
            final Map<PotionContentsKey, JavaToBedrockItemMapping> exactMappings = new HashMap<>();
            for (Map.Entry<PotionContentsKey, Set<JavaToBedrockItemMapping>> entry
                    : exactCandidates.getOrDefault(javaId, Map.of()).entrySet()) {
                final JavaToBedrockItemMapping mapping = this.selectPotionMapping(javaId, entry.getValue());
                if (mapping != null) {
                    exactMappings.put(entry.getKey(), mapping);
                }
            }

            final Int2ObjectMap<JavaToBedrockItemMapping> potionMappings = new Int2ObjectOpenHashMap<>();
            for (Map.Entry<Integer, Set<JavaToBedrockItemMapping>> entry
                    : potionCandidates.getOrDefault(javaId, Map.of()).entrySet()) {
                final JavaToBedrockItemMapping mapping = this.selectPotionMapping(javaId, entry.getValue());
                if (mapping != null) {
                    potionMappings.put(entry.getKey().intValue(), mapping);
                }
            }

            final JavaToBedrockItemMapping fallback = this.selectPotionMapping(
                    javaId, fallbackCandidates.getOrDefault(javaId, Set.of()));
            if (!exactMappings.isEmpty() || !potionMappings.isEmpty() || fallback != null) {
                mappings.put(javaId, new PotionItemMappings(Map.copyOf(exactMappings), potionMappings, fallback));
            }
        }
        return mappings;
    }

    private JavaToBedrockItemMapping selectPotionMapping(
            final int javaId,
            final Collection<JavaToBedrockItemMapping> candidates
    ) {
        if (candidates.isEmpty()) {
            return null;
        }
        final String javaIdentifier = BedrockProtocol.MAPPINGS.getJavaItems().inverse().get(javaId);
        if (javaIdentifier == null || !javaIdentifier.startsWith("minecraft:")) {
            return null;
        }

        final List<JavaToBedrockItemMapping> exactCandidates = candidates.stream()
                .filter(candidate -> candidate.bedrockIdentifier().equals(javaIdentifier)).toList();
        final Collection<JavaToBedrockItemMapping> selected = exactCandidates.isEmpty() ? candidates : exactCandidates;
        String bedrockIdentifier = null;
        JavaToBedrockItemMapping result = null;
        for (JavaToBedrockItemMapping candidate : selected) {
            if (bedrockIdentifier != null && !bedrockIdentifier.equals(candidate.bedrockIdentifier())) {
                return null;
            }
            bedrockIdentifier = candidate.bedrockIdentifier();
            if (result == null || candidate.data() < result.data()) {
                result = candidate;
            }
        }
        return result;
    }

    private static Int2ObjectMap<Enchant_Type> createJavaToBedrockEnchantments() {
        final Map<String, Set<Enchant_Type>> candidates = new HashMap<>();
        for (Map.Entry<Enchant_Type, String> entry : BedrockProtocol.MAPPINGS.getBedrockToJavaEnchantments().entrySet()) {
            candidates.computeIfAbsent(entry.getValue(), ignored -> EnumSet.noneOf(Enchant_Type.class)).add(entry.getKey());
        }

        final Int2ObjectMap<Enchant_Type> mappings = new Int2ObjectOpenHashMap<>();
        final CompoundTag enchantmentsRegistry = BedrockProtocol.MAPPINGS.getJavaRegistries()
                .getCompoundTag("minecraft:enchantment");
        if (enchantmentsRegistry == null) {
            return mappings;
        }
        int javaId = 0;
        for (String identifier : enchantmentsRegistry.keySet()) {
            final Set<Enchant_Type> enchantments = candidates.get(identifier);
            if (enchantments != null && enchantments.size() == 1) {
                mappings.put(javaId, enchantments.iterator().next());
            }
            javaId++;
        }
        return mappings;
    }

    private static boolean isMetaOnlyItem(final String identifier) {
        return BedrockProtocol.MAPPINGS.getBedrockToJavaMetaItems().containsKey(identifier)
                && !BedrockProtocol.MAPPINGS.getBedrockToJavaBlockItems().containsKey(identifier);
    }

    private static boolean isVanillaItem(final String bedrockIdentifier, final Integer bedrockId) {
        return bedrockIdentifier.startsWith("minecraft:") && bedrockId != null && bedrockId != 0 && bedrockId != -1;
    }

    private static boolean isSafelyReversible(final BedrockMappingData.JavaItemMapping mapping) {
        return mapping != null && mapping.identifier().startsWith("minecraft:")
                && mapping.name() == null && mapping.overrideTag() == null;
    }

    private static void addReverseCandidate(
            final Map<Integer, Set<JavaToBedrockItemMapping>> candidates,
            final int javaId,
            final JavaToBedrockItemMapping candidate
    ) {
        candidates.computeIfAbsent(javaId, ignored -> new HashSet<>()).add(candidate);
    }

    public BedrockItem bedrockItem(final Item javaItem) {
        try {
            return this.bedrockItem0(javaItem);
        } catch (final RuntimeException e) {
            try {
                if (ViaBedrock.getPlatform() != null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                            "Failed to rewrite Java item id=" + (javaItem != null ? javaItem.identifier() : -1), e);
                }
            } catch (final RuntimeException ignored) {
            }
            return BedrockItem.empty();
        }
    }

    private BedrockItem bedrockItem0(final Item javaItem) {
        if (javaItem == null || javaItem.isEmpty()) {
            return BedrockItem.empty();
        }

        StructuredDataContainer data = null;
        try {
            data = javaItem.dataContainer();
        } catch (final UnsupportedOperationException ignored) {
        }
        final CompoundTag customData = data != null ? data.get(StructuredDataKey.CUSTOM_DATA) : null;

        if (customData != null && customData.contains(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY)) {
            final BedrockItem shadowedItem = this.restoreBedrockItemShadow(
                    customData.get(CustomItemDataComponents.BEDROCK_ITEM_SHADOW_KEY), javaItem.amount());
            if (shadowedItem == null) {
                return BedrockItem.empty();
            }
            final String shadowedIdentifier = this.bedrockIdentifier(shadowedItem.identifier());
            shadowedItem.setTag(this.createBedrockTag(data, shadowedIdentifier, shadowedItem.tag()));
            this.overlayJavaItemMutations(shadowedItem, data, javaItem);
            return shadowedItem;
        }

        JavaToBedrockItemMapping mapping = null;
        boolean syncedCustomMapping = false;
        boolean identifierFallbackMapping = false;
        final CustomMappingSyncStorage customMappingSync = this.user().get(CustomMappingSyncStorage.class);
        final String syncedIdentifier = customMappingSync != null
                ? customMappingSync.access().customItemIdentifier(javaItem.identifier()) : null;
        if (syncedIdentifier != null) {
            mapping = this.customItemMapping(syncedIdentifier);
            if (mapping == null) {
                return BedrockItem.empty();
            }
            syncedCustomMapping = true;
        }

        if (mapping == null && customData != null && customData.contains(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY)) {
            if (!(customData.get(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY) instanceof StringTag identifierTag)) {
                return BedrockItem.empty();
            }
            mapping = this.customItemMapping(identifierTag.getValue());
            if (mapping == null) {
                return BedrockItem.empty();
            }
            identifierFallbackMapping = true;
        }

        final BiMap<String, Integer> javaItems = BedrockProtocol.MAPPINGS.getJavaItems();
        final String javaIdentifier = javaItems != null ? javaItems.inverse().get(javaItem.identifier()) : null;
        if (syncedCustomMapping && javaIdentifier != null) {
            return BedrockItem.empty();
        }
        if (identifierFallbackMapping && javaIdentifier != null && !"minecraft:paper".equals(javaIdentifier)) {
            return BedrockItem.empty();
        }
        if (mapping == null) {
            final PotionItemMappings potionMappings = this.javaToBedrockPotionItems.get(javaItem.identifier());
            if (potionMappings != null) {
                mapping = potionMappings.mapping(data != null
                        ? data.get(StructuredDataKey.POTION_CONTENTS1_21_2) : null);
            }
        }
        if (mapping == null) {
            mapping = this.javaToBedrockItems.get(javaItem.identifier());
        }
        if (mapping == null) {
            return BedrockItem.empty();
        }

        final int amount = Math.min(javaItem.amount(), 0xFF);
        final BedrockItem bedrockItem = new BedrockItem(mapping.bedrockId(), mapping.data(), (byte) amount);
        bedrockItem.setBlockRuntimeId(mapping.blockRuntimeId());
        if (data != null) {
            bedrockItem.setTag(this.createBedrockTag(data, mapping.bedrockIdentifier(), null));
            final String[] canPlace = this.bedrockBlockPredicates(data.get(JAVA_CAN_PLACE_ON));
            if (canPlace != null) {
                bedrockItem.setCanPlace(canPlace);
            }
            final String[] canBreak = this.bedrockBlockPredicates(data.get(JAVA_CAN_BREAK));
            if (canBreak != null) {
                bedrockItem.setCanBreak(canBreak);
            }
        }
        return bedrockItem;
    }

    private void overlayJavaItemMutations(final BedrockItem bedrockItem, final StructuredDataContainer data, final Item javaItem) {
        if (bedrockItem == null || data == null) {
            return;
        }
        final String[] canPlace = this.bedrockBlockPredicates(data.get(JAVA_CAN_PLACE_ON));
        if (canPlace != null) {
            bedrockItem.setCanPlace(canPlace);
        }
        final String[] canBreak = this.bedrockBlockPredicates(data.get(JAVA_CAN_BREAK));
        if (canBreak != null) {
            bedrockItem.setCanBreak(canBreak);
        }
        final PotionItemMappings potionMappings = this.javaToBedrockPotionItems.get(javaItem.identifier());
        if (potionMappings != null) {
            final JavaToBedrockItemMapping potionMapping = potionMappings.mapping(data.get(StructuredDataKey.POTION_CONTENTS1_21_2));
            if (potionMapping != null) {
                bedrockItem.setData(potionMapping.data());
            }
        }
    }

    private JavaToBedrockItemMapping customItemMapping(final String bedrockIdentifier) {
        final Integer bedrockId = this.items.get(bedrockIdentifier);
        if (bedrockId == null || bedrockId == 0 || bedrockId == -1) {
            return null;
        }
        return new JavaToBedrockItemMapping(
                bedrockIdentifier, bedrockId, (short) 0,
                BlockItemMappingLayout.fallbackBlockRuntimeId(this.blockItemValidBlockStates.get(bedrockId.intValue())));
    }

    private BedrockItem restoreBedrockItemShadow(final Tag shadowTag, final int javaAmount) {
        if (!(shadowTag instanceof CompoundTag shadow)
                || !(shadow.get("version") instanceof IntTag versionTag)
                || versionTag.asInt() != CustomItemDataComponents.BEDROCK_ITEM_SHADOW_VERSION
                || !(shadow.get("identifier") instanceof StringTag identifierTag)
                || !(shadow.get("data") instanceof IntTag dataTag)
                || !(shadow.get("block_runtime_id") instanceof IntTag blockRuntimeTag)
                || !(shadow.get("blocking_ticks") instanceof LongTag blockingTicksTag)) {
            return null;
        }
        final Integer bedrockId = this.items.get(identifierTag.getValue());
        final int itemData = dataTag.asInt();
        if (bedrockId == null || bedrockId == 0 || bedrockId == -1
                || itemData < 0 || itemData > Short.MAX_VALUE) {
            return null;
        }

        final String[] canPlace = shadowStrings(shadow, "can_place");
        final String[] canBreak = shadowStrings(shadow, "can_break");
        if (canPlace == null || canBreak == null) {
            return null;
        }
        final CompoundTag bedrockTag;
        if (shadow.contains("tag")) {
            if (!(shadow.get("tag") instanceof CompoundTag tag)) {
                return null;
            }
            bedrockTag = tag.copy();
        } else {
            bedrockTag = null;
        }
        return new BedrockItem(
                bedrockId,
                (short) itemData,
                (byte) Math.min(javaAmount, 0xFF),
                bedrockTag,
                canPlace,
                canBreak,
                blockingTicksTag.asLong(),
                blockRuntimeTag.asInt(),
                null
        );
    }

    private static String[] shadowStrings(final CompoundTag shadow, final String key) {
        final ListTag<StringTag> list = shadow.getListTag(key, StringTag.class);
        if (list == null) {
            return null;
        }
        final String[] values = new String[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = list.get(i).getValue();
        }
        return values;
    }

    private String[] bedrockBlockPredicates(final AdventureModePredicate adventureModePredicate) {
        if (adventureModePredicate == null || this.blockStateRewriter == null) {
            return null;
        }

        final BlockPredicate[] predicates = adventureModePredicate.predicates();
        if (predicates == null) {
            return null;
        }
        final BiMap<Integer, String> javaBlocks = BedrockProtocol.MAPPINGS.getJavaBlocks().inverse();
        final Set<String> bedrockIdentifiers = new LinkedHashSet<>();
        for (BlockPredicate predicate : predicates) {
            if (predicate == null || predicate.holderSet() == null) {
                return null;
            }
            if ((predicate.propertyMatchers() != null && predicate.propertyMatchers().length != 0) || predicate.tag() != null) {
                return null;
            }
            final var dataMatchers = predicate.dataMatchers();
            if (dataMatchers != null
                    && ((dataMatchers.exactPredicates() != null && dataMatchers.exactPredicates().length != 0)
                    || (dataMatchers.predicates() != null && dataMatchers.predicates().length != 0))) {
                return null;
            }

            if (predicate.holderSet().hasTagKey()) {
                final String tagKey = predicate.holderSet().tagKey();
                if (tagKey == null || tagKey.isEmpty()) {
                    return null;
                }
                final String identifier = tagKey.contains(":") ? tagKey : "minecraft:" + tagKey;
                if (this.blockStateRewriter.validBlockStates(identifier) == null) {
                    return null;
                }
                bedrockIdentifiers.add(identifier);
                continue;
            }
            if (!predicate.holderSet().hasIds()) {
                return null;
            }
            for (int javaBlockId : predicate.holderSet().ids()) {
                final String identifier = javaBlocks.get(javaBlockId);
                if (identifier == null || this.blockStateRewriter.validBlockStates(identifier) == null) {
                    return null;
                }
                bedrockIdentifiers.add(identifier);
            }
        }
        return bedrockIdentifiers.toArray(new String[0]);
    }

    private CompoundTag createBedrockTag(
            final StructuredDataContainer data,
            final String bedrockIdentifier,
            final CompoundTag shadowTag
    ) {
        final CompoundTag bedrockTag = shadowTag != null ? shadowTag.copy() : new CompoundTag();

        final Integer damage = data.get(StructuredDataKey.DAMAGE);
        if (damage != null) {
            if (damage > 0) {
                bedrockTag.putInt("Damage", damage);
            } else {
                bedrockTag.remove("Damage");
            }
        } else if (data.hasEmpty(StructuredDataKey.DAMAGE)) {
            bedrockTag.remove("Damage");
        }
        if (shadowTag == null && DYEABLE_LEATHER_ITEMS.contains(bedrockIdentifier)) {
            final DyedColor dyedColor = data.get(StructuredDataKey.DYED_COLOR1_21_5);
            if (dyedColor != null) {
                bedrockTag.putInt("customColor", dyedColor.rgb() & 0xFFFFFF);
            }
        }

        final Tag customName = data.get(StructuredDataKey.CUSTOM_NAME);
        final Tag[] javaLore = data.get(StructuredDataKey.LORE);
        final ListTag<StringTag> bedrockLore = new ListTag<>(StringTag.class);
        if (javaLore != null) {
            for (Tag lore : javaLore) {
                final String line = javaTextToBedrock(lore);
                if (line == null || isGeneratedFallbackLore(line, bedrockIdentifier)) {
                    continue;
                }
                bedrockLore.add(new StringTag(TextUtil.toSingleLine(line)));
            }
        }
        final boolean updateName = customName != null || data.hasEmpty(StructuredDataKey.CUSTOM_NAME);
        final boolean updateLore = data.hasEmpty(StructuredDataKey.LORE)
                || javaLore != null && (javaLore.length == 0 || !bedrockLore.isEmpty());
        if (updateName || updateLore) {
            CompoundTag display = bedrockTag.getCompoundTag("display");
            display = display != null ? display.copy() : new CompoundTag();
            if (customName != null) {
                final String name = javaTextToBedrock(customName);
                if (name != null) {
                    display.putString("Name", name);
                }
            } else if (updateName) {
                display.remove("Name");
            }

            if (updateLore) {
                if (bedrockLore.isEmpty()) {
                    display.remove("Lore");
                } else {
                    display.put("Lore", bedrockLore);
                }
            }
            if (display.isEmpty()) {
                bedrockTag.remove("display");
            } else {
                bedrockTag.put("display", display);
            }
        }

        if (shadowTag == null) {
            this.writeEnchantments(data, bedrockTag);
            writePotionContents(data, bedrockTag);
        }
        this.writeChargedProjectiles(data, bedrockTag);
        return bedrockTag.isEmpty() && shadowTag == null ? null : bedrockTag;
    }

    private void writeEnchantments(final StructuredDataContainer data, final CompoundTag bedrockTag) {
        final Enchantments enchantments = data.get(StructuredDataKey.ENCHANTMENTS1_21_5);
        if (enchantments == null) {
            return;
        }

        final ListTag<CompoundTag> bedrockEnchantments = new ListTag<>(CompoundTag.class);
        final int[] javaIds = enchantments.enchantments().keySet().toIntArray();
        Arrays.sort(javaIds);
        for (int javaId : javaIds) {
            final Enchant_Type bedrockEnchantment = this.javaToBedrockEnchantments.get(javaId);
            if (bedrockEnchantment == null) {
                continue;
            }
            final int level = Math.max(0, Math.min(enchantments.getLevel(javaId), 255));
            final CompoundTag enchantmentTag = new CompoundTag();
            enchantmentTag.putShort("id", (short) bedrockEnchantment.getValue());
            enchantmentTag.putShort("lvl", (short) level);
            bedrockEnchantments.add(enchantmentTag);
        }
        if (!bedrockEnchantments.isEmpty()) {
            bedrockTag.put("ench", bedrockEnchantments);
        }
    }

    private void writeChargedProjectiles(final StructuredDataContainer data, final CompoundTag bedrockTag) {
        final Item[] projectiles = data.get(JAVA_CHARGED_PROJECTILES);
        if (projectiles == null && !data.hasEmpty(JAVA_CHARGED_PROJECTILES)) {
            return;
        }
        if (projectiles == null || projectiles.length == 0 || projectiles[0] == null || projectiles[0].isEmpty()) {
            bedrockTag.remove("chargedItem");
            if (bedrockTag.get("Charged") != null) {
                bedrockTag.putBoolean("Charged", false);
            }
            return;
        }

        final CompoundTag chargedItem = this.motItemNbt(projectiles[0]);
        if (chargedItem == null) {
            return;
        }
        bedrockTag.put("chargedItem", chargedItem);
        bedrockTag.putBoolean("Charged", true);
    }

    private CompoundTag motItemNbt(final Item javaItem) {
        final BedrockItem bedrockItem = this.bedrockItem(javaItem);
        if (bedrockItem == null || bedrockItem.isEmpty()) {
            return null;
        }
        final CompoundTag itemTag = new CompoundTag();
        itemTag.putByte("Count", (byte) bedrockItem.amount());
        itemTag.putShort("Damage", bedrockItem.data());
        final String identifier = this.bedrockIdentifier(bedrockItem);
        final Integer legacyId = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockLegacyItems().get(identifier) : null;
        if (legacyId == null || legacyId == 255) {
            if (identifier == null) {
                return null;
            }
            itemTag.putString("Name", identifier);
        } else {
            itemTag.putShort("id", (short) (legacyId.intValue() & 0xFFFF));
        }
        if (bedrockItem.tag() != null) {
            itemTag.put("tag", bedrockItem.tag());
        }
        return itemTag;
    }

    private static void writePotionContents(final StructuredDataContainer data, final CompoundTag bedrockTag) {
        final PotionContents potionContents = data.get(StructuredDataKey.POTION_CONTENTS1_21_2);
        if (potionContents == null) {
            return;
        }

        if (potionContents.potion() != null) {
            final String potionIdentifier = Potions1_20_5.idToKey(potionContents.potion());
            if (potionIdentifier != null) {
                bedrockTag.putString("Potion", Key.namespaced(potionIdentifier));
            }
        } else if (potionContents.customColor() != null
                || (potionContents.customEffects() != null && potionContents.customEffects().length != 0)) {
            bedrockTag.putString("Potion", "");
        }
        if (potionContents.customColor() != null) {
            bedrockTag.putInt("CustomPotionColor", potionContents.customColor());
        }

        final ListTag<CompoundTag> customEffects = new ListTag<>(CompoundTag.class);
        if (potionContents.customEffects() != null) {
            for (PotionEffect effect : potionContents.customEffects()) {
                if (effect == null || effect.effectData() == null) {
                    continue;
                }
                final String effectIdentifier = PotionEffects1_20_5.idToKey(effect.effect());
                if (effectIdentifier == null) {
                    continue;
                }
                final CompoundTag effectTag = writePotionEffectData(effect.effectData());
                effectTag.putString("id", effectIdentifier);
                customEffects.add(effectTag);
            }
        }
        if (!customEffects.isEmpty()) {
            bedrockTag.put("custom_potion_effects", customEffects);
        }
    }

    private static CompoundTag writePotionEffectData(final PotionEffectData effectData) {
        final CompoundTag rootTag = new CompoundTag();
        CompoundTag currentTag = rootTag;
        PotionEffectData currentData = effectData;
        while (currentData != null) {
            currentTag.putInt("amplifier", currentData.amplifier());
            currentTag.putInt("duration", currentData.duration());
            currentTag.putBoolean("ambient", currentData.ambient());
            currentTag.putBoolean("show_particles", currentData.showParticles());
            currentTag.putBoolean("show_icon", currentData.showIcon());
            currentData = currentData.hiddenEffect();
            if (currentData != null) {
                final CompoundTag hiddenTag = new CompoundTag();
                currentTag.put("hidden_effect", hiddenTag);
                currentTag = hiddenTag;
            }
        }
        return rootTag;
    }

    private static String javaTextToBedrock(final Tag javaText) {
        try {
            return ProtocolConstants.JAVA_TEXT_COMPONENT_SERIALIZER.deserializeNbtTree(javaText).asLegacyFormatString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static boolean isGeneratedFallbackLore(final String lore, final String bedrockIdentifier) {
        final String plainLore = TextUtil.stripFormatting(lore);
        return plainLore.equals("[ViaBedrock] Custom item: " + bedrockIdentifier)
                || plainLore.equals("[ViaBedrock] Mapped item: " + bedrockIdentifier);
    }

    public BedrockItem[] bedrockItems(final Item[] javaItems) {
        final BedrockItem[] bedrockItems = new BedrockItem[javaItems.length];
        for (int i = 0; i < javaItems.length; i++) {
            bedrockItems[i] = this.bedrockItem(javaItems[i]);
        }
        return bedrockItems;
    }

    public BiMap<String, Integer> getItems() {
        return this.items;
    }

    public String bedrockIdentifier(final BedrockItem item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return this.bedrockIdentifier(item.identifier());
    }

    public String bedrockIdentifier(final int itemId) {
        final String identifier = this.items.inverse().get(itemId);
        if (identifier != null) {
            return identifier;
        }
        // BedrockItem.setIdentifier() stores id % 65536. MOT custom block items
        // are negative (255 - nukkitId); look up both signed and unsigned forms.
        if (itemId > 32767) {
            return this.items.inverse().get(itemId - 65536);
        }
        if (itemId < 0) {
            return this.items.inverse().get(itemId + 65536);
        }
        return null;
    }

    private String bedrockIdentifierFromNumericNbtId(final int numericId) {
        int signedId = numericId;
        if (signedId > 32767) {
            signedId -= 65536;
        }
        if (signedId == 255) {
            return null;
        }
        final String legacyIdentifier = BedrockProtocol.MAPPINGS.getBedrockLegacyItemIdentifier(signedId);
        if (legacyIdentifier != null) {
            return legacyIdentifier;
        }
        return this.bedrockIdentifier(signedId);
    }

    public ItemDefinitions.ItemUseDefinition itemUseDefinition(final BedrockItem item) {
        final String identifier = this.bedrockIdentifier(item);
        if (identifier == null) {
            return null;
        }
        final ItemDefinitions.ItemDefinition itemDefinition = this.user().get(ResourcePackStorage.class).getItems().get(identifier);
        return itemDefinition != null ? itemDefinition.itemUseDefinition() : null;
    }

    public Set<String> getComponentItems() {
        return this.componentItems;
    }

    public Type<BedrockItem> itemType() {
        return this.itemType;
    }

    public Type<BedrockItem> optionalItemType() {
        return this.optionalItemType;
    }

    public Type<BedrockItem[]> itemArrayType() {
        return this.itemArrayType;
    }

    public Type<BedrockItem> newItemType() {
        return this.newItemType;
    }

    public Type<BedrockItem> optionalNewItemType() {
        return this.optionalNewItemType;
    }

    public Type<BedrockItem[]> newItemArrayType() {
        return this.newItemArrayType;
    }

    private record PotionItemMappings(
            Map<PotionContentsKey, JavaToBedrockItemMapping> exactMappings,
            Int2ObjectMap<JavaToBedrockItemMapping> potionMappings,
            JavaToBedrockItemMapping fallback
    ) {
        JavaToBedrockItemMapping mapping(final PotionContents potionContents) {
            if (potionContents == null) {
                return this.fallback;
            }
            final JavaToBedrockItemMapping exactMapping = this.exactMappings.get(PotionContentsKey.of(potionContents));
            if (exactMapping != null) {
                return exactMapping;
            }
            if (potionContents.potion() != null) {
                final JavaToBedrockItemMapping potionMapping = this.potionMappings.get(potionContents.potion().intValue());
                if (potionMapping != null) {
                    return potionMapping;
                }
            }
            return this.fallback;
        }
    }

    private record PotionContentsKey(
            Integer potion,
            Integer customColor,
            List<PotionEffectKey> customEffects
    ) {
        static PotionContentsKey of(final PotionContents potionContents) {
            final List<PotionEffectKey> effects = new ArrayList<>();
            if (potionContents.customEffects() != null) {
                for (PotionEffect effect : potionContents.customEffects()) {
                    if (effect != null && effect.effectData() != null) {
                        effects.add(new PotionEffectKey(
                                effect.effect(), PotionEffectDataKey.of(effect.effectData())));
                    }
                }
            }
            return new PotionContentsKey(potionContents.potion(), potionContents.customColor(), List.copyOf(effects));
        }
    }

    private record PotionEffectKey(int effect, PotionEffectDataKey data) {
    }

    private record PotionEffectDataKey(List<PotionEffectDataValue> values) {
        static PotionEffectDataKey of(final PotionEffectData data) {
            final List<PotionEffectDataValue> values = new ArrayList<>();
            PotionEffectData current = data;
            while (current != null) {
                values.add(new PotionEffectDataValue(
                        current.amplifier(), current.duration(), current.ambient(),
                        current.showParticles(), current.showIcon()));
                current = current.hiddenEffect();
            }
            return new PotionEffectDataKey(List.copyOf(values));
        }
    }

    private record PotionEffectDataValue(
            int amplifier,
            int duration,
            boolean ambient,
            boolean showParticles,
            boolean showIcon
    ) {
    }

    private record JavaToBedrockItemMapping(
            String bedrockIdentifier,
            int bedrockId,
            short data,
            int blockRuntimeId
    ) {
    }

    public interface NbtRewriter {

        void toJava(final UserConnection user, final BedrockItem bedrockItem, final Item javaItem);

    }

}
