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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.scoreboard.ScoreboardEntry;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.IdentityDefinition_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ScorePacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * 基岩 {@code SET_SCORE}（0x6C / 108 号包）的读取布局。
 * <p>
 * 2168 之前是“包级动作 + 数量 + 每条固定字段”；2168 起去掉包级动作，
 * 改成逐条目类型表：{@code 类型序号 + 类型名 + 计分板 id + 按类型的载荷}。
 * 布局取自 Nukkit-MOT {@code SetScorePacket.encode()} 的 {@code protocol >= v1_26_40} 分支。
 */
public final class SetScoreLayout {

    /** 移除条目。目标名称是可选字符串。 */
    public static final int TYPE_REMOVE = 0;
    public static final int TYPE_CHANGE_PLAYER = 1;
    public static final int TYPE_CHANGE_ENTITY = 2;
    public static final int TYPE_CHANGE_FAKE_PLAYER = 3;

    private SetScoreLayout() {
    }

    /** 解码后的一条计分板变更；{@code entry} 为 null 表示移除。 */
    public record Entry(long scoreboardId, String objectiveName, ScoreboardEntry entry) {
    }

    /** @return true 表示逐条目带类型序号与类型名，包级不再有动作字节 */
    public static boolean usesTypedEntries(final int protocol) {
        return BedrockWire.applies(protocol, BedrockDeviation.SET_SCORE_V2168);
    }

    /**
     * 读出整条包体。
     * <p>
     * 旧布局的包级动作未知时记一条警告并返回空列表，等价于旧实现里的提前返回。
     */
    public static List<Entry> read(final PacketWrapper wrapper, final int protocol) {
        if (usesTypedEntries(protocol)) {
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 数量
            final List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int typeOrdinal = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 条目类型
                wrapper.read(BedrockTypes.STRING); // 类型名，与序号重复，忽略
                final long scoreboardId = wrapper.read(BedrockTypes.VAR_LONG); // 计分板 ID
                switch (typeOrdinal) {
                    case TYPE_REMOVE -> {
                        // 目标名称是可选字符串（缺省记空串）；移除只按计分板 id 生效，调用方靠 id 查旧条目
                        final String objectiveName = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.STRING) : "";
                        entries.add(new Entry(scoreboardId, objectiveName, null));
                    }
                    case TYPE_CHANGE_PLAYER, TYPE_CHANGE_ENTITY -> {
                        final String objectiveName = wrapper.read(BedrockTypes.STRING); // 目标名称
                        final int score = wrapper.read(BedrockTypes.INT_LE); // 分数
                        final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
                        final IdentityDefinition_Type type = typeOrdinal == TYPE_CHANGE_PLAYER
                                ? IdentityDefinition_Type.Player : IdentityDefinition_Type.Entity;
                        entries.add(new Entry(scoreboardId, objectiveName, new ScoreboardEntry(score, type, entityUniqueId, null)));
                    }
                    case TYPE_CHANGE_FAKE_PLAYER -> {
                        final String objectiveName = wrapper.read(BedrockTypes.STRING); // 目标名称
                        final int score = wrapper.read(BedrockTypes.INT_LE); // 分数
                        final String fakePlayerName = wrapper.read(BedrockTypes.STRING); // 假玩家名称
                        entries.add(new Entry(scoreboardId, objectiveName, new ScoreboardEntry(score, IdentityDefinition_Type.FakePlayer, null, fakePlayerName)));
                    }
                    default -> throw new IllegalStateException("Unhandled score entry type: " + typeOrdinal);
                }
            }
            return entries;
        }

        final byte rawAction = wrapper.read(Types.BYTE); // 动作
        final ScorePacketType action = ScorePacketType.getByValue(rawAction);
        if (action == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ScorePacketType: " + rawAction);
            return Collections.emptyList();
        }
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 数量
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final long scoreboardId = wrapper.read(BedrockTypes.VAR_LONG); // 计分板 ID
            final String objectiveName = wrapper.read(BedrockTypes.STRING); // 目标名称
            final int score = wrapper.read(BedrockTypes.INT_LE); // 分数
            final ScoreboardEntry entry;
            switch (action) {
                case Change -> {
                    final byte rawType = wrapper.read(Types.BYTE); // 类型
                    final IdentityDefinition_Type type = IdentityDefinition_Type.getByValue(rawType, IdentityDefinition_Type.Invalid);
                    Long entityUniqueId = null;
                    String fakePlayerName = null;
                    switch (type) {
                        case Player, Entity -> entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
                        case FakePlayer -> fakePlayerName = wrapper.read(BedrockTypes.STRING); // 假玩家名称
                        case Invalid -> throw new IllegalStateException("Invalid IdentityDefinition_Type: " + rawType); // 类型无效时基岩客户端会断开连接
                        default -> throw new IllegalStateException("Unhandled IdentityDefinition_Type: " + rawType);
                    }
                    entry = new ScoreboardEntry(score, type, entityUniqueId, fakePlayerName);
                }
                case Remove -> entry = null;
                default -> throw new IllegalStateException("Unhandled ScorePacketType: " + action);
            }
            entries.add(new Entry(scoreboardId, objectiveName, entry));
        }
        return entries;
    }

}
