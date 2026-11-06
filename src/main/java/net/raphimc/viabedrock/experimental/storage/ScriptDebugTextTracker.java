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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ScriptModuleDebugUtilities_ScriptDebugShapeType;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 把基岩 Script Debug Drawer（{@code PRIMITIVE_SHAPES} 数据包）转换为 Java
 * TEXT_DISPLAY 实体，且仅处理 {@code Text} 类型的形状。
 * <p>
 * 基岩服务端（例如 CodeFunCore 通过 SynapseAPI 的 {@code addShape}/{@code removeShape}）
 * 使用 Script Debug Drawer 绘制悬浮调试文本面板。Java 版没有对应机制，
 * 因此若不做这个转换，这些面板对 Java 客户端就是不可见的。每个 {@code Text} 形状
 * 变成一个虚拟 TEXT_DISPLAY 实体，做法与 {@link MultilineNametagTracker} 相同。
 * <p>
 * 非文本形状（Line/Box/Sphere/Circle/Arrow）会被解析（以保持多条目流
 * 对齐），但除此之外直接丢弃。
 * <p>
 * 该数据包是以形状 id 为键的增量流：带 {@code type} 的条目是
 * 新增/替换；不带 {@code type} 的条目是移除。形状可以有有限的
 * 存活时间（{@code totalTimeLeft}），可以附着到实体（{@code attachedEntityRuntimeId}），
 * 并且会在维度切换时全部清除（此时 Java 客户端本来也会清空所有实体）。
 */
public class ScriptDebugTextTracker extends StoredObject {

    private static final int DEFAULT_BACKGROUND_COLOR = 0x40000000; // 半透明黑色，与原版名称标签一致
    private static final byte BILLBOARD_FIXED = 0;
    private static final byte BILLBOARD_CENTER = 3; // 在所有轴上朝向相机
    private static final byte STYLE_SEE_THROUGH = 0x02; // 穿透方块渲染
    private static final float DEFAULT_VIEW_RANGE_BLOCKS = 64.0f; // view_range == 1.0 ~= 64 格
    private static final String PASSENGER_SOURCE = "script-debug-text";

    private final Map<Long, ShapeInfo> shapes = new HashMap<>();
    private long tickCounter = 0;

    public ScriptDebugTextTracker(final UserConnection user) {
        super(user);
    }

    // ---- 数据包处理器注册 ----

    public static void registerHandlers(final BedrockProtocol protocol) {
        // PRIMITIVE_SHAPES 没有对应的上游 Java 映射，因此 appendClientbound 注册了一个全新的
        // null 映射处理器。我们完整消费基岩数据包并取消它（不向 Java 透传）。
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.PRIMITIVE_SHAPES, wrapper -> {
            final ScriptDebugTextTracker tracker = wrapper.user().get(ScriptDebugTextTracker.class);
            if (tracker != null) {
                try {
                    tracker.handlePrimitiveShapes(wrapper);
                } catch (final Exception e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to handle PRIMITIVE_SHAPES packet", e);
                }
            }
            wrapper.cancel();
        });

        // CHANGE_DIMENSION —— 清空所有已跟踪的形状（Java 客户端在维度切换时会清空所有实体）
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.CHANGE_DIMENSION, wrapper -> {
            final ScriptDebugTextTracker tracker = wrapper.user().get(ScriptDebugTextTracker.class);
            if (tracker != null) {
                tracker.clearAll();
            }
        });
    }

    // ---- 数据包解析（基岩 1.26.20 PrimitiveShapesPacket 线格式） ----

    private void handlePrimitiveShapes(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 条目数量
        for (int i = 0; i < count; i++) {
            final long id = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 形状 id
            final Byte typeByte = readOptional(wrapper, Types.BYTE); // 可选类型（序号）
            final Position3f location = readOptional(wrapper, BedrockTypes.POSITION_3F);
            final Float scale = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            readOptional(wrapper, BedrockTypes.POSITION_3F); // 旋转（欧拉角）——未应用（文本朝向相机）
            final Float totalTimeLeft = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            final Float maxRenderDistance = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            readOptional(wrapper, BedrockTypes.INT_LE); // 颜色——文本颜色由文本中的 § 码决定
            readOptional(wrapper, BedrockTypes.VAR_INT); // 维度——每个 Java 连接只有一个维度
            final Long attachedRuntimeId = readOptional(wrapper, BedrockTypes.UNSIGNED_VAR_LONG);
            wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // payloadType（总是写入，但被忽略——我们按 type 分派）

            if (typeByte == null) {
                // type 缺失 = 移除。
                remove(id);
                continue;
            }

            final ScriptModuleDebugUtilities_ScriptDebugShapeType type = ScriptModuleDebugUtilities_ScriptDebugShapeType.getByValue(typeByte & 0xFF);
            if (type == null) {
                // 未知的形状类型：我们无法得知其载荷长度，因此无法安全地
                // 继续解析剩余条目。放弃这个数据包。
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown debug shape type: " + (typeByte & 0xFF) + ", skipping rest of PRIMITIVE_SHAPES packet");
                return;
            }

            // 读取类型专属的载荷（必须始终消费，以保持流对齐）。
            String text = null;
            boolean textRotation = false;
            Integer backgroundColor = null;
            boolean depthTest = false;
            switch (type) {
                case Text -> {
                    text = wrapper.read(BedrockTypes.STRING); // 文本（非可选）
                    textRotation = wrapper.read(Types.BOOLEAN);
                    backgroundColor = readOptional(wrapper, BedrockTypes.INT_LE);
                    if (BedrockWire.applies(BedrockDeviation.DEBUG_DRAWER_TEXT_LINE_GAP)) {
                        wrapper.read(BedrockTypes.FLOAT_LE); // 行间距
                    }
                    depthTest = wrapper.read(Types.BOOLEAN);
                    wrapper.read(Types.BOOLEAN); // showBackface
                    wrapper.read(Types.BOOLEAN); // showTextBackface
                }
                case Arrow -> {
                    readOptional(wrapper, BedrockTypes.POSITION_3F); // lineEndLocation
                    readOptional(wrapper, BedrockTypes.FLOAT_LE); // arrowHeadLength
                    readOptional(wrapper, BedrockTypes.FLOAT_LE); // arrowHeadRadius
                    readOptional(wrapper, Types.BYTE); // numSegments
                }
                case Box -> wrapper.read(BedrockTypes.POSITION_3F); // boxBound
                case Line -> wrapper.read(BedrockTypes.POSITION_3F); // lineEndLocation
                case Sphere, Circle -> wrapper.read(Types.BYTE); // numSegments
            }

            if (type == ScriptModuleDebugUtilities_ScriptDebugShapeType.Text) {
                upsertText(id, location, scale, totalTimeLeft, maxRenderDistance, attachedRuntimeId, text != null ? text : "", textRotation, backgroundColor, depthTest);
            } else {
                // 非文本形状：不渲染。如果这个 id 之前有文本显示实体，则移除它。
                if (shapes.containsKey(id)) {
                    remove(id);
                }
            }
        }
    }

    private static <T> T readOptional(final PacketWrapper wrapper, final Type<T> type) {
        return wrapper.read(Types.BOOLEAN) ? wrapper.read(type) : null;
    }

    // ---- 形状生命周期 ----

    private void upsertText(final long id, final Position3f location, final Float scale,
                            final Float totalTimeLeft, final Float maxRenderDistance, final Long attachedRuntimeId,
                            final String text, final boolean textRotation, final Integer backgroundColor, final boolean depthTest) {
        final ShapeInfo existing = shapes.get(id);
        if (existing != null && existing.matches(text, scale, location, attachedRuntimeId)) {
            return; // 未变化的重发——无需处理
        }
        if (existing != null) {
            remove(id); // 已变化——干净地重建（避免部分更新的边界情况）
        }
        create(id, location, scale, totalTimeLeft, maxRenderDistance, attachedRuntimeId, text, textRotation, backgroundColor, depthTest);
    }

    private void create(final long id, final Position3f location, final Float scale,
                        final Float totalTimeLeft, final Float maxRenderDistance, final Long attachedRuntimeId,
                        final String text, final boolean textRotation, final Integer backgroundColor, final boolean depthTest) {
        if (TextUtil.stripFormatting(text).isEmpty() && !text.contains("\n")) {
            // 没有可见内容可显示。
            return;
        }
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final int javaId = entityTracker.getNextJavaEntityId();
        final UUID uuid = UUID.randomUUID();
        final ShapeInfo info = new ShapeInfo(javaId, uuid, text, scale, location, attachedRuntimeId);

        // 解析附着关系：如果附着到（已知的）实体，则通过 SET_PASSENGERS 骑乘它，
        // 让客户端保持同步；否则在形状的绝对世界坐标处生成。
        double spawnX = location != null ? location.x() : 0;
        double spawnY = location != null ? location.y() : 0;
        double spawnZ = location != null ? location.z() : 0;
        int hostJavaId = -1;
        if (attachedRuntimeId != null) {
            final Entity host = entityTracker.getEntityByRid(attachedRuntimeId);
            if (host != null) {
                hostJavaId = host.javaId();
                final Position3f hostPos = host.position();
                if (hostPos != null) {
                    spawnX = hostPos.x();
                    spawnY = hostPos.y();
                    spawnZ = hostPos.z();
                }
            }
        }
        info.attachedHostJavaId = hostJavaId;

        // 有限存活时间——调度自动移除，避免显示实体永久残留。
        if (totalTimeLeft != null && totalTimeLeft > 0 && totalTimeLeft < Float.MAX_VALUE) {
            info.expiryTick = tickCounter + Math.round(totalTimeLeft * 20.0);
        }

        shapes.put(id, info);

        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // 实体 ID
        addEntity.write(Types.UUID, uuid); // UUID
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // 类型 ID
        addEntity.write(Types.DOUBLE, spawnX); // X
        addEntity.write(Types.DOUBLE, spawnY); // Y
        addEntity.write(Types.DOUBLE, spawnZ); // Z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // 速度
        addEntity.write(Types.BYTE, (byte) 0); // 俯仰角
        addEntity.write(Types.BYTE, (byte) 0); // 偏航角
        addEntity.write(Types.BYTE, (byte) 0); // 头部偏航角
        addEntity.write(Types.VAR_INT, 0); // 数据
        addEntity.send(BedrockProtocol.class);

        sendEntityData(info, text, scale, maxRenderDistance, textRotation, backgroundColor, depthTest, hostJavaId != -1);

        if (hostJavaId != -1) {
            // 附着时，形状的位置是相对实体的偏移；SET_PASSENGERS 让
            // 显示实体与宿主保持同步。乘客挂载点与实体根位置不同，
            // 因此该偏移是近似的，但显示实体能正确跟随实体。
            refreshHostPassengers(hostJavaId);
        }
    }

    private void sendEntityData(final ShapeInfo info, final String text, final Float scale, final Float maxRenderDistance,
                                final boolean textRotation, final Integer backgroundColor, final boolean depthTest, final boolean attached) {
        final List<EntityData> data = new ArrayList<>();

        // TEXT —— 内容（TextUtil 会把 § 颜色码和换行符还原成文本组件）
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TEXT), VersionedTypes.V26_1.entityDataTypes().componentType, TextUtil.stringToNbt(text)));

        // BILLBOARD —— 除非设置了 useRotation，基岩文本始终朝向相机。
        data.add(new EntityData(textDisplayIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS), VersionedTypes.V26_1.entityDataTypes().byteType, textRotation ? BILLBOARD_FIXED : BILLBOARD_CENTER));

        // BACKGROUND_COLOR —— 使用显式覆盖值，或类似原版的半透明黑色。
        data.add(new EntityData(textDisplayIndex(EntityDataFields.BACKGROUND_COLOR), VersionedTypes.V26_1.entityDataTypes().varIntType, backgroundColor != null ? backgroundColor : DEFAULT_BACKGROUND_COLOR));

        // TEXT_OPACITY —— 完全不透明。
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TEXT_OPACITY), VersionedTypes.V26_1.entityDataTypes().byteType, (byte) -1));

        // VIEW_RANGE —— 基岩的 maximumRenderDistance 以格为单位；
        // Java 的 view_range 是 64 格的倍数。
        final float viewRange = maxRenderDistance != null && maxRenderDistance > 0 ? maxRenderDistance / DEFAULT_VIEW_RANGE_BLOCKS : 1.0f;
        data.add(new EntityData(textDisplayIndex(EntityDataFields.VIEW_RANGE), VersionedTypes.V26_1.entityDataTypes().floatType, viewRange));

        // STYLE_FLAGS —— 当形状始终渲染时（depthTest == false）使用 see_through。
        data.add(new EntityData(textDisplayIndex(EntityDataFields.STYLE_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, depthTest ? (byte) 0 : STYLE_SEE_THROUGH));

        // SCALE —— 提供时使用统一缩放。
        if (scale != null && scale > 0) {
            data.add(new EntityData(textDisplayIndex(EntityDataFields.SCALE), VersionedTypes.V26_1.entityDataTypes().vector3FType, new Vector3f(scale, scale, scale)));
        }

        // TRANSLATION —— 附着时，把形状的位置用作相对乘客
        // 挂载点的偏移。绝对定位时实体已经位于形状位置，因此没有偏移。
        final Vector3f translation;
        if (attached && info.location != null) {
            translation = new Vector3f(info.location.x(), info.location.y(), info.location.z());
        } else {
            translation = new Vector3f(0f, 0f, 0f);
        }
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TRANSLATION), VersionedTypes.V26_1.entityDataTypes().vector3FType, translation));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void remove(final long id) {
        final ShapeInfo info = shapes.remove(id);
        if (info == null) return;
        if (info.attachedHostJavaId != -1) {
            refreshHostPassengers(info.attachedHostJavaId);
        }
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{info.javaId});
        removeEntities.send(BedrockProtocol.class);
    }

    public void clearAll() {
        // Java 客户端在维度切换时会清空所有实体，所以只需丢弃跟踪状态。
        shapes.clear();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearSource(PASSENGER_SOURCE);
        }
    }

    // ---- 刻（有限存活时间到期） ----

    public void tick() {
        tickCounter++;
        if (shapes.isEmpty()) return;
        List<Long> expired = null;
        for (final Map.Entry<Long, ShapeInfo> entry : shapes.entrySet()) {
            final Long expiryTick = entry.getValue().expiryTick;
            if (expiryTick != null && tickCounter >= expiryTick) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(entry.getKey());
            }
        }
        if (expired != null) {
            for (final Long id : expired) {
                remove(id);
            }
        }
    }

    // ---- 辅助方法 ----

    private void refreshHostPassengers(final int vehicleJavaId) {
        final int[] passengerJavaIds = shapes.values().stream()
                .filter(shape -> shape.attachedHostJavaId == vehicleJavaId)
                .mapToInt(shape -> shape.javaId)
                .toArray();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.setVirtualPassengers(PASSENGER_SOURCE, vehicleJavaId, passengerJavaIds);
        }
    }

    private static int textDisplayIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + fieldName);
        }
        return index;
    }

    // ---- 内部类 ----

    private static final class ShapeInfo {
        final int javaId;
        final UUID uuid;
        final String text;
        final Float scale;
        final Position3f location;
        final Long attachedRuntimeId;
        int attachedHostJavaId = -1;
        Long expiryTick; // null = 无限

        ShapeInfo(final int javaId, final UUID uuid, final String text, final Float scale, final Position3f location, final Long attachedRuntimeId) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.text = text;
            this.scale = scale;
            this.location = location;
            this.attachedRuntimeId = attachedRuntimeId;
        }

        boolean matches(final String text, final Float scale, final Position3f location, final Long attachedRuntimeId) {
            return java.util.Objects.equals(this.text, text)
                    && java.util.Objects.equals(this.scale, scale)
                    && java.util.Objects.equals(this.location, location)
                    && java.util.Objects.equals(this.attachedRuntimeId, attachedRuntimeId);
        }
    }

}
