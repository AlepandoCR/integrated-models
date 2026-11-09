package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motion itself: acceleration, braking, coasting, turning and the projection into world space.
 *
 * <p>The assertions that matter most are the two that are about <em>time</em> rather than about speed.
 * The solver is closed form precisely so that one step of two ticks agrees with two steps of one, and
 * that property is what makes a vehicle behave the same on a loaded server as on an idle one. It is also
 * the property that a plausible-looking integrator silently lacks.
 */
class VehicleSolverTest {

    /** How close two floats have to be to count as the same answer here. */
    private static final float EPSILON = 1.0E-5F;

    private static final VehicleSteering AHEAD = new VehicleSteering(1F, 0F, 0F, false, false);
    private static final VehicleSteering ASTERN = new VehicleSteering(-1F, 0F, 0F, false, false);
    private static final VehicleSteering RIGHT = new VehicleSteering(0F, 0F, 1F, false, false);

    @Test
    void noElapsedTimeIsNoMovementEvenWithAKeyHeld() {
        // No elapsed time is not the same as no input: inventing a displacement for it would make the
        // vehicle's speed depend on how often it was asked rather than on how long it drove.
        final VehicleMove move =
                VehicleSolver.advance(VehicleState.STOPPED, AHEAD, VehicleFixtures.car(), 0F);

        assertAll(
                () -> assertTrue(move.isStationary()),
                () -> assertSame(VehicleState.STOPPED, move.next())
        );
    }

    @Test
    void aNegativeElapsedTimeIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> VehicleSolver.advance(
                        VehicleState.STOPPED, AHEAD, VehicleFixtures.car(), -1F)),
                () -> assertThrows(IllegalArgumentException.class, () -> VehicleSolver.advance(
                        VehicleState.STOPPED, AHEAD, VehicleFixtures.car(), Float.NaN))
        );
    }

    @Test
    void speedRisesAtTheAccelerationRateAndStopsAtTheTopSpeed() {
        VehicleState state = VehicleState.STOPPED;
        final VehicleProfile car = VehicleFixtures.car();

        state = VehicleSolver.advance(state, AHEAD, car, 1F).next();
        assertEquals(0.25F, state.forwardSpeedBlocksPerTick(), EPSILON);
        state = VehicleSolver.advance(state, AHEAD, car, 1F).next();
        assertEquals(0.5F, state.forwardSpeedBlocksPerTick(), EPSILON);

        // Four accelerations reach the top speed and the fifth does not exceed it: the approach clamps
        // to the target rather than overshooting and relying on the envelope to catch it.
        state = VehicleSolver.advance(state, AHEAD, car, 10F).next();
        assertEquals(1F, state.forwardSpeedBlocksPerTick(), EPSILON);
    }

    @Test
    void oneLongStepAgreesWithSeveralShortOnesWhileAccelerating() {
        final VehicleProfile car = VehicleFixtures.car();

        VehicleState stepped = VehicleState.STOPPED;
        for (int tick = 0; tick < 3; tick++) {
            stepped = VehicleSolver.advance(stepped, AHEAD, car, 1F).next();
        }
        final VehicleState jumped =
                VehicleSolver.advance(VehicleState.STOPPED, AHEAD, car, 3F).next();

        // The property the whole closed form exists for: a server that stalled and caught up ends up
        // with the same vehicle as one that did not.
        assertEquals(stepped.forwardSpeedBlocksPerTick(), jumped.forwardSpeedBlocksPerTick(), EPSILON);
    }

    @Test
    void oneLongStepAgreesWithSeveralShortOnesWhileCoasting() {
        // Friction is geometric in the tick count, which is the half of the step-size independence that
        // a linear application silently breaks: with friction applied linearly, a two-tick coast would
        // lose twice as much speed as two one-tick coasts.
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleState rolling = new VehicleState(1F, 0F, 0F);

        VehicleState coasting = rolling;
        for (int tick = 0; tick < 2; tick++) {
            coasting = VehicleSolver.advance(coasting, VehicleSteering.NEUTRAL, car, 1F).next();
        }
        final VehicleState stepped = coasting;
        final VehicleState jumped =
                VehicleSolver.advance(rolling, VehicleSteering.NEUTRAL, car, 2F).next();

        assertAll(
                () -> assertEquals(0.25F, stepped.forwardSpeedBlocksPerTick(), EPSILON,
                        "a friction of a half should halve the speed each tick"),
                () -> assertEquals(0.25F, jumped.forwardSpeedBlocksPerTick(), EPSILON)
        );
    }

    @Test
    void reversingFromFullAheadUsesTheBrakingRateUntilItStops() {
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleState rolling = new VehicleState(1F, 0F, 0F);

        // Braking is 0.5 a tick against an acceleration of 0.25, and the regime is chosen by what the
        // change does to the speed's magnitude rather than by the sign of the input — so a rider who
        // slams into reverse gets the braking rate without the caller noticing the crossing.
        final VehicleState braked = VehicleSolver.advance(rolling, ASTERN, car, 1F).next();
        assertEquals(0.5F, braked.forwardSpeedBlocksPerTick(), EPSILON);

        final VehicleState stopped = VehicleSolver.advance(braked, ASTERN, car, 1F).next();
        assertEquals(0F, stopped.forwardSpeedBlocksPerTick(), EPSILON);

        // Past zero it is accelerating again, at the acceleration rate, toward the reverse top speed.
        final VehicleState reversing = VehicleSolver.advance(stopped, ASTERN, car, 1F).next();
        assertEquals(-0.25F, reversing.forwardSpeedBlocksPerTick(), EPSILON);
    }

    @Test
    void reverseHasItsOwnTopSpeedAndNoBoost() {
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleSteering boostedAstern = new VehicleSteering(-1F, 0F, 0F, true, false);

        VehicleState state = VehicleState.STOPPED;
        state = VehicleSolver.advance(state, boostedAstern, car, 100F).next();

        // A vehicle that reverses faster when the rider holds sprint is a vehicle whose boost key is a
        // speed key, which is a different feature.
        assertEquals(-0.5F, state.forwardSpeedBlocksPerTick(), EPSILON);
    }

    @Test
    void theBoostRaisesTheForwardCeilingAndReleasingItDecaysRatherThanSnapping() {
        final VehicleProfile boosted = VehicleFixtures.boosted();
        final VehicleSteering boostAhead = new VehicleSteering(1F, 0F, 0F, true, false);

        final VehicleState fast =
                VehicleSolver.advance(VehicleState.STOPPED, boostAhead, boosted, 100F).next();
        assertEquals(2F, fast.forwardSpeedBlocksPerTick(), EPSILON);

        // The envelope the final clamp uses is the profile's whole range, boost included, so releasing
        // the key lets the braking rate bring the speed down instead of snapping it to the unboosted
        // ceiling the instant the key comes up.
        final VehicleState released = VehicleSolver.advance(fast, AHEAD, boosted, 0.5F).next();
        assertEquals(1.5F, released.forwardSpeedBlocksPerTick(), EPSILON);
    }

    @Test
    void theHeadingTurnsAtTheProfileRateAndTheTranslationFollowsTheNewHeading() {
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleState rolling = new VehicleState(1F, 0F, 0F);

        final VehicleMove move = VehicleSolver.advance(rolling, RIGHT, car, 1F);

        assertAll(
                () -> assertEquals(10F, move.yawDegrees(), EPSILON),
                // Turn first, then translate: the other order is a half-step of lag that reads as the
                // controls being late. At yaw 10 the facing is (-sin 10, cos 10), so a forward step of
                // the coasting speed lands there and not along +Z.
                () -> assertEquals(-Math.sin(Math.toRadians(10.0D)) * move.next()
                        .forwardSpeedBlocksPerTick(), move.deltaX(), EPSILON),
                () -> assertEquals(Math.cos(Math.toRadians(10.0D)) * move.next()
                        .forwardSpeedBlocksPerTick(), move.deltaZ(), EPSILON)
        );
    }

    @Test
    void atYawZeroForwardIsPositiveZAndLeftIsPositiveX() {
        // The convention the whole pipeline shares. If this is wrong, a vehicle drives backwards and the
        // model on it reports its stride against the wrong axis, which is two bugs that hide each other.
        final VehicleProfile hovercraft = VehicleFixtures.hovercraft();
        final VehicleState rolling = new VehicleState(1F, 1F, 0F);

        final VehicleMove move =
                VehicleSolver.advance(rolling, VehicleSteering.NEUTRAL, hovercraft, 1F);

        assertAll(
                () -> assertEquals(0.5D, move.deltaZ(), EPSILON, "forward at yaw zero is +Z"),
                () -> assertEquals(0.5D, move.deltaX(), EPSILON, "left at yaw zero is +X"),
                () -> assertEquals(0.0D, move.deltaY(),
                        "vertical motion belongs to the entity's own physics")
        );
    }

    @Test
    void aTurningProfileNeverStrafesHoweverTheSteeringArrives() {
        // The scheme is enforced where the keys are translated, so a steering built by hand with both a
        // turn and a strafe is not something the control path can produce. What this asserts is the
        // other half: a profile whose strafe speed is zero cannot be made to slide.
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleSteering sideways = new VehicleSteering(0F, 1F, 0F, false, false);

        final VehicleMove move = VehicleSolver.advance(VehicleState.STOPPED, sideways, car, 10F);

        assertAll(
                () -> assertEquals(0F, move.next().strafeSpeedBlocksPerTick(), EPSILON),
                () -> assertTrue(move.isStationary())
        );
    }

    @Test
    void aSpeedInheritedFromAWiderProfileIsBroughtBackInsideTheEnvelope() {
        // The final clamp is a rail rather than a shaper: the only thing it catches is a state carried
        // over from a profile that has since been narrowed.
        final VehicleProfile car = VehicleFixtures.car();
        final VehicleState tooFast = new VehicleState(5F, 0F, 0F);

        final VehicleState clamped =
                VehicleSolver.advance(tooFast, VehicleSteering.NEUTRAL, car, 1F).next();

        assertTrue(clamped.forwardSpeedBlocksPerTick() <= car.boostedForwardSpeedBlocksPerTick(),
                () -> "inherited speed was not clamped: " + clamped.forwardSpeedBlocksPerTick());
    }
}
