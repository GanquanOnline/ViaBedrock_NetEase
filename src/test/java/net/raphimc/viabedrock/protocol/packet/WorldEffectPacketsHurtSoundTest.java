package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import net.raphimc.viabedrock.api.resourcepack.definition.SoundDefinitions;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SharedTypes_Legacy_LevelSoundEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorldEffectPacketsHurtSoundTest {
    @Test
    void invalidCustomSoundIdentifierCannotReachJavaPacketDecoder() {
        for (String invalidName : new String[]{"Uppercase", "has space", "other:namespace"}) {
            final SoundDefinitions.ConfiguredSound sound = new SoundDefinitions.ConfiguredSound(
                    invalidName, 1F, 1F, 1F, 1F);
            assertEquals("game.player.hurt", WorldEffectPackets.resolveEntitySound(
                    SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), VANILLA_ENTITIES,
                    Set.of("test:mob"), Map.of("test:mob", new SoundDefinitions.EventSounds(
                            "test:mob", Map.of("hurt", sound))), ignored -> true).sound());
        }
    }

    @Test
    void missingAudioFallsBackWithoutSuppressingExplicitZeroVolume() {
        final ParsedPackLayer layer = layer("test:mob", """
                {"entity_sounds":{"entities":{"test:mob":{"events":{
                  "hurt":{"sound":"pack.mob.hurt","volume":0}
                }}}}}
                """);
        final SoundDefinitions.ConfiguredSound fallback = WorldEffectPackets.resolveEntitySound(
                SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), VANILLA_ENTITIES,
                layer.entities().entities().keySet(), layer.sounds().entitySounds(), ignored -> false);
        assertEquals("game.player.hurt", fallback.sound());
        assertEquals(0F, resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), layer).maxVolume());
    }

    @Test
    void malformedCustomRangesCannotProduceInvalidSoundPackets() {
        final float[][] ranges = {
                {Float.NaN, 1F, 1F, 1F},
                {0F, Float.POSITIVE_INFINITY, 1F, 1F},
                {-1F, 1F, 1F, 1F},
                {2F, 1F, 1F, 1F},
                {1F, 1F, Float.NEGATIVE_INFINITY, 1F},
                {1F, 1F, 2F, 1F}
        };
        for (float[] range : ranges) {
            final SoundDefinitions.ConfiguredSound sound = new SoundDefinitions.ConfiguredSound(
                    "pack.mob.hurt", range[0], range[1], range[2], range[3]);
            final SoundDefinitions.ConfiguredSound result = WorldEffectPackets.resolveEntitySound(
                    SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), VANILLA_ENTITIES,
                    Set.of("test:mob"), Map.of("test:mob", new SoundDefinitions.EventSounds(
                            "test:mob", Map.of("hurt", sound))), ignored -> true);
            assertEquals("game.player.hurt", result.sound());
        }
    }

    private static final Set<String> VANILLA_ENTITIES = Set.of("minecraft:zombie", "minecraft:player");
    private static final SoundDefinitions.ConfiguredSound ZOMBIE_HURT =
            new SoundDefinitions.ConfiguredSound("mob.zombie.hurt", 1F, 1F, 0.8F, 1.2F);

    @Test
    void vanillaStaticMappingKeepsPriorityOverPackSound() {
        final ParsedPackLayer layer = layer("minecraft:zombie", """
                {"entity_sounds":{"entities":{"minecraft:zombie":{"events":{
                  "hurt":{"sound":"pack.zombie.hurt"}
                }}}}}
                """);
        assertSame(ZOMBIE_HURT, resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt,
                "minecraft:zombie", Map.of("minecraft:zombie", ZOMBIE_HURT), layer));
    }

    @Test
    void customEntityUsesParsedHurtSoundWithVolumeAndPitchRanges() {
        final ParsedPackLayer layer = layer("test:mob", """
                {"entity_sounds":{"entities":{"test:mob":{"events":{
                  "hurt":{"sound":"pack.mob.hurt","volume":[0.3,0.7],"pitch":[0.9,1.1]}
                }}}}}
                """);
        assertEquals(new SoundDefinitions.ConfiguredSound("pack.mob.hurt", 0.3F, 0.7F, 0.9F, 1.1F),
                resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), layer));
    }

    @Test
    void missingCustomHurtUsesPlayerSoundInsteadOfBlockFallback() {
        final ParsedPackLayer layer = layer("test:mob", """
                {"entity_sounds":{"entities":{"test:mob":{"events":{"step":"pack.mob.step"}}}}}
                """);
        assertEquals(new SoundDefinitions.ConfiguredSound("game.player.hurt", 1F, 1F, 0.8F, 1.2F),
                resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "test:mob", Map.of(), layer));
    }

    @Test
    void absentEntityDefinitionsAndUnmappedVanillaEntitiesDoNotFallback() {
        final ParsedPackLayer layer = layer("minecraft:zombie", """
                {"entity_sounds":{"entities":{"unknown:mob":{"events":{"hurt":"pack.mob.hurt"}}}}}
                """);
        assertNull(resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "unknown:mob", Map.of(), layer));
        assertNull(resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "minecraft:zombie", Map.of(), layer));
    }

    @Test
    void namespacesAreNormalizedAndOtherEventsRetainExistingBehavior() {
        final ParsedPackLayer layer = layer("minecraft:custom_mob", "{}");
        assertEquals("game.player.hurt", resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt,
                "custom_mob", Map.of(), layer).sound());
        assertNull(resolve(SharedTypes_Legacy_LevelSoundEvent.Death, "custom_mob", Map.of(), layer));
        assertNull(resolve(SharedTypes_Legacy_LevelSoundEvent.HurtInWater, "custom_mob", Map.of(), layer));
        assertNull(resolve(SharedTypes_Legacy_LevelSoundEvent.Hurt, "", Map.of(), layer));
    }

    private static SoundDefinitions.ConfiguredSound resolve(SharedTypes_Legacy_LevelSoundEvent event,
            String identifier, Map<String, SoundDefinitions.ConfiguredSound> mappings, ParsedPackLayer layer) {
        return WorldEffectPackets.resolveEntitySound(event, identifier, mappings,
                VANILLA_ENTITIES, layer.entities().entities().keySet(), layer.sounds().entitySounds(), ignored -> true);
    }

    private static ParsedPackLayer layer(String identifier, String soundsJson) {
        final InMemoryContent content = new InMemoryContent();
        content.putString("manifest.json", """
                {"format_version":2,"header":{"name":"hurt test","description":"hurt test",
                  "uuid":"%s","version":[1,0,0],"min_engine_version":[1,20,0]}}
                """.formatted(UUID.randomUUID()));
        content.putString("entity/mob.json", """
                {"minecraft:client_entity":{"description":{"identifier":"%s",
                  "textures":{"default":"textures/entity/mob"},"geometry":{"default":"geometry.mob"}}}}
                """.formatted(identifier));
        content.putString("sounds.json", soundsJson);
        return ParsedPackLayer.parse(new ResourcePack(content));
    }
}
