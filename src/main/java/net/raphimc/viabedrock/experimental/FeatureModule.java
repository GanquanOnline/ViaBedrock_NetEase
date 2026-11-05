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
package net.raphimc.viabedrock.experimental;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 模块化实验性功能实现的接口。
 * 每个功能模块独立注册，并接收来自协议转换管道的生命周期回调。
 */
public interface FeatureModule {

    /**
     * 在 BedrockMappingData.load() 的各阶段被调用，让模块能在初始化序列中的
     * 正确时机加载自定义映射。
     */
    default void onMappingsLoad(BedrockMappingData data, MappingLoadPhase phase) {
    }

    /** 在 BedrockProtocol.registerPackets() 期间被调用，用于注册自定义数据包转换器。 */
    default void onPacketRegistration(BedrockProtocol protocol) {
    }

    /** 在 BedrockProtocol.init() 期间被调用，用于注册按连接划分的存储。 */
    default void onStorageRegistration(UserConnection user) {
    }

    /** 在实体被加入 EntityTracker 之后调用。 */
    default void onEntityAdded(UserConnection user, Entity entity) {
    }

    /** 在实体从 EntityTracker 移除之前调用。 */
    default void onEntityRemoved(UserConnection user, Entity entity) {
    }

    /** 在 ADD_ENTITY/ADD_PLAYER 完成转换，且生成包中的基岩实体链接可用之后调用。 */
    default void onEntityLinks(UserConnection user, EntityLink[] links) {
    }

    /** 在实体存储了一批基岩实体数据之后调用。 */
    default void onEntityDataChanged(UserConnection user, Entity entity, EntityData[] entityData) {
    }

    /** 在实体的位置/旋转更新被应用之后调用。 */
    default void onEntityMoved(UserConnection user, Entity entity) {
    }

    /** 在写入 PLAYER_AUTH_INPUT 时、公共字段计算完成之后、序列化之前调用。 */
    default void onPlayerAuthInput(UserConnection user, ClientPlayerEntity clientPlayer, PlayerAuthInputContext context) {
    }

    /** 在 Java 客户端注册自定义 payload 通道时调用。 */
    default void onChannelRegistered(UserConnection user, Set<String> channels) {
    }

    /**
     * 为一个基岩 PY_RPC payload 解析备用的 Java 自定义 payload 通道。
     * 使用第一个非 null 的结果；否则使用共享的 PY_RPC 通道。
     */
    default String resolveClientboundPyRpcChannel(byte[] data) {
        return null;
    }

    /** 判断某个玩家列表条目在 Java 玩家列表中是否可见。 */
    default boolean isPlayerListEntryListed(UserConnection user, UUID uuid, long entityUniqueId, String name) {
        return true;
    }

    /** 允许模块装饰 Java 玩家列表的显示名称。 */
    default Tag decoratePlayerListDisplayName(UserConnection user, UUID uuid, long entityUniqueId, String name, int latency, Tag displayName) {
        return displayName;
    }

    /** 在现有 Java 玩家列表条目的延迟值发布之后调用。 */
    default void onPlayerLatenciesUpdated(UserConnection user, Map<UUID, Integer> latencies) {
    }

    /** 在现有 Java 玩家列表条目的 JE/BE 身份标签变化之后调用。 */
    default void onPlayerIdentitiesUpdated(UserConnection user, Map<UUID, PlayerIdentity> identities) {
    }

    /** 在维度切换期间实体跟踪器被替换之前调用。 */
    default void onDimensionChange(UserConnection user) {
    }

    /**
     * 在维度切换期间调用，用于为 Java 客户端解析维度键。
     * 使用任意模块返回的第一个非 null 结果。
     *
     * @return 解析出的维度键，返回 null 表示使用默认值
     */
    default String resolveDimensionKey(Dimension dimension, ChunkTracker oldChunkTracker) {
        return null;
    }

    /**
     * 在处理 ADD_ENTITY 时调用，用于解析自定义实体类型。
     * 使用任意模块返回的第一个非 null 结果。
     *
     * @return 自定义 Entity 实例，返回 null 表示回退到默认解析
     */
    default Entity resolveEntity(UserConnection user, long uniqueId, long runtimeId, String type) {
        return null;
    }

    /** 在资源包堆叠设置完成并可供处理之后调用。 */
    default void onResourcePackStackSet(UserConnection user) {
    }

    /** 在 Java 客户端加载完转换后的资源包之后调用。 */
    default void onJavaResourcePackLoaded(UserConnection user) {
    }

    /**
     * 在收到来自 Java 客户端的自定义 payload 数据包时调用。
     * 第一个返回 true 的模块认领该数据包。
     *
     * @param channel 自定义 payload 通道
     *
     * @param wrapper 数据包包装（调用方已将其取消）
     *
     * @return 若本模块处理了该 payload 则为 true，返回 false 则传给下一个模块
     */
    default boolean handleCustomPayload(String channel, PacketWrapper wrapper) {
        return false;
    }

}
