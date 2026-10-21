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
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.rewriter.EntityMetadataRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.packet.LevelSoundEventLayout;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.DataItemType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SharedTypes_Legacy_LevelSoundEvent;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.BossEventOperationType;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.BossBarStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;

import java.math.BigInteger;
import java.util.*;
import java.util.logging.Level;

public class Entity {

    protected final UserConnection user;
    protected final long uniqueId;
    protected final long runtimeId;
    protected final String type;
    protected final int javaId;
    protected final UUID javaUuid;
    protected final EntityTypes1_21_11 javaType;
    protected final Integer customJavaTypeId; // null 表示使用 javaType.getId()

    /**
     * x, y, z
     */
    protected Position3f position;
    /**
     * pitch, yaw, headYaw
     */
    protected Position3f rotation = Position3f.ZERO;
    protected boolean onGround;
    protected final Map<ActorDataIDs, EntityData> entityData = new EnumMap<>(ActorDataIDs.class);
    protected EntityProperties entityProperties = EntityProperties.empty();
    protected long entityDataFrame;
    protected String name;
    protected int age;
    protected boolean hasBossBar;
    private Boolean javaSaddleEquipped;

    public UserConnection user() {
        return this.user;
    }

    public Entity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType, final Integer customJavaTypeId) {
        this.user = user;
        this.uniqueId = uniqueId;
        this.runtimeId = runtimeId;
        this.type = type;
        this.javaId = javaId;
        this.javaUuid = javaUuid;
        this.javaType = javaType;
        this.customJavaTypeId = customJavaTypeId;
    }

    public Entity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType) {
        this(user, uniqueId, runtimeId, type, javaId, javaUuid, javaType, null);
    }

    public void tick() {
        this.age++;
    }

    public void remove() {
        final BossBarStorage bossBars = this.user.get(BossBarStorage.class);
        final boolean clientHasBossBar = bossBars != null ? bossBars.remove(this.javaUuid) : this.hasBossBar;
        this.hasBossBar = false;
        if (clientHasBossBar) {
            final PacketWrapper bossEvent = PacketWrapper.create(ClientboundPackets26_1.BOSS_EVENT, this.user);
            bossEvent.write(Types.UUID, this.javaUuid()); // uuid
            bossEvent.write(Types.VAR_INT, BossEventOperationType.REMOVE.ordinal()); // operation
            bossEvent.send(BedrockProtocol.class);
        }
    }

    public final void updateEntityData(final EntityData[] entityData) {
        final List<EntityData> javaEntityData = new ArrayList<>();
        this.updateEntityData(entityData, javaEntityData);
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
        setEntityData.write(Types.VAR_INT, this.javaId); // entity id
        setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // entity data
        setEntityData.send(BedrockProtocol.class);
    }

    public final void updateEntityData(final EntityData[] entityData, final List<EntityData> javaEntityData) {
        // First pass: validate and store all entity data so translators see the latest values from the entire batch
        final List<Map.Entry<ActorDataIDs, EntityData>> validData = new ArrayList<>();
        for (EntityData data : entityData) {
            final ActorDataIDs dataId = ActorDataIDs.getByValue(data.id());
            if (dataId == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ActorDataIDs: " + data.id());
                continue;
            }
            final DataItemType expectedType = BedrockProtocol.MAPPINGS.getBedrockEntityDataTypes().get(dataId);
            if (expectedType != null && expectedType != ((EntityDataTypesBedrock) data.dataType()).dataItemType()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Discarding entity data " + dataId + " for entity type " + this.type + " due to unexpected data type: " + data.dataType());
                continue;
            }
            this.entityData.put(dataId, data);
            validData.add(Map.entry(dataId, data));
        }
        this.translateEntityDataBatch(validData, javaEntityData);
        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            EntityMetadataRewriter.rewriteEntityProperties(this, javaEntityData);
        }
        this.onEntityDataChanged();
        ExperimentalFeatures.dispatchEntityDataChanged(this.user, this, entityData);
    }

    final void translateEntityDataBatch(final List<Map.Entry<ActorDataIDs, EntityData>> validData,
                                        final List<EntityData> javaEntityData) {
        // Both words describe one logical flag set. The validation pass already stored the complete
        // batch, so translating either word once observes the final low/high values.
        boolean actorFlagsTranslated = false;
        for (Map.Entry<ActorDataIDs, EntityData> entry : validData) {
            if (entry.getKey() == ActorDataIDs.RESERVED_0 || entry.getKey() == ActorDataIDs.RESERVED_092) {
                if (actorFlagsTranslated) {
                    continue;
                }
                actorFlagsTranslated = true;
            }
            if (!this.translateEntityData(entry.getKey(), entry.getValue(), javaEntityData)) {
                // TODO: Log warning when entity data translation is fully implemented
                // ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unknown entity data: " + entry.getKey() + " for entity type: " + this.type);
            }
        }
    }

    public void playSound(final SharedTypes_Legacy_LevelSoundEvent soundEvent) {
        final PacketWrapper levelSoundEvent = PacketWrapper.create(ClientboundBedrockPackets.LEVEL_SOUND_EVENT, this.user);
        levelSoundEvent.write(BedrockTypes.UNSIGNED_VAR_INT, soundEvent.getValue()); // event
        levelSoundEvent.write(BedrockTypes.POSITION_3F, this.position); // position
        levelSoundEvent.write(BedrockTypes.VAR_INT, 0); // data
        levelSoundEvent.write(BedrockTypes.STRING, this.type); // entity identifier
        levelSoundEvent.write(Types.BOOLEAN, false); // is baby mob
        levelSoundEvent.write(Types.BOOLEAN, false); // is global sound
        LevelSoundEventLayout.writeTrailer(levelSoundEvent, -1L);
        levelSoundEvent.send(BedrockProtocol.class, false);
    }

    public float eyeOffset() {
        // MOT ADD/MOVE for boats already includes getBaseOffset() (0.375). GanAC then
        // subtracts the same offset. Writing raw MOT Y would place the JE AABB 0.375 too high.
        if ("minecraft:boat".equals(this.type) || "minecraft:chest_boat".equals(this.type)) {
            return 0.375F;
        }
        // MOT EntityMinecartAbstract.getBaseOffset() is 0.35. Same ADD/MOVE rule as boats.
        if (this.javaType != null && this.javaType.isOrHasParent(EntityTypes1_21_11.ABSTRACT_MINECART)) {
            return 0.35F;
        }
        return 0F;
    }

    public long uniqueId() {
        return this.uniqueId;
    }

    public long runtimeId() {
        return this.runtimeId;
    }

    public String type() {
        return this.type;
    }

    public int javaId() {
        return this.javaId;
    }

    public UUID javaUuid() {
        return this.javaUuid;
    }

    public EntityTypes1_21_11 javaType() {
        return this.javaType;
    }

    public int javaTypeId() {
        return this.customJavaTypeId != null ? this.customJavaTypeId : this.javaType.getId();
    }

    public Position3f position() {
        return this.position;
    }

    public void setPosition(final Position3f position) {
        this.position = position;
    }

    public Position3f rotation() {
        return this.rotation;
    }

    public void setRotation(final Position3f rotation) {
        this.rotation = rotation;
    }

    public boolean isOnGround() {
        return this.onGround;
    }

    public void setOnGround(final boolean onGround) {
        this.onGround = onGround;
    }

    public Map<ActorDataIDs, EntityData> entityData() {
        return this.entityData;
    }

    public EntityProperties entityProperties() {
        return this.entityProperties;
    }

    public void setEntityProperties(final EntityProperties entityProperties) {
        this.entityProperties = Objects.requireNonNull(entityProperties, "entityProperties");
    }

    public long entityDataFrame() {
        return this.entityDataFrame;
    }

    public void setEntityDataFrame(final long entityDataFrame) {
        this.entityDataFrame = entityDataFrame;
    }

    public Set<ActorFlags> entityFlags() {
        final EntityData flagsData = this.entityData.get(ActorDataIDs.RESERVED_0);
        final EntityData flags2Data = this.entityData.get(ActorDataIDs.RESERVED_092);
        final long flags = flagsData != null ? flagsData.<Long>value() : 0L;
        final long flags2 = flags2Data != null ? flags2Data.<Long>value() : 0L;
        return EnumUtil.getEnumSetFromBitmask(ActorFlags.class, flags, flags2, ActorFlags::getValue);
    }

    public final boolean hasEntityFlag(final ActorFlags flag) {
        if (flag == null || flag.getValue() < 0 || flag.getValue() >= Long.SIZE * 2) {
            return false;
        }

        final int bit = flag.getValue();
        final EntityData flagsData = this.entityData.get(bit < Long.SIZE ? ActorDataIDs.RESERVED_0 : ActorDataIDs.RESERVED_092);
        return flagsData != null && (flagsData.<Long>value() & (1L << (bit & (Long.SIZE - 1)))) != 0;
    }

    static BigInteger combineEntityFlags(final long flags, final long flags2) {
        return unsignedLong(flags).or(unsignedLong(flags2).shiftLeft(Long.SIZE));
    }

    private static BigInteger unsignedLong(final long value) {
        BigInteger result = BigInteger.valueOf(value & Long.MAX_VALUE);
        if (value < 0) {
            result = result.setBit(Long.SIZE - 1);
        }
        return result;
    }

    public String name() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public int age() {
        return this.age;
    }

    public boolean hasBossBar() {
        return this.hasBossBar;
    }

    public void setHasBossBar(final boolean hasBossBar) {
        this.hasBossBar = hasBossBar;
    }

    public Boolean javaSaddleEquipped() {
        return this.javaSaddleEquipped;
    }

    public void setJavaSaddleEquipped(final Boolean javaSaddleEquipped) {
        this.javaSaddleEquipped = javaSaddleEquipped;
    }

    public final int getJavaEntityDataIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields().get(this.javaType).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown java entity data field: " + fieldName + " for entity type: " + this.javaType);
        }
        return index;
    }

    protected boolean translateEntityData(final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            return EntityMetadataRewriter.rewrite(user, this, id, entityData, javaEntityData);
        }

        return false;
    }

    protected void onEntityDataChanged() {
    }

}
