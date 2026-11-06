package net.raphimc.viabedrock.protocol.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 按协议号解析 {@code assets/viabedrock/data/} 下的数据表路径。
 * <p>
 * 每个协议号一套 {@code block_palette.nbt} + {@code runtime_item_states.json}，放在
 * {@code data/versions/<protocol>/} 下，与共享的 {@code data/bedrock/} 平级；文件由 {@code importBedrockDataTables} 工具从
 * Nukkit-MOT 的 {@code runtime_block_states_<protocol>.dat} / {@code runtime_item_states_<protocol>.json}
 * 生成，975 目录是现有 Geyser 调色板的原样拷贝，作为金标准。
 * <p>
 * {@code biome_definitions.nbt} 只有 MOT 的旧协议才有转储，缺失时回退到共享的
 * {@code bedrock/biome_definitions.nbt}，因此它不是启动闸门的必需项。
 * <p>
 * 目录缺失属于配置错误：调用方要么在启动时用 {@link #validate(int)} 给出明确缺文件信息，
 * 要么在加载映射时让 {@link #blockPalette(int)} 抛出同名异常。
 */
public final class BedrockDataTables {

    private static final String DATA_ROOT = "assets/viabedrock/data/";
    private static final String VERSIONS = "versions/";
    private static final String SHARED_BIOMES = "bedrock/biome_definitions.nbt";
    private static final String BLOCK_PALETTE = "block_palette.nbt";
    private static final String ITEM_STATES = "runtime_item_states.json";
    private static final String BIOME_DEFINITIONS = "biome_definitions.nbt";

    /**
     * 共用同一份转储的协议号。MOT 里 2192（1.26.50.27）与 2193（1.26.50）落到同一份数据：物品表按协议号显式映到
     * {@code V1_26_50}（{@code RuntimeItems.getMapping}），方块调色板按协议号向下回退到最近有资源的版本
     * （{@code BlockPalette.findNearestPaletteResource}）。所以两者共用 {@code versions/2193/}。
     */
    private static final Map<Integer, Integer> SHARED_TABLES = Map.of(2192, 2193);

    private BedrockDataTables() {
    }

    /** @return 该协议号的方块调色板资源名，相对 {@code assets/viabedrock/data/} */
    public static String blockPalette(final int protocol) {
        return blockPalette(protocol, BedrockDataTables::exists);
    }

    /** @return 该协议号的物品状态表资源名 */
    public static String itemStates(final int protocol) {
        return itemStates(protocol, BedrockDataTables::exists);
    }

    /** @return 该协议号的生物群系表；没有独立转储时用共享表 */
    public static String biomeDefinitions(final int protocol) {
        return biomeDefinitions(protocol, BedrockDataTables::exists);
    }

    static String blockPalette(final int protocol, final Predicate<String> exists) {
        return versionFile(protocol, BLOCK_PALETTE, exists, true);
    }

    static String itemStates(final int protocol, final Predicate<String> exists) {
        return versionFile(protocol, ITEM_STATES, exists, true);
    }

    static String biomeDefinitions(final int protocol, final Predicate<String> exists) {
        final String path = versionFile(protocol, BIOME_DEFINITIONS, exists, false);
        return path != null ? path : SHARED_BIOMES;
    }

    /**
     * 启动闸门：协议号没有可用的数据表时抛出带路径的异常，避免加载到一半才失败。
     *
     * @throws IllegalStateException 版本目录或必需文件缺失
     */
    public static void validate(final int protocol) {
        validate(protocol, BedrockDataTables::exists);
    }

    static void validate(final int protocol, final Predicate<String> exists) {
        final int directory = tableDirectory(protocol, exists);
        final List<String> missing = new ArrayList<>(2);
        for (final String fileName : List.of(BLOCK_PALETTE, ITEM_STATES)) {
            if (directory == 0 || !exists.test(DATA_ROOT + VERSIONS + directory + "/" + fileName)) {
                missing.add(fileName);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No bedrock data tables for protocol " + protocol
                    + ": missing " + String.join(", ", missing)
                    + " under " + tableDirectoryDescription(protocol, directory)
                    + " (run the importBedrockDataTables tool)");
        }
    }

    private static String versionFile(final int protocol, final String fileName,
                                      final Predicate<String> exists, final boolean required) {
        final int directory = tableDirectory(protocol, exists);
        if (directory != 0) {
            final String path = VERSIONS + directory + "/" + fileName;
            if (exists.test(DATA_ROOT + path)) {
                return path;
            }
        }
        if (required) {
            throw new IllegalStateException("No bedrock data table " + fileName
                    + " under " + tableDirectoryDescription(protocol, directory) + " for protocol " + protocol
                    + " (run the importBedrockDataTables tool)");
        }
        return null;
    }

    /**
     * 先把版本目录定下来，再从该目录取文件：按文件各自回退会把两个版本的转储混成一套
     * （调色板来自一个版本、物品表来自另一个）。
     *
     * @return 命中的版本目录号；{@code 0} 表示一个都没有
     */
    private static int tableDirectory(final int protocol, final Predicate<String> exists) {
        for (final int candidate : tableProtocols(protocol)) {
            if (exists.test(DATA_ROOT + VERSIONS + candidate + "/" + BLOCK_PALETTE)) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * 报错用的目录描述：已定下目录时写实际查的那个，共用协议再把候选目录列上；
     * 一个都没命中时列出全部候选。
     */
    private static String tableDirectoryDescription(final int protocol, final int directory) {
        final List<String> candidates = new ArrayList<>(2);
        for (final int candidate : tableProtocols(protocol)) {
            candidates.add(DATA_ROOT + VERSIONS + candidate + "/");
        }
        if (directory == 0 || candidates.size() == 1) {
            return String.join(" or ", candidates);
        }
        return DATA_ROOT + VERSIONS + directory + "/ (candidates: " + String.join(", ", candidates) + ")";
    }

    /** @return 该协议号要查的版本目录，共用表的协议先查自己、再查共用的那个 */
    private static List<Integer> tableProtocols(final int protocol) {
        final Integer shared = SHARED_TABLES.get(protocol);
        return shared == null ? List.of(protocol) : List.of(protocol, shared);
    }

    private static boolean exists(final String path) {
        final ClassLoader classLoader = BedrockDataTables.class.getClassLoader();
        return classLoader != null && classLoader.getResource(path) != null;
    }

}
