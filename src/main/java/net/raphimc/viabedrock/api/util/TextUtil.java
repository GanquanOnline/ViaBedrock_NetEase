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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.mcstructs.text.Style;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.LegacyStringDeserializer;
import com.viaversion.viaversion.libs.mcstructs.text.utils.TextUtils;
import net.lenni0451.mcstructs_bedrock.text.BedrockTextFormatting;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class TextUtil {

    private static final Function<Character, TextFormatting> BEDROCK_FORMATTING_RESOLVER = c -> Optional.ofNullable(BedrockTextFormatting.getByCode(c)).map(f -> {
        if (f.isColor()) {
            return new TextFormatting(f.getRgbValue());
        } else if (f.equals(BedrockTextFormatting.OBFUSCATED)) {
            return TextFormatting.OBFUSCATED;
        } else if (f.equals(BedrockTextFormatting.BOLD)) {
            return TextFormatting.BOLD;
        } else if (f.equals(BedrockTextFormatting.ITALIC)) {
            return TextFormatting.ITALIC;
        } else if (f.equals(BedrockTextFormatting.RESET)) {
            return TextFormatting.RESET;
        } else {
            throw new IllegalArgumentException("Unhandled BedrockTextFormatting: " + f);
        }
    }).orElse(null);

    public static String stringToJson(final String text) {
        return textComponentToJson(stringToTextComponent(text));
    }

    public static String textComponentToJson(final TextComponent textComponent) {
        return ProtocolConstants.JAVA_TEXT_COMPONENT_SERIALIZER.serializeJsonString(textComponent);
    }

    public static JsonElement stringToGson(final String text) {
        return textComponentToGson(stringToTextComponent(text));
    }

    public static JsonElement textComponentToGson(final TextComponent textComponent) {
        return ProtocolConstants.JAVA_TEXT_COMPONENT_SERIALIZER.serializeJsonTree(textComponent);
    }

    public static Tag stringToNbt(final String text) {
        return textComponentToNbt(stringToTextComponent(text));
    }

    /**
     * 去除（可能多行的）名称标签字符串首尾的空行。
     * 若一行在去掉格式代码后没有可见内容（{@link #stripFormatting(String)} 返回空字符串），
     * 则该行视为空行。内部行（包括内部空行）会保留，让真正的多行布局保持间距。
     *
     * @return 去除后的字符串（可能仍是多行）；若所有行都为空则为 {@code ""}；
     * 输入为 {@code null} 或不含换行时原样返回。
     */
    public static String trimBlankLines(final String text) {
        if (text == null || text.indexOf('\n') < 0) return text;
        final String[] lines = text.split("\n", -1); // 保留末尾的空段
        int start = 0;
        int end = lines.length;
        while (start < end && stripFormatting(lines[start]).isEmpty()) start++;
        while (end > start && stripFormatting(lines[end - 1]).isEmpty()) end--;
        if (start >= end) return "";
        return String.join("\n", Arrays.copyOfRange(lines, start, end));
    }

    /**
     * 挑选 Java 实际应当渲染的基岩名称标签字符串。
     * {@code NAME_RAW_TEXT} 有可见内容时优先于 {@code NAME}，与
     * {@code MultilineNametagTracker} 保持一致，让宿主的 CUSTOM_NAME 与多行显示
     * 不会落到不同的源字段上。
     */
    public static String nametagValue(final String name, final String nameRaw) {
        if (nameRaw != null && !stripFormatting(nameRaw).isEmpty()) return nameRaw;
        if (name != null && !stripFormatting(name).isEmpty()) return name;
        return null;
    }

    /**
     * 基岩 {@code NAMETAG_ALWAYS_SHOW} 是有符号字节。原版/Nukkit 写 {@code 1}/{@code 0}；
     * 某些网易全息文本写 {@code -1} 表示始终显示。只有显式的 {@code 0} 才表示仅注视时显示。
     * {@code null} 表示该字段被省略；此时基岩对具名实体始终显示。
     */
    public static boolean nametagAlwaysShown(final Number value) {
        return value == null || value.byteValue() != 0;
    }

    /**
     * 返回（可能多行的）名称标签字符串的最后一行。
     * Java 队伍前缀与原版玩家名称标签无法渲染 {@code \n}，因此
     * 多余的行必须放在虚拟盔甲架上，而不是宿主前缀里。
     */
    public static String lastLine(final String text) {
        if (text == null) return null;
        final int idx = text.lastIndexOf('\n');
        return idx < 0 ? text : text.substring(idx + 1);
    }

    /**
     * 把基岩 HUD 字符串压平为一行，供无法渲染 {@code \n} 的 Java 界面使用
     * （Boss 血条、计分板标题、标题/副标题、动作栏、玩家列表名称、容器标题）。
     * 先丢弃首尾空行，再用空格连接剩余各行。
     */
    public static String toSingleLine(final String text) {
        if (text == null) return null;
        final String trimmed = trimBlankLines(text);
        if (trimmed == null || trimmed.indexOf('\n') < 0) return trimmed;
        final StringBuilder out = new StringBuilder(trimmed.length());
        for (String line : trimmed.split("\n", -1)) {
            if (stripFormatting(line).isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(line);
        }
        return out.toString();
    }

    public static String stripFormatting(final String text) {
        if (text == null) return null;
        final StringBuilder out = new StringBuilder();
        final char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == BedrockTextFormatting.COLOR_CHAR && i + 1 < chars.length) {
                i++;
            } else {
                out.append(chars[i]);
            }
        }
        return out.toString();
    }

    public static Tag textComponentToNbt(final TextComponent textComponent) {
        return ProtocolConstants.JAVA_TEXT_COMPONENT_SERIALIZER.serializeNbtTree(textComponent);
    }

    /** 解析 Java 1.21.8 的 JSON 文本组件格式，并针对当前 Java 目标序列化。 */
    public static Tag javaTellrawJsonToNbt(final String json) {
        return textComponentToNbt(ProtocolConstants.JAVA_TELLRAW_SERIALIZER.toComponent(json));
    }

    public static TextComponent stringToTextComponent(final String text) {
        final TextComponent textComponent = LegacyStringDeserializer.parse(appendFormattingCodesAfterColorCode(text), TextFormatting.COLOR_CHAR, ResetTrackingStyle::new, BEDROCK_FORMATTING_RESOLVER);
        final AtomicBoolean wasReset = new AtomicBoolean(false);
        TextUtils.iterateAll(textComponent, c -> {
            final Style style = c.getStyle();
            if (style instanceof ResetTrackingStyle resetTrackingStyle && resetTrackingStyle.wasReset) {
                wasReset.set(true);
            }
            if (wasReset.get()) {
                if (!style.isBold()) {
                    style.setBold(false);
                }
                if (!style.isUnderlined()) {
                    style.setUnderlined(false);
                }
                if (!style.isItalic()) {
                    style.setItalic(false);
                }
            }
        });
        return textComponent;
    }

    public static CompoundTag ensureCompoundTag(final Tag tag) {
        if (tag instanceof CompoundTag compoundTag) {
            return compoundTag;
        } else if (tag instanceof StringTag stringTag) {
            final CompoundTag compoundTag = new CompoundTag();
            compoundTag.putString("text", stringTag.getValue());
            return compoundTag;
        } else {
            final CompoundTag compoundTag = new CompoundTag();
            compoundTag.putString("text", "");
            compoundTag.put("extra", new ListTag<>(List.of(tag)));
            return compoundTag;
        }
    }

    /** 预处理基岩文本，修正 Java 客户端在颜色代码后重置格式的问题 */
    private static String appendFormattingCodesAfterColorCode(final String s) {
        final char[] chars = s.toCharArray();
        final Set<BedrockTextFormatting> styles = EnumSet.noneOf(BedrockTextFormatting.class);
        final StringBuilder out = new StringBuilder();

        for (int i = 0; i < chars.length; i++) {
            final char c = chars[i];
            out.append(c);
            if (c == BedrockTextFormatting.COLOR_CHAR) {
                if (i + 1 < chars.length) {
                    final char code = chars[++i];
                    out.append(code);
                    final BedrockTextFormatting formatting = BedrockTextFormatting.getByCode(code);
                    if (formatting == null) continue;

                    if (BedrockTextFormatting.RESET.equals(formatting)) {
                        styles.clear();
                    } else if (formatting.isColor()) {
                        for (BedrockTextFormatting style : styles) {
                            out.append(style.asString());
                        }
                    } else {
                        styles.add(formatting);
                    }
                }
            }
        }
        return out.toString();
    }

    private static final class ResetTrackingStyle extends Style {

        private boolean wasReset;

        @Override
        public Style setFormatting(final TextFormatting formatting) {
            if (formatting == TextFormatting.RESET) {
                this.wasReset = true;
            }
            return super.setFormatting(formatting);
        }

        @Override
        public Style copy() {
            final ResetTrackingStyle copy = new ResetTrackingStyle();
            copy.setParent(super.copy());
            copy.mergeParent();
            copy.wasReset = this.wasReset;
            return copy;
        }

    }

}
