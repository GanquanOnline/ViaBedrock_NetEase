package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.pyrpc.GlowModEventCodec;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlowProjectionTrackerTest {
    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final GlowProjectionTracker tracker = new GlowProjectionTracker(this.user);

    GlowProjectionTrackerTest() {
        this.user.put(new EntityTracker(this.user));
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void snapshotReplacesPreviousStateAndFollowingUpdatesApplyInOrder() {
        this.tracker.apply(new GlowModEventCodec.Update("42", true, 255, 0, 0));
        assertTrue(this.tracker.isGlowing(42L));

        this.tracker.apply(new GlowModEventCodec.Sync(List.of(
                new GlowModEventCodec.Update("43", true, 0, 0, 255))));
        assertFalse(this.tracker.isGlowing(42L));
        assertTrue(this.tracker.isGlowing(43L));

        this.tracker.apply(new GlowModEventCodec.Update("43", false, 255, 255, 255));
        assertFalse(this.tracker.isGlowing(43L));
        this.tracker.apply(new GlowModEventCodec.Sync(List.of()));
        this.tracker.apply(new GlowModEventCodec.Update("42", true, 0, 255, 0));
        assertTrue(this.tracker.isGlowing(42L));
    }

    @Test
    void emptySnapshotClearsAllPreviousEntities() {
        this.tracker.apply(new GlowModEventCodec.Update("42", true, 255, 0, 0));
        this.tracker.apply(new GlowModEventCodec.Update("43", true, 0, 255, 0));
        this.tracker.apply(new GlowModEventCodec.Sync(List.of()));
        assertFalse(this.tracker.isGlowing(42L));
        assertFalse(this.tracker.isGlowing(43L));
    }
}
