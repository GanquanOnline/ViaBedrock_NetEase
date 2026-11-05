/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.definition;

import net.easecation.bedrockmotion.pack.ServerAnimationLayer;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.SelectedSubpackContent;
import net.raphimc.viabedrock.api.resourcepack.content.PackContentView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * 从单个资源包解析出的不可变定义。各层可按内容摘要缓存，
 * 并按自下而上的顺序折叠，而无需保留源包内容。
 */
public record ParsedPackLayer(
        ResourcePack.Key sourceKey,
        String selectedSubpack,
        TextDefinitions texts,
        BlockDefinitions blocks,
        ItemDefinitions items,
        AttachableDefinitions attachables,
        TextureDefinitions textures,
        SoundDefinitions sounds,
        ParticleDefinitions particles,
        EntityDefinitions entities,
        ModelDefinitions models,
        FogDefinitions fogs,
        BiomeDefinitions biomes,
        RenderControllerDefinitions renderControllers,
        ServerAnimationLayer serverAnimation
) {

    public ParsedPackLayer {
        Objects.requireNonNull(sourceKey, "sourceKey");
        selectedSubpack = Objects.requireNonNullElse(selectedSubpack, "");
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(attachables, "attachables");
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(particles, "particles");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(fogs, "fogs");
        Objects.requireNonNull(biomes, "biomes");
        Objects.requireNonNull(renderControllers, "renderControllers");
        Objects.requireNonNull(serverAnimation, "serverAnimation");
    }

    public static ParsedPackLayer parse(final ResourcePack pack) {
        return parse(pack, "");
    }

    /**
     * 与 BedrockMotion {@code ServerAnimationLayer.sourcePaths} 相同的三个目录：只有这些目录下的 json
     * 才是运动定义源。这里直接枚举，避免依赖尚未发布到影子包的 sourcePaths API。
     */
    private static final List<String> MOTION_SOURCE_DIRECTORIES =
            List.of("animations/", "animation_controllers/", "render_controllers/");

    /** Only these sources become retained definition graphs. Other files remain opaque pack data. */
    public static List<String> definitionSourcePaths(final PackContentView content) {
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (DefinitionSource source : DefinitionSource.values()) paths.addAll(source.files(content));
        for (String directory : MOTION_SOURCE_DIRECTORIES) paths.addAll(content.getFilesDeep(directory, ".json"));
        return List.copyOf(paths);
    }

    private static ServerAnimationLayer.ContentView motionContent(final PackContentView content) {
        return new ServerAnimationLayer.ContentView() {
            @Override public List<String> getFilesDeep(String path, String extension) {
                return content.getFilesDeep(path, extension);
            }
            @Override public String getString(String path) { return content.getString(path); }
        };
    }

    public static ParsedPackLayer parse(final ResourcePack pack, final String selectedSubpack) {
        Objects.requireNonNull(pack, "pack");
        final String subpack = Objects.requireNonNullElse(selectedSubpack, "");
        final ResourcePack effectivePack = subpack.isEmpty() ? pack
                : new ResourcePack(new SelectedSubpackContent(pack.content(), subpack));
        if (!pack.key().equals(effectivePack.key())) {
            throw new IllegalArgumentException("Selected subpack changed the resource pack manifest identity");
        }
        try {
            return effectivePack.content().withReadSession(() -> {
                final RenderControllerDefinitions renderControllers = RenderControllerDefinitions.fromPack(effectivePack);
                return new ParsedPackLayer(
                    effectivePack.key(),
                    subpack,
                    TextDefinitions.fromPack(effectivePack),
                    BlockDefinitions.fromPack(effectivePack),
                    ItemDefinitions.fromPack(effectivePack),
                    AttachableDefinitions.fromPack(effectivePack),
                    TextureDefinitions.fromPack(effectivePack),
                    SoundDefinitions.fromPack(effectivePack),
                    ParticleDefinitions.fromPack(effectivePack),
                    EntityDefinitions.fromPack(effectivePack),
                    ModelDefinitions.fromPack(effectivePack),
                    FogDefinitions.fromPack(effectivePack),
                    BiomeDefinitions.fromPack(effectivePack),
                    renderControllers,
                    ServerAnimationLayer.parseWithFrozenRenderControllers(
                            motionContent(effectivePack.content()), renderControllers.renderControllers())
                );
            });
        } finally {
            effectivePack.content().releaseTransientCaches();
        }
    }

    /**
     * 按基岩的包优先级折叠各层：后出现的层覆写先出现的层。声音的
     * 实体/方块定义按事件粒度合并，而不是替换整个实体。
     */
    public static FoldedDefinitions foldBottomToTop(final Collection<ParsedPackLayer> layersBottomToTop) {
        return foldBottomToTop(layersBottomToTop, List.of());
    }

    /**
     * 折叠普通堆叠，并在前面追加仅含模型的基层，例如 ViaBedrock 的皮肤包。
     * 模型基层中除模型以外的定义会被有意忽略。
     */
    public static FoldedDefinitions foldBottomToTop(final Collection<ParsedPackLayer> layersBottomToTop,
                                                     final Collection<ParsedPackLayer> modelBaseLayersBottomToTop) {
        final List<ParsedPackLayer> layers = List.copyOf(layersBottomToTop);
        final List<ModelDefinitions> modelLayers = new ArrayList<>();
        for (ParsedPackLayer layer : modelBaseLayersBottomToTop) {
            modelLayers.add(layer.models());
        }
        for (ParsedPackLayer layer : layers) {
            modelLayers.add(layer.models());
        }
        return new FoldedDefinitions(
                TextDefinitions.fold(layers.stream().map(ParsedPackLayer::texts).toList()),
                BlockDefinitions.fold(layers.stream().map(ParsedPackLayer::blocks).toList()),
                ItemDefinitions.fold(layers.stream().map(ParsedPackLayer::items).toList()),
                AttachableDefinitions.fold(layers.stream().map(ParsedPackLayer::attachables).toList()),
                TextureDefinitions.fold(layers.stream().map(ParsedPackLayer::textures).toList()),
                SoundDefinitions.fold(layers.stream().map(ParsedPackLayer::sounds).toList()),
                ParticleDefinitions.fold(layers.stream().map(ParsedPackLayer::particles).toList()),
                EntityDefinitions.fold(layers.stream().map(ParsedPackLayer::entities).toList()),
                ModelDefinitions.fold(modelLayers),
                FogDefinitions.fold(layers.stream().map(ParsedPackLayer::fogs).toList()),
                BiomeDefinitions.fold(layers.stream().map(ParsedPackLayer::biomes).toList()),
                RenderControllerDefinitions.fold(layers.stream().map(ParsedPackLayer::renderControllers).toList())
        );
    }

    public record FoldedDefinitions(
            TextDefinitions texts,
            BlockDefinitions blocks,
            ItemDefinitions items,
            AttachableDefinitions attachables,
            TextureDefinitions textures,
            SoundDefinitions sounds,
            ParticleDefinitions particles,
            EntityDefinitions entities,
            ModelDefinitions models,
            FogDefinitions fogs,
            BiomeDefinitions biomes,
            RenderControllerDefinitions renderControllers
    ) {
    }

}
