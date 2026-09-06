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

            final byte rawAction = wrapper.read(Types.BYTE); // action
            final PlayerListPacketType action = PlayerListPacketType.getByValue(rawAction);
            if (action == null) { // Bedrock client crashes if the action is not valid
                throw new IllegalStateException("Unknown PlayerListPacketType: " + rawAction);
            }
            switch (action) {
                case Add -> {
                    final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
                    final UUID[] uuids = new UUID[length];
                    final long[] entityUniqueIds = new long[length];
                    final String[] names = new String[length];
                    final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
                    wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_LISTED, PlayerInfoUpdateAction.UPDATE_LATENCY, PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME)); // actions
                    wrapper.write(Types.VAR_INT, length); // length
                    for (int i = 0; i < length; i++) {
                        uuids[i] = wrapper.read(BedrockTypes.UUID); // uuid
                        entityUniqueIds[i] = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id

                        // Remap local player UUID: Bedrock server assigns a different UUID than the Java UUID generated during login
                        final boolean localPlayer = entityTracker.getClientPlayer() != null
                                && entityTracker.getClientPlayer().uniqueId() == entityUniqueIds[i];
                        if (localPlayer) {
                            // Capture the server-assigned UUID before overwriting it, so the PLAYER_SKIN handler
                            // (which only has the UUID, not the entityUniqueId) can recognise the local player.
                            entityTracker.getClientPlayer().setBedrockUuid(uuids[i]);
                            uuids[i] = entityTracker.getClientPlayer().javaUuid();
                        }

                        wrapper.write(Types.UUID, uuids[i]); // uuid
                        wrapper.write(Types.STRING, StringUtil.encodeUUID(uuids[i])); // username
                        names[i] = TextUtil.toSingleLine(wrapper.read(BedrockTypes.STRING)); // username
                        final String xuid = wrapper.read(BedrockTypes.STRING); // xuid
                        final String platformOnlineId = wrapper.read(BedrockTypes.STRING); // platform online id
                        final int deviceOs = wrapper.read(BedrockTypes.INT_LE); // device os
                        final SkinData skin = wrapper.read(BedrockTypes.SKIN); // skin
                        final boolean isTeacher = wrapper.read(Types.BOOLEAN); // is teacher
                        final boolean isHost = wrapper.read(Types.BOOLEAN); // is host
                        final boolean isSubClient = wrapper.read(Types.BOOLEAN); // is sub client
                        wrapper.read(BedrockTypes.INT_LE); // color (argb)
                        final GameProfile.Property[] properties = new GameProfile.Property[]{
                                new GameProfile.Property("xuid", xuid),
                                new GameProfile.Property("platform_online_id", platformOnlineId),
                                new GameProfile.Property("device_os", String.valueOf(deviceOs)),
                                new GameProfile.Property("is_teacher", String.valueOf(isTeacher)),
                                new GameProfile.Property("is_host", String.valueOf(isHost)),
                                new GameProfile.Property("is_subclient", String.valueOf(isSubClient))
                        };
                        wrapper.write(Types.PROFILE_PROPERTY_ARRAY, properties); // properties
                        final boolean listed = ExperimentalFeatures.isPlayerListEntryListed(wrapper.user(), uuids[i], entityUniqueIds[i], names[i]);
                        wrapper.write(Types.BOOLEAN, listed); // listed
                        final int latency = localPlayer ? packetSyncStorage.latencyMillis() : playerListStorage.serverLatency(uuids[i]);
                        wrapper.write(Types.VAR_INT, latency); // latency
                        final Tag displayName = ExperimentalFeatures.decoratePlayerListDisplayName(wrapper.user(), uuids[i], entityUniqueIds[i], names[i], latency, TextUtil.stringToNbt(names[i]));
                        wrapper.write(Types.OPTIONAL_TAG, displayName); // display name
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
                    try {
                        for (int i = 0; i < length; i++) {
                            wrapper.read(Types.BOOLEAN); // trusted skin
                        }
                    } catch (InformativeException ignored) { // Bedrock client silently ignores read errors
                    }
                    try {
                        PlayerListLayout.skipNetEaseAddTrailer(wrapper, length);
                    } catch (RuntimeException ignored) { // MOT bloom trailer is optional on some builds
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
                        // Remove duplicate players from the player list first because Bedrock client overwrites entries if they are added twice
                        final PacketWrapper playerInfoRemove = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_REMOVE, wrapper.user());
                        playerInfoRemove.write(Types.UUID_ARRAY, toRemoveUUIDs.toArray(new UUID[0])); // uuids
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
                    final UUID[] uuids = wrapper.read(BedrockTypes.UUID_ARRAY); // uuids
                    wrapper.write(Types.UUID_ARRAY, uuids); // uuids

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
            final int rawType = wrapper.read(BedrockTypes.VAR_INT); // type
            final SetTitlePacketPayload_TitleType type = SetTitlePacketPayload_TitleType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SetTitlePacketPayload_TitleType: " + rawType);
                wrapper.cancel();
                return;
            }
            String text = wrapper.read(BedrockTypes.STRING); // text
            final int fadeInTicks = wrapper.read(BedrockTypes.VAR_INT); // fade in ticks
            final int stayTicks = wrapper.read(BedrockTypes.VAR_INT); // stay ticks
            final int fadeOutTicks = wrapper.read(BedrockTypes.VAR_INT); // fade out ticks
            wrapper.read(BedrockTypes.STRING); // xuid
            wrapper.read(BedrockTypes.STRING); // platform online id
            wrapper.read(BedrockTypes.STRING); // filtered text
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
                        wrapper.write(Types.BOOLEAN, type == SetTitlePacketPayload_TitleType.Reset); // reset
                    }
                    case Title, TitleTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_TITLE_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // text
                    }
                    case Subtitle, SubtitleTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_SUBTITLE_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // text
                    }
                    case Actionbar, ActionbarTextObject -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_ACTION_BAR_TEXT);
                        wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(text))); // text
                    }
                    case Times -> {
                        wrapper.setPacketType(ClientboundPackets26_1.SET_TITLES_ANIMATION);
                        wrapper.write(Types.INT, fadeInTicks); // fade in ticks
                        wrapper.write(Types.INT, stayTicks); // stay ticks
                        wrapper.write(Types.INT, fadeOutTicks); // fade out ticks
                    }
                    default -> throw new IllegalStateException("Unhandled SetTitlePacketPayload_TitleType: " + type);
                }
            } catch (Throwable e) { // Bedrock client silently ignores errors
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while translating '" + originalText + "'", e);
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_DISPLAY_OBJECTIVE, ClientboundPackets26_1.SET_DISPLAY_OBJECTIVE, wrapper -> {
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);

            final String displaySlot = wrapper.read(BedrockTypes.STRING); // display slot
            final String objectiveName = wrapper.read(BedrockTypes.STRING); // objective name
            final String displayName = wrapper.read(BedrockTypes.STRING); // display name
            wrapper.read(BedrockTypes.STRING); // criteria
            final ObjectiveSortOrder sortOrder = ObjectiveSortOrder.getByValue(wrapper.read(BedrockTypes.VAR_INT), ObjectiveSortOrder.Descending); // sort order | Any invalid value is treated as no sorting, but Java Edition doesn't support that

            switch (displaySlot) {
                case "sidebar" -> wrapper.write(Types.VAR_INT, 1); // position
                case "belowname" -> wrapper.write(Types.VAR_INT, 2); // position
                case "list" -> wrapper.write(Types.VAR_INT, 0); // position
                default -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown bedrock scoreboard display slot: " + displaySlot);
                    wrapper.cancel();
                    return;
                }
            }
            wrapper.write(Types.STRING, objectiveName); // objective name

            if (objectiveName.isEmpty()) return;

            final boolean created = !scoreboardTracker.hasObjective(objectiveName);
            if (created) {
                scoreboardTracker.addObjective(objectiveName, new ScoreboardObjective(objectiveName, sortOrder));
            }
            final ScoreboardObjective objective = scoreboardTracker.getObjective(objectiveName);
            if (created || !displayName.equals(objective.getDisplayName())) {
                objective.setDisplayName(displayName);
                final PacketWrapper scoreboardObjective = PacketWrapper.create(ClientboundPackets26_1.SET_OBJECTIVE, wrapper.user());
                scoreboardObjective.write(Types.STRING, objectiveName); // objective name
                scoreboardObjective.write(Types.BYTE, (byte) (created ? ObjectiveAction.ADD : ObjectiveAction.CHANGE).ordinal()); // mode
                scoreboardObjective.write(Types.TAG, ganquanScoreboardTitle(wrapper.user(), displayName)); // display name
                scoreboardObjective.write(Types.VAR_INT, ObjectiveCriteriaRenderType.INTEGER.ordinal()); // display mode
                scoreboardObjective.write(Types.BOOLEAN, false); // has number format
                scoreboardObjective.send(BedrockProtocol.class);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_SCORE, null, wrapper -> {
            wrapper.cancel();
            final ScoreboardTracker scoreboardTracker = wrapper.user().get(ScoreboardTracker.class);

            final byte rawAction = wrapper.read(Types.BYTE); // action
            final ScorePacketType action = ScorePacketType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ScorePacketType: " + rawAction);
                return;
            }
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // count
            for (int i = 0; i < count; i++) {
                final long scoreboardId = wrapper.read(BedrockTypes.VAR_LONG); // scoreboard id
                final String objectiveName = wrapper.read(BedrockTypes.STRING); // objective name
                final int score = wrapper.read(BedrockTypes.INT_LE); // score

                final ScoreboardEntry entry;
                switch (action) {
                    case Change -> {
                        final byte rawType = wrapper.read(Types.BYTE); // type
                        final IdentityDefinition_Type type = IdentityDefinition_Type.getByValue(rawType, IdentityDefinition_Type.Invalid);
                        Long entityUniqueId = null;
                        String fakePlayerName = null;
                        switch (type) {
                            case Player, Entity -> entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
                            case FakePlayer -> fakePlayerName = wrapper.read(BedrockTypes.STRING); // fake player name
                            case Invalid -> throw new IllegalStateException("Invalid IdentityDefinition_Type: " + rawType); // Bedrock client disconnects if the type is not valid
                            default -> throw new IllegalStateException("Unhandled IdentityDefinition_Type: " + rawType);
                        }
                        entry = new ScoreboardEntry(score, type, entityUniqueId, fakePlayerName);
                    }
                    case Remove -> entry = null;
                    default -> throw new IllegalStateException("Unhandled ScorePacketType: " + action);
                }

                final ScoreboardObjective objective = scoreboardTracker.getObjective(objectiveName);
                final Pair<ScoreboardObjective, ScoreboardEntry> existingEntry = scoreboardTracker.getEntry(scoreboardId);
                if (existingEntry != null) {
                    if (entry == null || objective == null) {
                        existingEntry.key().removeEntry(wrapper.user(), scoreboardId);
                    } else if (existingEntry.key() == objective) {
                        // Same Java owner: overwrite in place. RESET+SET leaves a one-frame
                        // hole that ModUIClient renders as a scoreboard flash.
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

            final byte rawAction = wrapper.read(Types.BYTE); // action
            final ScoreboardIdentityPacketType action = ScoreboardIdentityPacketType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ScoreboardIdentityPacketType: " + rawAction);
                return;
            }
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // count
            for (int i = 0; i < count; i++) {
                final long scoreboardId = wrapper.read(BedrockTypes.VAR_LONG); // scoreboard id
                final Pair<ScoreboardObjective, ScoreboardEntry> entry = scoreboardTracker.getEntry(scoreboardId);
                switch (action) {
                    case Update -> {
                        final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
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
                map(BedrockTypes.STRING, Types.STRING); // objective name
                create(Types.BYTE, (byte) ObjectiveAction.REMOVE.ordinal()); // mode
                handler(wrapper -> wrapper.user().get(ScoreboardTracker.class).removeObjective(wrapper.get(Types.STRING, 0)));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.BOSS_EVENT, ClientboundPackets26_1.BOSS_EVENT, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final long bossEntityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // boss entity unique id
            final int rawUpdateType = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // update type
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
                    final var name = TextUtil.stringToNbt(TextUtil.toSingleLine(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(wrapper.read(BedrockTypes.STRING))));
                    wrapper.read(BedrockTypes.STRING); // filtered name
                    final float progress = wrapper.read(BedrockTypes.FLOAT_LE);
                    wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // darken screen | Does nothing in Bedrock Edition
                    final int color = MathUtil.getOrFallback(wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // overlay | Does nothing in Bedrock Edition
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
                    wrapper.write(Types.VAR_INT, BossEventOperationType.REMOVE.ordinal()); // operation
                }
                case Update_Percent -> {
                    final float progress = wrapper.read(BedrockTypes.FLOAT_LE);
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
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_PROGRESS.ordinal()); // operation
                    wrapper.write(Types.FLOAT, progress); // progress
                }
                case Update_Name -> {
                    final var name = TextUtil.stringToNbt(TextUtil.toSingleLine(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(wrapper.read(BedrockTypes.STRING))));
                    wrapper.read(BedrockTypes.STRING); // filtered name
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
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_NAME.ordinal()); // operation
                    wrapper.write(Types.TAG, name); // name
                }
                case Update_Properties -> {
                    wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // darken screen | Does nothing in Bedrock Edition
                    final int color = MathUtil.getOrFallback(wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // overlay | Does nothing in Bedrock Edition
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
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_STYLE.ordinal()); // operation
                    wrapper.write(Types.VAR_INT, color); // color
                    wrapper.write(Types.VAR_INT, 0); // overlay
                }
                case Update_Style -> {
                    final int color = MathUtil.getOrFallback(wrapper.read(BedrockTypes.UNSIGNED_VAR_INT), 0, 5, 0);
                    wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // overlay | Does nothing in Bedrock Edition
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
                    wrapper.write(Types.VAR_INT, BossEventOperationType.UPDATE_STYLE.ordinal()); // operation
                    wrapper.write(Types.VAR_INT, color); // color
                    wrapper.write(Types.VAR_INT, 0); // overlay
                }
                case PlayerAdded, PlayerRemoved, Query -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled BossEventUpdateType: " + updateType);
            }
        });
        // MOT Player.kill() (protocol 860): DeathInfoPacket is the only death
        // signal besides RESPAWN SEARCHING. It does not emit ActorEvent.DEATH or
        // UPDATE_ATTRIBUTES(health=0) for the local player, so Java never sees
        // PLAYER_COMBAT_KILL unless we mark the client player dead here.
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
                playerCombatKill.write(Types.VAR_INT, clientPlayer.javaId()); // entity id
                playerCombatKill.write(Types.TAG, TextUtil.textComponentToNbt(gameSession.getDeathMessage())); // message
                playerCombatKill.send(BedrockProtocol.class);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.GUI_DATA_PICK_ITEM, ClientboundPackets26_1.SYSTEM_CHAT, wrapper -> {
            // Official Bedrock: string name + string effects + LE int slot.
            // MOT 860 GUIDataPickItemPacket.encode() is only putLInt(hotbarSlot).
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
            wrapper.write(Types.TAG, TextUtil.stringToNbt(GuiDataPickItemLayout.overlayText(pick))); // message
            wrapper.write(Types.BOOLEAN, true); // overlay
        });
        // MOT ToastRequestPacket (186): string title + string content.
        // Java 1.21.11 has no toast packet; MOT itself falls back to sendTitle below
        // protocol 527. Action-bar keeps the current title/subtitle intact.
        protocol.registerClientbound(ClientboundBedrockPackets.TOAST_REQUEST, ClientboundPackets26_1.SET_ACTION_BAR_TEXT, wrapper -> {
            final ToastRequestLayout.Packet toast = ToastRequestLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
            final String title = BedrockTranslator.translate(toast.title(), translator, new Object[0]);
            final String content = BedrockTranslator.translate(toast.content(), translator, new Object[0]);
            wrapper.write(Types.TAG, TextUtil.stringToNbt(TextUtil.toSingleLine(ToastRequestLayout.actionBarText(title, content))));
        });
        // MOT PlayerStartItemCoolDownPacket (176): string itemCategory + varint ticks.
        // Java 1.21.2+ cooldown packets take an item Identifier; MOT currently emits
        // goat_horn / shield without a namespace.
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_START_ITEM_COOLDOWN, ClientboundPackets26_1.COOLDOWN, wrapper -> {
            final PlayerStartItemCooldownLayout.Packet cooldown = PlayerStartItemCooldownLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final String javaIdentifier = PlayerStartItemCooldownLayout.javaCooldownIdentifier(cooldown.itemCategory());
            if (javaIdentifier == null) {
                wrapper.cancel();
                return;
            }
            wrapper.write(Types.STRING, javaIdentifier); // item identifier
            wrapper.write(Types.VAR_INT, cooldown.coolDownDuration()); // ticks
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
        wrapper.write(Types.VAR_INT, BossEventOperationType.ADD.ordinal()); // operation
        wrapper.write(Types.TAG, bar.name()); // name
        wrapper.write(Types.FLOAT, bar.progress()); // progress
        wrapper.write(Types.VAR_INT, bar.color()); // color
        wrapper.write(Types.VAR_INT, 0); // overlay
        wrapper.write(Types.UNSIGNED_BYTE, (short) 0); // flags
    }

    /**
     * Java text drops unknown Bedrock {@code §} pairs such as Ganquan's {@code §THEME1}.
     * Keep the raw title in {@code insertion} so ModUIClient can still route the JSON-UI skin.
     */
    private static Tag ganquanScoreboardTitle(final com.viaversion.viaversion.api.connection.UserConnection user, final String displayName) {
        final String translated = TextUtil.toSingleLine(user.get(ResourcePackStorage.class).getTexts().translate(displayName));
        final CompoundTag tag = TextUtil.ensureCompoundTag(TextUtil.stringToNbt(translated));
        tag.putString("insertion", translated);
        return tag;
    }

}
