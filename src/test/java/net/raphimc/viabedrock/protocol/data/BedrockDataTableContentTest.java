package net.raphimc.viabedrock.protocol.data;

import com.viaversion.nbt.io.NBTIO;
import com.viaversion.nbt.limiter.TagLimiter;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.util.BlockStateHasher;
import net.raphimc.viabedrock.api.util.FNV1;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已入库的按版本数据表自洽性：能被加载、字段能被映射数据读走、哈希与文件名一致。
 * <p>
 * 975 目录是现有 Geyser 调色板的原样拷贝，这里用逐字节相等把它钉住；1001/2168/2193/2225 由
 * {@code importBedrockDataTables} 从 MOT 转储生成，这里校验重新算出的
 * {@code network_id} / {@code name_hash} 与表里存的值一致。
 */
public class BedrockDataTableContentTest {

    /** 实际存在的版本目录；2192 复用 2193 的目录，不需要单独列 */
    private static final List<Integer> SHIPPED_PROTOCOLS = List.of(975, 1001, 2168, 2193, 2225);
    private static final int MIN_ITEM_STATES = 1500;

    @Test
    void goldenNineSevenFiveIsAnExactCopy() throws Exception {
        for (final String fileName : List.of("block_palette.nbt", "runtime_item_states.json", "biome_definitions.nbt")) {
            assertArrayEquals(read("assets/viabedrock/data/bedrock/" + fileName),
                    read("assets/viabedrock/data/versions/975/" + fileName),
                    "versions/975/" + fileName + " must stay byte identical to the golden table");
        }
    }

    @Test
    void paletteEntriesAreLoadableAndSelfConsistent() throws Exception {
        for (final int protocol : SHIPPED_PROTOCOLS) {
            final ListTag<CompoundTag> blocks = readPalette(protocol);
            assertFalse(blocks.isEmpty(), "palette for " + protocol + " is empty");

            final Set<String> identifiers = new HashSet<>();
            for (final CompoundTag tag : blocks) {
                assertNotNull(tag.getStringTag("name"));
                assertNotNull(tag.get("states"));
                final BedrockBlockState state = BedrockBlockState.fromNbt(tag);
                assertNotNull(state);
                identifiers.add(state.namespacedIdentifier());

                assertEquals(BlockStateHasher.hash(tag), tag.getIntTag("network_id").asInt(),
                        "network_id mismatch for " + state.namespacedIdentifier() + " in " + protocol);
                assertEquals(FNV1.fnv1_64(tag.getStringTag("name").getValue().getBytes(StandardCharsets.UTF_8)),
                        tag.getLongTag("name_hash").asLong(),
                        "name_hash mismatch for " + tag.getStringTag("name").getValue() + " in " + protocol);
                assertTrue(tag.getIntTag("version").asInt() > 0, "missing block version in " + protocol);
            }
            assertTrue(identifiers.size() > 500, "suspiciously few block identifiers in " + protocol);
        }
    }

    @Test
    void itemStatesAreLoadableAndUnique() throws Exception {
        for (final int protocol : SHIPPED_PROTOCOLS) {
            final JsonArray items = JsonParser.parseString(new String(
                    read("assets/viabedrock/data/versions/" + protocol + "/runtime_item_states.json"),
                    StandardCharsets.UTF_8)).getAsJsonArray();
            assertTrue(items.size() >= MIN_ITEM_STATES, "too few item states in " + protocol + ": " + items.size());

            final Set<Integer> ids = new HashSet<>();
            final Set<String> names = new HashSet<>();
            for (final JsonElement element : items) {
                final JsonObject item = element.getAsJsonObject();
                assertTrue(ids.add(item.get("id").getAsInt()), "duplicate item id in " + protocol);
                assertTrue(names.add(item.get("name").getAsString()), "duplicate item name in " + protocol);
            }
        }
    }

    @Test
    void biomeTableIsSharedByNewerProtocols() {
        assertEquals("assets/viabedrock/data/bedrock/biome_definitions.nbt",
                "assets/viabedrock/data/" + BedrockDataTables.biomeDefinitions(2168));
    }

    /**
     * 与 {@code BedrockMappingData} 的校验同构：共享方块状态表里的每个源状态都必须在调色板里有条目，
     * 否则加载映射时会抛 {@code Unknown bedrock block state}。
     */
    @Test
    void everySharedBlockStateHasAPaletteEntry() throws Exception {
        final var shared = net.easecation.blockstates.SharedBlockStates.instance();
        for (final int protocol : SHIPPED_PROTOCOLS) {
            final java.util.Set<net.raphimc.viabedrock.api.model.BedrockBlockState> states =
                    new java.util.HashSet<>(readPalette(protocol).stream()
                            .map(BedrockBlockState::fromNbt)
                            .toList());

            final java.util.List<String> missing = new java.util.ArrayList<>();
            for (final var source : shared.states()) {
                if (shared.base(source) == null) {
                    continue;
                }
                final java.util.Map<String, String> properties = new java.util.HashMap<>();
                final java.util.List<String> keys = shared.stateKeys(source.name());
                for (int i = 0; i < keys.size(); i++) {
                    final Object value = source.values().get(i);
                    properties.put(keys.get(i), value instanceof Boolean b ? b ? "1" : "0" : value.toString());
                }
                final net.raphimc.viabedrock.api.model.BlockState bedrockBlockState =
                        new net.raphimc.viabedrock.api.model.BlockState(
                                source.name().split(":", 2)[0], source.name().split(":", 2)[1], properties);
                if (!states.contains(bedrockBlockState)) {
                    missing.add(bedrockBlockState.toBlockStateString());
                }
            }
            assertTrue(missing.isEmpty(), "protocol " + protocol + " misses " + missing.size()
                    + " shared block states, first: " + missing.stream().limit(5).toList());
        }
    }

    private static ListTag<CompoundTag> readPalette(final int protocol) throws IOException {
        final CompoundTag root = readNbt("assets/viabedrock/data/" + BedrockDataTables.blockPalette(protocol));
        final ListTag<CompoundTag> blocks = root.getListTag("blocks", CompoundTag.class);
        assertNotNull(blocks, "palette for " + protocol + " has no blocks list");
        return blocks;
    }

    private static CompoundTag readNbt(final String path) throws IOException {
        try (final InputStream inputStream = new GZIPInputStream(open(path))) {
            final Tag tag = NBTIO.readTag(new DataInputStream(inputStream), TagLimiter.noop(), true, CompoundTag.class);
            return (CompoundTag) tag;
        }
    }

    private static InputStream open(final String path) throws IOException {
        final InputStream inputStream = BedrockDataTableContentTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(inputStream, "missing resource " + path);
        return inputStream;
    }

    private static byte[] read(final String path) throws IOException {
        try (final InputStream inputStream = open(path)) {
            return inputStream.readAllBytes();
        }
    }

}
