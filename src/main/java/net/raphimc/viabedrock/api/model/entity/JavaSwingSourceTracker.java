package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorSwingSource;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;

import java.util.Objects;

/** Java 的使用动作和攻击共享 SWING 包，以同一客户端 tick 内最近的使用动作关联来源。 */
public final class JavaSwingSourceTracker {

    private InteractionHand pendingHand;
    private ActorSwingSource pendingSource;

    public void recordUse(final InteractionHand hand, final ActorSwingSource source) {
        Objects.requireNonNull(hand, "hand");
        if (source != ActorSwingSource.Build && source != ActorSwingSource.Interact && source != ActorSwingSource.UseItem) {
            throw new IllegalArgumentException("Expected a use swing source");
        }
        // 主手交互失败再尝试副手时，以后一次动作替换，避免失败的主手记录残留。
        this.pendingHand = hand;
        this.pendingSource = source;
    }

    public ActorSwingSource consume(final InteractionHand hand) {
        if (hand != this.pendingHand || this.pendingSource == null) {
            return ActorSwingSource.Attack;
        }
        final ActorSwingSource source = this.pendingSource;
        this.reset();
        return source;
    }

    public void reset() {
        this.pendingHand = null;
        this.pendingSource = null;
    }
}
