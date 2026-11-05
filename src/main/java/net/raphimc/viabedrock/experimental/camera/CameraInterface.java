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
package net.raphimc.viabedrock.experimental.camera;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.CameraPacketLayout;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.BedrockWire;

import java.nio.charset.StandardCharsets;

public class CameraInterface {

    public static final String CONFIRM_CHANNEL = "becamera:confirm";
    public static final String CHANNEL = "becamera:data";

    public static void confirmPresence(final UserConnection user) {
        // 必须使用 CONFIGURATION 数据包类型，因为这段代码在通道注册期间（CONFIGURATION 状态）被调用
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CONFIRM.ordinal());
        pluginMessage.send(BedrockProtocol.class);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CAMERA_SHAKE, null, wrapper -> {
            wrapper.cancel();
            final float intensity = wrapper.read(BedrockTypes.FLOAT_LE);
            final float duration = wrapper.read(BedrockTypes.FLOAT_LE);
            final byte type = wrapper.read(Types.BYTE);
            final byte action = wrapper.read(Types.BYTE);

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraShake(wrapper.user(), intensity, duration, type, action);
        });

        // UnhandledPackets 已经会取消 CAMERA_PRESETS，这样在实验性相机关闭时残留字节不会中断加入批次。
        // 当 BECamera 转换被注册时，要覆盖这个取消行为（参考：CameraInterface 与 UnhandledPackets）。
        protocol.replaceClientbound(ClientboundBedrockPackets.CAMERA_PRESETS, wrapper -> {
            wrapper.cancel();
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            final boolean emulateNetEase = emulateNetEase();
            final int cameraProtocolVersion = cameraProtocol();

            final String[] names = new String[count];
            final String[] parents = new String[count];
            final Float[] posXs = new Float[count];
            final Float[] posYs = new Float[count];
            final Float[] posZs = new Float[count];
            final Float[] rotXs = new Float[count];
            final Float[] rotYs = new Float[count];
            final Byte[] audioListeners = new Byte[count];
            final Boolean[] playerEffectsArr = new Boolean[count];

            for (int i = 0; i < count; i++) {
                names[i] = wrapper.read(BedrockTypes.STRING);
                parents[i] = wrapper.read(BedrockTypes.STRING);

                posXs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                posYs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                posZs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                rotXs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;
                rotYs[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.FLOAT_LE) : null;

                // 可选字段是带版本区分的，必须严格按基岩 CameraPresets 数据包使用的顺序读取。
                if (CameraPacketLayout.usesRotationSpeed(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalFloat(wrapper); // rotation_speed（旋转速度）
                    skipOptionalBoolean(wrapper); // snap_to_target（对齐到目标）
                    if (CameraPacketLayout.usesRotationLimits(emulateNetEase, cameraProtocolVersion)) {
                        skipOptionalVec2f(wrapper); // horizontal_rotation_limit（水平旋转限制）
                        skipOptionalVec2f(wrapper); // vertical_rotation_limit（垂直旋转限制）
                        skipOptionalBoolean(wrapper); // continue_targeting（继续追踪目标）
                    }
                }
                if (CameraPacketLayout.usesBlockListeningRadius(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalFloat(wrapper); // block_listening_radius（方块监听半径）
                }
                if (CameraPacketLayout.usesBaseCameraOptions(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalVec2f(wrapper); // view_offset（视角偏移）
                }
                if (CameraPacketLayout.usesRotationSpeed(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalVec3f(wrapper); // entity_offset（实体偏移）
                }
                if (CameraPacketLayout.usesBaseCameraOptions(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalFloat(wrapper); // radius（半径）
                }
                if (CameraPacketLayout.usesYawLimits(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalFloat(wrapper); // yaw_limit_min（偏航角下限）
                    skipOptionalFloat(wrapper); // yaw_limit_max（偏航角上限）
                }
                audioListeners[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(Types.BYTE) : null;
                playerEffectsArr[i] = wrapper.read(Types.BOOLEAN) ? wrapper.read(Types.BOOLEAN) : null;
                if (CameraPacketLayout.usesAlignTargetAndCameraForward(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalBoolean(wrapper); // align_target_and_camera_forward（对齐目标与相机朝向）
                }
                // aim_assist（可选，嵌套）
                if (CameraPacketLayout.usesAimAssist(emulateNetEase, cameraProtocolVersion) && wrapper.read(Types.BOOLEAN)) {
                    skipOptionalString(wrapper); // preset_id（预设 ID）
                    skipOptionalIntLE(wrapper); // target_mode（目标模式）
                    skipOptionalVec2f(wrapper); // angle（角度）
                    skipOptionalFloat(wrapper); // distance（距离）
                }
                if (CameraPacketLayout.usesControlScheme(emulateNetEase, cameraProtocolVersion)) {
                    skipOptionalByte(wrapper); // control_scheme（控制方案）
                }
            }

            final CameraAudioTracker cameraAudio = wrapper.user().get(CameraAudioTracker.class);
            if (cameraAudio != null) {
                cameraAudio.setPresets(names, parents, posXs, posYs, posZs, audioListeners);
            }

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraPresets(wrapper.user(), count, names, parents, posXs, posYs, posZs, rotXs, rotYs, audioListeners, playerEffectsArr);
        });

        protocol.registerClientbound(ClientboundBedrockPackets.CAMERA_INSTRUCTION, null, wrapper -> {
            wrapper.cancel();

            boolean hasSet = false;
            boolean hasClear = false;
            boolean hasFade = false;

            // Set 指令字段
            int presetRuntimeId = 0;
            byte easingType = 0;
            float easeDuration = 0;
            boolean hasEase = false;
            Position3f setPos = null;
            float rotX = 0, rotY = 0;
            boolean hasRot = false;
            Position3f facing = null;
            boolean isDefault = false;

            // Fade 指令字段
            float fadeIn = 0, fadeStay = 0, fadeOut = 0;
            boolean hasFadeTime = false;
            float fadeR = 0, fadeG = 0, fadeB = 0;
            boolean hasFadeColor = false;

            // CameraSetInstruction（可选）
            hasSet = wrapper.read(Types.BOOLEAN);
            if (hasSet) {
                presetRuntimeId = wrapper.read(BedrockTypes.INT_LE);

                hasEase = wrapper.read(Types.BOOLEAN);
                if (hasEase) {
                    easingType = wrapper.read(Types.BYTE);
                    easeDuration = wrapper.read(BedrockTypes.FLOAT_LE);
                }

                if (wrapper.read(Types.BOOLEAN)) {
                    setPos = wrapper.read(BedrockTypes.POSITION_3F);
                }

                hasRot = wrapper.read(Types.BOOLEAN);
                if (hasRot) {
                    rotX = wrapper.read(BedrockTypes.FLOAT_LE);
                    rotY = wrapper.read(BedrockTypes.FLOAT_LE);
                }

                if (wrapper.read(Types.BOOLEAN)) {
                    facing = wrapper.read(BedrockTypes.POSITION_3F);
                }

                skipOptionalVec2f(wrapper); // view_offset（视角偏移）
                skipOptionalVec3f(wrapper); // entity_offset（实体偏移）

                if (wrapper.read(Types.BOOLEAN)) {
                    isDefault = wrapper.read(Types.BOOLEAN);
                }

                if (CameraPacketLayout.usesRemoveIgnoreStartingValues(emulateNetEase(), cameraProtocol())) {
                    wrapper.read(Types.BOOLEAN); // remove_ignore_starting_values_component（移除忽略起始值组件）
                }
            }

            // Clear 指令（可选）
            hasClear = wrapper.read(Types.BOOLEAN);
            if (hasClear) {
                wrapper.read(Types.BOOLEAN); // clear 值
            }

            // Fade 指令（可选）
            hasFade = wrapper.read(Types.BOOLEAN);
            if (hasFade) {
                hasFadeTime = wrapper.read(Types.BOOLEAN);
                if (hasFadeTime) {
                    fadeIn = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeStay = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeOut = wrapper.read(BedrockTypes.FLOAT_LE);
                }
                hasFadeColor = wrapper.read(Types.BOOLEAN);
                if (hasFadeColor) {
                    fadeR = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeG = wrapper.read(BedrockTypes.FLOAT_LE);
                    fadeB = wrapper.read(BedrockTypes.FLOAT_LE);
                }
            }

            // 在协议 >= 712/827/859 上，MOT CameraInstructionPacket.decode() 在 fade 之后仍会继续读取。
            // Java 的 BECamera 载荷仍然只携带 set/clear/fade，因此要消费掉剩余字节，而不是中断加入批次（参考：CameraInstructionPacket）。
            skipLeftoverCameraInstruction(wrapper, emulateNetEase(), cameraProtocol());

            final CameraAudioTracker cameraAudio = wrapper.user().get(CameraAudioTracker.class);
            if (cameraAudio != null) {
                cameraAudio.applyInstruction(hasSet, presetRuntimeId, setPos, hasClear);
            }

            if (!wrapper.user().get(ChannelStorage.class).hasChannel(CONFIRM_CHANNEL)) {
                return;
            }
            sendCameraInstruction(wrapper.user(),
                    hasSet, presetRuntimeId, hasEase, easingType, easeDuration,
                    setPos, hasRot, rotX, rotY, facing, isDefault,
                    hasClear,
                    hasFade, hasFadeTime, fadeIn, fadeStay, fadeOut,
                    hasFadeColor, fadeR, fadeG, fadeB);
        });
    }

    // --- 载荷发送方法 ---

    public static void sendCameraShake(final UserConnection user, final float intensity, final float duration, final byte type, final byte action) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_SHAKE.ordinal());
        pluginMessage.write(Types.FLOAT, intensity);
        pluginMessage.write(Types.FLOAT, duration);
        pluginMessage.write(Types.BYTE, type);
        pluginMessage.write(Types.BYTE, action);
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    public static void sendCameraPresets(final UserConnection user, final int count,
                                         final String[] names, final String[] parents,
                                         final Float[] posXs, final Float[] posYs, final Float[] posZs,
                                         final Float[] rotXs, final Float[] rotYs,
                                         final Byte[] audioListeners, final Boolean[] playerEffects) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_PRESETS.ordinal());
        pluginMessage.write(Types.INT, count);
        for (int i = 0; i < count; i++) {
            writeString(pluginMessage, names[i]);
            writeString(pluginMessage, parents[i]);
            writeOptionalFloat(pluginMessage, posXs[i]);
            writeOptionalFloat(pluginMessage, posYs[i]);
            writeOptionalFloat(pluginMessage, posZs[i]);
            writeOptionalFloat(pluginMessage, rotXs[i]);
            writeOptionalFloat(pluginMessage, rotYs[i]);
            writeOptionalByte(pluginMessage, audioListeners[i]);
            writeOptionalBoolean(pluginMessage, playerEffects[i]);
        }
        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    public static void sendCameraInstruction(final UserConnection user,
                                              final boolean hasSet, final int presetRuntimeId,
                                              final boolean hasEase, final byte easingType, final float easeDuration,
                                              final Position3f setPos, final boolean hasRot, final float rotX, final float rotY,
                                              final Position3f facing, final boolean isDefault,
                                              final boolean hasClear,
                                              final boolean hasFade, final boolean hasFadeTime,
                                              final float fadeIn, final float fadeStay, final float fadeOut,
                                              final boolean hasFadeColor, final float fadeR, final float fadeG, final float fadeB) {
        final PacketWrapper pluginMessage = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        pluginMessage.write(Types.STRING, CHANNEL);
        pluginMessage.write(Types.INT, PayloadType.CAMERA_INSTRUCTION.ordinal());

        // 标志位
        pluginMessage.write(Types.BOOLEAN, hasSet);
        pluginMessage.write(Types.BOOLEAN, hasClear);
        pluginMessage.write(Types.BOOLEAN, hasFade);

        if (hasSet) {
            pluginMessage.write(Types.INT, presetRuntimeId);
            pluginMessage.write(Types.BOOLEAN, hasEase);
            if (hasEase) {
                pluginMessage.write(Types.BYTE, easingType);
                pluginMessage.write(Types.FLOAT, easeDuration);
            }
            pluginMessage.write(Types.BOOLEAN, setPos != null);
            if (setPos != null) {
                pluginMessage.write(Types.FLOAT, setPos.x());
                pluginMessage.write(Types.FLOAT, setPos.y());
                pluginMessage.write(Types.FLOAT, setPos.z());
            }
            pluginMessage.write(Types.BOOLEAN, hasRot);
            if (hasRot) {
                pluginMessage.write(Types.FLOAT, rotX);
                pluginMessage.write(Types.FLOAT, rotY);
            }
            pluginMessage.write(Types.BOOLEAN, facing != null);
            if (facing != null) {
                pluginMessage.write(Types.FLOAT, facing.x());
                pluginMessage.write(Types.FLOAT, facing.y());
                pluginMessage.write(Types.FLOAT, facing.z());
            }
            pluginMessage.write(Types.BOOLEAN, isDefault);
        }

        if (hasFade) {
            pluginMessage.write(Types.BOOLEAN, hasFadeTime);
            if (hasFadeTime) {
                pluginMessage.write(Types.FLOAT, fadeIn);
                pluginMessage.write(Types.FLOAT, fadeStay);
                pluginMessage.write(Types.FLOAT, fadeOut);
            }
            pluginMessage.write(Types.BOOLEAN, hasFadeColor);
            if (hasFadeColor) {
                pluginMessage.write(Types.FLOAT, fadeR);
                pluginMessage.write(Types.FLOAT, fadeG);
                pluginMessage.write(Types.FLOAT, fadeB);
            }
        }

        pluginMessage.scheduleSend(BedrockProtocol.class);
    }

    // --- 辅助方法 ---

    private static void writeString(final PacketWrapper wrapper, final String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        wrapper.write(Types.INT, bytes.length);
        wrapper.write(Types.REMAINING_BYTES, bytes);
    }

    private static void writeOptionalFloat(final PacketWrapper wrapper, final Float value) {
        wrapper.write(Types.BOOLEAN, value != null);
        if (value != null) {
            wrapper.write(Types.FLOAT, value);
        }
    }

    private static void writeOptionalByte(final PacketWrapper wrapper, final Byte value) {
        wrapper.write(Types.BOOLEAN, value != null);
        if (value != null) {
            wrapper.write(Types.BYTE, value);
        }
    }

    private static void writeOptionalBoolean(final PacketWrapper wrapper, final Boolean value) {
        wrapper.write(Types.BOOLEAN, value != null);
        if (value != null) {
            wrapper.write(Types.BOOLEAN, value);
        }
    }

    private static void skipOptionalFloat(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    private static void skipOptionalBoolean(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(Types.BOOLEAN);
        }
    }

    private static void skipOptionalByte(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(Types.BYTE);
        }
    }

    private static void skipOptionalIntLE(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.INT_LE);
        }
    }

    private static void skipOptionalString(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.STRING);
        }
    }

    private static void skipOptionalVec2f(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    private static void skipOptionalVec3f(final PacketWrapper wrapper) {
        if (wrapper.read(Types.BOOLEAN)) {
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
            wrapper.read(BedrockTypes.FLOAT_LE);
        }
    }

    /**
     * 消费 fade 之后 MOT 的剩余字段：target（712）、FOV（827）、spline/attach（859）。
     * 样条旋转关键帧是 Vector3f（pitch/yaw/roll）加 time；Java 仍然丢弃 roll，因为 BECamera 的 {@code CameraPathManager} 会忽略它。
     */
    static void skipLeftoverCameraInstruction(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        if (CameraPacketLayout.usesCameraTarget(emulateNetEase, protocol)) {
            if (wrapper.read(Types.BOOLEAN)) {
                skipOptionalVec3f(wrapper); // target_center_offset（目标中心偏移）
                wrapper.read(BedrockTypes.LONG_LE); // unique_entity_id（唯一实体 ID）
            }
            skipOptionalBoolean(wrapper); // remove_target（移除目标）
        }
        if (CameraPacketLayout.usesCameraFov(emulateNetEase, protocol)) {
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.FLOAT_LE); // fov（视野）
                wrapper.read(BedrockTypes.FLOAT_LE); // ease_time（缓动时间）
                wrapper.read(Types.BYTE); // ease_type（缓动类型）
                wrapper.read(Types.BOOLEAN); // clear（清除）
            }
        }
        if (CameraPacketLayout.usesCameraSpline(emulateNetEase, protocol)) {
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.FLOAT_LE); // total_time（总时间）
                wrapper.read(Types.BYTE); // spline_type（样条类型）
                final int curveCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                for (int i = 0; i < curveCount; i++) {
                    wrapper.read(BedrockTypes.POSITION_3F);
                }
                final int progressCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                for (int i = 0; i < progressCount; i++) {
                    wrapper.read(BedrockTypes.FLOAT_LE); // value（值）
                    wrapper.read(BedrockTypes.FLOAT_LE); // time（时间）
                }
                final int rotationCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                for (int i = 0; i < rotationCount; i++) {
                    wrapper.read(BedrockTypes.POSITION_3F); // pitch/yaw/roll（俯仰/偏航/翻滚）
                    wrapper.read(BedrockTypes.FLOAT_LE); // time（时间）
                }
            }
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.LONG_LE); // attach unique_entity_id（附加到唯一实体 ID）
            }
            skipOptionalBoolean(wrapper); // detach_from_entity（从实体分离）
        }
    }

    private enum PayloadType {
        CONFIRM, CAMERA_INSTRUCTION, CAMERA_SHAKE, CAMERA_PRESETS
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int cameraProtocol() {
        if (emulateNetEase()) {
            final int configured = ViaBedrock.getConfig().getNetEaseProtocolVersion();
            return configured > 0 ? configured : BedrockWire.current();
        }
        return BedrockWire.current();
    }

}
