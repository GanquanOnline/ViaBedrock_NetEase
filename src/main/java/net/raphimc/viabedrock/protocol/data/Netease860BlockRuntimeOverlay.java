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
package net.raphimc.viabedrock.protocol.data;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.api.model.BedrockBlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * MOT {@code runtime_block_states_netease_860.dat} 的覆盖层。
 * <p>
 * 哈希型 {@code network_id} 与 ViaBedrock 的国际版调色板一致（对小端 {@code {name, states}} 做 FNV-1a）。
 * 顺序型 {@code runtimeId} 则不一致：MOT 在转储中给出它们，而 ViaBedrock 的顺序路径使用
 * 按名称哈希的顺序。{@code minecraft:micro_block} 只存在于 MOT 的转储中。
 */
public final class Netease860BlockRuntimeOverlay {

    /**
     * MOT 的 {@code GlobalBlockPalette.NETEASE_PALETTE_THRESHOLDS} 把网易 898 映射到标准
     * V1_21_111 调色板，而不是 {@code runtime_block_states_netease_860.dat}，因此该
     * 顺序覆盖层只描述协议 860。
     */
    public static final int SEQUENTIAL_OVERLAY_END_PROTOCOL = 898;

    /**
     * 该覆盖层替换原版的顺序 id 顺序，因此只在服务端下发顺序 id 且仍
     * 提供网易 860 调色板时生效。
     */
    public static boolean applies(final boolean emulateNetEase, final int protocol, final boolean hashedRuntimeBlockIds) {
        return emulateNetEase && !hashedRuntimeBlockIds && protocol > 0 && protocol < SEQUENTIAL_OVERLAY_END_PROTOCOL;
    }

    public record ExtraState(String name, int networkId, int runtimeId) {
        public CompoundTag toBlockStateTag() {
            final CompoundTag tag = new CompoundTag();
            tag.putString("name", this.name);
            tag.put("states", new CompoundTag());
            tag.putInt("network_id", this.networkId);
            tag.putInt("version", 18168865);
            return tag;
        }

        public BedrockBlockState toBedrockBlockState() {
            return BedrockBlockState.fromNbt(this.toBlockStateTag());
        }
    }

    private final Int2IntMap hashedToSequential;
    private final List<ExtraState> extraStates;

    private Netease860BlockRuntimeOverlay(final Int2IntMap hashedToSequential, final List<ExtraState> extraStates) {
        this.hashedToSequential = hashedToSequential;
        this.extraStates = extraStates;
    }

    public static Netease860BlockRuntimeOverlay empty() {
        final Int2IntMap map = new Int2IntOpenHashMap();
        map.defaultReturnValue(-1);
        return new Netease860BlockRuntimeOverlay(map, List.of());
    }

    public static Netease860BlockRuntimeOverlay parse(final JsonObject json) {
        if (json == null) {
            return empty();
        }
        final Int2IntMap map = new Int2IntOpenHashMap();
        map.defaultReturnValue(-1);
        final JsonArray pairs = json.getAsJsonArray("hashedToSequential");
        if (pairs != null) {
            for (JsonElement entry : pairs) {
                final JsonArray pair = entry.getAsJsonArray();
                map.put(pair.get(0).getAsInt(), pair.get(1).getAsInt());
            }
        }
        final List<ExtraState> extras = new ArrayList<>();
        final JsonArray extraJson = json.getAsJsonArray("extraStates");
        if (extraJson != null) {
            for (JsonElement entry : extraJson) {
                final JsonObject object = entry.getAsJsonObject();
                extras.add(new ExtraState(
                        object.get("name").getAsString(),
                        object.get("network_id").getAsInt(),
                        object.get("runtimeId").getAsInt()
                ));
            }
        }
        return new Netease860BlockRuntimeOverlay(map, List.copyOf(extras));
    }

    public Int2IntMap hashedToSequential() {
        return this.hashedToSequential;
    }

    public List<ExtraState> extraStates() {
        return this.extraStates;
    }

    public boolean isEmpty() {
        return this.hashedToSequential.isEmpty() && this.extraStates.isEmpty();
    }

    public int sequentialRuntimeId(final int hashedNetworkId) {
        return this.hashedToSequential.get(hashedNetworkId);
    }

    public int nextCustomSequentialId() {
        int next = 0;
        for (int value : this.hashedToSequential.values()) {
            if (value >= next) {
                next = value + 1;
            }
        }
        for (ExtraState extra : this.extraStates) {
            if (extra.runtimeId() >= next) {
                next = extra.runtimeId() + 1;
            }
        }
        return next;
    }
}
