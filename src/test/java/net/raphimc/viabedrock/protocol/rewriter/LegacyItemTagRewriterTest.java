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

import com.viaversion.nbt.stringified.SNBT;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.data.FullMappings;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.data.EnumTypes;
import com.viaversion.viaversion.api.minecraft.item.data.PotionContents;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffect;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.Potions1_20_5;
import com.viaversion.viaversion.util.GsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemTagRewriterTest {

    @Test
    void basePotionTagBecomesCurrentPotionComponent() throws Exception {
        final StructuredDataContainer data = dataWithLookup();

        LegacyItemTagRewriter.apply(data, SNBT.deserializeCompoundTag("{Potion:\"minecraft:night_vision\"}"));

        final PotionContents contents = data.get(StructuredDataKey.POTION_CONTENTS1_21_2);
        assertNotNull(contents);
        assertEquals(4, contents.potion());
        assertNull(contents.customColor());
        assertEquals(0, contents.customEffects().length);
        assertFalse(data.has(StructuredDataKey.POTION_CONTENTS1_20_5));
    }

    @Test
    void bedrockOnlyWitherPotionPreservesEffectAndDefaults() throws Exception {
        final CompoundTag tag = SNBT.deserializeCompoundTag(
                "{custom_potion_effects:[{id:\"minecraft:wither\",amplifier:1,duration:800}],CustomPotionColor:4013879}"
        );

        final PotionContents contents = LegacyItemTagRewriter.potionContents(tag);

        assertNotNull(contents);
        assertNull(contents.potion());
        assertEquals(4013879, contents.customColor());
        assertEquals(1, contents.customEffects().length);
        final PotionEffect effect = contents.customEffects()[0];
        assertEquals(19, effect.effect());
        assertEquals(1, effect.effectData().amplifier());
        assertEquals(800, effect.effectData().duration());
        assertFalse(effect.effectData().ambient());
        assertTrue(effect.effectData().showParticles());
        assertTrue(effect.effectData().showIcon());
    }

    @Test
    void allBedrockPotionMetasAndNewTippedArrowsHaveCompleteMappings() throws Exception {
        final JsonObject mappings = loadItemMappings();
        final Map<Integer, Integer> potionIds = expectedPotionIds();

        for (String identifier : new String[]{"minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion"}) {
            for (Map.Entry<Integer, Integer> entry : potionIds.entrySet()) {
                assertMappedPotion(mappings, identifier, entry.getKey(), entry.getValue());
            }
        }
        for (int bedrockMeta = 43; bedrockMeta <= 46; bedrockMeta++) {
            assertMappedPotion(mappings, "minecraft:arrow", bedrockMeta + 1, potionIds.get(bedrockMeta));
        }
    }

    @Test
    void currentPotionComponentSurvivesWireRoundTrip() throws Exception {
        final PotionContents expected = LegacyItemTagRewriter.potionContents(SNBT.deserializeCompoundTag(
                "{custom_potion_effects:[{id:\"minecraft:wither\",amplifier:1,duration:800}],CustomPotionColor:4013879}"
        ));
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PotionContents.TYPE1_21_2.write(buffer, expected);
            final PotionContents actual = PotionContents.TYPE1_21_2.read(buffer);
            assertEquals(expected.potion(), actual.potion());
            assertEquals(expected.customColor(), actual.customColor());
            assertArrayEquals(expected.customEffects(), actual.customEffects());
            assertEquals(expected.customName(), actual.customName());
        } finally {
            buffer.release();
        }
    }

    @Test
    void legacyPotionIdsMatchCurrentJavaRegistryOrder() {
        for (int potionId = 0; potionId < EnumTypes.POTION.keys().length; potionId++) {
            assertEquals("minecraft:" + Potions1_20_5.idToKey(potionId), EnumTypes.POTION.byId(potionId).toString());
        }
    }

    @Test
    void unrelatedLegacyTagDoesNotCreatePotionContents() throws Exception {
        assertNull(LegacyItemTagRewriter.potionContents(SNBT.deserializeCompoundTag("{BlockStateTag:{facing:\"north\"}}")));
    }

    private static void assertMappedPotion(final JsonObject mappings, final String identifier, final int bedrockMeta, final Integer javaPotionId) throws Exception {
        final JsonObject mapping = mappings.getAsJsonObject(identifier)
                .getAsJsonObject("meta")
                .getAsJsonObject(Integer.toString(bedrockMeta));
        assertNotNull(mapping, identifier + ":" + bedrockMeta);

        final PotionContents contents = LegacyItemTagRewriter.potionContents(
                SNBT.deserializeCompoundTag(mapping.get("java_tag").getAsString())
        );
        assertNotNull(contents, identifier + ":" + bedrockMeta);
        if (bedrockMeta == 36) {
            assertNull(contents.potion(), identifier + ":" + bedrockMeta);
            assertEquals(19, contents.customEffects()[0].effect(), identifier + ":" + bedrockMeta);
        } else {
            assertEquals(javaPotionId, contents.potion(), identifier + ":" + bedrockMeta);
        }
    }

    private static Map<Integer, Integer> expectedPotionIds() {
        final Map<Integer, Integer> ids = new LinkedHashMap<>();
        final int[] sequentialIds = {
                0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38
        };
        for (int bedrockMeta = 0; bedrockMeta < sequentialIds.length; bedrockMeta++) {
            ids.put(bedrockMeta, sequentialIds[bedrockMeta]);
        }
        ids.put(36, null); // 基岩的衰败药水在 Java 上使用自定义的凋零效果。
        ids.put(37, 19);
        ids.put(38, 20);
        ids.put(39, 21);
        ids.put(40, 40);
        ids.put(41, 41);
        ids.put(42, 18);
        ids.put(43, 42);
        ids.put(44, 43);
        ids.put(45, 44);
        ids.put(46, 45);
        return ids;
    }

    private static JsonObject loadItemMappings() {
        final InputStream stream = LegacyItemTagRewriterTest.class.getResourceAsStream(
                "/assets/viabedrock/data/custom/item_mappings.json"
        );
        assertNotNull(stream);
        return GsonUtil.getGson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
    }

    private static StructuredDataContainer dataWithLookup() throws ReflectiveOperationException {
        final StructuredDataContainer data = new StructuredDataContainer();
        final FullMappings lookup = (FullMappings) Proxy.newProxyInstance(
                FullMappings.class.getClassLoader(),
                new Class<?>[]{FullMappings.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id") || method.getName().equals("mappedId")) {
                        return 1;
                    }
                    throw new UnsupportedOperationException(method.toString());
                }
        );
        final Field lookupField = StructuredDataContainer.class.getDeclaredField("lookup");
        lookupField.setAccessible(true);
        lookupField.set(data, lookup);
        return data;
    }

}
