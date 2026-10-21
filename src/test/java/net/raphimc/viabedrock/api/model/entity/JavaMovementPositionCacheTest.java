package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaMovementPositionCacheTest {
    private static final Position3f FIRST_STEP = new Position3f(0.098F, 1.62F, 0F);
    private static final Position3f CORRECTION = new Position3f(0F, 1.62F, 0F);

    @Test
    void matchingEchoPreservesTheOrdinaryPositionForExplicitOmission() {
        final JavaMovementPositionCache cache = this.correctedCache();
        assertEquals(FIRST_STEP, cache.omittedPosition());
        assertNull(cache.omittedPosition());
    }

    @Test
    void ordinaryPositionAfterEchoReplacesTheCachedReport() {
        final JavaMovementPositionCache cache = this.correctedCache();
        final Position3f nextPosition = new Position3f(0.171F, 1.62F, 0F);
        cache.recordPosition(nextPosition);
        assertNull(cache.omittedPosition());
        this.issue(cache, 2);
        cache.confirmTeleport(2);
        this.echo(cache);
        assertEquals(nextPosition, cache.omittedPosition());
    }

    @Test
    void ordinaryPositionWithRotationAfterEchoReplacesTheCachedReport() {
        final JavaMovementPositionCache cache = this.correctedCache();
        final Position3f nextPosition = new Position3f(0.171F, 1.62F, 0F);
        cache.recordPositionWithRotation(nextPosition, 0.171D, 0D, 0D, 20F, 10F, (short) 1);
        assertNull(cache.omittedPosition());
        this.issue(cache, 2);
        cache.confirmTeleport(2);
        this.echo(cache);
        assertEquals(nextPosition, cache.omittedPosition());
    }

    @Test
    void matchingTeleportWithoutAnOrdinaryReportDoesNotInventPosition() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        this.issue(cache, 1);
        cache.confirmTeleport(1);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void unconfirmedTeleportDoesNotEnableOmittedPosition() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, 1);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void wrongSignedIdCannotConfirmTheTeleport() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, -1);
        cache.confirmTeleport(1);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void staleConfirmationInvalidatesTheOldReport() {
        final JavaMovementPositionCache cache = this.correctedCache();
        this.issue(cache, 2);
        cache.confirmTeleport(1);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void unsupportedRelativeTeleportDoesNotCarryAnOldReport() {
        final JavaMovementPositionCache cache = this.correctedCache();
        cache.issueTeleport(2, 0D, 0D, 0D, 0F, 0F, false);
        cache.confirmTeleport(2);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void consecutiveUnfinishedTeleportsDiscardTheOldCache() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, 1);
        this.issue(cache, 2);
        cache.confirmTeleport(2);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void dimensionResetRejectsOldConfirmationAndOldPosition() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, 1);
        cache.reset();
        cache.confirmTeleport(1);
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void explicitOmissionBeforeEchoDiscardsTheUnprovenBoundary() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, 1);
        cache.confirmTeleport(1);
        assertNull(cache.omittedPosition());
        this.echo(cache);
        assertNull(cache.omittedPosition());
    }

    @Test
    void echoMustMatchPositionRotationAndFlagsExactly() {
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            final JavaMovementPositionCache cache = new JavaMovementPositionCache();
            cache.recordPosition(FIRST_STEP);
            this.issue(cache, 1);
            cache.confirmTeleport(1);
            cache.recordPositionWithRotation(CORRECTION,
                    mismatch == 0 ? 0.00001D : 0D, 0D, 0D,
                    mismatch == 1 ? 1F : 0F, mismatch == 2 ? 1F : 0F, mismatch == 3 ? (short) 1 : 0);
            assertNull(cache.omittedPosition());
        }
    }

    private JavaMovementPositionCache correctedCache() {
        final JavaMovementPositionCache cache = new JavaMovementPositionCache();
        cache.recordPosition(FIRST_STEP);
        this.issue(cache, 1);
        cache.confirmTeleport(1);
        this.echo(cache);
        return cache;
    }

    private void issue(JavaMovementPositionCache cache, int id) {
        cache.issueTeleport(id, 0D, 0D, 0D, 0F, 0F, true);
    }

    private void echo(JavaMovementPositionCache cache) {
        cache.recordPositionWithRotation(CORRECTION, 0D, 0D, 0D, 0F, 0F, (short) 0);
    }
}
