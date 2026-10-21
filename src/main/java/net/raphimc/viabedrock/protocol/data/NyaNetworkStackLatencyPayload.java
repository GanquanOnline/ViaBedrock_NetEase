/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.data;

/**
 * NyaAC 与 ViaBedrock 共用的 Java 客户端边界载荷，仅描述回执时序，不携带运动结果。
 * <p>
 * 第二版分别编码边界家族与释放策略；同一种时序可以用于 motion、correction 等业务，
 * 不需要为每项业务新增 kind。普通 NSL 不属于本协议，仍按普通 Java Pong 路径即时回包。
 * <p>
 * {@link ReleasePolicy#NEXT_CLIENT_TICK_END} 的约定：收到 Java Pong 后，等待下一次确实
 * 生成 PlayerAuthInput 的 CLIENT_TICK_END；该包字段构建完成后立即释放 ACK，再发出当前
 * PlayerAuthInput。线上顺序为 ACK → PlayerAuthInput，不额外等待 MOVE_PLAYER_*。
 * Pong 只确认前序包的处理顺序，不证明传送或动量已经体现在某帧的实际位移中。
 * <p>
 * 第一版 kind 1 仅保留兼容解码，维持 Pong 后等待 Java movement 的历史语义；新请求不使用它。
 * 位布局为 magic[42:27]、version[26:24]、family[23:20]、policy[19:16]、token[15:0]。
 * 载荷限制为 43 位，确保回写 Bedrock NSL 时乘以 1,000,000 仍在正 long 范围内。
 */
public final class NyaNetworkStackLatencyPayload {

    private static final long MAGIC = 0x4E59L;
    private static final int MAGIC_SHIFT = 27;
    private static final int VERSION_SHIFT = 24;
    private static final int FAMILY_SHIFT = 20;
    private static final int POLICY_SHIFT = 16;
    private static final int LEGACY_KIND_SHIFT = 20;
    private static final long VERSION_1 = 1L;
    private static final long VERSION_2 = 2L;
    private static final int LEGACY_JAVA_CLIENT_TICK_END_BOUNDARY = 1;
    private static final long V2_TOKEN_MASK = (1L << POLICY_SHIFT) - 1L;
    private static final long MAGIC_MASK = 0xFFFFL << MAGIC_SHIFT;
    private static final long VERSION_MASK = 0x7L << VERSION_SHIFT;
    private static final long FOUR_BIT_MASK = 0xFL;
    private static final long MAX_VALUE = (1L << 43) - 1L;

    private NyaNetworkStackLatencyPayload() {
    }

    public static long encode(final Family family, final ReleasePolicy policy, final long token) {
        if (family == null || policy == null || policy.wireCode() <= 0) {
            throw new IllegalArgumentException("Invalid Java boundary descriptor");
        }
        return MAGIC << MAGIC_SHIFT
                | VERSION_2 << VERSION_SHIFT
                | (long) family.wireCode() << FAMILY_SHIFT
                | (long) policy.wireCode() << POLICY_SHIFT
                | token & V2_TOKEN_MASK;
    }

    public static long encodeLegacyAfterMovement(final long token) {
        return MAGIC << MAGIC_SHIFT
                | VERSION_1 << VERSION_SHIFT
                | (long) LEGACY_JAVA_CLIENT_TICK_END_BOUNDARY << LEGACY_KIND_SHIFT
                | token & ((1L << LEGACY_KIND_SHIFT) - 1L);
    }

    public static boolean isPayload(final long value) {
        return value >= 0 && value <= MAX_VALUE
                && (value & MAGIC_MASK) == MAGIC << MAGIC_SHIFT;
    }

    public static JavaBoundaryDescriptor decode(final long value) {
        if (!isPayload(value)) {
            return null;
        }
        final long version = (value & VERSION_MASK) >>> VERSION_SHIFT;
        if (version == VERSION_1) {
            final int kind = (int) ((value >>> LEGACY_KIND_SHIFT) & FOUR_BIT_MASK);
            return kind == LEGACY_JAVA_CLIENT_TICK_END_BOUNDARY
                    ? new JavaBoundaryDescriptor(Family.JAVA_CLIENT_BOUNDARY,
                    ReleasePolicy.LEGACY_AFTER_MOVEMENT)
                    : null;
        }
        if (version != VERSION_2) {
            return null;
        }
        final Family family = Family.byWireCode(
                (int) ((value >>> FAMILY_SHIFT) & FOUR_BIT_MASK));
        final ReleasePolicy policy = ReleasePolicy.byWireCode(
                (int) ((value >>> POLICY_SHIFT) & FOUR_BIT_MASK));
        return family == null || policy == null
                ? null : new JavaBoundaryDescriptor(family, policy);
    }

    public static boolean isJavaClientTickEndBoundary(final long value) {
        final JavaBoundaryDescriptor descriptor = decode(value);
        return descriptor != null && descriptor.releasePolicy() != ReleasePolicy.AFTER_CLIENT_PONG;
    }

    /** UUID 归属复用 v2 家族字段；时序策略与归属身份分开。 */
    public static boolean isOwnedBoundary(final long value) {
        final JavaBoundaryDescriptor descriptor = decode(value);
        return descriptor != null && descriptor.family() == Family.JAVA_OWNED_BOUNDARY;
    }

    public enum Family {
        JAVA_CLIENT_BOUNDARY(1),
        JAVA_OWNED_BOUNDARY(2);

        private final int wireCode;

        Family(final int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return this.wireCode;
        }

        private static Family byWireCode(final int wireCode) {
            for (Family value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }

    public enum ReleasePolicy {
        NEXT_CLIENT_TICK_END(1),
        AFTER_CLIENT_PONG(2),
        LEGACY_AFTER_MOVEMENT(-1);

        private final int wireCode;

        ReleasePolicy(final int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return this.wireCode;
        }

        private static ReleasePolicy byWireCode(final int wireCode) {
            for (ReleasePolicy value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }

    public record JavaBoundaryDescriptor(Family family, ReleasePolicy releasePolicy) {
    }

}
