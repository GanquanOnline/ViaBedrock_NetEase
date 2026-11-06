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
package net.raphimc.viabedrock.protocol.types.model;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 皮肤块。2168 起整块换成紧凑编码（见 {@link BedrockDeviation} 的四条 SKIN_* 偏差），
 * 2167 及以下与旧布局逐字节一致，由 {@code OfficialBedrockLayoutForkTest} 的 975 锁把住。
 * <p>
 * 实例方法用 {@link BedrockWire#current()} 取协议号。网易仿真下服务端发的是 {@code netease.protocol-version}
 * 那个形状（与官方 2168 块不同），所以两个配置值必须落在同一侧，不能一边 2168 一边 860/898。
 */
public class SkinType extends Type<SkinData> {

    /**
     * MOT {@code PersonaPieceType} 的 {@code serializeName}，索引即 2168 线上的序数。
     * 基准是 {@code repos/Nukkit-MOT} 的 {@code PersonaPieceType}（该枚举自称序数对齐 v2168）；MOT 再插入新件时必须同步这两个表。
     */
    private static final String[] PERSONA_PIECE_SERIALIZED_NAMES = {
            "unknown", "skeleton", "body", "skin", "bottom", "feet", "dress", "top", "high_pants", "hands",
            "outerwear", "facialhair", "mouth", "eyes", "hair", "hood", "back", "faceaccessory", "head", "legs",
            "leftleg", "rightleg", "arms", "leftarm", "rightarm", "capes", "classicskin", "emote", "coco", "unsupported"
    };

    /** 同上，2167 及以下的字符串形态；MOT 的 {@code fromName} 两种都认。 */
    private static final String[] PERSONA_PIECE_TYPES = {
            "persona_unknown", "persona_skeleton", "persona_body", "persona_skin", "persona_bottom", "persona_feet",
            "persona_dress", "persona_top", "persona_high_pants", "persona_hand", "persona_outerwear",
            "persona_facial_hair", "persona_mouth", "persona_eyes", "persona_hair", "persona_hood", "persona_back",
            "persona_face_accessory", "persona_head", "persona_legs", "persona_left_leg", "persona_right_leg",
            "persona_arms", "persona_left_arm", "persona_right_arm", "persona_capes", "persona_classic_skin",
            "persona_emote", "persona_coco", "unsupported"
    };

    /** 2168 的人格件染色固定写满四个颜色，缺的补 0。 */
    private static final int TINT_COLOR_COUNT = 4;

    /** 服务端给出的条目数只用作预分配容量，超出照 MOT 的量级截断，避免恶意包直接 OOM。 */
    private static final int MAX_ENTRIES_HINT = 1024;

    public SkinType() {
        super(SkinData.class);
    }

    @Override
    public SkinData read(final ByteBuf buffer) {
        return read(buffer, BedrockWire.current());
    }

    @Override
    public void write(final ByteBuf buffer, final SkinData value) {
        write(buffer, value, BedrockWire.current());
    }

    public static SkinData read(final ByteBuf buffer, final int protocol) {
        final boolean varintAnimations = BedrockWire.applies(protocol, BedrockDeviation.SKIN_ANIMATION_VARINTS);
        final boolean compactArmSize = BedrockWire.applies(protocol, BedrockDeviation.SKIN_ARM_SIZE_BYTE);
        final boolean compactPersona = BedrockWire.applies(protocol, BedrockDeviation.SKIN_PERSONA_V2168);
        final boolean stringTrusted = BedrockWire.applies(protocol, BedrockDeviation.SKIN_TRUSTED_STRING);

        final String skinId = BedrockTypes.STRING.read(buffer);
        // 2225 起 playFabId 从皮肤块搬到 PLAYER_LIST 条目
        final String playFabId = BedrockWire.applies(protocol, BedrockDeviation.SKIN_PLAY_FAB_ID_REMOVED)
                ? "" : BedrockTypes.STRING.read(buffer);
        final String skinResourcePatch = BedrockTypes.STRING.read(buffer);
        final BufferedImage skinData = BedrockTypes.IMAGE.read(buffer);

        final int animationCount = varintAnimations ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer) : buffer.readIntLE();
        final List<SkinData.AnimationData> animations = new ArrayList<>(Math.max(0, Math.min(animationCount, MAX_ENTRIES_HINT)));
        for (int i = 0; i < animationCount; i++) {
            final BufferedImage image = BedrockTypes.IMAGE.read(buffer);
            final int type = varintAnimations ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer) : buffer.readIntLE();
            final float frames = buffer.readFloatLE();
            final int expression = varintAnimations ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer) : buffer.readIntLE();
            animations.add(new SkinData.AnimationData(image, type, frames, expression));
        }

        final BufferedImage capeData = BedrockTypes.IMAGE.read(buffer);
        final String geometryData = BedrockTypes.STRING.read(buffer);
        final String geometryDataEngineVersion = BedrockTypes.STRING.read(buffer);
        final String animationData = BedrockTypes.STRING.read(buffer);
        final String capeId = BedrockTypes.STRING.read(buffer);
        final String fullSkinId = BedrockTypes.STRING.read(buffer);
        final String armSize;
        final String skinColor;
        if (compactArmSize) {
            armSize = buffer.readByte() != 0 ? "wide" : "slim";
            skinColor = intToSkinColor(buffer.readIntLE());
        } else {
            armSize = BedrockTypes.STRING.read(buffer);
            skinColor = BedrockTypes.STRING.read(buffer);
        }

        final int piecesLength = compactPersona ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer) : buffer.readIntLE();
        final List<SkinData.PersonaPieceData> personaPieces = new ArrayList<>(Math.max(0, Math.min(piecesLength, MAX_ENTRIES_HINT)));
        for (int i = 0; i < piecesLength; i++) {
            final String id = BedrockTypes.STRING.read(buffer);
            final String type;
            final String packId;
            if (compactPersona) {
                type = personaPieceName(buffer.readIntLE());
                packId = BedrockTypes.UUID.read(buffer).toString();
            } else {
                type = BedrockTypes.STRING.read(buffer);
                packId = BedrockTypes.STRING.read(buffer);
            }
            final boolean defaultPiece = buffer.readBoolean();
            final String productId = BedrockTypes.STRING.read(buffer);
            personaPieces.add(new SkinData.PersonaPieceData(id, type, packId, defaultPiece, productId));
        }

        final int tintsLength = compactPersona ? BedrockTypes.UNSIGNED_VAR_INT.read(buffer) : buffer.readIntLE();
        final List<SkinData.PersonaPieceTintData> tintColors = new ArrayList<>(Math.max(0, Math.min(tintsLength, MAX_ENTRIES_HINT)));
        for (int i = 0; i < tintsLength; i++) {
            final String type = BedrockTypes.STRING.read(buffer);
            final List<String> colors = new ArrayList<>(TINT_COLOR_COUNT);
            if (compactPersona) {
                for (int color = 0; color < TINT_COLOR_COUNT; color++) {
                    colors.add(intToSkinColor(buffer.readIntLE()));
                }
            } else {
                final int colorsLength = buffer.readIntLE();
                for (int color = 0; color < colorsLength; color++) {
                    colors.add(BedrockTypes.STRING.read(buffer));
                }
            }
            tintColors.add(new SkinData.PersonaPieceTintData(type, colors));
        }

        final boolean premium = buffer.readBoolean();
        final boolean persona = buffer.readBoolean();
        final boolean capeOnClassic = buffer.readBoolean();
        final boolean primaryUser = buffer.readBoolean();
        final boolean overridingPlayerAppearance = buffer.readBoolean();
        boolean trusted = false;
        String profileHash = null;
        if (stringTrusted) {
            trusted = Boolean.parseBoolean(BedrockTypes.STRING.read(buffer));
            profileHash = BedrockTypes.STRING.read(buffer);
        }

        return new SkinData(skinId, playFabId, skinResourcePatch, skinData, animations, capeData, geometryData, geometryDataEngineVersion,
                animationData, premium, persona, capeOnClassic, primaryUser, capeId, fullSkinId, armSize, skinColor, personaPieces,
                tintColors, overridingPlayerAppearance, trusted, profileHash);
    }

    public static void write(final ByteBuf buffer, final SkinData value, final int protocol) {
        final boolean varintAnimations = BedrockWire.applies(protocol, BedrockDeviation.SKIN_ANIMATION_VARINTS);
        final boolean compactArmSize = BedrockWire.applies(protocol, BedrockDeviation.SKIN_ARM_SIZE_BYTE);
        final boolean compactPersona = BedrockWire.applies(protocol, BedrockDeviation.SKIN_PERSONA_V2168);
        final boolean stringTrusted = BedrockWire.applies(protocol, BedrockDeviation.SKIN_TRUSTED_STRING);

        BedrockTypes.STRING.write(buffer, value.skinId());
        if (!BedrockWire.applies(protocol, BedrockDeviation.SKIN_PLAY_FAB_ID_REMOVED)) {
            BedrockTypes.STRING.write(buffer, value.playFabId());
        }
        BedrockTypes.STRING.write(buffer, value.skinResourcePatch());
        BedrockTypes.IMAGE.write(buffer, value.skinData());

        if (varintAnimations) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.animations().size());
        } else {
            buffer.writeIntLE(value.animations().size());
        }
        for (SkinData.AnimationData animation : value.animations()) {
            BedrockTypes.IMAGE.write(buffer, animation.image());
            if (varintAnimations) {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, animation.type());
                buffer.writeFloatLE(animation.frames());
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, animation.expression());
            } else {
                buffer.writeIntLE(animation.type());
                buffer.writeFloatLE(animation.frames());
                buffer.writeIntLE(animation.expression());
            }
        }

        BedrockTypes.IMAGE.write(buffer, value.capeData());
        BedrockTypes.STRING.write(buffer, value.geometryData());
        BedrockTypes.STRING.write(buffer, value.geometryDataEngineVersion());
        BedrockTypes.STRING.write(buffer, value.animationData());
        BedrockTypes.STRING.write(buffer, value.capeId());
        BedrockTypes.STRING.write(buffer, value.fullSkinId());
        if (compactArmSize) {
            buffer.writeByte("wide".equalsIgnoreCase(value.armSize()) ? 1 : 0);
            buffer.writeIntLE(skinColorToInt(value.skinColor()));
        } else {
            BedrockTypes.STRING.write(buffer, value.armSize());
            BedrockTypes.STRING.write(buffer, value.skinColor());
        }

        if (compactPersona) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.personaPieces().size());
        } else {
            buffer.writeIntLE(value.personaPieces().size());
        }
        for (SkinData.PersonaPieceData piece : value.personaPieces()) {
            BedrockTypes.STRING.write(buffer, piece.id());
            if (compactPersona) {
                buffer.writeIntLE(personaPieceOrdinal(piece.type()));
                BedrockTypes.UUID.write(buffer, parsePackId(piece.packId()));
            } else {
                BedrockTypes.STRING.write(buffer, piece.type());
                BedrockTypes.STRING.write(buffer, piece.packId());
            }
            buffer.writeBoolean(piece.defaultPiece());
            BedrockTypes.STRING.write(buffer, piece.productId());
        }

        if (compactPersona) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.tintColors().size());
        } else {
            buffer.writeIntLE(value.tintColors().size());
        }
        for (SkinData.PersonaPieceTintData tint : value.tintColors()) {
            BedrockTypes.STRING.write(buffer, tint.type());
            if (compactPersona) {
                for (int color = 0; color < TINT_COLOR_COUNT; color++) {
                    buffer.writeIntLE(color < tint.colors().size() ? skinColorToInt(tint.colors().get(color)) : 0);
                }
            } else {
                buffer.writeIntLE(tint.colors().size());
                for (String color : tint.colors()) {
                    BedrockTypes.STRING.write(buffer, color);
                }
            }
        }

        buffer.writeBoolean(value.premium());
        buffer.writeBoolean(value.persona());
        buffer.writeBoolean(value.capeOnClassic());
        buffer.writeBoolean(value.primaryUser());
        buffer.writeBoolean(value.overridingPlayerAppearance());
        if (stringTrusted) {
            BedrockTypes.STRING.write(buffer, Boolean.toString(value.trusted()));
            BedrockTypes.STRING.write(buffer, value.profileHash() != null ? value.profileHash() : "");
        }
    }

    /** MOT {@code PersonaPieceType.fromOrdinal(...).getSerializeName()}：越界按 UNKNOWN。 */
    static String personaPieceName(final int ordinal) {
        return ordinal >= 0 && ordinal < PERSONA_PIECE_SERIALIZED_NAMES.length
                ? PERSONA_PIECE_SERIALIZED_NAMES[ordinal]
                : PERSONA_PIECE_SERIALIZED_NAMES[0];
    }

    /** MOT {@code PersonaPieceType.fromName(...)}：两种写法都认，未知按序数 0。 */
    static int personaPieceOrdinal(final String name) {
        if (name != null) {
            for (int i = 0; i < PERSONA_PIECE_SERIALIZED_NAMES.length; i++) {
                if (PERSONA_PIECE_SERIALIZED_NAMES[i].equalsIgnoreCase(name) || PERSONA_PIECE_TYPES[i].equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        return 0;
    }

    static UUID parsePackId(final String packId) {
        if (packId != null && !packId.isEmpty()) {
            try {
                return UUID.fromString(packId);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new UUID(0L, 0L);
    }

    /** MOT {@code BinaryStream.intToSkinColor}：#AARRGGBB 大写。 */
    static String intToSkinColor(final int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    /** MOT {@code BinaryStream.skinColorToInt}：6 位补不透明 alpha，非法值按 0。 */
    static int skinColorToInt(final String color) {
        if (color == null || color.isEmpty()) {
            return 0;
        }
        final boolean hasHash = color.startsWith("#");
        final String hex = hasHash ? color.substring(1) : color;
        try {
            if (hex.length() <= 4) {
                return Integer.parseInt(hasHash ? hex : color);
            }
            final long rgba = hex.length() == 6 ? (Long.parseLong(hex, 16) | 0xFF000000L) : Long.parseLong(hex, 16);
            return (int) rgba;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

}
