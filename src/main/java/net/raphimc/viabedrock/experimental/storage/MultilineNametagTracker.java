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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.util.*;

/**
 * 跟踪并管理用于基岩版多行名称标签的虚拟实体。
 * <p>
 * 基岩版原生支持多行名称标签（通过 \n），但 Java 版的
 * CUSTOM_NAME 会忽略换行，只渲染成单行。本跟踪器采用两种策略：
 * <ul>
 * <li><b>玩家实体</b>：隐形盔甲架（每多一行一个），并以玩家
 * 自己的队伍名称标签作为最下面一行。每个盔甲架使用不同的
 * {@code minecraft:scale} 属性，把它的名称标签定位到正确的 Y 高度。</li>
 * <li><b>非玩家实体</b>：一个支持多行文本的 TEXT_DISPLAY 实体。</li>
 * </ul>
 * <p>
 * 两种策略都用 SET_PASSENGERS 骑乘宿主实体，因此客户端会自动
 * 让虚拟实体与宿主位置保持同步（无需手动同步位置）。
 * <p>
 * 与上游代码完全解耦——使用 ProtocolUtil.appendClientbound 挂接
 * 到现有的数据包处理器上，而不修改它们。
 * <p>
 * 重要：调用 resetReader() 之后，wrapper 中装的是 Java 格式数据（由
 * 上游处理器通过 write() 写入），而不是原始的基岩数据（已被 read() 消费并丢失）。
 * 所有 append 处理器都必须读取 Java 类型（例如 Types.VAR_INT），而不是基岩类型。
 */
public class MultilineNametagTracker extends StoredObject {

    private static final float LINE_HEIGHT = 0.28f;
    private static final float ARMOR_STAND_BASE_HEIGHT = 1.975f;
    private static final float PASSENGER_OFFSET = 0.0625f;
    private static final double SNEAKING_NAMETAG_DISTANCE_SQUARED = 32D * 32D;
    private static final String PASSENGER_SOURCE = "multiline-nametag";

    private final Map<Long, NametagDisplayInfo> displays = new HashMap<>();
    // 反向映射：宿主实体的 Java ID → 实体 uniqueId
    // 需要它是因为等到我们的 REMOVE_ENTITY 处理器运行时，
    // 实体已经从 EntityTracker 中移除了。
    private final Map<Integer, Long> hostJavaIdToUniqueId = new HashMap<>();

    public MultilineNametagTracker(final UserConnection user) {
        super(user);
    }

    // ---- 数据包处理器注册 ----

    public static void registerHandlers(final BedrockProtocol protocol) {
        // 1. ADD_ENTITY —— 在实体生成时检测多行名称标签
        // 上游使用 send()+cancel() 模式。resetReader() 之后，packetValues 中是
        // Java 的 ADD_ENTITY 格式：VarInt(javaId)、UUID、VarInt(typeId)、...
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_ENTITY, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                }
            } catch (final Exception ignored) {
            }
        });

        // 2. ADD_PLAYER —— 在玩家生成时检测多行名称标签
        // 上游使用 send()+cancel() 模式。resetReader() 之后，packetValues 中是
        // Java 的 ADD_ENTITY 格式：VarInt(javaId)、UUID、VarInt(typeId)、...
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_PLAYER, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                }
            } catch (final Exception ignored) {
            }
        });

        // 3. SET_ENTITY_DATA —— 检测名称标签变化
        // 上游写入：VarInt(javaId)、EntityDataList(javaEntityData)
        // 在 handleEntityDataUpdate 之后，我们从 wrapper 中过滤掉 CUSTOM_NAME/CUSTOM_NAME_VISIBLE，
        // 以免上游的实体数据重新启用宿主的名称标签。
        // 这是必要的，因为 append 处理器的纠正数据包（sendClearHostNametag）
        // 在 wrapper 的数据包之前发送，wrapper 会覆盖掉纠正结果。
        // 通过就地修改实体数据列表（与 wrapper 的 packetValues 中是同一个对象引用），
        // 我们确保到达客户端的是过滤后的数据。
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.SET_ENTITY_DATA, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                    // 如果 TextDisplay 处于活动状态，则从 wrapper 的实体数据中
                    // 过滤掉 CUSTOM_NAME/CUSTOM_NAME_VISIBLE。必须在 handleEntityDataUpdate 之后
                    // 执行，因为该调用可能会为新的多行名称创建新的显示实体。
                    tracker.filterHostNametagFromPacket(entity, wrapper);
                }
            } catch (final Exception ignored) {
            }
        });

        // 4. REMOVE_ENTITY —— 清理虚拟实体
        // 上游写入 REMOVE_ENTITIES：VarIntArrayPrimitive(int[]{javaId})
        // 此时实体已经从 EntityTracker 中移除，
        // 所以我们用 hostJavaIdToUniqueId 做反向查找。
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.REMOVE_ENTITY, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            try {
                wrapper.resetReader();
                final int[] javaEntityIds = wrapper.passthrough(Types.VAR_INT_ARRAY_PRIMITIVE);
                for (final int javaId : javaEntityIds) {
                    final Long entityUid = tracker.hostJavaIdToUniqueId.get(javaId);
                    if (entityUid != null) {
                        tracker.handleEntityRemoved(entityUid);
                    }
                }
            } catch (final Exception ignored) {
            }
        });

        // 5. CHANGE_DIMENSION —— 清空全部状态
        // 注意：这里不要检查 isCancelled()——上游使用 send()+cancel() 模式，
        // 所以维度切换确实发生了。Java 客户端会清空所有实体，因此我们必须清空跟踪状态。
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.CHANGE_DIMENSION, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker != null) {
                tracker.clearAll();
            }
        });
    }

    // ---- 核心逻辑 ----

    /**
     * 处理实体数据更新——检查多行名称标签，并创建/更新/移除显示实体。
     * 玩家实体使用盔甲架策略，非玩家实体使用 TEXT_DISPLAY 策略。
     */
    public void handleEntityDataUpdate(final Entity entity) {
        // 永远不要向客户端玩家本人显示名称标签
        if (entity instanceof ClientPlayerEntity) return;

        String name = getEntityName(entity);
        if (name != null) {
            // 去掉首尾的空行（包括只有格式符的行），使悬浮
            // 显示实体没有空白的最上一行/最下一行。中间的行保留，用于真正的多行名称。
            name = TextUtil.trimBlankLines(name);
            if (name.isEmpty()) name = null;
        }
        final boolean isMultiline = isMultilineName(name);
        final boolean alwaysShow = isNametagAlwaysShown(entity);
        final boolean isPlayer = entity instanceof PlayerEntity;
        final NametagDisplayInfo existing = displays.get(entity.uniqueId());

        if (isMultiline && alwaysShow) {
            if (existing == null) {
                if (isPlayer) {
                    createArmorStandDisplay((PlayerEntity) entity, name);
                } else {
                    createTextDisplay(entity, name);
                }
            } else {
                if (existing instanceof ArmorStandInfo armorStandInfo) {
                    updateArmorStandDisplay((PlayerEntity) entity, armorStandInfo, name,
                            resolvePlayerRenderState((PlayerEntity) entity));
                } else {
                    updateTextDisplay(entity, (TextDisplayInfo) existing, name);
                }
            }
        } else {
            if (existing != null) {
                hostJavaIdToUniqueId.remove(existing.hostJavaId);
                if (existing instanceof ArmorStandInfo armorStandInfo) {
                    removeArmorStandDisplay(armorStandInfo);
                } else {
                    removeTextDisplay((TextDisplayInfo) existing);
                }
                displays.remove(entity.uniqueId());
            }
        }
    }

    /** 宿主实体被移除时清理虚拟实体。 */
    public void handleEntityRemoved(final long entityUniqueId) {
        final NametagDisplayInfo info = displays.remove(entityUniqueId);
        if (info != null) {
            hostJavaIdToUniqueId.remove(info.hostJavaId);
            if (info instanceof ArmorStandInfo armorStandInfo) {
                removeArmorStandDisplay(armorStandInfo);
            } else {
                removeTextDisplay((TextDisplayInfo) info);
            }
        }
    }

    /**
     * 清空所有已跟踪状态（例如维度切换时）。
     * Java 客户端在维度切换时会自动清空所有实体，因此无需发送 REMOVE_ENTITIES。
     */
    public void clearAll() {
        displays.clear();
        hostJavaIdToUniqueId.clear();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearSource(PASSENGER_SOURCE);
        }
    }

    /** 协调依赖观察者的玩家名称标签状态。状态稳定时不发送任何数据包。 */
    public void tick() {
        if (this.displays.isEmpty()) return;

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final Iterator<Map.Entry<Long, NametagDisplayInfo>> iterator = this.displays.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Long, NametagDisplayInfo> entry = iterator.next();
            if (!(entry.getValue() instanceof ArmorStandInfo info)) continue;

            final Entity hostEntity = entityTracker.getEntityByUid(info.entityUniqueId);
            if (!(hostEntity instanceof PlayerEntity player) || hostEntity instanceof ClientPlayerEntity) {
                this.hostJavaIdToUniqueId.remove(info.hostJavaId);
                this.removeArmorStandDisplay(info);
                iterator.remove();
                continue;
            }

            this.applyArmorStandRenderState(info, resolvePlayerRenderState(player), info.lines.size());
        }
    }

    // ---- TEXT_DISPLAY 策略（非玩家实体） ----

    private void createTextDisplay(final Entity entity, final String name) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final int javaId = entityTracker.getNextJavaEntityId();
        final UUID uuid = UUID.randomUUID();
        final TextDisplayInfo info = new TextDisplayInfo(javaId, uuid, entity.uniqueId(), entity.javaId(), name);
        displays.put(entity.uniqueId(), info);
        hostJavaIdToUniqueId.put(entity.javaId(), entity.uniqueId());

        // 1. 发送纠正性 SET_ENTITY_DATA，隐藏宿主实体原有的 CUSTOM_NAME
        sendClearHostNametag(entity);

        // 2. 在宿主实体的位置生成 TEXT_DISPLAY 实体。
        // 收到 SET_PASSENGERS 后，客户端会把它重新定位到乘客挂载点。
        info.lastScale = getEffectiveScale(entity);
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // 实体 ID
        addEntity.write(Types.UUID, uuid); // UUID
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // 类型 ID
        addEntity.write(Types.DOUBLE, (double) entity.position().x()); // X
        addEntity.write(Types.DOUBLE, (double) entity.position().y()); // Y
        addEntity.write(Types.DOUBLE, (double) entity.position().z()); // Z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // 速度
        addEntity.write(Types.BYTE, (byte) 0); // 俯仰角
        addEntity.write(Types.BYTE, (byte) 0); // 偏航角
        addEntity.write(Types.BYTE, (byte) 0); // 头部偏航角
        addEntity.write(Types.VAR_INT, 0); // 数据
        addEntity.send(BedrockProtocol.class);

        // 3. 配置 TEXT_DISPLAY 实体数据（包括用于垂直偏移的 TRANSLATION）
        sendTextDisplayEntityData(info, name);

        // 4. 让 TEXT_DISPLAY 骑乘宿主实体，以自动同步位置
        sendSetPassengers(entity.javaId(), info.textDisplayJavaId);
    }

    private void updateTextDisplay(final Entity entity, final TextDisplayInfo info, final String name) {
        final boolean nameChanged = !name.equals(info.lastNameText);
        final float currentScale = getEffectiveScale(entity);
        final boolean scaleChanged = currentScale != info.lastScale;

        if (nameChanged || scaleChanged) {
            // 重新发送纠正数据包，确保宿主名称标签保持隐藏
            sendClearHostNametag(entity);

            if (nameChanged) {
                info.lastNameText = name;
                final Tag textNbt = TextUtil.stringToNbt(name);
                final List<EntityData> displayData = new ArrayList<>();
                displayData.add(new EntityData(
                        textDisplayIndex(EntityDataFields.TEXT),
                        VersionedTypes.V26_1.entityDataTypes().componentType,
                        textNbt));
                final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
                setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
                setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
                setEntityData.send(BedrockProtocol.class);
            }
            if (scaleChanged) {
                info.lastScale = currentScale;
                sendTranslationUpdate(info);
            }
        }
    }

    private void removeTextDisplay(final TextDisplayInfo info) {
        // 移除实体前先清除骑乘关系
        sendSetPassengers(info.hostJavaId);
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{info.textDisplayJavaId});
        removeEntities.send(BedrockProtocol.class);
    }

    private void sendTextDisplayEntityData(final TextDisplayInfo info, final String name) {
        info.lastNameText = name;

        final Tag textNbt = TextUtil.stringToNbt(name);
        final List<EntityData> displayData = new ArrayList<>();

        // TEXT —— 多行文本内容
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT),
                VersionedTypes.V26_1.entityDataTypes().componentType,
                textNbt));

        // LINE_WIDTH —— 足够大，使全息各行不会折行并叠出额外的一行。
        // 不要用 Integer.MAX_VALUE，以防客户端用更窄的 int 存储宽度。
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.LINE_WIDTH),
                VersionedTypes.V26_1.entityDataTypes().varIntType,
                1_000_000));

        // TEXT_DISPLAY 仍会继承 ENTITY.CUSTOM_NAME。保持其为空，
        // 这样显示实体自带的原版名称标签就不会叠加到 TEXT 字段之上。
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                null));
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                false));

        // BILLBOARD_RENDER_CONSTRAINTS = 3（CENTER——在所有轴上朝向相机，与原版名称标签渲染一致）
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 3));

        // TRANSLATION —— 从乘客挂载点到名称标签高度的小幅垂直偏移。
        // 公式：0.0625 * scale + 0.25（由 desiredY - passengerAttachmentY 推导）
        // scale=1 时只有约 0.3 格，因此 MC-261696 的公告板畸变可忽略。
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V26_1.entityDataTypes().vector3FType,
                new Vector3f(0f, getTranslationY(info.lastScale), 0f)));

        // BACKGROUND_COLOR = 0x40000000（半透明黑色，与原版名称标签样式一致）
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BACKGROUND_COLOR),
                VersionedTypes.V26_1.entityDataTypes().varIntType,
                0x40000000));

        // TEXT_OPACITY = -1（0xFF = 完全不透明）
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT_OPACITY),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) -1));

        // VIEW_RANGE = 1.0（默认）
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.VIEW_RANGE),
                VersionedTypes.V26_1.entityDataTypes().floatType,
                1.0f));

        // STYLE_FLAGS = 0x02（see_through——穿透方块渲染，与基岩版名称标签一致）
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.STYLE_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 0x02));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
        setEntityData.send(BedrockProtocol.class);
    }

    private void sendTranslationUpdate(final TextDisplayInfo info) {
        final List<EntityData> displayData = new ArrayList<>();
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V26_1.entityDataTypes().vector3FType,
                new Vector3f(0f, getTranslationY(info.lastScale), 0f)));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * 发送纠正性 SET_ENTITY_DATA，隐藏宿主实体原有的 CUSTOM_NAME。
     * 仅用于非玩家实体（TEXT_DISPLAY 策略）。
     * 玩家实体改用 {@link #sendPlayerTeamPrefix}。
     */
    private void sendClearHostNametag(final Entity entity) {
        final List<EntityData> clearData = new ArrayList<>();
        clearData.add(new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                null));
        clearData.add(new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                false));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, entity.javaId());
        setEntityData.write(VersionedTypes.V26_1.entityDataList, clearData);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * 当实体有活动的 TEXT_DISPLAY 时，从 wrapper 的实体数据列表中
     * 过滤掉 CUSTOM_NAME 和 CUSTOM_NAME_VISIBLE。这样可以防止上游处理器的
     * 实体数据重新启用宿主实体原有的名称标签。
     * <p>
     * 仅在 SET_ENTITY_DATA 的 append 处理器中使用。使用 {@code passthrough()} 把
     * 实体数据列表从 readableObjects 转移到 packetValues（输出），
     * 然后通过 {@code removeIf} 就地修改它。packetValues 中的列表引用
     * 是同一个对象，因此就地修改会反映到序列化后的数据包中。
     * <p>
     * 必须在 {@link #handleEntityDataUpdate} 之后调用（该调用可能创建/移除显示实体）。
     * 绝不能使用 {@code read()}——那样只会从 readableObjects 取出列表却不会
     * 加入 packetValues，导致实体数据列表在输出数据包中完全缺失。
     */
    private void filterHostNametagFromPacket(final Entity entity, final PacketWrapper wrapper) {
        // 玩家实体使用基于队伍的名称标签，而非 CUSTOM_NAME——无需过滤
        if (entity instanceof PlayerEntity) return;

        final NametagDisplayInfo existing = displays.get(entity.uniqueId());
        if (!(existing instanceof TextDisplayInfo)) return;

        // passthrough() 从 readableObjects 取出实体数据列表并加入
        // packetValues（输出）。由于返回的是同一个 ArrayList 引用，
        // removeIf 修改的就是将要序列化给客户端的数据。
        final List<EntityData> entityDataList = wrapper.passthrough(VersionedTypes.V26_1.entityDataList);
        final int customNameIndex = entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME);
        final int customNameVisibleIndex = entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE);
        entityDataList.removeIf(ed ->
                ed.id() == customNameIndex || ed.id() == customNameVisibleIndex);
    }

    // ---- 盔甲架策略（玩家实体） ----

    private void createArmorStandDisplay(final PlayerEntity entity, final String name) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final String[] allLines = name.split("\n");
        final int armorStandCount = allLines.length - 1; // 最下面一行放到玩家队伍名称标签上
        final PlayerNametagRenderState renderState = resolvePlayerRenderState(entity);

        final ArmorStandInfo info = new ArmorStandInfo(entity.uniqueId(), entity.javaId(), name);
        displays.put(entity.uniqueId(), info);
        hostJavaIdToUniqueId.put(entity.javaId(), entity.uniqueId());

        // 1. 把玩家队伍名称标签设为仅最下面一行
        sendPlayerTeamPrefix(entity, allLines[allLines.length - 1]);

        // 2. 为其余各行生成盔甲架（从下到上）
        for (int i = 0; i < armorStandCount; i++) {
            final int lineIndex = i + 1; // 用于缩放公式的从 1 开始的索引
            final String lineText = allLines[allLines.length - 1 - lineIndex];
            final int javaId = entityTracker.getNextJavaEntityId();
            final UUID uuid = UUID.randomUUID();
            info.lines.add(new ArmorStandLine(javaId, uuid, lineText));

            spawnArmorStand(entity, javaId, uuid);
            sendArmorStandEntityData(javaId, lineText, renderState);
            sendArmorStandScale(javaId, getArmorStandScale(lineIndex));
        }
        info.lastRenderState = renderState;

        // 3. 让所有盔甲架骑乘该玩家实体
        sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
    }

    private void updateArmorStandDisplay(final PlayerEntity entity, final ArmorStandInfo info, final String name,
                                         final PlayerNametagRenderState renderState) {
        if (name.equals(info.lastNameText)) {
            applyArmorStandRenderState(info, renderState, info.lines.size());
            return;
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        info.lastNameText = name;
        final String[] allLines = name.split("\n");
        final int requiredCount = allLines.length - 1;
        final int currentCount = info.lines.size();
        boolean passengersChanged = false;

        // 把玩家队伍名称标签更新为新的最下面一行
        sendPlayerTeamPrefix(entity, allLines[allLines.length - 1]);

        if (requiredCount < currentCount) {
            // 减少：移除多余的盔甲架
            final List<ArmorStandLine> removed = new ArrayList<>(
                    info.lines.subList(requiredCount, currentCount));
            info.lines.subList(requiredCount, currentCount).clear();

            // 先更新乘客（不含被移除的 ID），再移除实体
            sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
            final int[] removeIds = removed.stream().mapToInt(l -> l.javaId).toArray();
            final PacketWrapper removeEntities = PacketWrapper.create(
                    ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, removeIds);
            removeEntities.send(BedrockProtocol.class);
            // passengersChanged 保持为 false——SET_PASSENGERS 已在上面发送
        } else if (requiredCount > currentCount) {
            // 增加：生成新的盔甲架
            for (int i = currentCount; i < requiredCount; i++) {
                final int javaId = entityTracker.getNextJavaEntityId();
                final UUID uuid = UUID.randomUUID();
                info.lines.add(new ArmorStandLine(javaId, uuid, ""));
                spawnArmorStand(entity, javaId, uuid);
                sendArmorStandEntityData(javaId, "", renderState); // 文本在下面更新
            }
            passengersChanged = true;
        }

        // 更新所有盔甲架的文本
        for (int i = 0; i < info.lines.size(); i++) {
            final int lineIndex = i + 1;
            final String lineText = allLines[allLines.length - 1 - lineIndex];
            final ArmorStandLine line = info.lines.get(i);
            if (!lineText.equals(line.text)) {
                line.text = lineText;
                sendArmorStandNameUpdate(line.javaId, lineText);
            }
        }

        // 为任何新加入的盔甲架发送缩放值
        // （已有的盔甲架缩放值已正确——它不依赖宿主实体）
        if (passengersChanged) {
            for (int i = currentCount; i < info.lines.size(); i++) {
                sendArmorStandScale(info.lines.get(i).javaId, getArmorStandScale(i + 1));
            }
            sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
        }

        // 新加入的行已有 renderState；只有保留下来的行需要状态增量。
        applyArmorStandRenderState(info, renderState, Math.min(currentCount, requiredCount));
    }

    private void removeArmorStandDisplay(final ArmorStandInfo info) {
        if (!info.lines.isEmpty()) {
            // 清除骑乘关系
            sendSetPassengers(info.hostJavaId);
            // 移除所有盔甲架实体
            final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, info.getVirtualEntityIds());
            removeEntities.send(BedrockProtocol.class);
        }
        // 无需恢复队伍前缀——要么实体正在被移除（队伍也一并移除），
        // 要么名称已变成单行（上游 PlayerEntity.updateName 已发送正确的队伍更新）。
    }

    private void spawnArmorStand(final Entity hostEntity, final int javaId, final UUID uuid) {
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // 实体 ID
        addEntity.write(Types.UUID, uuid); // UUID
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.ARMOR_STAND.getId()); // 类型 ID
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().x()); // X
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().y()); // Y
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().z()); // Z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // 速度
        addEntity.write(Types.BYTE, (byte) 0); // 俯仰角
        addEntity.write(Types.BYTE, (byte) 0); // 偏航角
        addEntity.write(Types.BYTE, (byte) 0); // 头部偏航角
        addEntity.write(Types.VAR_INT, 0); // 数据
        addEntity.send(BedrockProtocol.class);
    }

    private void sendArmorStandEntityData(final int javaId, final String text,
                                          final PlayerNametagRenderState renderState) {
        final List<EntityData> data = new ArrayList<>();

        // 始终隐藏盔甲架模型；0x02 与宿主潜行时的名称样式一致。
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.SHARED_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                renderState.sharedFlags));

        // CUSTOM_NAME —— 该行文本
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                TextUtil.stringToNbt(text)));

        // ArmorStandRenderer 绕过了玩家距离/隐身检查，因此可见性必须显式设置。
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                renderState.nameVisible));

        // NO_GRAVITY = true
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.NO_GRAVITY),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                true));

        // CLIENT_FLAGS：0x04（无底板）。不是 marker（0x10）——marker 会让 height=0，
        // 从而阻止基于缩放的名称标签垂直定位。
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CLIENT_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 0x04));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void applyArmorStandRenderState(final ArmorStandInfo info,
                                            final PlayerNametagRenderState renderState,
                                            final int existingLineCount) {
        final PlayerNametagRenderState previousState = info.lastRenderState;
        if (previousState == renderState) return;

        final boolean sharedFlagsChanged = previousState == null || previousState.sharedFlags != renderState.sharedFlags;
        final boolean visibilityChanged = previousState == null || previousState.nameVisible != renderState.nameVisible;
        final int updateCount = Math.min(existingLineCount, info.lines.size());
        for (int i = 0; i < updateCount; i++) {
            sendArmorStandRenderStateUpdate(info.lines.get(i).javaId, renderState,
                    sharedFlagsChanged, visibilityChanged);
        }
        info.lastRenderState = renderState;
    }

    private void sendArmorStandRenderStateUpdate(final int javaId,
                                                  final PlayerNametagRenderState renderState,
                                                  final boolean sharedFlagsChanged,
                                                  final boolean visibilityChanged) {
        final List<EntityData> data = new ArrayList<>(2);
        if (sharedFlagsChanged) {
            data.add(new EntityData(
                    armorStandIndex(EntityDataFields.SHARED_FLAGS),
                    VersionedTypes.V26_1.entityDataTypes().byteType,
                    renderState.sharedFlags));
        }
        if (visibilityChanged) {
            data.add(new EntityData(
                    armorStandIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                    VersionedTypes.V26_1.entityDataTypes().booleanType,
                    renderState.nameVisible));
        }
        if (data.isEmpty()) return;

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void sendArmorStandScale(final int javaId, final double scale) {
        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, this.user());
        updateAttributes.write(Types.VAR_INT, javaId); // 实体 ID
        updateAttributes.write(Types.VAR_INT, 1); // 属性数量
        updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.SCALE)); // 属性 ID
        updateAttributes.write(Types.DOUBLE, scale); // 基础值
        updateAttributes.write(Types.VAR_INT, 0); // 修饰符数量
        updateAttributes.send(BedrockProtocol.class);
    }

    private void sendArmorStandNameUpdate(final int javaId, final String text) {
        final List<EntityData> data = new ArrayList<>();
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                TextUtil.stringToNbt(text)));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * 为玩家实体发送纠正性队伍更新，把队伍前缀设为
     * 指定的最下面一行文本。PlayerEntity 已经为 Java 队伍前缀
     * 发送最下面一行；如果后续的元数据批次重写了队伍，
     * 这个数据包会让前缀保持同步。
     */
    private void sendPlayerTeamPrefix(final PlayerEntity entity, final String bottomLine) {
        final boolean hasVisibleName = !TextUtil.stripFormatting(bottomLine).isEmpty();
        entity.updateTeamPresentation(hasVisibleName ? bottomLine : "", hasVisibleName);
    }

    // ---- 共用方法 ----

    /** 更新本功能的虚拟乘客，同时不覆盖真正的基岩骑乘乘客。 */
    private void sendSetPassengers(final int vehicleJavaId, final int... passengerJavaIds) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.setVirtualPassengers(PASSENGER_SOURCE, vehicleJavaId, passengerJavaIds);
        }
    }

    // ---- 工具方法 ----

    private static String getEntityName(final Entity entity) {
        final EntityData nameData = entity.entityData().get(ActorDataIDs.NAME);
        final EntityData nameRawData = entity.entityData().get(ActorDataIDs.NAME_RAW_TEXT);
        final String name = nameData != null ? (String) nameData.getValue() : null;
        final String nameRaw = nameRawData != null ? (String) nameRawData.getValue() : null;
        return TextUtil.nametagValue(name, nameRaw);
    }

    private static boolean isMultilineName(final String name) {
        return name != null && name.contains("\n");
    }

    private static boolean isNametagAlwaysShown(final Entity entity) {
        final EntityData alwaysShowData = entity.entityData().get(ActorDataIDs.NAMETAG_ALWAYS_SHOW);
        if (alwaysShowData != null) {
            return TextUtil.nametagAlwaysShown((Number) alwaysShowData.getValue());
        }
        // 默认：对有名称的实体为 true（基岩版默认行为）
        return true;
    }

    private PlayerNametagRenderState resolvePlayerRenderState(final PlayerEntity entity) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
        final boolean viewerSpectator = clientPlayer != null && GameTypeRewriter.isMotSpectator(clientPlayer.gameType());
        final double distanceSquared = squaredDistance(clientPlayer, entity);
        return resolvePlayerRenderState(
                entity.hasEntityFlag(ActorFlags.SNEAKING),
                entity.hasEntityFlag(ActorFlags.INVISIBLE),
                viewerSpectator,
                distanceSquared);
    }

    static PlayerNametagRenderState resolvePlayerRenderState(final boolean hostSneaking,
                                                              final boolean hostInvisible,
                                                              final boolean viewerSpectator,
                                                              final double distanceSquared) {
        final boolean visible = (!hostInvisible || viewerSpectator)
                && (!hostSneaking || distanceSquared <= SNEAKING_NAMETAG_DISTANCE_SQUARED);
        if (hostSneaking) {
            return visible ? PlayerNametagRenderState.VISIBLE_SNEAKING : PlayerNametagRenderState.HIDDEN_SNEAKING;
        }
        return visible ? PlayerNametagRenderState.VISIBLE_NORMAL : PlayerNametagRenderState.HIDDEN_NORMAL;
    }

    private static double squaredDistance(final Entity first, final Entity second) {
        if (first == null || second == null || first.position() == null || second.position() == null) {
            return Double.POSITIVE_INFINITY;
        }
        final double x = first.position().x() - second.position().x();
        final double y = first.position().y() - second.position().y();
        final double z = first.position().z() - second.position().z();
        return x * x + y * y + z * z;
    }

    private static float getEffectiveScale(final Entity entity) {
        final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
        if (scaleData != null) {
            final float scale = ((Number) scaleData.getValue()).floatValue();
            if (scale > 0f) return scale;
        }
        return 1.0f;
    }

    /**
     * 计算从乘客挂载点到名称标签位置的 TRANSLATION Y 偏移。
     * 供 TEXT_DISPLAY 策略使用。
     * <p>
     * Java LivingEntity 的乘客挂载 Y ≈ (entityHeight - 0.0625) * scale。
     * 期望的名称标签 Y ≈ entityHeight * scale + 0.25。
     * 差值 = 0.0625 * scale + 0.25（高度被抵消）。
     */
    private static float getTranslationY(final float scale) {
        return PASSENGER_OFFSET * scale + 0.25f;
    }

    /**
     * 为指定行索引处的盔甲架计算 minecraft:scale 属性值。
     * 供盔甲架策略使用。
     * <p>
     * 在 Minecraft 1.21.x 中，PASSENGER 和 NAME_TAG 挂载点都使用 AT_HEIGHT，
     * 即都位于 Y = entityHeight * scale。由于盔甲架作为乘客的位置
     * 等于载具的 PASSENGER 挂载点，而名称标签渲染在
     * 盔甲架的 NAME_TAG 挂载点 + 0.5 处：
     * <pre>
     * asNametagY = passengerY + ARMOR_STAND_BASE_HEIGHT * asScale + 0.5
     * playerNametagY = playerY + playerHeight * playerScale + 0.5
     * passengerY = playerY + playerHeight * playerScale  (PASSENGER = AT_HEIGHT)
     * gap = asNametagY - playerNametagY = ARMOR_STAND_BASE_HEIGHT * asScale
     * </pre>
     * 解出 asScale：{@code asScale = LINE_HEIGHT * lineIndex / ARMOR_STAND_BASE_HEIGHT}。
     * 该公式与宿主实体的缩放、高度和姿态无关。
     *
     * @param lineIndex 从 1 开始的索引（1 = 玩家队伍名称标签上方的第一行）
     */
    private static double getArmorStandScale(final int lineIndex) {
        return (LINE_HEIGHT * lineIndex) / ARMOR_STAND_BASE_HEIGHT;
    }

    private static int textDisplayIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + fieldName);
        }
        return index;
    }

    private static int armorStandIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.ARMOR_STAND).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown ARMOR_STAND entity data field: " + fieldName);
        }
        return index;
    }

    // ---- 内部类 ----

    private static abstract class NametagDisplayInfo {
        final long entityUniqueId;
        final int hostJavaId;
        String lastNameText;
        float lastScale;

        NametagDisplayInfo(final long entityUniqueId, final int hostJavaId, final String nameText) {
            this.entityUniqueId = entityUniqueId;
            this.hostJavaId = hostJavaId;
            this.lastNameText = nameText;
        }

        abstract int[] getVirtualEntityIds();
    }

    private static class TextDisplayInfo extends NametagDisplayInfo {
        final int textDisplayJavaId;
        final UUID textDisplayUuid;

        TextDisplayInfo(final int textDisplayJavaId, final UUID textDisplayUuid,
                        final long entityUniqueId, final int hostJavaId, final String nameText) {
            super(entityUniqueId, hostJavaId, nameText);
            this.textDisplayJavaId = textDisplayJavaId;
            this.textDisplayUuid = textDisplayUuid;
        }

        @Override
        int[] getVirtualEntityIds() {
            return new int[]{textDisplayJavaId};
        }
    }

    private static class ArmorStandInfo extends NametagDisplayInfo {
        final List<ArmorStandLine> lines = new ArrayList<>();
        PlayerNametagRenderState lastRenderState;

        ArmorStandInfo(final long entityUniqueId, final int hostJavaId, final String nameText) {
            super(entityUniqueId, hostJavaId, nameText);
        }

        @Override
        int[] getVirtualEntityIds() {
            return lines.stream().mapToInt(l -> l.javaId).toArray();
        }
    }

    enum PlayerNametagRenderState {
        VISIBLE_NORMAL((byte) 0x20, true),
        VISIBLE_SNEAKING((byte) 0x22, true),
        HIDDEN_NORMAL((byte) 0x20, false),
        HIDDEN_SNEAKING((byte) 0x22, false);

        final byte sharedFlags;
        final boolean nameVisible;

        PlayerNametagRenderState(final byte sharedFlags, final boolean nameVisible) {
            this.sharedFlags = sharedFlags;
            this.nameVisible = nameVisible;
        }
    }

    private static class ArmorStandLine {
        final int javaId;
        final UUID uuid;
        String text;

        ArmorStandLine(final int javaId, final UUID uuid, final String text) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.text = text;
        }
    }

}
