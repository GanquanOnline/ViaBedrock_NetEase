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
 * 档案只说明"这个协议号的布局数据已经就位"。配置成档案之外的协议号仍然会用它本身
 * 的协议号上线，布局由 {@link BedrockWire} 的偏差区间推导，启动日志会标记为 inferred；
 * 档案列表只用于下拉框、告警文案和启动日志，不参与布局判断。
 * <p>
 * 接入新的官方版本：先在该版本抓包补齐 {@link BedrockDeviation} 的区间，再把档案行
 * 追加到本枚举末尾，最后在 {@code BedrockDeviationTest} 钉住该协议的偏差集。
 * <p>
 * 追加第二个档案前要注意：ViaProxy 侧用 {@code equals(BedrockProtocolVersion.bedrockLatest)}
 * 判定“是否基岩连接”（AddressUtil、Proxy2ServerChannelInitializer、Client2ProxyHandler），
 * 而 {@code ViaBedrockPlatform.init} 只为 bedrockLatest 注册协议；两处不一起改，新档案会在
 * 下拉框里能选、实际却按 Java 连接处理。
 */
public enum BedrockProtocolProfile {

    V1_26_20(975, "1.26.20"),
    V1_26_30(1001, "1.26.30");

    /** {@code bedrock.protocol-version: 0} 解析为 {@link #newest()}。 */
    public static final int AUTO = 0;

    private final int protocolVersion;
    private final String gameVersion;

    BedrockProtocolProfile(final int protocolVersion, final String gameVersion) {
        this.protocolVersion = protocolVersion;
        this.gameVersion = gameVersion;
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public String gameVersion() {
        return this.gameVersion;
    }

    /** @return 最后一个档案；枚举只允许追加，所以末项就是已实现的最新版本 */
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
