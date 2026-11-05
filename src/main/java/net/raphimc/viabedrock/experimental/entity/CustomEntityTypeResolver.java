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
package net.raphimc.viabedrock.experimental.entity;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.libs.gson.Gson;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.MappingLoadPhase;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * 从 config/bedrock-loader/custom_entity_type_ids.json 加载自定义实体类型 ID 映射，
 * 并在处理 ADD_ENTITY 时解析它们。
 */
public class CustomEntityTypeResolver implements FeatureModule {

    private final Map<String, Integer> customEntityTypeIds = new HashMap<>();
    private final Map<String, EntityTypes1_21_11> customEntityTypeFallbacks = new HashMap<>();

    @Override
    public void onMappingsLoad(final BedrockMappingData data, final MappingLoadPhase phase) {
        if (phase == MappingLoadPhase.AFTER_ENTITY_MAPPINGS) {
            loadCustomEntityTypeIds(data);
        }
    }

    @Override
    public Entity resolveEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type) {
        final Integer customJavaTypeId = this.customEntityTypeIds.get(type);
        if (customJavaTypeId != null) {
            final EntityTypes1_21_11 fallbackType = this.customEntityTypeFallbacks.getOrDefault(type, EntityTypes1_21_11.PIG);
            return user.get(EntityTracker.class).addEntity(uniqueId, runtimeId, type, fallbackType, customJavaTypeId);
        }
        return null;
    }

    private void loadCustomEntityTypeIds(final BedrockMappingData data) {
        final File file = new File(System.getProperty("user.dir"), "config/bedrock-loader/custom_entity_type_ids.json");
        if (!file.exists()) return;

        try {
            final JsonObject root = new Gson().fromJson(new FileReader(file), JsonObject.class);
            final JsonObject mappings = root.getAsJsonObject("mappings");

            int loadedCount = 0;
            for (final Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                final String bedrockType = Key.namespaced(entry.getKey());
                final JsonObject mapping = entry.getValue().getAsJsonObject();
                final int javaTypeId = mapping.get("java_type_id").getAsInt();

                this.customEntityTypeIds.put(bedrockType, javaTypeId);

                if (mapping.has("fallback_type")) {
                    final String fallbackTypeStr = mapping.get("fallback_type").getAsString();
                    final EntityTypes1_21_11 fallbackType = data.getBedrockToJavaEntities().get(Key.namespaced(fallbackTypeStr));
                    if (fallbackType != null) {
                        this.customEntityTypeFallbacks.put(bedrockType, fallbackType);
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                "Unknown fallback entity type: " + fallbackTypeStr + " for custom entity: " + bedrockType);
                    }
                }
                loadedCount++;
            }

            ViaBedrock.getPlatform().getLogger().info("Loaded " + loadedCount + " custom entity type ID mappings");
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load custom entity type ID mappings", e);
        }
    }

}
