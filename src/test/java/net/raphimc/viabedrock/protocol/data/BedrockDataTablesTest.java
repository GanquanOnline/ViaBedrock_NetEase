package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 数据表路径解析与启动闸门。
 */
public class BedrockDataTablesTest {

    /** 协议号 → 实际存放数据表的版本目录；2192 与 2193 共用一份转储。{@link BedrockDataTableContentTest} 也从这里派生 */
    static final Map<Integer, Integer> SHIPPED_TABLES = Map.ofEntries(
            Map.entry(897, 975),
            Map.entry(898, 975),
            Map.entry(924, 975),
            Map.entry(944, 975),
            Map.entry(974, 975),
            Map.entry(975, 975),
            Map.entry(1001, 1001),
            Map.entry(2168, 2168),
            Map.entry(2192, 2193),
            Map.entry(2193, 2193),
            Map.entry(2225, 2225)
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
    void the924WindowSharesThe975Dump() {
        assertEquals("versions/975/block_palette.nbt", BedrockDataTables.blockPalette(924),
                "MOT 在 924 没有方块转储，调色板回落到 1.21.111(844)，而 844 是 975 的子集");
        assertEquals("versions/975/runtime_item_states.json", BedrockDataTables.itemStates(924),
                "924 的物品表就是 V1_26_0 的转储，名字全在 975 里");
        BedrockDataTables.validate(924);
    }

    @Test
    void the897And898WindowsShareThe975Dump() {
        for (int protocol : new int[]{897, 898}) {
            assertEquals("versions/975/block_palette.nbt", BedrockDataTables.blockPalette(protocol),
                    "MOT 在 897/898 没有方块转储，调色板回落到 1.21.111(844)，而 844 是 975 的子集");
            assertEquals("versions/975/runtime_item_states.json", BedrockDataTables.itemStates(protocol),
                    "官方 897/898 的 RuntimeItems 档案都是 V1_21_130，转储名字全在 975 里");
            BedrockDataTables.validate(protocol);
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

    @Test
    void sharedProtocolNamesBothDirectoriesWhenTablesAreMissing() {
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.blockPalette(2192, path -> false));
        assertTrue(error.getMessage().contains("versions/2192"), error.getMessage());
        assertTrue(error.getMessage().contains("versions/2193"), error.getMessage());
    }

    @Test
    void sharedProtocolNeverMixesTwoDirectories() {
        final Predicate<String> onlyPaletteIn2192 = path -> path.endsWith("versions/2192/block_palette.nbt")
                || path.endsWith("versions/2193/runtime_item_states.json");
        assertEquals("versions/2192/block_palette.nbt", BedrockDataTables.blockPalette(2192, onlyPaletteIn2192));
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.itemStates(2192, onlyPaletteIn2192),
                "目录定在 2192 之后，物品表不能再换成 2193 那份");
        assertTrue(error.getMessage().contains("versions/2192"), error.getMessage());
    }

    @Test
    void validationNamesTheResolvedDirectoryOfASharedProtocol() {
        final Predicate<String> onlyPaletteIn2192 = path -> path.endsWith("versions/2192/block_palette.nbt");
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BedrockDataTables.validate(2192, onlyPaletteIn2192));
        assertTrue(error.getMessage().contains("runtime_item_states.json"), error.getMessage());
        assertFalse(error.getMessage().contains("block_palette.nbt"), error.getMessage());
        assertTrue(error.getMessage().contains("versions/2192/ (candidates: "), error.getMessage());
    }

    @Test
    void versionsDirectoryHasNoOrphanFolders() throws Exception {
        final URL versions = BedrockDataTablesTest.class.getClassLoader().getResource("assets/viabedrock/data/versions");
        assertNotNull(versions, "missing assets/viabedrock/data/versions");
        Path versionsPath = null;
        try {
            versionsPath = Paths.get(versions.toURI());
        } catch (final FileSystemNotFoundException | URISyntaxException e) {
            // 测试 classpath 不是目录（例如以 jar 附加）时无法枚举，跳过而不是误报
        }
        assumeTrue(versionsPath != null, "versions/ 不在目录 classpath 上，跳过");
        final Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> stream = Files.list(versionsPath)) {
            stream.filter(Files::isDirectory).forEach(path -> onDisk.add(path.getFileName().toString()));
        }
        final Set<String> expected = new TreeSet<>();
        SHIPPED_TABLES.values().forEach(directory -> expected.add(String.valueOf(directory)));
        assertEquals(expected, onDisk, "versions/ 下的目录要与 SHIPPED_TABLES 的值集合一致");
    }

}
