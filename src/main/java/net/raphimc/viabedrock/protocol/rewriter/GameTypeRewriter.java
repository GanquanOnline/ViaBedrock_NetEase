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
package net.raphimc.viabedrock.protocol.rewriter;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GameType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;

public class GameTypeRewriter {

    /**
     * MOT 的 {@code Player.SPECTATOR} 是 3，其 {@code GameType} 枚举按序号取值
     * （{@code SURVIVAL_VIEWER=3}、{@code CREATIVE_VIEWER=4}、{@code SPECTATOR=6}）。
     * 官方基岩的旁观者是 6。线上的 3/4 不能落到 {@code Undefined}。
     */
    public static GameType fromWire(final int value) {
        return switch (value) {
            case 3, 4 -> GameType.Spectator;
            default -> GameType.getByValue(value, GameType.Undefined);
        };
    }

    public static boolean isMotSpectator(final GameType gameType) {
        return gameType == GameType.Spectator;
    }

    public static boolean isMotSpectator(final GameType gameType, final PlayerAbilities abilities) {
        return isMotSpectator(gameType) || (abilities != null && abilities.hasSpectatorNoclip());
    }

    public static GameMode getEffectiveGameMode(final GameType playerGameType, final GameType levelGameType) {
        return getEffectiveGameMode(playerGameType, levelGameType, false);
    }

    /**
     * 远程玩家始终保持 {@link GameMode#SPECTATOR}，以便原版客户端隐藏它们。
     * 当 VBU 能强制 {@code Entity.noClip} 时，本地玩家可以呈现为 {@link GameMode#ADVENTURE}，
     * 因为 Java 的 SPECTATOR 会锁住 MOT 背包。
     */
    public static GameMode getEffectiveGameMode(final GameType playerGameType, final GameType levelGameType,
                                                final boolean localSpectatorNoclip) {
        GameType effectiveGameType = playerGameType;
        if (effectiveGameType == GameType.Undefined || effectiveGameType == GameType.Default) {
            effectiveGameType = levelGameType;
        }
        if (effectiveGameType == GameType.Undefined || effectiveGameType == GameType.Default) {
            effectiveGameType = GameType.Survival; // 遇到越界值时基岩客户端默认使用生存模式
        }
        return (switch (effectiveGameType) {
            case Survival -> GameMode.SURVIVAL;
            case Creative -> GameMode.CREATIVE;
            case Adventure -> GameMode.ADVENTURE;
            case Spectator -> localSpectatorNoclip ? GameMode.ADVENTURE : GameMode.SPECTATOR;
            default -> throw new IllegalStateException("Unhandled GameType: " + effectiveGameType);
        });
    }

}
