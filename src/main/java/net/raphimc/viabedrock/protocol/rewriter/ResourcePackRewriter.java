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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.libs.gson.JsonObject;
import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomAttachableResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomEntityResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomItemTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomBlockTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomSoundResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.GlyphSheetResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.ItemModelResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class ResourcePackRewriter {

    public static final String BEDROCK_MOTION_PACK_MANAGER_KEY = "bedrockmotion_pack_manager";
    private static final List<Rewriter> REWRITERS = new CopyOnWriteArrayList<>();

    static {
        REWRITERS.add(new GlyphSheetResourceRewriter());
        REWRITERS.add(new CustomItemTextureResourceRewriter());
        REWRITERS.add(new CustomBlockTextureResourceRewriter());
        REWRITERS.add(new CustomAttachableResourceRewriter());
        REWRITERS.add(new CustomEntityResourceRewriter());
        REWRITERS.add(new CustomSoundResourceRewriter());
    }

    public static void registerRewriter(final Rewriter rewriter) {
        REWRITERS.add(Objects.requireNonNull(rewriter, "rewriter"));
    }

    public static Content bedrockToJava(final ResourcePackStorage resourcePackStorage) {
        return bedrockToJava(resourcePackStorage, new InMemoryContent());
    }

    public static Content bedrockToJava(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        requireMatchingRegistry(resourcePackStorage);
        for (Rewriter rewriter : REWRITERS) {
            rewriter.apply(resourcePackStorage, javaContent);
        }
        javaContent.putJson("pack.mcmeta", createPackManifest());
        return javaContent;
    }

    private static JsonObject createPackManifest() {
        final JsonObject pack = new JsonObject();
        pack.addProperty("description", "ViaBedrock Resource Pack");
        pack.addProperty("pack_format", ProtocolConstants.JAVA_PACK_VERSION);
        final JsonObject supportedFormats = new JsonObject();
        supportedFormats.addProperty("min_inclusive", 64);
        supportedFormats.addProperty("max_inclusive", ProtocolConstants.JAVA_PACK_VERSION);
        pack.add("supported_formats", supportedFormats);
        final JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /**
     * Initialize runtime data needed for custom entity rendering.
     * This must be called after setPackStack() to ensure data is available regardless of
     * whether the Java client downloads or caches the resource pack.
     */
    public static void initSharedRuntimeData(final ResourcePackStorage resourcePackStorage) {
        requireMatchingRegistry(resourcePackStorage);
        for (Rewriter rewriter : REWRITERS) {
            if (rewriter.runtimeDataScope() == RuntimeDataScope.SHARED) {
                rewriter.initRuntimeData(resourcePackStorage);
            }
        }

        if (!ViaBedrock.getConfig().shouldEnableServerEntityAnimation()) {
            return; // Server-side animation disabled, skip BedrockMotion initialization
        }
        initBedrockMotionPackManager(resourcePackStorage);
        initCustomEntityBoneData(resourcePackStorage);
    }

    /** Runs compatibility rewriters once for every connection, preserving session-local extension data. */
    public static void initSessionRuntimeData(final ResourcePackStorage resourcePackStorage) {
        requireMatchingRegistry(resourcePackStorage);
        for (Rewriter rewriter : REWRITERS) {
            if (rewriter.runtimeDataScope() == RuntimeDataScope.SESSION) {
                rewriter.initRuntimeData(resourcePackStorage);
            }
        }
    }

    /**
     * Initializes all resource-pack runtime data using the pre-shared-cache behavior.
     *
     * @deprecated Shared-cache callers should initialize shared and session data separately.
     */
    @Deprecated(forRemoval = false)
    public static void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        initSharedRuntimeData(resourcePackStorage);
        initSessionRuntimeData(resourcePackStorage);
    }

    public static String rewriterFingerprint() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // v3 keeps the manifest and embedded archive set identical when one logical pack UUID
            // occurs in multiple version layers.
            digest.update("ViaBedrock-Rewriters-v3\0".getBytes(StandardCharsets.US_ASCII));
            for (Rewriter rewriter : REWRITERS) {
                updateFingerprint(digest, rewriter.getClass().getName());
                updateFingerprint(digest, rewriter.artifactFingerprint());
                updateFingerprint(digest, rewriter.runtimeDataScope().name());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void updateFingerprint(final MessageDigest digest, final String value) {
        final byte[] bytes = Objects.requireNonNull(value, "rewriter fingerprint").getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void requireMatchingRegistry(final ResourcePackStorage resourcePackStorage) {
        if (!resourcePackStorage.getRewriterFingerprint().equals(rewriterFingerprint())) {
            throw new IllegalStateException(
                    "Resource pack rewriter registry changed after this session was created");
        }
    }

    /**
     * Populate per-bone metadata (bone names and scales) for all custom entities.
     * This data is needed by CustomEntity.spawn() to create per-bone Display Entities.
     */
    private static void initCustomEntityBoneData(final ResourcePackStorage resourcePackStorage) {
        if (resourcePackStorage.getEntities() == null || resourcePackStorage.getModels() == null) return;

        for (Map.Entry<String, EntityDefinitions.EntityDefinition> entityEntry : resourcePackStorage.getEntities().entities().entrySet()) {
            final EntityDefinitions.EntityDefinition entityDefinition = entityEntry.getValue();
            for (Map.Entry<String, String> modelEntry : entityDefinition.entityData().getGeometries().entrySet()) {
                final BedrockGeometryModel bedrockGeometry = resourcePackStorage.getModels()
                        .getEntityModel(modelEntry.getValue());
                if (bedrockGeometry == null) continue;

                for (Map.Entry<String, String> textureEntry : entityDefinition.entityData().getTextures().entrySet()) {
                    final String baseKey = entityEntry.getKey() + "_" + modelEntry.getKey() + "_" + textureEntry.getKey();

                    final List<String> boneNames = new ArrayList<>();
                    for (Parent bone : bedrockGeometry.getParents()) {
                        if (bone.getCubes().isEmpty()) continue;

                        final String boneName = bone.getName().toLowerCase();
                        try {
                            final BedrockGeometryModel perBoneGeometry = new BedrockGeometryModel(
                                    bedrockGeometry.getIdentifier() + "_" + boneName,
                                    bedrockGeometry.getTextureSize());
                            final Parent clonedBone = bone.clone();
                            clonedBone.setParent(null);
                            perBoneGeometry.getParents().add(clonedBone);

                            final String javaTexturePath = "viabedrock:item/entity/" +
                                    net.raphimc.viabedrock.api.util.StringUtil.makeIdentifierValueSafe(
                                            textureEntry.getValue().replace("textures/", ""));
                            final JavaItemModel itemModel = ItemModelResourceRewriter.prepareModelForClient(
                                    perBoneGeometry.toJavaItemModel(
                                            javaTexturePath, RotationType.HACKY_POST_1_21_6),
                                    resourcePackStorage.isSupportsFreeRotation());

                            final String boneKey = baseKey + "_" + boneName;
                            final float safeScale = Float.isFinite(itemModel.getScale()) ? itemModel.getScale() : 1.0f;
                            resourcePackStorage.putRuntimeData("ce_" + boneKey + "_scale", safeScale);
                            boneNames.add(boneName);
                        } catch (Throwable e) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                    "Failed to compute per-bone metadata for " + boneName + " in " + baseKey, e);
                        }
                    }

                    resourcePackStorage.putRuntimeData("ce_" + baseKey + "_bones", List.copyOf(boneNames));
                }
            }
        }
    }

    /**
     * Create a BedrockMotion PackManager from ViaBedrock's resource packs.
     * This PackManager provides animation/controller definitions for server-side entity animation.
     * Shared runtimes expose their typed manager directly; the compatibility path stores its manager
     * in session-local converter data for {@code ResourcePackStorage}'s legacy fallback.
     */
    static void initBedrockMotionPackManager(final ResourcePackStorage resourcePackStorage) {
        final PackManager sharedPackManager = resourcePackStorage.getBedrockMotionPackManager();
        if (sharedPackManager != null) {
            return;
        }
        if (resourcePackStorage.getRuntimeStackKey() != null) {
            throw new IllegalStateException("Shared resource pack runtime has no BedrockMotion PackManager");
        }

        try {
            final List<net.easecation.bedrockmotion.pack.content.Content> contents = new ArrayList<>();

            if (BedrockProtocol.MAPPINGS.getBedrockSkinPacks() != null) {
                final ResourcePack skinPack = BedrockProtocol.MAPPINGS.getBedrockSkinPacks().get(DataValues.VANILLA_SKIN_PACK_KEY);
                if (skinPack != null) {
                    try {
                        contents.add(new net.easecation.bedrockmotion.pack.content.Content(skinPack.content().toZip()));
                    } catch (IOException e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to convert vanilla skin pack for BedrockMotion", e);
                    }
                }
            }

            for (ResourcePack pack : resourcePackStorage.getPackStackBottomToTop()) {
                try {
                    contents.add(new net.easecation.bedrockmotion.pack.content.Content(pack.content().toZip()));
                } catch (IOException e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to convert pack for BedrockMotion", e);
                }
            }

            if (!contents.isEmpty()) {
                final PackManager packManager = new PackManager(contents, PackManager.Profile.SERVER_ANIMATION);
                resourcePackStorage.getConverterData().put(BEDROCK_MOTION_PACK_MANAGER_KEY, packManager);
                ViaBedrock.getPlatform().getLogger().info("Initialized BedrockMotion PackManager with " + contents.size() + " pack(s)");
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize BedrockMotion PackManager", e);
        }
    }

    public interface Rewriter {

        void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent);

        default void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        }

        /** Existing third-party rewriters remain per-session unless they explicitly opt into immutable sharing. */
        default RuntimeDataScope runtimeDataScope() {
            return RuntimeDataScope.SESSION;
        }

        /** Bump this value whenever this rewriter changes Java artifact output semantics. */
        default String artifactFingerprint() {
            return "1";
        }

    }

    public enum RuntimeDataScope {
        SHARED,
        SESSION
    }

}
