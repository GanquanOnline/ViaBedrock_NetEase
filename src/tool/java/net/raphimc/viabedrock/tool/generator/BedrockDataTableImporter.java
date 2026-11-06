/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.tool.generator;

import com.viaversion.nbt.io.NBTIO;
import com.viaversion.nbt.limiter.TagLimiter;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.LongTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.libs.gson.Gson;
import com.viaversion.viaversion.libs.gson.GsonBuilder;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import net.raphimc.viabedrock.api.util.BlockStateHasher;
import net.raphimc.viabedrock.api.util.FNV1;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts the per protocol data tables shipped in a Nukkit-MOT jar into ViaBedrock's
 * {@code assets/viabedrock/data/versions/<protocol>/} layout.
 * <p>
 * MOT ships {@code runtime_block_states_<protocol>.dat} (gzip NBT list), {@code runtime_item_states_<protocol>.json}
 * and, for older protocols only, {@code biome_definitions_<protocol>.dat}. ViaBedrock expects a block palette
 * keyed by name hash with the network ids used on the wire, so this tool recomputes {@code name_hash} and
 * {@code network_id} from the block state instead of trusting MOT's sequential {@code runtimeId}.
 * <p>
 * Usage:
 * <pre>
 *   import &lt;motJar&gt; &lt;outRoot&gt; &lt;protocol&gt;... [--base &lt;palette.nbt&gt;]
 *   verify &lt;motJar&gt; &lt;protocol&gt; &lt;palette.nbt&gt;
 * </pre>
 */
public final class BedrockDataTableImporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    public static void main(final String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: import <motJar> <outRoot> <protocol>... [--base <palette.nbt>] | verify <motJar> <protocol> <palette.nbt>");
            System.exit(1);
        }

        switch (args[0]) {
            case "import" -> {
                final Path jar = Path.of(args[1]);
                final Path outRoot = Path.of(args[2]);
                Path base = null;
                final List<Integer> protocols = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    if ("--base".equals(args[i])) {
                        base = Path.of(args[++i]);
                    } else {
                        protocols.add(Integer.parseInt(args[i]));
                    }
                }
                final List<CompoundTag> baseStates = base != null ? readBasePalette(base) : List.of();
                final JsonArray baseItems = base != null ? readOptionalItems(base.getParent().resolve("runtime_item_states.json")) : null;
                for (final int protocol : protocols) {
                    importProtocol(jar, outRoot, protocol, baseStates, baseItems);
                }
            }
            case "verify" -> verifyProtocol(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]));
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    private static void importProtocol(final Path jar, final Path outRoot, final int protocol,
                                       final List<CompoundTag> baseStates, final JsonArray baseItems) throws IOException {
        final Path target = outRoot.resolve(Integer.toString(protocol));
        Files.createDirectories(target);

        final List<CompoundTag> motStates = readPalette(jar, protocol);
        final List<CompoundTag> palette = merge(baseStates, motStates);
        writePalette(target.resolve("block_palette.nbt"), palette);
        System.out.println("[" + protocol + "] block_palette.nbt: " + palette.size() + " states (base="
                + baseStates.size() + ", mot=" + motStates.size() + ", added=" + (palette.size() - baseStates.size()) + ")");

        final byte[] items = readEntry(jar, "runtime_item_states_" + protocol + ".json");
        final JsonArray motItems = GSON.fromJson(new String(items, StandardCharsets.UTF_8), JsonArray.class);
        final JsonArray mergedItems = mergeItems(baseItems, motItems);
        Files.writeString(target.resolve("runtime_item_states.json"), GSON.toJson(mergedItems) + "\n", StandardCharsets.UTF_8);
        System.out.println("[" + protocol + "] runtime_item_states.json: " + mergedItems.size() + " items");

        final byte[] biomes = readEntry(jar, "biome_definitions_" + protocol + ".dat");
        if (biomes == null) {
            Files.deleteIfExists(target.resolve("biome_definitions.nbt"));
            System.out.println("[" + protocol + "] biome_definitions.nbt: no MOT source, keeping the shared table");
        } else {
            final CompoundTag biomeTag = readNbt(biomes, CompoundTag.class);
            writeNbt(target.resolve("biome_definitions.nbt"), biomeTag);
            System.out.println("[" + protocol + "] biome_definitions.nbt: " + biomeTag.getValue().size() + " biomes");
        }
    }

    /**
     * MOT stores the palette as a flat list with a sequential {@code runtimeId}; ViaBedrock looks blocks up by
     * name hash order and sends the hashed network id, so both are recomputed here.
     */
    private static List<CompoundTag> readPalette(final Path jar, final int protocol) throws IOException {
        final byte[] raw = readEntry(jar, "runtime_block_states_" + protocol + ".dat");
        if (raw == null) {
            throw new IllegalStateException("runtime_block_states_" + protocol + ".dat is missing from " + jar);
        }

        final ListTag<CompoundTag> motStates = readNbt(raw, ListTag.class);
        final List<CompoundTag> palette = new ArrayList<>(motStates.size());
        final java.util.Set<String> seen = new java.util.HashSet<>(motStates.size());
        for (final CompoundTag motState : motStates) {
            final String name = motState.getStringTag("name").getValue();
            if ("minecraft:unknown".equals(name) || name.startsWith("minecraft:unknown")) {
                continue;
            }
            final CompoundTag states = motState.get("states") instanceof CompoundTag statesTag
                    ? new CompoundTag(new TreeMap<>(statesTag.getValue()))
                    : new CompoundTag();

            final CompoundTag entry = new CompoundTag();
            entry.putString("name", name);
            entry.put("states", states);
            entry.putInt("block_id", motState.getNumberTag("id").asInt());
            entry.put("name_hash", new LongTag(FNV1.fnv1_64(name.getBytes(StandardCharsets.UTF_8))));
            entry.putInt("network_id", BlockStateHasher.hash(entry));
            entry.putInt("version", motState.getNumberTag("version").asInt());
            if (seen.add(stateKey(entry))) {
                palette.add(entry);
            }
        }

        palette.sort(Comparator
                .comparingLong((CompoundTag tag) -> tag.getLongTag("name_hash").asLong())
                .thenComparingInt(tag -> tag.getIntTag("network_id").asInt())
                .thenComparing(BedrockDataTableImporter::stateKey));
        return palette;
    }

    private static List<CompoundTag> readBasePalette(final Path path) throws IOException {
        final CompoundTag root = readNbt(Files.readAllBytes(path), CompoundTag.class);
        final ListTag<CompoundTag> blocks = root.getListTag("blocks", CompoundTag.class);
        return new ArrayList<>(blocks.getValue());
    }

    private static JsonArray readOptionalItems(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonArray.class);
    }

    /**
     * MOT 的物品表没有 {@code minecraft:item.*} 别名（Java 侧映射要用），所以同样以基础表为底，
     * 同名的以 MOT 为准（协议版本自己的 id 优先）。
     */
    private static JsonArray mergeItems(final JsonArray base, final JsonArray mot) {
        final java.util.TreeMap<String, JsonElement> byName = new java.util.TreeMap<>();
        if (base != null) {
            for (final JsonElement element : base) {
                byName.put(element.getAsJsonObject().get("name").getAsString(), element);
            }
        }
        for (final JsonElement element : mot) {
            byName.put(element.getAsJsonObject().get("name").getAsString(), element);
        }
        final JsonArray merged = new JsonArray(byName.size());
        byName.values().forEach(merged::add);
        return merged;
    }

    /**
     * MOT 的转储只包含服务端可能发出的状态，缺教育版/编辑器方块和部分客户端状态，
     * 直接拿来当调色板会让映射加载校验失败。因此以完整的基础调色板为底，
     * 只把基础里没有的状态追加进去。
     */
    private static List<CompoundTag> merge(final List<CompoundTag> base, final List<CompoundTag> mot) {
        final List<CompoundTag> merged = new ArrayList<>(base.size() + mot.size());
        final java.util.Set<String> seen = new java.util.HashSet<>(base.size() + mot.size());
        for (final CompoundTag tag : base) {
            if (seen.add(stateKey(tag))) {
                merged.add(tag);
            }
        }
        for (final CompoundTag tag : mot) {
            if (seen.add(stateKey(tag))) {
                merged.add(tag);
            }
        }
        merged.sort(Comparator
                .comparingLong((CompoundTag tag) -> tag.getLongTag("name_hash").asLong())
                .thenComparingInt(tag -> tag.getIntTag("network_id").asInt())
                .thenComparing(BedrockDataTableImporter::stateKey));
        return merged;
    }

    private static void verifyProtocol(final Path jar, final int protocol, final Path palettePath) throws IOException {
        final List<CompoundTag> generated = readPalette(jar, protocol);
        final CompoundTag existing = readNbt(Files.readAllBytes(palettePath), CompoundTag.class);
        final ListTag<CompoundTag> existingStates = existing.getListTag("blocks", CompoundTag.class);

        final Map<String, CompoundTag> existingByKey = new LinkedHashMap<>(existingStates.size());
        for (final CompoundTag tag : existingStates) {
            existingByKey.put(stateKey(tag), tag);
        }
        final Map<String, CompoundTag> generatedByKey = new LinkedHashMap<>(generated.size());
        for (final CompoundTag tag : generated) {
            generatedByKey.put(stateKey(tag), tag);
        }

        int mismatched = 0;
        int blockIdOnly = 0;
        final List<String> missingInGenerated = new ArrayList<>();
        final List<String> missingInExisting = new ArrayList<>();
        for (final Map.Entry<String, CompoundTag> entry : existingByKey.entrySet()) {
            final CompoundTag counterpart = generatedByKey.get(entry.getKey());
            if (counterpart == null) {
                missingInGenerated.add(entry.getKey());
                continue;
            }
            final String existingId = wireFields(entry.getValue());
            final String generatedId = wireFields(counterpart);
            if (!existingId.equals(generatedId)) {
                if (mismatched++ < 20) {
                    System.out.println("MISMATCH " + entry.getKey() + " existing=" + existingId + " generated=" + generatedId);
                }
            } else if (entry.getValue().getIntTag("block_id").asInt() != counterpart.getIntTag("block_id").asInt()) {
                blockIdOnly++;
            }
        }
        for (final String key : generatedByKey.keySet()) {
            if (!existingByKey.containsKey(key)) {
                missingInExisting.add(key);
            }
        }

        System.out.println("existing=" + existingStates.size() + " generated=" + generated.size());
        System.out.println("wire field mismatches (network_id/name_hash/version)=" + mismatched);
        System.out.println("block_id differs (unused by ViaBedrock, MOT legacy block id)=" + blockIdOnly);
        System.out.println("missing in generated=" + missingInGenerated.size());
        final Map<String, Integer> missingByName = new TreeMap<>();
        for (final String key : missingInGenerated) {
            missingByName.merge(key.substring(0, key.indexOf('{')), 1, Integer::sum);
        }
        missingByName.forEach((name, count) -> System.out.println("  ~ " + name + " x" + count));
        System.out.println("missing in existing=" + missingInExisting.size());
        missingInExisting.stream().limit(10).forEach(key -> System.out.println("  + " + key));
        System.out.println("order identical=" + sameOrder(existingStates, generated));
    }

    private static String wireFields(final CompoundTag tag) {
        return tag.getIntTag("network_id").asInt() + "/"
                + tag.getLongTag("name_hash").asLong() + "/"
                + tag.getIntTag("version").asInt();
    }

    private static boolean sameOrder(final ListTag<CompoundTag> existing, final List<CompoundTag> generated) {
        if (existing.size() != generated.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            if (!stateKey(existing.get(i)).equals(stateKey(generated.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static String stateKey(final CompoundTag tag) {
        final StringBuilder builder = new StringBuilder(tag.getStringTag("name").getValue()).append('{');
        if (tag.get("states") instanceof CompoundTag states) {
            for (final Map.Entry<String, Tag> entry : new TreeMap<>(states.getValue()).entrySet()) {
                builder.append(entry.getKey()).append('=').append(entry.getValue().getValue()).append(',');
            }
        }
        return builder.append('}').toString();
    }

    private static byte[] readEntry(final Path jar, final String name) throws IOException {
        try (final ZipFile zipFile = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zipFile.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                return inputStream.readAllBytes();
            }
        }
    }

    private static <T extends Tag> T readNbt(final byte[] raw, final Class<T> type) throws IOException {
        final InputStream inputStream = isGzip(raw)
                ? new GZIPInputStream(new java.io.ByteArrayInputStream(raw))
                : new java.io.ByteArrayInputStream(raw);
        return NBTIO.readTag(new DataInputStream(new BufferedInputStream(inputStream)), TagLimiter.noop(), true, type);
    }

    private static void writeNbt(final Path path, final Tag tag) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (final OutputStream gzip = new GZIPOutputStream(buffer)) {
            NBTIO.writeTag(new java.io.DataOutputStream(gzip), tag, true);
        }
        Files.write(path, buffer.toByteArray());
    }

    private static void writePalette(final Path path, final List<CompoundTag> palette) throws IOException {
        final ListTag<CompoundTag> blocks = new ListTag<>(CompoundTag.class);
        blocks.getValue().addAll(palette);
        final CompoundTag root = new CompoundTag();
        root.put("blocks", blocks);
        writeNbt(path, root);
    }

    private static boolean isGzip(final byte[] raw) {
        return raw.length > 1 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

}
