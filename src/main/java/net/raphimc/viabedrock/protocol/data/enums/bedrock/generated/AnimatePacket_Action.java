// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum AnimatePacket_Action {

    /** 未使用 */
    NoAction(0),
    /**
     * 客户端→服务端通知：挥动玩家手臂。服务器应把它重新广播给所有能看到手臂动作的对象<br>
     * 非常类似的动作另见 PlayerAuthInputPacket::InputData::MissedSwing
     */
    Swing(1),
    /** 服务端→客户端通知：停止在床上睡觉 */
    WakeUp(3),
    /** 服务端→客户端通知：播放暴击粒子 */
    CriticalHit(4),
    /** 未使用 */
    MagicCriticalHit(5),
    ;

    private static final Int2ObjectMap<AnimatePacket_Action> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (AnimatePacket_Action value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static AnimatePacket_Action getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static AnimatePacket_Action getByValue(final int value, final AnimatePacket_Action fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static AnimatePacket_Action getByName(final String name) {
        for (AnimatePacket_Action value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static AnimatePacket_Action getByName(final String name, final AnimatePacket_Action fallback) {
        for (AnimatePacket_Action value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    AnimatePacket_Action(final AnimatePacket_Action value) {
        this(value.value);
    }

    AnimatePacket_Action(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
