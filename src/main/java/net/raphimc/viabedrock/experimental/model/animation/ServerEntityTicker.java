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
 * Server-side animation manager for a single custom entity.
 * Implements AnimationEventListener to receive callbacks from BedrockMotion's Animator.
 * <p>
 * Lifecycle: created when CustomEntity spawns, destroyed when it despawns.
 * tick() is called from CustomEntity.tick() via EntityTracker.
 */
public class ServerEntityTicker implements AnimationEventListener {

    private static final Scope BASE_SCOPE = Scope.create();

    static {
        //noinspection UnstableApiUsage
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

        // Build entity scope (same pattern as CustomEntity in ViaBedrock)
        this.entityScope = BASE_SCOPE.copy();

        final MutableObjectBinding variableBinding = new MutableObjectBinding();
        this.entityScope.set("variable", variableBinding);
        this.entityScope.set("v", variableBinding);

        // Initialize variables from entity scripts
        try {
            for (String initExpression : entityData.getScripts().initialize()) {
                MoLangEngine.eval(this.entityScope, initExpression);
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize server entity variables", e);
        }

        // Geometry bindings
        final MutableObjectBinding geometryBinding = new MutableObjectBinding();
        for (Map.Entry<String, String> entry : entityData.getGeometries().entrySet()) {
            geometryBinding.set(entry.getKey(), Value.of(entry.getValue()));
            this.inverseGeometryMap.putIfAbsent(entry.getValue(), entry.getKey());
        }
        geometryBinding.block();
        this.entityScope.set("geometry", geometryBinding);

        // Texture bindings
        final MutableObjectBinding textureBinding = new MutableObjectBinding();
        for (Map.Entry<String, String> entry : entityData.getTextures().entrySet()) {
            textureBinding.set(entry.getKey(), Value.of(entry.getValue()));
            this.inverseTextureMap.putIfAbsent(entry.getValue(), entry.getKey());
        }
        textureBinding.block();
        this.entityScope.set("texture", textureBinding);

        // Material bindings
        final MutableObjectBinding materialBinding = new MutableObjectBinding();
        materialBinding.block();
        this.entityScope.set("material", materialBinding);

        this.entityScope.readOnly(true);

        // Create animation controllers
        initAnimationControllers();

        // Create standalone animators for animations listed in entity scripts
        initAnimators();
    }

    private void initAnimationControllers() {
        final AnimationDefinitions animDefs = packManager.getAnimationDefinitions();
        final Map<String, String> entityAnimations = entityData.getAnimations();

        for (Map.Entry<String, String> entry : entityAnimations.entrySet()) {
            final String animId = entry.getValue();
            // Check if this is an animation controller
            final AnimationController controller = packManager.getAnimationControllerDefinitions()
                    .getControllers().get(animId);
            if (controller != null) {
                // TODO 升级 BedrockMotion 影子包后改回五参构造，把共享 controller 定义传给动画运行时。
                // 当前 mavenLocal 的 1.0.0 只有四参构造，与上游 d471d365 不兼容。
                final AnimationControllerInstance instance = new AnimationControllerInstance(
                        controller, entityAnimations, animDefs, this);
                controllerInstances.add(instance);
            }
        }
    }

    private void initAnimators() {
        final AnimationDefinitions animDefs = packManager.getAnimationDefinitions();

        // Animations from entity scripts (animate list)
        for (BedrockEntityData.Scripts.Animate animateEntry : entityData.getScripts().animates()) {
            final String shortName = animateEntry.name();
            final String animId = entityData.getAnimations().get(shortName);
            if (animId == null) continue;

            // Skip if it's an animation controller (handled above)
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
     * Called every tick from CustomEntity.tick().
     * Evaluates animation controllers, runs animators, returns world transforms.
     *
     * @param queryBindings additional query bindings (variant, flags, etc.)
     * @return world transforms for all bones with cubes, or null if no animation is active
     */
    public Map<String, SimpleBoneModel.WorldTransform> tick(MutableObjectBinding queryBindings) {
        this.age++;

        // Build frame scope with query bindings (LayeredScope avoids expensive deep copy)
        final LayeredScope frameScope = new LayeredScope(entityScope);
        queryBindings.set("life_time", Value.of(this.age / 20.0f));
        queryBindings.block();
        frameScope.set("query", queryBindings);
        frameScope.set("q", queryBindings);

        // Tick animation controllers (evaluate transitions, update blend weights)
        for (AnimationControllerInstance ci : controllerInstances) {
            ci.setBaseScope(frameScope);
            ci.tick(frameScope);
        }

        // Set base scope for standalone animators
        for (Animator animator : animators) {
            animator.setBaseScope(frameScope);
        }

        // Reset bones, then apply all animations
        // Order: standalone animators first, then controllers (matches VBU client-side order)
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

        // Collect world transforms from animated bones
        return boneModel.collectWorldTransforms();
    }

    /**
     * Evaluate render controllers to determine current geometry/texture/material combinations.
     *
     * @param queryBindings query bindings with variant, flags, etc.
     * @return true if models changed
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
        // Server-side: evaluate timeline expressions (may update variables)
        for (String expr : expressions) {
            try {
                MoLangEngine.eval(entityScope, expr);
            } catch (Throwable e) {
                // Silently ignore timeline eval failures on server
            }
        }
    }

    @Override
    public Scope getEntityScope() {
        return entityScope;
    }

}
