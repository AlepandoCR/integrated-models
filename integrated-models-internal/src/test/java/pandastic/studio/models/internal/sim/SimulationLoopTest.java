package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationLoopTest {

    private static final int SIMULATION_HZ = 60;

    private final FakeClock clock = new FakeClock();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final RecordingLogger log = new RecordingLogger();

    /**
     * The loop's own settings. Mutable so the reload cases can publish a new snapshot behind it,
     * which is exactly what the configuration layer does.
     */
    private final SimFixtures.MutableSource source =
            new SimFixtures.MutableSource(SimFixtures.settings(new ScriptedSolver()));

    private final SimulationLoop loop =
            new SimulationLoop(this.scheduler, this.clock, this.log, this.source);

    private static Rig rig(String name) {
        return SimFixtures.rig(name, "model")
                .root("body")
                .bone("hair", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, SimFixtures.PROFILE_BOUNDS, SimFixtures.PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .build())
                .build();
    }

    private ModelSimulation simulation(String name, ScriptedSolver solver, PoseSink sink) {
        solver.neverRest();
        return new ModelSimulation(rig(name), SimFixtures.source(solver), sink);
    }

    /** Advances the clock a period and dispatches one iteration, as the platform would. */
    private void iterate(int times) {
        for (int iteration = 0; iteration < times; iteration++) {
            this.clock.advanceNanos(this.loop.periodNanos());
            this.scheduler.runIteration();
        }
    }

    @Test
    void drivesEveryRegisteredModelFromASingleTask() {
        final ScriptedSolver first = new ScriptedSolver();
        final ScriptedSolver second = new ScriptedSolver();
        final ScriptedSolver third = new ScriptedSolver();
        this.loop.register(simulation("a", first, PoseSink.DISCARDING));
        this.loop.register(simulation("b", second, PoseSink.DISCARDING));
        this.loop.register(simulation("c", third, PoseSink.DISCARDING));

        this.loop.start();
        iterate(3);

        assertAll(
                // The whole point: three models, one scheduled task. A task per model would put the
                // scheduler's dispatch overhead on work that is a few hundred multiplies.
                () -> assertEquals(1, this.scheduler.scheduleCount()),
                () -> assertEquals(3, this.loop.size()),
                () -> assertEquals(3, first.solveCount()),
                () -> assertEquals(3, second.solveCount()),
                () -> assertEquals(3, third.solveCount()),
                () -> assertTrue(this.loop.isRunning()));
    }

    @Test
    void oneClockReadingServesTheWholeIteration() {
        final ScriptedSolver first = new ScriptedSolver();
        final ScriptedSolver second = new ScriptedSolver();
        this.loop.register(simulation("a", first, PoseSink.DISCARDING));
        this.loop.register(simulation("b", second, PoseSink.DISCARDING));
        this.loop.start();

        this.scheduler.runIteration();
        this.clock.advanceMillis(37L);
        this.scheduler.runIteration();

        assertAll(
                () -> assertEquals(0F, first.deltaAt(0), 1.0E-9F),
                () -> assertEquals(0F, second.deltaAt(0), 1.0E-9F),
                // Both models advance by the same measured interval, so a long iteration cannot make
                // one model's physics run at a different speed to another's.
                () -> assertEquals(0.037F, first.deltaAt(1), 1.0E-6F),
                () -> assertEquals(0.037F, second.deltaAt(1), 1.0E-6F));
    }

    @Test
    void schedulesAtTheSimulationPeriodInNanoseconds() {
        this.loop.start();

        assertAll(
                () -> assertEquals(16_666_666L, this.loop.periodNanos()),
                () -> assertEquals(this.loop.periodNanos(), this.scheduler.period()),
                // Milliseconds would round 16.67 to 16 or 17, a 2% rate error either way.
                () -> assertSame(TimeUnit.NANOSECONDS, this.scheduler.unit()),
                () -> assertEquals(this.loop.periodNanos(), this.scheduler.initialDelay(),
                        "the first iteration needs a period of elapsed time to measure"),
                () -> assertEquals(SIMULATION_HZ, this.loop.simulationHz()));
    }

    @Test
    void refusesACadenceBuiltForAnotherSimulationRate() {
        // A cadence built for a 20 Hz simulation says "emit every step". Driven at 60 Hz it emits three
        // times as often as it claims, with a window three times longer than the gap it covers.
        final ModelSimulation mismatched = new ModelSimulation(
                rig("mismatch"),
                SimulationSettingsSource.fixed(
                        SimFixtures.settings(new ScriptedSolver(), 20, 20)),
                PoseSink.DISCARDING);

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> this.loop.register(mismatched));

        assertAll(
                () -> assertEquals(0, this.loop.size()),
                () -> assertTrue(thrown.getMessage().contains("mismatch"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("20 Hz"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("60 Hz"), thrown.getMessage()));
    }

    @Test
    void refusesToRegisterTheSameSimulationTwice() {
        final ModelSimulation simulation =
                simulation("twice", new ScriptedSolver(), PoseSink.DISCARDING);
        this.loop.register(simulation);

        // Registered twice it would be stepped twice per iteration: the second step measures no
        // elapsed time, but the cadence phase advances anyway, halving the emission interval.
        assertThrows(IllegalStateException.class, () -> this.loop.register(simulation));
        assertEquals(1, this.loop.size());
    }

    @Test
    void aThrowingSinkNeitherKillsTheLoopNorStopsItsSiblings() {
        final RecordingPoseSink broken = new RecordingPoseSink();
        broken.failWith(new IllegalStateException("the renderer is gone"));
        final RecordingPoseSink healthy = new RecordingPoseSink();

        this.loop.register(simulation("broken", new ScriptedSolver(), broken));
        final ScriptedSolver healthySolver = new ScriptedSolver();
        this.loop.register(simulation("healthy", healthySolver, healthy));

        this.loop.start();
        iterate(10);

        assertAll(
                // The broken model is ahead of the healthy one in the list, so without the
                // per-simulation catch its first failure would skip every model after it.
                () -> assertEquals(10, healthySolver.solveCount(),
                        "a sibling must still be stepped"),
                () -> assertEquals(4, healthy.count(), "and must still emit on its cadence"),
                () -> assertTrue(this.scheduler.isAlive(), "the loop must survive"),
                () -> assertTrue(this.scheduler.escaped().isEmpty(),
                        "nothing may reach the platform, which would swallow it silently"),
                () -> assertEquals(1, this.log.count(), "and the failure is reported, once"),
                () -> assertTrue(this.log.last().contains("broken"), this.log.last()),
                () -> assertSame(IllegalStateException.class,
                        this.log.throwables().get(0).getClass()));
    }

    @Test
    void rateLimitsRepeatedFailuresAndCarriesTheSuppressedCount() {
        final ScriptedSolver failing = new ScriptedSolver();
        this.loop.register(simulation("doomed", failing, PoseSink.DISCARDING));
        failing.failWith(new IllegalStateException("every single step"));

        this.loop.start();
        // A full second of a body that throws every iteration. Unlimited that is sixty stack traces.
        iterate(60);
        assertEquals(1, this.log.count(), "sixty failures are worth one line");

        this.clock.advanceNanos(SimFixtures.ERROR_LOG_INTERVAL_NANOS);
        iterate(1);

        assertAll(
                () -> assertEquals(2, this.log.count(), "and one more once the interval has elapsed"),
                // "this failed 59 times" and "this failed once" call for different responses.
                () -> assertTrue(this.log.last().contains("59 further failures suppressed"),
                        this.log.last()),
                () -> assertTrue(this.scheduler.isAlive()));
    }

    @Test
    void unregisteringThroughTheHandleStopsTheStepping() {
        final ScriptedSolver kept = new ScriptedSolver();
        final ScriptedSolver dropped = new ScriptedSolver();
        this.loop.register(simulation("kept", kept, PoseSink.DISCARDING));
        final SimulationLoop.Registration handle =
                this.loop.register(simulation("dropped", dropped, PoseSink.DISCARDING));
        this.loop.start();

        iterate(1);
        handle.unregister();
        iterate(2);

        assertAll(
                () -> assertEquals(3, kept.solveCount()),
                () -> assertEquals(1, dropped.solveCount()),
                () -> assertEquals(1, this.loop.size()),
                () -> assertFalse(handle.isRegistered()),
                () -> assertNotNull(handle.simulation()));
    }

    @Test
    void unregisteringIsIdempotentAndByIdentityReportsWhetherItFound() {
        final ModelSimulation registered =
                simulation("registered", new ScriptedSolver(), PoseSink.DISCARDING);
        final ModelSimulation stranger =
                simulation("stranger", new ScriptedSolver(), PoseSink.DISCARDING);
        final SimulationLoop.Registration handle = this.loop.register(registered);

        assertAll(
                () -> assertFalse(this.loop.unregister(stranger)),
                () -> assertTrue(this.loop.unregister(registered)),
                () -> assertFalse(this.loop.unregister(registered)),
                () -> assertEquals(0, this.loop.size()));
        handle.unregister();
        assertFalse(handle.isRegistered());
    }

    @Test
    void unregisteringMidIterationSkipsThatModelImmediately() {
        final SimulationLoop.Registration[] victim = new SimulationLoop.Registration[1];
        final ScriptedSolver first = new ScriptedSolver();
        final ScriptedSolver second = new ScriptedSolver();

        // The first model's sink unregisters the second, which sits after it in the list — so this
        // iteration's snapshot still contains it.
        this.loop.register(simulation("first", first,
                (rig, pose, interpolationTicks) -> victim[0].unregister()));
        victim[0] = this.loop.register(simulation("second", second, PoseSink.DISCARDING));

        this.loop.start();
        iterate(1);

        assertAll(
                () -> assertEquals(1, first.solveCount()),
                // Not "stepped once more and then dropped": for a model whose entity has just been
                // removed, that extra step is the one that reaches a sink with nothing to write to.
                () -> assertEquals(0, second.solveCount(),
                        "an unregistered model must not be stepped by the iteration that dropped it"),
                () -> assertTrue(this.scheduler.escaped().isEmpty()),
                () -> assertEquals(0, this.log.count(), "and that is not a failure"));
    }

    @Test
    void aSimulationRegisteredMidIterationStartsOnTheNextOne() {
        final ScriptedSolver latecomer = new ScriptedSolver();
        final ModelSimulation joining = simulation("latecomer", latecomer, PoseSink.DISCARDING);
        final boolean[] joined = {false};

        this.loop.register(simulation("first", new ScriptedSolver(),
                (rig, pose, interpolationTicks) -> {
                    if (!joined[0]) {
                        joined[0] = true;
                        this.loop.register(joining);
                    }
                }));

        this.loop.start();
        iterate(1);
        // Stepping it now would hand it this iteration's already-taken clock reading.
        assertEquals(0, latecomer.solveCount());

        iterate(1);
        assertEquals(1, latecomer.solveCount());
    }

    @Test
    void stopCancelsTheRetainedTaskAndIsIdempotent() {
        this.loop.register(simulation("a", new ScriptedSolver(), PoseSink.DISCARDING));
        this.loop.start();
        iterate(1);

        this.loop.stop();
        this.loop.stop();

        assertAll(
                () -> assertFalse(this.loop.isRunning()),
                () -> assertFalse(this.scheduler.isAlive()),
                // Cancelled through the handle the loop kept, which is the only ordering that works:
                // the classloader closes before the platform's own cancelTasks runs.
                () -> assertEquals(1, this.loop.size(),
                        "stopping the loop is not unbinding the models"));
    }

    @Test
    void refusesToStartTwiceButCanBeRestarted() {
        this.loop.start();
        assertThrows(IllegalStateException.class, this.loop::start);

        this.loop.stop();
        this.loop.start();

        assertAll(
                () -> assertEquals(2, this.scheduler.scheduleCount()),
                () -> assertTrue(this.loop.isRunning()));
    }

    @Test
    void stoppingBeforeStartingIsANoOp() {
        this.loop.stop();
        assertFalse(this.loop.isRunning());
    }

    @Test
    void reportsAFailureInTheLoopsOwnMachineryInsteadOfLettingItEscape() {
        // Throws on its first reading only, so the rate limiter can still read it to decide.
        final SimulationClock flaky = new SimulationClock() {

            private int readings;

            @Override
            public long nanoTime() {
                if (this.readings++ == 0) {
                    throw new IllegalStateException("the clock broke");
                }
                return 0L;
            }
        };
        final FakeScheduler ownScheduler = new FakeScheduler();
        final SimulationLoop guarded =
                new SimulationLoop(ownScheduler, flaky, this.log, this.source);
        guarded.start();

        ownScheduler.runIteration();

        assertAll(
                () -> assertTrue(ownScheduler.escaped().isEmpty(),
                        "a throw into the platform is swallowed unlogged, i.e. a loop doing nothing"),
                () -> assertEquals(1, this.log.count()),
                () -> assertTrue(this.log.last().contains("outside any single model"),
                        this.log.last()),
                () -> assertTrue(ownScheduler.isAlive()));
    }

    @Test
    void refusesANonsensicalSimulationRate() {
        // Refused while the snapshot is being built, which is what lets a bad reload fail wholesale
        // and leave the running configuration in force instead of being discovered one model later.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SimFixtures.settings(new ScriptedSolver(), 0, 20)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SimFixtures.settings(new ScriptedSolver(), -60, 20)),
                // And a cadence that does not divide the rate, which would emit at a frequency the
                // cadence does not claim and send a window that does not match the gap.
                () -> assertThrows(IllegalArgumentException.class, () -> new SimulationSettings(
                        new ScriptedSolver(),
                        60,
                        SimFixtures.cadence(20, 20),
                        SimFixtures.DRIVER_BOUNDS,
                        SimFixtures.ERROR_LOG_INTERVAL_NANOS)),
                () -> assertThrows(IllegalArgumentException.class, () -> new SimulationSettings(
                        new ScriptedSolver(),
                        60,
                        SimFixtures.cadence(60, 20),
                        SimFixtures.DRIVER_BOUNDS,
                        -1L)));
    }

    // ------------------------------------------------------------------------------------------
    // Injected configuration is honoured, and a reload propagates
    // ------------------------------------------------------------------------------------------

    @Test
    void schedulesAtTheConfiguredRateRatherThanAFixedSixty() {
        // Thirty hertz is 33.33 ms, which no default in this package would produce.
        final FakeScheduler ownScheduler = new FakeScheduler();
        final SimulationLoop thirty = new SimulationLoop(ownScheduler, this.clock, this.log,
                SimulationSettingsSource.fixed(SimFixtures.settings(new ScriptedSolver(), 30, 10)));

        thirty.start();

        assertAll(
                () -> assertEquals(30, thirty.simulationHz()),
                () -> assertEquals(33_333_333L, thirty.periodNanos()),
                () -> assertEquals(33_333_333L, ownScheduler.period()),
                () -> assertEquals(33_333_333L, ownScheduler.initialDelay()),
                () -> assertSame(TimeUnit.NANOSECONDS, ownScheduler.unit()));
    }

    @Test
    void throttlesFailuresAtTheConfiguredIntervalAndFollowsAChangeToIt() {
        // One second, not the five a stock configuration ships: a loop holding a default would stay
        // silent through the first half of this test and fail it.
        this.source.publish(new SimulationSettings(
                new ScriptedSolver(), SIMULATION_HZ,
                SimFixtures.cadence(SIMULATION_HZ, 20),
                SimFixtures.DRIVER_BOUNDS,
                1_000_000_000L));
        final ScriptedSolver failing = new ScriptedSolver();
        this.loop.register(simulation("doomed", failing, PoseSink.DISCARDING));
        failing.failWith(new IllegalStateException("every single step"));

        this.loop.start();
        iterate(60);
        this.clock.advanceNanos(1_000_000_000L);
        iterate(1);
        final int afterOneSecond = this.log.count();

        // Now switch the throttle off entirely. Zero is a legitimate setting — "report everything" —
        // and a limiter that had latched its interval would keep suppressing.
        this.source.publish(new SimulationSettings(
                new ScriptedSolver(), SIMULATION_HZ,
                SimFixtures.cadence(SIMULATION_HZ, 20),
                SimFixtures.DRIVER_BOUNDS,
                0L));
        iterate(5);

        assertAll(
                () -> assertEquals(2, afterOneSecond,
                        "a one-second interval reports twice over this stretch, not once"),
                () -> assertEquals(afterOneSecond + 5, this.log.count(),
                        "and an interval of zero lets every failure through"));
    }

    @Test
    void reloadRebuildsEveryRegisteredSimulationAndReDispatchesAChangedRate() {
        // The two halves of the propagation, which is why they are asserted together: a rate change
        // invalidates every live PhysicsState *and* the period the platform was asked to dispatch at,
        // and neither can be fixed from inside a step.
        final AngularPhysicsSolver first = SimFixtures.realSolver();
        final AngularPhysicsSolver second = new AngularPhysicsSolver(SimFixtures.tuning(120));
        final SimFixtures.MutableSource shared =
                new SimFixtures.MutableSource(SimFixtures.settings(first, 60, 20));
        final SimulationLoop reloadable =
                new SimulationLoop(this.scheduler, this.clock, this.log, shared);
        final ModelSimulation one =
                new ModelSimulation(rig("one"), shared, PoseSink.DISCARDING);
        final ModelSimulation two =
                new ModelSimulation(rig("two"), shared, PoseSink.DISCARDING);
        reloadable.register(one);
        reloadable.register(two);
        reloadable.start();
        assertEquals(16_666_666L, reloadable.scheduledPeriodNanos());

        shared.publish(SimFixtures.settings(second, 30, 10));
        final int rebuilt = reloadable.reload();

        assertAll(
                () -> assertEquals(2, rebuilt, "every registered simulation, not just the first"),
                () -> assertSame(second, one.solver()),
                () -> assertSame(second, two.solver()),
                () -> assertEquals(33_333_333L, reloadable.scheduledPeriodNanos(),
                        "the task has to be re-dispatched: the platform owns the repeating rate"),
                () -> assertEquals(2, this.scheduler.scheduleCount(),
                        "cancelled and dispatched once, never two live tasks at the same time"),
                () -> assertTrue(reloadable.isRunning()),
                () -> assertEquals(0, this.log.count(), "a clean reload is not a failure"));
    }

    @Test
    void reloadLeavesTheTaskAloneWhenOnlyTheCalibrationMoved() {
        final AngularPhysicsSolver replacement =
                new AngularPhysicsSolver(SimFixtures.tuning(120));
        final SimFixtures.MutableSource shared =
                new SimFixtures.MutableSource(SimFixtures.settings(SimFixtures.realSolver()));
        final SimulationLoop reloadable =
                new SimulationLoop(this.scheduler, this.clock, this.log, shared);
        reloadable.register(new ModelSimulation(rig("one"), shared, PoseSink.DISCARDING));
        reloadable.start();

        shared.publish(SimFixtures.settings(replacement));
        reloadable.reload();

        assertAll(
                () -> assertEquals(1, this.scheduler.scheduleCount(),
                        "re-dispatching an unchanged period would only lose an iteration"),
                () -> assertTrue(reloadable.isRunning()));
    }

    @Test
    void reloadOnAStoppedLoopRebuildsStateAndSchedulesNothing() {
        final AngularPhysicsSolver replacement =
                new AngularPhysicsSolver(SimFixtures.tuning(120));
        final SimFixtures.MutableSource shared =
                new SimFixtures.MutableSource(SimFixtures.settings(SimFixtures.realSolver()));
        final SimulationLoop reloadable =
                new SimulationLoop(this.scheduler, this.clock, this.log, shared);
        final ModelSimulation simulation =
                new ModelSimulation(rig("one"), shared, PoseSink.DISCARDING);
        reloadable.register(simulation);

        shared.publish(SimFixtures.settings(replacement, 30, 10));

        assertAll(
                () -> assertEquals(1, reloadable.reload()),
                () -> assertSame(replacement, simulation.solver()),
                () -> assertFalse(reloadable.isRunning()),
                () -> assertFalse(this.scheduler.isAlive()));
    }

    @Test
    void oneRigThatCannotBeRebuiltDoesNotCostTheRestTheirReload() {
        // A calibration the new rate cannot integrate for one rig must not abort the reload: the
        // failing model keeps running on its old state, reported once and by name.
        final SimFixtures.MutableSource shared =
                new SimFixtures.MutableSource(SimFixtures.settings(SimFixtures.realSolver()));
        final SimulationLoop reloadable =
                new SimulationLoop(this.scheduler, this.clock, this.log, shared);
        final ScriptedSolver healthy = new ScriptedSolver();
        reloadable.register(new ModelSimulation(rig("broken"),
                failingRebuild(shared), PoseSink.DISCARDING));
        reloadable.register(new ModelSimulation(rig("healthy"),
                SimFixtures.source(healthy), PoseSink.DISCARDING));

        final int rebuilt = reloadable.reload();

        assertAll(
                () -> assertEquals(1, rebuilt, "the healthy model is still rebuilt"),
                () -> assertEquals(1, this.log.count()),
                () -> assertTrue(this.log.last().contains("broken"), this.log.last()),
                () -> assertTrue(this.log.last().contains("previous one"), this.log.last()));
    }

    /**
     * A source whose first read succeeds — so a simulation can be constructed — and whose later reads
     * hand out a solver that refuses to create a state, which is how a reloaded calibration that
     * cannot integrate a rig presents.
     */
    private static SimulationSettingsSource failingRebuild(SimFixtures.MutableSource template) {
        final SimulationSettings initial = template.current();
        final SimulationSettings broken = new SimulationSettings(
                new RefusingSolver(),
                SIMULATION_HZ,
                SimFixtures.cadence(SIMULATION_HZ, 20),
                SimFixtures.DRIVER_BOUNDS,
                SimFixtures.ERROR_LOG_INTERVAL_NANOS);
        final boolean[] exhausted = {false};
        return () -> {
            if (!exhausted[0]) {
                exhausted[0] = true;
                return initial;
            }
            return broken;
        };
    }

    /** A calibration that cannot integrate the rig it is handed, which is what refusal looks like. */
    private static final class RefusingSolver implements PhysicsSolver {

        @Override
        public @NotNull PhysicsState createState(@NotNull Rig rig) {
            throw new IllegalArgumentException(
                    "Rig '" + rig.getName() + "' is tuned past the new calibration's stability bound");
        }

        @Override
        public int substepRateHz() {
            return SIMULATION_HZ;
        }

        @Override
        public int maxSubsteps() {
            return 4;
        }

        @Override
        public void solve(
                @NotNull Rig rig,
                @NotNull PhysicsState state,
                @NotNull Pose pose,
                float deltaSeconds,
                @NotNull PhysicsDriver driver
        ) {
            throw new AssertionError("a refused calibration must never reach a solve");
        }
    }
}
