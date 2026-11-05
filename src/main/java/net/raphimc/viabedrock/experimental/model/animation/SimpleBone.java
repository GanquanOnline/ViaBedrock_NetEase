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

import net.easecation.bedrockmotion.model.IBoneTarget;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ViaBedrock 代理的服务端 IBoneTarget 实现。
 * 纯数据存储，没有任何 MC 客户端依赖。
 */
public class SimpleBone implements IBoneTarget {

    private final String name;
    private final Vector3f pivot;
    private final Vector3f defaultRotation;
    private final boolean hasCubes;

    // 可变动画状态（每刻由 Animator 写入）
    private final Vector3f rotation = new Vector3f();
    private final Vector3f offset = new Vector3f();
    private float scaleX = 1, scaleY = 1, scaleZ = 1;

    // 层级结构
    private SimpleBone parent;
    private final Map<String, IBoneTarget> children = new LinkedHashMap<>();

    public SimpleBone(String name, Vector3f pivot, Vector3f defaultRotation, boolean hasCubes) {
        this.name = name;
        this.pivot = new Vector3f(pivot);
        this.defaultRotation = new Vector3f(defaultRotation);
        this.hasCubes = hasCubes;
        resetToDefaultPose();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Vector3f getRotation() {
        return rotation;
    }

    @Override
    public Vector3f getOffset() {
        return offset;
    }

    @Override
    public float getScaleX() {
        return scaleX;
    }

    @Override
    public float getScaleY() {
        return scaleY;
    }

    @Override
    public float getScaleZ() {
        return scaleZ;
    }

    @Override
    public void setScale(float x, float y, float z) {
        this.scaleX = x;
        this.scaleY = y;
        this.scaleZ = z;
    }

    @Override
    public void addOffset(Vector3f off) {
        this.offset.add(off);
    }

    @Override
    public void addRotation(Vector3f rot) {
        this.rotation.add(rot);
    }

    @Override
    public void addScale(float dx, float dy, float dz) {
        this.scaleX += dx;
        this.scaleY += dy;
        this.scaleZ += dz;
    }

    @Override
    public void resetToDefaultPose() {
        this.rotation.set(defaultRotation);
        this.offset.set(0, 0, 0);
        this.scaleX = 1;
        this.scaleY = 1;
        this.scaleZ = 1;
    }

    @Override
    public Map<String, IBoneTarget> getChildren() {
        return children;
    }

    public Vector3f getPivot() {
        return pivot;
    }

    public Vector3f getDefaultRotation() {
        return defaultRotation;
    }

    public boolean hasCubes() {
        return hasCubes;
    }

    public SimpleBone getParentBone() {
        return parent;
    }

    public void setParentBone(SimpleBone parent) {
        this.parent = parent;
    }

}
