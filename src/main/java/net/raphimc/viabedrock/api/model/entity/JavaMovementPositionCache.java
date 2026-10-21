package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.model.Position3f;
import org.checkerframework.checker.nullness.qual.Nullable;

/** 只保存 Java 普通位置包的观测坐标，不执行物理预测或位移外推。 */
final class JavaMovementPositionCache {
    private @Nullable Position3f regularPosition;
    private @Nullable Teleport teleport;
    private boolean confirmed;
    private boolean awaitingEcho;
    private boolean restoreOmittedPosition;

    void issueTeleport(int id, double x, double y, double z, float yaw, float pitch, boolean absolute) {
        // 连续未完成的传送不能证明旧普通坐标仍属于新边界，保守失效，不排队猜测。
        if (this.teleport != null || !absolute) this.regularPosition = null;
        this.teleport = absolute ? new Teleport(id, x, y, z, yaw, pitch) : null;
        this.confirmed = false;
        this.awaitingEcho = false;
        this.restoreOmittedPosition = false;
    }

    void confirmTeleport(int id) {
        this.confirmed = this.teleport != null && this.teleport.id() == id;
        if (!this.confirmed) {
            this.regularPosition = null;
            this.teleport = null;
        }
        this.awaitingEcho = true;
        this.restoreOmittedPosition = false;
    }

    void recordPosition(Position3f position) {
        this.regularPosition = position;
        this.restoreOmittedPosition = false;
        if (this.awaitingEcho) {
            this.teleport = null;
            this.confirmed = false;
            this.awaitingEcho = false;
            this.restoreOmittedPosition = false;
        }
    }

    void recordPositionWithRotation(Position3f position, double x, double y, double z,
                                    float yaw, float pitch, short flags) {
        if (!this.awaitingEcho) {
            this.recordPosition(position);
            return;
        }
        boolean matched = this.confirmed && this.teleport != null
                && this.teleport.matches(x, y, z, yaw, pitch, flags);
        if (!matched) this.regularPosition = null;
        this.restoreOmittedPosition = matched;
        this.teleport = null;
        this.confirmed = false;
        this.awaitingEcho = false;
        // 匹配 echo 不更新原版 xLast；不匹配的确认后包也不冒充普通缓存。
    }

    @Nullable Position3f omittedPosition() {
        if (this.awaitingEcho) {
            this.reset();
            return null;
        }
        final Position3f position = this.restoreOmittedPosition ? this.regularPosition : null;
        // 恢复只属于该 echo 后首次明确省略坐标的包，不能覆盖后来的服务端位置。
        this.restoreOmittedPosition = false;
        return position;
    }

    void reset() {
        this.regularPosition = null;
        this.teleport = null;
        this.confirmed = false;
        this.awaitingEcho = false;
        this.restoreOmittedPosition = false;
    }

    private record Teleport(int id, double x, double y, double z, float yaw, float pitch) {
        boolean matches(double x, double y, double z, float yaw, float pitch, short flags) {
            return flags == 0 && Double.compare(this.x, x) == 0 && Double.compare(this.y, y) == 0
                    && Double.compare(this.z, z) == 0 && Float.compare(this.yaw, yaw) == 0
                    && Float.compare(this.pitch, pitch) == 0;
        }
    }
}
