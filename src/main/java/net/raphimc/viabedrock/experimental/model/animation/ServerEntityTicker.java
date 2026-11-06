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

import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.mocha.LayeredScope;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.definitions.AnimationControllerDefinitions;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.easecation.bedrockmotion.render.RenderControllerEvaluator;
import net.raphimc.viabedrock.ViaBedrock;
import org.cube.converter.data.bedrock.BedrockEntityData;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.binding.JavaObjectBinding;
import team.unnamed.mocha.runtime.standard.MochaMath;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * 单个自定义实体的服务端动画管理器。
 * 实现 AnimationEventListener，以接收 BedrockMotion 的 Animator 回调。
 * <p>
 * 生命周期：CustomEntity 生成时创建，消失时销毁。
 * tick() 经 EntityTracker 由 CustomEntity.tick() 调用。
 */
public class ServerEntityTicker implements AnimationEventListener {

    private static final Scope BASE_SCOPE = Scope.create();

    static {
        // noinspection UnstableApiUsage
        BASE_SCOPE.set("math", JavaObjectBinding.of(MochaMath.class, null, new MochaMath()));
        BASE_SCOPE.readOnly(true);
    }

    private final BedrockEntityData entityData;
    private final SimpleBoneModel boneModel;
    private final PackManager packManager;

    private final Scope entityScope;
    private final List<Animator> animators = new ArrayList<>();
    private final List<AnimationControllerInstance> controllerInstances = new ArrayList<>();

    private final Map<String, String> inverseGeometryMap = new HashMap<>();
    private final Map<String, String> inverseTextureMap = new HashMap<>();

    private int age;
    private List<RenderControllerEvaluator.EvaluatedModel> currentModels = List.of();

    public ServerEntityTicker(BedrockEntityData entityData, SimpleBoneModel boneModel, PackManager packManager) {
        this.entityData = entityData;
        this.boneModel = boneModel;
        this.packManager = packManager;

        // 构建实体作用域（与 ViaBedrock 中 CustomEntity 的写法相同）
        this.entityScope = BASE_SCOPE.copy();

        final MutableObjectBinding variableBinding = new MutableObjectBinding();
        this.entityScope.set("variable", variableBinding);
        this.entityScope.set("v", variableBinding);

        // 从实体脚本初始化变量
        try {
            for (String initExpression : entityData.getScripts().initialize()) {
                MoLangEngine.eval(this.entityScope, initExpression);
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize server entity variables", e);
        }

        // 几何模型绑定
        final MutableObjectBinding geometryBinding = new MutableObjectBinding();
        for (Map.Entry<String, String> entry : entityData.getGeometries().entrySet()) {
            geometryBinding.set(entry.getKey(), Value.of(entry.getValue()));
            this.inverseGeometryMap.putIfAbsent(entry.getValue(), entry.getKey());
        }
        geometryBinding.block();
        this.entityScope.set("geometry", geometryBinding);

        // 纹理绑定
        final MutableObjectBinding textureBinding = new MutableObjectBinding();
        for (Map.Entry<String, String> entry : entityData.getTextures().entrySet()) {
            textureBinding.set(entry.getKey(), Value.of(entry.getValue()));
            this.inverseTextureMap.putIfAbsent(entry.getValue(), entry.getKey());
        }
        textureBinding.block();
        this.entityScope.set("texture", textureBinding);

        // 材质绑定
        final MutableObjectBinding materialBinding = new MutableObjectBinding();
        materialBinding.block();
        this.entityScope.set("material", materialBinding);

        this.entityScope.readOnly(true);

        // 创建动画控制器
        initAnimationControllers();

        // 为实体脚本中列出的动画创建独立动画器
        initAnimators();
    }

    private void initAnimationControllers() {
        final AnimationDefinitions animDefs = packManager.getAnimationDefinitions();
        final Map<String, String> entityAnimations = entityData.getAnimations();

        final AnimationControllerDefinitions controllerDefs = packManager.getAnimationControllerDefinitions();

        for (Map.Entry<String, String> entry : entityAnimations.entrySet()) {
            final String animId = entry.getValue();
            // 检查这是否是动画控制器
            final AnimationController controller = controllerDefs.getControllers().get(animId);
            if (controller != null) {
                final AnimationControllerInstance instance = new AnimationControllerInstance(
                        controller, entityAnimations, animDefs, controllerDefs, this);
                controllerInstances.add(instance);
            }
        }
    }

    private void initAnimators() {
        final AnimationDefinitions animDefs = packManager.getAnimationDefinitions();

        // 来自实体脚本的动画（animate 列表）
        for (BedrockEntityData.Scripts.Animate animateEntry : entityData.getScripts().animates()) {
            final String shortName = animateEntry.name();
            final String animId = entityData.getAnimations().get(shortName);
            if (animId == null) continue;

            // 如果是动画控制器则跳过（已在上面处理）
            if (packManager.getAnimationControllerDefinitions().getControllers().containsKey(animId)) {
                continue;
            }

            final AnimationDefinitions.AnimationData animData = animDefs.getAnimations().get(animId);
            if (animData == null) continue;

            final Animator animator = new Animator(this, animData);
            animators.add(animator);
        }
    }

    /**
     * 每刻由 CustomEntity.tick() 调用。
     * 评估动画控制器、运行动画器，返回世界变换。
     *
     * @param queryBindings 额外的查询绑定（变体、标志位等）
     *
     * @return 所有带立方体的骨骼的世界变换；没有动画激活时返回 null
     */
    public Map<String, SimpleBoneModel.WorldTransform> tick(MutableObjectBinding queryBindings) {
        this.age++;

        // 用查询绑定构建帧作用域（LayeredScope 避免昂贵的深拷贝）
        final LayeredScope frameScope = new LayeredScope(entityScope);
        queryBindings.set("life_time", Value.of(this.age / 20.0f));
        queryBindings.block();
        frameScope.set("query", queryBindings);
        frameScope.set("q", queryBindings);

        // 驱动动画控制器（评估转场、更新混合权重）
        for (AnimationControllerInstance ci : controllerInstances) {
            ci.setBaseScope(frameScope);
            ci.tick(frameScope);
        }

        // 为独立动画器设置基础作用域
        for (Animator animator : animators) {
            animator.setBaseScope(frameScope);
        }

        // 重置骨骼，然后应用所有动画
        // 顺序：先独立动画器，再控制器（与 VBU 客户端侧顺序一致）
        boneModel.resetAllBones();

        for (Animator animator : animators) {
            try {
                animator.animate(boneModel);
            } catch (IOException e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Animation error", e);
            }
        }

        for (AnimationControllerInstance ci : controllerInstances) {
            ci.animate(boneModel);
        }

        // 收集已动画骨骼的世界变换
        return boneModel.collectWorldTransforms();
    }

    /**
     * 评估渲染控制器，确定当前的几何模型/纹理/材质组合。
     *
     * @param queryBindings 含变体、标志位等信息的查询绑定
     *
     * @return 模型发生变化时返回 true
     */
    public boolean evaluateRenderControllers(MutableObjectBinding queryBindings) {
        final LayeredScope scope = new LayeredScope(entityScope);
        queryBindings.block();
        scope.set("query", queryBindings);
        scope.set("q", queryBindings);

        final List<RenderControllerEvaluator.EvaluatedModel> newModels =
                RenderControllerEvaluator.evaluate(
                        entityData, scope,
                        packManager.getRenderControllerDefinitions(),
                        inverseGeometryMap, inverseTextureMap);

        if (!newModels.isEmpty() && !newModels.equals(currentModels)) {
            this.currentModels = newModels;
            return true;
        }
        return false;
    }

    public List<RenderControllerEvaluator.EvaluatedModel> getCurrentModels() {
        return currentModels;
    }

    public SimpleBoneModel getBoneModel() {
        return boneModel;
    }

    // --- AnimationEventListener ---

    @Override
    public void onTimelineEvent(List<String> expressions) {
        // 服务端：评估时间轴表达式（可能更新变量）
        for (String expr : expressions) {
            try {
                MoLangEngine.eval(entityScope, expr);
            } catch (Throwable e) {
                // 服务端静默忽略时间轴求值失败
            }
        }
    }

    @Override
    public Scope getEntityScope() {
        return entityScope;
    }

}
