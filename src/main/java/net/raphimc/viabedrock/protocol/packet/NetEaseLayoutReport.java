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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.ServerboundPacketType;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 记录"读完一个基岩包之后还剩多少字节"的分布。
 * <p>
 * 网易每次升版本最贵的环节是找出哪些布局读不干净，而剩余字节此前被
 * {@link PacketLeftoverLayout} 静默丢弃。开启 {@code layout-report} 后按包聚合：
 * 每个包第一次出现时打一条日志，之后只累加，避免刷屏。
 */
public final class NetEaseLayoutReport {

    private static final Logger LOGGER = Logger.getLogger(NetEaseLayoutReport.class.getName());
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private NetEaseLayoutReport() {
    }

    public static boolean enabled() {
        final ViaBedrockConfig config = ViaBedrock.getConfig();
        return config != null && config.shouldReportBedrockLayoutLeftovers();
    }

    public static void record(final PacketWrapper wrapper, final int leftover) {
        if (leftover <= 0 || !enabled()) {
            return;
        }
        record(keyOf(wrapper), leftover);
    }

    static void record(final String packetKey, final int leftover) {
        if (leftover <= 0) {
            return;
        }
        final Entry entry = ENTRIES.computeIfAbsent(packetKey, key -> new Entry());
        if (entry.add(leftover) == 1L) {
            LOGGER.warning("Bedrock layout leftover: " + packetKey + " left " + leftover + " unread byte(s)");
        }
    }

    /** 测试与诊断工具用：当前聚合的稳定排序快照。 */
    static String summary() {
        final List<Map.Entry<String, Entry>> snapshot = new ArrayList<>(ENTRIES.entrySet());
        snapshot.sort(Comparator.comparing(Map.Entry::getKey));
        if (snapshot.isEmpty()) {
            return "no layout leftovers recorded";
        }
        final StringBuilder builder = new StringBuilder("layout leftovers:");
        for (Map.Entry<String, Entry> entry : snapshot) {
            builder.append(System.lineSeparator())
                    .append("  ").append(entry.getKey())
                    .append(": ").append(entry.getValue().occurrences).append(" occurrence(s)")
                    .append(", ").append(entry.getValue().totalBytes).append(" byte(s) total")
                    .append(", ").append(entry.getValue().maxBytes).append(" byte(s) max");
        }
        return builder.toString();
    }

    /** 测试用：清空聚合。 */
    static void reset() {
        ENTRIES.clear();
    }

    private static String keyOf(final PacketWrapper wrapper) {
        final int id = wrapper.getId();
        final PacketType packetType = wrapper.getPacketType();
        if (packetType == null) {
            return String.valueOf(id);
        }
        final String direction = packetType instanceof ServerboundPacketType ? "serverbound" : "clientbound";
        return direction + "/" + packetType.state().name() + "/" + id;
    }

    private static final class Entry {

        private long occurrences;
        private long totalBytes;
        private int maxBytes;

        private synchronized long add(final int leftover) {
            this.occurrences++;
            this.totalBytes += leftover;
            if (leftover > this.maxBytes) {
                this.maxBytes = leftover;
            }
            return this.occurrences;
        }

    }

}
