/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.definition;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsedPackLayerTest {

    @Test
    void parsesAllDefinitionFamiliesAndFreezesCollections() {
        final ParsedPackLayer layer = ParsedPackLayer.parse(pack(Map.ofEntries(
                Map.entry("texts/en_US.lang", "test.translation=Hello"),
                Map.entry("blocks.json", """
                        {"format_version":"1.0.0","test:block":{"sound":"stone"}}
                        """),
                Map.entry("items/test.json", itemJson("test:item", "test_icon")),
                Map.entry("attachables/test.json", attachableJson("test:attachable", "textures/items/attachable")),
                Map.entry("textures/item_texture.json", """
                        {"texture_name":"atlas.items","texture_data":{"test_icon":{"textures":"textures/items/test"}}}
                        """),
                Map.entry("sounds/sound_definitions.json", """
                        {"test.sound":{"category":"neutral","min_distance":2.5,"max_distance":24.0,
                          "sounds":[{"name":"sounds/test","volume":0.4,"pitch":1.2,"weight":3,"is3D":false}]}}
                        """),
                Map.entry("particles/test.json", """
                        {"particle_effect":{"description":{"identifier":"test:particle"},"components":{}}}
                        """),
                Map.entry("entity/test.json", entityJson("test:entity", "textures/entity/test")),
                Map.entry("models/entity/test.geo.json", geometryJson("geometry.test")),
                Map.entry("fogs/test.json", """
                        {"minecraft:fog_settings":{"description":{"identifier":"test:fog"},"distance":{"air":{"fog_color":"#112233"}}}}
                        """),
                Map.entry("biomes/test.json", """
                        {"minecraft:client_biome":{"description":{"identifier":"test:biome"},"components":{"minecraft:sky_color":{"sky_color":"#112233"}}}}
                        """),
                Map.entry("render_controllers/test.json", renderControllerJson("controller.render.test")),
                Map.entry("animations/test.json", """
                        {"format_version":"1.8.0","animations":{"animation.test.idle":{"loop":true}}}
                        """),
                Map.entry("animation_controllers/test.json", """
                        {"format_version":"1.10.0","animation_controllers":{"controller.animation.test":{
                          "initial_state":"default","states":{"default":{"animations":["idle"]}}
                        }}}
                        """)
        )));

        assertEquals("Hello", layer.texts().get("test.translation"));
        assertEquals("stone", layer.blocks().get("test:block").sound());
        assertEquals("test_icon", layer.items().get("test:item").iconComponent());
        assertNotNull(layer.attachables().attachables().get("test:attachable"));
        assertEquals("textures/items/test", layer.textures().itemTextures().get("test_icon").getFirst().texturePath());
        final SoundDefinitions.SoundDefinition soundDefinition =
                layer.sounds().soundDefinitions().get("test.sound");
        final SoundDefinitions.SoundFile soundFile = soundDefinition.soundFiles().getFirst();
        assertEquals("sounds/test", soundFile.path());
        assertEquals(2.5F, soundDefinition.minDistance());
        assertEquals(24F, soundDefinition.maxDistance());
        assertEquals(0.4F, soundFile.volume());
        assertEquals(1.2F, soundFile.pitch());
        assertEquals(3, soundFile.weight());
        assertFalse(soundFile.is3D());
        assertNotNull(layer.particles().get("test:particle"));
        assertNotNull(layer.entities().get("test:entity"));
        assertNotNull(layer.models().getEntityModel("geometry.test"));
        assertNotNull(layer.fogs().get("test:fog"));
        assertNotNull(layer.biomes().get("test:biome"));
        assertNotNull(layer.renderControllers().get("controller.render.test"));
        assertNotNull(layer.serverAnimation().animations().get("animation.test.idle"));
        assertNotNull(layer.serverAnimation().animationControllers().get("controller.animation.test"));
        assertSame(layer.renderControllers().get("controller.render.test"),
                layer.serverAnimation().renderControllers().get("controller.render.test"));

        assertThrows(UnsupportedOperationException.class,
                () -> layer.textures().itemTextures().put("other", List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> layer.textures().itemTextures().get("test_icon").add(
                        new TextureDefinitions.ItemTextureDefinition("test_icon", "other")));
        assertThrows(UnsupportedOperationException.class,
                () -> layer.entities().get("test:entity").entityData().getTextures().put("other", "other"));
        assertThrows(UnsupportedOperationException.class,
                () -> layer.renderControllers().get("controller.render.test").materialsMap().put("*", "Material.default"));
    }

    @Test
    void acceptsVanillaNullDistanceFields() {
        final ParsedPackLayer layer = ParsedPackLayer.parse(pack(Map.of(
                "sounds/sound_definitions.json", """
                        {"random.splash":{"category":"player","min_distance":null,"max_distance":null,
                          "sounds":["sounds/random/splash"]}}
                        """
        )));

        final SoundDefinitions.SoundDefinition definition =
                layer.sounds().soundDefinitions().get("random.splash");
        assertNotNull(definition);
        assertEquals(0F, definition.minDistance());
        assertNull(definition.maxDistance());
    }

    @Test
    void reusesCommonLayerAcrossIndependentBranchFolds() {
        final ParsedPackLayer common = ParsedPackLayer.parse(pack(Map.of(
                "texts/en_US.lang", "shared.value=common\ncommon.only=base",
                "items/common.json", itemJson("test:common", "common_icon"),
                "items/shared.json", itemJson("test:shared", "common_shared")
        )));
        final ParsedPackLayer branchA = ParsedPackLayer.parse(pack(Map.of(
                "texts/en_US.lang", "shared.value=branch-a\na.only=a",
                "items/shared.json", itemJson("test:shared", "a_shared")
        )));
        final ParsedPackLayer branchB = ParsedPackLayer.parse(pack(Map.of(
                "texts/en_US.lang", "shared.value=branch-b\nb.only=b",
                "items/shared.json", itemJson("test:shared", "b_shared")
        )));

        final ParsedPackLayer.FoldedDefinitions commonOnly = ParsedPackLayer.foldBottomToTop(List.of(common));
        final ParsedPackLayer.FoldedDefinitions foldedA = ParsedPackLayer.foldBottomToTop(List.of(common, branchA));
        final ParsedPackLayer.FoldedDefinitions foldedB = ParsedPackLayer.foldBottomToTop(List.of(common, branchB));

        assertEquals("common", commonOnly.texts().get("shared.value"));
        assertEquals("branch-a", foldedA.texts().get("shared.value"));
        assertEquals("branch-b", foldedB.texts().get("shared.value"));
        assertEquals("a_shared", foldedA.items().get("test:shared").iconComponent());
        assertEquals("b_shared", foldedB.items().get("test:shared").iconComponent());
        assertSame(common.items().get("test:common"), foldedA.items().get("test:common"));
        assertSame(common.items().get("test:common"), foldedB.items().get("test:common"));
        assertEquals("common_shared", common.items().get("test:shared").iconComponent());
    }

    @Test
    void mergesEntityAndBlockSoundsAtEventGranularity() {
        final ParsedPackLayer common = ParsedPackLayer.parse(pack(Map.of("sounds.json", soundsJson(
                "base.hurt", "base.step", "base.break"))));
        final ParsedPackLayer branchA = ParsedPackLayer.parse(pack(Map.of("sounds.json", """
                {
                  "entity_sounds":{"entities":{"test:mob":{"events":{
                    "hurt":{"sound":"a.hurt"},"branch":{"sound":"a.branch"}
                  }}}},
                  "block_sounds":{"stone":{"events":{"break":{"sound":"a.break"}}}}
                }
                """)));
        final ParsedPackLayer branchB = ParsedPackLayer.parse(pack(Map.of("sounds.json", """
                {"entity_sounds":{"entities":{"test:mob":{"events":{"hurt":{"sound":"b.hurt"}}}}}}
                """)));

        final SoundDefinitions soundsA = ParsedPackLayer.foldBottomToTop(List.of(common, branchA)).sounds();
        final SoundDefinitions soundsB = ParsedPackLayer.foldBottomToTop(List.of(common, branchB)).sounds();

        assertEquals("a.hurt", entitySound(soundsA, "hurt"));
        assertEquals("base.step", entitySound(soundsA, "step"));
        assertEquals("a.branch", entitySound(soundsA, "branch"));
        assertEquals("a.break", blockSound(soundsA, "break"));
        assertEquals("base.step", blockSound(soundsA, "step"));
        assertEquals("b.hurt", entitySound(soundsB, "hurt"));
        assertEquals("base.step", entitySound(soundsB, "step"));
        assertFalse(soundsB.entitySounds().get("test:mob").eventSounds().containsKey("branch"));
        assertThrows(UnsupportedOperationException.class,
                () -> soundsA.entitySounds().get("test:mob").eventSounds().put(
                        "other", new SoundDefinitions.ConfiguredSound("other", 1F, 1F, 1F, 1F)));
    }

    @Test
    void malformedHigherLayerDoesNotHideValidLowerDefinition() {
        final ParsedPackLayer common = ParsedPackLayer.parse(pack(Map.of(
                "entity/base.json", entityJson("test:entity", "textures/entity/base"),
                "sounds/sound_definitions.json", """
                        {"test.sound":{"sounds":["sounds/base"]}}
                        """
        )));
        final ParsedPackLayer malformed = ParsedPackLayer.parse(pack(Map.of(
                "entity/broken.json", """
                        {"minecraft:client_entity":{"description":{"identifier":"test:entity","textures":42}}}
                        """,
                "sounds/sound_definitions.json", """
                        {"test.sound":{"sounds":42}}
                        """
        )));

        final ParsedPackLayer.FoldedDefinitions folded = ParsedPackLayer.foldBottomToTop(List.of(common, malformed));

        assertEquals("textures/entity/base",
                folded.entities().get("test:entity").entityData().getTextures().get("default"));
        assertEquals("sounds/base",
                folded.sounds().soundDefinitions().get("test.sound").soundFiles().getFirst().path());
    }

    @Test
    void modelBaseLayersDoNotLeakOtherDefinitionFamilies() {
        final ParsedPackLayer skin = ParsedPackLayer.parse(pack(Map.of(
                "texts/en_US.lang", "skin.value=skin",
                "models/entity/skin.geo.json", geometryJson("geometry.skin")
        )));
        final ParsedPackLayer common = ParsedPackLayer.parse(pack(Map.of(
                "texts/en_US.lang", "common.value=common",
                "models/entity/common.geo.json", geometryJson("geometry.common")
        )));

        final ParsedPackLayer.FoldedDefinitions folded =
                ParsedPackLayer.foldBottomToTop(List.of(common), List.of(skin));

        assertEquals("common", folded.texts().get("common.value"));
        assertEquals("skin.value", folded.texts().get("skin.value"));
        assertNotNull(folded.models().getEntityModel("geometry.skin"));
        assertNotNull(folded.models().getEntityModel("geometry.common"));
    }

    @Test
    void selectedSubpackOverridesBaseWithoutExposingOtherSubpacks() {
        final ResourcePack pack = pack(Map.of(
                "items/shared.json", itemJson("test:shared", "base"),
                "subpacks/hd/items/shared.json", itemJson("test:shared", "hd"),
                "subpacks/low/items/shared.json", itemJson("test:shared", "low")
        ));

        final ParsedPackLayer base = ParsedPackLayer.parse(pack);
        final ParsedPackLayer hd = ParsedPackLayer.parse(pack, "hd");

        assertEquals("base", base.items().get("test:shared").iconComponent());
        assertEquals("hd", hd.items().get("test:shared").iconComponent());
        assertEquals("hd", hd.selectedSubpack());
    }

    @Test
    void selectedSubpackCannotOverrideRootManifestIdentity() {
        final UUID injectedId = UUID.randomUUID();
        final ResourcePack pack = pack(Map.of(
                "items/shared.json", itemJson("test:shared", "base"),
                "subpacks/hd/items/shared.json", itemJson("test:shared", "hd"),
                "subpacks/hd/manifest.json", """
                        {"format_version":2,"header":{
                          "name":"injected","description":"injected","uuid":"%s",
                          "version":[9,9,9],"min_engine_version":[1,20,0]
                        }}
                        """.formatted(injectedId)
        ));

        final ParsedPackLayer hd = ParsedPackLayer.parse(pack, "hd");

        assertEquals(pack.key(), hd.sourceKey());
        assertEquals("hd", hd.items().get("test:shared").iconComponent());
    }

    @Test
    void geometryCompatibilityGettersReturnDetachedGraphs() {
        final ParsedPackLayer layer = ParsedPackLayer.parse(pack(Map.of(
                "models/entity/isolation.geo.json", """
                        {"format_version":"1.12.0","minecraft:geometry":[{
                          "description":{"identifier":"geometry.isolation","texture_width":16,"texture_height":16},
                          "bones":[{"name":"root","pivot":[1,2,3],"rotation":[4,5,6]}]
                        }]}
                        """
        )));

        final var first = layer.models().getEntityModel("geometry.isolation");
        final var second = layer.models().getEntityModel("geometry.isolation");
        assertNotSame(first, second);
        first.getParents().clear();
        second.getParents().getFirst().getPivot().setX(99F);

        final var third = layer.models().getEntityModel("geometry.isolation");
        assertEquals(1, third.getParents().size());
        assertEquals(1F, third.getParents().getFirst().getPivot().getX());

        final var detachedMap = layer.models().entityModels();
        detachedMap.get("geometry.isolation").getParents().clear();
        assertEquals(1, layer.models().getEntityModel("geometry.isolation").getParents().size());
    }

    @Test
    void sourceInventoryIncludesRootGeometryButExcludesOpaqueClientData() {
        final ResourcePack pack = pack(Map.of(
                "geometry.json", geometryJson("geometry.root"),
                "animations/pose.json", "{\"animations\":{}}",
                "render_controllers/pose.json", "{\"render_controllers\":{}}",
                "client_content/catalog.json", "{}",
                "subpacks/hd/geometry.json", geometryJson("geometry.hd")
        ));
        final List<String> sources = ParsedPackLayer.definitionSourcePaths(pack.content());
        assertEquals(1, sources.stream().filter("geometry.json"::equals).count());
        assertEquals(1, sources.stream().filter("render_controllers/pose.json"::equals).count());
        assertEquals(1, sources.stream().filter("animations/pose.json"::equals).count());
        assertFalse(sources.contains("client_content/catalog.json"));
        assertFalse(sources.contains("subpacks/hd/geometry.json"));
        assertNotNull(ParsedPackLayer.parse(pack).models().getEntityModel("geometry.root"));
        assertNotNull(ParsedPackLayer.parse(pack, "hd").models().getEntityModel("geometry.hd"));
    }

    private static String entitySound(final SoundDefinitions definitions, final String event) {
        return definitions.entitySounds().get("test:mob").eventSounds().get(event).sound();
    }

    private static String blockSound(final SoundDefinitions definitions, final String event) {
        return definitions.blockSounds().get("stone").eventSounds().get(event).sound();
    }

    private static String soundsJson(final String hurt, final String step, final String blockBreak) {
        return """
                {
                  "entity_sounds":{"entities":{"test:mob":{"events":{
                    "hurt":{"sound":"%s"},"step":{"sound":"%s"}
                  }}}},
                  "block_sounds":{"stone":{"events":{
                    "break":{"sound":"%s"},"step":{"sound":"%s"}
                  }}}
                }
                """.formatted(hurt, step, blockBreak, step);
    }

    private static String itemJson(final String identifier, final String icon) {
        return """
                {"minecraft:item":{"description":{"identifier":"%s"},"components":{"minecraft:icon":"%s"}}}
                """.formatted(identifier, icon);
    }

    private static String entityJson(final String identifier, final String texture) {
        return """
                {"minecraft:client_entity":{"description":{
                  "identifier":"%s","textures":{"default":"%s"},"geometry":{"default":"geometry.test"}
                }}}
                """.formatted(identifier, texture);
    }

    private static String attachableJson(final String identifier, final String texture) {
        return """
                {"minecraft:attachable":{"description":{
                  "identifier":"%s","textures":{"default":"%s"},"geometry":{"default":"geometry.test"}
                }}}
                """.formatted(identifier, texture);
    }

    private static String geometryJson(final String identifier) {
        return """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"%s","texture_width":16,"texture_height":16},"bones":[]
                }]}
                """.formatted(identifier);
    }

    private static String renderControllerJson(final String identifier) {
        return """
                {"render_controllers":{"%s":{
                  "geometry":"Geometry.default","textures":["Texture.default"],
                  "materials":[{"*":"Material.default"}]
                }}}
                """.formatted(identifier);
    }

    private static ResourcePack pack(final Map<String, String> files) {
        final InMemoryContent content = new InMemoryContent();
        final UUID uuid = UUID.randomUUID();
        content.putString("manifest.json", """
                {"format_version":2,"header":{
                  "name":"test","description":"test","uuid":"%s","version":[1,0,0],"min_engine_version":[1,20,0]
                }}
                """.formatted(uuid));
        files.forEach(content::putString);
        return new ResourcePack(content);
    }

}
