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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.pyrpc;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ModEventS2C 中 Glow schema 的窄解码器。
 */
public final class GlowModEventCodec {
    public static final String MOD_NAME = "ECNukkitClientMod";
    public static final String SERVER_SYSTEM = "ECNukkitServerSystem";
    public static final String MOD_EVENT = "ModEventS2C";
    public static final String UPDATE_EVENT = "RequestEntityGlowUpdate";
    public static final String SYNC_EVENT = "RequestEntityGlowSync";
    public static final int SCHEMA = 1;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_ENTITY_ID_LENGTH = 32;

    private GlowModEventCodec() {
    }

    public sealed interface Message permits Update, Sync {
        long revision();
    }

    public record Update(String entityId, boolean enabled, int red, int green, int blue, long revision)
            implements Message {
    }

    public record Sync(long revision, List<Update> entries) implements Message {
        public Sync {
            entries = List.copyOf(entries);
        }
    }

    public static Optional<Message> decode(final byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            return Optional.empty();
        }
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload)) {
            Value root = unpacker.unpackValue();
            if (unpacker.hasNext() || !root.isArrayValue()) {
                return Optional.empty();
            }
            List<Value> outer = root.asArrayValue().list();
            if (outer.size() != 3 || !MOD_EVENT.equals(stringValue(outer.get(0)))
                    || !outer.get(2).isNilValue()) {
                return Optional.empty();
            }
            if (!outer.get(1).isArrayValue()) {
                return Optional.empty();
            }
            List<Value> args = outer.get(1).asArrayValue().list();
            if (args.size() != 4 || !MOD_NAME.equals(stringValue(args.get(0)))
                    || !SERVER_SYSTEM.equals(stringValue(args.get(1)))) {
                return Optional.empty();
            }
            String eventName = stringValue(args.get(2));
            if (UPDATE_EVENT.equals(eventName)) {
                return parseUpdate(args.get(3), null);
            }
            if (SYNC_EVENT.equals(eventName)) {
                return parseSync(args.get(3));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Message> parseSync(final Value value) {
        if (!value.isMapValue()) {
            return Optional.empty();
        }
        MapValue map = value.asMapValue();
        if (integerValue(map, "schema", -1) != SCHEMA
                || !booleanValue(map, "replace", false)) {
            return Optional.empty();
        }
        long revision = longValue(map, "revision", -1L);
        Value entriesValue = mapValue(map, "entries");
        if (revision < 0 || entriesValue == null || !entriesValue.isArrayValue()) {
            return Optional.empty();
        }
        List<Value> entries = entriesValue.asArrayValue().list();
        if (entries.size() > MAX_ENTRIES) {
            return Optional.empty();
        }
        List<Update> updates = new ArrayList<>(entries.size());
        List<String> ids = new ArrayList<>(entries.size());
        for (Value entry : entries) {
            Optional<Message> parsed = parseUpdate(entry, revision);
            if (parsed.isEmpty() || !(parsed.get() instanceof Update update)
                    || ids.contains(update.entityId())) {
                return Optional.empty();
            }
            ids.add(update.entityId());
            updates.add(update);
        }
        return Optional.of(new Sync(revision, updates));
    }

    private static Optional<Message> parseUpdate(final Value value, final Long inheritedRevision) {
        if (!value.isMapValue()) {
            return Optional.empty();
        }
        MapValue map = value.asMapValue();
        if (integerValue(map, "schema", -1) != SCHEMA) {
            return Optional.empty();
        }
        String entityId = stringValue(mapValue(map, "entity_id"));
        if (!isEntityId(entityId)) {
            return Optional.empty();
        }
        Value enabledValue = mapValue(map, "enabled");
        if (enabledValue == null || !enabledValue.isBooleanValue()) {
            return Optional.empty();
        }
        boolean enabled = enabledValue.asBooleanValue().getBoolean();
        long revision = inheritedRevision != null
                ? inheritedRevision
                : longValue(map, "revision", -1L);
        if (revision < 0) {
            return Optional.empty();
        }
        int red = 255;
        int green = 255;
        int blue = 255;
        if (enabled) {
            red = integerValue(map, "red", -1);
            green = integerValue(map, "green", -1);
            blue = integerValue(map, "blue", -1);
            if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
                return Optional.empty();
            }
        }
        return Optional.of(new Update(entityId, enabled, red, green, blue, revision));
    }

    private static boolean isEntityId(final String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ENTITY_ID_LENGTH) {
            return false;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static Value mapValue(final MapValue map, final String key) {
        for (Map.Entry<Value, Value> entry : map.map().entrySet()) {
            if (key.equals(stringValue(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stringValue(final Value value) {
        if (value == null) {
            return null;
        }
        if (value.isStringValue()) {
            return value.asStringValue().asString();
        }
        if (value.isBinaryValue()) {
            return new String(value.asBinaryValue().asByteArray(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static int integerValue(final MapValue map, final String key, final int fallback) {
        Value value = mapValue(map, key);
        if (value == null || !value.isIntegerValue()) {
            return fallback;
        }
        long number = value.asIntegerValue().toLong();
        return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? fallback : (int) number;
    }

    private static long longValue(final MapValue map, final String key, final long fallback) {
        Value value = mapValue(map, key);
        if (value == null || !value.isIntegerValue()) {
            return fallback;
        }
        return value.asIntegerValue().toLong();
    }

    private static boolean booleanValue(final MapValue map, final String key, final boolean fallback) {
        Value value = mapValue(map, key);
        return value != null && value.getValueType() == ValueType.BOOLEAN
                ? value.asBooleanValue().getBoolean() : fallback;
    }
}
