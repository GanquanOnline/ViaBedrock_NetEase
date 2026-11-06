/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 */
package net.raphimc.viabedrock.protocol.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPacketLayoutTest {

    @Test
    void netease860UsesModernPresetFieldsAndAimAssistInt() {
        assertTrue(CameraPacketLayout.usesBaseCameraOptions(true, 860));
        assertTrue(CameraPacketLayout.usesRotationSpeed(true, 860));
        assertTrue(CameraPacketLayout.usesRotationLimits(true, 860));
        assertTrue(CameraPacketLayout.usesBlockListeningRadius(true, 860));
        assertTrue(CameraPacketLayout.usesAimAssist(true, 860));
        assertTrue(CameraPacketLayout.usesControlScheme(true, 860));
        assertFalse(CameraPacketLayout.usesAlignTargetAndCameraForward(true, 860));
        assertTrue(CameraPacketLayout.usesRemoveIgnoreStartingValues(true, 860));
        assertTrue(CameraPacketLayout.usesCameraTarget(true, 860));
        assertTrue(CameraPacketLayout.usesCameraFov(true, 860));
        assertTrue(CameraPacketLayout.usesCameraSpline(true, 860));
    }

    @Test
    void official975KeepsLatestFieldsWithoutRemovedAlignOption() {
        assertTrue(CameraPacketLayout.usesBaseCameraOptions(false, 975));
        assertTrue(CameraPacketLayout.usesRotationSpeed(false, 975));
        assertTrue(CameraPacketLayout.usesAimAssist(false, 975));
        assertTrue(CameraPacketLayout.usesControlScheme(false, 975));
        assertFalse(CameraPacketLayout.usesAlignTargetAndCameraForward(false, 975));
        assertTrue(CameraPacketLayout.usesRemoveIgnoreStartingValues(false, 975));
    }

    @Test
    void olderNeteaseCameraVersionsFollowFieldIntroductions() {
        assertFalse(CameraPacketLayout.usesBaseCameraOptions(true, 711));
        assertFalse(CameraPacketLayout.usesRotationSpeed(true, 728));
        assertTrue(CameraPacketLayout.usesBaseCameraOptions(true, 728));
        assertTrue(CameraPacketLayout.usesRotationSpeed(true, 729));
        assertFalse(CameraPacketLayout.usesRotationLimits(true, 747));
        assertTrue(CameraPacketLayout.usesRotationLimits(true, 748));
        assertTrue(CameraPacketLayout.usesAlignTargetAndCameraForward(true, 817));
        assertFalse(CameraPacketLayout.usesAlignTargetAndCameraForward(true, 818));
        assertFalse(CameraPacketLayout.usesAimAssist(true, 765));
        assertTrue(CameraPacketLayout.usesAimAssist(true, 766));
        assertFalse(CameraPacketLayout.usesStartingRotation(true, 860));
    }

    @Test
    void easeByNameAndSplineKeyframeEaseFollow924And944() {
        assertFalse(CameraPacketLayout.usesSplineKeyframeEase(923));
        assertTrue(CameraPacketLayout.usesSplineKeyframeEase(924));
        assertTrue(CameraPacketLayout.usesSplineKeyframeEase(2225));
        assertFalse(CameraPacketLayout.usesEaseByName(943));
        assertTrue(CameraPacketLayout.usesEaseByName(944));
        assertTrue(CameraPacketLayout.usesEaseByName(975));
    }

    @Test
    void startingRotationIs2192Only() {
        assertFalse(CameraPacketLayout.usesStartingRotation(true, 2191));
        assertTrue(CameraPacketLayout.usesStartingRotation(true, 2192));
        assertTrue(CameraPacketLayout.usesStartingRotation(true, 2225));
        assertTrue(CameraPacketLayout.usesStartingRotation(false, 2191));
    }

}
