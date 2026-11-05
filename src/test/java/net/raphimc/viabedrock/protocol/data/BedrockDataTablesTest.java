package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据表路径解析与启动闸门。
 */
public class BedrockDataTablesTest {

    private static final List<Integer> SHIPPED_PROTOCOLS = List.of(975, 1001, 2168);

    @Test
    void everyShippedProtocolHasRequiredTables() {
        for (final int protocol : SHIPPED_PROTOCOLS) {
            BedrockDataTables.validate(protocol);
            assertEquals("versions/" + protocol + "/block_palette.nbt", BedrockDataTables.blockPalette(protocol));
            assertEquals("versions/" + protocol + "/runtime_item_states.json", BedrockDataTables.itemStates(protocol));
        }
    }

    @Test
    void missingVersionDirectoryNamesProtocolAndFiles() {
        final Predicate<String> nothingExists = path -> false;
        final List<String> files = List.of("block_palette.nbt", "runtime_item_states.json");

        final IllegalStateException paletteError = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.blockPalette(4242, nothingExists));
        assertTrue(paletteError.getMessage().contains("4242"), paletteError.getMessage());
        assertTrue(paletteError.getMessage().contains(files.get(0)), paletteError.getMessage());

        final IllegalStateException itemError = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.itemStates(4242, nothingExists));
        assertTrue(itemError.getMessage().contains(files.get(1)), itemError.getMessage());
    }

    @Test
    void missingShippedVersionFailsValidation() {
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.validate(9999));
        assertTrue(error.getMessage().contains("9999"), error.getMessage());
        assertTrue(error.getMessage().contains("block_palette.nbt"), error.getMessage());
        assertTrue(error.getMessage().contains("runtime_item_states.json"), error.getMessage());
    }

    @Test
    void biomeTableFallsBackToTheSharedDump() {
        assertEquals("versions/975/biome_definitions.nbt", BedrockDataTables.biomeDefinitions(975));
        assertEquals("bedrock/biome_definitions.nbt", BedrockDataTables.biomeDefinitions(1001),
                "MOT only ships biome dumps for old protocols, newer versions share the table");
        assertEquals("bedrock/biome_definitions.nbt",
                BedrockDataTables.biomeDefinitions(1001, path -> false));
    }

    @Test
    void versionDirectoryLookupIgnoresLegacyFallback() {
        final Predicate<String> onlyLegacy = path -> path.endsWith("bedrock/block_palette.nbt");
        assertThrows(IllegalStateException.class, () -> BedrockDataTables.blockPalette(1001, onlyLegacy),
                "a configured protocol must not silently reuse the 975 palette");
        assertFalse(onlyLegacy.test(BedrockDataTables.blockPalette(975)));
    }

}
