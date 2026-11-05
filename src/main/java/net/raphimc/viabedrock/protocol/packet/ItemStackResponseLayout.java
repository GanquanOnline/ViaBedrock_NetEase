/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.model.ContainerSlotTypeLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 基岩 ITEM_STACK_RESPONSE（数据包 0x94）的线上布局辅助工具。
 * <p>
 * 官方 975 和网易 860 仍都编码为：结果字节、请求 id，
 * 然后在 OK 时跟一个容器数组。每个容器在 712+ 是必需的 FullContainerName，
 * 其后是槽位条目，其 stack-network id 为有符号 varint。
 * 协议 2168 增加了可选的容器 / stack-id boolean，在 860 或官方 975 上
 * 绝不能读取，否则下一次点击请求会被当成残留数据解析。
 */
public final class ItemStackResponseLayout {

    public static final int FULL_CONTAINER_NAME_PROTOCOL = 712;
    public static final int CUSTOM_NAME_PROTOCOL = 422;
    public static final int FILTERED_CUSTOM_NAME_PROTOCOL = 766;
    public static final int DURABILITY_CORRECTION_PROTOCOL = 428;
    public static final int OPTIONAL_CONTAINER_PROTOCOL = 2168;
    public static final byte RESULT_OK = 0;

    private ItemStackResponseLayout() {
    }

    public static boolean usesOptionalContainerEntries(final boolean emulateNetEase, final int protocol) {
        return protocol >= OPTIONAL_CONTAINER_PROTOCOL;
    }

    public static boolean usesFullContainerName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= FULL_CONTAINER_NAME_PROTOCOL;
    }

    public static boolean usesCustomName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= CUSTOM_NAME_PROTOCOL;
    }

    public static boolean usesFilteredCustomName(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= FILTERED_CUSTOM_NAME_PROTOCOL;
    }

    public static boolean usesDurabilityCorrection(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= DURABILITY_CORRECTION_PROTOCOL;
    }

    public static DecodedResponse skip(final PacketWrapper wrapper) {
        return decode(wrapper, emulateNetEase(), encodeProtocol());
    }

    public static DecodedResponse skip(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        return decode(wrapper, emulateNetEase, protocol);
    }

    public static DecodedResponse skip(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        return decode(buffer, emulateNetEase, protocol);
    }

    public static DecodedResponse decode(final PacketWrapper wrapper) {
        return decode(wrapper, emulateNetEase(), encodeProtocol());
    }

    public static DecodedResponse decode(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        boolean anyRejected = false;
        final int[] requestIds = new int[Math.max(0, count)];
        final List<DecodedEntry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final DecodedEntry entry = decodeEntry(wrapper, emulateNetEase, protocol);
            requestIds[i] = entry.requestId();
            entries.add(entry);
            if (!entry.ok()) {
                anyRejected = true;
            }
        }
        return new DecodedResponse(count, anyRejected, requestIds, List.copyOf(entries));
    }

    public static DecodedResponse decode(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        boolean anyRejected = false;
        final int[] requestIds = new int[Math.max(0, count)];
        final List<DecodedEntry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final DecodedEntry entry = decodeEntry(buffer, emulateNetEase, protocol);
            requestIds[i] = entry.requestId();
            entries.add(entry);
            if (!entry.ok()) {
                anyRejected = true;
            }
        }
        return new DecodedResponse(count, anyRejected, requestIds, List.copyOf(entries));
    }

    public static boolean skipEntry(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        return decodeEntry(wrapper, emulateNetEase, protocol).ok();
    }

    public static boolean skipEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        return decodeEntry(buffer, emulateNetEase, protocol).ok();
    }

    public static DecodedEntry skipEntryDetailed(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        return decodeEntry(wrapper, emulateNetEase, protocol);
    }

    public static DecodedEntry skipEntryDetailed(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        return decodeEntry(buffer, emulateNetEase, protocol);
    }

    public static DecodedEntry decodeEntry(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final byte result = wrapper.read(Types.BYTE);
        final int requestId = wrapper.read(BedrockTypes.VAR_INT);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            wrapper.read(Types.BOOLEAN); // 当前 Nukkit 上总是 true
            if (!wrapper.read(Types.BOOLEAN)) {
                return new DecodedEntry(requestId, result == RESULT_OK, List.of());
            }
        } else if (result != RESULT_OK) {
            return new DecodedEntry(requestId, false, List.of());
        }
        return new DecodedEntry(requestId, result == RESULT_OK, decodeContainers(wrapper, emulateNetEase, protocol));
    }

    public static DecodedEntry decodeEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final byte result = buffer.readByte();
        final int requestId = BedrockTypes.VAR_INT.read(buffer);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.readBoolean(); // 当前 Nukkit 上总是 true
            if (!buffer.readBoolean()) {
                return new DecodedEntry(requestId, result == RESULT_OK, List.of());
            }
        } else if (result != RESULT_OK) {
            return new DecodedEntry(requestId, false, List.of());
        }
        return new DecodedEntry(requestId, result == RESULT_OK, decodeContainers(buffer, emulateNetEase, protocol));
    }

    public static void skipContainers(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        decodeContainers(wrapper, emulateNetEase, protocol);
    }

    public static void skipContainers(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        decodeContainers(buffer, emulateNetEase, protocol);
    }

    public static List<DecodedContainer> decodeContainers(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<DecodedContainer> containers = new ArrayList<>(Math.max(0, containerCount));
        for (int i = 0; i < containerCount; i++) {
            final FullContainerName name;
            if (usesFullContainerName(emulateNetEase, protocol)) {
                name = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
            } else {
                name = new FullContainerName(ContainerSlotTypeLayout.fromWire(wrapper.read(Types.BYTE) & 0xFF, emulateNetEase), null);
            }
            containers.add(new DecodedContainer(name, decodeSlots(wrapper, emulateNetEase, protocol)));
        }
        return List.copyOf(containers);
    }

    public static List<DecodedContainer> decodeContainers(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int containerCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final List<DecodedContainer> containers = new ArrayList<>(Math.max(0, containerCount));
        for (int i = 0; i < containerCount; i++) {
            final FullContainerName name;
            if (usesFullContainerName(emulateNetEase, protocol)) {
                name = BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
            } else {
                name = new FullContainerName(ContainerSlotTypeLayout.fromWire(buffer.readByte() & 0xFF, emulateNetEase), null);
            }
            containers.add(new DecodedContainer(name, decodeSlots(buffer, emulateNetEase, protocol)));
        }
        return List.copyOf(containers);
    }

    public static void skipSlots(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        decodeSlots(wrapper, emulateNetEase, protocol);
    }

    public static void skipSlots(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        decodeSlots(buffer, emulateNetEase, protocol);
    }

    public static List<DecodedSlot> decodeSlots(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int slotCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<DecodedSlot> slots = new ArrayList<>(Math.max(0, slotCount));
        for (int i = 0; i < slotCount; i++) {
            final int slot = wrapper.read(Types.BYTE) & 0xFF;
            final int hotbarSlot = wrapper.read(Types.BYTE) & 0xFF;
            final int count = wrapper.read(Types.BYTE) & 0xFF;
            final int stackNetworkId;
            if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
                wrapper.read(Types.BOOLEAN); // 当前 Nukkit 上总是 true
                stackNetworkId = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.VAR_INT) : 0;
            } else {
                stackNetworkId = wrapper.read(BedrockTypes.VAR_INT);
            }
            final String customName = usesCustomName(emulateNetEase, protocol)
                    ? wrapper.read(BedrockTypes.STRING) : null;
            final String filteredCustomName = usesFilteredCustomName(emulateNetEase, protocol)
                    ? wrapper.read(BedrockTypes.STRING) : null;
            final Integer durabilityCorrection = usesDurabilityCorrection(emulateNetEase, protocol)
                    ? wrapper.read(BedrockTypes.VAR_INT) : null;
            slots.add(new DecodedSlot(slot, hotbarSlot, count, stackNetworkId,
                    customName, filteredCustomName, durabilityCorrection));
        }
        return List.copyOf(slots);
    }

    public static List<DecodedSlot> decodeSlots(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int slotCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final List<DecodedSlot> slots = new ArrayList<>(Math.max(0, slotCount));
        for (int i = 0; i < slotCount; i++) {
            final int slot = buffer.readByte() & 0xFF;
            final int hotbarSlot = buffer.readByte() & 0xFF;
            final int count = buffer.readByte() & 0xFF;
            final int stackNetworkId;
            if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
                buffer.readBoolean(); // 当前 Nukkit 上总是 true
                stackNetworkId = buffer.readBoolean() ? BedrockTypes.VAR_INT.read(buffer) : 0;
            } else {
                stackNetworkId = BedrockTypes.VAR_INT.read(buffer);
            }
            final String customName = usesCustomName(emulateNetEase, protocol)
                    ? BedrockTypes.STRING.read(buffer) : null;
            final String filteredCustomName = usesFilteredCustomName(emulateNetEase, protocol)
                    ? BedrockTypes.STRING.read(buffer) : null;
            final Integer durabilityCorrection = usesDurabilityCorrection(emulateNetEase, protocol)
                    ? BedrockTypes.VAR_INT.read(buffer) : null;
            slots.add(new DecodedSlot(slot, hotbarSlot, count, stackNetworkId,
                    customName, filteredCustomName, durabilityCorrection));
        }
        return List.copyOf(slots);
    }

    public static void writeOkEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol,
                                    final int requestId, final int containerWireId, final int slot,
                                    final int count, final int stackNetworkId) {
        writeOkEntry(buffer, emulateNetEase, protocol, requestId, containerWireId, slot,
                count, stackNetworkId, "", "", 0);
    }

    public static void writeOkEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol,
                                    final int requestId, final int containerWireId, final int slot,
                                    final int count, final int stackNetworkId, final String customName,
                                    final String filteredCustomName, final int durabilityCorrection) {
        buffer.writeByte(RESULT_OK);
        BedrockTypes.VAR_INT.write(buffer, requestId);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
        if (usesFullContainerName(emulateNetEase, protocol)) {
            buffer.writeByte(containerWireId & 0xFF);
            buffer.writeBoolean(false);
        } else {
            buffer.writeByte(containerWireId & 0xFF);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
        buffer.writeByte(slot);
        buffer.writeByte(slot);
        buffer.writeByte(count);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
            BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
        } else {
            BedrockTypes.VAR_INT.write(buffer, stackNetworkId);
        }
        if (usesCustomName(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, customName != null ? customName : "");
        }
        if (usesFilteredCustomName(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, filteredCustomName != null ? filteredCustomName : "");
        }
        if (usesDurabilityCorrection(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, durabilityCorrection);
        }
    }

    public static void writeRejectedEntry(final ByteBuf buffer, final boolean emulateNetEase, final int protocol, final int requestId) {
        buffer.writeByte(1);
        BedrockTypes.VAR_INT.write(buffer, requestId);
        if (usesOptionalContainerEntries(emulateNetEase, protocol)) {
            buffer.writeBoolean(true);
            buffer.writeBoolean(false);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig() != null ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
    }

    private static int encodeProtocol() {
        if (emulateNetEase() && netEaseProtocol() > 0) {
            return netEaseProtocol();
        }
        return 975;
    }

    public record DecodedSlot(int slot, int hotbarSlot, int count, int stackNetworkId,
                              String customName, String filteredCustomName, Integer durabilityCorrection) {
        public DecodedSlot(final int slot, final int hotbarSlot, final int count, final int stackNetworkId) {
            this(slot, hotbarSlot, count, stackNetworkId, null, null, null);
        }
    }

    public record DecodedContainer(FullContainerName container, List<DecodedSlot> slots) {
        public DecodedContainer {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        public ContainerEnumName name() {
            return this.container != null ? this.container.name() : null;
        }

        public Integer dynamicId() {
            return this.container != null ? this.container.dynamicId() : null;
        }
    }

    public record DecodedEntry(int requestId, boolean ok, List<DecodedContainer> containers) {
        public DecodedEntry(final int requestId, final boolean ok) {
            this(requestId, ok, List.of());
        }

        public DecodedEntry {
            containers = containers == null ? List.of() : List.copyOf(containers);
        }
    }

    public record DecodedResponse(int entryCount, boolean anyRejected, int[] requestIds, List<DecodedEntry> entries) {
        public DecodedResponse(final int entryCount, final boolean anyRejected) {
            this(entryCount, anyRejected, new int[0], List.of());
        }

        public DecodedResponse(final int entryCount, final boolean anyRejected, final int[] requestIds) {
            this(entryCount, anyRejected, requestIds, List.of());
        }

        public DecodedResponse {
            requestIds = requestIds == null ? new int[0] : requestIds;
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

}
