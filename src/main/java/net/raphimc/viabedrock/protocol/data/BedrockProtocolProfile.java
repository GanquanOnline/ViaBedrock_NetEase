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
 * 已实现的官方基岩版协议档案，也就是 {@code bedrock.protocol-version} 可选的取值。
 * <p>
 * 档案只说明"这个协议号的布局数据已经就位"。配置成档案之外的协议号时，{@link BedrockWire}
 * 仍按它本身的协议号推导布局并标记为 inferred，但启动闸门 {@link BedrockDataTables#validate}
 * 要求该协议号能解析到数据表目录（自己那份，或 {@code BedrockDataTables} 里登记的共用目录），
 * 所以实际能启动的只有随包发布了数据表的协议号。
 * <p>
 * 档案按协议号升序排列，{@link #newest()} 取末项，所以新增旧协议号要插到对应位置，
 * 不能一律追加到末尾。
 * <p>
 * 接入新的官方版本：先在该版本抓包补齐 {@link BedrockDeviation} 的区间，再用
 * {@code importBedrockDataTables} 生成 {@code data/versions/<protocol>/}，然后把档案行按协议号
 * 插到对应位置，最后在 {@code BedrockDeviationTest} 钉住该协议的偏差集。
 * <p>
 * 档案行与数据表目录必须成对出现：{@code ViaBedrockPlatform.init} 会为每个档案注册转换协议，
 * 启动闸门 {@link BedrockDataTables#validate} 则按协议号找目录，缺了直接拒绝启动。
 * “是否基岩连接”一律走 {@code BedrockProtocolVersion.isBedrock}，不要再写
 * {@code equals(bedrockLatest)}。
 */
public enum BedrockProtocolProfile {

    /**
     * 974 与 975 声明的游戏版本串相同（MOT 的 {@code V1_26_20_26} 名字也是 "1.26.20"），
     * 线上只差 {@code DIMENSION_DATA} 的 {@code dimension type} 字段。为了让 ViaProxy
     * 下拉框能区分，给 974 单独一个展示标签；握手仍发 {@link #gameVersion()}。
     */
    V1_26_10(944, "1.26.10"),
    V1_26_20_26(974, "1.26.20", "1.26.20 (974)"),
    V1_26_20(975, "1.26.20"),
    V1_26_30(1001, "1.26.30"),
    V1_26_40(2168, "1.26.40"),
    V1_26_50_27(2192, "1.26.50.27"),
    V1_26_50(2193, "1.26.50"),
    V1_26_60(2225, "1.26.60");

    /** {@code bedrock.protocol-version: 0} 解析为 {@link #newest()}。 */
    public static final int AUTO = 0;

    private final int protocolVersion;
    private final String gameVersion;
    private final String label;

    BedrockProtocolProfile(final int protocolVersion, final String gameVersion) {
        this(protocolVersion, gameVersion, gameVersion);
    }

    BedrockProtocolProfile(final int protocolVersion, final String gameVersion, final String label) {
        this.protocolVersion = protocolVersion;
        this.gameVersion = gameVersion;
        this.label = label;
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public String gameVersion() {
        return this.gameVersion;
    }

    /** @return 展示用名字；仅当两个档案握手字符串相同时才与 {@link #gameVersion()} 不同 */
    public String label() {
        return this.label;
    }

    /** @return 最后一个档案；枚举按协议号升序排列，所以末项就是已实现的最新版本 */
    public static BedrockProtocolProfile newest() {
        final BedrockProtocolProfile[] profiles = values();
        return profiles[profiles.length - 1];
    }

    public static BedrockProtocolProfile byProtocol(final int protocolVersion) {
        for (final BedrockProtocolProfile profile : values()) {
            if (profile.protocolVersion == protocolVersion) {
                return profile;
            }
        }
        return null;
    }

    public static boolean isSupported(final int protocolVersion) {
        return byProtocol(protocolVersion) != null;
    }

    /** 当配置值是已实现的档案或 {@link #AUTO} 标记时返回 true。 */
    public static boolean isSelectable(final int configuredProtocolVersion) {
        return configuredProtocolVersion == AUTO || isSupported(configuredProtocolVersion);
    }

    /** 把配置值解析成线格式协议号：{@link #AUTO} 解析为 {@link #newest()}，其余原样保留。
     * 未知协议号也保留，布局由 {@link BedrockWire} 的偏差区间推导。 */
    public static int resolveProtocol(final int configuredProtocolVersion) {
        return configuredProtocolVersion == AUTO ? newest().protocolVersion() : configuredProtocolVersion;
    }

    /** @return 已实现档案的协议号清单，用于启动告警 */
    public static String supportedList() {
        final StringBuilder builder = new StringBuilder();
        for (final BedrockProtocolProfile profile : values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(profile.protocolVersion);
        }
        return builder.append(", ").append(AUTO).append(" = auto = ").append(newest().protocolVersion()).toString();
    }

}
