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
     * MOT {@code Player.SPECTATOR} is 3 and its {@code GameType} enum is ordinal-based
     * ({@code SURVIVAL_VIEWER=3}, {@code CREATIVE_VIEWER=4}, {@code SPECTATOR=6}).
     * Official Bedrock spectator is 6. Wire 3/4 must not fall through as {@code Undefined}.
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
     * Remote players always stay {@link GameMode#SPECTATOR} so vanilla clients hide them.
     * The local player may present {@link GameMode#ADVENTURE} when VBU can force
     * {@code Entity.noClip}, because Java SPECTATOR locks the MOT backpack.
     */
    public static GameMode getEffectiveGameMode(final GameType playerGameType, final GameType levelGameType,
                                                final boolean localSpectatorNoclip) {
        GameType effectiveGameType = playerGameType;
        if (effectiveGameType == GameType.Undefined || effectiveGameType == GameType.Default) {
            effectiveGameType = levelGameType;
        }
        if (effectiveGameType == GameType.Undefined || effectiveGameType == GameType.Default) {
            effectiveGameType = GameType.Survival; // Bedrock client defaults to survival in case of out of bounds values
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
