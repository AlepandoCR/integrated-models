package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.ModelSimulation;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop that makes the culler reachable, and the claim it exists for: a model nobody can see stops
 * being simulated.
 *
 * <p>The last section is the one that matters. Everything above it tests plumbing — a rate, a
 * re-dispatch, a failure that does not escape — and plumbing is cheap to get right. What was missing from
 * this project was not a pass function but a saving, and a culler that suppressed packets while still
 * integrating every spring on the server would pass every other test here.
 */
class ViewLoopTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());

    private static final int SIMULATION_HZ = 20;

    /** Deliberately not a default: 3 ticks, so a loop reading the key cannot coincide with 1 or 2. */
    private static final int SAMPLE_INTERVAL_TICKS = 3;

    private static ViewSettings settings(int sampleIntervalTicks) {
        return new ViewSettings(
                40D,    // viewRangeBlocks
                7D,     // nearRadiusBlocks
                12D,    // exitMarginBlocks — clears the reach below at 3 ticks
                50D,    // coneEnterDegrees
                65D,    // coneExitDegrees
                4D,     // cameraOffsetBlocks
                1.5D,   // defaultModelRadiusBlocks
                0.8D,   // maxViewerSpeedBlocksPerTick
                sampleIntervalTicks,
                false,  // honourClientViewDistance
                2);     // spawnBudgetPerViewer
    }

    /** A viewer at the origin looking along +Z, which is Minecraft's yaw 0. */
    private static ViewerSnapshot viewer() {
        return ViewerSnapshot.of(
                UUID.nameUUIDFromBytes("viewer".getBytes()), WORLD, 0D, 0D, 0D, 0F, 0F, 32, 0L);
    }

    // =============================================================================================
    // The rate
    // =============================================================================================

    @Test
    void theLoopIsDispatchedAtTheIntervalTheFileStates() {
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final ViewLoop loop = loop(scheduler, settings(SAMPLE_INTERVAL_TICKS));

        loop.start();

        // Three ticks at twenty ticks a second is 150 ms. Asserted in nanoseconds because that is the
        // unit the scheduler is asked in: rounding the period to a whole millisecond would be a rate
        // error, which is the same reason the simulation loop dispatches in nanoseconds.
        assertAll(
                () -> assertTrue(loop.isRunning()),
                () -> assertEquals(150_000_000L, scheduler.period()),
                () -> assertEquals(150_000_000L, scheduler.initialDelay(),
                        "the first pass waits a full period, so it runs against a filled index"),
                () -> assertEquals(TimeUnit.NANOSECONDS, scheduler.unit()),
                () -> assertEquals(150_000_000L, loop.scheduledPeriodNanos()),
                () -> assertEquals(1, scheduler.scheduleCount(),
                        "one task for the whole server, not one per model"));
    }

    @Test
    void theRateIsNotTheSimulationsRate() {
        // The mistake this layer exists to prevent. A cull decision is a function of positions that
        // change at most once a tick, so a pass per simulation step would produce identical answers and
        // pay for every one of them.
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final ViewLoop loop = loop(scheduler, settings(SAMPLE_INTERVAL_TICKS));

        loop.start();

        final long simulationPeriodNanos = 1_000_000_000L / SIMULATION_HZ;
        assertTrue(scheduler.period() > simulationPeriodNanos,
                "the pass period must be slower than a simulation step, but was " + scheduler.period()
                        + " against " + simulationPeriodNanos);
    }

    @Test
    void startingTwiceIsRefusedRatherThanDoubled() {
        final ViewLoop loop = loop(new FakeOffTickScheduler(), settings(SAMPLE_INTERVAL_TICKS));
        loop.start();
        assertThrows(IllegalStateException.class, loop::start);
    }

    @Test
    void stoppingIsIdempotent() {
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final ViewLoop loop = loop(scheduler, settings(SAMPLE_INTERVAL_TICKS));
        loop.start();

        loop.stop();
        loop.stop();

        assertAll(
                () -> assertFalse(loop.isRunning()),
                () -> assertFalse(scheduler.isAlive()));
    }

    @Test
    void aReloadThatMovedTheIntervalCancelsBeforeItDispatches() {
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final AtomicReference<ViewSettings> live =
                new AtomicReference<>(settings(SAMPLE_INTERVAL_TICKS));
        final ViewLoop loop = new ViewLoop(
                scheduler, SimulationClock.SYSTEM, SimulationLogger.DISCARDING, live::get,
                new ViewerIndex());
        loop.start();
        final SimulationTask first = scheduler.task();

        live.set(settings(5));
        final boolean redispatched = loop.reload();

        assertAll(
                () -> assertTrue(redispatched),
                () -> assertEquals(250_000_000L, loop.scheduledPeriodNanos()),
                () -> assertEquals(2, scheduler.scheduleCount()),
                // Two live passes would each undo the other's spawn budget.
                () -> assertNotNull(first),
                () -> assertTrue(first.isCancelled(),
                        "the previous task has to be cancelled before the new one is dispatched"));
    }

    @Test
    void aReloadThatChangedNothingLeavesTheTaskAlone() {
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final ViewLoop loop = loop(scheduler, settings(SAMPLE_INTERVAL_TICKS));
        loop.start();

        assertAll(
                () -> assertFalse(loop.reload()),
                () -> assertEquals(1, scheduler.scheduleCount()));
    }

    @Test
    void aReloadOnAStoppedLoopDispatchesNothing() {
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final AtomicReference<ViewSettings> live =
                new AtomicReference<>(settings(SAMPLE_INTERVAL_TICKS));
        final ViewLoop loop = new ViewLoop(
                scheduler, SimulationClock.SYSTEM, SimulationLogger.DISCARDING, live::get,
                new ViewerIndex());

        live.set(settings(5));

        assertFalse(loop.reload());
    }

    // =============================================================================================
    // Failure isolation
    // =============================================================================================

    @Test
    void aPassThatThrowsIsCaughtRatherThanEscapingIntoThePlatform() {
        // The platform swallows whatever escapes a scheduled body and reschedules anyway, so an
        // uncaught throw is a loop that silently does nothing.
        final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
        final RecordingErrors errors = new RecordingErrors();
        final ViewLoop loop = new ViewLoop(
                scheduler, SimulationClock.SYSTEM, errors,
                ViewSettingsSource.fixed(settings(SAMPLE_INTERVAL_TICKS)), new ViewerIndex());
        loop.addSource(() -> {
            throw new IllegalStateException("a binder that blew up");
        });
        loop.start();

        scheduler.runOnce();

        assertAll(
                () -> assertTrue(scheduler.escaped().isEmpty(),
                        "nothing may reach the platform: " + scheduler.escaped()),
                () -> assertEquals(1, errors.count()),
                () -> assertTrue(scheduler.isAlive(), "the task survives so the next pass is tried"));
    }

    // =============================================================================================
    // Sources
    // =============================================================================================

    @Test
    void everySourcesModelsAreDecidedFor() {
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));

        final RecordingCullable near = new RecordingCullable(WORLD, 0D, 0D, 3D);
        final RecordingCullable far = new RecordingCullable(WORLD, 0D, 0D, 4D);
        loop.addSource(() -> List.of(near));
        loop.addSource(() -> List.of(far));

        final ViewLoop.Result result = loop.runPass();

        assertAll(
                () -> assertEquals(2, result.models()),
                () -> assertEquals(1, result.viewers()),
                () -> assertEquals(2, result.decided().shown()),
                () -> assertEquals(2, loop.sourceCount()));
    }

    @Test
    void aModelThatHasLeftItsBinderIsNoLongerDecidedFor() {
        // The reason the binders' own membership is the authority: a registry here would keep deciding
        // for a model that is gone.
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));

        final List<RecordingCullable> bound = new ArrayList<>();
        bound.add(new RecordingCullable(WORLD, 0D, 0D, 3D));
        loop.addSource(() -> List.copyOf(bound));

        loop.runPass();
        bound.clear();
        final ViewLoop.Result afterTeardown = loop.runPass();

        assertEquals(0, afterTeardown.models());
    }

    // =============================================================================================
    // The gate, as decisions
    // =============================================================================================

    @Test
    void aModelNobodyHoldsIsSuspended() {
        // No viewers at all, which is every model on a server with nobody online.
        final ViewLoop loop = loopOver(new ViewerIndex(), settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable model = new RecordingCullable(WORLD, 0D, 0D, 3D);
        loop.addSource(() -> List.of(model));

        final ViewLoop.Result result = loop.runPass();

        assertAll(
                () -> assertFalse(model.simulated),
                () -> assertEquals(1, result.suspended()),
                () -> assertEquals(0, result.simulated()),
                () -> assertEquals(1, result.withheld()));
    }

    @Test
    void aModelSomebodyHoldsStaysSimulated() {
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable model = new RecordingCullable(WORLD, 0D, 0D, 3D);
        loop.addSource(() -> List.of(model));

        final ViewLoop.Result result = loop.runPass();

        assertAll(
                () -> assertTrue(model.simulated),
                () -> assertEquals(0, result.suspended()),
                () -> assertEquals(1, result.simulated()),
                () -> assertEquals(0, result.withheld()));
    }

    @Test
    void aViewerWalkingBackIntoRangeResumesTheModel() {
        final ViewerIndex index = new ViewerIndex();
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable model = new RecordingCullable(WORLD, 0D, 0D, 3D);
        loop.addSource(() -> List.of(model));

        loop.runPass();
        assertFalse(model.simulated, "nobody online yet");

        index.publish(viewer());
        final ViewLoop.Result afterJoin = loop.runPass();

        assertAll(
                () -> assertTrue(model.simulated),
                () -> assertEquals(1, afterJoin.resumed()));
    }

    @Test
    void aSettledDecisionReportsNoChange() {
        // Hysteresis plus an unchanged gate: the common case, and the one that must cost nothing.
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable model = new RecordingCullable(WORLD, 0D, 0D, 3D);
        loop.addSource(() -> List.of(model));

        loop.runPass();
        final ViewLoop.Result second = loop.runPass();

        assertTrue(second.isIdle(), "a repeated pass over unchanged state must change nothing");
    }

    @Test
    void aModelTheCullerMayNotTouchIsNeverSuspended() {
        // isCullable() false means "this is not a culling decision". Without the carve-out the two
        // answers would contradict each other: the culler hides such a model from everybody, so
        // "nobody holds it" would be true of exactly the models it has been told to leave alone.
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable midBind = new RecordingCullable(WORLD, 0D, 0D, 3D);
        midBind.cullable = false;
        loop.addSource(() -> List.of(midBind));

        final ViewLoop.Result result = loop.runPass();

        assertAll(
                () -> assertTrue(midBind.simulated),
                () -> assertEquals(0, result.suspended()),
                // Not counted as a saving either: nothing was saved, and letting it inflate the figure
                // would have this layer claim credit for work that never existed.
                () -> assertEquals(0, result.withheld()));
    }

    @Test
    void theGateIsAppliedAfterTheDecisionAndNotBeforeIt() {
        // A model shown for the first time on this very pass has to be simulated by the end of it. If
        // the gate ran first it would read a viewport the pass had not filled yet and suspend a model it
        // was about to hand to somebody.
        final ViewerIndex index = new ViewerIndex();
        index.publish(viewer());
        final ViewLoop loop = loopOver(index, settings(SAMPLE_INTERVAL_TICKS));
        final RecordingCullable model = new RecordingCullable(WORLD, 0D, 0D, 3D);
        loop.addSource(() -> List.of(model));

        final ViewLoop.Result result = loop.runPass();

        assertAll(
                () -> assertEquals(1, result.decided().shown()),
                () -> assertTrue(model.simulated,
                        "a model shown on this pass must be simulated by the end of it"));
    }

    // =============================================================================================
    // The claim: an unseen model skips the work
    // =============================================================================================

    @Test
    void anUnseenModelIsNotStepped() {
        final Fixture fixture = new Fixture();

        // Seen: one iteration, one solve, one emission.
        fixture.index.publish(viewer());
        fixture.view.runPass();
        fixture.stepSimulationOnce();
        assertAll(
                () -> assertEquals(1, fixture.solver.solves,
                        "a model somebody holds has to be integrated"),
                () -> assertEquals(1, fixture.sink.emissions),
                () -> assertEquals(1L, fixture.simulation.stepCount()));

        // The player logs out. Their sampler forgets them, so the next pass finds no viewer.
        fixture.index.forget(viewer().playerId());
        final ViewLoop.Result suspended = fixture.view.runPass();

        // Ten more iterations of the simulation loop, which is half a second at the shipped rate.
        for (int iteration = 0; iteration < 10; iteration++) {
            fixture.stepSimulationOnce();
        }

        assertAll(
                () -> assertEquals(1, suspended.suspended()),
                () -> assertEquals(1, suspended.withheld()),

                // The three figures that make this real, and each one is a different cost.
                () -> assertEquals(1, fixture.solver.solves,
                        "the spring integration over every bone must not have run again"),
                () -> assertEquals(1, fixture.sink.emissions,
                        "no pose may have been emitted"),
                () -> assertEquals(1L, fixture.simulation.stepCount(),
                        "the step itself must not have been taken — not the pose reset, not the gait,"
                                + " not the cadence bookkeeping"),

                // And the membership is intact, which is what separates this from an unregister: the
                // handle that owns it still unregisters it on teardown, and a reload still reaches it.
                () -> assertEquals(1, fixture.loop.size(),
                        "a suspended model is still a bound model"),
                () -> assertTrue(fixture.registration.isRegistered()),
                () -> assertFalse(fixture.registration.isStepping()));
    }

    @Test
    void aResumedModelIsSteppedAgainWithoutBeingReset() {
        final Fixture fixture = new Fixture();
        fixture.index.publish(viewer());
        fixture.view.runPass();
        fixture.stepSimulationOnce();
        fixture.stepSimulationOnce();

        fixture.index.forget(viewer().playerId());
        fixture.view.runPass();
        fixture.stepSimulationOnce();
        final long whileSuspended = fixture.simulation.stepCount();

        fixture.index.publish(viewer());
        fixture.view.runPass();
        fixture.stepSimulationOnce();

        assertAll(
                () -> assertEquals(whileSuspended + 1L, fixture.simulation.stepCount()),
                () -> assertEquals(3, fixture.solver.solves),
                // Not reset: the cadence phase carries on from where it was, because resuming is one
                // volatile write and may not reach into a simulation the loop's own thread owns.
                () -> assertTrue(fixture.registration.isStepping()));
    }

    @Test
    void aSuspendedModelIsStillRebuiltByAReload() {
        // Suspension gates the stepping and not the membership, so a calibration change reaches every
        // bound model whether or not anybody is looking at it. Were it an unregister, a model would come
        // back carrying a state built for a step size that no longer exists.
        final Fixture fixture = new Fixture();
        fixture.view.runPass();
        assertFalse(fixture.registration.isStepping());

        assertEquals(1, fixture.loop.reload());
    }

    @Test
    void theGateDoesNothingForAModelThatHasBeenUnregistered() {
        // The one thing a separate flag buys over reusing the registration's own active flag: resuming
        // must not be able to revive a model whose entity is gone, because its sink is already detached.
        final Fixture fixture = new Fixture();
        fixture.registration.unregister();

        assertAll(
                () -> assertFalse(fixture.registration.setStepping(false)),
                () -> assertFalse(fixture.registration.setStepping(true)),
                () -> assertFalse(fixture.registration.isStepping()),
                () -> assertEquals(0, fixture.loop.size()));
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private static @NotNull ViewLoop loop(
            @NotNull SimulationScheduler scheduler,
            @NotNull ViewSettings settings
    ) {
        return new ViewLoop(scheduler, SimulationClock.SYSTEM, SimulationLogger.DISCARDING,
                ViewSettingsSource.fixed(settings), new ViewerIndex());
    }

    private static @NotNull ViewLoop loopOver(
            @NotNull ViewerIndex index,
            @NotNull ViewSettings settings
    ) {
        return new ViewLoop(new FakeOffTickScheduler(), SimulationClock.SYSTEM,
                SimulationLogger.DISCARDING, ViewSettingsSource.fixed(settings), index);
    }

    /**
     * A real simulation on a real loop, with a real view loop deciding about it.
     *
     * <p>The whole point of the fixture is that nothing between the culling decision and the skipped
     * work is faked: the loop is {@link SimulationLoop}, the simulation is {@link ModelSimulation}, and
     * the two things that count — the solver and the sink — are the real interfaces with a counter in
     * them. A fake in the middle would let the test agree with a culler that saved nothing.
     */
    private static final class Fixture {

        private final @NotNull CountingSolver solver = new CountingSolver();
        private final @NotNull CountingSink sink = new CountingSink();
        private final @NotNull ViewerIndex index = new ViewerIndex();
        private final @NotNull FakeOffTickScheduler simulationScheduler = new FakeOffTickScheduler();
        private final @NotNull SimulationLoop loop;
        private final @NotNull ModelSimulation simulation;
        private final @NotNull SimulationLoop.Registration registration;
        private final @NotNull ViewLoop view;

        private Fixture() {
            final Rig rig = Rig.modelOnly("fixture", "root");
            final SimulationSettingsSource source = SimulationSettingsSource.fixed(
                    new SimulationSettings(
                            this.solver,
                            SIMULATION_HZ,
                            // One step per emission, so every step that is taken emits and the counters
                            // cannot be confused by a cadence that was skipping anyway.
                            EmissionCadence.of(SIMULATION_HZ, SIMULATION_HZ, new CadenceBounds(1, 59)),
                            new DriverBounds(1.5F, 6F),
                            0L));
            this.loop = new SimulationLoop(
                    this.simulationScheduler, SimulationClock.SYSTEM, SimulationLogger.DISCARDING,
                    source);
            this.simulation = new ModelSimulation(rig, source, this.sink);
            this.registration = this.loop.register(this.simulation);
            this.loop.start();

            this.view = new ViewLoop(new FakeOffTickScheduler(), SimulationClock.SYSTEM,
                    SimulationLogger.DISCARDING,
                    ViewSettingsSource.fixed(settings(SAMPLE_INTERVAL_TICKS)), this.index);
            final GatedCullable model = new GatedCullable(this.registration);
            this.view.addSource(() -> List.of(model));
        }

        /** One iteration of the real simulation loop, dispatched as the platform would. */
        private void stepSimulationOnce() {
            this.simulationScheduler.runOnce();
        }
    }

    /** A model at a fixed place whose gate is a real loop membership. */
    private static final class GatedCullable implements CullableModel {

        private final @NotNull UUID id = UUID.randomUUID();
        private final @NotNull ModelViewport viewport = new ModelViewport();
        private final @NotNull SimulationLoop.Registration registration;

        private GatedCullable(@NotNull SimulationLoop.Registration registration) {
            this.registration = registration;
        }

        @Override
        public @NotNull UUID modelId() {
            return this.id;
        }

        @Override
        public @NotNull UUID worldId() {
            return WORLD;
        }

        @Override
        public double cullX() {
            return 0D;
        }

        @Override
        public double cullY() {
            return 0D;
        }

        @Override
        public double cullZ() {
            return 3D;
        }

        @Override
        public @NotNull ModelViewport viewport() {
            return this.viewport;
        }

        @Override
        public @NotNull ModelViewportSink viewportSink() {
            return ModelViewportSink.NONE;
        }

        @Override
        public boolean setSimulated(boolean simulated) {
            return this.registration.setStepping(simulated);
        }

        @Override
        public boolean isSimulated() {
            return this.registration.isStepping();
        }
    }

    /** A model whose gate is a flag, for the decision tests. */
    private static final class RecordingCullable implements CullableModel {

        private final @NotNull UUID id = UUID.randomUUID();
        private final @NotNull UUID world;
        private final @NotNull ModelViewport viewport = new ModelViewport();
        private final double x;
        private final double y;
        private final double z;

        private boolean cullable = true;
        private boolean simulated = true;

        private RecordingCullable(@NotNull UUID world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public @NotNull UUID modelId() {
            return this.id;
        }

        @Override
        public @NotNull UUID worldId() {
            return this.world;
        }

        @Override
        public double cullX() {
            return this.x;
        }

        @Override
        public double cullY() {
            return this.y;
        }

        @Override
        public double cullZ() {
            return this.z;
        }

        @Override
        public @NotNull ModelViewport viewport() {
            return this.viewport;
        }

        @Override
        public @NotNull ModelViewportSink viewportSink() {
            return ModelViewportSink.NONE;
        }

        @Override
        public boolean isCullable() {
            return this.cullable;
        }

        @Override
        public boolean setSimulated(boolean wanted) {
            if (this.simulated == wanted) {
                return false;
            }
            this.simulated = wanted;
            return true;
        }

        @Override
        public boolean isSimulated() {
            return this.simulated;
        }
    }

    /**
     * A solver that counts and integrates nothing.
     *
     * <p>Never at rest, so the simulation's own at-rest gate cannot be what stops the emissions — which
     * is the one way this test could pass while the culling did nothing.
     */
    private static final class CountingSolver implements PhysicsSolver {

        private int solves;

        @Override
        public @NotNull PhysicsState createState(@NotNull Rig rig) {
            return new NeverResting();
        }

        @Override
        public void solve(
                @NotNull Rig rig,
                @NotNull PhysicsState state,
                @NotNull Pose pose,
                float deltaSeconds,
                @NotNull PhysicsDriver driver
        ) {
            this.solves++;
        }

        @Override
        public int substepRateHz() {
            return 60;
        }

        @Override
        public int maxSubsteps() {
            return 8;
        }

        private static final class NeverResting implements PhysicsState {

            @Override
            public void reset() {
            }

            @Override
            public boolean isAtRest() {
                return false;
            }
        }
    }

    /** Counts emissions, which is the bandwidth half of the saving. */
    private static final class CountingSink implements PoseSink {

        private int emissions;

        @Override
        public void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
            this.emissions++;
        }
    }

    /** Collects what the loop reported rather than letting it reach a logger. */
    private static final class RecordingErrors implements SimulationLogger {

        private final @NotNull List<String> messages = new ArrayList<>();

        @Override
        public void error(@NotNull String message, @NotNull Throwable thrown) {
            this.messages.add(message);
        }

        private int count() {
            return this.messages.size();
        }
    }

    /**
     * An off-tick scheduler the test steps by hand, modelling the platform's documented behaviour: it
     * swallows whatever escapes the body and keeps the task alive, which is why every loop in this
     * plugin has to do its own catching.
     */
    private static final class FakeOffTickScheduler implements SimulationScheduler {

        private final @NotNull List<Throwable> escaped = new ArrayList<>();

        private @Nullable Runnable body;
        private @Nullable FakeTask task;
        private @Nullable TimeUnit unit;
        private long initialDelay;
        private long period;
        private int scheduleCount;

        @Override
        public @NotNull SimulationTask runAtFixedRate(
                @NotNull Runnable body,
                long initialDelay,
                long period,
                @NotNull TimeUnit unit
        ) {
            this.body = body;
            this.initialDelay = initialDelay;
            this.period = period;
            this.unit = unit;
            this.scheduleCount++;
            final FakeTask scheduled = new FakeTask();
            this.task = scheduled;
            return scheduled;
        }

        private void runOnce() {
            final Runnable scheduled = this.body;
            if (scheduled == null) {
                throw new IllegalStateException("Nothing has been scheduled");
            }
            try {
                scheduled.run();
            } catch (final Throwable thrown) {
                this.escaped.add(thrown);
            }
        }

        private boolean isAlive() {
            return this.task != null && !this.task.cancelled;
        }

        private @Nullable SimulationTask task() {
            return this.task;
        }

        private int scheduleCount() {
            return this.scheduleCount;
        }

        private long initialDelay() {
            return this.initialDelay;
        }

        private long period() {
            return this.period;
        }

        private @Nullable TimeUnit unit() {
            return this.unit;
        }

        private @NotNull List<Throwable> escaped() {
            return List.copyOf(this.escaped);
        }

        private static final class FakeTask implements SimulationTask {

            private boolean cancelled;

            @Override
            public void cancel() {
                this.cancelled = true;
            }

            @Override
            public boolean isCancelled() {
                return this.cancelled;
            }
        }
    }
}
