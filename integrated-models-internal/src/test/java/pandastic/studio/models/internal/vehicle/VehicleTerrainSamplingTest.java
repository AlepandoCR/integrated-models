package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many times a real control step reads the ground, before and after the sampling rules.
 *
 * <h2>Why a count is the assertion</h2>
 * Because the thing being fixed is a cost, and a cost claimed without a before and an after is an
 * opinion. Every test in the first half runs the same vehicle twice — once with
 * {@link TerrainSampling#EVERY_STEP}, which is what every vehicle did before these keys existed, and once
 * with a configured rule — and asserts both figures. The probe count comes from {@link FakeVehicleHost},
 * so it counts the calls that would have walked a column of the world on a real server.
 *
 * <h2>What a reading actually costs, which is what makes the counts mean something</h2>
 * {@code EntityVehicleHost#probeTerrain} scans the column the vehicle is in and the column one block
 * ahead of it, from the profile's reach above the vehicle to the same distance below, stopping at the
 * first block that is not passable. At the reach a one-block step height implies that is three candidate
 * reads in its own column and four in the column ahead — five actual reads on flat ground, seven at
 * worst. So a probe count here is a block-read count times five, and the figure that worried the owner
 * is that times twenty steps a second times the number of vehicles on the server.
 *
 * <h2>And the halves that are not about counting</h2>
 * A suppressed reading is a state the rest of the layer has to handle, so the second half of this suite
 * is about that: that the model keeps moving on a reading it did not take, that the old reading cannot
 * arm the same climb twice, and that there is one buffer per vehicle rather than one per step.
 */
class VehicleTerrainSamplingTest {

    private static final float EPSILON = 1.0E-5F;

    /** The surface the scripted vehicle stands on. */
    private static final double GROUND_Y = 64.0D;

    /**
     * A rule with every figure deliberately unusual: a quarter block, fifteen degrees, two ticks, forty.
     *
     * <p>The viewer gate is off here, so that the counting tests are about the deadband and the interval
     * and not about who is watching. The two tests that are about the viewer gate switch it on.
     */
    private static final TerrainSampling SAMPLING = new TerrainSampling(0.25F, 15F, 2, 40, false);

    /**
     * A cart that leans and follows a slope and <strong>cannot climb</strong>.
     *
     * <p>The step height is zero on purpose for most of this suite: a climb writes a vertical
     * displacement, which moves the vehicle, which is itself a reason to take a reading — so a suite
     * about when readings are taken would otherwise be measuring the climb. The one test that is about
     * the climb uses its own response.
     */
    private static final TerrainResponse FLAT_RESPONSE = new TerrainResponse(20F, 24F, 0F, 0F, 1, 0.2F);

    private final FakeVehicleHost host = new FakeVehicleHost();
    private final FakeVehicleVisual visual =
            new FakeVehicleVisual(this.host.sharedJournal()).locators(VehicleFixtures.locators());
    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();
    private final List<Throwable> logged = new ArrayList<>();
    private final AtomicInteger stoppedCount = new AtomicInteger();

    // ------------------------------------------------------------------ the counts

    @Test
    void aParkedVehicleReadsTheGroundOnceInsteadOfOncePerStep() {
        // The owner's case, and the one that dominates a real server: most vehicles are standing still
        // most of the time, and the ground under a vehicle that has not moved cannot have changed by the
        // vehicle's own doing. Twenty steps is one second of a parked cart.
        final int before = probesOverParkedSteps(TerrainSampling.EVERY_STEP, 20);
        final int after = probesOverParkedSteps(SAMPLING, 20);

        assertAll(
                () -> assertEquals(20, before, "one reading per step is what it used to cost"),
                () -> assertEquals(0, after,
                        "a parked vehicle re-reads nothing; the age forces one at tick 40")
        );
    }

    @Test
    void aParkedVehicleStillNoticesTheWorldChangingUnderIt() {
        // The other half of the deadband, and the reason it is safe rather than merely cheap: what
        // changed may be the world. Somebody builds a ramp against a parked cart and the cart's own
        // motion will never notice, so the reading expires on its own.
        this.host.terrain(GROUND_Y, GROUND_Y);
        primed(terrainProfile(), SAMPLING);
        this.host.clearProbeCount();

        for (int step = 0; step < 39; step++) {
            this.clock.advanceTicks(1);
            this.host.runOnce();
        }
        final int duringTheAge = this.host.probeCount();

        // Past the fortieth tick since the last reading, which is this rule's two seconds. The hillside
        // moved while the vehicle did not, which is the one change a movement deadband cannot see.
        this.host.terrainAhead(GROUND_Y + 1.0D);
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(0, duringTheAge),
                () -> assertEquals(1, this.host.probeCount())
        );
    }

    @Test
    void aCrawlingVehicleReadsOnceEveryFewStepsInsteadOfEveryStep() {
        // A vehicle being driven slowly, which is the second common state: a tenth of a block a tick
        // crosses a quarter-block deadband every third step, so two steps in three cost nothing. The
        // interval cannot be the thing suppressing them, because it is two ticks and these steps are one.
        final int before = probesOverDrivenSteps(TerrainSampling.EVERY_STEP, 30);
        final int after = probesOverDrivenSteps(SAMPLING, 30);

        assertAll(
                () -> assertEquals(30, before),
                // Nine rather than ten, because the first step of the stretch is still accelerating from
                // a stop and so moves less than a tenth. Three times fewer block reads for a vehicle
                // that is being driven, which is the case the deadband was least expected to help with.
                () -> assertEquals(9, after,
                        () -> "a tenth of a block a tick should read about every third step, not "
                                + after + " times in 30")
        );
    }

    @Test
    void aVehicleCoveringAWholeColumnPerStepStillReadsEveryStepAndThatIsTheLookAheadsLimit() {
        // The honest statement of what cannot be reduced. The probe looks exactly one column ahead, so a
        // vehicle travelling a column per step is always standing in the column its last reading
        // described — and the gate's own override forces a reading whatever the interval says, because
        // acting on that reading would be arming a step up for ground already behind it. A server that
        // wants this cheaper has to lower the vehicle's speed, not the sample rate.
        final int before = probesOverDrivenSteps(TerrainSampling.EVERY_STEP, 10,
                VehicleFixtures.terrainProfile(VehicleKind.GROUND, FLAT_RESPONSE));
        final int after = probesOverDrivenSteps(SAMPLING, 10,
                VehicleFixtures.terrainProfile(VehicleKind.GROUND, FLAT_RESPONSE));

        assertAll(
                () -> assertEquals(10, before),
                // Eight of the ten. The two that are suppressed are the opening steps, while the vehicle
                // is still accelerating and has not yet covered a column in a tick; from the step it
                // reaches a block a tick onward, every single step reads. Which is the claim: at that
                // speed the sampling rules give a server nothing, and the lever that would is the speed.
                () -> assertEquals(8, after, "a block a tick is a column a tick")
        );
    }

    // ------------------------------------------------------------------ the viewer gate

    @Test
    void aVehicleNoViewerHoldsReadsNoBlocksAtAll() {
        // The largest saving, and the one the culler earns: what the terrain reaction produces is a lean,
        // a pitch and a step up on a model, so for a model nobody holds the right number of block reads
        // is zero. The vehicle is being driven throughout, which is the point — this is not the deadband
        // suppressing a parked cart, it is a moving vehicle nobody can see.
        final TerrainSampling whenSeen = new TerrainSampling(0.25F, 15F, 2, 40, true);

        final int probes = countProbes(whenSeen, 20, terrainProfile(), true, false);

        assertEquals(0, probes);
    }

    @Test
    void anUnseenVehicleStartsReadingAgainOnTheFirstStepSomebodyCanSeeIt() {
        // The repair, which is what makes the trade acceptable: an unseen vehicle does not climb, so one
        // driven at a kerb stops at it — and the first step a viewer holds it, it reads the ground, arms
        // the step and drives on. What a player arriving finds is a vehicle that moves, not one stuck.
        final TerrainSampling whenSeen = new TerrainSampling(0.25F, 15F, 2, 40, true);
        this.visual.hasViewers(false);
        final VehicleImpl vehicle = primed(terrainProfile(), whenSeen);
        drive(vehicle);
        this.host.clearProbeCount();
        this.clock.advanceTicks(1);
        this.host.runOnce();
        final int whileUnseen = this.host.probeCount();

        this.visual.hasViewers(true);
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(0, whileUnseen),
                () -> assertEquals(1, this.host.probeCount())
        );
    }

    @Test
    void anUnseenVehicleKeepsReadingWhenTheOperatorSwitchedTheViewerGateOff() {
        // The key is the operator's, and the reason to switch it off is a server whose vehicles drive
        // themselves across terrain nobody is watching. Off, nothing about viewers is consulted at all.
        assertTrue(countProbes(SAMPLING, 10, terrainProfile(), true, false) > 0);
    }

    // ------------------------------------------------------------------ the suppressed reading

    @Test
    void aModelKeepsMovingTowardTheAngleAReadingItDidNotTakeImplies() {
        // The answer to "interpolate the response between samples", and the reason no second mechanism
        // was added for it: the angle blend already does this. It closes a fraction of the remaining
        // difference per measured tick, so on a step with no new reading the model carries on toward the
        // target the previous one set — a fifth of the way, every tick, at this profile's blend.
        //
        // The failure this rules out is the one that would have made the deadband unusable: a suppressed
        // reading treated as "no reading" blends the model flat, so a parked vehicle on a slope would
        // settle level and snap back the moment a reading was taken.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 0.5D);
        final VehicleImpl vehicle = primed(terrainProfile(), SAMPLING);
        this.host.clearProbeCount();

        final float[] pitches = new float[4];
        for (int step = 0; step < pitches.length; step++) {
            this.clock.advanceTicks(1);
            this.host.runOnce();
            pitches[step] = vehicle.getAttitude().pitchDegrees();
        }

        assertAll(
                () -> assertEquals(0, this.host.probeCount(), "every one of those steps was suppressed"),
                // Half a block of rise over a one-block run is half of the 24-degree ceiling, nose up.
                () -> assertTrue(pitches[0] < 0F, () -> "it started to pitch: " + pitches[0]),
                () -> assertTrue(pitches[1] < pitches[0], "and kept going on a reading it did not take"),
                () -> assertTrue(pitches[2] < pitches[1]),
                () -> assertTrue(pitches[3] < pitches[2]),
                () -> assertTrue(pitches[3] > -12F, "still approaching the target rather than past it")
        );
    }

    @Test
    void oneBufferPerVehicleIsFilledInPlaceRatherThanOnePerStep() {
        // The allocation claim, and the only form of it that can be checked without measuring a heap:
        // every probe is handed the same object. That is also what makes the cheap path possible at all —
        // a suppressed step works because the previous reading is still sitting in the buffer.
        final VehicleImpl vehicle = primed(terrainProfile(), TerrainSampling.EVERY_STEP);
        drive(vehicle);
        final TerrainSample first = this.host.lastProbedSample();

        for (int step = 0; step < 5; step++) {
            this.clock.advanceTicks(1);
            this.host.runOnce();
        }

        assertAll(
                () -> assertSame(first, this.host.lastProbedSample()),
                () -> assertTrue(this.host.probeCount() >= 5, "it really did probe on those steps")
        );
    }

    // ------------------------------------------------------------------ the reading that is reused

    @Test
    void aReusedReadingNeverArmsTheSameStepUpASecondTime() {
        // The defect the reuse flag exists to prevent, and the reason a sampling rule could not simply be
        // bolted onto the solver. A climb adds a rise to the vehicle's own position, so arming one twice
        // from one reading is a block of height nobody measured: a parked vehicle facing a step would
        // climb it, finish, find the same reading still saying "one block up", and ascend for ever — with
        // every individual decision correct.
        final VehicleProfile climbing = VehicleFixtures.terrainProfile(
                VehicleKind.GROUND, VehicleFixtures.snapping(0F, 0F, 0F, 1F, 1));
        final TerrainSample sample = new TerrainSample();
        sample.set(GROUND_Y, GROUND_Y, true, GROUND_Y + 1.0D);

        // The whole climb, from a reading taken that step: one tick of duration, so it finishes at once.
        final TerrainSolver.Motion armed = TerrainSolver.respond(TerrainAttitude.LEVEL, sample,
                climbing, VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        // The same reading, one step later, with nothing re-read.
        sample.markReused();
        final TerrainSolver.Motion second = TerrainSolver.respond(armed.next(), sample,
                climbing, VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        assertAll(
                () -> assertEquals(1.0D, armed.rise(), EPSILON, "the climb it was armed for"),
                () -> assertFalse(armed.next().isClimbing(), "a one-tick climb finishes in one tick"),
                () -> assertEquals(0.0D, second.rise(), EPSILON,
                        "a reading from an earlier step must not arm a second climb"),
                () -> assertFalse(second.next().isClimbing())
        );
    }

    @Test
    void aClimbAlreadyInFlightKeepsAdvancingOnAReusedReading() {
        // The other side of that gate, and it is not symmetric. A climb in flight was armed from a
        // reading that was taken, and the rise it is applying is already decided — abandoning it half way
        // would leave the vehicle sunk into the block it was climbing onto, which is worse than finishing
        // a climb whose last tick nobody re-checked.
        final VehicleProfile climbing = VehicleFixtures.terrainProfile(
                VehicleKind.GROUND, VehicleFixtures.snapping(0F, 0F, 0F, 1F, 4));
        final TerrainSample sample = new TerrainSample();
        sample.set(GROUND_Y, GROUND_Y, true, GROUND_Y + 1.0D);

        final TerrainSolver.Motion armed = TerrainSolver.respond(TerrainAttitude.LEVEL, sample,
                climbing, VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);
        sample.markReused();
        final TerrainSolver.Motion continued = TerrainSolver.respond(armed.next(), sample,
                climbing, VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        assertAll(
                () -> assertEquals(0.25D, armed.rise(), EPSILON, "a quarter of the block per tick"),
                () -> assertEquals(0.25D, continued.rise(), EPSILON, "and the next quarter"),
                () -> assertTrue(continued.next().isClimbing())
        );
    }

    @Test
    void aSlopeIsAnsweredFromAReusedReadingLikeAnyOther() {
        // Because a pitch is a target the model blends toward rather than a displacement it applies, so
        // acting on the same hillside twice is acting on the same hillside twice. Only the climb is
        // gated, and this is what says so.
        final VehicleProfile ground = VehicleFixtures.terrainProfile(
                VehicleKind.GROUND, VehicleFixtures.snapping(0F, 24F, 0F, 0F, 1));
        final TerrainSample sample = new TerrainSample();
        sample.set(GROUND_Y, GROUND_Y, true, GROUND_Y + 0.5D);
        sample.markReused();

        final TerrainSolver.Motion motion = TerrainSolver.respond(TerrainAttitude.LEVEL, sample,
                ground, VehicleState.STOPPED, VehicleState.STOPPED, 1F, true);

        assertEquals(-12F, motion.pitchDegrees(), EPSILON);
    }

    // ------------------------------------------------------------------ helpers

    /** The cart this suite drives: ground, leaning, slope-following, unable to climb. */
    private static VehicleProfile terrainProfile() {
        return VehicleProfile.builder(VehicleFixtures.VEHICLE_BOUNDS)
                // A tenth of a block a tick, which crosses a quarter-block deadband every third step.
                .forwardSpeed(0.1F)
                .reverseSpeed(0.1F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .kind(VehicleKind.GROUND)
                .terrain(FLAT_RESPONSE)
                .build();
    }

    /**
     * How many readings {@code steps} one-tick steps take, on a vehicle of this suite's own.
     *
     * <p>Its own host, visual and clock rather than the test's fields, because every counting test runs
     * the same vehicle twice — once at {@link TerrainSampling#EVERY_STEP} and once at a configured rule —
     * and two vehicles sharing a host would share a position and a scheduled body. A before and an after
     * measured on one object would not be a before and an after.
     *
     * @param sampling how often a reading may be taken
     * @param steps    how many one-tick control steps to run
     * @param profile  the vehicle to drive
     * @param driven   whether somebody holds the throttle, as against a parked vehicle
     * @param seen     whether any viewer holds the model
     * @return the readings taken after the seeding step
     */
    private int countProbes(
            TerrainSampling sampling,
            int steps,
            VehicleProfile profile,
            boolean driven,
            boolean seen
    ) {
        final FakeVehicleHost ownHost = new FakeVehicleHost();
        final FakeVehicleVisual ownVisual = new FakeVehicleVisual(ownHost.sharedJournal())
                .locators(VehicleFixtures.locators())
                .hasViewers(seen);
        final VehicleFixtures.SteppableClock ownClock = new VehicleFixtures.SteppableClock();
        ownHost.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y);

        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), profile, ownHost, ownVisual,
                VehicleSettingsSource.fixed(VehicleFixtures.settingsWithSampling(sampling)), ownClock,
                (message, thrown) -> this.logged.add(thrown), SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());
        assertTrue(vehicle.start());
        // The seeding step: no elapsed time, and the one reading a vehicle's first step always takes.
        ownHost.runOnce();
        if (driven) {
            ownHost.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
            assertTrue(vehicle.board(StubPlayer.named("driver")).isAboard());
        }
        ownHost.clearProbeCount();

        for (int step = 0; step < steps; step++) {
            ownClock.advanceTicks(1);
            ownHost.runOnce();
        }
        return ownHost.probeCount();
    }

    /** How many readings {@code steps} one-tick steps of a parked vehicle take. */
    private int probesOverParkedSteps(TerrainSampling sampling, int steps) {
        return countProbes(sampling, steps, terrainProfile(), false, true);
    }

    /** How many readings {@code steps} one-tick steps of a vehicle being driven forward take. */
    private int probesOverDrivenSteps(TerrainSampling sampling, int steps) {
        return probesOverDrivenSteps(sampling, steps, terrainProfile());
    }

    private int probesOverDrivenSteps(TerrainSampling sampling, int steps, VehicleProfile profile) {
        return countProbes(sampling, steps, profile, true, true);
    }

    /**
     * A started vehicle whose clock has been seeded by one step at zero elapsed time.
     *
     * <p>That first step takes a reading, because a vehicle's first step has nothing to reuse. Every
     * count here is taken after it and after {@link FakeVehicleHost#clearProbeCount()}, so the figures
     * are about steady driving rather than about the seeding.
     */
    private VehicleImpl primed(VehicleProfile profile, TerrainSampling sampling) {
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), profile, this.host,
                this.visual, VehicleSettingsSource.fixed(
                        VehicleFixtures.settingsWithSampling(sampling)), this.clock,
                (message, thrown) -> this.logged.add(thrown), SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());
        assertTrue(vehicle.start(), "the fixture host should allow a start");
        this.host.runOnce();
        this.host.clearJournal();
        return vehicle;
    }

    /** Puts somebody in the driver's seat and has them hold the throttle. */
    private void drive(VehicleImpl vehicle) {
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        assertTrue(vehicle.board(StubPlayer.named("driver")).isAboard(),
                "the fixture rig declares a driver's seat on a locator the fixture model has");
    }
}
