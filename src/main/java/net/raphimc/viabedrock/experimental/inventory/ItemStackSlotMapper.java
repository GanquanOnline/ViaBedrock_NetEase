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

import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.BrewingStandContainer;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.CraftingTableContainer;
import net.raphimc.viabedrock.api.model.container.EnderChestContainer;
import net.raphimc.viabedrock.api.model.container.FurnaceContainer;
import net.raphimc.viabedrock.api.model.container.GenericContainer;
import net.raphimc.viabedrock.api.model.container.HorseContainer;
import net.raphimc.viabedrock.api.model.container.ShulkerBoxContainer;
import net.raphimc.viabedrock.api.model.container.SmithingTableContainer;
import net.raphimc.viabedrock.api.model.container.TradeContainer;
import net.raphimc.viabedrock.experimental.inventory.SlotMapper.BedrockSlotRef;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.packet.ItemStackRequestLayout;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * 把基岩的容器 id/槽位对映射到 Nukkit 的 ItemStackRequest 槽位信息上。
 * 网络槽位编号遵循 {@code NetworkMapping.toInternalSlot}：玩家物品栏保持 0-35，盔甲保持 0-3，光标是 HUD 0，
 * 而假 UI 菜单（铁砧、附魔、砂轮……）仍发送 PlayerUI 槽位，而不是 Java 菜单索引。
 */
public final class ItemStackSlotMapper {

    private ItemStackSlotMapper() {
    }

    public static ItemStackRequestLayout.SlotInfo fromRef(final BedrockSlotRef ref) {
        if (ref == null) {
            return null;
        }
        return fromContainer(ref.container(), ref.containerId(), ref.slot());
    }

    public static ItemStackRequestLayout.SlotInfo fromContainer(final Container container, final int containerId, final int slot) {
        if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
            return playerInventory(slot);
        }
        if (containerId == ContainerID.CONTAINER_ID_ARMOR.getValue()) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.ArmorContainer, slot, 0);
        }
        if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.OffhandContainer, 0, 0);
        }
        if (containerId == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue()) {
            return hud(slot);
        }
        if (container == null) {
            return null;
        }
        return fromOpenContainer(container, slot);
    }

    public static ItemStackRequestLayout.SlotInfo playerInventory(final int bedrockSlot) {
        if (bedrockSlot >= 0 && bedrockSlot <= 8) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.HotbarContainer, bedrockSlot, 0);
        }
        if (bedrockSlot >= 9 && bedrockSlot <= 35) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.InventoryContainer, bedrockSlot, 0);
        }
        return null;
    }

    public static ItemStackRequestLayout.SlotInfo hud(final int hudSlot) {
        if (hudSlot == 0) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CursorContainer, 0, 0);
        }
        if (hudSlot == 50) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CreatedOutputContainer, 50, 0);
        }
        if (hudSlot >= 28 && hudSlot <= 40) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CraftingInputContainer, hudSlot, 0);
        }
        return null;
    }

    private static ContainerEnumName chestSlotType(final ChestContainer container) {
        // ChestContainer is also used for barrels. Nukkit only accepts BARREL for BarrelInventory;
        // ordinary chests/ender chests stay LEVEL_ENTITY.
        if (container.isValidBlockTag(net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags.BARREL)
                && !container.isValidBlockTag(net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags.CHEST)
                && !container.isValidBlockTag(net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags.TRAPPED_CHEST)) {
            return ContainerEnumName.BarrelContainer;
        }
        return ContainerEnumName.LevelEntityContainer;
    }

    public static ItemStackRequestLayout.SlotInfo fromOpenContainer(final Container container, final int slot) {
        if (container instanceof CraftingTableContainer) {
            if (slot == 0) {
                return hud(50);
            }
            if (slot >= 1 && slot <= 9) {
                return hud(31 + slot);
            }
            return null;
        }
        if (container instanceof AnvilContainer) {
            return switch (slot) {
                case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.AnvilInputContainer, 1, 0);
                case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.AnvilMaterialContainer, 2, 0);
                case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.AnvilResultPreviewContainer, 3, 0);
                default -> null;
            };
        }
        if (container instanceof TradeContainer) {
            return switch (slot) {
                case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.Trade2Ingredient1Container, 0, 0);
                case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.Trade2Ingredient2Container, 1, 0);
                case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.Trade2ResultPreviewContainer, 49, 0);
                default -> null;
            };
        }
        if (container instanceof FurnaceContainer) {
            final ContainerEnumName name = switch (container.type()) {
                case BLAST_FURNACE -> switch (slot) {
                    case 0 -> ContainerEnumName.BlastFurnaceIngredientContainer;
                    case 1 -> ContainerEnumName.FurnaceFuelContainer;
                    case 2 -> ContainerEnumName.FurnaceResultContainer;
                    default -> null;
                };
                case SMOKER -> switch (slot) {
                    case 0 -> ContainerEnumName.SmokerIngredientContainer;
                    case 1 -> ContainerEnumName.FurnaceFuelContainer;
                    case 2 -> ContainerEnumName.FurnaceResultContainer;
                    default -> null;
                };
                default -> switch (slot) {
                    case 0 -> ContainerEnumName.FurnaceIngredientContainer;
                    case 1 -> ContainerEnumName.FurnaceFuelContainer;
                    case 2 -> ContainerEnumName.FurnaceResultContainer;
                    default -> null;
                };
            };
            return name == null ? null : new ItemStackRequestLayout.SlotInfo(name, slot, 0);
        }
        if (container instanceof BrewingStandContainer) {
            return switch (slot) {
                case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.BrewingStandInputContainer, 0, 0);
                case 1, 2, 3 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.BrewingStandResultContainer, slot, 0);
                case 4 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.BrewingStandFuelContainer, 4, 0);
                default -> null;
            };
        }
        if (container instanceof SmithingTableContainer) {
            return switch (slot) {
                case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableInputContainer, 51, 0);
                case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableMaterialContainer, 52, 0);
                case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableTemplateContainer, 53, 0);
                case 3 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableResultPreviewContainer, 54, 0);
                default -> null;
            };
        }
        if (container instanceof ShulkerBoxContainer) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.ShulkerBoxContainer, slot, 0);
        }
        if (container instanceof ChestContainer) {
            return new ItemStackRequestLayout.SlotInfo(
                    chestSlotType((ChestContainer) container), slot, 0);
        }
        if (container instanceof EnderChestContainer) {
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LevelEntityContainer, slot, 0);
        }
        if (container instanceof HorseContainer) {
            // MOT NetworkMapping.getInventory：HORSE_EQUIP → HorseInventory，LEVEL_ENTITY → topWindow（此菜单打开时为 HorseInventory）。
            // 在请求路径上货物栏不是 HOTBAR_AND_INVENTORY：该类型解析为 player.getInventory()，会抢走马的货物栏。
            // MOT 的响应仍把货物栏标记为 HOTBAR_AND_INVENTORY；见 resolveResponseSlot。参考：NetworkMapping.getSlotType / getInventory。
            if (slot <= HorseContainer.SLOT_ARMOR) {
                return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.HorseEquipContainer, slot, 0);
            }
            return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LevelEntityContainer, slot, 0);
        }
        if (container instanceof GenericContainer) {
            return switch (container.type()) {
                case HOPPER, MINECART_HOPPER, DISPENSER, DROPPER, MINECART_CHEST, CHEST_BOAT, CONTAINER ->
                        new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LevelEntityContainer, slot, 0);
                case CRAFTER -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CrafterLevelEntityContainer, slot, 0);
                case ENCHANTMENT -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.EnchantingInputContainer, 14, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.EnchantingMaterialContainer, 15, 0);
                    default -> null;
                };
                case BEACON -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.BeaconPaymentContainer, 0, 0);
                case LOOM -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LoomInputContainer, 9, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LoomDyeContainer, 10, 0);
                    case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LoomMaterialContainer, 11, 0);
                    case 3 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LoomResultPreviewContainer, 12, 0);
                    default -> null;
                };
                case GRINDSTONE -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.GrindstoneInputContainer, 16, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.GrindstoneAdditionalContainer, 17, 0);
                    case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.GrindstoneResultPreviewContainer, 18, 0);
                    default -> null;
                };
                case STONECUTTER -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.StonecutterInputContainer, 3, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.StonecutterResultPreviewContainer, 4, 0);
                    default -> null;
                };
                case CARTOGRAPHY -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CartographyInputContainer, 12, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CartographyAdditionalContainer, 13, 0);
                    case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.CartographyResultPreviewContainer, 14, 0);
                    default -> null;
                };
                case SMITHING_TABLE -> switch (slot) {
                    case 0 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableInputContainer, 51, 0);
                    case 1 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableMaterialContainer, 52, 0);
                    case 2 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableTemplateContainer, 53, 0);
                    case 3 -> new ItemStackRequestLayout.SlotInfo(ContainerEnumName.SmithingTableResultPreviewContainer, 54, 0);
                    default -> null;
                };
                default -> null;
            };
        }
        return new ItemStackRequestLayout.SlotInfo(ContainerEnumName.LevelEntityContainer, slot, 0);
    }

    /**
     * {@link #fromOpenContainer} / {@link #playerInventory} / {@link #hud} 的逆映射。
     * MOT ITEM_STACK_RESPONSE 的槽位使用与 ItemStackRequest 相同的网络编号。
     * 参考：MOT NetworkMapping.toInternalSlot 与 TransferItemActionProcessor.buildContainer。
     */
    public static BedrockSlotRef resolveResponseSlot(final InventoryTracker tracker, final FullContainerName containerName, final int networkSlot) {
        if (tracker == null || containerName == null || containerName.name() == null) {
            return null;
        }
        return resolveResponseSlot(tracker, containerName.name(), networkSlot, containerName.dynamicId());
    }

    public static BedrockSlotRef resolveResponseSlot(final InventoryTracker tracker, final ContainerEnumName name, final int networkSlot, final Integer dynamicId) {
        if (tracker == null || name == null) {
            return null;
        }
        return switch (name) {
            case HotbarContainer, InventoryContainer -> {
                if (networkSlot < 0 || networkSlot > 35) {
                    yield null;
                }
                yield new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), networkSlot, tracker.getInventoryContainer());
            }
            case CombinedHotbarAndInventoryContainer -> {
                // MOT 的 getSlotType(HorseInventory) 把货物栏（槽位 >= 2）标记为 HOTBAR_AND_INVENTORY。玩家物品栏的响应使用 HOTBAR / INVENTORY。
                if (tracker.getCurrentContainer() instanceof HorseContainer horse
                        && networkSlot >= HorseContainer.SLOT_CHEST_BASE && networkSlot < horse.size()) {
                    yield new BedrockSlotRef(horse.containerId(), networkSlot, horse);
                }
                if (networkSlot < 0 || networkSlot > 35) {
                    yield null;
                }
                yield new BedrockSlotRef(ContainerID.CONTAINER_ID_INVENTORY.getValue(), networkSlot, tracker.getInventoryContainer());
            }
            case HorseEquipContainer -> {
                if (!(tracker.getCurrentContainer() instanceof HorseContainer horse)
                        || networkSlot < 0 || networkSlot > HorseContainer.SLOT_ARMOR) {
                    yield null;
                }
                yield new BedrockSlotRef(horse.containerId(), networkSlot, horse);
            }
            case ArmorContainer -> {
                if (networkSlot < 0 || networkSlot > 3) {
                    yield null;
                }
                yield new BedrockSlotRef(ContainerID.CONTAINER_ID_ARMOR.getValue(), networkSlot, tracker.getArmorContainer());
            }
            case OffhandContainer -> new BedrockSlotRef(ContainerID.CONTAINER_ID_OFFHAND.getValue(), 0, tracker.getOffhandContainer());
            case CursorContainer -> new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 0, tracker.getHudContainer());
            case CreatedOutputContainer, CraftingOutputPreviewContainer -> new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), 50, tracker.getHudContainer());
            case CraftingInputContainer -> {
                if (networkSlot < 28 || networkSlot > 40) {
                    yield null;
                }
                yield new BedrockSlotRef(ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), networkSlot, tracker.getHudContainer());
            }
            case DynamicContainer -> {
                final var dynamic = tracker.getDynamicContainer(new FullContainerName(name, dynamicId));
                if (dynamic == null) {
                    yield null;
                }
                yield new BedrockSlotRef(dynamic.containerId(), networkSlot, dynamic);
            }
            default -> resolveOpenContainerSlot(tracker.getCurrentContainer(), name, networkSlot);
        };
    }

    static BedrockSlotRef resolveOpenContainerSlot(final Container container, final ContainerEnumName name, final int networkSlot) {
        if (container == null || name == null) {
            return null;
        }
        final int size = container.size();
        for (int slot = 0; slot < size; slot++) {
            final ItemStackRequestLayout.SlotInfo info = fromOpenContainer(container, slot);
            if (info != null && info.container() == name && info.slot() == networkSlot) {
                return new BedrockSlotRef(container.containerId(), slot, container);
            }
        }
        if (isLevelEntityLike(name) && networkSlot >= 0 && networkSlot < size) {
            return new BedrockSlotRef(container.containerId(), networkSlot, container);
        }
        return null;
    }

    private static boolean isLevelEntityLike(final ContainerEnumName name) {
        return name == ContainerEnumName.LevelEntityContainer
                || name == ContainerEnumName.ShulkerBoxContainer
                || name == ContainerEnumName.BarrelContainer
                || name == ContainerEnumName.CrafterLevelEntityContainer;
    }
}
