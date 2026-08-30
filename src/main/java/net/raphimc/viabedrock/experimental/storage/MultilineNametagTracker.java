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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.PlayerTeamMethod;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamCollisionRule;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamVisibility;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.util.*;

/**
 * Tracks and manages virtual entities for Bedrock multiline nametags.
 * <p>
 * Bedrock edition natively supports multiline nametags (via \n), but Java edition's
 * CUSTOM_NAME ignores newlines and renders single-line. This tracker uses two strategies:
 * <ul>
 *   <li><b>Player entities</b>: invisible armor stands (one per extra line), with the player's
 *       own team nametag serving as the bottom line. Each armor stand has a different
 *       {@code minecraft:scale} attribute to position its nametag at the correct Y height.</li>
 *   <li><b>Non-player entities</b>: a single TEXT_DISPLAY entity with multiline text support.</li>
 * </ul>
 * <p>
 * Both strategies use SET_PASSENGERS to ride the host entity, so the client automatically
 * keeps virtual entities in sync with the host's position (no manual position sync needed).
 * <p>
 * Fully decoupled from upstream code — uses ProtocolUtil.appendClientbound to hook
 * into existing packet handlers without modifying them.
 * <p>
 * IMPORTANT: After resetReader(), the wrapper contains Java-format data (written by the
 * upstream handler via write()), NOT the original Bedrock data (consumed by read() and lost).
 * All append handlers must read Java types (e.g. Types.VAR_INT) not Bedrock types.
 */
public class MultilineNametagTracker extends StoredObject {

    private static final float LINE_HEIGHT = 0.28f;
    private static final float ARMOR_STAND_BASE_HEIGHT = 1.975f;
    private static final float PASSENGER_OFFSET = 0.0625f;
    private static final double SNEAKING_NAMETAG_DISTANCE_SQUARED = 32D * 32D;
    private static final String PASSENGER_SOURCE = "multiline-nametag";

    private final Map<Long, NametagDisplayInfo> displays = new HashMap<>();
    // Reverse mapping: host entity Java ID → entity uniqueId
    // Needed because by the time our REMOVE_ENTITY handler runs,
    // the entity is already removed from EntityTracker.
    private final Map<Integer, Long> hostJavaIdToUniqueId = new HashMap<>();

    public MultilineNametagTracker(final UserConnection user) {
        super(user);
    }

    // ---- Packet handler registration ----

    public static void registerHandlers(final BedrockProtocol protocol) {
        // 1. ADD_ENTITY — detect multiline nametags on entity spawn
        // Upstream uses send()+cancel() pattern. After resetReader(), packetValues contain
        // Java ADD_ENTITY format: VarInt(javaId), UUID, VarInt(typeId), ...
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_ENTITY, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                }
            } catch (final Exception ignored) {
            }
        });

        // 2. ADD_PLAYER — detect multiline nametags on player spawn
        // Upstream uses send()+cancel() pattern. After resetReader(), packetValues contain
        // Java ADD_ENTITY format: VarInt(javaId), UUID, VarInt(typeId), ...
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_PLAYER, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                }
            } catch (final Exception ignored) {
            }
        });

        // 3. SET_ENTITY_DATA — detect nametag changes
        // Upstream writes: VarInt(javaId), EntityDataList(javaEntityData)
        // After handleEntityDataUpdate, we filter CUSTOM_NAME/CUSTOM_NAME_VISIBLE from the wrapper
        // to prevent the upstream's entity data from re-enabling the host's nametag.
        // This is necessary because the append handler's corrective packets (sendClearHostNametag)
        // are sent BEFORE the wrapper's packet, so the wrapper would overwrite the correction.
        // By modifying the entity data list in-place (same object reference as in the wrapper's
        // packetValues), we ensure the filtered data is what reaches the client.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.SET_ENTITY_DATA, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            try {
                wrapper.resetReader();
                final int javaEntityId = wrapper.passthrough(Types.VAR_INT);
                final Entity entity = entityTracker.getEntityByJid(javaEntityId);
                if (entity != null) {
                    tracker.handleEntityDataUpdate(entity);
                    // Filter CUSTOM_NAME/CUSTOM_NAME_VISIBLE from the wrapper's entity data
                    // if a TextDisplay is active. Must run AFTER handleEntityDataUpdate because
                    // that call may create a new display for a newly-multiline name.
                    tracker.filterHostNametagFromPacket(entity, wrapper);
                }
            } catch (final Exception ignored) {
            }
        });

        // 4. REMOVE_ENTITY — cleanup virtual entities
        // Upstream writes REMOVE_ENTITIES: VarIntArrayPrimitive(int[]{javaId})
        // Entity is already removed from EntityTracker at this point,
        // so we use hostJavaIdToUniqueId for reverse lookup.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.REMOVE_ENTITY, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            try {
                wrapper.resetReader();
                final int[] javaEntityIds = wrapper.passthrough(Types.VAR_INT_ARRAY_PRIMITIVE);
                for (final int javaId : javaEntityIds) {
                    final Long entityUid = tracker.hostJavaIdToUniqueId.get(javaId);
                    if (entityUid != null) {
                        tracker.handleEntityRemoved(entityUid);
                    }
                }
            } catch (final Exception ignored) {
            }
        });

        // 5. CHANGE_DIMENSION — clear all state
        // Note: Do NOT check isCancelled() here — upstream uses send()+cancel() pattern,
        // so the dimension change DID happen. Java client clears all entities, so we must clear our tracking state.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.CHANGE_DIMENSION, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker != null) {
                tracker.clearAll();
            }
        });
    }

    // ---- Core logic ----

    /**
     * Handles entity data updates — checks for multiline nametags and creates/updates/removes display.
     * Uses armor stand strategy for player entities, TEXT_DISPLAY strategy for non-player entities.
     */
    public void handleEntityDataUpdate(final Entity entity) {
        // Never show nametag to the client player themselves
        if (entity instanceof ClientPlayerEntity) return;

        String name = getEntityName(entity);
        if (name != null) {
            // Trim leading and trailing blank lines (incl. formatting-only lines) so the floating
            // display has no empty top/bottom rows. Interior lines are kept for genuine multiline names.
            name = TextUtil.trimBlankLines(name);
            if (name.isEmpty()) name = null;
        }
        final boolean isMultiline = isMultilineName(name);
        final boolean alwaysShow = isNametagAlwaysShown(entity);
        final boolean isPlayer = entity instanceof PlayerEntity;
        final NametagDisplayInfo existing = displays.get(entity.uniqueId());

        if (isMultiline && alwaysShow) {
            if (existing == null) {
                if (isPlayer) {
                    createArmorStandDisplay((PlayerEntity) entity, name);
                } else {
                    createTextDisplay(entity, name);
                }
            } else {
                if (existing instanceof ArmorStandInfo armorStandInfo) {
                    updateArmorStandDisplay((PlayerEntity) entity, armorStandInfo, name,
                            resolvePlayerRenderState((PlayerEntity) entity));
                } else {
                    updateTextDisplay(entity, (TextDisplayInfo) existing, name);
                }
            }
        } else {
            if (existing != null) {
                hostJavaIdToUniqueId.remove(existing.hostJavaId);
                if (existing instanceof ArmorStandInfo armorStandInfo) {
                    removeArmorStandDisplay(armorStandInfo);
                } else {
                    removeTextDisplay((TextDisplayInfo) existing);
                }
                displays.remove(entity.uniqueId());
            }
        }
    }

    /**
     * Cleans up virtual entities when the host entity is removed.
     */
    public void handleEntityRemoved(final long entityUniqueId) {
        final NametagDisplayInfo info = displays.remove(entityUniqueId);
        if (info != null) {
            hostJavaIdToUniqueId.remove(info.hostJavaId);
            if (info instanceof ArmorStandInfo armorStandInfo) {
                removeArmorStandDisplay(armorStandInfo);
            } else {
                removeTextDisplay((TextDisplayInfo) info);
            }
        }
    }

    /**
     * Clears all tracked state (e.g., on dimension change).
     * Java client automatically clears all entities on dimension change, so no REMOVE_ENTITIES needed.
     */
    public void clearAll() {
        displays.clear();
        hostJavaIdToUniqueId.clear();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearSource(PASSENGER_SOURCE);
        }
    }

    /**
     * Reconciles viewer-dependent player nametag state. Stable state sends no packets.
     */
    public void tick() {
        if (this.displays.isEmpty()) return;

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final Iterator<Map.Entry<Long, NametagDisplayInfo>> iterator = this.displays.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Long, NametagDisplayInfo> entry = iterator.next();
            if (!(entry.getValue() instanceof ArmorStandInfo info)) continue;

            final Entity hostEntity = entityTracker.getEntityByUid(info.entityUniqueId);
            if (!(hostEntity instanceof PlayerEntity player) || hostEntity instanceof ClientPlayerEntity) {
                this.hostJavaIdToUniqueId.remove(info.hostJavaId);
                this.removeArmorStandDisplay(info);
                iterator.remove();
                continue;
            }

            this.applyArmorStandRenderState(info, resolvePlayerRenderState(player), info.lines.size());
        }
    }

    // ---- TEXT_DISPLAY strategy (non-player entities) ----

    private void createTextDisplay(final Entity entity, final String name) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final int javaId = entityTracker.getNextJavaEntityId();
        final UUID uuid = UUID.randomUUID();
        final TextDisplayInfo info = new TextDisplayInfo(javaId, uuid, entity.uniqueId(), entity.javaId(), name);
        displays.put(entity.uniqueId(), info);
        hostJavaIdToUniqueId.put(entity.javaId(), entity.uniqueId());

        // 1. Send corrective SET_ENTITY_DATA to hide the host entity's original CUSTOM_NAME
        sendClearHostNametag(entity);

        // 2. Spawn TEXT_DISPLAY entity at the host entity's position.
        // The client will reposition it to the passenger attachment point once SET_PASSENGERS arrives.
        info.lastScale = getEffectiveScale(entity);
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // entity id
        addEntity.write(Types.UUID, uuid); // uuid
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // type id
        addEntity.write(Types.DOUBLE, (double) entity.position().x()); // x
        addEntity.write(Types.DOUBLE, (double) entity.position().y()); // y
        addEntity.write(Types.DOUBLE, (double) entity.position().z()); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // velocity
        addEntity.write(Types.BYTE, (byte) 0); // pitch
        addEntity.write(Types.BYTE, (byte) 0); // yaw
        addEntity.write(Types.BYTE, (byte) 0); // head yaw
        addEntity.write(Types.VAR_INT, 0); // data
        addEntity.send(BedrockProtocol.class);

        // 3. Configure TEXT_DISPLAY entity data (including TRANSLATION for vertical offset)
        sendTextDisplayEntityData(info, name);

        // 4. Make TEXT_DISPLAY ride the host entity for automatic position sync
        sendSetPassengers(entity.javaId(), info.textDisplayJavaId);
    }

    private void updateTextDisplay(final Entity entity, final TextDisplayInfo info, final String name) {
        final boolean nameChanged = !name.equals(info.lastNameText);
        final float currentScale = getEffectiveScale(entity);
        final boolean scaleChanged = currentScale != info.lastScale;

        if (nameChanged || scaleChanged) {
            // Re-send corrective packet to ensure host nametag stays hidden
            sendClearHostNametag(entity);

            if (nameChanged) {
                info.lastNameText = name;
                final Tag textNbt = TextUtil.stringToNbt(name);
                final List<EntityData> displayData = new ArrayList<>();
                displayData.add(new EntityData(
                        textDisplayIndex(EntityDataFields.TEXT),
                        VersionedTypes.V26_1.entityDataTypes().componentType,
                        textNbt));
                final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
                setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
                setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
                setEntityData.send(BedrockProtocol.class);
            }
            if (scaleChanged) {
                info.lastScale = currentScale;
                sendTranslationUpdate(info);
            }
        }
    }

    private void removeTextDisplay(final TextDisplayInfo info) {
        // Clear riding relationship before removing the entity
        sendSetPassengers(info.hostJavaId);
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{info.textDisplayJavaId});
        removeEntities.send(BedrockProtocol.class);
    }

    private void sendTextDisplayEntityData(final TextDisplayInfo info, final String name) {
        info.lastNameText = name;

        final Tag textNbt = TextUtil.stringToNbt(name);
        final List<EntityData> displayData = new ArrayList<>();

        // TEXT — the multiline text content
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT),
                VersionedTypes.V26_1.entityDataTypes().componentType,
                textNbt));

        // LINE_WIDTH — large enough that hologram rows do not wrap into an extra overlapping
        // line. Avoid Integer.MAX_VALUE in case a client stores the width in a narrower int.
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.LINE_WIDTH),
                VersionedTypes.V26_1.entityDataTypes().varIntType,
                1_000_000));

        // TEXT_DISPLAY still inherits ENTITY.CUSTOM_NAME. Keep it empty so the display's own
        // vanilla nametag cannot stack on top of the TEXT field.
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                null));
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                false));

        // BILLBOARD_RENDER_CONSTRAINTS = 3 (CENTER — face camera on all axes, matches vanilla nametag rendering)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 3));

        // TRANSLATION — small vertical offset from passenger attachment point to nametag height.
        // Formula: 0.0625 * scale + 0.25 (derived from: desiredY - passengerAttachmentY)
        // Only ~0.3 blocks for scale=1, so MC-261696 billboard distortion is negligible.
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V26_1.entityDataTypes().vector3FType,
                new Vector3f(0f, getTranslationY(info.lastScale), 0f)));

        // BACKGROUND_COLOR = 0x40000000 (semi-transparent black, matches vanilla nametag style)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BACKGROUND_COLOR),
                VersionedTypes.V26_1.entityDataTypes().varIntType,
                0x40000000));

        // TEXT_OPACITY = -1 (0xFF = fully opaque)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT_OPACITY),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) -1));

        // VIEW_RANGE = 1.0 (default)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.VIEW_RANGE),
                VersionedTypes.V26_1.entityDataTypes().floatType,
                1.0f));

        // STYLE_FLAGS = 0x02 (see_through — render through blocks, like Bedrock nametags)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.STYLE_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 0x02));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
        setEntityData.send(BedrockProtocol.class);
    }

    private void sendTranslationUpdate(final TextDisplayInfo info) {
        final List<EntityData> displayData = new ArrayList<>();
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V26_1.entityDataTypes().vector3FType,
                new Vector3f(0f, getTranslationY(info.lastScale), 0f)));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, displayData);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * Sends a corrective SET_ENTITY_DATA to hide the host entity's original CUSTOM_NAME.
     * Only used for non-player entities (TEXT_DISPLAY strategy).
     * Player entities use {@link #sendPlayerTeamPrefix} instead.
     */
    private void sendClearHostNametag(final Entity entity) {
        final List<EntityData> clearData = new ArrayList<>();
        clearData.add(new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                null));
        clearData.add(new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                false));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, entity.javaId());
        setEntityData.write(VersionedTypes.V26_1.entityDataList, clearData);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * Filters CUSTOM_NAME and CUSTOM_NAME_VISIBLE from the wrapper's entity data list
     * when a TEXT_DISPLAY is active for the entity. This prevents the upstream handler's
     * entity data from re-enabling the host entity's original nametag.
     * <p>
     * Only used in the SET_ENTITY_DATA append handler. Uses {@code passthrough()} to
     * transfer the entity data list from readableObjects to packetValues (the output),
     * then modifies it in-place via {@code removeIf}. The list reference in packetValues
     * is the same object, so in-place changes are reflected in the serialized packet.
     * <p>
     * Must be called AFTER {@link #handleEntityDataUpdate} (which may create/remove displays).
     * Must NOT use {@code read()} — that would poll the list from readableObjects without
     * adding it to packetValues, causing the entity data list to be entirely missing from
     * the output packet.
     */
    private void filterHostNametagFromPacket(final Entity entity, final PacketWrapper wrapper) {
        // Player entities use team-based nametags, not CUSTOM_NAME — no filtering needed
        if (entity instanceof PlayerEntity) return;

        final NametagDisplayInfo existing = displays.get(entity.uniqueId());
        if (!(existing instanceof TextDisplayInfo)) return;

        // passthrough() polls the entity data list from readableObjects and adds it to
        // packetValues (the output). Since it returns the same ArrayList reference,
        // removeIf modifies the data that will be serialized to the client.
        final List<EntityData> entityDataList = wrapper.passthrough(VersionedTypes.V26_1.entityDataList);
        final int customNameIndex = entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME);
        final int customNameVisibleIndex = entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE);
        entityDataList.removeIf(ed ->
                ed.id() == customNameIndex || ed.id() == customNameVisibleIndex);
    }

    // ---- Armor Stand strategy (player entities) ----

    private void createArmorStandDisplay(final PlayerEntity entity, final String name) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final String[] allLines = name.split("\n");
        final int armorStandCount = allLines.length - 1; // bottom line goes on player team nametag
        final PlayerNametagRenderState renderState = resolvePlayerRenderState(entity);

        final ArmorStandInfo info = new ArmorStandInfo(entity.uniqueId(), entity.javaId(), name);
        displays.put(entity.uniqueId(), info);
        hostJavaIdToUniqueId.put(entity.javaId(), entity.uniqueId());

        // 1. Set player team nametag to the bottom line only
        sendPlayerTeamPrefix(entity, allLines[allLines.length - 1]);

        // 2. Spawn armor stands for the remaining lines (from bottom to top)
        for (int i = 0; i < armorStandCount; i++) {
            final int lineIndex = i + 1; // 1-based index for scale formula
            final String lineText = allLines[allLines.length - 1 - lineIndex];
            final int javaId = entityTracker.getNextJavaEntityId();
            final UUID uuid = UUID.randomUUID();
            info.lines.add(new ArmorStandLine(javaId, uuid, lineText));

            spawnArmorStand(entity, javaId, uuid);
            sendArmorStandEntityData(javaId, lineText, renderState);
            sendArmorStandScale(javaId, getArmorStandScale(lineIndex));
        }
        info.lastRenderState = renderState;

        // 3. Make all armor stands ride the player entity
        sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
    }

    private void updateArmorStandDisplay(final PlayerEntity entity, final ArmorStandInfo info, final String name,
                                         final PlayerNametagRenderState renderState) {
        if (name.equals(info.lastNameText)) {
            applyArmorStandRenderState(info, renderState, info.lines.size());
            return;
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        info.lastNameText = name;
        final String[] allLines = name.split("\n");
        final int requiredCount = allLines.length - 1;
        final int currentCount = info.lines.size();
        boolean passengersChanged = false;

        // Update player team nametag to the new bottom line
        sendPlayerTeamPrefix(entity, allLines[allLines.length - 1]);

        if (requiredCount < currentCount) {
            // Decrease: remove excess armor stands
            final List<ArmorStandLine> removed = new ArrayList<>(
                    info.lines.subList(requiredCount, currentCount));
            info.lines.subList(requiredCount, currentCount).clear();

            // Update passengers first (without removed IDs), then remove entities
            sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
            final int[] removeIds = removed.stream().mapToInt(l -> l.javaId).toArray();
            final PacketWrapper removeEntities = PacketWrapper.create(
                    ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, removeIds);
            removeEntities.send(BedrockProtocol.class);
            // passengersChanged stays false — SET_PASSENGERS already sent above
        } else if (requiredCount > currentCount) {
            // Increase: spawn new armor stands
            for (int i = currentCount; i < requiredCount; i++) {
                final int javaId = entityTracker.getNextJavaEntityId();
                final UUID uuid = UUID.randomUUID();
                info.lines.add(new ArmorStandLine(javaId, uuid, ""));
                spawnArmorStand(entity, javaId, uuid);
                sendArmorStandEntityData(javaId, "", renderState); // text updated below
            }
            passengersChanged = true;
        }

        // Update text for all armor stands
        for (int i = 0; i < info.lines.size(); i++) {
            final int lineIndex = i + 1;
            final String lineText = allLines[allLines.length - 1 - lineIndex];
            final ArmorStandLine line = info.lines.get(i);
            if (!lineText.equals(line.text)) {
                line.text = lineText;
                sendArmorStandNameUpdate(line.javaId, lineText);
            }
        }

        // Send scale for any newly added armor stands
        // (existing ones already have correct scale — it doesn't depend on host entity)
        if (passengersChanged) {
            for (int i = currentCount; i < info.lines.size(); i++) {
                sendArmorStandScale(info.lines.get(i).javaId, getArmorStandScale(i + 1));
            }
            sendSetPassengers(entity.javaId(), info.getVirtualEntityIds());
        }

        // Newly added lines already have renderState; only retained lines need a state delta.
        applyArmorStandRenderState(info, renderState, Math.min(currentCount, requiredCount));
    }

    private void removeArmorStandDisplay(final ArmorStandInfo info) {
        if (!info.lines.isEmpty()) {
            // Clear riding relationship
            sendSetPassengers(info.hostJavaId);
            // Remove all armor stand entities
            final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, info.getVirtualEntityIds());
            removeEntities.send(BedrockProtocol.class);
        }
        // No need to restore team prefix — either the entity is being removed (team removed too),
        // or the name became single-line (upstream PlayerEntity.updateName already sent correct team update).
    }

    private void spawnArmorStand(final Entity hostEntity, final int javaId, final UUID uuid) {
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // entity id
        addEntity.write(Types.UUID, uuid); // uuid
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.ARMOR_STAND.getId()); // type id
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().x()); // x
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().y()); // y
        addEntity.write(Types.DOUBLE, (double) hostEntity.position().z()); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // velocity
        addEntity.write(Types.BYTE, (byte) 0); // pitch
        addEntity.write(Types.BYTE, (byte) 0); // yaw
        addEntity.write(Types.BYTE, (byte) 0); // head yaw
        addEntity.write(Types.VAR_INT, 0); // data
        addEntity.send(BedrockProtocol.class);
    }

    private void sendArmorStandEntityData(final int javaId, final String text,
                                          final PlayerNametagRenderState renderState) {
        final List<EntityData> data = new ArrayList<>();

        // Always hide the armor stand model; 0x02 mirrors the host's sneaking name style.
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.SHARED_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                renderState.sharedFlags));

        // CUSTOM_NAME — the line text
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                TextUtil.stringToNbt(text)));

        // ArmorStandRenderer bypasses player distance/invisibility checks, so visibility is explicit.
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                renderState.nameVisible));

        // NO_GRAVITY = true
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.NO_GRAVITY),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                true));

        // CLIENT_FLAGS: 0x04 (no base plate). NOT marker (0x10) — marker makes height=0,
        // which would prevent scale-based vertical positioning of nametags.
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CLIENT_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                (byte) 0x04));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void applyArmorStandRenderState(final ArmorStandInfo info,
                                            final PlayerNametagRenderState renderState,
                                            final int existingLineCount) {
        final PlayerNametagRenderState previousState = info.lastRenderState;
        if (previousState == renderState) return;

        final boolean sharedFlagsChanged = previousState == null || previousState.sharedFlags != renderState.sharedFlags;
        final boolean visibilityChanged = previousState == null || previousState.nameVisible != renderState.nameVisible;
        final int updateCount = Math.min(existingLineCount, info.lines.size());
        for (int i = 0; i < updateCount; i++) {
            sendArmorStandRenderStateUpdate(info.lines.get(i).javaId, renderState,
                    sharedFlagsChanged, visibilityChanged);
        }
        info.lastRenderState = renderState;
    }

    private void sendArmorStandRenderStateUpdate(final int javaId,
                                                  final PlayerNametagRenderState renderState,
                                                  final boolean sharedFlagsChanged,
                                                  final boolean visibilityChanged) {
        final List<EntityData> data = new ArrayList<>(2);
        if (sharedFlagsChanged) {
            data.add(new EntityData(
                    armorStandIndex(EntityDataFields.SHARED_FLAGS),
                    VersionedTypes.V26_1.entityDataTypes().byteType,
                    renderState.sharedFlags));
        }
        if (visibilityChanged) {
            data.add(new EntityData(
                    armorStandIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                    VersionedTypes.V26_1.entityDataTypes().booleanType,
                    renderState.nameVisible));
        }
        if (data.isEmpty()) return;

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void sendArmorStandScale(final int javaId, final double scale) {
        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, this.user());
        updateAttributes.write(Types.VAR_INT, javaId); // entity id
        updateAttributes.write(Types.VAR_INT, 1); // attribute count
        updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.SCALE)); // attribute id
        updateAttributes.write(Types.DOUBLE, scale); // base value
        updateAttributes.write(Types.VAR_INT, 0); // modifier count
        updateAttributes.send(BedrockProtocol.class);
    }

    private void sendArmorStandNameUpdate(final int javaId, final String text) {
        final List<EntityData> data = new ArrayList<>();
        data.add(new EntityData(
                armorStandIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                TextUtil.stringToNbt(text)));
        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    /**
     * Sends a corrective team update for a player entity, setting the team prefix
     * to the specified bottom line text. PlayerEntity already sends the bottom line
     * for Java team prefixes; this packet keeps the prefix in sync if a later
     * metadata batch rewrites the team.
     */
    private void sendPlayerTeamPrefix(final PlayerEntity entity, final String bottomLine) {
        final boolean hasVisibleName = !TextUtil.stripFormatting(bottomLine).isEmpty();
        final PacketWrapper setPlayerTeam = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user());
        setPlayerTeam.write(Types.STRING, "vb_" + entity.javaId()); // team name
        setPlayerTeam.write(Types.BYTE, (byte) PlayerTeamMethod.CHANGE.ordinal()); // mode
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("vb_" + entity.javaId())); // display name
        setPlayerTeam.write(Types.BYTE, (byte) 3); // flags
        setPlayerTeam.write(Types.VAR_INT, (hasVisibleName ? TeamVisibility.ALWAYS : TeamVisibility.NEVER).ordinal()); // name tag visibility
        setPlayerTeam.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal()); // collision rule
        setPlayerTeam.write(Types.VAR_INT, TextFormatting.RESET.getOrdinal()); // color
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt(hasVisibleName ? bottomLine : "")); // prefix
        setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // suffix
        setPlayerTeam.send(BedrockProtocol.class);
    }

    // ---- Shared methods ----

    /**
     * Updates this feature's virtual passengers without overwriting real Bedrock riding passengers.
     */
    private void sendSetPassengers(final int vehicleJavaId, final int... passengerJavaIds) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.setVirtualPassengers(PASSENGER_SOURCE, vehicleJavaId, passengerJavaIds);
        }
    }

    // ---- Utilities ----

    private static String getEntityName(final Entity entity) {
        final EntityData nameData = entity.entityData().get(ActorDataIDs.NAME);
        final EntityData nameRawData = entity.entityData().get(ActorDataIDs.NAME_RAW_TEXT);
        final String name = nameData != null ? (String) nameData.getValue() : null;
        final String nameRaw = nameRawData != null ? (String) nameRawData.getValue() : null;
        return TextUtil.nametagValue(name, nameRaw);
    }

    private static boolean isMultilineName(final String name) {
        return name != null && name.contains("\n");
    }

    private static boolean isNametagAlwaysShown(final Entity entity) {
        final EntityData alwaysShowData = entity.entityData().get(ActorDataIDs.NAMETAG_ALWAYS_SHOW);
        if (alwaysShowData != null) {
            return TextUtil.nametagAlwaysShown((Number) alwaysShowData.getValue());
        }
        // Default: true for entities with a name (Bedrock default behavior)
        return true;
    }

    private PlayerNametagRenderState resolvePlayerRenderState(final PlayerEntity entity) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
        final boolean viewerSpectator = clientPlayer != null && GameTypeRewriter.isMotSpectator(clientPlayer.gameType());
        final double distanceSquared = squaredDistance(clientPlayer, entity);
        return resolvePlayerRenderState(
                entity.hasEntityFlag(ActorFlags.SNEAKING),
                entity.hasEntityFlag(ActorFlags.INVISIBLE),
                viewerSpectator,
                distanceSquared);
    }

    static PlayerNametagRenderState resolvePlayerRenderState(final boolean hostSneaking,
                                                              final boolean hostInvisible,
                                                              final boolean viewerSpectator,
                                                              final double distanceSquared) {
        final boolean visible = (!hostInvisible || viewerSpectator)
                && (!hostSneaking || distanceSquared <= SNEAKING_NAMETAG_DISTANCE_SQUARED);
        if (hostSneaking) {
            return visible ? PlayerNametagRenderState.VISIBLE_SNEAKING : PlayerNametagRenderState.HIDDEN_SNEAKING;
        }
        return visible ? PlayerNametagRenderState.VISIBLE_NORMAL : PlayerNametagRenderState.HIDDEN_NORMAL;
    }

    private static double squaredDistance(final Entity first, final Entity second) {
        if (first == null || second == null || first.position() == null || second.position() == null) {
            return Double.POSITIVE_INFINITY;
        }
        final double x = first.position().x() - second.position().x();
        final double y = first.position().y() - second.position().y();
        final double z = first.position().z() - second.position().z();
        return x * x + y * y + z * z;
    }

    private static float getEffectiveScale(final Entity entity) {
        final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
        if (scaleData != null) {
            final float scale = ((Number) scaleData.getValue()).floatValue();
            if (scale > 0f) return scale;
        }
        return 1.0f;
    }

    /**
     * Calculates the TRANSLATION Y offset from passenger attachment point to nametag position.
     * Used by the TEXT_DISPLAY strategy.
     * <p>
     * Java LivingEntity passenger attachment Y ≈ (entityHeight - 0.0625) * scale.
     * Desired nametag Y ≈ entityHeight * scale + 0.25.
     * Difference = 0.0625 * scale + 0.25 (height cancels out).
     */
    private static float getTranslationY(final float scale) {
        return PASSENGER_OFFSET * scale + 0.25f;
    }

    /**
     * Calculates the minecraft:scale attribute value for an armor stand at the given line index.
     * Used by the armor stand strategy.
     * <p>
     * In Minecraft 1.21.x, both PASSENGER and NAME_TAG attachment points use AT_HEIGHT,
     * meaning they are both at Y = entityHeight * scale. Since the armor stand's position
     * as a passenger equals the vehicle's PASSENGER attachment point, and the nametag renders
     * at the armor stand's NAME_TAG attachment point + 0.5:
     * <pre>
     * asNametagY = passengerY + ARMOR_STAND_BASE_HEIGHT * asScale + 0.5
     * playerNametagY = playerY + playerHeight * playerScale + 0.5
     * passengerY = playerY + playerHeight * playerScale  (PASSENGER = AT_HEIGHT)
     * gap = asNametagY - playerNametagY = ARMOR_STAND_BASE_HEIGHT * asScale
     * </pre>
     * Solving for asScale: {@code asScale = LINE_HEIGHT * lineIndex / ARMOR_STAND_BASE_HEIGHT}.
     * The formula is independent of host entity scale, height, or pose.
     *
     * @param lineIndex 1-based index (1 = first line above the player's team nametag)
     */
    private static double getArmorStandScale(final int lineIndex) {
        return (LINE_HEIGHT * lineIndex) / ARMOR_STAND_BASE_HEIGHT;
    }

    private static int textDisplayIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + fieldName);
        }
        return index;
    }

    private static int armorStandIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.ARMOR_STAND).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown ARMOR_STAND entity data field: " + fieldName);
        }
        return index;
    }

    // ---- Inner classes ----

    private static abstract class NametagDisplayInfo {
        final long entityUniqueId;
        final int hostJavaId;
        String lastNameText;
        float lastScale;

        NametagDisplayInfo(final long entityUniqueId, final int hostJavaId, final String nameText) {
            this.entityUniqueId = entityUniqueId;
            this.hostJavaId = hostJavaId;
            this.lastNameText = nameText;
        }

        abstract int[] getVirtualEntityIds();
    }

    private static class TextDisplayInfo extends NametagDisplayInfo {
        final int textDisplayJavaId;
        final UUID textDisplayUuid;

        TextDisplayInfo(final int textDisplayJavaId, final UUID textDisplayUuid,
                        final long entityUniqueId, final int hostJavaId, final String nameText) {
            super(entityUniqueId, hostJavaId, nameText);
            this.textDisplayJavaId = textDisplayJavaId;
            this.textDisplayUuid = textDisplayUuid;
        }

        @Override
        int[] getVirtualEntityIds() {
            return new int[]{textDisplayJavaId};
        }
    }

    private static class ArmorStandInfo extends NametagDisplayInfo {
        final List<ArmorStandLine> lines = new ArrayList<>();
        PlayerNametagRenderState lastRenderState;

        ArmorStandInfo(final long entityUniqueId, final int hostJavaId, final String nameText) {
            super(entityUniqueId, hostJavaId, nameText);
        }

        @Override
        int[] getVirtualEntityIds() {
            return lines.stream().mapToInt(l -> l.javaId).toArray();
        }
    }

    enum PlayerNametagRenderState {
        VISIBLE_NORMAL((byte) 0x20, true),
        VISIBLE_SNEAKING((byte) 0x22, true),
        HIDDEN_NORMAL((byte) 0x20, false),
        HIDDEN_SNEAKING((byte) 0x22, false);

        final byte sharedFlags;
        final boolean nameVisible;

        PlayerNametagRenderState(final byte sharedFlags, final boolean nameVisible) {
            this.sharedFlags = sharedFlags;
            this.nameVisible = nameVisible;
        }
    }

    private static class ArmorStandLine {
        final int javaId;
        final UUID uuid;
        String text;

        ArmorStandLine(final int javaId, final UUID uuid, final String text) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.text = text;
        }
    }

}
