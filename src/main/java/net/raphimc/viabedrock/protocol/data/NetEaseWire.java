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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次连接使用的网易线格式描述：被模拟的协议号加上生效的偏差集合。
 * <p>
 * 偏差完全由 {@link NetEaseDeviation} 的协议区间推导，因此任何协议号（包括尚未
 * 实现的网易新版本）都能得到一套确定的布局，而不是静默退回最近档案。协议号不在
 * {@link NetEaseProtocolProfile} 中时标记 {@link #inferred()}，启动日志会打印推断出的
 * 偏差清单，便于对照真实抓包复核。
 * <p>
 * 布局代码走 {@link #applies(boolean, int, NetEaseDeviation)}，它不分配对象。
 */
public final class NetEaseWire {

    /** 未启用网易仿真。 */
    public static final NetEaseWire DISABLED = new NetEaseWire(0, 0L, false);

    private final int protocolVersion;
    private final long mask;
    private final boolean inferred;

    private NetEaseWire(final int protocolVersion, final long mask, final boolean inferred) {
        this.protocolVersion = protocolVersion;
        this.mask = mask;
        this.inferred = inferred;
    }

    public static NetEaseWire of(final boolean emulateNetEase, final int protocolVersion) {
        if (!emulateNetEase || protocolVersion <= 0) {
            return DISABLED;
        }
        long mask = 0L;
        for (NetEaseDeviation deviation : NetEaseDeviation.values()) {
            if (deviation.appliesTo(protocolVersion)) {
                mask |= deviation.bit();
            }
        }
        return new NetEaseWire(protocolVersion, mask, !NetEaseProtocolProfile.isSupported(protocolVersion));
    }

    /** 布局热路径用：不分配对象。{@code protocolVersion <= 0}（未启用或 {@code auto} 标记）一律为 false。 */
    public static boolean applies(final boolean emulateNetEase, final int protocolVersion, final NetEaseDeviation deviation) {
        return emulateNetEase && protocolVersion > 0 && deviation.appliesTo(protocolVersion);
    }

    public boolean has(final NetEaseDeviation deviation) {
        return (this.mask & deviation.bit()) != 0L;
    }

    public boolean enabled() {
        return this.protocolVersion > 0;
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    /** @return true 表示协议号不是已知档案，偏差集合由区间推断得出 */
    public boolean inferred() {
        return this.inferred;
    }

    public List<NetEaseDeviation> deviations() {
        if (this.mask == 0L) {
            return Collections.emptyList();
        }
        final List<NetEaseDeviation> active = new ArrayList<>(Long.bitCount(this.mask));
        for (NetEaseDeviation deviation : NetEaseDeviation.values()) {
            if (this.has(deviation)) {
                active.add(deviation);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /** @return 启动日志用的一行描述 */
    public String describe() {
        if (!this.enabled()) {
            return "disabled";
        }
        final List<NetEaseDeviation> active = this.deviations();
        final StringBuilder builder = new StringBuilder("protocol ").append(this.protocolVersion);
        if (this.inferred) {
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

    @Override
    public String toString() {
        return this.describe();
    }

}
