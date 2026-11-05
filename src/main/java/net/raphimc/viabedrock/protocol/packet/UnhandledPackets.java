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
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.storage.EntityPropertyStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.PacketSyncStorage;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.Map;
import java.util.logging.Level;

public class UnhandledPackets {

    /**
     * 存在于 {@link ClientboundBedrockPackets} 但没有
     * Java 映射的服务端→客户端类型。ViaVersion 透传会把基岩字节当作 Java 包泄漏。
     */
    static final ClientboundBedrockPackets[] NETEASE_UNMAPPED_CLIENTBOUND = {
            ClientboundBedrockPackets.SERVER_PLAYER_POST_MOVE_POSITION,
            ClientboundBedrockPackets.HURT_ARMOR,
            ClientboundBedrockPackets.SIMPLE_EVENT,
            ClientboundBedrockPackets.LEGACY_TELEMETRY_EVENT,
            ClientboundBedrockPackets.SPAWN_EXPERIENCE_ORB,
            ClientboundBedrockPackets.SHOW_CREDITS,
            ClientboundBedrockPackets.UPDATE_EQUIP,
            ClientboundBedrockPackets.ADD_BEHAVIOR_TREE,
            ClientboundBedrockPackets.SHOW_STORE_OFFER,
            ClientboundBedrockPackets.AUTOMATION_CLIENT_CONNECT,
            ClientboundBedrockPackets.SET_LAST_HURT_BY,
            ClientboundBedrockPackets.SERVER_SETTINGS_RESPONSE,
            ClientboundBedrockPackets.ON_SCREEN_TEXTURE_ANIMATION,
            ClientboundBedrockPackets.STRUCTURE_TEMPLATE_DATA_RESPONSE,
            ClientboundBedrockPackets.MULTIPLAYER_SETTINGS,
            ClientboundBedrockPackets.POSITION_TRACKING_DB_SERVER_BROADCAST,
            ClientboundBedrockPackets.DEBUG_INFO,
            ClientboundBedrockPackets.MOTION_PREDICTION_HINTS,
            ClientboundBedrockPackets.DEBUG_RENDERER,
            ClientboundBedrockPackets.ADD_VOLUME_ENTITY,
            ClientboundBedrockPackets.REMOVE_VOLUME_ENTITY,
            ClientboundBedrockPackets.SIMULATION_TYPE,
            ClientboundBedrockPackets.TICKING_AREAS_LOAD_STATUS,
            ClientboundBedrockPackets.AGENT_ACTION_EVENT,
            ClientboundBedrockPackets.CHANGE_MOB_PROPERTY,
            ClientboundBedrockPackets.EDITOR_NETWORK,
            ClientboundBedrockPackets.FEATURE_REGISTRY,
            ClientboundBedrockPackets.SERVER_STATS,
            ClientboundBedrockPackets.GAME_TEST_RESULTS,
            ClientboundBedrockPackets.UNLOCKED_RECIPES,
            ClientboundBedrockPackets.AGENT_ANIMATION,
            ClientboundBedrockPackets.AWARD_ACHIEVEMENT,
            ClientboundBedrockPackets.JIGSAW_STRUCTURE_DATA,
            ClientboundBedrockPackets.MOVEMENT_EFFECT,
            ClientboundBedrockPackets.PLAYER_UPDATE_ENTITY_OVERRIDES,
            ClientboundBedrockPackets.PLAYER_LOCATION,
            ClientboundBedrockPackets.CONTROL_SCHEME_SET,
            ClientboundBedrockPackets.PRIMITIVE_SHAPES,
            ClientboundBedrockPackets.DATA_STORE,
            ClientboundBedrockPackets.DATA_DRIVEN_UI_SHOW_SCREEN,
            ClientboundBedrockPackets.DATA_DRIVEN_UI_CLOSE_SCREEN,
            ClientboundBedrockPackets.DATA_DRIVEN_UI_RELOAD,
            ClientboundBedrockPackets.VOXEL_SHAPES,
            ClientboundBedrockPackets.LOCATOR_BAR,
            ClientboundBedrockPackets.SYNC_WORLD_CLOCKS,
            ClientboundBedrockPackets.ATTRIBUTE_LAYER_SYNC,
            ClientboundBedrockPackets.SERVER_STORE_INFO,
            ClientboundBedrockPackets.SERVER_PRESENCE_INFO
    };

    public static void register(final BedrockProtocol protocol) {
        // MOT Player.kill() + doImmediateRespawn 会在玩家仍处于死亡状态时写入
        // SetHealthPacket(maxHealth)。原版基岩会忽略该数据包。
        // Java 1.21.11 在 showDeathScreen=false 时仍需要 PLAYER_COMBAT_KILL /
        // SET_HEALTH(0) 才会发出 PERFORM_RESPAWN；Java 客户端自己会
        // 发送 ClientReadyToSpawn。不要在这里自动回复，否则 MOT 会看到两个
        // ClientReadyToSpawn 数据包。
        protocol.registerClientbound(ClientboundBedrockPackets.SET_HEALTH, null, wrapper -> {
            wrapper.cancel();
            final int health = DeathSyncLayout.readSetHealth(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (!DeathSyncLayout.isImmediateRespawnHealth(health)) {
                return;
            }
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null || entityTracker.getClientPlayer() == null) {
                return;
            }
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (!clientPlayer.isInitiallySpawned()) {
                return;
            }
            if (!clientPlayer.isDead()) {
                clientPlayer.setHealth(0F);
                clientPlayer.sendAttribute("minecraft:health");
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SYNC_ENTITY_PROPERTY, null, wrapper -> {
            wrapper.cancel();
            try {
                final CompoundTag registry = wrapper.read(BedrockTypes.BIG_ENDIAN_NETWORK_COMPOUND_TAG);
                EntityPropertyStorage.getOrCreate(wrapper.user()).register(registry);
                PacketLeftoverLayout.discardUnreadInput(wrapper);
            } catch (final RuntimeException exception) {
                Via.getPlatform().getLogger().log(Level.WARNING, "Unable to parse MOT SYNC_ENTITY_PROPERTY", exception);
            }
        });
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.PHOTO_TRANSFER); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.SHOW_PROFILE);
        protocol.cancelClientbound(ClientboundBedrockPackets.LAB_TABLE); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.EDUCATION_SETTINGS); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.EMOTE); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.CODE_BUILDER); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.EMOTE_LIST); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.PLAYER_FOG); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.EDU_URI_RESOURCE); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.LESSON_PROGRESS); // 不适用（教育版）
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_HUD); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.CURRENT_STRUCTURE_FEATURE); // 无用
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST_PRESETS); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.PLAYER_VIDEO_CAPTURE); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.GRAPHICS_OVERRIDE_PARAMETER); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.TEXTURE_SHIFT); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_SPLINE); // Java 版不可能出现
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_AIM_ASSIST_ACTOR_PRIORITY); // Java 版不可能出现
        // ViaBedrock 生成的 MinecraftPacketIds 遗漏的 MOT 数据包池 ID。
        // 没有注册类型时它们会以“Received unknown packet”出现并
        // 中止当前批次。MOT 860 从不构造 301/319，但 173/197
        // 存在于池中；取消它们，以免残留字节踢掉 Java。
        protocol.cancelClientbound(ClientboundBedrockPackets.PHOTO_INFO_REQUEST);
        protocol.cancelClientbound(ClientboundBedrockPackets.CLIENT_CHEAT_ABILITY);
        // MOT ProtocolInfo 中存在于 MinecraftPacketIds 但
        // 没有 Java 映射的遗留 ID。取消，以免未知 ID 中止批次。
        protocol.cancelClientbound(ClientboundBedrockPackets.REFRESH_ENTITLEMENTS);
        protocol.cancelClientbound(ClientboundBedrockPackets.RESOURCE_PACKS_READY_FOR_VALIDATION);
        protocol.cancelClientbound(ClientboundBedrockPackets.COMPRESSED_BIOME_DEFINITION_LIST);
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_MOVEMENT_AUTHORITY);
        protocol.cancelClientbound(ClientboundBedrockPackets.PARTY_CHANGED);
        protocol.cancelClientbound(ClientboundBedrockPackets.TICK_SYNC);
        protocol.cancelClientbound(ClientboundBedrockPackets.LEVEL_SOUND_EVENT_V1);
        protocol.cancelClientbound(ClientboundBedrockPackets.ENTITY_FALL);
        protocol.cancelClientbound(ClientboundBedrockPackets.CRAFTING_EVENT);
        protocol.cancelClientbound(ClientboundBedrockPackets.ADVENTURE_SETTINGS);
        protocol.cancelClientbound(ClientboundBedrockPackets.ITEM_FRAME_DROP_ITEM);
        protocol.cancelClientbound(ClientboundBedrockPackets.LEVEL_SOUND_EVENT_V2);
        protocol.cancelClientbound(ClientboundBedrockPackets.LECTERN_UPDATE);
        protocol.cancelClientbound(ClientboundBedrockPackets.VIDEO_STREAM_CONNECT);
        protocol.cancelClientbound(ClientboundBedrockPackets.SCRIPT_CUSTOM_EVENT);
        protocol.cancelClientbound(ClientboundBedrockPackets.UPDATE_BLOCK_PROPERTIES);
        protocol.cancelClientbound(ClientboundBedrockPackets.UPDATE_SOUND_DATA);
        protocol.cancelClientbound(ClientboundBedrockPackets.SEND_PARTY_DESTINATION_COOKIE);
        protocol.cancelClientbound(ClientboundBedrockPackets.PARTY_DESTINATION_COOKIE_RESPONSE);
        // MOT 860 的 processLogin() 总会发送 TrimData + SyncEntityProperty。
        // Java 没有盔甲纹饰调色板 / 演员属性包；取消它们，以免残留
        // 字节中止重放的加入批次。
        protocol.cancelClientbound(ClientboundBedrockPackets.TRIM_DATA);
        // 开启实验性功能时 CameraInterface 会覆写此项。
        protocol.cancelClientbound(ClientboundBedrockPackets.CAMERA_PRESETS);
        // MOT 860 有 SetPlayerInventoryOptions (307)，但 Java 没有物品栏布局
        // 数据包。取消，以免残留字节中止加入 / 物品栏批次。
        protocol.cancelClientbound(ClientboundBedrockPackets.SET_PLAYER_INVENTORY_OPTIONS);
        // 未映射的服务端→客户端 ID 否则会作为 Java 包透传并
        // 踢掉 1.21.11（“Received unknown packet”/ 残留字节中止批次）。
        // 仅 JE 丢弃：基岩客户端不使用 ViaBedrock。
        for (final ClientboundBedrockPackets leftover : NETEASE_UNMAPPED_CLIENTBOUND) {
            protocol.cancelClientbound(leftover);
        }

        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_21_9.KEEP_ALIVE, null, UnhandledPackets::handleJavaKeepAlive);
        protocol.cancelServerbound(ServerboundPackets26_1.CHAT_ACK);
        protocol.cancelServerbound(ServerboundPackets26_1.CHAT_SESSION_UPDATE);
        protocol.cancelServerbound(ServerboundPackets26_1.COOKIE_RESPONSE);
        protocol.cancelServerbound(ServerboundPackets26_1.DEBUG_SAMPLE_SUBSCRIPTION);
        protocol.registerServerbound(ServerboundPackets26_1.KEEP_ALIVE, null, UnhandledPackets::handleJavaKeepAlive);
        protocol.cancelServerbound(ServerboundPackets26_1.SET_TEST_BLOCK);
        protocol.cancelServerbound(ServerboundPackets26_1.TEST_INSTANCE_BLOCK_ACTION);
    }

    static void handleJavaKeepAlive(final PacketWrapper wrapper) {
        final long id = wrapper.read(Types.LONG);
        wrapper.cancel();
        final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
        if (packetSyncStorage == null) {
            return;
        }
        final Long sentNanos = packetSyncStorage.consumeKeepAlive(id);
        if (sentNanos == null) {
            return;
        }
        final long nowNanos = System.nanoTime();
        final int serverTransportLatencyMillis = Via.getManager().getProviders().get(NettyPipelineProvider.class)
                .getServerTransportLatencyMillis(wrapper.user());
        packetSyncStorage.updateLatency(nowNanos - sentNanos, serverTransportLatencyMillis);
        publishJavaPlayerLatency(wrapper, packetSyncStorage, nowNanos);
    }

    private static void publishJavaPlayerLatency(final PacketWrapper wrapper, final PacketSyncStorage packetSyncStorage, final long nowNanos) {
        if (wrapper.user().getProtocolInfo().getServerState() != State.PLAY || !packetSyncStorage.shouldPublishLatency(nowNanos)) {
            return;
        }
        final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
        if (entityTracker == null || entityTracker.getClientPlayer() == null) {
            return;
        }
        final java.util.UUID javaUuid = entityTracker.getClientPlayer().javaUuid();
        final PlayerListStorage playerListStorage = wrapper.user().get(PlayerListStorage.class);
        if (playerListStorage == null || !playerListStorage.containsPlayer(javaUuid)) {
            return;
        }
        PacketFactory.createJavaPlayerLatencyUpdate(wrapper.user(), javaUuid, packetSyncStorage.latencyMillis()).send(BedrockProtocol.class);
        packetSyncStorage.markLatencyPublished(nowNanos);
        ExperimentalFeatures.dispatchPlayerLatenciesUpdated(wrapper.user(), Map.of(javaUuid, packetSyncStorage.latencyMillis()));
    }

}
