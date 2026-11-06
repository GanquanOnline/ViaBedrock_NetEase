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

import com.google.common.base.Utf8;
import com.google.common.collect.Sets;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.lenni0451.mcstructs_bedrock.text.components.RootBedrockComponent;
import net.lenni0451.mcstructs_bedrock.text.components.TranslationBedrockComponent;
import net.lenni0451.mcstructs_bedrock.text.serializer.BedrockComponentSerializer;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.lenni0451.mcstructs_bedrock.text.utils.TranslatorOptions;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.model.CommandData;
import net.raphimc.viabedrock.protocol.model.CommandOriginData;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public class ChatPackets {

    private static final PacketHandler CHAT_COMMAND_HANDLER = new PacketHandlers() {
        @Override
        protected void register() {
            map(Types.STRING, BedrockTypes.STRING, c -> '/' + c); // 命令
            handler(wrapper -> wrapper.write(BedrockTypes.COMMAND_ORIGIN_DATA, new CommandOriginData(CommandOriginType.Player, UUID.randomUUID(), ""))); // 来源
            create(Types.BOOLEAN, false); // 是否为内部
            handler(wrapper -> CommandPacketLayout.writeNetEaseUnlimit(wrapper)); // unlimit（网易 898+）
            handler(wrapper -> CommandPacketLayout.writeRequestVersion(wrapper)); // 版本
            handler(PacketWrapper::clearInputBuffer);
            handler(wrapper -> {
                final CommandsStorage commandsStorage = wrapper.user().get(CommandsStorage.class);
                int execResult = CommandsStorage.RESULT_NO_OP;
                if (commandsStorage != null) {
                    execResult = commandsStorage.execute(wrapper.get(BedrockTypes.STRING, 0));
                }

                if (execResult == CommandsStorage.RESULT_CANCEL) {
                    wrapper.cancel();
                } else if (execResult != CommandsStorage.RESULT_ALLOW_SEND) {
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    if (!gameSession.areCommandsEnabled() || (gameSession.getChatRestrictionLevel() == ChatRestrictionLevel.Disabled && clientPlayer.abilities().playerPermission() <= PlayerPermissionLevel.Member.getValue())) {
                        wrapper.cancel();
                        PacketFactory.sendJavaSystemChat(wrapper.user(), TextUtil.stringToNbt("§e" + wrapper.user().get(ResourcePackStorage.class).getTexts().get("commands.generic.disabled")));
                    }
                }
            });
        }
    };

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.TEXT, ClientboundPackets26_1.SYSTEM_CHAT, wrapper -> {
            try {
                rewriteClientboundText(wrapper);
            } catch (Throwable e) {
                // 格式错误的 TEXT 包绝不能关闭会话。原版基岩
                // 会丢弃该消息；此前在 cancel() 之后调用 PacketHandlers.read() 会重新抛出异常。
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Skipping TEXT packet due to parse error: " + e);
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.COMMAND_OUTPUT, ClientboundPackets26_1.SYSTEM_CHAT, wrapper -> {
            final CommandOriginData originData = wrapper.read(BedrockTypes.COMMAND_ORIGIN_DATA); // 来源
            final CommandOutputType type = CommandPacketLayout.readOutputType(wrapper); // 类型
            CommandPacketLayout.readSuccessCount(wrapper); // 成功次数

            if (originData.type() != CommandOriginType.Player) { // 基岩客户端会忽略非玩家来源
                wrapper.cancel();
                return;
            }

            final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
            final StringBuilder message = new StringBuilder();
            final int messageCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 消息数量
            for (int i = 0; i < messageCount; i++) {
                final CommandPacketLayout.CommandOutputMessage outputMessage = CommandPacketLayout.readOutputMessage(wrapper);
                final String messageId = outputMessage.messageId();
                final boolean successful = outputMessage.successful();
                final String[] parameters = outputMessage.parameters();

                message.append(successful ? "§r" : "§c");
                message.append(BedrockTranslator.translate(messageId, translator, parameters));
                if (i != messageCount - 1) {
                    message.append("\n");
                }
            }
            CommandPacketLayout.skipOutputData(wrapper, type); // 数据集

            wrapper.write(Types.TAG, TextUtil.stringToNbt(message.toString()));
            wrapper.write(Types.BOOLEAN, false); // 叠加层
        });
        protocol.registerClientboundTransition(ClientboundBedrockPackets.AVAILABLE_COMMANDS,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    final CommandData[] commands;
                    try {
                        commands = wrapper.read(BedrockTypes.COMMAND_DATA_ARRAY); // 命令
                    } catch (Throwable t) {
                        // 网易尾部布局差异不得中断会话；命令是可选的
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                "Skipping AVAILABLE_COMMANDS (configuration) due to parse error: " + t);
                        wrapper.cancel();
                        return;
                    }
                    wrapper.user().put(new CommandsStorage(wrapper.user(), commands));
                    wrapper.cancel(); // 将在 Java 玩家就绪时发送
                }, ClientboundPackets26_1.COMMANDS, (PacketHandler) wrapper -> {
                    final CommandData[] commands;
                    try {
                        commands = wrapper.read(BedrockTypes.COMMAND_DATA_ARRAY); // 命令
                    } catch (Throwable t) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                "Skipping AVAILABLE_COMMANDS (play) due to parse error: " + t);
                        wrapper.cancel();
                        return;
                    }
                    final CommandsStorage commandsStorage = new CommandsStorage(wrapper.user(), commands);
                    wrapper.user().put(commandsStorage);
                    commandsStorage.writeCommandTree(wrapper);
                }
        );
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_SOFT_ENUM, null, wrapper -> {
            wrapper.cancel();
            final CommandsStorage commandsStorage = wrapper.user().get(CommandsStorage.class);
            if (commandsStorage == null) return;

            final String name = wrapper.read(BedrockTypes.STRING); // 名称
            final Set<String> values = Sets.newHashSet(wrapper.read(BedrockTypes.STRING_ARRAY)); // 值

            final CommandData.EnumData softEnum = commandsStorage.getSoftEnum(name);
            if (softEnum == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received update for unknown soft enum: " + name);
                return;
            }

            final byte rawAction = wrapper.read(Types.BYTE); // 动作
            final SoftEnumUpdateType action = SoftEnumUpdateType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SoftEnumUpdateType: " + rawAction);
                return;
            }

            switch (action) {
                case Add -> softEnum.addValues(values);
                case Remove -> softEnum.removeValues(values);
                case Replace -> {
                    softEnum.values().clear();
                    softEnum.addValues(values);
                }
                default -> throw new IllegalStateException("Unhandled SoftEnumUpdateType: " + action);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_COMMANDS_ENABLED, null, wrapper -> {
            wrapper.cancel();
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final boolean commandsEnabled = wrapper.read(Types.BOOLEAN); // 命令是否启用
            if (commandsEnabled != gameSession.areCommandsEnabled()) {
                gameSession.setCommandsEnabled(commandsEnabled);
                final CommandsStorage commandsStorage = wrapper.user().get(CommandsStorage.class);
                if (commandsStorage != null) {
                    commandsStorage.updateCommandTree();
                }
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.CHAT, ServerboundBedrockPackets.TEXT, new PacketHandlers() {
            @Override
            public void register() {
                handler(wrapper -> writeServerboundChatHeader(wrapper, TextPacketType.chat));
                handler(wrapper -> wrapper.write(BedrockTypes.STRING, wrapper.user().get(EntityTracker.class).getClientPlayer().name())); // 来源名称
                map(Types.STRING, BedrockTypes.STRING); // 消息
                handler(wrapper -> writeServerboundChatParameters(wrapper));
                handler(wrapper -> writeServerboundChatTrailer(wrapper));
                handler(PacketWrapper::clearInputBuffer);
                handler(wrapper -> {
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    if (gameSession.getChatRestrictionLevel() != ChatRestrictionLevel.None || clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Muted)) {
                        wrapper.cancel();
                        PacketFactory.sendJavaSystemChat(wrapper.user(), TextUtil.stringToNbt("§e" + wrapper.user().get(ResourcePackStorage.class).getTexts().get("permissions.chatmute")));
                    }
                });
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.CHAT_COMMAND, ServerboundBedrockPackets.COMMAND_REQUEST, CHAT_COMMAND_HANDLER);
        protocol.registerServerbound(ServerboundPackets26_1.CHAT_COMMAND_SIGNED, ServerboundBedrockPackets.COMMAND_REQUEST, CHAT_COMMAND_HANDLER);
        protocol.registerServerbound(ServerboundPackets26_1.COMMAND_SUGGESTION, null, wrapper -> {
            wrapper.cancel();
            final CommandsStorage commandsStorage = wrapper.user().get(CommandsStorage.class);
            if (commandsStorage == null) return;

            final int id = wrapper.read(Types.VAR_INT); // 事务 ID
            final String command = wrapper.read(Types.STRING); // 命令
            if (!command.startsWith("/")) {
                return;
            }

            final Suggestions suggestions = commandsStorage.complete(command);

            final PacketWrapper tabComplete = PacketWrapper.create(ClientboundPackets26_1.COMMAND_SUGGESTIONS, wrapper.user());
            tabComplete.write(Types.VAR_INT, id); // 事务 ID
            tabComplete.write(Types.VAR_INT, suggestions.getRange().getStart()); // 起始索引
            tabComplete.write(Types.VAR_INT, suggestions.getRange().getLength()); // 长度
            tabComplete.write(Types.VAR_INT, suggestions.getList().size()); // 数量
            for (Suggestion suggestion : suggestions.getList()) {
                tabComplete.write(Types.STRING, suggestion.getText()); // 文本
                if (suggestion.getTooltip() != null) {
                    tabComplete.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(suggestion.getTooltip().getString())); // 提示文本
                } else {
                    tabComplete.write(Types.OPTIONAL_TAG, null); // 提示文本
                }
            }
            tabComplete.send(BedrockProtocol.class);
        });
    }

    static void rewriteClientboundText(final PacketWrapper wrapper) {
        final ByteBuf input = ((PacketWrapperImpl) wrapper).getInputBuffer();
        if (input == null) {
            wrapper.cancel();
            return;
        }

        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int protocol = BedrockWire.effectiveProtocol();
        final TextPacketLayout.DecodedText decoded = TextPacketLayout.readPacket(input, emulateNetEase, protocol);
        wrapper.clearInputBuffer();
        final Function<String, String> translator = wrapper.user().get(ResourcePackStorage.class).getTexts().lookup();
        String originalMessage = decoded.message();
        boolean javaTellrawMessage = false;
        try {
            switch (decoded.type()) {
                case chat, whisper, announcement -> {
                    String message = originalMessage;
                    if (decoded.localize()) {
                        message = BedrockTranslator.translate(message, translator, new Object[0]);
                    }
                    if (decoded.type() == TextPacketType.chat && !decoded.sourceName().isEmpty()) {
                        message = BedrockTranslator.translate("chat.type.text", translator, new String[]{decoded.sourceName(), message}, TranslatorOptions.SKIP_ARGS_TRANSLATION);
                    } else if (decoded.type() == TextPacketType.whisper) {
                        message = BedrockTranslator.translate("chat.type.text", translator, new String[]{decoded.sourceName(), BedrockTranslator.translate("§7§o%commands.message.display.incoming", translator, new String[]{decoded.sourceName(), message})}, TranslatorOptions.SKIP_ARGS_TRANSLATION);
                    }
                    wrapper.write(Types.TAG, TextUtil.stringToNbt(message));
                    wrapper.write(Types.BOOLEAN, false);
                }
                case textObjectWhisper, textObject, textObjectAnnouncement -> {
                    String message = originalMessage;
                    final RootBedrockComponent rootComponent = BedrockComponentSerializer.deserialize(message);
                    rootComponent.forEach(c -> {
                        if (c instanceof TranslationBedrockComponent translation) {
                            translation.setTranslator(translator);
                        }
                    });
                    message = rootComponent.asString();
                    if (decoded.localize()) {
                        message = BedrockTranslator.translate(message, translator, new Object[0]);
                    }
                    wrapper.write(Types.TAG, TextUtil.stringToNbt(message));
                    wrapper.write(Types.BOOLEAN, false);
                }
                case raw, systemMessage, tip -> {
                    String message = originalMessage;
                    if (decoded.type() == TextPacketType.raw && message.startsWith(ProtocolConstants.JAVA_TELLRAW_MAGIC_HEADER)) {
                        javaTellrawMessage = true;
                        final int wireSize = Utf8.encodedLength(message);
                        if (wireSize > ProtocolConstants.JAVA_TELLRAW_MAX_WIRE_BYTES) {
                            throw new IllegalArgumentException("Java tellraw envelope exceeds " + ProtocolConstants.JAVA_TELLRAW_MAX_WIRE_BYTES + " UTF-8 bytes: " + wireSize);
                        }
                        wrapper.write(Types.TAG, TextUtil.javaTellrawJsonToNbt(message.substring(ProtocolConstants.JAVA_TELLRAW_MAGIC_HEADER.length())));
                        wrapper.write(Types.BOOLEAN, false);
                        break;
                    }
                    if (decoded.localize()) {
                        message = BedrockTranslator.translate(message, translator, new Object[0]);
                    }
                    wrapper.write(Types.TAG, TextUtil.stringToNbt(message));
                    wrapper.write(Types.BOOLEAN, decoded.type() == TextPacketType.tip);
                }
                case translate, popup, jukeboxPopup -> {
                    String message = originalMessage;
                    if (decoded.localize()) {
                        message = BedrockTranslator.translate(message, translator, decoded.parameters());
                    }
                    wrapper.write(Types.TAG, TextUtil.stringToNbt(message));
                    wrapper.write(Types.BOOLEAN, decoded.type() == TextPacketType.popup || decoded.type() == TextPacketType.jukeboxPopup);
                }
                default -> throw new IllegalStateException("Unhandled TextPacketType: " + decoded.type());
            }
        } catch (Throwable e) {
            if (javaTellrawMessage) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while parsing Java tellraw message", e);
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while translating '" + originalMessage + "'", e);
            }
            wrapper.cancel();
        }
    }

    static void writeServerboundChatHeader(final PacketWrapper wrapper, final TextPacketType type) {
        final boolean legacy = TextPacketLayout.isLegacyTypeFirstLayout();
        if (legacy) {
            wrapper.write(Types.UNSIGNED_BYTE, (short) type.getValue());
            wrapper.write(Types.BOOLEAN, false);
            return;
        }
        wrapper.write(Types.BOOLEAN, false);
        final byte category = TextPacketLayout.categoryOf(type);
        wrapper.write(Types.UNSIGNED_BYTE, (short) category);
        if (TextPacketLayout.usesCategoryNameStrings()) {
            for (final String name : TextPacketLayout.categoryNameStrings(category)) {
                wrapper.write(BedrockTypes.STRING, name);
            }
        }
        wrapper.write(Types.UNSIGNED_BYTE, (short) type.getValue());
    }

    static void writeServerboundChatParameters(final PacketWrapper wrapper) {
        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int protocol = BedrockWire.effectiveProtocol();
        if (TextPacketLayout.usesInlineNetEaseChatParameters(emulateNetEase, protocol)) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // 网易聊天参数列表（897..923）
        }
    }

    static void writeServerboundChatTrailer(final PacketWrapper wrapper) {
        wrapper.write(BedrockTypes.STRING, wrapper.user().get(AuthData.class).getXuid());
        wrapper.write(BedrockTypes.STRING, "");
        final boolean emulateNetEase = ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int protocol = BedrockWire.effectiveProtocol();
        if (TextPacketLayout.usesRequiredFilteredString(emulateNetEase, protocol)) {
            wrapper.write(BedrockTypes.STRING, "");
        } else {
            wrapper.write(BedrockTypes.OPTIONAL_STRING, null);
        }
        if (TextPacketLayout.usesNetEaseParameterList(emulateNetEase, protocol, TextPacketType.chat)
                && !TextPacketLayout.usesInlineNetEaseChatParameters(emulateNetEase, protocol)) {
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // 网易聊天参数列表（897 之前）
        }
    }

}
