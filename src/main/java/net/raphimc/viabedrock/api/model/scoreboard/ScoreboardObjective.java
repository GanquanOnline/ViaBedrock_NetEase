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
package net.raphimc.viabedrock.api.model.scoreboard;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.IdentityDefinition_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ObjectiveSortOrder;

public class ScoreboardObjective {

    private final String name;
    private final ObjectiveSortOrder sortOrder;
    private final Long2ObjectMap<ScoreboardEntry> entries = new Long2ObjectOpenHashMap<>();
    private String displayName = "";

    public ScoreboardObjective(final String name, final ObjectiveSortOrder sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(final String displayName) {
        this.displayName = displayName != null ? displayName : "";
    }

    public ScoreboardEntry getEntry(final long scoreboardId) {
        return this.entries.get(scoreboardId);
    }

    public ScoreboardEntry getEntryWithSameTarget(final ScoreboardEntry entry) {
        for (ScoreboardEntry value : this.entries.values()) {
            if (value.isSameTarget(entry)) {
                return value;
            }
        }

        return null;
    }

    public ScoreboardEntry getEntryForPlayer(final long entityUniqueId) {
        for (ScoreboardEntry value : this.entries.values()) {
            if (value.entityUniqueId() != null && value.type() == IdentityDefinition_Type.Player && entityUniqueId == value.entityUniqueId()) {
                return value;
            }
        }

        return null;
    }

    public void addEntry(final UserConnection user, final long scoreboardId, final ScoreboardEntry entry) {
        this.entries.put(scoreboardId, entry);
        entry.updateJavaName(user);
        this.updateEntry0(user, entry);
    }

    public void updateEntry(final UserConnection user, final ScoreboardEntry entry) {
        this.removeEntry0(user, entry);
        entry.updateJavaName(user);
        this.updateEntry0(user, entry);
    }

    public void updateEntryInPlace(final UserConnection user, final ScoreboardEntry entry) {
        this.updateEntry0(user, entry);
    }

    public void removeEntry(final UserConnection user, final long scoreboardId) {
        this.removeEntry0(user, this.entries.remove(scoreboardId));
    }

    private void updateEntry0(final UserConnection user, final ScoreboardEntry entry) {
        final PacketWrapper setScore = PacketWrapper.create(ClientboundPackets26_1.SET_SCORE, user);
        setScore.write(Types.STRING, entry.javaName()); // 玩家名称
        setScore.write(Types.STRING, this.name); // 计分项名称
        setScore.write(Types.VAR_INT, this.sortOrder == ObjectiveSortOrder.Ascending ? -entry.score() : entry.score()); // 分数
        setScore.write(Types.OPTIONAL_TAG, ganquanScoreboardEntryName(entry.javaName())); // 显示名称
        setScore.write(Types.BOOLEAN, false); // 是否有数字格式
        setScore.send(BedrockProtocol.class);
    }

    private void removeEntry0(final UserConnection user, final ScoreboardEntry entry) {
        final PacketWrapper resetScore = PacketWrapper.create(ClientboundPackets26_1.RESET_SCORE, user);
        resetScore.write(Types.STRING, entry.javaName()); // 玩家名称
        resetScore.write(Types.OPTIONAL_STRING, this.name); // 计分项名称
        resetScore.send(BedrockProtocol.class);
    }

    /**
     * Java 的计分板文本会丢弃未知的基岩 {@code §} 组合，例如甘泉使用的
     * {@code §rh}/{@code §le}。把原始名称保留在 {@code insertion} 中，使 ModUIClient
     * 能在多次 SET_SCORE 替换之间把 chip 保持在同一个 JSON-UI 槽位上。
     */
    static Tag ganquanScoreboardEntryName(final String javaName) {
        if (javaName == null || javaName.isEmpty()) {
            return null;
        }
        final CompoundTag tag = TextUtil.ensureCompoundTag(TextUtil.stringToNbt(javaName));
        tag.putString("insertion", javaName);
        return tag;
    }

}
