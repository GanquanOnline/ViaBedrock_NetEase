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
package net.raphimc.viabedrock.experimental.npc;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.mcstructs.converter.impl.v1_21_5.NbtConverter_v1_21_5;
import com.viaversion.viaversion.libs.mcstructs.core.Identifier;
import com.viaversion.viaversion.libs.mcstructs.dialog.ActionButton;
import com.viaversion.viaversion.libs.mcstructs.dialog.AfterAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.action.CustomAllAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.Dialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.body.PlainMessageBody;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.MultiActionDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.serializer.DialogSerializer;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTextUtils;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.NpcDialoguePacket_NpcDialogueActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.NpcRequestPacket_RequestType;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.logging.Level;

/**
 * 处理 NPC 对话数据包（NPC_DIALOGUE 为服务端→客户端，NPC 响应经 CUSTOM_CLICK_ACTION 回传）。
 * NpcDialogueState 存放在 InventoryTracker 中，因为它参与 isAnyScreenOpen() 检查。
 */
public class NpcDialogueModule implements FeatureModule {

    private static final int DIALOG_BUTTON_WIDTH = 200;

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        registerNpcDialogueHandler(protocol);
        registerNpcClickActionHandler(protocol);
    }

    private void registerNpcDialogueHandler(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.NPC_DIALOGUE, null, wrapper -> {
            wrapper.cancel();

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long npcEntityUniqueId = wrapper.read(BedrockTypes.LONG_LE); // NPC 实体唯一 ID
            final int rawActionType = wrapper.read(BedrockTypes.VAR_INT); // 动作类型
            final NpcDialoguePacket_NpcDialogueActionType actionType = NpcDialoguePacket_NpcDialogueActionType.getByValue(rawActionType);
            final String dialogue = wrapper.read(BedrockTypes.STRING); // 对话
            final String sceneName = wrapper.read(BedrockTypes.STRING); // 场景名称
            final String npcName = wrapper.read(BedrockTypes.STRING); // NPC 名称
            final String actionJson = wrapper.read(BedrockTypes.STRING); // 动作 JSON

            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "NPC_DIALOGUE: uniqueId=" + npcEntityUniqueId + " actionType=" + rawActionType + " dialogue=\"" + dialogue + "\" sceneName=\"" + sceneName + "\" npcName=\"" + npcName + "\" actionJson=\"" + actionJson + "\"");

            if (actionType == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown NpcDialogueActionType: " + rawActionType);
                return;
            }

            if (actionType == NpcDialoguePacket_NpcDialogueActionType.Close) {
                if (inventoryTracker.getCurrentNpcDialogue() != null) {
                    inventoryTracker.setCurrentNpcDialogue(null);
                    final PacketWrapper clearDialog = PacketWrapper.create(ClientboundPackets26_1.CLEAR_DIALOG, wrapper.user());
                    clearDialog.send(BedrockProtocol.class);
                }
                return;
            }

            // actionType == Open
            if (inventoryTracker.isAnyScreenOpen()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open NPC dialogue while another screen is open");
                return;
            }

            final Entity npcEntity = entityTracker.getEntityByUid(npcEntityUniqueId);
            final long npcEntityRuntimeId;
            if (npcEntity != null) {
                npcEntityRuntimeId = npcEntity.runtimeId();
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "NPC entity not found for unique ID: " + npcEntityUniqueId);
                npcEntityRuntimeId = npcEntityUniqueId;
            }

            inventoryTracker.setCurrentNpcDialogue(new InventoryTracker.NpcDialogueState(npcEntityUniqueId, npcEntityRuntimeId, sceneName));

            final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
            final Identifier responseIdentifier = Identifier.of("viabedrock", "npc/" + sceneName);

            final CompoundTag exitButtonAdditions = new CompoundTag();
            exitButtonAdditions.putBoolean("exit", true);
            final ActionButton exitButton = new ActionButton(new StringComponent(resourcePackStorage.getTexts().get("gui.close")), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, exitButtonAdditions));

            final MultiActionDialog dialog = new MultiActionDialog(
                    TextUtil.stringToTextComponent(npcName.isEmpty() ? "NPC" : TextUtil.toSingleLine(npcName)),
                    true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);

            addTextToDialog(dialogue.isEmpty() ? " " : dialogue, dialog);

            if (!actionJson.isEmpty()) {
                try {
                    final com.viaversion.viaversion.libs.gson.JsonArray buttonsArray =
                            com.viaversion.viaversion.libs.gson.JsonParser.parseString(actionJson).getAsJsonArray();
                    for (int i = 0; i < buttonsArray.size(); i++) {
                        final com.viaversion.viaversion.libs.gson.JsonObject buttonObj = buttonsArray.get(i).getAsJsonObject();
                        final int mode = buttonObj.has("mode") ? buttonObj.get("mode").getAsInt() : 0;
                        if (mode != 0) continue; // 只显示 mode=0 (BUTTON) 的按钮

                        final String buttonName = buttonObj.has("button_name") ? buttonObj.get("button_name").getAsString() : "";
                        final String buttonText = buttonObj.has("text") ? buttonObj.get("text").getAsString() : "";
                        final String displayText = !buttonName.isEmpty() ? buttonName : buttonText;
                        final CompoundTag buttonAdditions = new CompoundTag();
                        buttonAdditions.putInt("button_index", i);
                        dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(displayText)), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, buttonAdditions)));
                    }
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error parsing NPC action JSON: " + actionJson, e);
                }
            }

            // MultiActionDialog 序列化时至少需要一个动作按钮
            if (dialog.getActions().isEmpty()) {
                dialog.getActions().add(exitButton);
            }

            try {
                final PacketWrapper showDialog = PacketWrapper.create(ClientboundPackets26_1.SHOW_DIALOG, wrapper.user());
                showDialog.write(Types.TRUSTED_COMPOUND_TAG_HOLDER, Holder.of((CompoundTag) DialogSerializer.V1_21_6.getDirectCodec().serialize(NbtConverter_v1_21_5.INSTANCE, dialog).get())); // 对话数据
                showDialog.send(BedrockProtocol.class);
            } catch (Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error serializing NPC dialogue dialog", e);
                inventoryTracker.setCurrentNpcDialogue(null);
                return;
            }

            // 发送 ExecuteOpeningCommands 以触发 mode=2 按钮的命令
            final PacketWrapper openingRequest = PacketWrapper.create(ServerboundBedrockPackets.NPC_REQUEST, wrapper.user());
            openingRequest.write(BedrockTypes.UNSIGNED_VAR_LONG, npcEntityRuntimeId); // 实体运行时 ID
            openingRequest.write(Types.BYTE, (byte) NpcRequestPacket_RequestType.ExecuteOpeningCommands.getValue()); // 类型
            openingRequest.write(BedrockTypes.STRING, ""); // 命令
            openingRequest.write(Types.BYTE, (byte) 0); // 动作索引
            openingRequest.write(BedrockTypes.STRING, sceneName); // 场景名称
            openingRequest.sendToServer(BedrockProtocol.class);
        });
    }

    private void registerNpcClickActionHandler(final BedrockProtocol protocol) {
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CUSTOM_CLICK_ACTION, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentNpcDialogue() == null) {
                return; // 不是 NPC 对话响应，交给表单处理器处理
            }

            // NPC 对话处于激活状态 —— 读取并处理响应
            wrapper.cancel();
            final String id = wrapper.read(Types.STRING); // ID
            final CompoundTag payload = (CompoundTag) wrapper.read(Types.CUSTOM_CLICK_ACTION_TAG); // 负载
            final InventoryTracker.NpcDialogueState npcState = inventoryTracker.getCurrentNpcDialogue();

            if (!id.equals("viabedrock:npc/" + npcState.sceneName())) {
                return;
            }

            if (payload.contains("exit") && payload.getBoolean("exit")) {
                inventoryTracker.closeCurrentNpcDialogue();
                return;
            }

            final int buttonIndex = payload.getInt("button_index");
            inventoryTracker.setCurrentNpcDialogue(null);

            final PacketWrapper npcRequest = PacketWrapper.create(ServerboundBedrockPackets.NPC_REQUEST, wrapper.user());
            npcRequest.write(BedrockTypes.UNSIGNED_VAR_LONG, npcState.npcEntityRuntimeId()); // 实体运行时 ID
            npcRequest.write(Types.BYTE, (byte) NpcRequestPacket_RequestType.ExecuteAction.getValue()); // 类型
            npcRequest.write(BedrockTypes.STRING, ""); // 命令
            npcRequest.write(Types.BYTE, (byte) buttonIndex); // 动作索引
            npcRequest.write(BedrockTypes.STRING, npcState.sceneName()); // 场景名称
            npcRequest.sendToServer(BedrockProtocol.class);
        });
    }

    private static void addTextToDialog(final String text, final Dialog dialog) {
        for (final String line : BedrockTextUtils.split(text, "\n")) {
            dialog.getBody().add(new PlainMessageBody(TextUtil.stringToTextComponent(line)));
        }
    }

}
