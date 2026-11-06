package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据表路径解析与启动闸门。
 */
public class BedrockDataTablesTest {

    /** 协议号 → 实际存放数据表的版本目录；2192 与 2193 共用一份转储 */
    private static final Map<Integer, Integer> SHIPPED_TABLES = Map.of(
            975, 975,
            1001, 1001,
            2168, 2168,
            2192, 2193,
            2193, 2193,
            2225, 2225
    );

    @Test
    void everyProfileHasAShippedTableDirectory() {
        for (final BedrockProtocolProfile profile : BedrockProtocolProfile.values()) {
            assertTrue(SHIPPED_TABLES.containsKey(profile.protocolVersion()),
                    "档案 " + profile.gameVersion() + " 没有登记数据表目录");
        }
        assertEquals(BedrockProtocolProfile.values().length, SHIPPED_TABLES.size(),
                "登记了没有档案的目录");
    }

    @Test
    void everyShippedProtocolHasRequiredTables() {
        for (final Map.Entry<Integer, Integer> entry : SHIPPED_TABLES.entrySet()) {
            final int protocol = entry.getKey();
            final int table = entry.getValue();
            BedrockDataTables.validate(protocol);
            assertEquals("versions/" + table + "/block_palette.nbt", BedrockDataTables.blockPalette(protocol));
            assertEquals("versions/" + table + "/runtime_item_states.json", BedrockDataTables.itemStates(protocol));
        }
    }

    @Test
    void sharedProtocolFallsBackToTheOtherProtocolsDirectory() {
        assertEquals("versions/2193/block_palette.nbt", BedrockDataTables.blockPalette(2192),
                "MOT 把 2192 与 2193 都映射到 GameVersion.V1_26_50 的转储");
        assertEquals("versions/2193/runtime_item_states.json", BedrockDataTables.itemStates(2192));
        assertEquals("bedrock/biome_definitions.nbt", BedrockDataTables.biomeDefinitions(2192),
                "共用目录同样没有生物群系转储");
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
