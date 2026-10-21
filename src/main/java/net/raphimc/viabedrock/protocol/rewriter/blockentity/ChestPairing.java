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
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;

/**
 * Maps Bedrock double-chest pairing ({@code pairx}/{@code pairz}) onto Java {@code type=left|right|single}.
 * Inverse of Geyser's Java→Bedrock pair offset (MOT {@code BlockEntityChest} stores the partner the same way).
 */
public final class ChestPairing {

    public static final String TYPE_SINGLE = "single";
    public static final String TYPE_LEFT = "left";
    public static final String TYPE_RIGHT = "right";

    private ChestPairing() {
    }

    public static String javaType(final String facing, final int x, final int z, final CompoundTag tag) {
        if (tag == null || !(tag.get("pairx") instanceof IntTag pairX) || !(tag.get("pairz") instanceof IntTag pairZ)) {
            return TYPE_SINGLE;
        }
        return javaType(facing, pairX.asInt() - x, pairZ.asInt() - z);
    }

    public static String javaType(final String facing, final int dx, final int dz) {
        if (facing == null || Math.abs(dx) + Math.abs(dz) != 1) {
            return TYPE_SINGLE;
        }
        return switch (facing) {
            case "east" -> dz == 1 ? TYPE_LEFT : dz == -1 ? TYPE_RIGHT : TYPE_SINGLE;
            case "west" -> dz == -1 ? TYPE_LEFT : dz == 1 ? TYPE_RIGHT : TYPE_SINGLE;
            case "south" -> dx == -1 ? TYPE_LEFT : dx == 1 ? TYPE_RIGHT : TYPE_SINGLE;
            case "north" -> dx == 1 ? TYPE_LEFT : dx == -1 ? TYPE_RIGHT : TYPE_SINGLE;
            default -> TYPE_SINGLE;
        };
    }

}
