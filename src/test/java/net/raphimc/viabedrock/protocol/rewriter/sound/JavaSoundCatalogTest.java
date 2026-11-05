/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.sound;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSoundCatalogTest {

    @Test
    void predictsNestedJavaVariantUsingThePacketSeed() {
        final JsonObject json = GsonUtil.getGson().fromJson("""
                {
                  "nested":{"sounds":[
                    {"name":"one","volume":0.5,"pitch":0.8,"weight":1,"attenuation_distance":8},
                    {"name":"two","volume":0.25,"pitch":1.2,"weight":3,"attenuation_distance":24}
                  ]},
                  "outer":{"sounds":[
                    {"name":"nested","type":"event","volume":0.4,"pitch":1.5}
                  ]}
                }
                """, JsonObject.class);
        final JavaSoundCatalog catalog = new JavaSoundCatalog(json);

        for (long seed = 0; seed < 32; seed++) {
            final Random expectedRandom = new Random(seed);
            expectedRandom.nextInt(4); // 外层事件选择会消耗它自己的随机值。
            final boolean first = expectedRandom.nextInt(4) == 0;
            final JavaSoundCatalog.Variant variant = catalog.resolve("outer", seed);

            assertEquals(first ? 0.2F : 0.1F, variant.volume(), 0.0001F);
            assertEquals(first ? 1.2F : 1.8F, variant.pitch(), 0.0001F);
            assertEquals(first ? 8 : 24, variant.attenuationDistance());
        }
    }

    @Test
    void loadsKnownVanilla1218AcousticDefinitions() {
        final JavaSoundCatalog.Variant attack = JavaSoundCatalog.resolve(
                ProtocolVersion.v1_21_7, "minecraft:entity.player.attack.nodamage", 123L);
        final JavaSoundCatalog.Variant splash = JavaSoundCatalog.resolve(
                ProtocolVersion.v1_21_7, "entity.generic.splash", 456L);

        assertEquals(0.7F, attack.volume(), 0.0001F);
        assertEquals(1F, attack.pitch(), 0.0001F);
        assertEquals(16, attack.attenuationDistance());
        assertEquals(1F, splash.volume(), 0.0001F);
        assertEquals(16, splash.attenuationDistance());
    }

    @Test
    void selectsCatalogForEverySupportedResourcePackClientFamily() {
        for (ProtocolVersion version : new ProtocolVersion[]{
                ProtocolVersion.v1_21_7, ProtocolVersion.v1_21_9,
                ProtocolVersion.v1_21_11, ProtocolVersion.v26_1}) {
            final JavaSoundCatalog.Variant attack = JavaSoundCatalog.resolve(
                    version, "minecraft:entity.player.attack.nodamage", 789L);
            assertEquals(0.7F, attack.volume(), 0.0001F, version.getName());
            assertEquals(16, attack.attenuationDistance(), version.getName());
        }
    }

}
