package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.ClipRole;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The state machine that picks idle, walk or run from a speed — and, more to the point, the two guards
 * that stop it picking a different one every sample.
 */
class ClipSelectorTest {

    /** Walk from 0.1, run from 0.5, a 0.05 band around each, and no hold at all. */
    private static final ClipSettings NO_HOLD =
            new ClipSettings(true, 0.1F, 0.5F, 0.05F, 0F, 3, 3);

    /** The same thresholds with no band either, so a bare threshold's behaviour is visible. */
    private static final ClipSettings NO_GUARDS =
            new ClipSettings(true, 0.1F, 0.5F, 0F, 0F, 3, 3);

    /** The same bands with a hold of a quarter second. */
    private static final ClipSettings HELD =
            new ClipSettings(true, 0.1F, 0.5F, 0.05F, 0.25F, 3, 3);

    private static final float STEP_SECONDS = 0.05F;

    @Test
    void startsStanding() {
        assertEquals(ClipRole.IDLE, new ClipSelector().role());
    }

    @Test
    void picksEachStateFromAStandingStart() {
        assertAll(
                () -> assertEquals(ClipRole.IDLE,
                        new ClipSelector().select(0F, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.WALK,
                        new ClipSelector().select(0.2F, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.RUN,
                        new ClipSelector().select(0.9F, STEP_SECONDS, NO_HOLD)));
    }

    @Test
    void enteringAFasterStateNeedsTheThresholdPlusTheBand() {
        final ClipSelector selector = new ClipSelector();

        assertAll(
                // 0.12 is past the walk threshold but inside the band, so standing holds.
                () -> assertEquals(ClipRole.IDLE, selector.select(0.12F, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.WALK, selector.select(0.16F, STEP_SECONDS, NO_HOLD)));
    }

    @Test
    void leavingAStateNeedsTheThresholdMinusTheBand() {
        final ClipSelector selector = new ClipSelector();
        selector.select(0.2F, STEP_SECONDS, NO_HOLD);

        assertAll(
                // 0.08 is under the walk threshold but inside the band, so walking holds.
                () -> assertEquals(ClipRole.WALK, selector.select(0.08F, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.IDLE, selector.select(0.04F, STEP_SECONDS, NO_HOLD)));
    }

    @Test
    void aSpeedHoveringOnAThresholdDoesNotFlap() {
        // The failure this class exists to prevent: an owner at almost exactly walking pace crossing the
        // threshold every sample, and a clip restarted every sample.
        final ClipSelector selector = new ClipSelector();
        selector.select(0.2F, STEP_SECONDS, NO_HOLD);
        int changes = 0;
        ClipRole previous = selector.role();

        for (int sample = 0; sample < 40; sample++) {
            final float speed = sample % 2 == 0 ? 0.099F : 0.101F;
            final ClipRole role = selector.select(speed, STEP_SECONDS, NO_HOLD);
            if (role != previous) {
                changes++;
            }
            previous = role;
        }

        assertEquals(0, changes, "the hysteresis band should have absorbed every crossing");
    }

    @Test
    void withNoBandTheSameSpeedFlapsOnEverySample() {
        // The control for the test above. Documents that the band is what is doing the work, rather than
        // some accident of the thresholds.
        final ClipSelector selector = new ClipSelector();
        selector.select(0.2F, STEP_SECONDS, NO_GUARDS);
        int changes = 0;
        ClipRole previous = selector.role();

        for (int sample = 0; sample < 40; sample++) {
            final float speed = sample % 2 == 0 ? 0.099F : 0.101F;
            final ClipRole role = selector.select(speed, STEP_SECONDS, NO_GUARDS);
            if (role != previous) {
                changes++;
            }
            previous = role;
        }

        assertEquals(40, changes);
    }

    @Test
    void theMinimumHoldKeepsAStateThatTheSpeedHasAlreadyLeft() {
        // Covers the oscillation the band cannot: a speed swinging from a stop to a sprint and back.
        final ClipSelector selector = new ClipSelector();
        selector.select(0.9F, 1F, HELD);
        assertEquals(ClipRole.RUN, selector.role());

        assertAll(
                () -> assertEquals(ClipRole.RUN, selector.select(0F, 0.1F, HELD)),
                () -> assertEquals(ClipRole.RUN, selector.select(0F, 0.1F, HELD)),
                // 0.3 s elapsed, which is past the 0.25 s hold.
                () -> assertEquals(ClipRole.IDLE, selector.select(0F, 0.1F, HELD)));
    }

    @Test
    void theHoldTimerRestartsOnEveryAcceptedChange() {
        final ClipSelector selector = new ClipSelector();
        selector.select(0.2F, 1F, HELD);

        assertEquals(0F, selector.heldSeconds());
    }

    @Test
    void aResetStandsStillAndLetsTheNextSampleMoveImmediately() {
        // A model that has just been rebound must not stand frozen for the length of a hold it was not
        // part of.
        final ClipSelector selector = new ClipSelector();
        selector.select(0.9F, 1F, HELD);
        selector.reset();

        assertAll(
                () -> assertEquals(ClipRole.IDLE, selector.role()),
                () -> assertEquals(ClipRole.RUN, selector.select(0.9F, 0F, HELD)));
    }

    @Test
    void treatsGarbageSpeedAsStandingStill() {
        // The speed arrives from a differenced position, so the right failure mode for a bad sample is a
        // model that stops rather than one that throws on a region thread.
        assertAll(
                () -> assertEquals(ClipRole.IDLE,
                        new ClipSelector().select(Float.NaN, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.IDLE,
                        new ClipSelector().select(Float.POSITIVE_INFINITY, STEP_SECONDS, NO_HOLD)),
                () -> assertEquals(ClipRole.IDLE,
                        new ClipSelector().select(-5F, STEP_SECONDS, NO_HOLD)));
    }

    @Test
    void aNonPositiveDeltaDoesNotAdvanceTheHold() {
        final ClipSelector selector = new ClipSelector();
        selector.select(0F, -1F, HELD);
        selector.select(0F, Float.NaN, HELD);

        assertEquals(0F, selector.heldSeconds());
    }

    @Test
    void dropsStraightFromRunningToStandingWhenTheOwnerStops() {
        // Running to standing is one transition, not two: a model that stopped dead should not play a
        // walk cycle on the way to standing still.
        final ClipSelector selector = new ClipSelector();
        selector.select(0.9F, STEP_SECONDS, NO_HOLD);

        assertEquals(ClipRole.IDLE, selector.select(0F, STEP_SECONDS, NO_HOLD));
    }
}
