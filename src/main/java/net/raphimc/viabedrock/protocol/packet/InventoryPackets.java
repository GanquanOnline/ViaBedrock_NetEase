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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.*;
import net.lenni0451.mcstructs_bedrock.forms.serializer.FormSerializer;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.container.*;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.RegistryUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.inventory.AnvilSimulator;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.experimental.inventory.ItemStackRequestEncoder;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.data.generated.java.RegistryKeys;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class InventoryPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_OPEN, ClientboundPackets26_1.OPEN_SCREEN, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final byte containerId = wrapper.read(Types.BYTE); // 容器 id
            final byte rawType = wrapper.read(Types.BYTE); // 类型
            final ContainerType type = ContainerType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ContainerType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition position = wrapper.read(BedrockTypes.BLOCK_POSITION); // 位置
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 id
            ContainerOpenLayout.skipTrailer(wrapper);
            // PacketWrapper 会把未读取的基岩字节追加到 Java OPEN_SCREEN 上。
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            if (inventoryTracker.isAnyScreenOpen()) {
                if (type == ContainerType.INVENTORY) {
                    // MOT 在 Interact.OpenInventory 之后会回显 type=-1。如果当前
                    // 已打开一个箱子，回弹 CONTAINER_CLOSE -1 就会关闭
                    // 那个箱子（Player.java:4065-4076）。在另一个窗口仍为当前窗口时，
                    // 不要把它确认为玩家物品栏的打开。
                    wrapper.cancel();
                    return;
                }
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open container while another container is open");
                PacketFactory.sendBedrockContainerClose(wrapper.user(), (byte) -1, ContainerType.NONE);
                wrapper.cancel();
                return;
            }
            final boolean dummyWorldPosition = InventoryTracker.isDummyWorldPosition(position);
            final BedrockBlockEntity blockEntity = dummyWorldPosition ? null : chunkTracker.getBlockEntity(position);
            // MOT CONTAINER_OPEN 没有标题字符串。Java 1.21 的键并不是 "container.<bedrock_tag>"
            // （ender_chest -> container.enderchest，shulker_box -> container.shulkerBox）。
            // 哑 (0,0,0) 持有者不能继承世界原点方块的 tag/CustomName，
            // 否则只要出生点有末影箱，插件箱子就会变成 "Ender Chest"。
            final String bedrockBlockTag = dummyWorldPosition ? null : blockStateRewriter.tag(chunkTracker.getBlockState(position));
            final String javaTitleKey = JavaContainerTitles.key(bedrockBlockTag, type);
            TextComponent title = new TranslationComponent(javaTitleKey);
            if (blockEntity != null && blockEntity.tag().get("CustomName") instanceof StringTag customNameTag) {
                title = TextUtil.stringToTextComponent(TextUtil.toSingleLine(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(customNameTag.getValue())));
            }

            final Container container;
            int javaMenuId = BedrockProtocol.MAPPINGS.getBedrockToJavaContainers().getOrDefault(type, -1);
            switch (type) {
                case INVENTORY -> {
                    // Java 已经拥有玩家物品栏窗口。Nukkit 在 Interact.OpenInventory 之后仍会发送 CONTAINER_OPEN
                    // type=-1，以便 SAI 传输被接受，但把它当成真实界面会占用
                    // currentContainer，之后打开箱子/工作台就会被拒绝为
                    // "另一个容器已打开"。只记录这个基岩确认即可。
                    inventoryTracker.acknowledgeBedrockInventoryOpen(containerId, position);
                    wrapper.cancel();
                    return;
                }
                case CONTAINER -> {
                    if (entityUniqueId != -1L) {
                        // Nukkit 把运输船箱子 / 矿车箱子作为 type 0 发送，并带上实体唯一 id。
                        container = new GenericContainer(wrapper.user(), containerId, type, title, position, 27);
                    } else if (dummyWorldPosition) {
                        // MOT 的假 / 插件物品栏以及没有查看方块的末影箱
                        // 会在 (0,0,0) 发送 CONTAINER_OPEN。不要按世界原点
                        // 方块来分类：出生点的末影箱/潜影盒/箱子会让远处的插件 GUI
                        // 被当作世界方块支撑，InventoryTracker.tick() 就会因距离而关闭它。
                        container = new GenericContainer(wrapper.user(), containerId, type, title, position, 27);
                    } else {
                        final String blockTag = blockStateRewriter.tag(chunkTracker.getBlockState(position));
                        if (CustomBlockTags.ENDER_CHEST.equals(blockTag)) {
                            container = new EnderChestContainer(wrapper.user(), containerId, title, position);
                        } else if (CustomBlockTags.SHULKER_BOX.equals(blockTag)) {
                            container = new ShulkerBoxContainer(wrapper.user(), containerId, title, position);
                            javaMenuId = BedrockProtocol.MAPPINGS.getJavaShulkerBoxMenuId();
                        } else {
                            final boolean isDoubleChest = blockEntity != null && blockEntity.tag().get("pairx") instanceof IntTag && blockEntity.tag().get("pairz") instanceof IntTag;
                            if (isDoubleChest) {
                                container = new ChestContainer(wrapper.user(), containerId, title, position, 54);
                                javaMenuId = BedrockProtocol.MAPPINGS.getJavaDoubleChestMenuId();
                            } else {
                                container = new ChestContainer(wrapper.user(), containerId, title, position, 27);
                            }
                        }
                    }
                }
                case BREWING_STAND -> {
                    container = new BrewingStandContainer(wrapper.user(), containerId, title, position);
                }
                case ANVIL -> {
                    container = new AnvilContainer(wrapper.user(), containerId, title, position);
                }
                case WORKBENCH -> {
                    container = new CraftingTableContainer(wrapper.user(), containerId, title, position);
                }
                case FURNACE, BLAST_FURNACE, SMOKER -> {
                    container = new FurnaceContainer(wrapper.user(), containerId, type, title, position);
                }
                case HOPPER, MINECART_HOPPER -> {
                    container = entityUniqueId != -1L || type == ContainerType.MINECART_HOPPER
                            ? new GenericContainer(wrapper.user(), containerId, type, title, position, 5)
                            : new GenericContainer(wrapper.user(), containerId, type, title, position, 5, CustomBlockTags.HOPPER);
                }
                case DISPENSER -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 9, CustomBlockTags.DISPENSER);
                }
                case DROPPER -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 9, CustomBlockTags.DROPPER);
                }
                case CRAFTER -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 9, CustomBlockTags.CRAFTER);
                }
                case MINECART_CHEST, CHEST_BOAT -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 27);
                }
                case ENCHANTMENT -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 2, CustomBlockTags.ENCHANTING_TABLE);
                }
                case BEACON -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 1, CustomBlockTags.BEACON);
                }
                case LOOM -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 4);
                }
                case LECTERN -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 1, CustomBlockTags.LECTERN);
                }
                case GRINDSTONE -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 3);
                }
                case STONECUTTER -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 2);
                }
                case CARTOGRAPHY -> {
                    container = new GenericContainer(wrapper.user(), containerId, type, title, position, 3);
                }
                case SMITHING_TABLE -> {
                    container = new SmithingTableContainer(wrapper.user(), containerId, title, position);
                }
                case TRADE -> {
                    container = new TradeContainer(wrapper.user(), containerId, title);
                }
                case HORSE -> {
                    // MOT 的 InventoryType.HORSE（网络 12）是 HorseInventory。Java 1.21.11
                    // 需要 MOUNT_SCREEN_OPEN (containerId, columns, entityId)，而不是 OPEN_SCREEN。
                    // 参考：MOT ContainerInventory.onOpen 的 entityId + HorseInventory 大小 2+箱子。
                    if (entityUniqueId == -1L) {
                        wrapper.cancel();
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open HORSE container without entity unique id");
                        PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                        return;
                    }
                    final Entity horse = wrapper.user().get(EntityTracker.class).getEntityByUid(entityUniqueId);
                    if (horse == null) {
                        wrapper.cancel();
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open HORSE container for unknown entity " + entityUniqueId);
                        PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                        return;
                    }
                    container = new HorseContainer(wrapper.user(), containerId, title, position,
                            HorseContainer.sizeFor(horse), entityUniqueId, horse.javaId());
                }
                case NONE, CAULDRON, JUKEBOX, ARMOR, HAND, HUD, DECORATED_POT -> { // 基岩客户端无法打开这些容器
                    wrapper.cancel();
                    return;
                }
                default -> {
                    // throw new IllegalStateException("Unhandled ContainerType: " + type);
                    wrapper.cancel();
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open unimplemented container: " + type);
                    PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                    return;
                }
            }
            inventoryTracker.setCurrentContainer(container);

            // 使用 Java 窗口 id（javaContainerId），而不是原始的基岩 containerId：工作台的
            // javaContainerId() 会覆写为固定值，且所有服务端→客户端更新 / 客户端→服务端
            // 查找都以 javaContainerId 为键。这里发送原始 containerId 会使窗口 id 失步，
            // 导致工作台的 CONTAINER_CLICK/CLOSE 永远匹配不上（物品放不进去，容器也从不关闭）。
            if (container instanceof HorseContainer horseContainer) {
                wrapper.setPacketType(ClientboundPackets26_1.MOUNT_SCREEN_OPEN);
                wrapper.write(Types.VAR_INT, horseContainer.javaContainerId()); // 容器 id
                wrapper.write(Types.VAR_INT, horseContainer.javaColumns()); // 物品栏列数
                wrapper.write(Types.INT, horseContainer.javaEntityId()); // 坐骑实体 id
                return;
            }
            wrapper.write(Types.VAR_INT, container.javaContainerId()); // 容器 id（Java 窗口 id）
            wrapper.write(Types.VAR_INT, javaMenuId); // 类型
            wrapper.write(Types.TAG, TextUtil.textComponentToNbt(title)); // 标题
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_TRADE, null, wrapper -> {
            wrapper.cancel();
            handleUpdateTrade(wrapper);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_CLOSE, ClientboundPackets26_1.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.BYTE, Types.VAR_INT); // 容器 id
                handler(wrapper -> {
                    final ContainerType containerType = ContainerType.getByValue(wrapper.read(Types.BYTE)); // 类型
                    final boolean serverInitiated = wrapper.read(Types.BOOLEAN); // 服务端发起
                    final byte bedrockContainerId = (byte) (int) wrapper.get(Types.VAR_INT, 0);

                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final Container container;
                    if (serverInitiated) {
                        container = inventoryTracker.acceptServerClose(bedrockContainerId, containerType);
                    } else {
                        container = inventoryTracker.acceptClientCloseConfirmation(bedrockContainerId);
                        wrapper.cancel(); // Java 已经关闭了发起此确认的界面。
                    }
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }
                    // Java 窗口 id 必须与 CONTAINER_OPEN 发送的一致（javaContainerId），而不是原始的基岩 containerId。
                    wrapper.set(Types.VAR_INT, 0, container.javaContainerId());

                    clearClosedCraftingTable(inventoryTracker, container);
                    if (container.type() == ContainerType.ENCHANTMENT) {
                        final EnchantingSessionStorage enchantingSession = wrapper.user().get(EnchantingSessionStorage.class);
                        if (enchantingSession != null) {
                            enchantingSession.clear();
                        }
                    }
                    if (container instanceof AnvilContainer) {
                        final AnvilSessionStorage session = wrapper.user().get(AnvilSessionStorage.class);
                        if (session != null) {
                            session.clear();
                        }
                    }
                    if (container instanceof TradeContainer) {
                        final TradeSessionStorage session = wrapper.user().get(TradeSessionStorage.class);
                        if (session != null) {
                            session.clear();
                        }
                    }
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_CONTENT, ClientboundPackets26_1.CONTAINER_SET_CONTENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final InventoryContentLayout.DecodedInventoryContent decoded = InventoryContentLayout.read(wrapper, itemRewriter);
            final int containerId = decoded.containerId();
            final BedrockItem[] items = decoded.items();
            final FullContainerName containerName = decoded.containerName();
            final BedrockItem storageItem = decoded.storageItem();
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            Container container = inventoryTracker.getContainerClientbound(containerId, containerName, storageItem);
            if (container instanceof HorseContainer horse && items != null && items.length != horse.size()) {
                // CONTAINER_OPEN 之后 MOT 的 sendContents 携带真实的 2+chestSize 数组。
                // 调整镜像大小并重新打开，使 Java 列数与 CONTAINER_SET_CONTENT 匹配。
                final HorseContainer resized = horse.withSize(items.length);
                inventoryTracker.replaceCurrentContainer(resized);
                PacketFactory.sendJavaMountScreenOpen(wrapper.user(), resized);
                container = resized;
            }
            if (container != null && container.setItems(items)) {
                inventoryTracker.incrementAuthoritativeInventoryGeneration(container);
                PacketFactory.writeJavaContainerSetContent(wrapper, container);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_SLOT, ClientboundPackets26_1.CONTAINER_SET_SLOT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final InventorySlotLayout.DecodedInventorySlot decoded = InventorySlotLayout.read(wrapper, itemRewriter);
            final int containerId = decoded.containerId();
            final int slot = decoded.slot();
            final FullContainerName containerName = decoded.containerName();
            final BedrockItem storageItem = decoded.storageItem();
            final BedrockItem item = decoded.item();
            // PacketWrapper 会把任何未读取的基岩字节追加到 Java 数据包上。
            // 消耗掉剩余部分，避免布局不匹配时以多余数据把 1.21.11 踢下线。
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound(containerId, containerName, storageItem);
            if (container != null && container.setItem(slot, item)) {
                inventoryTracker.incrementAuthoritativeInventoryGeneration(container);
                if (container.type() == ContainerType.HUD && slot == 0) { // 光标物品
                    wrapper.setPacketType(ClientboundPackets26_1.SET_CURSOR_ITEM);
                } else {
                    wrapper.write(Types.VAR_INT, container.javaContainerId()); // 容器 id
                    wrapper.write(Types.VAR_INT, 0); // 修订版本
                    wrapper.write(Types.SHORT, (short) container.javaSlot(slot)); // 槽位
                }
                wrapper.write(VersionedTypes.V26_1.item, container.getJavaItem(slot)); // 物品
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_STACK_RESPONSE, null, wrapper -> {
            wrapper.cancel();
            // SAI 服务器会回显每次点击。消耗掉 860/975 的容器数组，以免
            // 下一个已映射的数据包被当成残留的 ITEM_STACK_RESPONSE 字节解析。
            // 网易 860 的错误条目没有容器，所以被拒绝的点击必须
            // 从点击前的快照回滚 Java 的乐观预测。
            final ItemStackResponseLayout.DecodedResponse decoded = ItemStackResponseLayout.decode(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            ClientAuthInventoryModule.handleItemStackResponse(wrapper.user(), decoded);
            ExperimentalFeatures.handleItemStackResponse(wrapper.user(), decoded);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_SET_DATA, ClientboundPackets26_1.CONTAINER_SET_DATA, wrapper -> {
            final int containerId = wrapper.read(Types.BYTE); // 容器 id
            final int property = wrapper.read(BedrockTypes.VAR_INT); // 属性
            final int value = wrapper.read(BedrockTypes.VAR_INT); // 值

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getCurrentContainer();
            // 同一时间只能打开一个界面，且服务端只在
            // 熔炉界面打开时发送 CONTAINER_SET_DATA。其他情况（例如酿造台）会被丢弃，与以往行为一致。
            if (!(container instanceof FurnaceContainer) || (byte) containerId != container.containerId()) {
                wrapper.cancel();
                return;
            }

            // 基岩（ContainerSetDataPacket）只发送分子：
            // 0 SMELT_PROGRESS      = cookTime     (0..burnInterval) -> Java cooking_time_spent（数据槽 2）
            // 1 REMAINING_FUEL_TIME = burnDuration (0..burnInterval) -> Java lit_time_remaining（数据槽 0）
            // Java 还需要对应的分母槽位，否则火焰比例错误、烹饪箭头
            // 会完全隐藏（cooking_total_time == 0 时 getBurnProgress 返回 0）。burnInterval 对普通熔炉是 200，
            // 对高炉 / 烟熏炉是 100（它们以双倍速度烹饪）。
            final int burnInterval = container.type() == ContainerType.FURNACE ? 200 : 100;
            final int javaWindowId = container.javaContainerId();

            final int numeratorSlot;
            final int denominatorSlot;
            switch (property) {
                case 0 -> { numeratorSlot = 2; denominatorSlot = 3; } // SMELT_PROGRESS
                case 1 -> { numeratorSlot = 0; denominatorSlot = 1; } // REMAINING_FUEL_TIME
                default -> { wrapper.cancel(); return; } // MAX_FUEL_TIME / STORED_XP / FUEL_AUX：Java GUI 不需要
            }

            // 合成分母（基岩从不发送它），让 GUI 能渲染出成比例的进度条。
            final PacketWrapper denominator = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_DATA, wrapper.user());
            denominator.write(Types.VAR_INT, javaWindowId); // 容器 id
            denominator.write(Types.SHORT, (short) denominatorSlot); // 属性
            denominator.write(Types.SHORT, (short) burnInterval); // 值
            denominator.send(BedrockProtocol.class);

            // 把这个数据包改写为分子更新。
            wrapper.write(Types.VAR_INT, javaWindowId); // 容器 id
            wrapper.write(Types.SHORT, (short) numeratorSlot); // 属性
            wrapper.write(Types.SHORT, (short) value); // 值
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ENCHANT_OPTIONS, null, wrapper -> {
            wrapper.cancel();
            final List<PlayerEnchantOptionsLayout.EnchantOption> options = PlayerEnchantOptionsLayout.readPacket(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getCurrentContainer();
            final EnchantingSessionStorage enchantingSession = wrapper.user().get(EnchantingSessionStorage.class);
            if (container == null || container.type() != ContainerType.ENCHANTMENT) {
                enchantingSession.clear();
                return;
            }
            enchantingSession.setOptions(options);
            sendJavaEnchantmentData(wrapper.user(), container, options, enchantingSession.seed());
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MODAL_FORM_REQUEST, ClientboundPackets26_1.SHOW_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // id
            final String data = wrapper.read(BedrockTypes.STRING); // 数据

            if (inventoryTracker.getCurrentContainer() != null || inventoryTracker.getCurrentForm() != null) {
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserBusy);
                wrapper.cancel();
                return;
            }

            final Form form;
            try {
                form = FormSerializer.deserialize(data);
            } catch (RuntimeException e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while deserializing modal form " + id, e);
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserClosed);
                wrapper.cancel();
                return;
            }
            final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
            form.setTranslator(resourcePackStorage.getTexts()::translate);
            try {
                wrapper.write(Types.TRUSTED_COMPOUND_TAG_HOLDER, BedrockFormDialogConverter.serialize(BedrockFormDialogConverter.convert(id, form, wrapper.user().getProtocolInfo().protocolVersion()))); // 对话框数据
            } catch (RuntimeException e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while converting modal form " + id + " (" + form.getClass().getSimpleName() + ")", e);
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserClosed);
                wrapper.cancel();
                return;
            }
            inventoryTracker.setCurrentForm(IntObjectPair.of(id, form));
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CLOSE_FORM, ClientboundPackets26_1.CLEAR_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() != null) {
                inventoryTracker.closeCurrentForm();
            } else if (inventoryTracker.getCurrentNpcDialogue() != null) {
                inventoryTracker.setCurrentNpcDialogue(null);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_HOTBAR, ClientboundPackets26_1.SET_HELD_SLOT, wrapper -> {
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 选中槽位
            final byte containerId = wrapper.read(Types.BYTE); // 容器 id
            final boolean shouldSelectSlot = wrapper.read(Types.BOOLEAN); // 是否应选中槽位
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (slot >= 0 && slot < 9 && containerId == inventoryContainer.containerId() && shouldSelectSlot) {
                inventoryContainer.setSelectedHotbarSlotLocally((byte) slot);
                wrapper.write(Types.VAR_INT, slot); // 槽位
            } else {
                wrapper.cancel();
                if (containerId != inventoryContainer.containerId()) { // 基岩客户端不再渲染快捷栏选中和手持物品
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set hotbar slot with wrong container id: " + containerId);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_REGISTRY_CLEANUP, null, wrapper -> {
            wrapper.cancel();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final FullContainerName[] removedContainers = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME_ARRAY); // 被移除的容器
            for (FullContainerName containerName : removedContainers) {
                inventoryTracker.removeDynamicContainer(containerName);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ARMOR_DAMAGE, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            if (!wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                wrapper.cancel();
                return;
            }
            final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 大小
            if (size <= 0) {
                wrapper.cancel();
                return;
            }
            final Container armorContainer = wrapper.user().get(InventoryTracker.class).getArmorContainer();

            wrapper.write(Types.VAR_INT, wrapper.user().get(EntityTracker.class).getClientPlayer().javaId()); // 实体 id
            for (int i = 0; i < size; i++) {
                final int rawArmorSlot = wrapper.read(BedrockTypes.VAR_INT); // 盔甲槽位
                final SharedTypes_Legacy_ArmorSlot armorSlot = SharedTypes_Legacy_ArmorSlot.getByValue(rawArmorSlot);
                if (armorSlot == null) { // 如果发送未知盔甲槽位，基岩客户端会忽略整个数据包
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SharedTypes_Legacy_ArmorSlot: " + rawArmorSlot);
                    wrapper.cancel();
                    return;
                }
                final short damage = wrapper.read(BedrockTypes.SHORT_LE); // 损伤值

                final BedrockItem item = armorSlot.getValue() < armorContainer.size() ? armorContainer.getItem(armorSlot.getValue()) : BedrockItem.empty();
                if (item.tag() == null) {
                    item.setTag(new CompoundTag());
                }
                item.tag().putInt("Damage", damage);

                final EquipmentSlot equipmentSlot = switch (armorSlot) {
                    case Head -> EquipmentSlot.HEAD;
                    case Torso -> EquipmentSlot.CHEST;
                    case Legs -> EquipmentSlot.LEGS;
                    case Feet -> EquipmentSlot.FEET;
                    case Body -> EquipmentSlot.BODY;
                };
                wrapper.write(Types.BYTE, (byte) (equipmentSlot.ordinal() | (i < (size - 1) ? Byte.MIN_VALUE : 0))); // 槽位
                wrapper.write(VersionedTypes.V26_1.item, wrapper.user().get(ItemRewriter.class).javaItem(item)); // 物品
            }
            PacketLeftoverLayout.discardUnreadInput(wrapper);
        });

        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_BUTTON_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // 容器 id
            final int buttonId = wrapper.read(Types.VAR_INT); // 按钮 id
            handleEnchantButtonClick(wrapper.user(), containerId, buttonId);
        });
        protocol.registerServerbound(ServerboundPackets26_1.RENAME_ITEM, ServerboundBedrockPackets.FILTER_TEXT, wrapper -> {
            final String name = wrapper.read(Types.STRING);
            wrapper.clearInputBuffer();
            if (!(wrapper.user().get(InventoryTracker.class).getCurrentContainer() instanceof AnvilContainer)) {
                wrapper.cancel();
                return;
            }
            final String sanitized = FilterTextLayout.sanitize(name);
            final AnvilSessionStorage session = wrapper.user().get(AnvilSessionStorage.class);
            if (session != null) {
                session.setRenameText(sanitized);
            }
            FilterTextLayout.write(wrapper, sanitized, false);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.FILTER_TEXT, null, wrapper -> {
            wrapper.cancel();
            final FilterTextLayout.Packet packet = FilterTextLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            AnvilSimulator.handleFilterEcho(wrapper.user(), packet);
        });
        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // 容器 id
            final int revision = wrapper.read(Types.VAR_INT); // 修订版本
            final short slot = wrapper.read(Types.SHORT); // 槽位
            final byte button = wrapper.read(Types.BYTE); // 按钮
            final ContainerInput action = ContainerInput.values()[wrapper.read(Types.VAR_INT)]; // 动作

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            final Container container = inventoryTracker.getContainerServerbound(containerId);
            if (container == null) {
                if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
                        // ClientAuthInventoryModule 已经接管了玩家物品栏的点击。在这里
                        // 继续向下处理会把每次从背包取出都变成 Interact.OpenInventory。
                        wrapper.cancel();
                        return;
                    }
                    if (inventoryTracker.isBedrockPlayerInventoryOpen()) {
                        wrapper.cancel();
                        return;
                    }
                    // 如果服务端不响应，基岩客户端可能发送多个 OpenInventory 请求。
                    PacketFactory.sendBedrockOpenInventory(wrapper.user());
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }

                wrapper.cancel();
                return;
            }
            if (!container.handleClick(revision, slot, button, action)) {
                if (container.type() != ContainerType.INVENTORY) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT, null, wrapper -> {
            // 网易 860 的 SAI 创造模式点击由 ClientAuthInventoryModule 接管。
            // 官方 975 仍会取消并重新同步；保留这里的这个分支。
            if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()
                    && ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                return;
            }
            wrapper.cancel();
            final short slot = wrapper.read(Types.SHORT); // 槽位
            final Item item = wrapper.read(VersionedTypes.V26_1.lengthPrefixedItem); // 物品

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
        });
        protocol.registerServerbound(ServerboundPackets26_1.CUSTOM_CLICK_ACTION, null, wrapper -> {
            wrapper.cancel();
            final String id = wrapper.read(Types.STRING); // id
            final CompoundTag payload = (CompoundTag) wrapper.read(Types.CUSTOM_CLICK_ACTION_TAG); // 载荷
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            if (inventoryTracker.getCurrentForm() != null) {
                final Form form = inventoryTracker.getCurrentForm().right();
                final int formId = inventoryTracker.getCurrentForm().leftInt();
                if (!id.equals("viabedrock:form/" + formId)) {
                    return;
                }

                inventoryTracker.setCurrentForm(null);
                final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper.user());
                if (payload.contains("exit") && payload.getBoolean("exit")) {
                    modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                    modalFormResponse.write(Types.BOOLEAN, false); // 是否有响应
                    modalFormResponse.write(Types.BOOLEAN, true); // 是否有取消原因
                    modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // 取消原因
                    modalFormResponse.sendToServer(BedrockProtocol.class);
                    return;
                }

                if (form instanceof ModalForm modalForm) {
                    modalForm.setClickedButton(payload.getInt("button_id"));
                } else if (form instanceof ActionForm actionForm) {
                    actionForm.setClickedButton(payload.getInt("button_id"));
                } else if (form instanceof CustomForm customForm) {
                    for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                        final String inputKey = String.valueOf(elementIndex);
                        if (!payload.contains(inputKey)) continue;
                        final FormElement element = customForm.getElements()[elementIndex];
                        if (element instanceof CheckboxFormElement checkbox) {
                            checkbox.setChecked(payload.getBoolean(inputKey));
                        } else if (element instanceof DropdownFormElement dropdown) {
                            dropdown.setSelected(Integer.parseInt(payload.getString(inputKey)));
                        } else if (element instanceof SliderFormElement slider) {
                            slider.setCurrent(payload.getFloat(inputKey));
                        } else if (element instanceof StepSliderFormElement stepSlider) {
                            stepSlider.setSelected(Integer.parseInt(payload.getString(inputKey)));
                        } else if (element instanceof TextFieldFormElement textField) {
                            textField.setValue(payload.getString(inputKey));
                        }
                    }
                } else {
                    throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
                }

                modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                modalFormResponse.write(Types.BOOLEAN, true); // 是否有响应
                modalFormResponse.write(BedrockTypes.STRING, form.serializeResponse() + '\n'); // 响应
                modalFormResponse.write(Types.BOOLEAN, false); // 是否有取消原因
                modalFormResponse.sendToServer(BedrockProtocol.class);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLOSE, ServerboundBedrockPackets.CONTAINER_CLOSE, wrapper -> {
            final int javaWindowId = wrapper.read(Types.VAR_INT);
            wrapper.clearInputBuffer();
            wrapper.write(Types.BYTE, (byte) javaWindowId); // 下面会被覆写
            wrapper.write(Types.BYTE, (byte) ContainerType.NONE.getValue());
            wrapper.write(Types.BOOLEAN, false);

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerServerbound(javaWindowId);
            if (container == null) {
                // Java 的玩家物品栏不是由服务端容器数据包打开的。基岩会在其光标
                // 事务完成后用容器 -1 关闭该 UI。
                if (javaWindowId == 0 && !inventoryTracker.isContainerOpen()) {
                    if (!ClientAuthInventoryModule.returnCursorBeforeClose(wrapper.user())) {
                        wrapper.cancel();
                        return;
                    }
                    wrapper.set(Types.BYTE, 0, (byte) -1);
                    inventoryTracker.clearBedrockPlayerInventoryOpen();
                    return;
                }
                wrapper.cancel();
                return;
            }

            // 基岩客户端在关闭界面前会用物品栏事务处理它的光标。
            // Java 只发送 CONTAINER_CLOSE，所以要补上这缺失的一步。
            if (!ClientAuthInventoryModule.returnCursorBeforeClose(wrapper.user())
                    || !inventoryTracker.beginClientClose(container)) {
                wrapper.cancel();
                return;
            }
            wrapper.set(Types.BYTE, 0, container.bedrockCloseContainerId());
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CARRIED_ITEM, ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper -> {
            final short slot = wrapper.read(Types.SHORT); // 槽位
            // Nukkit 的 MobEquipmentProcessor 总是调用 setUsingItem(false)，而静默的
            // 反向 F 交换会恢复当前选中的槽位。在物品使用或
            // 副手提升期间锁定它，以免使用状态和被提升的槽位被破坏。
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (clientPlayer != null && shouldBlockNetEaseHotbarChange(
                    ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                    clientPlayer.isUsingItem(),
                    clientPlayer.isOffhandPromoted())) {
                wrapper.cancel();
                wrapper.user().get(InventoryTracker.class).getInventoryContainer().sendSelectedHotbarSlotToClient();
                return;
            }
            wrapper.user().get(InventoryTracker.class).getInventoryContainer().setSelectedHotbarSlot((byte) slot, wrapper); // 槽位
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_BLOCK, ServerboundBedrockPackets.BLOCK_PICK_REQUEST, wrapper -> {
            wrapper.passthroughAndMap(Types.BLOCK_POSITION1_14, BedrockTypes.SIGNED_BLOCK_POSITION); // 有符号位置
            wrapper.passthrough(Types.BOOLEAN); // 包含数据
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // 空快捷栏槽位数量（原版客户端总是发送 9）
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_ENTITY, ServerboundBedrockPackets.ENTITY_PICK_REQUEST, wrapper -> {
            final int entityId = wrapper.read(Types.VAR_INT); // 实体 id
            final boolean includeData = wrapper.read(Types.BOOLEAN); // 包含数据

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByJid(entityId);
            if (entity == null) {
                final BlockPosition fakeBlockPosition = ExperimentalFeatures.resolveFakeBlockEntityPickPosition(wrapper.user(), entityId);
                if (fakeBlockPosition != null) {
                    wrapper.cancel();
                    final PacketWrapper pick = PacketWrapper.create(ServerboundBedrockPackets.BLOCK_PICK_REQUEST, wrapper.user());
                    pick.write(BedrockTypes.SIGNED_BLOCK_POSITION, fakeBlockPosition); // 有符号位置
                    pick.write(Types.BOOLEAN, includeData); // 包含数据
                    pick.write(Types.UNSIGNED_BYTE, (short) 9); // 空快捷栏槽位
                    pick.sendToServer(BedrockProtocol.class);
                    return;
                }
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.LONG_LE, entity.uniqueId()); // 实体唯一 id
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // 空快捷栏槽位数量（原版客户端总是发送 9）
            wrapper.write(Types.BOOLEAN, includeData); // 包含数据
        });
        protocol.registerServerbound(ServerboundPackets26_1.EDIT_BOOK, null, wrapper -> {
            wrapper.cancel();
            sendBookEditPackets(wrapper.user(),
                    wrapper.read(Types.VAR_INT),
                    readJavaBookPages(wrapper),
                    wrapper.read(Types.BOOLEAN) ? wrapper.read(Types.STRING) : null);
        });
    }

    private static List<String> readJavaBookPages(final PacketWrapper wrapper) {
        final int pageCount = wrapper.read(Types.VAR_INT);
        final List<String> pages = new ArrayList<>(Math.max(0, pageCount));
        for (int i = 0; i < pageCount; i++) {
            pages.add(wrapper.read(Types.STRING));
        }
        return pages;
    }

    private static void sendBookEditPackets(final UserConnection user, final int javaSlot, final List<String> javaPages, final String title) {
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        if (inventoryTracker == null) {
            return;
        }
        final InventoryContainer inventory = inventoryTracker.getInventoryContainer();
        final int bedrockSlot = javaInventorySlotToBedrock(javaSlot);
        if (bedrockSlot < 0 || bedrockSlot >= inventory.size()) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Ignoring EDIT_BOOK for out-of-range Java slot " + javaSlot);
            return;
        }
        final BedrockItem current = inventory.getItem(bedrockSlot);
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        if (itemRewriter == null || !BookEditLayout.WRITABLE_BOOK.equals(itemRewriter.bedrockIdentifier(current))) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Ignoring EDIT_BOOK for non-writable-book slot " + bedrockSlot);
            return;
        }
        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int protocol = ViaBedrock.getConfig().getNetEaseProtocolVersion();
        for (final byte[] payload : BookEditLayout.diffPages(bedrockSlot, BookEditLayout.pagesFromItem(current), javaPages, emulateNetEase, protocol)) {
            sendBookEdit(user, payload);
        }
        if (title != null) {
            final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
            final String author = clientPlayer != null ? clientPlayer.name() : "";
            final AuthData authData = user.get(AuthData.class);
            final String xuid = authData != null && authData.getXuid() != null ? authData.getXuid() : "";
            sendBookEdit(user, BookEditLayout.encodeSignBook(bedrockSlot, title, author, xuid, emulateNetEase, protocol));
        }
    }

    private static int javaInventorySlotToBedrock(final int javaSlot) {
        if (javaSlot >= 36 && javaSlot <= 44) {
            return javaSlot - 36;
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return javaSlot;
        }
        return -1;
    }

    private static void sendBookEdit(final UserConnection user, final byte[] payload) {
        final PacketWrapper packet = PacketWrapper.create(ServerboundBedrockPackets.BOOK_EDIT, user);
        packet.write(Types.REMAINING_BYTES, payload);
        packet.sendToServer(BedrockProtocol.class);
    }

    private static void sendJavaEnchantmentData(final UserConnection user, final Container container,
                                                final List<PlayerEnchantOptionsLayout.EnchantOption> options, final int seed) {
        final int javaWindowId = container.javaContainerId();
        for (int button = 0; button < 3; button++) {
            final PlayerEnchantOptionsLayout.EnchantOption option = button < options.size() ? options.get(button) : null;
            sendJavaContainerSetData(user, javaWindowId, button, option != null ? option.javaXpCost() : 0);
            sendJavaContainerSetData(user, javaWindowId, button + 4, option != null ? javaEnchantmentClue(user, option) : -1);
            sendJavaContainerSetData(user, javaWindowId, button + 7, option != null ? javaEnchantmentLevelClue(option) : -1);
        }
        sendJavaContainerSetData(user, javaWindowId, 3, seed);
    }

    private static int javaEnchantmentClue(final UserConnection user, final PlayerEnchantOptionsLayout.EnchantOption option) {
        final PlayerEnchantOptionsLayout.EnchantData clue = option.primaryClue();
        if (clue == null) {
            return -1;
        }
        final Enchant_Type bedrockType = Enchant_Type.getByValue(clue.type());
        if (bedrockType == null) {
            return -1;
        }
        final String javaIdentifier = BedrockProtocol.MAPPINGS.getBedrockToJavaEnchantments().get(bedrockType);
        if (javaIdentifier == null) {
            return -1;
        }
        final CompoundTag registry = user.get(GameSessionStorage.class).getJavaRegistries().getCompoundTag(RegistryKeys.ENCHANTMENT);
        if (registry == null) {
            return -1;
        }
        final CompoundTag entry = registry.getCompoundTag(javaIdentifier);
        if (entry == null) {
            return -1;
        }
        try {
            return RegistryUtil.getRegistryIndex(registry, entry);
        } catch (final IllegalArgumentException ignored) {
            return -1;
        }
    }

    private static int javaEnchantmentLevelClue(final PlayerEnchantOptionsLayout.EnchantOption option) {
        final PlayerEnchantOptionsLayout.EnchantData clue = option.primaryClue();
        return clue != null ? clue.level() : -1;
    }

    private static void sendJavaContainerSetData(final UserConnection user, final int windowId, final int property, final int value) {
        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_DATA, user);
        packet.write(Types.VAR_INT, windowId);
        packet.write(Types.SHORT, (short) property);
        packet.write(Types.SHORT, (short) value);
        packet.send(BedrockProtocol.class);
    }

    private static void handleEnchantButtonClick(final UserConnection user, final int containerId, final int buttonId) {
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        if (inventoryTracker.getPendingCloseContainer() != null) {
            return;
        }
        final Container container = inventoryTracker.getContainerServerbound(containerId);
        if (container == null || container.type() != ContainerType.ENCHANTMENT) {
            return;
        }
        final EnchantingSessionStorage enchantingSession = user.get(EnchantingSessionStorage.class);
        final PlayerEnchantOptionsLayout.EnchantOption option = enchantingSession.option(buttonId);
        if (option == null) {
            return;
        }
        if (!user.get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Ignoring enchant button click without SAI inventory");
            return;
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return;
        }
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final boolean creative = clientPlayer != null && clientPlayer.javaGameMode() == GameMode.CREATIVE;
        final int cost = option.javaXpCost();
        int reagentCount = 0;
        if (!creative) {
            if (experienceLevel(clientPlayer) < cost || experienceLevel(clientPlayer) < option.minLevel()) {
                return;
            }
            final BedrockItem reagent = container.getItem(1);
            if (reagent == null || reagent.isEmpty() || reagent.amount() < cost || !isLapisLazuli(user, reagent)) {
                return;
            }
            reagentCount = cost;
        }
        final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeEnchantApply(
                inventoryTracker, option.enchantNetId(), Math.max(1, input.amount() > 0 ? 1 : 0), reagentCount, creative);
        if (encoded.unsupported() || encoded.isEmpty()) {
            PacketFactory.sendJavaContainerSetContent(user, container);
            return;
        }
        sendEnchantItemStackRequest(user, encoded.payload());
        enchantingSession.clear();
        sendJavaEnchantmentData(user, container, List.of(), 0);
    }

    private static int experienceLevel(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null) {
            return 0;
        }
        final EntityAttribute level = clientPlayer.attributes().get("minecraft:player.level");
        return level != null ? (int) level.computeClampedValue() : 0;
    }

    private static boolean isLapisLazuli(final UserConnection user, final BedrockItem item) {
        return "minecraft:lapis_lazuli".equals(user.get(ItemRewriter.class).bedrockIdentifier(item));
    }

    private static void sendEnchantItemStackRequest(final UserConnection user, final byte[] payload) {
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(Types.REMAINING_BYTES, payload);
        request.sendToServer(BedrockProtocol.class);
    }

    private static void handleUpdateTrade(final PacketWrapper wrapper) {
        final byte windowId = wrapper.read(Types.BYTE);
        wrapper.read(Types.BYTE); // 窗口类型（MOT 总是 15 / TRADE）
        wrapper.read(BedrockTypes.VAR_INT); // 大小
        final int tradeTier = wrapper.read(BedrockTypes.VAR_INT);
        final long traderUniqueId = wrapper.read(BedrockTypes.VAR_LONG);
        wrapper.read(BedrockTypes.VAR_LONG); // 玩家唯一 id
        final String displayName = wrapper.read(BedrockTypes.STRING);
        wrapper.read(Types.BOOLEAN); // 新的交易 UI
        final boolean usingEconomyTrade = wrapper.read(Types.BOOLEAN);
        final Tag offersTag = wrapper.read(BedrockTypes.NETWORK_TAG);
        PacketLeftoverLayout.discardUnreadInput(wrapper);

        final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
        final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
        final TradeSessionStorage session = wrapper.user().get(TradeSessionStorage.class);
        final java.util.List<TradeOfferLayout.Offer> offers = TradeOfferLayout.parseOffers(offersTag);
        final int villagerExperience = villagerTradeExperience(wrapper.user(), traderUniqueId);
        if (session != null) {
            session.setOffers(offers, tradeTier, villagerExperience, usingEconomyTrade);
        }

        Container container = inventoryTracker.getCurrentContainer();
        if (!(container instanceof TradeContainer)) {
            if (inventoryTracker.isAnyScreenOpen()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open trade while another container is open");
                PacketFactory.sendBedrockContainerClose(wrapper.user(), TradeContainer.MOT_CLOSE_CONTAINER_ID, ContainerType.NONE);
                return;
            }
            final TextComponent title = displayName != null && !displayName.isEmpty()
                    ? TextUtil.stringToTextComponent(displayName)
                    : new TranslationComponent("entity.minecraft.villager");
            container = new TradeContainer(wrapper.user(), windowId, title);
            inventoryTracker.setCurrentContainer(container);
            final PacketWrapper openScreen = PacketWrapper.create(ClientboundPackets26_1.OPEN_SCREEN, wrapper.user());
            openScreen.write(Types.VAR_INT, container.javaContainerId());
            openScreen.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getBedrockToJavaContainers().getOrDefault(ContainerType.TRADE, -1));
            openScreen.write(Types.TAG, TextUtil.textComponentToNbt(title));
            openScreen.send(BedrockProtocol.class);
        }

        final PacketWrapper merchantOffers = PacketWrapper.create(ClientboundPackets26_1.MERCHANT_OFFERS, wrapper.user());
        TradeOfferLayout.writeJavaMerchantOffers(
                merchantOffers, container.javaContainerId(), offers, itemRewriter,
                tradeTier, session != null ? session.villagerExperience() : villagerExperience, true, usingEconomyTrade);
        merchantOffers.send(BedrockProtocol.class);
    }

    private static int villagerTradeExperience(final UserConnection user, final long traderUniqueId) {
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker == null) {
            return 0;
        }
        final Entity trader = entityTracker.getEntityByUid(traderUniqueId);
        if (trader == null) {
            return 0;
        }
        final com.viaversion.viaversion.api.minecraft.entitydata.EntityData data = trader.entityData().get(ActorDataIDs.TRADE_EXPERIENCE);
        if (data == null || data.value() == null) {
            return 0;
        }
        final Object value = data.value();
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }

    static boolean shouldBlockNetEaseHotbarChange(final boolean emulateNetEaseClient,
                                                     final boolean usingItem,
                                                     final boolean offhandPromoted) {
        return emulateNetEaseClient && (usingItem || offhandPromoted);
    }

    private static void sendModalFormCancel(final UserConnection user, final int formId, final ModalFormCancelReason reason) {
        final PacketWrapper response = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, user);
        response.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
        response.write(Types.BOOLEAN, false); // 是否有响应
        response.write(Types.BOOLEAN, true); // 是否有取消原因
        response.write(Types.BYTE, (byte) reason.getValue()); // 取消原因
        response.sendToServer(BedrockProtocol.class);
    }

    private static void clearClosedCraftingTable(final InventoryTracker inventoryTracker, final Container container) {
        if (!(container instanceof CraftingTableContainer)) {
            return;
        }
        final Container hudContainer = inventoryTracker.getHudContainer();
        for (int slot = 32; slot <= 40; slot++) {
            hudContainer.setItemSilent(slot, BedrockItem.empty());
        }
        hudContainer.setItemSilent(50, BedrockItem.empty());
    }

}
