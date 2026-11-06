package net.raphimc.viabedrock.protocol.types.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 皮肤块里两张查表（人格件序数、皮肤颜色）的定点测试。
 * 序数基准 = {@code repos/Nukkit-MOT} 的 {@code PersonaPieceType}，它声明自己与 v2168 对齐。
 */
class SkinTypePersonaTest {

    private static final String[] MOT_2168_SERIALIZED_NAMES = {
            "unknown", "skeleton", "body", "skin", "bottom", "feet", "dress", "top", "high_pants", "hands",
            "outerwear", "facialhair", "mouth", "eyes", "hair", "hood", "back", "faceaccessory", "head", "legs",
            "leftleg", "rightleg", "arms", "leftarm", "rightarm", "capes", "classicskin", "emote", "coco", "unsupported"
    };

    @Test
    void ordinalTableMatchesMot2168() {
        for (int ordinal = 0; ordinal < MOT_2168_SERIALIZED_NAMES.length; ordinal++) {
            assertEquals(MOT_2168_SERIALIZED_NAMES[ordinal], SkinType.personaPieceName(ordinal), "序数 " + ordinal);
            assertEquals(ordinal, SkinType.personaPieceOrdinal(MOT_2168_SERIALIZED_NAMES[ordinal]), MOT_2168_SERIALIZED_NAMES[ordinal]);
        }
        assertEquals(30, MOT_2168_SERIALIZED_NAMES.length, "MOT 加件时这里和 SkinType 的两张表要一起改");
    }

    @Test
    void unknownOrdinalsAndNamesFallBackToUnknown() {
        assertEquals("unknown", SkinType.personaPieceName(-1));
        assertEquals("unknown", SkinType.personaPieceName(MOT_2168_SERIALIZED_NAMES.length));
        assertEquals(0, SkinType.personaPieceOrdinal("not_a_piece"));
        assertEquals(0, SkinType.personaPieceOrdinal(null));
    }

    @Test
    void legacyPrefixedNamesMapToTheSameOrdinals() {
        assertEquals(3, SkinType.personaPieceOrdinal("persona_skin"));
        assertEquals(9, SkinType.personaPieceOrdinal("persona_hand"));
        assertEquals(26, SkinType.personaPieceOrdinal("persona_classic_skin"));
        assertEquals(28, SkinType.personaPieceOrdinal("persona_coco"));
        assertEquals(29, SkinType.personaPieceOrdinal("unsupported"));
        assertEquals(3, SkinType.personaPieceOrdinal("PERSONA_SKIN"));
    }

    @Test
    void skinColorsFollowMotEncoding() {
        assertEquals("#00FF00FF", SkinType.intToSkinColor(0x00FF00FF));
        assertEquals("#00000000", SkinType.intToSkinColor(0));
        assertEquals(0xFFFFFFFF, SkinType.skinColorToInt("#FFFFFF"), "6 位补不透明 alpha");
        assertEquals(0xFF123456, SkinType.skinColorToInt("#123456"));
        assertEquals(0xFF123456, SkinType.skinColorToInt("#FF123456"));
        assertEquals(0, SkinType.skinColorToInt("#zzzzzz"));
        assertEquals(0, SkinType.skinColorToInt(null));
    }

}
