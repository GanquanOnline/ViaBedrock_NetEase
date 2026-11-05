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
 * 可选的网易客户端协议档案。
 * <p>
 * Nukkit-MOT 提供 {@code 1.21.124_NetEase}（860，modpc 3.9）与
 * {@code 1.21.130_NetEase}（898，modpc 3.10）。两者都复用其协议号对应的国际版线格式，
 * 再叠加以被模拟协议为条件的网易新增内容，因此每个布局分支
 * 都只由被模拟的协议决定；游戏版本字符串只用于登录链与皮肤数据。
 */
public enum NetEaseProtocolProfile {

    V1_21_124(860, "1.21.124_NetEase"),
    V1_21_130(898, "1.21.130_NetEase");

    /** {@code netease.protocol-version: auto} 时解析为 {@link #newest()}。 */
    public static final int AUTO = 0;
    public static final int NETEASE_RAKNET_PROTOCOL_VERSION = 8;
    public static final int INTERNATIONAL_RAKNET_PROTOCOL_VERSION = 11;

    private final int protocolVersion;
    private final String gameVersion;

    NetEaseProtocolProfile(final int protocolVersion, final String gameVersion) {
        this.protocolVersion = protocolVersion;
        this.gameVersion = gameVersion;
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public String gameVersion() {
        return this.gameVersion;
    }

    public static NetEaseProtocolProfile newest() {
        return V1_21_130;
    }

    public static NetEaseProtocolProfile byProtocol(final int protocolVersion) {
        for (final NetEaseProtocolProfile profile : values()) {
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

    /**
     * 解析配置的协议。未知值回退到 {@link #newest()}，以免过期
     * 配置产生半套门控的布局集合。
     */
    public static NetEaseProtocolProfile resolve(final int configuredProtocolVersion) {
        final NetEaseProtocolProfile configured = byProtocol(configuredProtocolVersion);
        return configured != null ? configured : newest();
    }

    /**
     * 网易客户端使用 RakNet 8，国际基岩版使用 RakNet 11。两者都允许，
     * 因为网易服务器可能运行任一种传输层；其他值一律回退到 8。
     */
    public static int resolveRakNetProtocolVersion(final int configuredRakNetProtocolVersion) {
        if (configuredRakNetProtocolVersion == NETEASE_RAKNET_PROTOCOL_VERSION
                || configuredRakNetProtocolVersion == INTERNATIONAL_RAKNET_PROTOCOL_VERSION) {
            return configuredRakNetProtocolVersion;
        }
        return NETEASE_RAKNET_PROTOCOL_VERSION;
    }
}
