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

import com.viaversion.nbt.tag.Tag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.util.Pair;
import net.lenni0451.mcstructs_bedrock.text.components.RootBedrockComponent;
import net.lenni0451.mcstructs_bedrock.text.components.TranslationBedrockComponent;
import net.lenni0451.mcstructs_bedrock.text.serializer.BedrockComponentSerializer;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.scoreboard.ScoreboardEntry;
import net.raphimc.viabedrock.api.model.scoreboard.ScoreboardObjective;
import net.raphimc.viabedrock.api.util.*;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.ObjectiveAction;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.BossEventOperationType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.CustomChatCompletionsAction;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ObjectiveCriteriaRenderType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public class HudPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_LIST, ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper -> {
            final PlayerListStorage playerListStorage = wrapper.user().get(PlayerListStorage.class);
            final SpectatorMenuProjection spectatorMenu = wrapper.user().get(SpectatorMenuProjection.class);
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final boolean varintEntryFlags = PlayerListLayout.usesVarintEntryFlags(BedrockWire.current());
            final byte rawAction;
            final int length;
            if (varintEntryFlags) {
                length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
                rawAction = length > 0 ? PlayerListLayout.readEntryAction(wrapper) : (byte) 0; // 空列表按添加处理
            } else {
                rawAction = wrapper.read(Types.BYTE); // 动作
                length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 长度
            }
            final PlayerListPacketType action = PlayerListPacketType.getByValue(rawAction);
            if (action == null) { // 动作无效时基岩客户端会崩溃
                throw new IllegalStateException("Unknown PlayerListPacketType: " + rawAction);
            }
            switch (action) {
                case Add -> {
                    final UUID[] uuids = new UUID[length];
                    final long[] entityUniqueIds = new long[length];
                    final String[] names = new String[length];
                    final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
                    wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_LISTED, PlayerInfoUpdateAction.UPDATE_LATENCY, PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME)); // 动作列表
                    wrapper.write(Types.VAR_INT, length); // 长度
                    for (int i = 0; i < length; i++) {
                        if (varintEntryFlags && i > 0 && !readMatchingEntryAction(wrapper, rawAction)) {
                            return;
                        }
                        uuids[i] = wrapper.read(BedrockTypes.UUID); // uuid
                        entityUniqueIds[i] = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID

                        // 重映射本地玩家 UUID：基岩服务器分配的 UUID 与登录时生成的 Java UUID 不同
                        final boolean localPlayer = entityTracker.getClientPlayer() != null
                                && entityTracker.getClientPlayer().uniqueId() == entityUniqueIds[i];
                        if (localPlayer) {
                            // 在覆盖之前先存下服务器分配的 UUID，这样 PLAYER_SKIN 处理器
                            // （它只有 UUID，没有 entityUniqueId）才能认出本地玩家。
                            entityTracker.getClientPlayer().setBedrockUuid(uuids[i]);
                            uuids[i] = entityTracker.getClientPlayer().javaUuid();
                        }

                        wrapper.write(Types.UUID, uuids[i]); // uuid
                        wrapper.write(Types.STRING, StringUtil.encodeUUID(uuids[i])); // 用户名
                        names[i] = TextUtil.toSingleLine(wrapper.read(BedrockTypes.STRING)); // 用户名
                        final String xuid = wrapper.read(BedrockTypes.STRING); // XUID
                        if (BedrockWire.applies(BedrockDeviation.PLAYER_LIST_PLAY_FAB_ID)) {
                            wrapper.read(BedrockTypes.STRING); // playFabId（2225 起从皮肤块搬到条目）
                        }
                        final String platformOnlineId = wrapper.read(BedrockTypes.STRING); // 平台在线 ID
                        final int deviceOs = wrapper.read(BedrockTypes.INT_LE); // 设备 OS
                        final SkinData skin = wrapper.read(BedrockTypes.SKIN); // 皮肤
                        final boolean isTeacher = wrapper.read(Types.BOOLEAN); // 是否为教师
                        final boolean isHost = wrapper.read(Types.BOOLEAN); // 是否为主机
                        final boolean isSubClient = wrapper.read(Types.BOOLEAN); // 是否为子客户端
                        wrapper.read(BedrockTypes.INT_LE); // 颜色 (argb)
                        final GameProfile.Property[] properties = new GameProfile.Property[]{
                                new GameProfile.Property("xuid", xuid),
                                new GameProfile.Property("platform_online_id", platformOnlineId),
                                new GameProfile.Property("device_os", String.valueOf(deviceOs)),
                                new GameProfile.Property("is_teacher", String.valueOf(isTeacher)),
                                new GameProfile.Property("is_host", String.valueOf(isHost)),
                                new GameProfile.Property("is_subclient", String.valueOf(isSubClient))
                        };
                        wrapper.write(Types.PROFILE_PROPERTY_ARRAY, properties); // 属性
                        final boolean listed = ExperimentalFeatures.isPlayerListEntryListed(wrapper.user(), uuids[i], entityUniqueIds[i], names[i]);
                        wrapper.write(Types.BOOLEAN, listed); // 是否列出
                        final int latency = localPlayer ? packetSyncStorage.latencyMillis() : playerListStorage.serverLatency(uuids[i]);
                        wrapper.write(Types.VAR_INT, latency); // 延迟
                        final Tag displayName = ExperimentalFeatures.decoratePlayerListDisplayName(wrapper.user(), uuids[i], entityUniqueIds[i], names[i], latency, TextUtil.stringToNbt(names[i]));
                        wrapper.write(Types.OPTIONAL_TAG, displayName); // 显示名称
                        playerListStorage.putJavaProfile(new PlayerListStorage.JavaProfile(
                                uuids[i],
                                StringUtil.encodeUUID(uuids[i]),
                                properties,
                                net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode.SURVIVAL,
                                listed,
                                latency,
                                displayName
                        ));

                        if (localPlayer) {
                            playerListStorage.putIdentity(uuids[i], PlayerIdentity.javaEdition(PlayerIdentity.javaVersionName(wrapper.user())));
                            if (latency != PacketSyncStorage.UNKNOWN_LATENCY) {
                                packetSyncStorage.markLatencyPublished(System.nanoTime());
                            }
                        } else {
                            playerListStorage.markLatencyPublished(uuids[i], latency);
                        }

                        Via.getManager().getProviders().get(SkinProvider.class).setSkin(wrapper.user(), uuids[i], skin);
                    }
                    if (!varintEntryFlags) {
                        try {
                            for (int i = 0; i < length; i++) {
                                wrapper.read(Types.BOOLEAN); // 受信任皮肤
                            }
                        } catch (InformativeException ignored) { // 基岩客户端会静默忽略读取错误
                        }
                    }
                    try {
                        PlayerListLayout.skipNetEaseAddTrailer(wrapper, length);
                    } catch (RuntimeException ignored) { // MOT 的 bloom 尾部在某些构建中是可选的
                    }
                    PacketLeftoverLayout.discardUnreadInput(wrapper);

                    final List<UUID> toRemoveUUIDs = new ArrayList<>();
                    final List<String> toRemoveNames = new ArrayList<>();
                    for (int i = 0; i < uuids.length; i++) {
                        final Pair<Long, String> entry = playerListStorage.addPlayer(uuids[i], entityUniqueIds[i], names[i]);
                        if (entry != null) {
                            toRemoveUUIDs.add(uuids[i]);
                            toRemoveNames.add(entry.value());
                        }

                        final Pair<ScoreboardObjective, ScoreboardEntry> scoreboardEntry = scoreboardTracker.getEntryForPlayer(entityUniqueIds[i]);
                        if (scoreboardEntry != null) {
                            scoreboardEntry.key().updateEntry(wrapper.user(), scoreboardEntry.value());
                        }
                    }

                    if (!toRemoveUUIDs.isEmpty()) {
                        // 先从玩家列表移除重复玩家，因为重复添加时基岩客户端会覆盖条目
                        final PacketWrapper playerInfoRemove = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_REMOVE, wrapper.user());
                        playerInfoRemove.write(Types.UUID_ARRAY, toRemoveUUIDs.toArray(new UUID[0])); // UUID 列表
                        playerInfoRemove.send(BedrockProtocol.class);

                        PacketFactory.sendJavaCustomChatCompletions(wrapper.user(), CustomChatCompletionsAction.REMOVE, toRemoveNames.toArray(new String[0]));
                    }

                    PacketFactory.sendJavaCustomChatCompletions(wrapper.user(), CustomChatCompletionsAction.ADD, names);
                    if (spectatorMenu.isActive()) {
                        wrapper.cancel();
                        spectatorMenu.afterPlayerListAdd(uuids);
                    }
                }
                case Remove -> {
                    wrapper.setPacketType(ClientboundPackets26_1.PLAYER_INFO_REMOVE);
                    final UUID[] uuids;
                    if (varintEntryFlags) {
                        uuids = new UUID[length];
                        for (int i = 0; i < length; i++) {
                            if (i > 0 && !readMatchingEntryAction(wrapper, rawAction)) {
                                return;
                            }
                            uuids[i] = wrapper.read(BedrockTypes.UUID); // uuid
                        }
                    } else {
                        uuids = PlayerListLayout.readRemovalUuids(wrapper, length); // UUID 列表，条目数已在包首读过
                    }
                    wrapper.write(Types.UUID_ARRAY, uuids); // UUID 列表

                    final List<String> names = new ArrayList<>();
                    for (UUID uuid : uuids) {
                        final Pair<Long, String> entry = playerListStorage.removePlayer(uuid);
                        if (entry != null) {
                            names.add(entry.value());
                            final Pair<ScoreboardObjective, ScoreboardEntry> scoreboardEntry = scoreboardTracker.getEntryForPlayer(entry.key());
                            if (scoreboardEntry != null) {
                                scoreboardEntry.key().updateEntry(wrapper.user(), scoreboardEntry.value());
                            }
                        }
                    }

                    PacketFactory.sendJavaCustomChatCompletions(wrapper.user(), CustomChatCompletionsAction.REMOVE, names.toArray(new String[0]));
                    spectatorMenu.afterPlayerListRemove(uuids);
                }
                default -> throw new IllegalStateException("Unhandled PlayerListPacketType: " + action);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_TITLE, null, wrapper -> {
            final int rawType = wrapper.read(BedrockTypes.VAR_INT); // 类型
            final SetTitlePacketPayload_TitleType type = SetTitlePacketPayload_TitleType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SetTitlePacketPayload_TitleType: " + rawType);
                wrapper.cancel();
                return;
            }
            String text = wrapper.read(BedrockTypes.STRING); // 文本
            final int fadeInTicks = wrapper.read(BedrockTypes.VAR_INT); // 淡入刻数
            final int stayTicks = wrapper.read(BedrockTypes.VAR_INT); // 停留刻数
            final int fadeOutTicks = wrapper.read(BedrockTypes.VAR_INT); // 淡出刻数
            wrapper.read(BedrockTypes.STRING); // XUID
            wrapper.read(BedrockTypes.STRING); // 平台在线 ID
            wrapper.read(BedrockTypes.STRING); // 过滤后文本
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
            final String originalText = text;
            try {
                if (type.getValue() >= SetTitlePacketPayload_TitleType.TitleTextObject.getValue() && type.getValue() <= SetTitlePacketPayload_TitleType.ActionbarTextObject.getValue()) {
                    final RootBedrockComponent rootComponent = BedrockComponentSerializer.deserialize(text);
                    rootComponent.forEach(c -> {
                        if (c instanceof TranslationBedrockComponent) ((TranslationBedrockComponent) c).setTranslator(translator);
                    });
                    text = rootComponent.asString();
                }

                switch (type) {
                    case Clear, Reset -> {
                        wrapper.setPacketType(ClientboundPackets26_1.CLEAR_TITLES);
                        wrapper.write(Types.BOOLEAN, type == SetTitlePacketPayload_TitleType.Reset); // 重置
                    }
                    case Title, TitleTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_TITLE_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // 文本
                    }
                    case Subtitle, SubtitleTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_SUBTITLE_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // 文本
                    }
                    case Actionbar, ActionbarTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_ACTION_BAR_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // 文本
                    }
                    case Times -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_TITLES_ANIMATION);
                        wrapper.write(Types.INT, fadeInTicks); // 淡入刻数
                        wrapper.write(Types.INT, stayTicks); // 停留刻数
                        wrapper.write(Types.INT, fadeOutTicks); // 淡出刻数
                    }
                    default -> throw new IllegalStateException("Unhandled SetTitlePacketPayload_TitleType: " + type);
                }
            } catch (Throwable e) { // 基岩客户端会静默忽略错误
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while translating '" + originalText + "'", e);
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_DISPLAY_OBJECTIVE, ClientboundPackets26_1.SET_DISPLAY_OBJECTIVE, wrapper -> {
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);

            final String displaySlot = wrapper.read(BedrockTypes.STRING); // 显示槽位
            final String objectiveName = wrapper.read(BedrockTypes.STRING); // 目标名称
            final String displayName = wrapper.read(BedrockTypes.STRING); // 显示名称
            wrapper.read(BedrockTypes.STRING); // 判据
            final ObjectiveSortOrder sortOrder = ObjectiveSortOrder.getByValue(wrapper.read(BedrockTypes.VAR_INT), ObjectiveSortOrder.Descending); // 排序方式 | 任何无效值都被视为不排序，但 Java 版不支持不排序

            switch (displaySlot) {
                case "sidebar" -> wrapper.write(Types.VAR_INT, 1); // 位置
                case "belowname" -> wrapper.write(Types.VAR_INT, 2); // 位置
                case "list" -> wrapper.write(Types.VAR_INT, 0); // 位置
                default -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown bedrock scoreboard display slot: " + displaySlot);
                    wrapper.cancel();
                    return;
                }
            }
            wrapper.write(Types.STRING, objectiveName); // 目标名称

            if (objectiveName.isEmpty()) return;

            final boolean created = !scoreboardTracker.hasObjective(objectiveName);
            if (created) {
                scoreboardTracker.addObjective(objectiveName, new ScoreboardObjective(objectiveName, sortOrder));
            }
            final ScoreboardObjective objective = scoreboardTracker.getObjective(objectiveName);
            if (created || !displayName.equals(objective.getDisplayName())) {
                objective.setDisplayName(displayName);
                final PacketWrapper scoreboardObjective = PacketWrapper.create(ClientboundPackets26_1.SET_OBJECTIVE, wrapper.user());
                scoreboardObjective.write(Types.STRING, objectiveName); // 目标名称
                scoreboardObjective.write(Types.BYTE, (byte) (created ? ObjectiveAction.ADD : ObjectiveAction.CHANGE).ordinal()); // 模式
                scoreboardObjective.write(Types.TAG, ganquanScoreboardTitle(wrapper.user(), displayName)); // 显示名称
                scoreboardObjective.write(Types.VAR_INT, ObjectiveCriteriaRenderType.INTEGER.ordinal()); // 显示模式
                scoreboardObjective.write(Types.BOOLEAN, false); // 是否有数字格式
                scoreboardObjective.send(BedrockProtocol.class);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_SCORE, null, wrapper -> {
            wrapper.cancel();
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);

            for (final SetScoreLayout.Entry decoded : SetScoreLayout.read(wrapper, BedrockWire.current())) {
                final long scoreboardId = decoded.scoreboardId(); // 计分板 ID
                final String objectiveName = decoded.objectiveName(); // 目标名称
                final ScoreboardEntry entry = decoded.entry(); // null 表示移除

                final ScoreboardObjective objective = scoreboardTracker.getObjective(objectiveName);
                final Pair<ScoreboardObjective, ScoreboardEntry> existingEntry = scoreboardTracker.getEntry(scoreboardId);
                if (existingEntry != null) {
                    if (entry == null || objective == null) {
                        existingEntry.key().removeEntry(wrapper.user(), scoreboardId);
                    } else if (existingEntry.key() == objective) {
                        // 相同的 Java 所有者：就地覆盖。RESET+SET 会留下一帧
                        // 空洞，ModUIClient 会把它渲染成计分板闪烁。
                        existingEntry.value().setScore(entry.score());
                        objective.updateEntryInPlace(wrapper.user(), existingEntry.value());
                    } else {
                        existingEntry.key().removeEntry(wrapper.user(), scoreboardId);
                        existingEntry.value().setScore(entry.score());
                        objective.addEntry(wrapper.user(), scoreboardId, existingEntry.value());
                    }
                } else if (entry != null && objective != null) {
                    final ScoreboardEntry sameTargetEntry = objective.getEntryWithSameTarget(entry);
                    if (sameTargetEntry != null) {
                        sameTargetEntry.setScore(entry.score());
                        objective.updateEntryInPlace(wrapper.user(), sameTargetEntry);
                    } else if (entry.isValid()) {
                        objective.addEntry(wrapper.user(), scoreboardId, entry);
                    }
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_SCOREBOARD_IDENTITY, null, wrapper -> {
            wrapper.cancel();
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);

            final byte rawAction = wrapper.read(Types.BYTE); // 动作
            final ScoreboardIdentityPacketType action = ScoreboardIdentityPacketType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ScoreboardIdentityPacketType: " + rawAction);
                return;
            }
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 计数
            for (int i = 0; i < count; i++) {
                final long scoreboardId = wrapper.read(BedrockTypes.VAR_LONG); // 计分板 id
                final Pair<ScoreboardObjective, ScoreboardEntry> entry = scoreboardTracker.getEntry(scoreboardId);
                switch (action) {
                    case Update -> {
                        final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 id
                        if (entry == null) continue;
                        final ScoreboardEntry scoreboardEntry = entry.value();

                        if (scoreboardEntry.entityUniqueId() == null) {
                            scoreboardEntry.updateTarget(IdentityDefinition_Type.Player, entityUniqueId, scoreboardEntry.fakePlayerName());
                            entry.key().updateEntry(wrapper.user(), scoreboardEntry);
                        }
                    }
                    case Remove -> {
                        if (entry == null) continue;
                        final ScoreboardEntry scoreboardEntry = entry.value();

                        if (scoreboardEntry.fakePlayerName() != null) {
                            scoreboardEntry.updateTarget(IdentityDefinition_Type.FakePlayer, null, scoreboardEntry.fakePlayerName());
                            entry.key().updateEntry(wrapper.user(), scoreboardEntry);
                        }
                    }
                    default -> throw new IllegalStateException("Unhandled ScoreboardIdentityPacketType: " + action);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.REMOVE_OBJECTIVE, ClientboundPackets26_1.SET_OBJECTIVE, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.STRING, Types.STRING); // 目标名称
                create(Types.BYTE, (byte) ObjectiveAction.REMOVE.ordinal()); // 模式
                handler(wrapper -> wrapper.user().get(ScoreboardTracker.class).removeObjective(wrapper.get(Types.STRING, 0)));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.BOSS_EVENT, ClientboundPackets26_1.BOSS_EVENT, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final boolean unifiedLayout = BedrockWire.applies(BedrockDeviation.BOSS_EVENT_UNIFIED_LAYOUT);
            final long bossEntityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // Boss 实体唯一 id
            final int rawUpdateType;
            final String unifiedTitle;
            final float unifiedProgress;
            final int unifiedColor;
            if (unifiedLayout) {
                // 1001 起所有更新类型共用定长布局：颜色/叠加层变单字节，暗化屏幕消失
                if (BedrockWire.applies(BedrockDeviation.BOSS_EVENT_PLAYER_EID)) {
                    wrapper.read(BedrockTypes.VAR_LONG); // 玩家唯一 id，Java 侧没有对应语义；2192 起移除
                }
                rawUpdateType = wrapper.read(Types.BYTE) & 0xFF;
                unifiedTitle = wrapper.read(BedrockTypes.STRING);
                wrapper.read(BedrockTypes.STRING); // 已过滤名称
                unifiedProgress = wrapper.read(BedrockTypes.FLOAT_LE);
                unifiedColor = wrapper.read(Types.BYTE) & 0xFF;
                wrapper.read(Types.BYTE); // 叠加层 | 在基岩版中不起作用
            } else {
                rawUpdateType = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 更新类型
                unifiedTitle = null;
                unifiedProgress = 0F;
                unifiedColor = 0;
            }
            final BossEventUpdateType updateType = BossEventUpdateType.getByValue(rawUpdateType);
            if (updateType == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown BossEventUpdateType: " + rawUpdateType);
                wrapper.cancel();
                return;
            }

            final Entity entity = entityTracker.getEntityByUid(bossEntityUniqueId);
            final BossBarStorage bossBars = wrapper.user().get(BossBarStorage.class);
            switch (updateType) {
                case Add -> {
                    final UUID uuid = bossBars.resolveOrCreateJavaUuid(bossEntityUniqueId, entity != null ? entity.javaUuid() : null);
                    final var name = TextUtil.stringToNbt(TextUtil.toSingleLine(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(unifiedLayout ? unifiedTitle : wrapper.read(BedrockTypes.STRING))));
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.STRING); // 已过滤名称
                    }
                    final float progress = unifiedLayout ? unifiedProgress : wrapper.read(BedrockTypes.FLOAT_LE);
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // 暗化屏幕 | 在基岩版中不起作用
                    }
                    final int color = MathUtil.getOrFallback(unifiedLayout ? unifiedColor : wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 叠加层 | 在基岩版中不起作用
                    }
                    final BossBarStorage.BossBar bar = bossBars.add(uuid, name, progress, color);
                    if (entity != null) entity.setHasBossBar(true);
                    if (!bossBars.markAddSent(uuid)) {
                        wrapper.cancel();
                        return;
                    }
                    writeBossBarAdd(wrapper, uuid, bar);
                }
                case Remove -> {
                    final UUID uuid = bossBars.getJavaUuid(bossEntityUniqueId);
                    if (uuid == null) {
                        wrapper.cancel();
                        return;
                    }
                    if (entity != null) entity.setHasBossBar(false);
                    if (!bossBars.remove(uuid)) {
                        wrapper.cancel();
                        return;
                    }
                    wrapper.write(Types.UUID, uuid); // uuid
                    wrapper.write(Types.VAR_INT, BossEventOperationType.REMOVE.ordinal()); // 操作
                }
                case Update_Percent -> {
                    final float progress = unifiedLayout ? unifiedProgress : wrapper.read(BedrockTypes.FLOAT_LE);
                    final UUID uuid = bossBars.getJavaUuid(bossEntityUniqueId);
                    if (uuid == null) {
                        wrapper.cancel();
                        return;
                    }
                    final BossBarStorage.BossBar bar = bossBars.get(uuid);
                    if (bar == null) {
                        wrapper.cancel();
                        return;
                    }
                    bar.setProgress(progress);
                    if (restoreClearedBossBar(wrapper, bossBars, uuid, bar)) return;
                    wrapper.write(Types.UUID, uuid); // uuid
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_PROGRESS.ordinal()); // 操作
                    wrapper.write(Types.FLOAT, progress); // 进度
                }
                case Update_Name -> {
                    final var name = TextUtil.stringToNbt(TextUtil.toSingleLine(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(unifiedLayout ? unifiedTitle : wrapper.read(BedrockTypes.STRING))));
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.STRING); // 已过滤名称
                    }
                    final UUID uuid = bossBars.getJavaUuid(bossEntityUniqueId);
                    if (uuid == null) {
                        wrapper.cancel();
                        return;
                    }
                    final BossBarStorage.BossBar bar = bossBars.get(uuid);
                    if (bar == null) {
                        wrapper.cancel();
                        return;
                    }
                    bar.setName(name);
                    if (restoreClearedBossBar(wrapper, bossBars, uuid, bar)) return;
                    wrapper.write(Types.UUID, uuid); // uuid
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_NAME.ordinal()); // 操作
                    wrapper.write(Types.TAG, name); // 名称
                }
                case Update_Properties -> {
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // 暗化屏幕 | 在基岩版中不起作用
                    }
                    final int color = MathUtil.getOrFallback(unifiedLayout ? unifiedColor : wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 叠加层 | 在基岩版中不起作用
                    }
                    final UUID uuid = bossBars.getJavaUuid(bossEntityUniqueId);
                    if (uuid == null) {
                        wrapper.cancel();
                        return;
                    }
                    final BossBarStorage.BossBar bar = bossBars.get(uuid);
                    if (bar == null) {
                        wrapper.cancel();
                        return;
                    }
                    bar.setColor(color);
                    if (restoreClearedBossBar(wrapper, bossBars, uuid, bar)) return;
                    wrapper.write(Types.UUID, uuid); // uuid
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_STYLE.ordinal()); // 操作
                    wrapper.write(Types.VAR_INT, color); // 颜色
                    wrapper.write(Types.VAR_INT, 0); // 叠加层
                }
                case Update_Style -> {
                    final int color = MathUtil.getOrFallback(unifiedLayout ? unifiedColor : wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    if (!unifiedLayout) {
                        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 叠加层 | 在基岩版中不起作用
                    }
                    final UUID uuid = bossBars.getJavaUuid(bossEntityUniqueId);
                    if (uuid == null) {
                        wrapper.cancel();
                        return;
                    }
                    final BossBarStorage.BossBar bar = bossBars.get(uuid);
                    if (bar == null) {
                        wrapper.cancel();
                        return;
                    }
                    bar.setColor(color);
                    if (restoreClearedBossBar(wrapper, bossBars, uuid, bar)) return;
                    wrapper.write(Types.UUID, uuid); // uuid
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_STYLE.ordinal()); // 操作
                    wrapper.write(Types.VAR_INT, color); // 颜色
                    wrapper.write(Types.VAR_INT, 0); // 叠加层
                }
                case PlayerAdded, PlayerRemoved, Query -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled BossEventUpdateType: " + updateType);
            }
        });
        // MOT Player.kill()（协议 860）：除 RESPAWN SEARCHING 之外，DeathInfoPacket 是唯一的死亡
        // 信号。它不会为本地玩家发出 ActorEvent.DEATH 或
        // UPDATE_ATTRIBUTES(health=0)，因此若不在这里把客户端玩家标记为死亡，
        // Java 就永远看不到 PLAYER_COMBAT_KILL。
        protocol.registerClientbound(ClientboundBedrockPackets.DEATH_INFO, null, wrapper -> {
            wrapper.cancel();
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final DeathSyncLayout.DeathInfo deathInfo = DeathSyncLayout.readDeathInfo(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
            gameSession.setDeathMessage(TextUtil.stringToTextComponent(TextUtil.toSingleLine(BedrockTranslator.translate(deathInfo.messageTranslationKey(), translator, deathInfo.messageParameters()))));
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            if (clientPlayer == null) {
                return;
            }
            if (!clientPlayer.isDead()) {
                clientPlayer.setHealth(0F);
                clientPlayer.sendAttribute("minecraft:health");
            }
            if (clientPlayer.isDead() && gameSession.getDeathMessage() != null) {
                final PacketWrapper playerCombatKill = PacketWrapper.create(ClientboundPackets26_1.PLAYER_COMBAT_KILL, wrapper.user());
                playerCombatKill.write(Types.VAR_INT, clientPlayer.javaId()); // 实体 id
                playerCombatKill.write(Types.TAG, TextUtil.textComponentToNbt(gameSession.getDeathMessage())); // 消息
                playerCombatKill.send(BedrockProtocol.class);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.GUI_DATA_PICK_ITEM, ClientboundPackets26_1.SYSTEM_CHAT, wrapper -> {
            // 官方基岩：string name + string effects + LE int slot。
            // MOT 860 的 GUIDataPickItemPacket.encode() 只有 putLInt(hotbarSlot)。
            final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
            final int neteaseProtocol = emulateNetEase ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
            final GuiDataPickItemLayout.Packet pick = GuiDataPickItemLayout.read(wrapper, emulateNetEase, neteaseProtocol);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            if (GuiDataPickItemLayout.isHotbarSlot(pick.hotbarSlot())) {
                final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
                inventoryContainer.setSelectedHotbarSlotLocally((byte) pick.hotbarSlot());
                inventoryContainer.sendSelectedHotbarSlotToClient();
            }
            if (!GuiDataPickItemLayout.hasOverlayText(pick)) {
                wrapper.cancel();
                return;
            }
            wrapper.write(Types.TAG, TextUtil.stringToNbt(GuiDataPickItemLayout.overlayText(pick))); // 消息
            wrapper.write(Types.BOOLEAN, true); // 叠加层
        });
        // MOT ToastRequestPacket (186)：string title + string content。
        // Java 1.21.11 没有 toast 数据包；MOT 自身在协议 527 以下回退为 sendTitle。
        // 动作栏会保持当前的标题/副标题不变。
        protocol.registerClientbound(ClientboundBedrockPackets.TOAST_REQUEST, ClientboundPackets26_1.SET_ACTION_BAR_TEXT, wrapper -> {
            final ToastRequestLayout.Packet toast = ToastRequestLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
            final String title = BedrockTranslator.translate(toast.title(), translator, new Object[0]);
            final String content = BedrockTranslator.translate(toast.content(), translator, new Object[0]);
            wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(ToastRequestLayout.actionBarText(title, content))));
        });
        // MOT PlayerStartItemCoolDownPacket (176)：string itemCategory + varint ticks。
        // Java 1.21.2+ 的冷却数据包接收物品 Identifier；MOT 目前发出的
        // goat_horn / shield 没有命名空间。
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_START_ITEM_COOLDOWN, ClientboundPackets26_1.COOLDOWN, wrapper -> {
            final PlayerStartItemCooldownLayout.Packet cooldown = PlayerStartItemCooldownLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final String javaIdentifier = PlayerStartItemCooldownLayout.javaCooldownIdentifier(cooldown.itemCategory());
            if (javaIdentifier == null) {
                wrapper.cancel();
                return;
            }
            wrapper.write(Types.STRING, javaIdentifier); // 物品标识符
            wrapper.write(Types.VAR_INT, cooldown.coolDownDuration()); // 刻数
        });
    }

    private static boolean restoreClearedBossBar(final PacketWrapper wrapper, final BossBarStorage bossBars, final UUID uuid, final BossBarStorage.BossBar bar) {
        return switch (bossBars.reconcileUpdate(uuid)) {
            case ADD -> {
                writeBossBarAdd(wrapper, uuid, bar);
                yield true;
            }
            case UPDATE -> false;
            case DROP -> throw new IllegalStateException("Boss bar disappeared while reconciling update: " + uuid);
        };
    }

    private static void writeBossBarAdd(final PacketWrapper wrapper, final UUID uuid, final BossBarStorage.BossBar bar) {
        wrapper.write(Types.UUID, uuid); // uuid
        wrapper.write(Types.VAR_INT, BossEventOperationType.ADD.ordinal()); // 操作
        wrapper.write(Types.TAG, bar.name()); // 名称
        wrapper.write(Types.FLOAT, bar.progress()); // 进度
        wrapper.write(Types.VAR_INT, bar.color()); // 颜色
        wrapper.write(Types.VAR_INT, 0); // 叠加层
        wrapper.write(Types.UNSIGNED_BYTE, (short) 0); // 标志
    }

    /**
     * 2168 起 PLAYER_LIST 在每条之前重复一次动作标志。与包头不一致时丢弃整包，
     * 避免 java 侧按错误动作写出条目。
     */
    private static boolean readMatchingEntryAction(final PacketWrapper wrapper, final byte expected) {
        final byte entryAction = PlayerListLayout.readEntryAction(wrapper);
        if (entryAction != expected) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "Mixed PLAYER_LIST entry actions: " + entryAction + " != " + expected);
            wrapper.cancel();
            return false;
        }
        return true;
    }

    /**
     * Java 文本会丢弃未知的基岩 {@code §} 组合，例如 Ganquan 的 {@code §THEME1}。
     * 把原始标题保留在 {@code insertion} 中，以便 ModUIClient 仍能路由 JSON-UI 皮肤。
     */
    private static Tag ganquanScoreboardTitle(final com.viaversion.viaversion.api.connection.UserConnection user, final String displayName) {
        final String translated = TextUtil.toSingleLine(user.get(ResourcePackStorage.class).getTexts().translate(displayName));
        final CompoundTag tag = TextUtil.ensureCompoundTag(TextUtil.stringToNbt(translated));
        tag.putString("insertion", translated);
        return tag;
    }

}
