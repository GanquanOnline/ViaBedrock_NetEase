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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntOpenHashMap;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.entity.*;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class EntityTracker extends StoredObject {

    private final AtomicInteger ID_COUNTER = new AtomicInteger(1);

    private ClientPlayerEntity clientPlayerEntity = null;
    private final Long2ObjectMap<Entity> entities = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Long> runtimeIdToUniqueId = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Long> javaIdToUniqueId = new Int2ObjectOpenHashMap<>();
    private final Object2IntMap<BlockPosition> itemFrames = new Object2IntOpenHashMap<>();
    private final Int2ObjectMap<ItemFrameInfo> itemFrameInfoById = new Int2ObjectOpenHashMap<>();

    public EntityTracker(final UserConnection user) {
        super(user);
    }

    /**
     * 物品展示框伪实体的反向查找数据。物品展示框在基岩里是方块，但只是 Java 侧的伪实体，
     * 因此 Java 的实体交互必须映射回方块坐标（以及正面）才能到达服务器。
     */
    public record ItemFrameInfo(BlockPosition position, int facingDirection) {
    }

    public Entity addEntity(final long uniqueId, final long runtimeId, final String type, final EntityTypes1_21_11 javaType) {
        final UUID javaUuid = UUID.randomUUID();
        if (javaType == EntityTypes1_21_11.ITEM) {
            return this.addEntity(new DroppedItemEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)) {
            return this.addEntity(new AbstractHorseEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.MOB)) {
            return this.addEntity(new MobEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.LIVING_ENTITY)) {
            return this.addEntity(new LivingEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType));
        } else {
            return this.addEntity(new Entity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType));
        }
    }

    public Entity addEntity(final long uniqueId, final long runtimeId, final String type, final EntityTypes1_21_11 javaType, final Integer customJavaTypeId) {
        final UUID javaUuid = UUID.randomUUID();
        if (javaType == EntityTypes1_21_11.ITEM) {
            return this.addEntity(new DroppedItemEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType, customJavaTypeId));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)) {
            return this.addEntity(new AbstractHorseEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType, customJavaTypeId));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.MOB)) {
            return this.addEntity(new MobEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType, customJavaTypeId));
        } else if (javaType.isOrHasParent(EntityTypes1_21_11.LIVING_ENTITY)) {
            return this.addEntity(new LivingEntity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType, customJavaTypeId));
        } else {
            return this.addEntity(new Entity(this.user(), uniqueId, runtimeId, type, this.getNextJavaEntityId(), javaUuid, javaType, customJavaTypeId));
        }
    }

    public <T extends Entity> T addEntity(final T entity) {
        return this.addEntity(entity, true);
    }

    public <T extends Entity> T addEntity(final T entity, final boolean updateTeam) {
        if (entity instanceof ClientPlayerEntity clientPlayerEntity) {
            this.clientPlayerEntity = clientPlayerEntity;
        }

        final Entity prevEntity = this.entities.get(entity.uniqueId());
        if (prevEntity != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Duplicate entity unique ID: " + entity.uniqueId());
            this.removeEntityAndNotify(prevEntity);
        }

        final Entity prevRuntimeEntity = this.entityByRuntimeId(entity.runtimeId());
        if (prevRuntimeEntity != null && prevRuntimeEntity != prevEntity) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Duplicate entity runtime ID: " + entity.runtimeId());
            this.removeEntityAndNotify(prevRuntimeEntity);
        }

        this.entities.put(entity.uniqueId(), entity);
        if (this.javaIdToUniqueId.put(entity.javaId(), (Long) entity.uniqueId()) != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Duplicate Java entity ID: " + entity.javaId());
        }
        this.runtimeIdToUniqueId.put(entity.runtimeId(), (Long) entity.uniqueId());

        if (updateTeam && entity instanceof PlayerEntity player) {
            player.createTeam();
        }

        ExperimentalFeatures.dispatchEntityAdded(this.user(), entity);

        return entity;
    }

    private Entity entityByRuntimeId(final long runtimeId) {
        final Long uniqueId = this.runtimeIdToUniqueId.get(runtimeId);
        return uniqueId != null ? this.entities.get(uniqueId) : null;
    }

    private void removeEntityAndNotify(final Entity entity) {
        this.removeEntity(entity);
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{entity.javaId()}); // 实体 id
        removeEntities.send(BedrockProtocol.class);
    }

    public void removeEntity(final Entity entity) {
        if (entity instanceof ClientPlayerEntity) {
            throw new IllegalArgumentException("Cannot remove client player entity");
        }

        final SpectatorCameraTracker spectatorCamera = this.user().get(SpectatorCameraTracker.class);
        if (spectatorCamera != null) {
            spectatorCamera.onEntityRemoved(entity);
        }
        ExperimentalFeatures.dispatchEntityRemoved(this.user(), entity);

        this.entities.remove(entity.uniqueId());
        this.runtimeIdToUniqueId.remove(entity.runtimeId());
        this.javaIdToUniqueId.remove(entity.javaId());
        entity.remove();
    }

    public void spawnItemFrame(final BlockPosition position, final BlockState blockState, final CompoundTag blockEntityTag) {
        this.removeItemFrame(position);

        if (!blockState.identifier().equals("frame") && !blockState.identifier().equals("glow_frame")) {
            throw new IllegalArgumentException("Block state must be a frame or glow_frame");
        }

        final int javaId = this.getNextJavaEntityId();
        final int facingDirection = Integer.parseInt(blockState.properties().get("facing_direction"));
        this.itemFrames.put(position, javaId);
        this.itemFrameInfoById.put(javaId, new ItemFrameInfo(position, facingDirection));

        final PacketWrapper spawnEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        spawnEntity.write(Types.VAR_INT, javaId); // 实体 id
        spawnEntity.write(Types.UUID, UUID.randomUUID()); // uuid
        spawnEntity.write(Types.VAR_INT, blockState.identifier().equals("frame") ? EntityTypes1_21_11.ITEM_FRAME.getId() : EntityTypes1_21_11.GLOW_ITEM_FRAME.getId()); // 类型 id
        spawnEntity.write(Types.DOUBLE, (double) position.x()); // x
        spawnEntity.write(Types.DOUBLE, (double) position.y()); // y
        spawnEntity.write(Types.DOUBLE, (double) position.z()); // z
        spawnEntity.write(Types.LOW_PRECISION_VECTOR, Vector3d.ZERO); // 速度
        spawnEntity.write(Types.BYTE, (byte) 0); // 俯仰角
        spawnEntity.write(Types.BYTE, (byte) 0); // 偏航角
        spawnEntity.write(Types.BYTE, (byte) 0); // 头部偏航角
        spawnEntity.write(Types.VAR_INT, facingDirection); // 数据
        spawnEntity.send(BedrockProtocol.class);

        // 从调用方提供的方块实体填充显示的物品 / 旋转。
        // 调用方直接传入它（区块加载时来自本地区块，方块更新时来自区块映射），
        // 因为此时通过 ChunkTracker 查询并不可靠：正在被重映射的区块尚未注册到区块映射中，
        // 查询会返回 null，展示框就会渲染为空。若方块实体之后作为单独的 BLOCK_ENTITY_DATA 数据包
        // 到达，则会从该处理器再次调用 updateItemFrameContents。
        if (blockEntityTag != null) {
            this.updateItemFrameContents(position, blockEntityTag);
        }
    }

    public void updateItemFrameContents(final BlockPosition position, final CompoundTag tag) {
        final int javaId = this.getItemFrameId(position);
        if (javaId == -1) {
            return;
        }

        // ITEM_FRAME 与 GLOW_ITEM_FRAME 共享同一实体数据布局。
        final List<String> dataFields = BedrockProtocol.MAPPINGS.getJavaEntityDataFields().get(EntityTypes1_21_11.ITEM_FRAME);

        final Item javaItem;
        if (tag != null && tag.get("Item") instanceof CompoundTag itemTag) {
            javaItem = this.user().get(ItemRewriter.class).javaItemFromNbt(itemTag);
        } else {
            javaItem = StructuredItem.empty();
        }

        int rotation = 0;
        if (tag != null && tag.get("ItemRotation") instanceof NumberTag rotationTag) {
            rotation = bedrockItemFrameRotationToJava(rotationTag.asFloat());
        }

        final List<EntityData> entityData = new ArrayList<>();
        entityData.add(new EntityData(dataFields.indexOf(EntityDataFields.ITEM), VersionedTypes.V26_1.entityDataTypes.itemType, javaItem));
        entityData.add(new EntityData(dataFields.indexOf(EntityDataFields.ROTATION), VersionedTypes.V26_1.entityDataTypes.varIntType, rotation));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId); // 实体 id
        setEntityData.write(VersionedTypes.V26_1.entityDataList, entityData); // 实体数据
        setEntityData.send(BedrockProtocol.class);
    }

    private static int bedrockItemFrameRotationToJava(final float bedrockRotation) {
        int rotation = Math.round(bedrockRotation);
        if (rotation >= 8) { // 有些服务器以角度存储旋转（0, 45, ..., 315）
            rotation = Math.round(bedrockRotation / 45F);
        }
        return ((rotation % 8) + 8) % 8;
    }

    public void removeItemFrame(final BlockPosition position) {
        if (!this.itemFrames.containsKey(position)) {
            return;
        }

        final int javaId = this.itemFrames.removeInt(position);
        this.itemFrameInfoById.remove(javaId);

        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{javaId}); // 实体 id
        removeEntities.send(BedrockProtocol.class);
    }

    public int getItemFrameId(final BlockPosition position) {
        return this.itemFrames.getOrDefault(position, -1);
    }

    public ItemFrameInfo getItemFrameInfo(final int javaId) {
        return this.itemFrameInfoById.get(javaId);
    }

    void putItemFrame(final BlockPosition position, final int javaId, final int facingDirection) {
        this.itemFrames.put(position, javaId);
        this.itemFrameInfoById.put(javaId, new ItemFrameInfo(position, facingDirection));
    }

    public void removeItemFrame(final ChunkPosition chunkPos) {
        final List<BlockPosition> toRemove = new ArrayList<>();
        for (BlockPosition position : this.itemFrames.keySet()) {
            if (position.x() >> 4 == chunkPos.chunkX() && position.z() >> 4 == chunkPos.chunkZ()) {
                toRemove.add(position);
            }
        }
        for (BlockPosition position : toRemove) {
            this.removeItemFrame(position);
        }
    }

    public void tick() {
        for (Entity entity : this.entities.values()) {
            if (entity != this.clientPlayerEntity) {
                entity.tick();
            }
        }
    }

    public void prepareForRespawn() {
        final RidingTracker riding = this.user().get(RidingTracker.class);
        if (riding != null) {
            riding.reset();
        }
        for (Entity entity : this.entities.values()) {
            entity.remove();
        }
    }

    public Entity getEntityByRid(final long runtimeId) {
        return this.entities.get(this.runtimeIdToUniqueId.get(runtimeId));
    }

    public Entity getEntityByUid(final long uniqueId) {
        return this.entities.get(uniqueId);
    }

    public Entity getEntityByJid(final int javaId) {
        return this.entities.get(this.javaIdToUniqueId.get(javaId));
    }

    public Collection<Entity> getEntities() {
        return this.entities.values();
    }

    public ClientPlayerEntity getClientPlayer() {
        return this.clientPlayerEntity;
    }

    public boolean isEmpty() {
        return this.entities.isEmpty() || (this.entities.size() == 1 && this.entities.containsKey(this.clientPlayerEntity.uniqueId()));
    }

    public int getNextJavaEntityId() {
        return ID_COUNTER.getAndIncrement();
    }

}
