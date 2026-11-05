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

import org.cube.converter.data.bedrock.BedrockAttachableData;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.element.PolyMesh;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DefinitionImmutability {

    private DefinitionImmutability() {
    }

    static <K, V> Map<K, V> map(final Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static BedrockEntityData entityData(final BedrockEntityData source) {
        return new BedrockEntityData(
                source.getIdentifier(),
                scripts(source.getScripts()),
                List.copyOf(source.getControllers()),
                map(source.getMaterials()),
                map(source.getAnimations()),
                map(source.getTextures()),
                map(source.getGeometries()),
                map(source.getParticleEffects())
        );
    }

    static BedrockAttachableData attachableData(final BedrockAttachableData source) {
        return new BedrockAttachableData(
                source.getIdentifier(),
                scripts(source.getScripts()),
                List.copyOf(source.getControllers()),
                map(source.getMaterials()),
                map(source.getAnimations()),
                map(source.getTextures()),
                map(source.getGeometries()),
                map(source.getParticleEffects())
        );
    }

    private static BedrockEntityData.Scripts scripts(final BedrockEntityData.Scripts source) {
        return new BedrockEntityData.Scripts(
                List.copyOf(source.initialize()),
                List.copyOf(source.pre_animation()),
                source.scale(),
                List.copyOf(source.animates())
        );
    }

    static BedrockRenderController renderController(final BedrockRenderController source) {
        return new BedrockRenderController(
                source.identifier(),
                map(source.materialsMap()),
                source.geometryExpression(),
                List.copyOf(source.textureExpressions()),
                arrays(source.materials()),
                arrays(source.textures()),
                arrays(source.geometries()),
                map(source.partVisibility()),
                source.ignoreLighting(),
                source.lightColorMultiplier()
        );
    }

    private static List<BedrockRenderController.Array> arrays(final List<BedrockRenderController.Array> source) {
        final List<BedrockRenderController.Array> copy = new ArrayList<>(source.size());
        for (BedrockRenderController.Array array : source) {
            copy.add(new BedrockRenderController.Array(array.name(), List.copyOf(array.values())));
        }
        return List.copyOf(copy);
    }

    static BedrockGeometryModel model(final BedrockGeometryModel source) {
        // CubeConverter 的最终模型类型暴露可变的父级，因此分离模型/骨骼图
        // 并把它保存在不可变的定义映射之后。运行时使用方只读取或克隆它。
        final BedrockGeometryModel copy = new BedrockGeometryModel(
                source.getIdentifier(), source.getTextureSize().clone());
        for (Parent parent : source.getParents()) {
            final Parent parentCopy = parent.clone();
            if (parent.getPolyMesh() != null) {
                parentCopy.setPolyMesh(polyMesh(parent.getPolyMesh()));
            }
            copy.getParents().add(parentCopy);
        }
        return copy;
    }

    private static PolyMesh polyMesh(final PolyMesh source) {
        return new PolyMesh(
                source.isNormalizedUvs(),
                copy(source.getPositions()),
                copy(source.getNormals()),
                copy(source.getUvs()),
                copy(source.getPolys())
        );
    }

    private static float[][] copy(final float[][] source) {
        final float[][] copy = new float[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static int[][][] copy(final int[][][] source) {
        final int[][][] copy = new int[source.length][][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = new int[source[i].length][];
            for (int j = 0; j < source[i].length; j++) {
                copy[i][j] = source[i][j].clone();
            }
        }
        return copy;
    }

}
