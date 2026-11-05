/* 不可变的 BedrockLoader 自定义映射快照模型与二进制解码器。 */
package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.nbt.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record CustomMappingSnapshot(
        int schemaVersion,
        int javaProtocolVersion,
        int flags,
        List<BlockStateEntry> blockStates,
        List<BlockEntityTypeEntry> blockEntityTypes,
        List<ItemEntry> items) {

    public CustomMappingSnapshot {
        blockStates = List.copyOf(blockStates);
        blockEntityTypes = List.copyOf(blockEntityTypes);
        items = List.copyOf(items);
    }

    public enum PropertyValueType {
        BOOL(0),
        INT(1),
        STRING(2);

        private final int id;

        PropertyValueType(final int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }

        static PropertyValueType byId(final int id) {
            return switch (id) {
                case 0 -> BOOL;
                case 1 -> INT;
                case 2 -> STRING;
                default -> throw new IllegalArgumentException("Invalid property type " + id);
            };
        }
    }

    public record TypedBedrockPropertyValue(PropertyValueType type, Object value) implements Comparable<TypedBedrockPropertyValue> {
        public TypedBedrockPropertyValue {
            Objects.requireNonNull(type, "type");
            switch (type) {
                case BOOL -> {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Bool property value must be Boolean");
                }
                case INT -> {
                    if (!(value instanceof Integer i) || i < 0) throw new IllegalArgumentException("Int property value must be non-negative Integer");
                }
                case STRING -> {
                    if (!(value instanceof String)) throw new IllegalArgumentException("String property value must be String");
                }
            }
        }

        @Override
        public int compareTo(final TypedBedrockPropertyValue other) {
            final int typeCompare = Integer.compare(this.type.id(), other.type.id());
            if (typeCompare != 0) return typeCompare;
            return switch (this.type) {
                case BOOL -> Boolean.compare((Boolean) this.value, (Boolean) other.value);
                case INT -> Integer.compare((Integer) this.value, (Integer) other.value);
                case STRING -> compareUtf8((String) this.value, (String) other.value);
            };
        }

        public String asBlockStateStringValue() {
            return this.value.toString();
        }
    }

    public record TypedBedrockState(String identifier, SortedMap<String, TypedBedrockPropertyValue> properties) implements Comparable<TypedBedrockState> {
        public TypedBedrockState {
            Objects.requireNonNull(identifier, "identifier");
            properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
        }

        public static TypedBedrockState fromRuntimeState(final BlockState state) {
            if (state instanceof BedrockBlockState bedrockBlockState) {
                final SortedMap<String, TypedBedrockPropertyValue> typed = new TreeMap<>();
                if (bedrockBlockState.blockStateTag().get("states") instanceof com.viaversion.nbt.tag.CompoundTag states) {
                    for (Map.Entry<String, Tag> entry : states.getValue().entrySet()) {
                        final Object value = entry.getValue().getValue();
                        if (value instanceof Byte b) {
                            typed.put(entry.getKey(), new TypedBedrockPropertyValue(PropertyValueType.BOOL, b != 0));
                        } else if (value instanceof Number n) {
                            typed.put(entry.getKey(), new TypedBedrockPropertyValue(PropertyValueType.INT, n.intValue()));
                        } else {
                            typed.put(entry.getKey(), new TypedBedrockPropertyValue(PropertyValueType.STRING, String.valueOf(value)));
                        }
                    }
                }
                return new TypedBedrockState(bedrockBlockState.namespacedIdentifier(), typed);
            }

            final SortedMap<String, TypedBedrockPropertyValue> typed = new TreeMap<>();
            for (Map.Entry<String, String> entry : state.properties().entrySet()) {
                typed.put(entry.getKey(), new TypedBedrockPropertyValue(PropertyValueType.STRING, entry.getValue()));
            }
            return new TypedBedrockState(state.namespacedIdentifier(), typed);
        }

        public String toUntypedBlockStateString() {
            if (this.properties.isEmpty()) return this.identifier;
            final StringBuilder builder = new StringBuilder(this.identifier).append('[');
            boolean first = true;
            for (Map.Entry<String, TypedBedrockPropertyValue> entry : this.properties.entrySet()) {
                if (!first) builder.append(',');
                builder.append(entry.getKey()).append('=').append(entry.getValue().asBlockStateStringValue());
                first = false;
            }
            return builder.append(']').toString();
        }

        @Override
        public int compareTo(final TypedBedrockState other) {
            final int idCompare = compareUtf8(this.identifier, other.identifier);
            if (idCompare != 0) return idCompare;
            final int countCompare = Integer.compare(this.properties.size(), other.properties.size());
            if (countCompare != 0) return countCompare;
            final Iterator<Map.Entry<String, TypedBedrockPropertyValue>> a = this.properties.entrySet().iterator();
            final Iterator<Map.Entry<String, TypedBedrockPropertyValue>> b = other.properties.entrySet().iterator();
            while (a.hasNext() && b.hasNext()) {
                final Map.Entry<String, TypedBedrockPropertyValue> ae = a.next();
                final Map.Entry<String, TypedBedrockPropertyValue> be = b.next();
                final int keyCompare = compareUtf8(ae.getKey(), be.getKey());
                if (keyCompare != 0) return keyCompare;
                final int valueCompare = ae.getValue().compareTo(be.getValue());
                if (valueCompare != 0) return valueCompare;
            }
            return 0;
        }
    }

    public record BlockStateEntry(
            TypedBedrockState bedrockState,
            int targetJavaRawId,
            String fallbackJavaState,
            int emit,
            int filter,
            float secondsToDestroy,
            CustomMappingAccess.BlockEntityRule blockEntityRule) {
    }

    public record BlockEntityTypeEntry(
            String bedrockIdentifier,
            String javaIdentifier,
            int targetJavaRawId,
            CustomMappingAccess.BlockEntityRule rule) {
    }

    public record ItemEntry(
            String bedrockIdentifier,
            int targetJavaRawId,
            int maxStackSize) {
    }

    public static CustomMappingSnapshot decode(
            final byte[] body,
            final int maxSnapshotBytes,
            final int maxCustomBlockStates,
            final int maxCustomBlockEntityTypes,
            final int maxCustomItems,
            final int maxJavaBlockStateId) {
        final Reader r = new Reader(body, maxSnapshotBytes);
        final int schemaVersion = r.readNonNegativeVarInt("schema version");
        // Schema 3 在方块实体规则之后为每个方块状态增加一个 seconds_to_destroy 浮点数（用于瞬间破坏检测）。
        // 在灰度发布期间仍接受 Schema 2 以保持前向/后向兼容；其方块状态读取时不带该字段，默认取 NaN（视为「未知」，即不是瞬间破坏）。
        // Schema 4 在方块实体类型表之后追加 Java 自定义物品的原始 id 条目。
        // Schema 5 为每个自定义物品条目追加生效的 Java 最大堆叠数。
        if (schemaVersion < 2 || schemaVersion > 5) throw new IllegalArgumentException("Unsupported BedrockLoader custom mapping schema " + schemaVersion + " (expected 2, 3, 4 or 5)");
        final int javaProtocolVersion = r.readNonNegativeVarInt("java protocol");
        final int flags = r.readNonNegativeVarInt("flags");

        final int stringCount = r.readNonNegativeVarInt("string table size");
        if (stringCount > 262144) throw new IllegalArgumentException("Invalid string table size");
        final String[] strings = new String[stringCount];
        final HashSet<String> seenStrings = new HashSet<>();
        String previousString = null;
        int totalStringBytes = 0;
        for (int i = 0; i < stringCount; i++) {
            strings[i] = r.readString();
            totalStringBytes += strings[i].getBytes(StandardCharsets.UTF_8).length;
            if (totalStringBytes > maxSnapshotBytes) throw new IllegalArgumentException("String table exceeds snapshot byte limit");
            if (!seenStrings.add(strings[i])) throw new IllegalArgumentException("Duplicate string table entry");
            if (previousString != null && compareUtf8(previousString, strings[i]) >= 0) throw new IllegalArgumentException("String table is not sorted");
            previousString = strings[i];
        }

        final int blockStateCount = r.readNonNegativeVarInt("custom block state count");
        if (blockStateCount > maxCustomBlockStates) throw new IllegalArgumentException("Invalid custom block state count");
        final Set<String> vanillaIdentifiers = blockStateCount == 0 ? Set.of() : vanillaBedrockIdentifiers();
        final List<BlockStateEntry> blockStates = new ArrayList<>(blockStateCount);
        final HashSet<TypedBedrockState> seenStates = new HashSet<>();
        final HashSet<Integer> seenTargetJavaRawIds = new HashSet<>();
        WireBlockStateOrder previousBlockStateOrder = null;
        for (int i = 0; i < blockStateCount; i++) {
            final int identifierId = id(r, strings);
            final String identifier = strings[identifierId];
            if (isPlaceholder(identifier)) throw new IllegalArgumentException("Placeholder block state in custom mapping snapshot");
            if (vanillaIdentifiers.contains(identifier)) throw new IllegalArgumentException("Custom block state overrides vanilla identifier: " + identifier);

            final int propertyCount = r.readNonNegativeVarInt("property count");
            final SortedMap<String, TypedBedrockPropertyValue> properties = new TreeMap<>();
            final List<WirePropertyOrder> propertyOrder = new ArrayList<>(propertyCount);
            int previousNameId = -1;
            for (int p = 0; p < propertyCount; p++) {
                final int nameId = id(r, strings);
                if (nameId <= previousNameId) throw new IllegalArgumentException("Properties are not sorted");
                previousNameId = nameId;
                final int typeId = r.readUnsignedByte();
                final PropertyValueType type = PropertyValueType.byId(typeId);
                final Object value;
                final int stringValueId;
                final int intValue;
                final boolean boolValue;
                switch (type) {
                    case BOOL -> {
                        boolValue = r.readBool();
                        intValue = 0;
                        stringValueId = -1;
                        value = boolValue;
                    }
                    case INT -> {
                        intValue = r.readNonNegativeVarInt("property int value");
                        boolValue = false;
                        stringValueId = -1;
                        value = intValue;
                    }
                    case STRING -> {
                        stringValueId = id(r, strings);
                        boolValue = false;
                        intValue = 0;
                        value = strings[stringValueId];
                    }
                    default -> throw new IllegalStateException("Unexpected type " + type);
                }
                properties.put(strings[nameId], new TypedBedrockPropertyValue(type, value));
                propertyOrder.add(new WirePropertyOrder(nameId, typeId, boolValue, intValue, stringValueId));
            }

            final int targetJavaRawId = r.readNonNegativeVarInt("target java raw id");
            final String fallbackJavaState = strings[id(r, strings)];
            final int emit = r.readUnsignedByte();
            final int filter = r.readUnsignedByte();
            final CustomMappingAccess.BlockEntityRule rule = rule(r.readUnsignedByte());
            final float secondsToDestroy = schemaVersion >= 3 ? r.readFloat() : Float.NaN;
            if (emit > 15 || filter > 15) throw new IllegalArgumentException("Invalid light semantics");

            if (targetJavaRawId > maxJavaBlockStateId) throw new IllegalArgumentException("Target Java raw id exceeds configured maximum");
            if (!seenTargetJavaRawIds.add(targetJavaRawId)) throw new IllegalArgumentException("Duplicate target Java raw id");
            if (fallbackJavaState.isBlank()) throw new IllegalArgumentException("Blank fallback Java state");

            final WireBlockStateOrder order = new WireBlockStateOrder(identifierId, propertyOrder);
            if (previousBlockStateOrder != null && previousBlockStateOrder.compareTo(order) >= 0) throw new IllegalArgumentException("Block states are not sorted");
            previousBlockStateOrder = order;

            final TypedBedrockState typedState = new TypedBedrockState(identifier, properties);
            if (!seenStates.add(typedState)) throw new IllegalArgumentException("Duplicate bedrock block state");
            blockStates.add(new BlockStateEntry(typedState, targetJavaRawId, fallbackJavaState, emit, filter, secondsToDestroy, rule));
        }

        final int blockEntityCount = r.readNonNegativeVarInt("custom block entity type count");
        if (blockEntityCount > maxCustomBlockEntityTypes) throw new IllegalArgumentException("Invalid custom block entity type count");
        final List<BlockEntityTypeEntry> blockEntityTypes = new ArrayList<>(blockEntityCount);
        final HashSet<Integer> blockEntityRawIds = new HashSet<>();
        final HashSet<String> blockEntityIdentifiers = new HashSet<>();
        WireBlockEntityOrder previousBlockEntityOrder = null;
        for (int i = 0; i < blockEntityCount; i++) {
            final int bedrockIdentifierId = id(r, strings);
            final String bedrockIdentifier = strings[bedrockIdentifierId];
            if (isPlaceholder(bedrockIdentifier)) throw new IllegalArgumentException("Placeholder block entity type in custom mapping snapshot");
            final int javaIdentifierId = id(r, strings);
            final String javaIdentifier = strings[javaIdentifierId];
            final int targetJavaRawId = r.readNonNegativeVarInt("target block entity raw id");
            final CustomMappingAccess.BlockEntityRule rule = rule(r.readUnsignedByte());
            if ((long) targetJavaRawId >= (long) Integer.MAX_VALUE) throw new IllegalArgumentException("Custom block entity id exceeds configured maximum");
            if (!blockEntityRawIds.add(targetJavaRawId)) throw new IllegalArgumentException("Duplicate custom block entity raw id");
            if (!blockEntityIdentifiers.add(bedrockIdentifier)) throw new IllegalArgumentException("Duplicate custom block entity identifier");
            final WireBlockEntityOrder order = new WireBlockEntityOrder(bedrockIdentifierId, javaIdentifierId, targetJavaRawId);
            if (previousBlockEntityOrder != null && previousBlockEntityOrder.compareTo(order) >= 0) throw new IllegalArgumentException("Block entity types are not sorted");
            previousBlockEntityOrder = order;
            blockEntityTypes.add(new BlockEntityTypeEntry(bedrockIdentifier, javaIdentifier, targetJavaRawId, rule));
        }

        final List<ItemEntry> items;
        if (schemaVersion >= 4) {
            final int itemCount = r.readNonNegativeVarInt("custom item count");
            if (itemCount > maxCustomItems) throw new IllegalArgumentException("Invalid custom item count");
            items = new ArrayList<>(itemCount);
            final HashSet<Integer> itemRawIds = new HashSet<>();
            final HashSet<String> itemIdentifiers = new HashSet<>();
            WireItemOrder previousItemOrder = null;
            for (int i = 0; i < itemCount; i++) {
                final int bedrockIdentifierId = id(r, strings);
                final String bedrockIdentifier = strings[bedrockIdentifierId];
                if (isPlaceholder(bedrockIdentifier)) throw new IllegalArgumentException("Placeholder item in custom mapping snapshot");
                if (bedrockIdentifier.startsWith("minecraft:")) throw new IllegalArgumentException("Custom item overrides vanilla identifier: " + bedrockIdentifier);
                final int targetJavaRawId = r.readNonNegativeVarInt("target item raw id");
                final int maxStackSize = schemaVersion >= 5 ? r.readNonNegativeVarInt("custom item max stack size") : 0;
                if ((long) targetJavaRawId >= (long) Integer.MAX_VALUE) throw new IllegalArgumentException("Custom item id exceeds configured maximum");
                if (schemaVersion >= 5 && (maxStackSize < 1 || maxStackSize > 99)) throw new IllegalArgumentException("Invalid custom item max stack size: " + maxStackSize);
                if (!itemRawIds.add(targetJavaRawId)) throw new IllegalArgumentException("Duplicate custom item raw id");
                if (!itemIdentifiers.add(bedrockIdentifier)) throw new IllegalArgumentException("Duplicate custom item identifier");
                final WireItemOrder order = new WireItemOrder(bedrockIdentifierId, targetJavaRawId, maxStackSize);
                if (previousItemOrder != null && previousItemOrder.compareTo(order) >= 0) throw new IllegalArgumentException("Items are not sorted");
                previousItemOrder = order;
                items.add(new ItemEntry(bedrockIdentifier, targetJavaRawId, maxStackSize));
            }
        } else {
            items = List.of();
        }

        r.ensureFullyRead();
        return new CustomMappingSnapshot(schemaVersion, javaProtocolVersion, flags, blockStates, blockEntityTypes, items);
    }

    private static int id(final Reader r, final String[] strings) {
        final int id = r.readNonNegativeVarInt("string id");
        if (id >= strings.length) throw new IllegalArgumentException("String id out of range");
        return id;
    }

    private static Set<String> vanillaBedrockIdentifiers() {
        final HashSet<String> vanillaIdentifiers = new HashSet<>();
        for (BedrockBlockState state : BedrockProtocol.MAPPINGS.getBedrockBlockStates()) {
            vanillaIdentifiers.add(state.namespacedIdentifier());
        }
        return vanillaIdentifiers;
    }

    private static boolean isPlaceholder(final String identifier) {
        return identifier.startsWith("bedrock-loader:placeholder_");
    }

    static int compareUtf8(final String a, final String b) {
        final byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        final byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        final int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            final int cmp = Byte.compareUnsigned(ab[i], bb[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(ab.length, bb.length);
    }

    private static CustomMappingAccess.BlockEntityRule rule(final int id) {
        return switch (id) {
            case 0 -> CustomMappingAccess.BlockEntityRule.NONE;
            case 1 -> CustomMappingAccess.BlockEntityRule.MOD_BLOCK;
            case 2 -> CustomMappingAccess.BlockEntityRule.NOOP;
            case 3 -> CustomMappingAccess.BlockEntityRule.DROP;
            default -> throw new IllegalArgumentException("Invalid block entity rule " + id);
        };
    }

    private record WirePropertyOrder(int nameId, int typeId, boolean boolValue, int intValue, int stringValueId) implements Comparable<WirePropertyOrder> {
        @Override
        public int compareTo(final WirePropertyOrder other) {
            int cmp = Integer.compare(this.nameId, other.nameId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.typeId, other.typeId);
            if (cmp != 0) return cmp;
            return switch (this.typeId) {
                case 0 -> Boolean.compare(this.boolValue, other.boolValue);
                case 1 -> Integer.compare(this.intValue, other.intValue);
                case 2 -> Integer.compare(this.stringValueId, other.stringValueId);
                default -> throw new IllegalArgumentException("Invalid property type " + this.typeId);
            };
        }
    }

    private record WireBlockStateOrder(int identifierId, List<WirePropertyOrder> properties) implements Comparable<WireBlockStateOrder> {
        @Override
        public int compareTo(final WireBlockStateOrder other) {
            int cmp = Integer.compare(this.identifierId, other.identifierId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.properties.size(), other.properties.size());
            if (cmp != 0) return cmp;
            for (int i = 0; i < this.properties.size(); i++) {
                cmp = this.properties.get(i).compareTo(other.properties.get(i));
                if (cmp != 0) return cmp;
            }
            return 0;
        }
    }

    private record WireBlockEntityOrder(int bedrockIdentifierId, int javaIdentifierId, int javaRawId) implements Comparable<WireBlockEntityOrder> {
        @Override
        public int compareTo(final WireBlockEntityOrder other) {
            int cmp = Integer.compare(this.bedrockIdentifierId, other.bedrockIdentifierId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.javaIdentifierId, other.javaIdentifierId);
            if (cmp != 0) return cmp;
            return Integer.compare(this.javaRawId, other.javaRawId);
        }
    }

    private record WireItemOrder(int bedrockIdentifierId, int javaRawId, int maxStackSize) implements Comparable<WireItemOrder> {
        @Override
        public int compareTo(final WireItemOrder other) {
            int cmp = Integer.compare(this.bedrockIdentifierId, other.bedrockIdentifierId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.javaRawId, other.javaRawId);
            if (cmp != 0) return cmp;
            return Integer.compare(this.maxStackSize, other.maxStackSize);
        }
    }

    private static final class Reader {
        private final ByteBuf buf;
        private final int maxByteArrayLength;

        Reader(final byte[] bytes, final int maxByteArrayLength) {
            this.buf = Unpooled.wrappedBuffer(bytes);
            this.maxByteArrayLength = maxByteArrayLength;
        }

        int readUnsignedByte() {
            return this.buf.readUnsignedByte();
        }

        float readFloat() {
            if (this.buf.readableBytes() < 4) throw new IllegalArgumentException("Truncated float");
            return this.buf.readFloat();
        }

        boolean readBool() {
            final int b = readUnsignedByte();
            if (b > 1) throw new IllegalArgumentException("Invalid bool");
            return b == 1;
        }

        int readNonNegativeVarInt(final String field) {
            final int value = this.readVarInt();
            if (value < 0) throw new IllegalArgumentException("Negative " + field);
            return value;
        }

        int readVarInt() {
            int result = 0;
            int shift = 0;
            for (int i = 0; i < 5; i++) {
                final int b = readUnsignedByte();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IllegalArgumentException("VarInt too long");
        }

        String readString() {
            final int len = readNonNegativeVarInt("string length");
            if (len > 32767 || len > this.buf.readableBytes() || len > this.maxByteArrayLength) throw new IllegalArgumentException("Invalid string length");
            final byte[] bytes = new byte[len];
            this.buf.readBytes(bytes);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid UTF-8 string", e);
            }
        }

        void ensureFullyRead() {
            if (this.buf.isReadable()) throw new IllegalArgumentException("Unexpected trailing snapshot body bytes: " + this.buf.readableBytes());
        }
    }
}
