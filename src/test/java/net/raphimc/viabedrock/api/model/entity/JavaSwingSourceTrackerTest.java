package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorSwingSource;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaSwingSourceTrackerTest {

    @Test
    void standaloneSwingRemainsAnAttack() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        assertEquals(ActorSwingSource.Attack, tracker.consume(InteractionHand.MAIN_HAND));
    }

    @Test
    void useSourceIsConsumedOnceAndDoesNotHideTheNextAttack() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        for (ActorSwingSource source : new ActorSwingSource[]{ActorSwingSource.Build, ActorSwingSource.Interact, ActorSwingSource.UseItem}) {
            tracker.recordUse(InteractionHand.MAIN_HAND, source);
            assertEquals(source, tracker.consume(InteractionHand.MAIN_HAND));
            assertEquals(ActorSwingSource.Attack, tracker.consume(InteractionHand.MAIN_HAND));
        }
    }

    @Test
    void offhandUseDoesNotTurnAMainHandAttackIntoUse() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        tracker.recordUse(InteractionHand.OFF_HAND, ActorSwingSource.Build);
        assertEquals(ActorSwingSource.Attack, tracker.consume(InteractionHand.MAIN_HAND));
        assertEquals(ActorSwingSource.Build, tracker.consume(InteractionHand.OFF_HAND));
    }

    @Test
    void offhandFallbackReplacesTheFailedMainHandAttempt() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        tracker.recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Interact);
        tracker.recordUse(InteractionHand.OFF_HAND, ActorSwingSource.Build);
        assertEquals(ActorSwingSource.Build, tracker.consume(InteractionHand.OFF_HAND));
        assertEquals(ActorSwingSource.Attack, tracker.consume(InteractionHand.MAIN_HAND));
    }

    @Test
    void resetDropsAnAttemptWithoutAFollowingSwing() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        tracker.recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
        tracker.reset();
        assertEquals(ActorSwingSource.Attack, tracker.consume(InteractionHand.MAIN_HAND));
    }

    @Test
    void rejectsAttackSourcesAsUseContext() {
        final JavaSwingSourceTracker tracker = new JavaSwingSourceTracker();
        assertThrows(IllegalArgumentException.class,
                () -> tracker.recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Attack));
    }
}
