package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleState;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement decision as a pure function of input, state, profile and elapsed time.
 *
 * <p>Everything a vehicle can get wrong is decided here, which is why this is the suite that matters
 * most: it covers the elapsed-time clamp, the steering translation, the rest fixed point and — the
 * expensive one — whether a displacement is routed to the asynchronous teleport or written in place.
 * None of it needs a server, and the live-server version of these assertions would be a server that
 * silently discards a write and never says so.
 */
class VehicleStepperTest {

    private static final float EPSILON = 1.0E-5F;

    private final VehicleSettings settings = VehicleFixtures.settings();

    @Test
    void oneTickOfNanosecondsIsOneTick() {
        // The derivation, asserted rather than assumed: the server and client tick rate is 20 Hz, so a
        // tick is fifty million nanoseconds.
        assertAll(
                () -> assertEquals(50_000_000L, VehicleStepper.NANOS_PER_TICK),
                () -> assertEquals(1F, VehicleStepper.elapsedTicks(50_000_000L, 6F), EPSILON),
                () -> assertEquals(0.5F, VehicleStepper.elapsedTicks(25_000_000L, 6F), EPSILON)
        );
    }

    @Test
    void aGapLongerThanTheClampIsCutDownRatherThanIntegrated() {
        // The guard on a server that stalled. Integrating the whole gap faithfully would move the
        // vehicle to where it would have been, through whatever was in the way.
        assertEquals(6F, VehicleStepper.elapsedTicks(50_000_000L * 400L, 6F), EPSILON);
    }

    @Test
    void aNonPositiveGapIsNoTimeAtAll() {
        // A monotonic clock should not go backwards, and the first step of a vehicle's life has nothing
        // to difference against. Both answer "no time has passed".
        assertAll(
                () -> assertEquals(0F, VehicleStepper.elapsedTicks(0L, 6F)),
                () -> assertEquals(0F, VehicleStepper.elapsedTicks(-1_000_000L, 6F))
        );
    }

    @Test
    void anInputBitfieldBecomesSteeringThroughTheProfilesScheme() {
        final VehicleStepper.Plan turning = VehicleStepper.plan(VehicleState.STOPPED,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT | RiderIntent.LEFT_BIT),
                VehicleFixtures.profile(), this.settings, VehicleStepper.NANOS_PER_TICK);
        final VehicleStepper.Plan strafing = VehicleStepper.plan(VehicleState.STOPPED,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT | RiderIntent.LEFT_BIT),
                VehicleFixtures.strafingProfile(), this.settings, VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertEquals(1F, turning.steering().throttle()),
                () -> assertEquals(-1F, turning.steering().turn(), "left decreases yaw"),
                () -> assertEquals(0F, turning.steering().strafe()),
                () -> assertEquals(1F, strafing.steering().strafe()),
                () -> assertEquals(0F, strafing.steering().turn())
        );
    }

    @Test
    void anIdleVehicleWithNoRiderDecidesNothingAtAll() {
        final VehicleStepper.Plan plan = VehicleStepper.plan(VehicleState.STOPPED, RiderIntent.NONE,
                VehicleFixtures.profile(), this.settings, VehicleStepper.NANOS_PER_TICK * 3L);

        assertAll(
                () -> assertTrue(plan.isIdle()),
                () -> assertSame(MoveKind.NONE, plan.kind()),
                () -> assertTrue(plan.move().isStationary())
        );
    }

    @Test
    void aCoastingVehicleBelowTheRestSpeedIsSnappedToADeadStopKeepingItsHeading() {
        // Friction is geometric, so a coasting vehicle approaches zero and never arrives. Left alone it
        // writes a position every tick forever for a displacement no client could resolve.
        final VehicleState crawling = new VehicleState(0.04F, 0F, 137F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(crawling, RiderIntent.NONE,
                VehicleFixtures.profile(), this.settings, VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertEquals(0F, plan.move().next().forwardSpeedBlocksPerTick()),
                () -> assertEquals(137F, plan.move().next().yawDegrees(), EPSILON,
                        "a vehicle that has stopped has not forgotten which way it points"),
                () -> assertTrue(plan.isIdle(), "the stop has to be a fixed point, not almost one")
        );
    }

    @Test
    void aDrivenVehicleIsNeverSnappedToAStopHoweverSlowItIs() {
        // The snap is gated on the steering as well as the speed. Without that, a profile whose
        // acceleration is below the rest threshold would never be able to start moving.
        final VehicleState crawling = new VehicleState(0.001F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(crawling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertFalse(plan.isIdle()),
                () -> assertTrue(plan.move().next().forwardSpeedBlocksPerTick() > 0.001F)
        );
    }

    @Test
    void turningOnTheSpotIsNotIdleEvenWithNoDisplacement() {
        // The one branch a reader assumes is missing. A vehicle with both speeds at zero and the turn key
        // held has nothing to translate and still has to be written.
        final VehicleStepper.Plan plan = VehicleStepper.plan(VehicleState.STOPPED,
                RiderIntent.ofBits(RiderIntent.RIGHT_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertSame(MoveKind.NONE, plan.kind()),
                () -> assertFalse(plan.isIdle(), "the steering is not neutral, so there is work to do"),
                () -> assertEquals(10F, plan.move().yawDegrees(), EPSILON)
        );
    }

    @Test
    void anOrdinaryStepIsWrittenInPlace() {
        final VehicleState rolling = new VehicleState(0.5F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(rolling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertSame(MoveKind.LOCAL, plan.kind()),
                () -> assertEquals(0.75D, plan.move().deltaZ(), EPSILON,
                        "a tick at 0.75 blocks a tick along +Z, which is the facing at yaw zero")
        );
    }

    @Test
    void aStepLargerThanTheConfiguredLocalThresholdIsRoutedToTheTeleport() {
        // The expensive decision, and the one a live server reports on by silently discarding the write.
        // The settings here permit two blocks; six ticks at a block a tick is six.
        final VehicleState fast = new VehicleState(1F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(fast,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK * 100L);

        assertAll(
                () -> assertEquals(6F, plan.ticks(), EPSILON, "the gap was clamped first"),
                () -> assertSame(MoveKind.TELEPORT, plan.kind()),
                () -> assertEquals(6.0D, plan.move().deltaZ(), EPSILON)
        );
    }

    @Test
    void theSameStepStaysLocalWhenTheOperatorRaisesTheThreshold() {
        // The other half of the previous assertion, and what distinguishes a layer that reads its
        // configuration from one that happens to agree with a literal.
        final VehicleSettings generous =
                new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 3L, 6F, 8F, 0.05F);
        final VehicleState fast = new VehicleState(1F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(fast,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), generous,
                VehicleStepper.NANOS_PER_TICK * 100L);

        assertSame(MoveKind.LOCAL, plan.kind());
    }

    @Test
    void theRestThresholdIsTheConfiguredOneRatherThanALiteral() {
        final VehicleSettings twitchy =
                new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 3L, 6F, 2F, 0.0001F);
        final VehicleState crawling = new VehicleState(0.04F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(crawling, RiderIntent.NONE,
                VehicleFixtures.profile(), twitchy, VehicleStepper.NANOS_PER_TICK);

        assertAll(
                () -> assertFalse(plan.isIdle(),
                        "with a far lower rest threshold the same vehicle is still moving"),
                () -> assertEquals(0.02F, plan.move().next().forwardSpeedBlocksPerTick(), EPSILON)
        );
    }

    @Test
    void sneakSteersNothingBecauseTheServerDismountsForIt() {
        final VehicleStepper.Plan plan = VehicleStepper.plan(VehicleState.STOPPED,
                RiderIntent.ofBits(RiderIntent.SNEAK_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK);

        assertTrue(plan.isIdle());
    }
}
