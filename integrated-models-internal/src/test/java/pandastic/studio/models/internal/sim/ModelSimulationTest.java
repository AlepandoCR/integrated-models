package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSimulationTest {

    private static final int SIMULATION_HZ = 60;
    private static final long PERIOD_NANOS = 1_000_000_000L / SIMULATION_HZ;
    private static final int HAIR = 1;

    private final FakeClock clock = new FakeClock();
    private final ScriptedSolver solver = new ScriptedSolver();
    private final RecordingPoseSink sink = new RecordingPoseSink();

    private static Rig rig() {
        return SimFixtures.rig("doll", "model")
                .root("body")
                .bone("hair", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, SimFixtures.PROFILE_BOUNDS, SimFixtures.PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .build())
                .build();
    }

    private ModelSimulation simulation(int emissionHz) {
        return simulation(SimFixtures.settings(this.solver, SIMULATION_HZ, emissionHz));
    }

    private ModelSimulation simulation(SimulationSettings settings) {
        return new ModelSimulation(rig(), SimulationSettingsSource.fixed(settings), this.sink);
    }

    /** One iteration of the loop: advance the clock by a period, then step. */
    private void tick(ModelSimulation simulation) {
        this.clock.advanceNanos(PERIOD_NANOS);
        simulation.step(this.clock.nanoTime());
    }

    private void tick(ModelSimulation simulation, int times) {
        for (int iteration = 0; iteration < times; iteration++) {
            tick(simulation);
        }
    }

    @Test
    void emitsExactlyOncePerCadenceInterval() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 30);

        assertAll(
                () -> assertEquals(3, simulation.cadence().stepsPerEmission()),
                // Thirty steps at one emission every three: ten updates, not thirty. Two thirds of the
                // packets a naive loop would send are the ones the client discards unseen.
                () -> assertEquals(10, this.sink.count()),
                () -> assertEquals(30, this.solver.solveCount(), "every step still simulates"),
                () -> assertEquals(1, this.sink.last().interpolationTicks(),
                        "the window has to cover the 20 Hz interval"));
    }

    @Test
    void aSlowerCadenceDecimatesFurtherAndWidensTheWindow() {
        final ModelSimulation simulation = simulation(10);
        this.solver.neverRest();

        tick(simulation, 30);

        assertAll(
                () -> assertEquals(6, simulation.cadence().stepsPerEmission()),
                () -> assertEquals(5, this.sink.count()),
                () -> assertEquals(2, this.sink.last().interpolationTicks(),
                        "half the rate needs twice the window or the motion stutters"));
    }

    @Test
    void emissionPhaseStartsAtTheFirstStep() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 1);
        assertEquals(1, this.sink.count(), "step zero is an emission step");

        tick(simulation, 2);
        assertEquals(1, this.sink.count(), "the two steps after it are not");

        tick(simulation, 1);
        assertEquals(2, this.sink.count());
    }

    @Test
    void measuredElapsedTimeDrivesTheSolverNotTheIterationCount() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        // Deliberately nothing like a fixed period: the scheduler drops missed iterations and the
        // Windows timer is granular to 1-16 ms, so a real loop never sees an even cadence.
        final long[] gapsMillis = {16L, 4L, 40L, 1L, 250L, 17L};
        for (final long gapMillis : gapsMillis) {
            this.clock.advanceMillis(gapMillis);
            simulation.step(this.clock.nanoTime());
        }

        assertEquals(gapsMillis.length, this.solver.deltaCount());
        assertAll(
                () -> assertEquals(0F, this.solver.deltaAt(0), 1.0E-9F,
                        "the first step has nothing to difference against"),
                () -> assertEquals(0.004F, this.solver.deltaAt(1), 1.0E-6F),
                () -> assertEquals(0.040F, this.solver.deltaAt(2), 1.0E-6F),
                () -> assertEquals(0.001F, this.solver.deltaAt(3), 1.0E-6F),
                () -> assertEquals(0.250F, this.solver.deltaAt(4), 1.0E-6F),
                () -> assertEquals(0.017F, this.solver.deltaAt(5), 1.0E-6F));
    }

    @Test
    void aClockThatDidNotAdvanceReportsNoElapsedTimeRatherThanANegativeOne() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation);
        simulation.step(this.clock.nanoTime());
        this.clock.advanceNanos(-PERIOD_NANOS);
        simulation.step(this.clock.nanoTime());

        assertAll(
                () -> assertEquals(0F, this.solver.deltaAt(1), 1.0E-9F,
                        "a repeated reading is zero elapsed, not a nominal timestep"),
                () -> assertEquals(0F, this.solver.deltaAt(2), 1.0E-9F,
                        "a clock that went backwards must never reach the integrator"));
    }

    @Test
    void goesSilentAtRestAfterEmittingTheSettlingStepExactlyOnce() {
        final ModelSimulation simulation = simulation(20);
        // Moving for six steps, i.e. two emissions, then settled.
        this.solver.restAfter(6);

        tick(simulation, 6);
        final int whileMoving = this.sink.count();

        tick(simulation, 60);

        assertAll(
                () -> assertEquals(2, whileMoving, "steps 0 and 3 emit while it is moving"),
                // Exactly one more: the pose the physics actually settled on. Going quiet one update
                // early would leave every bone a fraction short of its final angle, permanently.
                () -> assertEquals(whileMoving + 1, this.sink.count(),
                        "a resting model costs one settling update and then nothing"),
                () -> assertTrue(simulation.isAtRest()),
                () -> assertEquals(66, simulation.stepCount(),
                        "it keeps stepping, it just stops talking"));
    }

    @Test
    void movementReArmsTheSettlingEmission() {
        final ModelSimulation simulation = simulation(20);

        this.solver.restAfter(0);
        tick(simulation, 30);
        final int firstRest = this.sink.count();

        this.solver.neverRest();
        tick(simulation, 30);
        final int afterMoving = this.sink.count();

        this.solver.restAfter(this.solver.solveCount());
        tick(simulation, 30);

        assertAll(
                () -> assertEquals(1, firstRest, "it starts settled, so one baseline update"),
                () -> assertEquals(11, afterMoving, "then ten while moving"),
                () -> assertEquals(12, this.sink.count(), "and one more when it settles again"));
    }

    @Test
    void restingRightFromTheFirstStepStillPublishesABaselinePose() {
        final ModelSimulation simulation = simulation(20);
        this.solver.restAfter(0);

        tick(simulation, 120);

        assertEquals(1, this.sink.count(),
                "a model that never moves costs one packet, not zero and not forty a second");
    }

    @Test
    void aSinkThatThrowsOnTheSettlingStepGetsAnotherChance() {
        final ModelSimulation simulation = simulation(20);
        this.solver.restAfter(0);
        this.sink.failWith(new IllegalStateException("no connection"));

        // The sink records before throwing, so each of these is a retry rather than a silent drop.
        // Isolating the throw is the loop's job; here it is the subject of the test.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                tick(simulation, 3);
            } catch (final IllegalStateException expected) {
                continue;
            }
            throw new AssertionError("the failing sink should have thrown on attempt " + attempt);
        }
        assertEquals(3, this.sink.count(), "a failed settling update must be retried, not lost");

        this.sink.failWith(null);
        tick(simulation, 3);
        tick(simulation, 30);

        assertEquals(4, this.sink.count(), "once it succeeds, the model goes quiet again");
    }

    @Test
    void clearsTheBasePoseEachStepSoOffsetsDoNotCompound() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 30);

        // The scripted solver adds its offset, exactly as secondary motion does. Without the pose
        // being rewritten every step this would be ten times the offset by the last emission.
        assertEquals(10, this.sink.count());
        for (final RecordingPoseSink.Emission emission : this.sink.emissions()) {
            assertEquals(ScriptedSolver.OFFSET_PER_SOLVE, emission.pose().pitch(0), 1.0E-6F,
                    "the base pose has to be cleared before every solve");
        }
    }

    @Test
    void reusesOneBorrowedPoseBufferRatherThanAllocatingPerEmission() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 3);
        final Pose first = this.sink.lastBorrowed();
        tick(simulation, 3);

        assertSame(first, this.sink.lastBorrowed(),
                "the pose is borrowed, which is only worth saying if it is the same buffer");
    }

    @Test
    void handsTheSolverTheLatestPublishedDriverSnapshot() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation);
        final PhysicsDriver running = new PhysicsDriver(0.2F, 0F, 0F, 0.05F);
        simulation.driver().publish(running);
        tick(simulation);
        simulation.driver().reset();
        tick(simulation);

        assertAll(
                () -> assertSame(PhysicsDriver.STILL, this.solver.drivers().get(0),
                        "before the first sample the owner is treated as motionless"),
                () -> assertSame(running, this.solver.drivers().get(1)),
                () -> assertSame(PhysicsDriver.STILL, this.solver.drivers().get(2)));
    }

    @Test
    void resetSnapsBackAndRestartsBothTheCadencePhaseAndTheClock() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 4);
        assertEquals(2, this.sink.count());

        simulation.reset();
        assertEquals(0, simulation.stepCount());

        // A whole second passes while the model is unbound. The next step must measure zero, not a
        // second, or the solver is handed a step several hundred times its stable size.
        this.clock.advanceSeconds(1.0D);
        simulation.step(this.clock.nanoTime());

        assertAll(
                () -> assertEquals(3, this.sink.count(), "the rebound model emits immediately"),
                () -> assertEquals(0F, this.solver.lastDelta(), 1.0E-9F,
                        "the gap across a rebind describes nothing physical"));
    }

    @Test
    void settlesWithTheRealSolverAndThenStopsCostingBandwidth() {
        final Rig windyRig = SimFixtures.rig("doll", "model")
                .root("body")
                .bone("hair", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, SimFixtures.PROFILE_BOUNDS, SimFixtures.PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .wind(1F)
                        .build())
                .build();
        final ModelSimulation simulation = new ModelSimulation(
                windyRig,
                SimFixtures.source(SimFixtures.realSolver()),
                this.sink);

        simulation.driver().publish(new PhysicsDriver(0.21F, 0F, 0F, 0F));
        tick(simulation, 20);

        assertAll(
                () -> assertFalse(simulation.isAtRest(), "the wind is still deflecting it"),
                () -> assertTrue(this.sink.count() > 1, "a moving model emits on cadence"),
                () -> assertTrue(Math.abs(this.sink.last().pose().pitch(HAIR)) > 1.0E-3F,
                        "the bone should trail the movement, got "
                                + this.sink.last().pose().pitch(HAIR)));

        simulation.driver().reset();
        // A hundred seconds of simulated time: far more than a damped pendulum needs to settle.
        tick(simulation, 6000);
        final int afterSettling = this.sink.count();
        tick(simulation, 600);

        assertAll(
                () -> assertTrue(simulation.isAtRest(), "it has to actually reach rest"),
                () -> assertEquals(afterSettling, this.sink.count(),
                        "ten further seconds at rest must cost zero packets"),
                () -> assertTrue(this.sink.last().pose().isFinite()));
    }

    // ------------------------------------------------------------------------------------------
    // Injected configuration is honoured, and a reload propagates
    // ------------------------------------------------------------------------------------------

    @Test
    void emitsOnTheConfiguredRateWithTheWindowThatRateImplies() {
        // Five hertz is nowhere near the stock twenty: twelve steps between emissions and a four-tick
        // window. A simulation holding a default cadence would emit ten times here, not two, and send
        // a one-tick window.
        final ModelSimulation slow =
                simulation(SimFixtures.settings(this.solver, SIMULATION_HZ, 5));
        this.solver.neverRest();

        tick(slow, 24);

        assertAll(
                () -> assertEquals(5, slow.cadence().emissionHz()),
                () -> assertEquals(12, slow.cadence().stepsPerEmission()),
                () -> assertEquals(2, this.sink.count()),
                () -> assertEquals(4, this.sink.last().interpolationTicks(),
                        "a 5 Hz emission has to cover four client ticks or the motion stutters"),
                () -> assertEquals(24, this.solver.solveCount(), "every step still simulates"));
    }

    @Test
    void aLoopRateThatIsNotSixtyStillProducesAMatchedCadence() {
        // Thirty hertz, which the stock numbers never produce: a 30 Hz loop emitting at 10 Hz is three
        // steps per emission and a two-tick window.
        final ModelSimulation thirty = simulation(SimFixtures.settings(this.solver, 30, 10));
        this.solver.neverRest();

        tick(thirty, 9);

        assertAll(
                () -> assertEquals(3, thirty.cadence().stepsPerEmission()),
                () -> assertEquals(2, thirty.cadence().interpolationTicks()),
                () -> assertEquals(3, this.sink.count()));
    }

    @Test
    void aPublishedCadenceChangeTakesEffectWithoutARebind() {
        final SimFixtures.MutableSource source =
                new SimFixtures.MutableSource(SimFixtures.settings(this.solver, SIMULATION_HZ, 20));
        final ModelSimulation simulation =
                new ModelSimulation(rig(), source, this.sink);
        this.solver.neverRest();

        tick(simulation, 6);
        final int atTwenty = this.sink.count();

        source.publish(SimFixtures.settings(this.solver, SIMULATION_HZ, 10));
        tick(simulation, 6);

        assertAll(
                () -> assertEquals(2, atTwenty, "six steps at one emission every three"),
                () -> assertEquals(atTwenty + 1, this.sink.count(),
                        "and one every six once the rate halves"),
                () -> assertEquals(2, this.sink.last().interpolationTicks(),
                        "the window has to follow the rate, or the motion stutters at the new one"));
    }

    @Test
    void aSubstepRateChangeRebuildsTheSolverState() {
        // This is the propagation that cannot be skipped. A PhysicsState holds per-bone limits and a
        // fractional-time accumulator derived from the step size of the solver that built it, so a
        // reloaded rate leaves every live state calibrated for an integrator that no longer exists.
        // Nothing throws; the bones simply move at the wrong speed.
        final AngularPhysicsSolver first = SimFixtures.realSolver();
        final AngularPhysicsSolver second = new AngularPhysicsSolver(SimFixtures.tuning(120));
        final SimFixtures.MutableSource source =
                new SimFixtures.MutableSource(SimFixtures.settings(first));
        final ModelSimulation simulation = new ModelSimulation(rig(), source, this.sink);

        simulation.driver().publish(new PhysicsDriver(0.21F, 0F, 0F, 0F));
        tick(simulation, 10);
        assertSame(first, simulation.solver());

        source.publish(SimFixtures.settings(second, SIMULATION_HZ, SimFixtures.EMISSION_HZ));
        tick(simulation, 1);

        assertAll(
                () -> assertSame(second, simulation.solver(),
                        "the step must pick up the reloaded calibration"),
                // A rebuild snaps back and restarts the cadence phase, exactly as reset() does: there
                // is no velocity from the old calibration worth blending out of.
                () -> assertEquals(1L, simulation.stepCount(),
                        "the rebuilt simulation restarts its phase rather than keeping the old one"));
    }

    @Test
    void anExplicitRebuildSnapsBackAndRestartsBothThePhaseAndTheClock() {
        final ModelSimulation simulation = simulation(20);
        this.solver.neverRest();

        tick(simulation, 4);
        assertEquals(2, this.sink.count());

        simulation.rebuild();
        assertEquals(0, simulation.stepCount());

        // A second passes while the calibration is being swapped. The next step must measure zero, or
        // the new integrator opens with a step several hundred times its stable size.
        this.clock.advanceSeconds(1.0D);
        simulation.step(this.clock.nanoTime());

        assertAll(
                () -> assertEquals(3, this.sink.count(), "a rebuilt model emits immediately"),
                () -> assertEquals(0F, this.solver.lastDelta(), 1.0E-9F));
    }

    @Test
    void readsTheSettingsSnapshotExactlyOncePerStep() {
        // A step that read the source twice could straddle a reload and decide to emit on one cadence
        // while sending the window of another — the same class of bug as reading the driver sample
        // twice, which this class already documents.
        final SimFixtures.MutableSource source =
                new SimFixtures.MutableSource(SimFixtures.settings(this.solver));
        final ModelSimulation simulation = new ModelSimulation(rig(), source, this.sink);
        this.solver.neverRest();
        source.resetReads();

        tick(simulation, 10);

        assertEquals(10, source.reads(),
                "ten steps must be ten snapshot reads, no more");
    }

    @Test
    void aMidStepReloadNeverProducesAMixedSnapshotStep() {
        // The sink publishes a new snapshot from inside the emission it is handed, which is as close to
        // "mid-step" as a reload can land. Whatever it publishes must not reach the step in flight: the
        // window on that emission has to be the one its own snapshot carried.
        final int[] windows = new int[4];
        final int[] seen = {0};
        final SimFixtures.MutableSource source =
                new SimFixtures.MutableSource(SimFixtures.settings(this.solver, SIMULATION_HZ, 20));
        final ModelSimulation simulation = new ModelSimulation(rig(), source,
                (rig, pose, interpolationTicks) -> {
                    if (seen[0] < windows.length) {
                        windows[seen[0]++] = interpolationTicks;
                    }
                    // 20 Hz carries a 1-tick window, 4 Hz a 5-tick one: as different as the bounds
                    // allow, so a leaked snapshot would be unmistakable.
                    source.publish(SimFixtures.settings(this.solver, SIMULATION_HZ, 4));
                });
        this.solver.neverRest();

        tick(simulation, 1);
        final int duringFirstStep = windows[0];
        tick(simulation, 30);

        assertAll(
                () -> assertEquals(1, duringFirstStep,
                        "the step that triggered the reload must finish on its own snapshot"),
                () -> assertEquals(5, windows[1],
                        "and only the next step may see the new one"),
                () -> assertEquals(5, simulation.cadence().interpolationTicks()));
    }
}
