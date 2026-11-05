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
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.java.PlayerTeamMethod;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamCollisionRule;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamVisibility;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.PlayerVisualState;
import net.raphimc.viabedrock.experimental.storage.GlowProjectionTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerEntity extends LivingEntity {

    protected PlayerAbilities abilities;
    private String teamPrefix = "";
    private boolean teamNameTagVisible = true;
    private final PlayerVisualState visualState = new PlayerVisualState();

    public PlayerEntity(final UserConnection user, final long runtimeId, final int javaId, final UUID javaUuid, final PlayerAbilities abilities) {
        super(user, abilities.entityUniqueId(), runtimeId, "minecraft:player", javaId, javaUuid, EntityTypes1_21_11.PLAYER);

        this.abilities = abilities;
    }

    public final void createTeam() {
        final PacketWrapper setPlayerTeam = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user);
        setPlayerTeam.write(Types.STRING, "vb_" + this.javaId); // 队伍名称
        setPlayerTeam.write(Types.BYTE, (byte) PlayerTeamMethod.ADD.ordinal()); // 模式
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("vb_" + this.javaId)); // 显示名称
        setPlayerTeam.write(Types.BYTE, (byte) 3); // 标志位
        setPlayerTeam.write(Types.VAR_INT, TeamVisibility.ALWAYS.ordinal()); // 名称标签可见性
        setPlayerTeam.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal()); // 碰撞规则
        setPlayerTeam.write(Types.VAR_INT, this.glowTeamColorOrdinal()); // 颜色
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // 前缀
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // 后缀
        setPlayerTeam.write(Types.STRING_ARRAY, new String[]{this.javaTeamMemberName()}); // 玩家
        setPlayerTeam.send(BedrockProtocol.class);
    }

    protected String javaTeamMemberName() {
        return StringUtil.encodeUUID(this.javaUuid);
    }

    public final void updateName(final String name) {
        this.setName(name);
        // 裁剪空行，使单行的队伍前缀不会带首尾换行
        // （Java 客户端会把这种换行渲染成缺字形方框）。多行追踪器从实体数据中读取
        // 原始存储的名称，并把额外行挂到虚拟盔甲架上。
        // Java 的队伍前缀无法渲染内部换行，因此这里只保留最下面一行。
        final String trimmed = TextUtil.trimBlankLines(name);
        final String prefix = TextUtil.lastLine(trimmed);
        final boolean hasVisibleName = prefix != null && !TextUtil.stripFormatting(prefix).isEmpty();
        this.updateTeamPresentation(hasVisibleName ? prefix : "", hasVisibleName);
    }

    public final void updateTeamPresentation(final String prefix, final boolean nameTagVisible) {
        this.teamPrefix = prefix;
        this.teamNameTagVisible = nameTagVisible;
        final PacketWrapper setPlayerTeam = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user);
        setPlayerTeam.write(Types.STRING, "vb_" + this.javaId); // 队伍名称
        setPlayerTeam.write(Types.BYTE, (byte) PlayerTeamMethod.CHANGE.ordinal()); // 模式
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("vb_" + this.javaId)); // 显示名称
        setPlayerTeam.write(Types.BYTE, (byte) 3); // 标志位
        setPlayerTeam.write(Types.VAR_INT, (nameTagVisible ? TeamVisibility.ALWAYS : TeamVisibility.NEVER).ordinal()); // 名称标签可见性
        setPlayerTeam.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal()); // 碰撞规则
        setPlayerTeam.write(Types.VAR_INT, this.glowTeamColorOrdinal()); // 颜色
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt(prefix)); // 前缀
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // 后缀
        setPlayerTeam.send(BedrockProtocol.class);
    }

    public final void refreshGlowTeam() {
        this.updateTeamPresentation(this.teamPrefix, this.teamNameTagVisible);
    }

    private int glowTeamColorOrdinal() {
        final GlowProjectionTracker tracker = this.user.get(GlowProjectionTracker.class);
        return tracker == null ? TextFormatting.RESET.getOrdinal() : tracker.colorOrdinal(this.uniqueId());
    }

    public final void sendInitialEntityData() {
        final List<EntityData> entityData = new ArrayList<>();
        entityData.add(new EntityData(this.getJavaEntityDataIndex(EntityDataFields.PLAYER_MODE_CUSTOMISATION), VersionedTypes.V26_1.entityDataTypes.byteType, (byte) 0xFF));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user);
        setEntityData.write(Types.VAR_INT, this.javaId); // 实体 id
        setEntityData.write(VersionedTypes.V26_1.entityDataList, entityData); // 实体数据
        setEntityData.send(BedrockProtocol.class);
    }

    public final void updateEntityProperties(final EntityProperties properties) {
        final GameSessionStorage gameSession = this.user.get(GameSessionStorage.class);
        if (gameSession == null) {
            return;
        }
        final int propertyIndex = gameSession.getPlayerEntityPropertyIndex(ViaBedrockUtilityInterface.CUSTOM_SPECTATOR_PROPERTY);
        if (propertyIndex < 0 || !properties.intProperties().containsKey(propertyIndex)) {
            return;
        }

        this.visualState.updateCustomSpectator(properties, propertyIndex).ifPresent(next ->
                ViaBedrockUtilityInterface.sendPlayerVisualState(this.user, this.javaUuid, next));
    }

    @Override
    public void remove() {
        super.remove();

        final PacketWrapper setPlayerTeam = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user);
        setPlayerTeam.write(Types.STRING, "vb_" + this.javaId); // 队伍名称
        setPlayerTeam.write(Types.BYTE, (byte) PlayerTeamMethod.REMOVE.ordinal()); // 模式
        setPlayerTeam.send(BedrockProtocol.class);
    }

    @Override
    public float eyeOffset() {
        return 1.62F;
    }

    public PlayerAbilities abilities() {
        return this.abilities;
    }

    public void setAbilities(final PlayerAbilities abilities) {
        this.abilities = abilities;
    }

    @Override
    protected boolean translateEntityData(final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        switch (id) {
            case NAME, NAME_RAW_TEXT -> {
                final String name = (String) entityData.getValue();
                this.updateName(name != null ? name : "");
            }
            case NAMETAG_ALWAYS_SHOW -> {
                final byte alwaysShow = (byte) entityData.getValue();
                final String currentName = TextUtil.lastLine(TextUtil.trimBlankLines(this.name()));
                final boolean hasVisibleName = alwaysShow == 1 && currentName != null && !TextUtil.stripFormatting(currentName).isEmpty();
                this.updateTeamPresentation(hasVisibleName ? currentName : "", hasVisibleName);
            }
        }
        return super.translateEntityData(id, entityData, javaEntityData);
    }

    @Override
    protected boolean translateAttribute(final EntityAttribute attribute, final PacketWrapper javaAttributes, final AtomicInteger attributeCount, final List<EntityData> javaEntityData) {
        return switch (attribute.name()) {
            case "minecraft:absorption" -> {
                javaEntityData.add(new EntityData(this.getJavaEntityDataIndex(EntityDataFields.PLAYER_ABSORPTION), VersionedTypes.V26_1.entityDataTypes.floatType, attribute.computeClampedValue()));
                javaAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.MAX_ABSORPTION)); // 属性 id
                javaAttributes.write(Types.DOUBLE, (double) attribute.maxValue()); // 基础值
                javaAttributes.write(Types.VAR_INT, 0); // 修饰符数量
                attributeCount.incrementAndGet();
                yield true;
            }
            case "minecraft:luck" -> {
                javaAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.LUCK)); // 属性 id
                javaAttributes.write(Types.DOUBLE, (double) attribute.computeClampedValue()); // 基础值
                javaAttributes.write(Types.VAR_INT, 0); // 修饰符数量
                attributeCount.incrementAndGet();
                yield true;
            }
            case "minecraft:player.hunger", "minecraft:player.saturation", "minecraft:player.experience", "minecraft:player.level", "minecraft:player.exhaustion" ->
                    true; // 非客户端玩家实体忽略
            default -> super.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData);
        };
    }

}
