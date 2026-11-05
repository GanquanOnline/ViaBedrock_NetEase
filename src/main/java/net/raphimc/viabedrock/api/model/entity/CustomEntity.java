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
package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Quaternion;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.render.RenderControllerEvaluator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.experimental.model.animation.ServerEntityTicker;
import net.raphimc.viabedrock.experimental.model.animation.SimpleBoneModel;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomEntityResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.util.*;
import java.util.logging.Level;

public class CustomEntity extends Entity {

    /**
     * LOD 层级：距离（方块）-> 更新间隔（刻）。
     * interpolation_duration 与更新间隔一致，以保证过渡无缝。
     */
    private static final int[][] LOD_TIERS = {
            {16, 4},    // < 16 方块：每 4 刻更新一次
            {32, 8},    // < 32 方块：每 8 刻
            {48, 12},   // < 48 方块：每 12 刻
            {64, 20},   // < 64 方块：每 20 刻
    };
    private static final int LOD_FREEZE_DISTANCE = 64;

    private final EntityDefinitions.EntityDefinition entityDefinition;
    private final List<BoneDisplayEntity> boneEntities = new ArrayList<>();
    private boolean spawned;
    private boolean collisionBoxSent;

    // 服务端动画（仅在未安装 VBU 模组时使用）
    private ServerEntityTicker serverTicker;
    private int ticksSinceLastUpdate;
    private int currentLodInterval = 4;

    // 用于查询绑定的移动追踪
    private Position3f prevEntityPosition;
    private float accumulatedDistance;
    private float groundSpeed;
    private float verticalSpeed;
    private float positionDeltaX;
    private float positionDeltaY;
    private float positionDeltaZ;

    public CustomEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final EntityDefinitions.EntityDefinition entityDefinition) {
        super(user, uniqueId, runtimeId, type, javaId, UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
        this.entityDefinition = entityDefinition;
    }

    @Override
    public void tick() {
        super.tick();

        // 每刻更新移动追踪（准确的移动速度 / 距离计算依赖于此）
        this.updateMovementTracking();

        // 仅在未安装 VBU 时使用服务端动画
        if (this.serverTicker == null || !this.spawned || this.boneEntities.isEmpty()) {
            return;
        }

        this.ticksSinceLastUpdate++;
        if (this.ticksSinceLastUpdate < this.currentLodInterval) {
            return;
        }
        this.ticksSinceLastUpdate = 0;

        // 根据与客户端玩家的距离更新 LOD 层级
        updateLodTier();

        // 运行动画刻
        final MutableObjectBinding queryBindings = buildQueryBindings();
        final Map<String, SimpleBoneModel.WorldTransform> transforms = this.serverTicker.tick(queryBindings);
        if (transforms == null || transforms.isEmpty()) {
            return;
        }

        // 把更新后的变换发送给所有骨骼显示实体
        sendBoneTransformUpdates(transforms);
    }

    @Override
    public void setPosition(final Position3f position) {
        super.setPosition(position);

        if (!this.spawned) {
            this.initServerTicker();
            this.evaluateRenderControllerChange();
            this.spawn();
        } else {
            this.boneEntities.forEach(BoneDisplayEntity::updatePositionAndRotation);
        }
    }

    @Override
    public void setRotation(final Position3f rotation) {
        super.setRotation(rotation);

        if (this.spawned) {
            this.boneEntities.forEach(BoneDisplayEntity::updatePositionAndRotation);
        }
    }

    @Override
    public void remove() {
        super.remove();
        this.despawn();
        this.serverTicker = null;
    }

    @Override
    protected boolean translateEntityData(final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        if (id == ActorDataIDs.RESERVED_038 && this.spawned) {
            final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
            if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            }
            this.updateCollisionBox(javaEntityData);
            this.collisionBoxSent = true;
            return true;
        }
        if ((id == ActorDataIDs.RESERVED_053 || id == ActorDataIDs.RESERVED_054) && this.spawned) {
            this.updateCollisionBox(javaEntityData);
            this.collisionBoxSent = true;
            return true;
        }
        return super.translateEntityData(id, entityData, javaEntityData);
    }

    @Override
    protected void onEntityDataChanged() {
        super.onEntityDataChanged();

        if (!this.collisionBoxSent && this.spawned) {
            this.sendInitialCollisionBox();
            this.collisionBoxSent = true;
        }

        if (this.evaluateRenderControllerChange()) {
            this.despawn();
            this.spawn();
        } else {
            final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
            if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            }
        }
    }

    // ---- 服务端 ticker 初始化 ----

    private void initServerTicker() {
        final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
        if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
            return; // VBU 模组在客户端处理动画
        }
        if (!ViaBedrock.getConfig().shouldEnableServerEntityAnimation()) {
            return; // 服务端动画已被配置禁用
        }

        final ResourcePackStorage resourcePackStorage = this.user.get(ResourcePackStorage.class);

        // 为此实体查找第一个几何模型
        for (Map.Entry<String, String> entry : this.entityDefinition.entityData().getGeometries().entrySet()) {
            final BedrockGeometryModel geometry = resourcePackStorage.getModels().getEntityModel(entry.getValue());
            if (geometry == null) continue;

            final SimpleBoneModel boneModel = new SimpleBoneModel(geometry);

            final PackManager packManager = resourcePackStorage.getBedrockMotionPackManager();
            if (packManager == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "BedrockMotion PackManager not found, server-side animation disabled");
                return;
            }

            this.serverTicker = new ServerEntityTicker(
                    this.entityDefinition.entityData(), boneModel, packManager);
            break;
        }
    }

    // ---- 生成 / 消失 ----

    private void spawn() {
        this.spawned = true;

        final EntityTracker entityTracker = this.user.get(EntityTracker.class);
        final ResourcePackStorage resourcePackStorage = this.user.get(ResourcePackStorage.class);
        final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);

        // 已安装 VBU 模组：交由客户端渲染
        if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
            ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            return;
        }

        // 确定当前的渲染控制器模型
        final List<RenderControllerEvaluator.EvaluatedModel> models =
                this.serverTicker != null ? this.serverTicker.getCurrentModels() : List.of();
        if (models.isEmpty()) {
            return;
        }

        // 为每个求值得到的模型生成逐骨骼的显示实体
        for (RenderControllerEvaluator.EvaluatedModel model : models) {
            final String baseKey = this.entityDefinition.identifier() + "_" + model.key();

            @SuppressWarnings("unchecked")
            final List<String> boneNames = (List<String>) resourcePackStorage.getRuntimeData().get("ce_" + baseKey + "_bones");
            if (boneNames == null || boneNames.isEmpty()) continue;

            for (String boneName : boneNames) {
                final String boneKey = baseKey + "_" + boneName;
                final Object scaleObj = resourcePackStorage.getRuntimeData().get("ce_" + boneKey + "_scale");
                if (scaleObj == null) continue;
                final float scale = (float) scaleObj;

                // 根据骨骼的静止枢轴计算静止偏移
                final org.joml.Vector3f restPivot = this.serverTicker != null
                        ? this.serverTicker.getBoneModel().getRestPivot(boneName)
                        : new org.joml.Vector3f();
                final Vector3f restOffset = computeRestOffset(restPivot);

                final BoneDisplayEntity boneEntity = new BoneDisplayEntity(
                        entityTracker.getNextJavaEntityId(), boneName, scale, restOffset);
                this.boneEntities.add(boneEntity);

                final List<EntityData> javaEntityData = new ArrayList<>();

                // 带有逐骨骼自定义模型数据的物品模型
                final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();
                data.set(StructuredDataKey.ITEM_MODEL, CustomEntityResourceRewriter.getItemModel(this.entityDefinition.identifier()));
                data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomEntityResourceRewriter.getCustomModelData(boneKey));
                final StructuredItem item = new StructuredItem(
                        BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:paper"), 1, data);
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.ITEM_STACK),
                        VersionedTypes.V26_1.entityDataTypes.itemType, item));

                // 实体级缩放（取自 RESERVED_038 实体数据）
                final float entityScale = getEntityScale();

                // 缩放（模型缩放 * 实体缩放）
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.SCALE),
                        VersionedTypes.V26_1.entityDataTypes.vector3FType,
                        new Vector3f(scale * entityScale, scale * entityScale, scale * entityScale)));

                // 平移：静止姿态偏移乘以实体缩放
                // 公式：TRANSLATION = (bonePivotWorld_java - R * P_offset) * entityScale
                // 静止时：R = 单位矩阵，因此 TRANSLATION = (bedrockPivotToJavaBlocks(restPivot) - restOffset) * entityScale
                final Vector3f pivotJava = bedrockPivotToJavaBlocks(restPivot);
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSLATION),
                        VersionedTypes.V26_1.entityDataTypes.vector3FType,
                        new Vector3f((pivotJava.x() - restOffset.x()) * entityScale,
                                (pivotJava.y() - restOffset.y()) * entityScale,
                                (pivotJava.z() - restOffset.z()) * entityScale)));

                // LEFT_ROTATION：初始为单位四元数
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.LEFT_ROTATION),
                        VersionedTypes.V26_1.entityDataTypes.quaternionType,
                        new Quaternion(0F, 0F, 0F, 1F)));

                // 启用插值以获得平滑动画
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_DURATION),
                        VersionedTypes.V26_1.entityDataTypes.varIntType,
                        this.currentLodInterval));
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS),
                        VersionedTypes.V26_1.entityDataTypes.varIntType,
                        0));

                // 生成 ADD_ENTITY 包
                final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user);
                addEntity.write(Types.VAR_INT, boneEntity.javaId());
                addEntity.write(Types.UUID, boneEntity.javaUuid());
                addEntity.write(Types.VAR_INT, boneEntity.javaType().getId());
                addEntity.write(Types.DOUBLE, (double) this.position.x());
                addEntity.write(Types.DOUBLE, (double) this.position.y());
                addEntity.write(Types.DOUBLE, (double) this.position.z());
                addEntity.write(Types.LOW_PRECISION_VECTOR, Vector3d.ZERO);
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.x()));
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.y()));
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.z()));
                addEntity.write(Types.VAR_INT, 0);
                addEntity.send(BedrockProtocol.class);

                // 设置实体数据
                final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
                setEntityData.write(Types.VAR_INT, boneEntity.javaId());
                setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData);
                setEntityData.send(BedrockProtocol.class);
            }
        }
    }

    private void despawn() {
        this.spawned = false;
        final int[] entityIds = new int[boneEntities.size()];
        for (int i = 0; i < boneEntities.size(); i++) {
            entityIds[i] = boneEntities.get(i).javaId();
        }
        this.boneEntities.clear();
        if (entityIds.length > 0) {
            final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user);
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, entityIds);
            removeEntities.send(BedrockProtocol.class);
        }
    }

    // ---- 动画更新 ----

    private void sendBoneTransformUpdates(Map<String, SimpleBoneModel.WorldTransform> transforms) {
        final float entityScale = getEntityScale();

        for (BoneDisplayEntity boneEntity : this.boneEntities) {
            final SimpleBoneModel.WorldTransform transform = transforms.get(boneEntity.boneName);
            if (transform == null) continue;

            final List<EntityData> javaEntityData = new ArrayList<>();

            // 把世界枢轴从基岩模型单位转换为 Java 方块（含 X 轴翻转）
            final Vector3f pivotJava = bedrockPivotToJavaBlocks(transform.position());

            // 把旋转四元数从基岩空间转换到 Java 空间（X 轴翻转）
            final Quaternion rotJava = bedrockQuatToJava(transform.rotation());

            // 提取动画缩放（来自骨骼层级的 worldScale）
            final org.joml.Vector3f animScale = transform.scale();

            // TRANSLATION = entityScale * (pivotJava - R * diag(worldScale) * restOffset)
            // 在旋转之前先用 worldScale 缩放 restOffset，使旋转 / 缩放
            // 中心无论动画缩放如何都保持在骨骼枢轴上。
            final org.joml.Quaternionf rj = new org.joml.Quaternionf(rotJava.x(), rotJava.y(), rotJava.z(), rotJava.w());
            final org.joml.Vector3f scaledRotatedOffset = new org.joml.Vector3f(
                    boneEntity.restOffset.x() * animScale.x,
                    boneEntity.restOffset.y() * animScale.y,
                    boneEntity.restOffset.z() * animScale.z);
            rj.transform(scaledRotatedOffset);

            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSLATION),
                    VersionedTypes.V26_1.entityDataTypes.vector3FType,
                    new Vector3f((pivotJava.x() - scaledRotatedOffset.x) * entityScale,
                            (pivotJava.y() - scaledRotatedOffset.y) * entityScale,
                            (pivotJava.z() - scaledRotatedOffset.z) * entityScale)));

            // 旋转
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.LEFT_ROTATION),
                    VersionedTypes.V26_1.entityDataTypes.quaternionType,
                    rotJava));

            // 动画带来的缩放（模型缩放 * 动画缩放 * 实体缩放）
            final float baseScale = boneEntity.modelScale;
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.SCALE),
                    VersionedTypes.V26_1.entityDataTypes.vector3FType,
                    new Vector3f(baseScale * animScale.x * entityScale,
                            baseScale * animScale.y * entityScale,
                            baseScale * animScale.z * entityScale)));

            // 设置插值：时长 = LOD 间隔，起点 = 0（当前）
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_DURATION),
                    VersionedTypes.V26_1.entityDataTypes.varIntType,
                    this.currentLodInterval));
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS),
                    VersionedTypes.V26_1.entityDataTypes.varIntType,
                    0));

            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
            setEntityData.write(Types.VAR_INT, boneEntity.javaId());
            setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData);
            setEntityData.send(BedrockProtocol.class);
        }
    }

    // ---- LOD 系统 ----

    private void updateLodTier() {
        final EntityTracker entityTracker = this.user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer == null || clientPlayer.position() == null || this.position == null) {
            this.currentLodInterval = LOD_TIERS[0][1]; // 默认为最近的层级
            return;
        }

        final float dx = this.position.x() - clientPlayer.position().x();
        final float dy = this.position.y() - clientPlayer.position().y();
        final float dz = this.position.z() - clientPlayer.position().z();
        final float distSq = dx * dx + dy * dy + dz * dz;

        for (int[] tier : LOD_TIERS) {
            if (distSq < tier[0] * tier[0]) {
                this.currentLodInterval = tier[1];
                return;
            }
        }

        // 超出最大距离：冻结动画
        this.currentLodInterval = Integer.MAX_VALUE;
    }

    // ---- 移动追踪 ----

    private void updateMovementTracking() {
        if (this.prevEntityPosition != null && this.position != null) {
            this.positionDeltaX = this.position.x() - this.prevEntityPosition.x();
            this.positionDeltaY = this.position.y() - this.prevEntityPosition.y();
            this.positionDeltaZ = this.position.z() - this.prevEntityPosition.z();

            final float horizontalDist = (float) Math.sqrt(
                    this.positionDeltaX * this.positionDeltaX + this.positionDeltaZ * this.positionDeltaZ);
            this.accumulatedDistance += horizontalDist;
            this.groundSpeed = horizontalDist * 20f; // 每秒方块数（20 tps）
            this.verticalSpeed = this.positionDeltaY * 20f;
        }
        this.prevEntityPosition = this.position;
    }

    // ---- 查询绑定 ----

    private MutableObjectBinding buildQueryBindings() {
        final MutableObjectBinding queryBinding = new MutableObjectBinding();

        // 实体数据查询（variant、mark_variant、skin_id）
        if (this.entityData.containsKey(ActorDataIDs.VARIANT)) {
            queryBinding.set("variant", Value.of(this.entityData.get(ActorDataIDs.VARIANT).<Integer>value()));
        }
        if (this.entityData.containsKey(ActorDataIDs.MARK_VARIANT)) {
            queryBinding.set("mark_variant", Value.of(this.entityData.get(ActorDataIDs.MARK_VARIANT).<Integer>value()));
        }
        if (this.entityData.containsKey(ActorDataIDs.SKIN_ID)) {
            queryBinding.set("skin_id", Value.of(this.entityData.get(ActorDataIDs.SKIN_ID).<Integer>value()));
        }

        // 实体标志位
        final Set<ActorFlags> entityFlags = this.entityFlags();
        for (Map.Entry<ActorFlags, String> entry : BedrockProtocol.MAPPINGS.getBedrockEntityFlagMoLangQueries().entrySet()) {
            if (entityFlags.contains(entry.getKey())) {
                queryBinding.set(entry.getValue(), Value.of(true));
            }
        }
        if (entityFlags.contains(ActorFlags.ONFIRE)) {
            queryBinding.set("is_onfire", Value.of(true));
        }

        // 移动查询
        queryBinding.set("modified_distance_moved", Value.of(this.accumulatedDistance));
        queryBinding.set("modified_move_speed", Value.of(this.groundSpeed));
        queryBinding.set("ground_speed", Value.of(this.groundSpeed));
        queryBinding.set("vertical_speed", Value.of(this.verticalSpeed));

        // 状态查询
        queryBinding.set("is_on_ground", Value.of(this.onGround));
        queryBinding.set("is_alive", Value.of(true));
        queryBinding.set("life_time", Value.of(this.age / 20.0f));

        // 旋转查询（rotation：pitch=x，yaw=y，headYaw=z）
        queryBinding.set("body_y_rotation", Value.of(this.rotation.y()));
        queryBinding.set("body_x_rotation", Value.of(this.rotation.x()));
        queryBinding.set("target_x_rotation", Value.of(this.rotation.x()));
        queryBinding.set("target_y_rotation", Value.of(this.rotation.z()));
        queryBinding.set("head_x_rotation", Value.of(this.rotation.x()));
        queryBinding.set("head_y_rotation", Value.of(this.rotation.z()));

        // 相机距离与 rotation_to_camera
        final EntityTracker entityTracker = this.user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer != null && clientPlayer.position() != null && this.position != null) {
            final float dx = clientPlayer.position().x() - this.position.x();
            final float dy = clientPlayer.position().y() - this.position.y();
            final float dz = clientPlayer.position().z() - this.position.z();
            final float distFromCamera = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            queryBinding.set("distance_from_camera", Value.of(distFromCamera));

            final float horizontalDist = (float) Math.sqrt(dx * dx + dz * dz);
            final float rotToCameraX = -(float) Math.toDegrees(Math.atan2(dy, horizontalDist));
            final float rotToCameraY = -(float) (Math.toDegrees(Math.atan2(dx, dz)) + this.rotation.y() - 180.0);
            queryBinding.setFunction("rotation_to_camera", (double arg) -> {
                if ((int) arg == 0) return (double) rotToCameraX;
                if ((int) arg == 1) return (double) rotToCameraY;
                return 0.0;
            });
        } else {
            queryBinding.set("distance_from_camera", Value.of(0));
        }

        // position_delta 函数
        final float pdx = this.positionDeltaX;
        final float pdy = this.positionDeltaY;
        final float pdz = this.positionDeltaZ;
        queryBinding.setFunction("position_delta", (double arg) -> {
            int axis = (int) arg;
            if (axis == 0) return (double) pdx;
            if (axis == 1) return (double) pdy;
            if (axis == 2) return (double) pdz;
            return 0.0;
        });

        return queryBinding;
    }

    // ---- 渲染控制器求值 ----

    private boolean evaluateRenderControllerChange() {
        if (this.serverTicker == null) {
            return false;
        }
        return this.serverTicker.evaluateRenderControllers(buildQueryBindings());
    }

    // ---- 碰撞箱 ----

    private void sendInitialCollisionBox() {
        final List<EntityData> javaEntityData = new ArrayList<>();
        this.updateCollisionBox(javaEntityData);
        if (!javaEntityData.isEmpty()) {
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
            setEntityData.write(Types.VAR_INT, this.javaId()); // 实体 id
            setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // 实体数据
            setEntityData.send(BedrockProtocol.class);
        }
    }

    private static final float DEFAULT_WIDTH = 0.6F;
    private static final float DEFAULT_HEIGHT = 1.8F;

    private void updateCollisionBox(final List<EntityData> javaEntityData) {
        float scale = 1.0F;
        if (this.entityData.containsKey(ActorDataIDs.RESERVED_038)) {
            scale = this.entityData.get(ActorDataIDs.RESERVED_038).<Float>value();
        }

        float width = this.entityData.containsKey(ActorDataIDs.RESERVED_053)
                ? this.entityData.get(ActorDataIDs.RESERVED_053).<Float>value()
                : DEFAULT_WIDTH;
        float height = this.entityData.containsKey(ActorDataIDs.RESERVED_054)
                ? this.entityData.get(ActorDataIDs.RESERVED_054).<Float>value()
                : DEFAULT_HEIGHT;
        width *= scale;
        height *= scale;

        javaEntityData.add(new EntityData(
                this.getJavaEntityDataIndex(EntityDataFields.WIDTH),
                VersionedTypes.V26_1.entityDataTypes().floatType, width));
        javaEntityData.add(new EntityData(
                this.getJavaEntityDataIndex(EntityDataFields.HEIGHT),
                VersionedTypes.V26_1.entityDataTypes().floatType, height));
    }

    // ---- 实体缩放辅助方法 ----

    private float getEntityScale() {
        if (this.entityData.containsKey(ActorDataIDs.RESERVED_038)) {
            return this.entityData.get(ActorDataIDs.RESERVED_038).<Float>value();
        }
        return 1.0F;
    }

    // ---- 坐标转换辅助方法 ----

    /**
     * 按 Java 显示实体的方块单位计算某个骨骼的静止偏移（P_offset）。
     * 这是该骨骼的方块在物品模型渲染空间中的居中位置。
     * <p>
     * CubeConverter 的坐标映射：
     * - Java X = -Bedrock X + 8（模型单位），换算为方块：-bpx/16 + 0.5
     * - Java Y = Bedrock Y，换算为方块：bpy/16
     * - Java Z = Bedrock Z + 8（模型单位），换算为方块：bpz/16 + 0.5
     */
    private static Vector3f computeRestOffset(final org.joml.Vector3f restPivot) {
        return new Vector3f(
                -restPivot.x / 16f + 0.5f,
                restPivot.y / 16f,
                restPivot.z / 16f + 0.5f);
    }

    /**
     * 把骨骼动画后的世界枢轴从基岩模型单位转换为 Java 方块。
     * 应用基岩→Java 的 X 轴翻转。
     */
    private static Vector3f bedrockPivotToJavaBlocks(final org.joml.Vector3f bedrockPivot) {
        return new Vector3f(
                -bedrockPivot.x / 16f,
                bedrockPivot.y / 16f,
                bedrockPivot.z / 16f);
    }

    /**
     * 把基岩空间的旋转四元数转换到 Java 空间（X 轴翻转）。
     * 在 X 轴反射下：绕 Y 轴和 Z 轴的旋转方向反转。
     * 四元数：(qx, qy, qz, qw) → (qx, -qy, -qz, qw)
     */
    private static Quaternion bedrockQuatToJava(final org.joml.Quaternionf q) {
        return new Quaternion(q.x, -q.y, -q.z, q.w);
    }

    // ---- 内部类：逐骨骼显示实体 ----

    private class BoneDisplayEntity extends Entity {

        final String boneName;
        final float modelScale;
        /** Java 方块单位下的静止偏移——此骨骼的方块在物品模型中的居中位置 */
        final Vector3f restOffset;

        public BoneDisplayEntity(final int javaId, final String boneName, final float modelScale, final Vector3f restOffset) {
            super(CustomEntity.this.user, 0L, 0L, null, javaId, UUID.randomUUID(), EntityTypes1_21_11.ITEM_DISPLAY);
            this.boneName = boneName;
            this.modelScale = modelScale;
            this.restOffset = restOffset;
        }

        public void updatePositionAndRotation() {
            final PacketWrapper entityPositionSync = PacketWrapper.create(ClientboundPackets26_1.ENTITY_POSITION_SYNC, this.user);
            entityPositionSync.write(Types.VAR_INT, this.javaId());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.x());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.y());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.z());
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.FLOAT, CustomEntity.this.rotation.y());
            entityPositionSync.write(Types.FLOAT, CustomEntity.this.rotation.x());
            entityPositionSync.write(Types.BOOLEAN, CustomEntity.this.onGround);
            entityPositionSync.send(BedrockProtocol.class);
        }
    }

}
