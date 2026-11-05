// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum TextPacketType {

    /** 未经格式化的原始文本消息 */
    raw(0),
    /** 带作者名的玩家聊天消息 */
    chat(1),
    /** 带翻译键和参数的本地化消息 */
    translate(2),
    /** 带翻译键和参数的弹窗通知 */
    popup(3),
    /** 唱片机“正在播放”弹窗通知 */
    jukeboxPopup(4),
    /** 动作栏提示消息 */
    tip(5),
    /** 系统生成的消息 */
    systemMessage(6),
    /** 玩家之间的私信 */
    whisper(7),
    /** 带作者的广播公告消息 */
    announcement(8),
    /** 使用文本对象格式的私信 */
    textObjectWhisper(9),
    /** 使用 JSON 文本对象格式的富文本消息 */
    textObject(10),
    /** 使用文本对象格式的广播公告 */
    textObjectAnnouncement(11),
    ;

    private static final Int2ObjectMap<TextPacketType> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (TextPacketType value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static TextPacketType getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static TextPacketType getByValue(final int value, final TextPacketType fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static TextPacketType getByName(final String name) {
        for (TextPacketType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static TextPacketType getByName(final String name, final TextPacketType fallback) {
        for (TextPacketType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    TextPacketType(final TextPacketType value) {
        this(value.value);
    }

    TextPacketType(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
