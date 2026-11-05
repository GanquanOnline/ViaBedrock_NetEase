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
package net.raphimc.viabedrock.protocol.data;

/**
 * 官方基岩版线格式在版本之间的差异项。
 * <p>
 * 每项用协议号区间 {@code [introducedAt, removedAt)} 描述自己存在的时间段，
 * {@code removedAt == 0} 表示到最新支持版本仍然存在。区间是唯一事实来源，因此
 * 新增一个协议号通常只需要追加档案行，不改任何布局分支，未知协议号也不会静默
 * 退回最近档案的布局。
 * <p>
 * 与网易轴的区别：官方轴没有"网易独有/追平"之分，前向窗口（M3）用
 * {@code introducedAt} 表达，后向窗口（M4）用 {@code removedAt} 表达。
 * <p>
 * 枚举顺序决定位掩码，只允许追加，不要插入或重排。
 */
public enum BedrockDeviation {

    // ---- 1001 (1.26.30) ----

    /**
     * 物品堆从旧槽位编码换到网络物品堆描述符。INVENTORY_CONTENT、MOB_ARMOR_EQUIPMENT、
     * INVENTORY_TRANSACTION（动作与手持物）都走这一条；INVENTORY_SLOT 本来就提前用了描述符。
     */
    ITEM_STACK_DESCRIPTOR(1001, 0,
            "物品堆改用网络物品堆描述符"),

    /** legacy 槽位从“有就写”改成“先写存在性 bool 再写槽位”。 */
    INVENTORY_LEGACY_SLOT_FLAG(1001, 0,
            "INVENTORY_TRANSACTION 的 legacy 槽位前多一个存在性 bool"),

    /** transactionType 前与动作数前各多一个恒 true 的 bool，2192 又被移除。 */
    INVENTORY_CONSTANT_BOOLEANS(1001, 2192,
            "INVENTORY_TRANSACTION 在 transactionType 与动作数前各多一个恒 true 的 bool"),

    /** 动作来源拆成两组“外层存在性 + 内层存在性”的 bool 对，windowId 改为有符号单字节。 */
    INVENTORY_SOURCE_PRESENCE_PAIRS(1001, 2192,
            "INVENTORY_TRANSACTION 动作来源改为 bool 存在性对，windowId 改为有符号单字节"),

    /** actionType 改有符号 varint，triggerType / face / 客户端交互预测改为单字节。 */
    INVENTORY_BYTE_ENUMS(1001, 0,
            "INVENTORY_TRANSACTION 的 actionType / triggerType / face / 客户端交互预测改为单字节或有符号 varint"),

    /** START_GAME 层级设置末段追加编辑器连接策略（varint）与匿名方块掉落（bool）。 */
    START_GAME_EDITOR_FIELDS(1001, 0,
            "START_GAME 层级设置末段追加编辑器连接策略与匿名掉落字段"),

    /** START_GAME 在服务端权威音效后多一个 loggingChat 的 bool，2168 又移除。 */
    START_GAME_LOGGING_CHAT(1001, 2168,
            "START_GAME 在服务端权威音效后多一个 loggingChat 的 bool"),

    /** BOSS_EVENT 改为定长布局：类型单字节，玩家 id / 标题 / 进度 / 颜色 / 叠加层恒在，暗化屏幕消失。 */
    BOSS_EVENT_UNIFIED_LAYOUT(1001, 0,
            "BOSS_EVENT 改为定长布局：类型单字节，玩家 id 与颜色/叠加层恒在，暗化屏幕消失"),

    /** CLIENT_CACHE_BLOB_STATUS 的两个计数从共享前缀改为各自数组前的内联头。 */
    BLOB_STATUS_INTERLEAVED_HASHES(1001, 0,
            "CLIENT_CACHE_BLOB_STATUS 的缺失/已确认计数改为各自数组前的内联头"),

    /** SUB_CHUNK_REQUEST 先写偏移再写基准原点，原点从三个 zigzag varint 改为三个小端 int32。 */
    SUB_CHUNK_REQUEST_OFFSETS_FIRST(1001, 0,
            "SUB_CHUNK_REQUEST 先写偏移再写基准原点，原点改为三个小端 int32"),

    /** LEVEL_SOUND_EVENT 的声音从数字改成按名字字符串传输。 */
    LEVEL_SOUND_EVENT_NAME(1001, 0,
            "LEVEL_SOUND_EVENT 的声音改为按名字字符串传输"),

    ;

    /** 位掩码容量，偏差集用一个 long 存放。 */
    public static final int MAX_DEVIATIONS = Long.SIZE;

    private final int introducedAt;
    private final int removedAt;
    private final String description;
    private final long bit;

    BedrockDeviation(final int introducedAt, final int removedAt, final String description) {
        this.introducedAt = introducedAt;
        this.removedAt = removedAt;
        this.description = description;
        this.bit = 1L << this.ordinal();
    }

    public boolean appliesTo(final int protocolVersion) {
        return inRange(this.introducedAt, this.removedAt, protocolVersion);
    }

    /** 半开区间判定，单独抽出来让区间语义能直接测。 */
    static boolean inRange(final int introducedAt, final int removedAt, final int protocolVersion) {
        return protocolVersion >= introducedAt
                && (removedAt == 0 || protocolVersion < removedAt);
    }

    /** @return 该偏差出现的最小协议号，0 表示已知最早版本就存在 */
    public int introducedAt() {
        return this.introducedAt;
    }

    /** @return 该偏差消失的协议号，0 表示尚未消失 */
    public int removedAt() {
        return this.removedAt;
    }

    public String description() {
        return this.description;
    }

    public long bit() {
        return this.bit;
    }

}
