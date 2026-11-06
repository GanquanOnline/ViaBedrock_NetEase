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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code LEVEL_SOUND_EVENT} 声音名字与数字 id 的对照表。
 * <p>
 * 协议 1001 起该包不再写数字 id，而是写名字字符串。表由 Nukkit-MOT 的
 * {@code LevelSoundEventPacket}（常量定义 + {@code getSoundName} 开关）导出，
 * 因为这里要对的是 MOT 服务端真正在线上收发的名字，而不是官方文档的历史命名。
 * 生成脚本：{@code scratch/m3a-forks/gen_sound_names.py}。
 */
public final class LevelSoundEventNames {

    private static final String RESOURCE = "assets/viabedrock/data/bedrock/level_sound_event_names.json";

    private final Map<String, Integer> ids;
    private final Map<Integer, String> names;

    private LevelSoundEventNames(final Map<String, Integer> ids) {
        this.ids = Collections.unmodifiableMap(ids);
        final Map<Integer, String> reversed = new HashMap<>(ids.size());
        for (final Map.Entry<String, Integer> entry : ids.entrySet()) {
            reversed.putIfAbsent(entry.getValue(), entry.getKey());
        }
        this.names = Collections.unmodifiableMap(reversed);
    }

    /** @return 声音名对应的数字 id，未知名字返回 null */
    public static Integer id(final String name) {
        return Holder.INSTANCE.ids.get(name);
    }

    /** @return 数字 id 对应的声音名，未知 id 返回 null */
    public static String name(final int id) {
        return Holder.INSTANCE.names.get(id);
    }

    /** @return 声音名对应的数字 id；名字未知时抛异常，避免把错位的数据继续往下传 */
    public static int requireId(final String name) {
        final Integer id = id(name);
        if (id == null) {
            throw new IllegalStateException("Unknown level sound event name: " + name);
        }
        return id;
    }

    /** @return 数字 id 对应的声音名；id 未知时抛异常 */
    public static String requireName(final int id) {
        final String name = name(id);
        if (name == null) {
            throw new IllegalStateException("Unknown level sound event id: " + id);
        }
        return name;
    }

    public static int size() {
        return Holder.INSTANCE.ids.size();
    }

    private static LevelSoundEventNames load() {
        try (InputStream stream = LevelSoundEventNames.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                final JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                final Map<String, Integer> ids = new HashMap<>(json.size());
                for (final Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    ids.put(entry.getKey(), entry.getValue().getAsInt());
                }
                return new LevelSoundEventNames(ids);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + RESOURCE, e);
        }
    }

    private static final class Holder {
        private static final LevelSoundEventNames INSTANCE = load();
    }

}
