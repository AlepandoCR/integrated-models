package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehicleProfile;
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
                new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 3L, 6F, 8F, 0.05F,
                        VehicleFixtures.seatSettings());
        final VehicleState fast = new VehicleState(1F, 0F, 0F);

        final VehicleStepper.Plan plan = VehicleStepper.plan(fast,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), generous,
                VehicleStepper.NANOS_PER_TICK * 100L);

        assertSame(MoveKind.LOCAL, plan.kind());
    }

    @Test
    void aDisplacementExactlyAtTheConfiguredThresholdIsStillWrittenInPlace() {
        // The boundary itself, and it is inclusive on purpose: the threshold is how far a single in-place
        // write may reach, so a write of exactly that far is one it may make. Two ticks at the fixture's
        // top speed of a block a tick is its two-block threshold exactly; half a tick more is past it.
        final VehicleState rolling = new VehicleState(1F, 0F, 0F);

        final VehicleStepper.Plan atTheLimit = VehicleStepper.plan(rolling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK * 2L);
        final VehicleStepper.Plan past = VehicleStepper.plan(rolling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK * 5L / 2L);

        assertAll(
                () -> assertEquals(2.0D, atTheLimit.move().deltaZ(), EPSILON),
                () -> assertSame(MoveKind.LOCAL, atTheLimit.kind()),
                () -> assertEquals(2.5D, past.move().deltaZ(), EPSILON),
                () -> assertSame(MoveKind.TELEPORT, past.kind())
        );
    }

    @Test
    void theSameOrdinaryStepIsARelocationOnAHostWithNoInPlaceMove() {
        // The fact the stepper could not see and now is given. A server with no Mojang-mapped adapter
        // cannot write a displacement in place at all, so every move its vehicles make is a relocation —
        // and the plan now names the operation that will actually happen rather than the one the
        // threshold would allow on a server that could perform it.
        final VehicleState rolling = new VehicleState(0.5F, 0F, 0F);

        final VehicleStepper.Plan withAdapter = VehicleStepper.plan(rolling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK, TerrainAttitude.LEVEL, new TerrainSample(), true);
        final VehicleStepper.Plan without = VehicleStepper.plan(rolling,
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT), VehicleFixtures.profile(), this.settings,
                VehicleStepper.NANOS_PER_TICK, TerrainAttitude.LEVEL, new TerrainSample(), false);

        assertAll(
                () -> assertSame(MoveKind.LOCAL, withAdapter.kind()),
                () -> assertSame(MoveKind.TELEPORT, without.kind()),
                () -> assertEquals(withAdapter.move().deltaZ(), without.move().deltaZ(), EPSILON,
                        "the displacement is the same; only the way it reaches the world differs"),
                () -> assertFalse(without.isIdle(), "a step that has to relocate still has work to do")
        );
    }

    @Test
    void aStepWithNothingToWriteStaysNothingOnAHostWithNoInPlaceMove() {
        // The one kind the capability must not touch. Promoting "nothing to write" to a relocation would
        // make every parked vehicle on an adapterless server pay for a teleport transaction a tick, for a
        // displacement of zero.
        final VehicleStepper.Plan plan = VehicleStepper.plan(VehicleState.STOPPED, RiderIntent.NONE,
                VehicleFixtures.profile(), this.settings, VehicleStepper.NANOS_PER_TICK,
                TerrainAttitude.LEVEL, new TerrainSample(), false);

        assertAll(
                () -> assertSame(MoveKind.NONE, plan.kind()),
                () -> assertTrue(plan.isIdle())
        );
    }

    @Test
    void aHostWithNoInPlaceMoveIsGrantedNoClimbToThrowAway() {
        // The climbPermitted contract, which used to promise one thing and compute another. It promises
        // false for a step whose displacement is going through the asynchronous teleport, and the one
        // reason every step of a server goes through it — no adapter at all — was the one fact the
        // stepper could not see. A rise granted anyway was folded into the move, routed to the
        // relocation, and dropped; harmless only because the relocation also cancelled the armed climb.
        final VehicleProfile climbing = VehicleFixtures.terrainProfile(
                VehicleKind.GROUND, VehicleFixtures.snapping(20F, 24F, 0F, 1F, 4));
        final TerrainSample hillside = new TerrainSample();
        hillside.set(64.0D, 64.0D, true, 65.0D);

        final VehicleStepper.Plan granted = VehicleStepper.plan(VehicleState.STOPPED, RiderIntent.NONE,
                climbing, this.settings, VehicleStepper.NANOS_PER_TICK, TerrainAttitude.LEVEL, hillside,
                true);
        final VehicleStepper.Plan refused = VehicleStepper.plan(VehicleState.STOPPED, RiderIntent.NONE,
                climbing, this.settings, VehicleStepper.NANOS_PER_TICK, TerrainAttitude.LEVEL, hillside,
                false);

        assertAll(
                () -> assertEquals(0.25D, granted.terrain().rise(), EPSILON,
                        "a block spread over the response's four ticks"),
                () -> assertSame(MoveKind.LOCAL, granted.kind(), "a climb on its own is a local write"),
                () -> assertEquals(0.0D, refused.terrain().rise(), EPSILON),
                () -> assertSame(MoveKind.NONE, refused.kind(),
                        "and nothing is written at all, rather than a teleport that discards the rise"),
                () -> assertTrue(refused.terrain().next().isClimbing(),
                        "deferred rather than abandoned, which is the solver's own documented rule")
        );
    }

    @Test
    void theRestThresholdIsTheConfiguredOneRatherThanALiteral() {
        final VehicleSettings twitchy =
                new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 3L, 6F, 2F, 0.0001F,
                        VehicleFixtures.seatSettings());
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
