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
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.storage.SpectatorCameraTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class SpectatorCameraPackets {

    public static final String MESSAGE_ID_V1 = "easecation:spectator_camera_v1";
    public static final String MESSAGE_ID_V2 = "easecation:spectator_camera_v2";
    static final int PROTOCOL_VERSION = 2;
    static final int MAX_PAYLOAD_LENGTH = 16 * 1024;

    private SpectatorCameraPackets() {
    }

    static void handleLegacy(final PacketWrapper wrapper, final String payload) {
        if (wrapper.user().getProtocolInfo().getServerState() != State.PLAY) return;

        final LegacyMessage message;
        try {
            message = parseLegacy(payload);
        } catch (RuntimeException exception) {
            logInvalidPayload(wrapper);
            return;
        }

        final SpectatorCameraTracker tracker = wrapper.user().get(SpectatorCameraTracker.class);
        switch (message.action()) {
            case ATTACH -> tracker.attachLegacy(message.targetRuntimeId());
            case DETACH -> tracker.detachLegacy();
            case DETACH_REQUEST -> {
                // 该动作仅在代理→服务端方向有效。
            }
        }
    }

    static void handle(final PacketWrapper wrapper, final String payload) {
        if (wrapper.user().getProtocolInfo().getServerState() != State.PLAY) return;

        final Message message;
        try {
            message = parse(payload);
        } catch (RuntimeException exception) {
            logInvalidPayload(wrapper);
            return;
        }

        final SpectatorCameraTracker tracker = wrapper.user().get(SpectatorCameraTracker.class);
        switch (message.action()) {
            case BEGIN_SESSION -> {
                tracker.beginSession(message.sessionId(), message.generation(), message.targets(), message.teams());
                tracker.confirmSession();
            }
            case REPLACE_TARGETS -> tracker.replaceTargets(
                    message.sessionId(), message.generation(), message.targets(), message.teams());
            case ATTACH -> tracker.attach(message.sessionId(), message.generation(), message.targetRuntimeId());
            case DETACH_TARGET -> tracker.detachTarget(message.sessionId(), message.generation());
            case END_SESSION -> tracker.endSession(message.sessionId());
            case SESSION_READY, DETACH_REQUEST, TARGET_REQUEST -> {
                // 这些动作仅在代理→服务端方向有效。
            }
        }
    }

    static LegacyMessage parseLegacy(final String payload) {
        final JsonObject object = parseObject(payload, 512);
        final LegacyAction action = parseEnum(LegacyAction.class, requireString(object, "action"));
        long targetRuntimeId = -1L;
        if (action == LegacyAction.ATTACH) {
            targetRuntimeId = requirePositiveLong(object, "targetRuntimeId");
        }
        return new LegacyMessage(action, targetRuntimeId);
    }

    static Message parse(final String payload) {
        final JsonObject object = parseObject(payload, MAX_PAYLOAD_LENGTH);

        final JsonElement versionElement = object.get("version");
        if (!(versionElement instanceof JsonPrimitive versionPrimitive)
                || !versionPrimitive.isNumber()
                || versionPrimitive.getAsInt() != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported spectator camera protocol version");
        }
        final Action action = parseEnum(Action.class, requireString(object, "action"));
        final UUID sessionId = UUID.fromString(requireString(object, "session"));
        long generation = -1L;
        long targetRuntimeId = -1L;
        List<Target> targets = List.of();
        List<Team> teams = List.of();

        if (action != Action.END_SESSION) {
            generation = requirePositiveLong(object, "generation");
        }
        if (action == Action.ATTACH) {
            targetRuntimeId = requirePositiveLong(object, "targetRuntimeId");
        }
        if (action == Action.BEGIN_SESSION || action == Action.REPLACE_TARGETS) {
            targets = parseTargets(object.get("targets"));
            teams = parseTeams(object.get("teams"));
        }
        return new Message(action, sessionId, generation, targetRuntimeId, targets, teams);
    }

    public static String encodeDetachRequest(final UUID sessionId, final long generation, final String reason) {
        final JsonObject object = baseRequest("detach_request", sessionId, generation);
        object.addProperty("reason", reason);
        return GsonUtil.getGson().toJson(object);
    }

    public static String encodeSessionReady(final UUID sessionId, final long generation) {
        return GsonUtil.getGson().toJson(baseRequest("session_ready", sessionId, generation));
    }

    public static String encodeLegacyDetachRequest(final String reason) {
        final JsonObject object = new JsonObject();
        object.addProperty("action", "detach_request");
        object.addProperty("reason", reason);
        return GsonUtil.getGson().toJson(object);
    }

    public static String encodeTargetRequest(final UUID sessionId, final long generation, final UUID targetId) {
        final JsonObject object = baseRequest("target_request", sessionId, generation);
        object.addProperty("target", targetId.toString());
        return GsonUtil.getGson().toJson(object);
    }

    private static JsonObject baseRequest(final String action, final UUID sessionId, final long generation) {
        final JsonObject object = new JsonObject();
        object.addProperty("version", PROTOCOL_VERSION);
        object.addProperty("action", action);
        object.addProperty("session", sessionId.toString());
        object.addProperty("generation", generation);
        return object;
    }

    private static List<Target> parseTargets(final JsonElement element) {
        if (!(element instanceof JsonArray array)) {
            throw new IllegalArgumentException("Spectator camera targets are missing");
        }
        final List<Target> targets = new ArrayList<>(array.size());
        for (JsonElement targetElement : array) {
            if (!(targetElement instanceof JsonObject targetObject)) {
                throw new IllegalArgumentException("Invalid spectator camera target");
            }
            targets.add(new Target(
                    UUID.fromString(requireString(targetObject, "uuid")),
                    requireString(targetObject, "name")
            ));
        }
        return List.copyOf(targets);
    }

    private static List<Team> parseTeams(final JsonElement element) {
        if (element == null) {
            return List.of();
        }
        if (!(element instanceof JsonArray array)) {
            throw new IllegalArgumentException("Spectator camera teams are missing");
        }
        final List<Team> teams = new ArrayList<>(array.size());
        final Set<String> keys = new HashSet<>();
        for (JsonElement teamElement : array) {
            if (!(teamElement instanceof JsonObject teamObject)) {
                throw new IllegalArgumentException("Invalid spectator camera team");
            }
            final String key = requireString(teamObject, "key");
            if (key.isBlank() || key.length() > 64 || !keys.add(key)) {
                throw new IllegalArgumentException("Invalid spectator camera team key");
            }
            final String displayName = requireString(teamObject, "name");
            if (displayName.length() > 256) {
                throw new IllegalArgumentException("Invalid spectator camera team name");
            }
            final int color = requireInteger(teamObject, "color");
            if (color < -1 || color > 15) {
                throw new IllegalArgumentException("Invalid spectator camera team color");
            }
            final JsonElement membersElement = teamObject.get("members");
            if (!(membersElement instanceof JsonArray membersArray)) {
                throw new IllegalArgumentException("Spectator camera team members are missing");
            }
            final List<UUID> members = new ArrayList<>(membersArray.size());
            final Set<UUID> uniqueMembers = new HashSet<>();
            for (JsonElement memberElement : membersArray) {
                if (!(memberElement instanceof JsonPrimitive memberPrimitive) || !memberPrimitive.isString()) {
                    throw new IllegalArgumentException("Invalid spectator camera team member");
                }
                final UUID member = UUID.fromString(memberPrimitive.getAsString());
                if (uniqueMembers.add(member)) {
                    members.add(member);
                }
            }
            teams.add(new Team(key, displayName, color, List.copyOf(members)));
        }
        return List.copyOf(teams);
    }

    private static JsonObject parseObject(final String payload, final int maxLength) {
        if (payload == null || payload.length() > maxLength) {
            throw new IllegalArgumentException("Spectator camera payload is too large");
        }
        final JsonElement root = GsonUtil.getGson().fromJson(payload, JsonElement.class);
        if (!(root instanceof JsonObject object)) {
            throw new IllegalArgumentException("Spectator camera payload must be an object");
        }
        return object;
    }

    private static <T extends Enum<T>> T parseEnum(final Class<T> type, final String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown spectator camera action", exception);
        }
    }

    private static void logInvalidPayload(final PacketWrapper wrapper) {
        final SpectatorCameraTracker tracker = wrapper.user().get(SpectatorCameraTracker.class);
        if (tracker.markInvalidPayloadLogged()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Ignoring invalid spectator camera message");
        }
    }

    private static String requireString(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException("Missing spectator camera field");
        }
        return primitive.getAsString();
    }

    private static long requirePositiveLong(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Missing spectator camera number");
        }
        final long value = Long.parseLong(primitive.getAsString());
        if (value <= 0L) {
            throw new IllegalArgumentException("Invalid spectator camera number");
        }
        return value;
    }

    private static int requireInteger(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Missing spectator camera number");
        }
        try {
            return Integer.parseInt(primitive.getAsString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid spectator camera number", exception);
        }
    }

    enum Action {
        BEGIN_SESSION,
        REPLACE_TARGETS,
        ATTACH,
        DETACH_TARGET,
        END_SESSION,
        SESSION_READY,
        DETACH_REQUEST,
        TARGET_REQUEST
    }

    enum LegacyAction {
        ATTACH,
        DETACH,
        DETACH_REQUEST
    }

    public record Target(UUID uuid, String name) {
    }

    public record Team(String key, String displayName, int color, List<UUID> members) {
    }

    record Message(
            Action action,
            UUID sessionId,
            long generation,
            long targetRuntimeId,
            List<Target> targets,
            List<Team> teams
    ) {
    }

    record LegacyMessage(LegacyAction action, long targetRuntimeId) {
    }
}
