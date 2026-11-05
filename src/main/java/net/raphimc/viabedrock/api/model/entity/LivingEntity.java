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
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AbilitiesIndex;
import net.raphimc.viabedrock.protocol.data.enums.java.UpdateMobEffectFlag;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import net.raphimc.viabedrock.protocol.model.EntityEffect;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class LivingEntity extends Entity {

    protected final Map<String, EntityAttribute> attributes = new HashMap<>();
    protected final Map<String, EntityEffect> effects = new HashMap<>();

    public LivingEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType, final Integer customJavaTypeId) {
        super(user, uniqueId, runtimeId, type, javaId, javaUuid, javaType, customJavaTypeId);
        this.attributes.put("minecraft:health", new EntityAttribute("minecraft:health", 20F, 0, 20F));
    }

    public LivingEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final UUID javaUuid, final EntityTypes1_21_11 javaType) {
        this(user, uniqueId, runtimeId, type, javaId, javaUuid, javaType, null);
    }

    @Override
    public void tick() {
        super.tick();

        Set<String> effectsToRemove = null;
        for (EntityEffect effect : this.effects.values()) {
            if (effect.duration().get() != -1 && effect.duration().decrementAndGet() <= 0) {
                if (effectsToRemove == null) {
                    effectsToRemove = new HashSet<>();
                }
                effectsToRemove.add(effect.identifier());
            }
        }
        // 基岩客户端在客户端侧移除效果，但 Java 版不会，因此需要为每个效果发送移除包
        if (effectsToRemove != null) {
            for (String identifier : effectsToRemove) {
                this.removeExpiredEffect(identifier);
            }
        }
    }

    protected void removeExpiredEffect(final String identifier) {
        final PacketWrapper removeMobEffect = PacketWrapper.create(ClientboundPackets26_1.REMOVE_MOB_EFFECT, this.user);
        this.removeEffect(identifier, removeMobEffect);
        removeMobEffect.send(BedrockProtocol.class);
    }

    public final void sendAttribute(final String name) {
        final EntityAttribute attribute = this.attributes.get(name);
        if (attribute != null) {
            this.updateAttributes(new EntityAttribute[]{attribute});
        }
    }

    public final void updateAttributes(final EntityAttribute[] attributes) {
        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, this.user);
        this.updateAttributes(attributes, updateAttributes);
        updateAttributes.send(BedrockProtocol.class);
    }

    public final void updateAttributes(final EntityAttribute[] attributes, final PacketWrapper javaAttributes) {
        javaAttributes.write(Types.VAR_INT, this.javaId); // 实体 id
        javaAttributes.write(Types.VAR_INT, 0); // 属性数量
        final AtomicInteger attributeCount = new AtomicInteger(0);
        final List<EntityData> javaEntityData = new ArrayList<>();
        for (EntityAttribute attribute : attributes) {
            if (attribute.name().equals("minecraft:health") && this instanceof PlayerEntity player && player.abilities().getBooleanValue(AbilitiesIndex.Invulnerable)) {
                final EntityAttribute oldAttribute = this.attributes.get(attribute.name());
                if (attribute.computeClampedValue() <= oldAttribute.computeClampedValue()) continue;
            }
            this.attributes.put(attribute.name(), attribute);
            if (!this.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData)) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unknown entity attribute: " + attribute.name() + " for entity type: " + this.javaType());
            }
        }
        if (attributeCount.get() > 0) {
            javaAttributes.set(Types.VAR_INT, 1, attributeCount.get());
        }
        if (!javaEntityData.isEmpty()) {
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
            setEntityData.write(Types.VAR_INT, this.javaId); // 实体 id
            setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // 实体数据
            setEntityData.send(BedrockProtocol.class);
        }
    }

    public final void sendEffects() {
        for (EntityEffect effect : this.effects.values()) {
            final PacketWrapper updateMobEffect = PacketWrapper.create(ClientboundPackets26_1.UPDATE_MOB_EFFECT, this.user);
            this.updateEffect(effect, updateMobEffect);
            updateMobEffect.send(BedrockProtocol.class);
        }
    }

    public final void updateEffect(final EntityEffect effect, final PacketWrapper javaEffect) {
        this.effects.put(effect.identifier(), effect);
        javaEffect.write(Types.VAR_INT, this.javaId); // 实体 id
        javaEffect.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEffects().get(BedrockProtocol.MAPPINGS.getBedrockToJavaEffects().get(effect.identifier()))); // 效果 id
        javaEffect.write(Types.VAR_INT, effect.amplifier()); // 倍率
        javaEffect.write(Types.VAR_INT, effect.duration().get() != -1 ? Math.max(effect.duration().get(), 0) : -1); // 持续时间
        byte flags = 0;
        if (effect.ambient()) flags |= UpdateMobEffectFlag.AMBIENT.getBit();
        if (effect.showParticles()) flags |= UpdateMobEffectFlag.VISIBLE.getBit();
        javaEffect.write(Types.BYTE, flags); // 标志位
    }

    public final void removeEffect(final String identifier, final PacketWrapper javaEffect) {
        this.effects.remove(identifier);
        javaEffect.write(Types.VAR_INT, this.javaId); // 实体 id
        javaEffect.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEffects().get(BedrockProtocol.MAPPINGS.getBedrockToJavaEffects().get(identifier))); // 效果 id
    }

    public final void clearEffects() {
        this.effects.clear();
    }

    public boolean isDead() {
        return this.attributes.get("minecraft:health").computeClampedValue() <= 0F;
    }

    public void setHealth(final float health) {
        this.attributes.put("minecraft:health", this.attributes.get("minecraft:health").withValue(health));
    }

    public Map<String, EntityAttribute> attributes() {
        return this.attributes;
    }

    public Map<String, EntityEffect> effects() {
        return this.effects;
    }

    protected boolean translateAttribute(final EntityAttribute attribute, final PacketWrapper javaAttributes, final AtomicInteger attributeCount, final List<EntityData> javaEntityData) {
        return switch (attribute.name()) {
            case "minecraft:attack_damage", "minecraft:knockback_resistance" -> {
                javaAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(switch (attribute.name()) {
                    case "minecraft:attack_damage" -> Attributes.ATTACK_DAMAGE;
                    case "minecraft:knockback_resistance" -> Attributes.KNOCKBACK_RESISTANCE;
                    default -> throw new IllegalStateException("Unhandled entity attribute: " + attribute.name());
                })); // 属性 id
                javaAttributes.write(Types.DOUBLE, (double) attribute.computeClampedValue()); // 基础值
                javaAttributes.write(Types.VAR_INT, 0); // 修饰符数量
                attributeCount.incrementAndGet();
                yield true;
            }
            case "minecraft:movement" -> {
                final JavaMovementAttribute movement = javaMovementAttribute(attribute);
                javaAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.MOVEMENT_SPEED)); // 属性 id
                javaAttributes.write(Types.DOUBLE, (double) movement.baseValue()); // 基础值
                javaAttributes.write(Types.VAR_INT, movement.sprinting() ? 1 : 0); // 修饰符数量
                if (movement.sprinting()) {
                    javaAttributes.write(Types.STRING, "minecraft:sprinting"); // 修饰符 id
                    javaAttributes.write(Types.DOUBLE, 0.3D); // 数量
                    javaAttributes.write(Types.BYTE, (byte) 2); // 加法、乘法、总计
                }
                attributeCount.incrementAndGet();
                yield true;
            }
            case "minecraft:health" -> {
                javaEntityData.add(new EntityData(this.getJavaEntityDataIndex(EntityDataFields.HEALTH), VersionedTypes.V26_1.entityDataTypes.floatType, attribute.computeClampedValue()));
                javaAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.MAX_HEALTH)); // 属性 id
                javaAttributes.write(Types.DOUBLE, (double) attribute.maxValue()); // 基础值
                javaAttributes.write(Types.VAR_INT, 0); // 修饰符数量
                attributeCount.incrementAndGet();
                yield true;
            }
            case "minecraft:absorption", "minecraft:follow_range", "minecraft:luck" -> true; // 对通用实体忽略
            case "minecraft:lava_movement", "minecraft:underwater_movement" -> true; // 暂时忽略，因为 Java 版没有这些属性
            default -> false;
        };
    }

    static JavaMovementAttribute javaMovementAttribute(final EntityAttribute attribute) {
        // Bedrock 当前值已包含属性修饰符，而 Java 会单独应用疾跑修饰符。
        // 这里只还原已识别的疾跑倍率，其他修饰符仍保留在基础值中。
        float baseValue = attribute.computeClampedValue();
        boolean sprinting = false;
        for (EntityAttribute.Modifier modifier : attribute.modifiers()) {
            if (!isSprintingModifier(modifier)) {
                continue;
            }

            final float multiplier = 1F + modifier.amount();
            if (Float.isFinite(multiplier) && multiplier > 0F) {
                baseValue /= multiplier;
                sprinting = true;
            }
        }
        return new JavaMovementAttribute(baseValue, sprinting);
    }

    static boolean isSprintingModifier(final EntityAttribute.Modifier modifier) {
        return modifier.operation() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AttributeModifierOperation.OPERATION_MULTIPLY_TOTAL
                && modifier.operand() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AttributeOperands.OPERAND_CURRENT
                && (modifier.id().equalsIgnoreCase("d208fc00-42aa-4aad-9276-d5446530de43")
                || modifier.name().equalsIgnoreCase("Sprinting speed boost"));
    }

    record JavaMovementAttribute(float baseValue, boolean sprinting) {
    }

}
