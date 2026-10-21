package net.raphimc.viabedrock.protocol.data;

import com.viaversion.nbt.io.NBTIO;
import com.viaversion.nbt.limiter.TagLimiter;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntArrayTag;
import com.viaversion.viaversion.api.minecraft.RegistryType;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import com.viaversion.viaversion.util.GsonUtil;
import com.viaversion.viaversion.util.Key;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockProjectileTagsTest {

    @Test
    void witherSkullsBecomePickableWithoutChangingOtherTagsOrVanillaProjectileMembers() throws IOException {
        final CompoundTag tags = loadTags();
        final CompoundTag expected = tags.copy();
        final JsonArray entities = loadEntityIdentifiers();
        final Set<String> originalMembers = redirectableIdentifiers(tags, entities);
        assertFalse(originalMembers.contains("wither_skull"));
        assertTrue(originalMembers.contains("fireball"));

        BedrockMappingData.addBedrockProjectileTags(tags, entities);

        final Set<String> expectedMembers = new HashSet<>(originalMembers);
        expectedMembers.add("wither_skull");
        assertEquals(expectedMembers, redirectableIdentifiers(tags, entities));
        expected.getCompoundTag(Key.namespaced(RegistryType.ENTITY.identifier())).put("minecraft:redirectable_projectile", redirectableTag(tags).copy());
        assertEquals(expected, tags);

        final JsonObject bedrockMappings = loadJson("custom/entity_mappings.json");
        for (String identifier : new String[]{"minecraft:wither_skull", "minecraft:wither_skull_dangerous"}) {
            assertEquals("minecraft:wither_skull", bedrockMappings.get(identifier).getAsString());
        }
    }

    @Test
    void applyingCompatibilityTwiceDoesNotDuplicateProjectileIds() throws IOException {
        final CompoundTag tags = loadTags();
        final JsonArray entities = loadEntityIdentifiers();
        BedrockMappingData.addBedrockProjectileTags(tags, entities);
        final CompoundTag expected = tags.copy();

        BedrockMappingData.addBedrockProjectileTags(tags, entities);

        assertEquals(expected, tags);
        assertEquals(redirectableTag(tags).getValue().length, Arrays.stream(redirectableTag(tags).getValue()).distinct().count());
    }

    @Test
    void missingEntityMappingFailsBeforeChangingTags() throws IOException {
        final CompoundTag tags = loadTags();
        final CompoundTag expected = tags.copy();
        final JsonArray entities = loadEntityIdentifiers();
        entities.remove(entities.asList().indexOf(new JsonPrimitive("wither_skull")));

        assertThrows(IllegalStateException.class, () -> BedrockMappingData.addBedrockProjectileTags(tags, entities));
        assertEquals(expected, tags);
    }

    private static Set<String> redirectableIdentifiers(final CompoundTag tags, final JsonArray entities) {
        final Set<String> identifiers = new HashSet<>();
        for (int id : redirectableTag(tags).getValue()) {
            identifiers.add(entities.get(id).getAsString());
        }
        return identifiers;
    }

    private static IntArrayTag redirectableTag(final CompoundTag tags) {
        return tags.getCompoundTag(Key.namespaced(RegistryType.ENTITY.identifier())).getIntArrayTag("minecraft:redirectable_projectile");
    }

    private static JsonArray loadEntityIdentifiers() {
        return loadJson("java/via_mappings.json").getAsJsonArray("entities");
    }

    private static JsonObject loadJson(final String name) {
        try (InputStream input = resource(name); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return GsonUtil.getGson().fromJson(reader, JsonObject.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load " + name, exception);
        }
    }

    private static CompoundTag loadTags() throws IOException {
        try (InputStream input = resource("java/tags.nbt"); DataInputStream data = new DataInputStream(new GZIPInputStream(input))) {
            return NBTIO.readTag(data, TagLimiter.noop(), true, CompoundTag.class);
        }
    }

    private static InputStream resource(final String name) {
        final InputStream input = BedrockProjectileTagsTest.class.getResourceAsStream("/assets/viabedrock/data/" + name);
        assertNotNull(input, name);
        return input;
    }
}
