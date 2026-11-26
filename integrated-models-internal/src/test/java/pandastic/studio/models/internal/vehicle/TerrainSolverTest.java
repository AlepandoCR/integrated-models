package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleState;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain reaction as a pure function of a sample, a profile and the motion the vehicle just made.
 *
 * <p>This is the suite that earns the sample/response split. The rule the whole feature rests on —
 * <em>every angle is a ceiling scaled by the fraction of a full-rate input achieved</em> — cannot be
 * observed on a running server at all: what a server can show is that a cart leaned, not that it leaned
 * by the right fraction of the right ceiling for the turn it was making. So it is asserted here, by
 * enumeration, against a scripted hillside and a scripted motion, with no world and no thread.
 *
 * <p>Every ceiling below is a round number and every input is exactly a half or a whole of its full
 * rate, so each assertion is an exact equality rather than a tolerance. The profile turns ten degrees a
 * tick and accelerates a quarter of a block per tick squared, which is what makes that possible.
 */
class TerrainSolverTest {

    private static final float EPSILON = 1.0E-5F;

    /** The surface the scripted vehicle is standing on. Any y would do; a realistic one reads better. */
    private static final double GROUND_Y = 64.0D;

    /** Snaps to its targets, so an assertion is about the target and not about the blend. */
    private static final TerrainResponse SNAPPING =
            VehicleFixtures.snapping(20F, 24F, 16F, 1F, 4);

    private final TerrainSample sample = new TerrainSample();

    // ------------------------------------------------------------------ the bank

    @Test
    void aFullRateTurnLeansByTheWholeCeilingAndAHalfRateTurnByHalfOfIt() {
        // The headline rule, and the reason one number is enough to describe a vehicle's lean: the
        // response states the roll at a full-rate turn and the solver scales it by how hard the vehicle
        // is actually turning. Nothing configures the half; it falls out of the turn being half as hard.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        flat();

        final TerrainSolver.Motion full = turning(profile, 10F);
        final TerrainSolver.Motion half = turning(profile, 5F);
        final TerrainSolver.Motion straight = turning(profile, 0F);

        assertAll(
                () -> assertEquals(20F, full.bankDegrees(), EPSILON),
                () -> assertEquals(10F, half.bankDegrees(), EPSILON),
                () -> assertEquals(0F, straight.bankDegrees(), EPSILON)
        );
    }

    @Test
    void aLeftTurnLeansTheOtherWayAndNeitherDirectionExceedsTheCeiling() {
        // The sign follows the direction the vehicle is going round, and a turn asked for harder than
        // the profile allows is clamped rather than extrapolated — which can happen to a state carried
        // over from a profile that has since been narrowed.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        flat();

        assertAll(
                () -> assertEquals(-20F, turning(profile, -10F).bankDegrees(), EPSILON),
                () -> assertEquals(20F, turning(profile, 40F).bankDegrees(), EPSILON),
                () -> assertEquals(-20F, turning(profile, -40F).bankDegrees(), EPSILON)
        );
    }

    @Test
    void aTurnAcrossTheYawSeamIsAFewDegreesAndNotThreeHundredAndSixty() {
        // The yaw rate is differenced from two normalised headings, so a vehicle that crossed ±180
        // between them has turned five degrees. Unnormalised it would read as a 355-degree turn and the
        // model would slam to its full lean the other way, once, at one bearing.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        flat();

        final TerrainSolver.Motion motion = TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample,
                profile, new VehicleState(0F, 0F, 177.5F), new VehicleState(0F, 0F, -177.5F), 1F, true);

        assertEquals(10F, motion.bankDegrees(), EPSILON);
    }

    @Test
    void aStrafingVehicleLeansFromItsSideSpeedInstead() {
        // The other denominator, and the one VehicleProfile's bank-drivable check exists to protect: a
        // strafing vehicle never yaws, so there is no turn rate to scale by and the side speed is the
        // input. Sliding to its left is going left, so it leans left.
        final VehicleProfile profile =
                VehicleFixtures.strafingTerrainProfile(VehicleKind.GROUND, SNAPPING);
        flat();

        final TerrainSolver.Motion full = TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample,
                profile, VehicleState.STOPPED, new VehicleState(0F, 0.5F, 0F), 1F, true);
        final TerrainSolver.Motion half = TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample,
                profile, VehicleState.STOPPED, new VehicleState(0F, -0.25F, 0F), 1F, true);

        assertAll(
                () -> assertEquals(-20F, full.bankDegrees(), EPSILON),
                () -> assertEquals(10F, half.bankDegrees(), EPSILON)
        );
    }

    @Test
    void everyKindBanks() {
        // Stated on TerrainResponse and worth pinning: the lean is the one term no kind opts out of.
        // A flyer rolls into its turn, a boat rolls with its wheel, a submarine rolls as it comes round.
        flat();
        for (final VehicleKind kind : VehicleKind.values()) {
            final VehicleProfile profile = VehicleFixtures.terrainProfile(kind, SNAPPING);
            assertEquals(20F, turning(profile, 10F).bankDegrees(), EPSILON,
                    "a " + kind.key() + " should bank into a full-rate turn");
        }
    }

    // ------------------------------------------------------------------ the pitch, per kind

    @Test
    void onlyAGroundVehicleTakesItsPitchFromTheSlope() {
        // Read from VehicleKind.followsSlope(), not from a switch. A flyer that pitched to match the
        // ground it was passing over reads as a bug rather than as flight.
        surface(GROUND_Y + 0.5D);

        for (final VehicleKind kind : VehicleKind.values()) {
            final VehicleProfile profile = VehicleFixtures.terrainProfile(kind, SNAPPING);
            final TerrainSolver.Motion motion = stillOn(profile);
            if (kind.followsSlope()) {
                // Half a block of rise over the one-block run is half of a forty-five-degree grade.
                assertEquals(-12F, motion.pitchDegrees(), EPSILON,
                        kind.key() + " follows the slope, nose up on a climb");
            } else {
                assertEquals(0F, motion.pitchDegrees(), EPSILON,
                        kind.key() + " has no surface beneath it to take an angle from");
            }
        }
    }

    @Test
    void everyKindButGroundPitchesFromItsOwnAcceleration() {
        // The complement, and the reason a car is the exception: its pitch is the hill it is on, and
        // adding acceleration squat on top would fight it.
        flat();

        for (final VehicleKind kind : VehicleKind.values()) {
            final VehicleProfile profile = VehicleFixtures.terrainProfile(kind, SNAPPING);
            // A quarter of a block per tick gained in one tick is exactly the profile's full
            // acceleration rate, so the fraction of full input is one.
            final TerrainSolver.Motion motion = TerrainSolver.respond(TerrainAttitude.LEVEL,
                    this.sample, profile, VehicleState.STOPPED, new VehicleState(0.25F, 0F, 0F), 1F,
                    true);
            if (kind.pitchesFromAcceleration()) {
                assertEquals(-16F, motion.pitchDegrees(), EPSILON,
                        kind.key() + " squats its stern under full acceleration");
            } else {
                assertEquals(0F, motion.pitchDegrees(), EPSILON,
                        "a " + kind.key() + " vehicle's pitch is the hill it is on");
            }
        }
    }

    @Test
    void slowingDownNosesTheOtherWayAndUsesTheBrakingRate() {
        // The denominator follows the regime, matching the two rates VehicleSolver integrates in. The
        // braking rate here is twice the acceleration rate, so losing a quarter of a block a tick is
        // half of full braking and the pitch is half the ceiling, the other way up.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.BOAT, SNAPPING);
        flat();

        final TerrainSolver.Motion motion = TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample,
                profile, new VehicleState(0.5F, 0F, 0F), new VehicleState(0.25F, 0F, 0F), 1F, true);

        assertEquals(8F, motion.pitchDegrees(), EPSILON);
    }

    @Test
    void drivingOffALedgeNosesDownAndAWallDoesNotRearTheVehicleUp() {
        // A descent is a slope and is honoured to the full ceiling. A rise taller than the vehicle could
        // ever take is a wall, and the answer is level rather than a clamped maximum: a cart stopped
        // dead by a cliff face that stood on its back end would read as the physics being broken.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);

        surface(GROUND_Y - 1.0D);
        final TerrainSolver.Motion descending = stillOn(profile);
        surface(GROUND_Y + 3.0D);
        final TerrainSolver.Motion wall = stillOn(profile);

        assertAll(
                () -> assertEquals(24F, descending.pitchDegrees(), EPSILON),
                () -> assertEquals(0F, wall.pitchDegrees(), EPSILON)
        );
    }

    // ------------------------------------------------------------------ the blend

    @Test
    void anAngleClosesTheConfiguredShareOfWhatIsLeftEachTickAndConverges() {
        // What stops a vehicle snapping to a new lean the instant the terrain changes under it. A blend
        // of a half closes half the remaining difference per tick, which is exact in binary — so this is
        // an equality rather than a trend, and the monotone approach is asserted rather than eyeballed.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND,
                new TerrainResponse(20F, 24F, 16F, 1F, 4, 0.5F));
        flat();

        TerrainAttitude attitude = TerrainAttitude.LEVEL;
        final float[] expected = {10F, 15F, 17.5F, 18.75F};
        for (int tick = 0; tick < expected.length; tick++) {
            final TerrainSolver.Motion motion = TerrainSolver.respond(attitude, this.sample, profile,
                    new VehicleState(0F, 0F, 0F), new VehicleState(0F, 0F, 10F), 1F, true);
            assertEquals(expected[tick], motion.bankDegrees(), EPSILON,
                    "tick " + (tick + 1) + " of a blend toward twenty degrees");
            attitude = motion.next();
        }
    }

    @Test
    void oneStepOfTwoTicksBlendsTheSameAmountAsTwoStepsOfOne() {
        // Geometric in the tick count, like the solver's friction and for the same reason: a control
        // loop that missed a tick has to produce a vehicle that reacted late rather than one sitting at
        // a different angle. A linear blend would overshoot here by a quarter of the difference.
        assertEquals(
                TerrainSolver.blendToward(TerrainSolver.blendToward(0F, 20F, 0.5F, 1F), 20F, 0.5F, 1F),
                TerrainSolver.blendToward(0F, 20F, 0.5F, 2F),
                EPSILON);
    }

    @Test
    void aBlendOfOneSnapsAndNothingEverOvershoots() {
        assertAll(
                () -> assertEquals(20F, TerrainSolver.blendToward(0F, 20F, 1F, 1F), EPSILON),
                () -> assertEquals(20F, TerrainSolver.blendToward(0F, 20F, 1F, 7F), EPSILON),
                () -> assertTrue(TerrainSolver.blendToward(0F, 20F, 0.9F, 1F) <= 20F),
                () -> assertTrue(TerrainSolver.blendToward(20F, 0F, 0.9F, 1F) >= 0F)
        );
    }

    // ------------------------------------------------------------------ the step

    @Test
    void aBlockOfRiseIsSpreadOverTheConfiguredDurationAndNoFurther() {
        // The protocol fact this layer exists for: a client interpolates toward the transform it was
        // last given over the window it was given, so a whole block delivered in one tick snaps however
        // good the maths was. Four ticks of a quarter of a block each is what buys the smoothness, and
        // the total has to be exactly the block — a rise that overshoots leaves the vehicle floating.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        surface(GROUND_Y + 1.0D);

        TerrainAttitude carried = TerrainAttitude.LEVEL;
        double climbed = 0.0D;
        for (int tick = 0; tick < 4; tick++) {
            final TerrainSolver.Motion motion = climbing(carried, profile);
            assertEquals(0.25D, motion.rise(), EPSILON, "tick " + (tick + 1) + " of a four-tick climb");
            climbed += motion.rise();
            carried = motion.next();
        }

        final TerrainAttitude finished = carried;
        final double total = climbed;
        final TerrainSolver.Motion after = climbing(finished, profile);
        assertAll(
                () -> assertEquals(1.0D, total, EPSILON),
                () -> assertFalse(finished.isClimbing(), "the climb has to finish, not approach"),
                () -> assertEquals(0.25D, after.rise(), EPSILON,
                        "and the next block is a new climb, armed because the first one finished")
        );
    }

    @Test
    void aStepTallerThanTheProfilePermitsIsNotClimbedAtAll() {
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        surface(GROUND_Y + 1.5D);

        final TerrainSolver.Motion motion = climbing(TerrainAttitude.LEVEL, profile);

        assertAll(
                () -> assertFalse(motion.hasRise()),
                () -> assertFalse(motion.next().isClimbing())
        );
    }

    @Test
    void onlyAKindThatClimbsStepsEverRises() {
        // profile.climbs() is the conjunction of a kind that climbs and a height to climb, which is why
        // it is asked rather than the two halves separately. A hull does not climb a block.
        surface(GROUND_Y + 1.0D);

        for (final VehicleKind kind : VehicleKind.values()) {
            final VehicleProfile profile = VehicleFixtures.terrainProfile(kind, SNAPPING);
            final TerrainSolver.Motion motion = climbing(TerrainAttitude.LEVEL, profile);
            assertEquals(kind.climbsSteps(), motion.hasRise(),
                    "a " + kind.key() + " vehicle climbing a block");
        }
    }

    @Test
    void aStepThatCannotInterpolateCarriesNoRiseAndForgetsNothing() {
        // The MoveKind distinction reaching the terrain: a displacement going through the asynchronous
        // teleport applies an absolute position with no interpolation at all, so a block of rise inside
        // one would snap. The climb is kept rather than cancelled, so it resumes on the next step that
        // can carry it.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        surface(GROUND_Y + 1.0D);

        final TerrainSolver.Motion withheld = TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample,
                profile, VehicleState.STOPPED, VehicleState.STOPPED, 1F, false);
        final TerrainSolver.Motion resumed = climbing(withheld.next(), profile);

        assertAll(
                () -> assertFalse(withheld.hasRise()),
                () -> assertTrue(withheld.next().isClimbing(), "the climb is deferred, not abandoned"),
                () -> assertEquals(1F, withheld.next().climbRemainingBlocks(), EPSILON),
                () -> assertEquals(0.25D, resumed.rise(), EPSILON)
        );
    }

    @Test
    void aClimbInFlightIsNotReArmedWhileItRuns() {
        // Re-arming would replace the remaining distance with the full rise every step, so the same
        // block would be spread over the same duration again and again and the vehicle would approach
        // the top of it forever — with no symptom except a step it never finishes.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        surface(GROUND_Y + 1.0D);

        final TerrainSolver.Motion first = climbing(TerrainAttitude.LEVEL, profile);
        final TerrainSolver.Motion second = climbing(first.next(), profile);

        assertAll(
                () -> assertEquals(0.75F, first.next().climbRemainingBlocks(), EPSILON),
                () -> assertEquals(0.5F, second.next().climbRemainingBlocks(), EPSILON)
        );
    }

    // ------------------------------------------------------------------ no sample

    @Test
    void aVehicleWithNoTerrainReadingArmsNothingAndInventsNothing() {
        // What a host with no world answers, and what the real host answers for a look-ahead probe into
        // a region it does not own. Nothing is armed and the angles that depend on the ground stay where
        // they were rather than snapping flat — which is the visible failure a refused probe must not
        // cause, since it happens every time a vehicle crosses a chunk boundary.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        final TerrainAttitude leaning = new TerrainAttitude(0F, -12F, 0F, 0F);
        this.sample.unavailable();

        final TerrainSolver.Motion motion = TerrainSolver.respond(leaning, this.sample, profile,
                VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        assertAll(
                () -> assertFalse(this.sample.isKnown()),
                () -> assertFalse(motion.hasRise()),
                () -> assertFalse(motion.next().isClimbing()),
                // The pitch blends toward zero because no slope is being reported, which is honest: with
                // a snapping blend it reaches it in one step. What it must not do is arm a climb or
                // produce a rise against ground nobody read.
                () -> assertEquals(0F, motion.pitchDegrees(), EPSILON)
        );
    }

    @Test
    void aClimbAlreadyInFlightSurvivesAProbeThatCouldNotBeTaken() {
        // The asymmetry that matters. A reading that was never taken must not cancel a climb decided
        // against terrain that was read: abandoning it half-way leaves the vehicle sunk into the block
        // it was climbing onto, which is worse than finishing a climb whose last tick nobody re-checked.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        this.sample.unavailable();

        final TerrainSolver.Motion motion = TerrainSolver.respond(
                new TerrainAttitude(0F, 0F, 0.5F, 0.25F), this.sample, profile,
                VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        assertEquals(0.25D, motion.rise(), EPSILON);
    }

    @Test
    void groundThatWasReadAndHoldsNothingArmsNothing() {
        // Distinct from an unavailable reading, and the distinction is the reason the sample carries
        // both: a vehicle over a chasm has an answer, and the answer is that there is nothing to climb.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        this.sample.set(GROUND_Y, TerrainSample.NO_SURFACE, true, GROUND_Y + 1.0D);

        final TerrainSolver.Motion motion = climbing(TerrainAttitude.LEVEL, profile);

        assertAll(
                () -> assertTrue(this.sample.isKnown()),
                () -> assertFalse(this.sample.isSupported()),
                () -> assertEquals(0.0D, this.sample.rise(), EPSILON),
                () -> assertFalse(motion.hasRise()),
                () -> assertEquals(0F, motion.pitchDegrees(), EPSILON)
        );
    }

    // ------------------------------------------------------------------ the degenerate step

    @Test
    void noElapsedTimeChangesNothingAtAll() {
        // The first step of a vehicle's life has nothing to difference against, and a loop asked twice
        // inside one tick must not climb twice as fast as one asked once.
        final VehicleProfile profile = VehicleFixtures.terrainProfile(VehicleKind.GROUND, SNAPPING);
        surface(GROUND_Y + 1.0D);
        final TerrainAttitude attitude = new TerrainAttitude(5F, -3F, 0.5F, 0.25F);

        final TerrainSolver.Motion motion = TerrainSolver.respond(attitude, this.sample, profile,
                VehicleState.STOPPED, VehicleState.STOPPED, 0F, true);

        assertAll(
                () -> assertSame(attitude, motion.next()),
                () -> assertFalse(motion.hasRise()),
                () -> assertEquals(5F, motion.bankDegrees(), EPSILON),
                () -> assertEquals(-3F, motion.pitchDegrees(), EPSILON)
        );
    }

    @Test
    void anInertResponseLeavesTheModelFlatWhateverTheTerrainSays() {
        // Every vehicle written before this layer carries TerrainResponse.NONE, and the whole of the
        // compatibility claim is that such a vehicle behaves exactly as it did: no lean, no pitch, no
        // climb, on any ground.
        final VehicleProfile profile =
                VehicleFixtures.terrainProfile(VehicleKind.GROUND, TerrainResponse.NONE);
        surface(GROUND_Y + 1.0D);

        final TerrainSolver.Motion motion = turning(profile, 10F);

        assertAll(
                () -> assertEquals(0F, motion.bankDegrees()),
                () -> assertEquals(0F, motion.pitchDegrees()),
                () -> assertFalse(motion.hasRise()),
                () -> assertTrue(motion.next().isLevel())
        );
    }

    // ------------------------------------------------------------------ the emission gate

    @Test
    void anAngleThatMovedLessThanTheWiresOwnResolutionIsNotWorthAPacket() {
        // A rotation travels as one byte per axis, so the finest rotation a client can be told about is
        // a three-hundred-and-sixtieth of a turn divided by 256. Two angles inside one of those are the
        // same rotation downstream, and sending both pays full price for nothing.
        assertAll(
                () -> assertFalse(TerrainSolver.isVisiblyDifferent(0F, 0.7F)),
                () -> assertTrue(TerrainSolver.isVisiblyDifferent(0F, 1.5F)),
                () -> assertFalse(TerrainSolver.isVisiblyDifferent(12F, 12F)),
                () -> assertTrue(TerrainSolver.isVisiblyDifferent(12F, 14F))
        );
    }

    // ------------------------------------------------------------------ helpers

    /** A sample of flat ground under and ahead of the vehicle. */
    private void flat() {
        surface(GROUND_Y);
    }

    /** A sample whose column ahead has its surface at {@code aheadY}. */
    private void surface(double aheadY) {
        this.sample.set(GROUND_Y, GROUND_Y, true, aheadY);
    }

    /** One step of a vehicle standing still on the sampled ground. */
    private TerrainSolver.Motion stillOn(VehicleProfile profile) {
        return TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample, profile,
                VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);
    }

    /** One step of a vehicle with a climb permitted, continuing from {@code attitude}. */
    private TerrainSolver.Motion climbing(TerrainAttitude attitude, VehicleProfile profile) {
        return TerrainSolver.respond(attitude, this.sample, profile,
                VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);
    }

    /** One step of a vehicle whose heading moved by {@code yawRate} degrees over one tick. */
    private TerrainSolver.Motion turning(VehicleProfile profile, float yawRate) {
        return TerrainSolver.respond(TerrainAttitude.LEVEL, this.sample, profile,
                new VehicleState(0F, 0F, 0F), new VehicleState(0F, 0F, yawRate), 1F, true);
    }
}
