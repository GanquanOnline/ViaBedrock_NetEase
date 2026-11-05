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
package net.raphimc.viabedrock.api.util;

import com.viaversion.viaversion.libs.mcstructs.core.Identifier;

import java.util.UUID;

public class StringUtil {

    private static final String IDENTIFIER_INVALID_VALUE_CHARS = Identifier.VALID_VALUE_CHARS.replace("[", "[^").replace("]*", "]");

    public static String makeIdentifierValueSafe(final String s) {
        return s.replace(':', '/').replaceAll(IDENTIFIER_INVALID_VALUE_CHARS, "_");
    }

    /**
     * 把 UUID 编码为 16 字符长的 Minecraft 隐形字符串
     *
     * @param uuid 要编码的 uuid
     *
     * @return 编码后的字符串
     */
    public static String encodeUUID(final UUID uuid) {
        return encodeLong(uuid.getMostSignificantBits()) + encodeLong(uuid.getLeastSignificantBits());
    }

    /**
     * 把 long 编码为 8 字符长的 Minecraft 隐形字符串
     *
     * @param bits 要编码的 long
     *
     * @return 编码后的字符串
     */
    public static String encodeLong(long bits) {
        final StringBuilder builder = new StringBuilder();
        final char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            chars[i] = (char) (bits & 0xFF);
            bits >>= 8;
        }
        for (char c : chars) {
            builder.append('§').append(c);
        }
        return builder.toString();
    }

}
