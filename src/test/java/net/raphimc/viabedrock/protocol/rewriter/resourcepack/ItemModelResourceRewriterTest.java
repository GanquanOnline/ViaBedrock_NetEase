/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Cube;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.java.JavaItemModel;
import org.cube.converter.util.element.Position2V;
import org.cube.converter.util.element.Position3V;
import org.cube.converter.util.element.UVMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemModelResourceRewriterTest {

    private static final String ITEM_PATH = "assets/viabedrock/items/entities/test/empty.json";
    private static final String MODEL_ROOT = "assets/viabedrock/models/entities/test/";

    @Test
    void skipsItemDefinitionsWithNoGeneratedCases() {
        final InMemoryContent content = new InMemoryContent();

        new TestRewriter().write(content, "test:empty", Map.of(), false);

        assertNull(content.getJson(ITEM_PATH));
        assertEquals(0, content.size());
    }

    @Test
    void generatedLegacyPackContainsNoEmptyCasesOrInvalidElements() {
        final InMemoryContent content = new InMemoryContent();
        final Map<String, JsonObject> models = new LinkedHashMap<>();
        models.put("invalid_rotation", geometryModel(
                vector(7, 7, 7), vector(9, 9, 9), legacyRotation(135, "x")));
        models.put("invalid_bounds", geometryModel(
                vector(37.47, 30.31, 8), vector(42, 35, 12), freeRotation(10, 20, 30)));
        models.put("malformed_rotation", geometryModel(
                vector(7, 7, 7), vector(9, 9, 9), new JsonPrimitive("invalid")));

        new TestRewriter().write(content, "test:generated", models, false);

        assertGeneratedLegacyPackIsValid(content);
        final JsonObject boundedModel = content.getJson(MODEL_ROOT + "generated/invalid_bounds.json");
        final JsonObject boundedElement = boundedModel.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertFalse(boundedElement.has("rotation"));
        assertEquals(32D, boundedElement.getAsJsonArray("from").get(0).getAsDouble());
        assertNotNull(boundedModel.getAsJsonObject("textures").get("particle"));
        final JsonObject malformedModel = content.getJson(MODEL_ROOT + "generated/malformed_rotation.json");
        assertFalse(malformedModel.getAsJsonArray("elements").get(0).getAsJsonObject().has("rotation"));
    }

    @Test
    void preparesLegacyGeometryWithCompensatingScale() {
        final Position3V size = new Position3V(4, 4, 4);
        final JavaItemModel model = new JavaItemModel("viabedrock:test", new Position2V(16, 16));
        final Parent parent = new Parent("root", new Position3V(2, 3, 4), Position3V.zero());
        final Cube cube = new Cube(
                new Position3V(-10, 2, 4), new Position3V(-40, 0, 0), size,
                Position3V.zero(), false, UVMap.fromBoxUV(size, new Float[]{0F, 0F}, false));
        parent.getCubes().put(0, cube);
        model.getParents().add(parent);
        final Position3V originalParentPivot = parent.getPivot().clone();
        final Position3V originalPosition = cube.getPosition().clone();
        final Position3V originalSize = cube.getSize().clone();
        final Position3V originalCubePivot = cube.getPivot().clone();

        ItemModelResourceRewriter.prepareModelForClient(model, false);

        assertTrue(model.getScale() > 1F);
        final JsonObject compiled = model.compile();
        final JsonObject element = compiled.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertLegacyGoogleVectorIsValid(element.getAsJsonArray("from"));
        assertLegacyGoogleVectorIsValid(element.getAsJsonArray("to"));
        assertCompensatedVector(originalParentPivot, parent.getPivot(), model.getScale());
        assertCompensatedVector(originalPosition, cube.getPosition(), model.getScale());
        assertCompensatedVector(originalSize, cube.getSize(), model.getScale());
        assertCompensatedVector(originalCubePivot, cube.getPivot(), model.getScale());
    }

    @Test
    void selectsConverterFormatForClientModelVersion() {
        assertEquals(RotationType.HACKY_POST_1_21_6,
                ItemModelResourceRewriter.rotationTypeFor(false));
        assertEquals(RotationType.POST_1_21_11,
                ItemModelResourceRewriter.rotationTypeFor(true));
        assertEquals("2", new TestRewriter().artifactFingerprint());
    }

    @Test
    void keepsModernFreeRotationOutput() {
        final InMemoryContent content = new InMemoryContent();
        final JsonObject model = geometryModel(
                vector(-20, 0, 0), vector(40, 16, 16), freeRotation(90, 45, 10));

        new TestRewriter().write(content, "test:modern", Map.of("default", model), true);

        final JsonObject output = content.getJson(MODEL_ROOT + "modern/default.json");
        final JsonObject element = output.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals(90D, element.getAsJsonObject("rotation").get("x").getAsDouble());
        assertEquals(-20D, element.getAsJsonArray("from").get(0).getAsDouble());
    }

    private static JsonObject geometryModel(final JsonArray from, final JsonArray to,
                                            final JsonElement rotation) {
        final JsonObject element = new JsonObject();
        element.add("from", from);
        element.add("to", to);
        element.add("rotation", rotation);
        final JsonObject face = new JsonObject();
        face.add("uv", vector4(0, 0, 16, 16));
        face.addProperty("texture", "#0");
        final JsonObject faces = new JsonObject();
        faces.add("down", face);
        element.add("faces", faces);

        final JsonArray elements = new JsonArray();
        elements.add(element);
        final JsonObject textures = new JsonObject();
        textures.addProperty("0", "viabedrock:test");
        final JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    private static JsonObject legacyRotation(final double angle, final String axis) {
        final JsonObject rotation = new JsonObject();
        rotation.addProperty("angle", angle);
        rotation.addProperty("axis", axis);
        rotation.add("origin", vector(8, 8, 8));
        return rotation;
    }

    private static JsonObject freeRotation(final double x, final double y, final double z) {
        final JsonObject rotation = new JsonObject();
        rotation.addProperty("x", x);
        rotation.addProperty("y", y);
        rotation.addProperty("z", z);
        rotation.add("origin", vector(8, 8, 8));
        return rotation;
    }

    private static JsonArray vector(final double x, final double y, final double z) {
        final JsonArray vector = new JsonArray();
        vector.add(x);
        vector.add(y);
        vector.add(z);
        return vector;
    }

    private static JsonArray vector4(final double x, final double y, final double z, final double w) {
        final JsonArray vector = vector(x, y, z);
        vector.add(w);
        return vector;
    }

    private static void assertGeneratedLegacyPackIsValid(final InMemoryContent content) {
        final var itemFiles = content.getFilesDeep("assets/viabedrock/items/", ".json");
        assertFalse(itemFiles.isEmpty());
        for (String itemFile : itemFiles) {
            final JsonArray cases = content.getJson(itemFile).getAsJsonObject("model").getAsJsonArray("cases");
            assertFalse(cases.isEmpty(), () -> "Empty case list in " + itemFile);
            for (JsonElement caseEntry : cases) {
                final String modelIdentifier = caseEntry.getAsJsonObject()
                        .getAsJsonObject("model").get("model").getAsString();
                final String[] parts = modelIdentifier.split(":", 2);
                assertEquals(2, parts.length, () -> "Invalid model identifier in " + itemFile);
                final String modelPath = "assets/" + parts[0] + "/models/" + parts[1] + ".json";
                assertNotNull(content.getJson(modelPath), () -> "Missing referenced model " + modelPath);
            }
        }

        final var modelFiles = content.getFilesDeep("assets/viabedrock/models/", ".json");
        assertFalse(modelFiles.isEmpty());
        for (String modelFile : modelFiles) {
            final JsonObject model = content.getJson(modelFile);
            assertNotNull(model.getAsJsonObject("textures").get("particle"),
                    () -> "Missing particle texture in " + modelFile);
            assertLegacyElementsAreValid(model);
        }
    }

    private static void assertLegacyElementsAreValid(final JsonObject model) {
        for (JsonElement entry : model.getAsJsonArray("elements")) {
            final JsonObject element = entry.getAsJsonObject();
            assertLegacyVectorIsValid(element.getAsJsonArray("from"));
            assertLegacyVectorIsValid(element.getAsJsonArray("to"));
            if (!element.has("rotation")) {
                continue;
            }
            final JsonObject rotation = element.getAsJsonObject("rotation");
            assertFalse(rotation.has("x") || rotation.has("y") || rotation.has("z"));
            assertTrue(Math.abs(rotation.get("angle").getAsDouble()) <= 45D);
            assertTrue(java.util.Set.of("x", "y", "z").contains(rotation.get("axis").getAsString()));
        }
    }

    private static void assertLegacyVectorIsValid(final JsonArray vector) {
        assertEquals(3, vector.size());
        for (JsonElement coordinate : vector) {
            final double value = coordinate.getAsDouble();
            assertTrue(Double.isFinite(value));
            assertTrue(value >= -16D && value <= 32D, () -> "Legacy coordinate out of range: " + value);
        }
    }

    private static void assertLegacyGoogleVectorIsValid(final JsonArray vector) {
        assertEquals(3, vector.size());
        for (JsonElement coordinate : vector) {
            final double value = coordinate.getAsDouble();
            assertTrue(Double.isFinite(value));
            assertTrue(value >= -16D && value <= 32D, () -> "Legacy coordinate out of range: " + value);
        }
    }

    private static void assertCompensatedVector(final Position3V original, final Position3V scaled,
                                                final float displayScale) {
        assertEquals(original.getX(), scaled.getX() * displayScale, 0.0001D);
        assertEquals(original.getY(), scaled.getY() * displayScale, 0.0001D);
        assertEquals(original.getZ(), scaled.getZ() * displayScale, 0.0001D);
    }

    private static final class TestRewriter extends ItemModelResourceRewriter {

        private TestRewriter() {
            super("entities");
        }

        private void write(final InMemoryContent content, final String name,
                           final Map<String, JsonObject> models, final boolean supportsFreeRotation) {
            this.putItemDefinition(content, name, models, supportsFreeRotation);
        }

        @Override
        public void apply(final net.raphimc.viabedrock.protocol.storage.ResourcePackStorage storage,
                          final net.raphimc.viabedrock.api.resourcepack.content.Content content) {
        }
    }

}
