/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

/**
 * 基岩相机包布局的版本判断。
 * <p>
 * 每个谓词只由协议号决定（旧签名的 {@code emulateNetEase} 参数只为兼容调用点）；
 * 网易 860 与官方 860..896 一样落在所有阈值的下侧，两个轴走同一条代码路径。
 */
public final class CameraPacketLayout {

    public static final int ROTATION_SPEED_PROTOCOL = 729;
    public static final int ROTATION_LIMITS_PROTOCOL = 748;
    public static final int ALIGN_TARGET_PROTOCOL = 748;
    public static final int ALIGN_TARGET_REMOVED_PROTOCOL = 818;
    public static final int AIM_ASSIST_PROTOCOL = 766;
    public static final int YAW_LIMITS_PROTOCOL = 776;
    public static final int CONTROL_SCHEME_PROTOCOL = 800;
    public static final int CAMERA_TARGET_PROTOCOL = 712;
    public static final int CAMERA_FOV_PROTOCOL = 827;
    public static final int CAMERA_SPLINE_PROTOCOL = 859;
    /** 样条关键帧开始带缓动字段 */
    public static final int SPLINE_KEYFRAME_EASE_PROTOCOL = 924;
    /** 缓动字段由枚举序号改成名字字符串 */
    public static final int CAMERA_EASE_BY_NAME_PROTOCOL = 944;
    public static final int STARTING_ROTATION_PROTOCOL = 2192;

    private CameraPacketLayout() {
    }

    public static boolean usesRotationSpeed(final boolean emulateNetEase, final int protocol) {
        return protocol >= ROTATION_SPEED_PROTOCOL;
    }

    public static boolean usesBaseCameraOptions(final boolean emulateNetEase, final int protocol) {
        return protocol >= 712;
    }

    public static boolean usesRotationLimits(final boolean emulateNetEase, final int protocol) {
        return protocol >= ROTATION_LIMITS_PROTOCOL;
    }

    /** 网易 860 独有的一段：748..817 带对齐目标，818 起移除。官方基岩 748..817 也一样，但那段不在支持范围内。 */
    public static boolean usesAlignTargetAndCameraForward(final boolean emulateNetEase, final int protocol) {
        return protocol >= ALIGN_TARGET_PROTOCOL && protocol < ALIGN_TARGET_REMOVED_PROTOCOL;
    }

    public static boolean usesBlockListeningRadius(final boolean emulateNetEase, final int protocol) {
        return protocol >= AIM_ASSIST_PROTOCOL;
    }

    public static boolean usesAimAssist(final boolean emulateNetEase, final int protocol) {
        return protocol >= AIM_ASSIST_PROTOCOL;
    }

    public static boolean usesYawLimits(final boolean emulateNetEase, final int protocol) {
        return protocol >= YAW_LIMITS_PROTOCOL;
    }

    public static boolean usesControlScheme(final boolean emulateNetEase, final int protocol) {
        return protocol >= CONTROL_SCHEME_PROTOCOL;
    }

    public static boolean usesCameraTarget(final boolean emulateNetEase, final int protocol) {
        return protocol >= CAMERA_TARGET_PROTOCOL;
    }

    public static boolean usesCameraFov(final boolean emulateNetEase, final int protocol) {
        return protocol >= CAMERA_FOV_PROTOCOL;
    }

    public static boolean usesCameraSpline(final boolean emulateNetEase, final int protocol) {
        return protocol >= CAMERA_SPLINE_PROTOCOL;
    }

    public static boolean usesRemoveIgnoreStartingValues(final boolean emulateNetEase, final int protocol) {
        return protocol >= ALIGN_TARGET_REMOVED_PROTOCOL;
    }

    /** 每个预设尾部新增“继承起始朝向”开关与可选的起始朝向；897..2191（含默认 975）没有这两个字段。 */
    public static boolean usesStartingRotation(final boolean emulateNetEase, final int protocol) {
        return protocol >= STARTING_ROTATION_PROTOCOL;
    }

    /** 样条关键帧的缓动字段与尾部 {@code splineIdentifier} / {@code loadFromJson} 从 924 起出现。 */
    public static boolean usesSplineKeyframeEase(final int protocol) {
        return protocol >= SPLINE_KEYFRAME_EASE_PROTOCOL;
    }

    /** 缓动字段在 944 起由枚举序号改成名字字符串。 */
    public static boolean usesEaseByName(final int protocol) {
        return protocol >= CAMERA_EASE_BY_NAME_PROTOCOL;
    }

}
