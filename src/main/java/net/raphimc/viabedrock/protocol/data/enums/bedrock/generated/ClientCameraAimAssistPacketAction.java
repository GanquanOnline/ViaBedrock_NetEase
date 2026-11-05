// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum ClientCameraAimAssistPacketAction {

    /** 设置瞄准辅助使用 CameraPresets 中 aim_assist 字段的配置 */
    SetFromCameraPreset(0),
    /** 清除瞄准辅助设置 */
    Clear(1),
    ;

    private static final Int2ObjectMap<ClientCameraAimAssistPacketAction> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (ClientCameraAimAssistPacketAction value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static ClientCameraAimAssistPacketAction getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static ClientCameraAimAssistPacketAction getByValue(final int value, final ClientCameraAimAssistPacketAction fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static ClientCameraAimAssistPacketAction getByName(final String name) {
        for (ClientCameraAimAssistPacketAction value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static ClientCameraAimAssistPacketAction getByName(final String name, final ClientCameraAimAssistPacketAction fallback) {
        for (ClientCameraAimAssistPacketAction value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    ClientCameraAimAssistPacketAction(final ClientCameraAimAssistPacketAction value) {
        this(value.value);
    }

    ClientCameraAimAssistPacketAction(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
