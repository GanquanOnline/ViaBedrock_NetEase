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
package net.raphimc.viabedrock.experimental.model.animation;

import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * ViaBedrock 的服务端 IBoneModel。
 * 由 BedrockGeometryModel 的骨骼层级构建。
 * 为 Display Entity 更新提供世界变换计算。
 */
public class SimpleBoneModel implements IBoneModel {

    private final Map<String, IBoneTarget> boneIndex = new LinkedHashMap<>();
    private final List<IBoneTarget> allBones = new ArrayList<>();
    // 按拓扑顺序排列的骨骼（父骨骼在子骨骼之前），用于世界变换计算
    private final List<SimpleBone> topoOrder = new ArrayList<>();

    public SimpleBoneModel(BedrockGeometryModel geometry) {
        final Map<String, SimpleBone> bonesByName = new LinkedHashMap<>();

        // 第一遍：创建所有骨骼
        for (Parent parent : geometry.getParents()) {
            final String name = parent.getName();
            final Vector3f pivot = new Vector3f(
                    parent.getPivot().getX(),
                    parent.getPivot().getY(),
                    parent.getPivot().getZ());
            final Vector3f rotation = new Vector3f(
                    parent.getRotation().getX(),
                    parent.getRotation().getY(),
                    parent.getRotation().getZ());
            final boolean hasCubes = !parent.getCubes().isEmpty();

            final SimpleBone bone = new SimpleBone(name, pivot, rotation, hasCubes);
            bonesByName.put(name.toLowerCase(Locale.ROOT), bone);
            allBones.add(bone);
            boneIndex.put(name.toLowerCase(Locale.ROOT), bone);
        }

        // 第二遍：建立父子层级关系
        for (Parent parent : geometry.getParents()) {
            if (parent.getParent() != null && !parent.getParent().isEmpty()) {
                final SimpleBone child = bonesByName.get(parent.getName().toLowerCase(Locale.ROOT));
                final SimpleBone parentBone = bonesByName.get(parent.getParent().toLowerCase(Locale.ROOT));
                if (child != null && parentBone != null) {
                    child.setParentBone(parentBone);
                    parentBone.getChildren().put(child.getName().toLowerCase(Locale.ROOT), child);
                }
            }
        }

        // 构建拓扑顺序（父骨骼在子骨骼之前）
        final Set<String> visited = new HashSet<>();
        for (SimpleBone bone : bonesByName.values()) {
            topoSort(bone, bonesByName, visited);
        }
    }

    private void topoSort(SimpleBone bone, Map<String, SimpleBone> allBones, Set<String> visited) {
        final String key = bone.getName().toLowerCase(Locale.ROOT);
        if (visited.contains(key)) return;

        // 确保父骨骼先被访问
        if (bone.getParentBone() != null) {
            topoSort(bone.getParentBone(), allBones, visited);
        }

        visited.add(key);
        topoOrder.add(bone);
    }

    /** 获取骨骼在基岩模型单位下的静止枢轴点。 */
    public Vector3f getRestPivot(final String boneName) {
        final IBoneTarget bone = boneIndex.get(boneName.toLowerCase(Locale.ROOT));
        if (bone instanceof SimpleBone simpleBone) {
            return simpleBone.getPivot();
        }
        return new Vector3f();
    }

    @Override
    public Map<String, IBoneTarget> getBoneIndex() {
        return boneIndex;
    }

    @Override
    public Iterable<IBoneTarget> getAllBones() {
        return allBones;
    }

    @Override
    public void resetAllBones() {
        for (IBoneTarget bone : allBones) {
            bone.resetToDefaultPose();
        }
    }

    /**
     * 计算所有带立方体的骨骼的世界变换。
     * 必须在动画引擎把旋转/偏移/缩放写入骨骼之后调用。
     * <p>
     * 基岩骨骼变换：translate(pivot + offset) * rotateZYX(rotation) * translate(-pivot)
     * 世界变换 = parent.worldMatrix * bone.localMatrix
     *
     * @return 带立方体的骨骼的名称（小写）到世界变换的映射
     */
    public Map<String, WorldTransform> collectWorldTransforms() {
        final Map<String, Matrix4f> worldMatrices = new HashMap<>();
        final Map<String, WorldTransform> result = new LinkedHashMap<>();

        for (SimpleBone bone : topoOrder) {
            final Matrix4f localMatrix = computeLocalMatrix(bone);
            final Matrix4f worldMatrix;

            if (bone.getParentBone() != null) {
                final Matrix4f parentWorld = worldMatrices.get(
                        bone.getParentBone().getName().toLowerCase(Locale.ROOT));
                worldMatrix = new Matrix4f(parentWorld).mul(localMatrix);
            } else {
                worldMatrix = localMatrix;
            }

            worldMatrices.put(bone.getName().toLowerCase(Locale.ROOT), worldMatrix);

            if (bone.hasCubes()) {
                // 通过世界矩阵变换骨骼枢轴点，得到世界坐标下的枢轴位置
                final Vector3f worldPivot = new Vector3f(bone.getPivot());
                worldMatrix.transformPosition(worldPivot);

                // 从世界矩阵中提取旋转四元数
                final Quaternionf worldRotation = new Quaternionf();
                worldMatrix.getNormalizedRotation(worldRotation);

                // 提取缩放
                final Vector3f worldScale = new Vector3f();
                worldMatrix.getScale(worldScale);

                result.put(bone.getName().toLowerCase(Locale.ROOT),
                        new WorldTransform(worldPivot, worldRotation, worldScale));
            }
        }

        return result;
    }

    private static Matrix4f computeLocalMatrix(SimpleBone bone) {
        final Vector3f pivot = bone.getPivot();
        final Vector3f offset = bone.getOffset();
        final Vector3f rot = bone.getRotation();

        // 基岩骨骼变换：T(pivot + offset) * R(ZYX) * S * T(-pivot)
        // 缩放必须在取消枢轴的平移之前应用，
        // 这样缩放才是相对骨骼枢轴点而非原点进行的。
        return new Matrix4f()
                .translate(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z)
                .rotateZYX(
                        (float) Math.toRadians(rot.z),
                        (float) Math.toRadians(rot.y),
                        (float) Math.toRadians(rot.x))
                .scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ())
                .translate(-pivot.x, -pivot.y, -pivot.z);
    }

    /**
     * 单个骨骼的世界变换，供 Display Entity 元数据更新使用。
     *
     * @param position 骨骼枢轴点的世界坐标（基岩模型单位，1/16 方块）
     *
     * @param rotation 世界旋转，以四元数表示
     *
     * @param scale    世界缩放
     */
    public record WorldTransform(Vector3f position, Quaternionf rotation, Vector3f scale) {
    }

}
