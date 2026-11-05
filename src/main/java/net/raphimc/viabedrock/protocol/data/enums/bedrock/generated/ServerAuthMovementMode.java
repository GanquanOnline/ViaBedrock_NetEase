// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum ServerAuthMovementMode {

    LegacyClientAuthoritativeV1_Deprecated(0),
    /**
     * 本文档其余部分称其为“客户端权威”。<br>
     * 该模式是当前的默认模式，预览中即将提供迁移到服务端权威的方案<br>
     * 客户端发往服务端的包与服务器权威模式基本相同，详见其文档：<br>
     * - PlayerAuthInputPacket（主要运动与输入数据）<br>
     * - InventoryTransactionPacket（物品使用，与移动间接相关）<br>
     * 某些情况下会发送 PlayerActionPacket，另一些情况下它已部分并入 PlayerAuthInputPacket。<br>
     * 可通过以下方式重新定位客户端：<br>
     * - MovePlayerPacket<br>
     * - SetActorMotionPacket
     */
    ClientAuthoritativeV2(1),
    /**
     * 本文档其余部分称其为“服务端权威”<br>
     * 在即将到来的预览之后，该模式旨在成为新的默认模式。<br>
     * 客户端发往服务端的包类似。<br>
     * - PlayerAuthInputPacket（主要运动与输入数据）<br>
     * - InventoryTransactionPacket（物品使用，与移动间接相关）<br>
     * 某些情况下会发送 PlayerActionPacket，另一些情况下它已部分并入 PlayerAuthInputPacket。<br>
     * 可通过以下方式重新定位客户端：<br>
     * - MovePlayerPacket<br>
     * - CorrectPlayerMovePredictionPacket<br>
     * - SetActorMotionPacket<br>
     * 此外，该模式下许多服务端→客户端包带有 'Tick' 值。这些值回显客户端在 PlayerAuthInputPacket 中提供的 tick 值。<br>
     * 对于与玩家或客户端预测载具相关的包，tick 值应取该玩家最近一次处理过的 PlayerAuthInputPacket 的值。<br>
     * 指定为零也是可接受的，但可能造成轻微的画面闪烁，因为它可能干扰客户端预测动作。
     */
    ServerAuthoritativeV3(2),
    ;

    private static final Int2ObjectMap<ServerAuthMovementMode> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (ServerAuthMovementMode value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static ServerAuthMovementMode getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static ServerAuthMovementMode getByValue(final int value, final ServerAuthMovementMode fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static ServerAuthMovementMode getByName(final String name) {
        for (ServerAuthMovementMode value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static ServerAuthMovementMode getByName(final String name, final ServerAuthMovementMode fallback) {
        for (ServerAuthMovementMode value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    ServerAuthMovementMode(final ServerAuthMovementMode value) {
        this(value.value);
    }

    ServerAuthMovementMode(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
