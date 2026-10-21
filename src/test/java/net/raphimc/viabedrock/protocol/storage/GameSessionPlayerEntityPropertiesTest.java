package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSessionPlayerEntityPropertiesTest {

    @Test
    void resolvesPlayerPropertyNamesByWireOrder() {
        final Map<String, Integer> indices = GameSessionStorage.parsePlayerEntityPropertyIndices(properties(
                "easecation:custom",
                "easecation:custom_spectator",
                "easecation:custom_armor_status"));

        assertEquals(1, indices.get("easecation:custom_spectator"));
        assertEquals(null, indices.get("easecation:missing"));
    }

    @Test
    void replacingTheDefinitionClearsStaleIndices() {
        final CompoundTag zombieProperties = new CompoundTag();
        zombieProperties.putString("type", "minecraft:zombie");

        assertEquals(Map.of(), GameSessionStorage.parsePlayerEntityPropertyIndices(zombieProperties));
    }

    private static CompoundTag properties(final String... names) {
        final ListTag<CompoundTag> properties = new ListTag<>(CompoundTag.class);
        for (String name : names) {
            final CompoundTag property = new CompoundTag();
            property.putString("name", name);
            property.putInt("type", 2);
            properties.add(property);
        }
        final CompoundTag result = new CompoundTag();
        result.putString("type", "minecraft:player");
        result.put("properties", properties);
        return result;
    }
}
