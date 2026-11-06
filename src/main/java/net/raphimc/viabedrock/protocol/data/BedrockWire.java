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

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次运行使用的官方基岩线格式描述：{@code bedrock.protocol-version} 解析出的协议号，
 * 以及由 {@link BedrockDeviation} 的协议区间推导出的偏差集合。
 * <p>
 * 协议号不在 {@link BedrockProtocolProfile} 中时标记 {@link #inferred(int)}：布局按
 * 已实现的区间给出，握手仍然发送配置的协议号，启动日志会打印推断结果，便于对照
 * 真实抓包复核，而不是静默退回最近档案。
 * <p>
 * 布局热路径走 {@link #applies(int, BedrockDeviation)}，它只做区间比较，不分配对象。
 * <p>
 * 配置在启动时解析：运行期 reload 不会重发握手，改了协议号必须重开会话才一致。
 */
public final class BedrockWire {

    private BedrockWire() {
    }

    /** @return 当前生效的官方协议号；配置缺失或非正数时回退到 975 默认值 */
    public static int current() {
        final ViaBedrockConfig config = ViaBedrock.getConfig();
        final int configured = config != null ? config.getBedrockProtocolVersion() : 0;
        return configured > 0 ? configured : ProtocolConstants.DEFAULT_BEDROCK_PROTOCOL_VERSION;
    }

    /**
     * @return 本次连接实际使用的线格式协议号：网易仿真下取配置的网易协议号，
     * 否则取 {@link #current()}。布局分支按这个号判断，不再按“是否仿真网易”分支：
     * 已实现的网易号（860 / 898）低于当前已实现的最低阈值 924，两者结果一致；
     * 将来若启用高于阈值的网易号，这里返回的仍是网易号，判断不会失真。
     */
    public static int effectiveProtocol() {
        final ViaBedrockConfig config = ViaBedrock.getConfig();
        if (config != null && config.shouldEmulateNetEaseClient() && config.getNetEaseProtocolVersion() > 0) {
            return config.getNetEaseProtocolVersion();
        }
        return current();
    }

    /** @return 登录声明使用的游戏版本串：已实现档案用自己的版本，其它协议号取不超过它的最近档案 */
    public static String announcedGameVersion() {
        return announcedGameVersion(current());
    }

    public static String announcedGameVersion(final int protocolVersion) {
        final BedrockProtocolProfile profile = BedrockProtocolProfile.byProtocol(protocolVersion);
        if (profile != null) {
            return profile.gameVersion();
        }
        BedrockProtocolProfile nearest = null;
        for (final BedrockProtocolProfile candidate : BedrockProtocolProfile.values()) {
            if (candidate.protocolVersion() <= protocolVersion
                    && (nearest == null || candidate.protocolVersion() > nearest.protocolVersion())) {
                nearest = candidate;
            }
        }
        return (nearest != null ? nearest : BedrockProtocolProfile.newest()).gameVersion();
    }

    public static boolean inferred() {
        return inferred(current());
    }

    /** @return true 表示协议号不是已实现档案，偏差集由区间推断得出 */
    public static boolean inferred(final int protocolVersion) {
        return protocolVersion > 0 && !BedrockProtocolProfile.isSupported(protocolVersion);
    }

    /** 布局热路径用：不分配对象。{@code protocolVersion <= 0}（未配置）一律为 false。 */
    public static boolean applies(final int protocolVersion, final BedrockDeviation deviation) {
        return protocolVersion > 0 && deviation.appliesTo(protocolVersion);
    }

    /** 便捷重载：按当前生效的协议号判定。热路径请先取一次 {@link #current()} 再复用。 */
    public static boolean applies(final BedrockDeviation deviation) {
        return applies(current(), deviation);
    }

    /** @return 该协议号下生效的偏差集，启动日志与钉子测试用 */
    public static List<BedrockDeviation> deviations(final int protocolVersion) {
        if (protocolVersion <= 0) {
            return Collections.emptyList();
        }
        final List<BedrockDeviation> active = new ArrayList<>();
        for (final BedrockDeviation deviation : BedrockDeviation.values()) {
            if (deviation.appliesTo(protocolVersion)) {
                active.add(deviation);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /** @return 启动日志用的一行描述 */
    public static String describe() {
        return describe(current());
    }

    /** @return 启动日志用的一行描述；{@code protocolVersion} 为配置解析后的协议号 */
    public static String describe(final int protocolVersion) {
        final List<BedrockDeviation> active = deviations(protocolVersion);
        final StringBuilder builder = new StringBuilder("protocol ").append(protocolVersion);
        if (inferred(protocolVersion)) {
            builder.append(" (inferred from deviation ranges, not a known profile)");
        }
        builder.append(", ").append(active.size()).append(" deviation(s): ");
        if (active.isEmpty()) {
            builder.append("none");
        } else {
            for (int i = 0; i < active.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(active.get(i).name());
            }
        }
        return builder.toString();
    }

}
