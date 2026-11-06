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
 * 网易客户端相对官方基岩版线格式的偏差项。
 * <p>
 * 每项用协议号区间 {@code [introducedAt, removedAt)} 描述自己存在的时间段，
 * {@code removedAt == 0} 表示尚未移除。区间是唯一事实来源，因此新增一个网易
 * 协议号通常不需要改任何布局分支：区间直接给出答案，未知协议号也不会静默退回
 * 最近档案。
 * <p>
 * {@link Kind#PERMANENT} 是网易独有的字段，官方不会追平；{@link Kind#CATCH_UP}
 * 是网易曾经落后官方留下的临时差，追平后消失。升级时只需要复核 CATCH_UP 的行。
 * <p>
 * 接入新的网易版本：先在 {@link NetEaseProtocolProfile} 追加一行档案，再用新客户端抓一份
 * START_GAME 对齐 CATCH_UP 行的区间，最后在 {@code NetEaseDeviationTest} 钉住该协议的偏差集。
 * 布局分支本身不用动。
 * <p>
 * 枚举顺序决定位掩码，只允许追加，不要插入或重排。
 */
public enum NetEaseDeviation {

    PLAYER_LIST_TRAILER(Kind.PERMANENT, 649, 0,
            "PLAYER_LIST 添加在可信皮肤数组之后按条目追加 bloom / persona 尾部"),
    TEXT_NETEASE_PARAMETER_LIST(Kind.CATCH_UP, 410, 924,
            "TEXT 携带网易聊天参数列表；官方 924 移除 category 名称串后一并消失"),
    TEXT_REQUIRED_FILTERED_STRING(Kind.CATCH_UP, 685, 897,
            "TEXT 尾部的 filtered 字段是必填字符串，而不是可选字符串"),
    TEXT_NETEASE_POPUP_TAIL(Kind.CATCH_UP, 410, 897,
            "TEXT 弹窗在过滤后的消息之后多带一个字符串"),
    BEHAVIOUR_PACK_STACK_TRAILER(Kind.CATCH_UP, 0, 897,
            "RESOURCE_PACK_STACK 先序列化行为包栈；官方 897 起移除"),
    CONTAINER_OPEN_TRAILER(Kind.PERMANENT, 860, 0,
            "CONTAINER_OPEN 在 entityUniqueId 之后追加一个布尔值"),
    CONTAINER_OPEN_EXTENDED_TRAILER(Kind.PERMANENT, 898, 0,
            "CONTAINER_OPEN 追加 screenName、customDescription、isClosedDropItem"),
    COMMAND_UNLIMIT_AFTER_VERSION(Kind.PERMANENT, 898, 0,
            "COMMAND_REQUEST 的 unlimit 标志从包尾移到 internal 之后"),
    ENTITY_ATTACK_ID(Kind.PERMANENT, 898, 0,
            "ANIMATE 在载荷之后追加攻击者的 attackId varlong"),
    SEQUENTIAL_BLOCK_PALETTE_OVERLAY(Kind.CATCH_UP, 0, 898,
            "顺序方块 id 走网易 860 调色板覆盖层；898 起改用标准调色板");

    /** 位掩码容量，{@link NetEaseWire} 用一个 long 存整套偏差。 */
    public static final int MAX_DEVIATIONS = Long.SIZE;

    public enum Kind {
        /** 网易独有，官方不会追平。 */
        PERMANENT,
        /** 网易追平官方后消失的临时差。 */
        CATCH_UP
    }

    private final Kind kind;
    private final int introducedAt;
    private final int removedAt;
    private final String description;
    private final long bit;

    NetEaseDeviation(final Kind kind, final int introducedAt, final int removedAt, final String description) {
        this.kind = kind;
        this.introducedAt = introducedAt;
        this.removedAt = removedAt;
        this.description = description;
        this.bit = 1L << this.ordinal();
    }

    public boolean appliesTo(final int protocolVersion) {
        return protocolVersion >= this.introducedAt
                && (this.removedAt == 0 || protocolVersion < this.removedAt);
    }

    public Kind kind() {
        return this.kind;
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
