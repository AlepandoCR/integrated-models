package pandastic.studio.models.internal.binding;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.DriverSampler;
import pandastic.studio.models.internal.sim.ModelSimulation;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiggedModelImplTest {

    private static final float TOLERANCE = 1.0E-6F;

    /**
     * A server-wide teleport filter tighter than anything shipped.
     *
     * <p>Deliberately not the shipped 1.5/6: a test that used the defaults could not tell a binding
     * that read the configuration it was given from one that ignored it and agreed by accident.
     */
    private static final DriverBounds TIGHT_SERVER_WIDE = new DriverBounds(0.37F, 2.9F);

    /** Looser than anything shipped, and about what a projectile needs: an arrow is roughly 3. */
    private static final DriverBounds PROJECTILE_BOUNDS = new DriverBounds(4.25F, 7.5F);

    private final Rig rig = BindingFixtures.rig();
    private final BindingFixtures.Journal journal = new BindingFixtures.Journal();
    private final FakeAnchor anchor = new FakeAnchor(this.journal);
    private final SimulationLoop loop = new SimulationLoop(
            new UnusedScheduler(), new StrictLogger(), BindingFixtures.simulationSource());
    private final BindingFixtures.MutableBindingSource bindingSource =
            new BindingFixtures.MutableBindingSource(BindingFixtures.SAMPLE_PERIOD_TICKS);
    private final AtomicInteger stopNotifications = new AtomicInteger();

    RiggedModelImplTest() {
        // Every journal entry then carries how many simulations the loop was still stepping, which is
        // how an ordering claim about the loop becomes an assertion about recorded text.
        this.journal.observing(this.loop::size);
    }

    private RiggedModelImpl model() {
        return model(LocomotionStageSource.NONE, BindingFixtures.simulationSource());
    }

    private RiggedModelImpl model(LocomotionStageSource locomotion) {
        return model(locomotion, BindingFixtures.simulationSource());
    }

    private RiggedModelImpl model(
            LocomotionStageSource locomotion,
            SimulationSettingsSource simulationSettings
    ) {
        return model(locomotion, simulationSettings, BindingOptions.defaults());
    }

    private RiggedModelImpl model(
            LocomotionStageSource locomotion,
            SimulationSettingsSource simulationSettings,
            BindingOptions options
    ) {
        final Consumer<RiggedModelImpl> onStopped = ignored -> this.stopNotifications.incrementAndGet();
        return new RiggedModelImpl(this.rig, this.anchor, this.loop, simulationSettings,
                this.bindingSource, locomotion, options, onStopped);
    }

    /** A model on a deliberately tight server-wide filter, bound with {@code options}. */
    private RiggedModelImpl modelOn(DriverBounds serverWide, BindingOptions options) {
        return model(LocomotionStageSource.NONE,
                SimulationSettingsSource.fixed(BindingFixtures.simulationSettings(serverWide)),
                options);
    }

    /** Spawns, seeds the sampler at the origin, then moves by one delta and samples it. */
    private PhysicsDriver publishedAfterMoving(
            RiggedModelImpl model,
            double deltaX,
            double deltaY,
            double deltaZ
    ) {
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        model.spawn();
        // The first sample seeds; a delta measured across a bind describes nothing physical.
        this.anchor.tick();

        this.anchor.moveTo(deltaX, 64D + deltaY, deltaZ, 0F);
        this.anchor.tick();
        return model.driver().current();
    }

    // ------------------------------------------------------------------ spawn

    @Test
    void spawnOpensTheRenderingRegistersWithTheLoopAndSchedulesTheSampler() {
        final RiggedModelImpl model = model();

        assertTrue(model.spawn());

        assertAll(
                () -> assertTrue(model.isAlive()),
                () -> assertEquals(1, this.loop.size(), "the simulation must be registered"),
                () -> assertNotNull(this.anchor.rendering(), "the rendering must be opened"),
                () -> assertTrue(this.anchor.hasSampler(), "the sampler must be scheduled"),
                // The whole order, in one assertion. Rendering first because it is the step most likely
                // to fail and the cheapest to unwind; the sampler last because it is the only thing
                // that can tear the model down from another thread.
                () -> assertEquals(List.of("render(stepping=0)", "schedule(stepping=1)"),
                        this.journal.entries()));
    }

    @Test
    void theSamplerIsScheduledOnlyAfterTheSimulationIsRegistered() {
        final RiggedModelImpl model = model();
        final AtomicInteger loopSizeAtSchedule = new AtomicInteger(-1);
        this.anchor.onSchedule(() -> loopSizeAtSchedule.set(this.loop.size()));

        model.spawn();

        // A sampler dispatched before registration would publish into a simulation nothing is
        // stepping, which is harmless — but a sampler dispatched before the *simulation exists* would
        // dereference null, and this is the ordering that rules both out at once.
        assertEquals(1, loopSizeAtSchedule.get());
    }

    @Test
    void spawnRefusesToRunOffTheAnchorsOwningThread() {
        final RiggedModelImpl model = model();
        this.anchor.owned(false);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, model::spawn);

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("thread that owns it"),
                        () -> "must say why, got: " + thrown.getMessage()),
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                // Nothing was created, so there is nothing to unwind — the guard comes before the work.
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void spawnReturnsFalseForAnAnchorThatDiedBeforeIt() {
        final RiggedModelImpl model = model();
        this.anchor.die();

        assertAll(
                // A race the caller could not have avoided, so a return value rather than an exception.
                () -> assertFalse(model.spawn()),
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void spawnUnwindsWhenTheAnchorRetiresBeforeTheSamplerCanBeScheduled() {
        final RiggedModelImpl model = model();
        this.anchor.refuseScheduling();

        assertFalse(model.spawn(), "an anchor that retired mid-spawn is a race, not a failure");

        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size(), "the registration must be undone"),
                // The unwind is the whole point: a tracker left open by a failed spawn is a model
                // standing in the world that nothing holds a reference to.
                () -> assertTrue(rendering.isClosed(), "the tracker must be released"),
                () -> assertTrue(rendering.isDetached()),
                () -> assertEquals(
                        List.of("render(stepping=0)", "schedule(stepping=1)", "detach(stepping=0)",
                                "close(stepping=0)"),
                        this.journal.entries()));
    }

    @Test
    void spawnUnwindsAndRethrowsWhenTheRenderingCannotBeOpened() {
        final RiggedModelImpl model = model();
        this.anchor.failRenderWith(new IllegalStateException(
                "Rig 'test-rig' cannot bind: 2 bone(s) did not resolve [hair, cape]"));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, model::spawn);

        assertAll(
                // Propagated verbatim: the complete list of names is the only useful part of it.
                () -> assertTrue(thrown.getMessage().contains("[hair, cape]")),
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertFalse(this.anchor.hasSampler()));
    }

    @Test
    void spawningAnAlreadyAliveModelChangesNothing() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        assertAll(
                () -> assertFalse(model.spawn(), "already alive"),
                () -> assertEquals(1, this.loop.size()),
                () -> assertEquals(1, this.anchor.scheduleCount(), "no second sampler"),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void aStoppedHandleRefusesToSpawnAgain() {
        final RiggedModelImpl model = model();
        model.spawn();
        model.despawn();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, model::spawn);

        // Single use on purpose: a revived handle would hold a sink whose per-bone transformers were
        // cached against a tracker that no longer exists, which looks live and writes nowhere.
        assertTrue(thrown.getMessage().contains("single use"),
                () -> "must explain itself, got: " + thrown.getMessage());
    }

    // -------------------------------------------------------------- unbinding

    @Test
    void despawnStopsTheStepperThenThePublisherThenReleases() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        model.despawn();

        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertTrue(this.anchor.isSamplerCancelled()),
                () -> assertTrue(rendering.isDetached()),
                () -> assertTrue(rendering.isClosed()),
                // Every entry reads stepping=0, which is the assertion: the loop had already let go
                // before anything was cancelled, detached or released. And detach precedes close, so
                // an emission in flight when the loop was unregistered cannot reach a closed tracker.
                () -> assertEquals(
                        List.of("cancel(stepping=0)", "detach(stepping=0)", "close(stepping=0)"),
                        this.journal.entries()));
    }

    @Test
    void despawnIsIdempotent() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        model.despawn();
        model.despawn();
        model.despawn();

        assertAll(
                () -> assertEquals(
                        List.of("cancel(stepping=0)", "detach(stepping=0)", "close(stepping=0)"),
                        this.journal.entries()),
                () -> assertEquals(1, this.stopNotifications.get(), "the teardown runs once"));
    }

    @Test
    void despawningAModelThatNeverSpawnedIsSafeAndStillDeregisters() {
        final RiggedModelImpl model = model();

        model.despawn();

        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertTrue(model.isStopped()),
                () -> assertEquals(List.of(), this.journal.entries()),
                // Still told, or the binder's list would keep a handle nobody can ever use.
                () -> assertEquals(1, this.stopNotifications.get()));
    }

    @Test
    void despawnFromOffTheOwningThreadDefersTheTrackerRelease() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();
        this.anchor.owned(false);

        model.despawn();

        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                // Detached immediately — that is thread-free and stops the writing — but the release
                // mutates the world, so it waits for the thread allowed to do that.
                () -> assertTrue(rendering.isDetached()),
                () -> assertFalse(rendering.isClosed(), "released only on the owning thread"),
                () -> assertEquals(1, this.anchor.pendingSubmissions()),
                () -> assertEquals(
                        List.of("cancel(stepping=0)", "detach(stepping=0)", "submit(stepping=0)"),
                        this.journal.entries()));

        this.anchor.runSubmitted();
        assertTrue(rendering.isClosed(), "and released once it runs");
    }

    // ------------------------------------------------------ the anchor dying

    @Test
    void thePlatformsRetiredCallbackStopsTheModelWithoutReleasingTheTracker() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        this.anchor.retire();

        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertTrue(rendering.isDetached()),
                // Deliberate. A retired callback runs in critical code that may not remove entities,
                // and releasing a tracker removes whatever hitbox entities the renderer made for it;
                // there is also no thread left to defer to, because the scheduler that would have run
                // the deferred work is the one that just retired. The renderer closes its own registry
                // from its own entity-removal hook.
                () -> assertFalse(rendering.isClosed()),
                () -> assertEquals(0, this.anchor.pendingSubmissions(),
                        "nothing is deferred to a scheduler that has retired"),
                () -> assertTrue(this.anchor.isSamplerCancelled()),
                // One entry, not two: the platform retires the task itself before it calls back, so
                // the teardown's own cancel is already a no-op. The stepping=0 is the ordering claim —
                // the loop had let go before the sink was detached.
                () -> assertEquals(List.of("detach(stepping=0)"), this.journal.entries()));
    }

    @Test
    void theSamplerStopsTheModelWhenItFindsTheAnchorGone() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        // Dead but not retired: an entity can be invalid for a tick before its scheduler retires, and
        // the retired callback is the only other notification there is.
        this.anchor.die();
        this.anchor.tick();

        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertTrue(rendering.isDetached()),
                // The sampler runs on the owning thread and is not critical code, so this path does
                // release the tracker, inline.
                () -> assertTrue(rendering.isClosed()),
                () -> assertEquals(1, this.stopNotifications.get()));
    }

    @Test
    void aDeadAnchorStopsTheModelOnceHoweverManySidesNoticeIt() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        this.anchor.die();
        this.anchor.tick();
        // The caller, then the platform, both arriving after the sampler already noticed.
        model.despawn();
        this.anchor.retire();

        assertAll(
                () -> assertEquals(1, this.stopNotifications.get()),
                () -> assertEquals(
                        List.of("cancel(stepping=0)", "detach(stepping=0)", "close(stepping=0)"),
                        this.journal.entries()));
    }

    // ---------------------------------------------------------------- sampling

    @Test
    void theDriverPublishedIsTheOneTheSimulationReads() {
        final RiggedModelImpl model = model();
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        model.spawn();

        final ModelSimulation simulation = model.simulation();
        assertNotNull(simulation);
        assertSame(model.driver(), simulation.driver(),
                "the sampler and the solver must be two ends of one seam, not two holders that agree");

        // First sample seeds and publishes STILL: a delta against a position invented at bind time
        // would make the model's first tick of physics depend on where it happened to spawn.
        this.anchor.tick();
        assertSame(PhysicsDriver.STILL, simulation.driver().current());

        // Then a real step, forward along +Z at yaw 0, which is the owner's own forward.
        this.anchor.moveTo(0D, 64D, 0.25D, 0F);
        this.anchor.tick();

        final PhysicsDriver published = simulation.driver().current();
        assertAll(
                () -> assertEquals(0.25F, published.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.sideSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.verticalSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.yawRateRadians(), TOLERANCE));
    }

    @Test
    void theSamplerAppliesTheConfiguredTeleportThresholds() {
        // Deliberately tiny: a sampler that ignored its configuration would pass the delta through,
        // and against the stock 1.5-block threshold it would pass either way.
        final DriverBounds strict = new DriverBounds(0.05F, 0.05F);
        final RiggedModelImpl model =
                model(LocomotionStageSource.NONE,
                        SimulationSettingsSource.fixed(BindingFixtures.simulationSettings(strict)));
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        model.spawn();
        this.anchor.tick();

        this.anchor.moveTo(0D, 64D, 0.25D, 0F);
        this.anchor.tick();

        // Filtered to zero. A position delta is indistinguishable from a teleport unless whoever
        // differenced it says so, and feeding a teleport to the wind term launches the whole rig.
        assertEquals(0F, model.driver().current().forwardSpeed(), TOLERANCE);
    }

    // -------------------------------------------------- per-binding driver bounds

    /**
     * An arrow's genuine motion, published rather than discarded.
     *
     * <p>The regression test for the whole feature. A projectile binds fine — nothing in this layer
     * narrows to a living entity — but an arrow at full draw covers about three blocks in a tick, and
     * against a player-sized server-wide ceiling every one of those ticks is classified as a teleport.
     * The model then flies correctly, because the renderer moves it, while its physics is told
     * {@code STILL} forever: no feathers streaming back, no ribbon reacting, no error and no log line.
     *
     * <p>The pair with {@link #aFastEntityOnTheServerWideFilterIsStillTreatedAsTeleporting()} is the
     * assertion. One differs from the other in the options and nothing else, and the published forward
     * speed goes from zero to the real three blocks.
     */
    @Test
    void aBindingWithARaisedCeilingPublishesAFastEntitysRealMotion() {
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE,
                BindingOptions.ofDriverBounds(PROJECTILE_BOUNDS));

        final PhysicsDriver published = publishedAfterMoving(model, 0D, 0D, 3.1D);

        assertAll(
                () -> assertEquals(3.1F, published.forwardSpeed(), TOLERANCE),
                () -> assertTrue(published.isMoving(),
                        "the secondary physics has to learn the thing it hangs off is moving"));
    }

    @Test
    void aFastEntityOnTheServerWideFilterIsStillTreatedAsTeleporting() {
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE, BindingOptions.defaults());

        final PhysicsDriver published = publishedAfterMoving(model, 0D, 0D, 3.1D);

        // Unchanged, and deliberately so: the server-wide number is what a player's teleport is
        // measured against, which is why raising it was never the fix.
        assertEquals(0F, published.forwardSpeed(), TOLERANCE);
    }

    @Test
    void aBindingThatStatesNothingGetsTheSuppliedServerWideBounds() {
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE, BindingOptions.defaults());

        // 0.3 is under the supplied 0.37 and over nothing else in sight: it would be discarded by a
        // stricter default and passed by the shipped 1.5, so only the supplied value explains it.
        final PhysicsDriver published = publishedAfterMoving(model, 0D, 0D, 0.3D);

        assertAll(
                () -> assertEquals(0.3F, published.forwardSpeed(), TOLERANCE),
                () -> assertTrue(model.options().driverBounds().isEmpty(),
                        "and it must still be recorded as having stated nothing"));
    }

    @Test
    void theHorizontalCeilingIsIndependentOfTheVerticalOne() {
        // Loose horizontally, tighter than gravity vertically — a projectile that should stream behind
        // its own flight but not react to falling.
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE,
                BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 0.09F)));

        final PhysicsDriver published = publishedAfterMoving(model, 0D, -0.5D, 3.1D);

        assertAll(
                () -> assertEquals(3.1F, published.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.verticalSpeed(), TOLERANCE));
    }

    @Test
    void theVerticalCeilingIsIndependentOfTheHorizontalOne() {
        // The other way round, because one ceiling quietly standing in for both would pass the test
        // above on its own.
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE,
                BindingOptions.ofDriverBounds(new DriverBounds(0.09F, 5.5F)));

        final PhysicsDriver published = publishedAfterMoving(model, 0D, -3.9D, 0.5D);

        assertAll(
                () -> assertEquals(-3.9F, published.verticalSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.forwardSpeed(), TOLERANCE));
    }

    @Test
    void aGenuineTeleportIsStillDiscardedAtTheRaisedCeiling() {
        final RiggedModelImpl model = modelOn(TIGHT_SERVER_WIDE,
                BindingOptions.ofDriverBounds(PROJECTILE_BOUNDS));

        // A portal, a /tp, a respawn. The point of the per-binding ceiling is that it is still a
        // ceiling: feeding this to the wind term launches every bone in the rig at once, and the chain
        // then takes a second to crawl back from its limits.
        final PhysicsDriver published = publishedAfterMoving(model, 0D, 180D, 420D);

        assertAll(
                () -> assertEquals(0F, published.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, published.verticalSpeed(), TOLERANCE),
                () -> assertFalse(published.isMoving()));
    }

    @Test
    void stanceFlagsReachTheGait() {
        final FakeLocomotion gait = new FakeLocomotion();
        final RiggedModelImpl model = model(bound -> FakeLocomotion.stageFor(bound, gait));
        model.spawn();

        final FakeLocomotion.RecordingGaitState state = gait.state();
        assertAll(
                () -> assertFalse(state.isSneaking()),
                () -> assertFalse(state.isAirborne()));

        this.anchor.stance(true, true);
        this.anchor.tick();

        assertAll(
                // Written on the owning region thread, which is the only thread that can know them.
                () -> assertTrue(state.isSneaking()),
                () -> assertTrue(state.isAirborne()));

        this.anchor.stance(false, false);
        this.anchor.tick();

        assertAll(
                () -> assertFalse(state.isSneaking()),
                () -> assertFalse(state.isAirborne()));
    }

    @Test
    void positionAndStanceComeFromOneReadOfTheAnchor() {
        final FakeLocomotion gait = new FakeLocomotion();
        final RiggedModelImpl model = model(bound -> FakeLocomotion.stageFor(bound, gait));
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        model.spawn();
        this.anchor.tick();

        this.anchor.moveTo(0D, 64D, 0.1D, 0F);
        this.anchor.stance(true, false);
        this.anchor.tick();

        // Both halves of the sample describe the same instant. A gait blended from this tick's stance
        // against last tick's speed is the same disagreement the one-sample-per-step rule prevents, one
        // layer earlier.
        assertAll(
                () -> assertEquals(0.1F, model.driver().current().forwardSpeed(), TOLERANCE),
                () -> assertTrue(gait.state().isSneaking()));
    }

    @Test
    void aSamplerBodyThatLandsAfterTheModelStoppedDoesNothing() {
        final FakeLocomotion gait = new FakeLocomotion();
        final RiggedModelImpl model = model(bound -> FakeLocomotion.stageFor(bound, gait));
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        model.spawn();
        this.anchor.tick();

        model.despawn();
        this.anchor.moveTo(0D, 64D, 0.25D, 0F);
        this.anchor.stance(true, true);
        // Cancelling a repeating task does not wait for a body already in flight, so the model's own
        // gate is the only thing that can stop one. This is that body landing late.
        this.anchor.tickEvenIfCancelled();

        assertAll(
                () -> assertSame(PhysicsDriver.STILL, model.driver().current(),
                        "the teardown cleared the sample and the late body must not republish"),
                () -> assertFalse(gait.state().isSneaking(),
                        "nor reach a gait nothing is stepping any more"));
    }

    @Test
    void theSimulationTheSamplerFeedsEmitsThroughTheBindingsSink() {
        final RiggedModelImpl model = model();
        model.spawn();

        final ModelSimulation simulation = model.simulation();
        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(simulation);
        assertNotNull(rendering);

        // Three steps at 60 Hz with a 20 Hz cadence: the third is the emission step.
        for (int step = 0; step < 3; step++) {
            simulation.step(step * 16_666_667L);
        }

        assertAll(
                () -> assertEquals(1, rendering.accepted(),
                        "the simulation must hold the sink the binding opened"),
                () -> assertEquals(simulation.cadence().interpolationTicks(),
                        rendering.lastInterpolationTicks(),
                        "and the window must come from the cadence, not from the sink"));
    }

    @Test
    void aDetachedSinkRefusesPosesEvenIfSomethingStillStepsTheSimulation() {
        final RiggedModelImpl model = model();
        model.spawn();
        final ModelSimulation simulation = model.simulation();
        final FakeRenderBinding rendering = this.anchor.rendering();
        assertNotNull(simulation);
        assertNotNull(rendering);

        model.despawn();
        for (int step = 0; step < 3; step++) {
            simulation.step(step * 16_666_667L);
        }

        // The loop's unregister does not join an iteration in flight, so detaching is what covers the
        // window rather than hoping it is narrow.
        assertAll(
                () -> assertEquals(0, rendering.accepted()),
                () -> assertTrue(rendering.refused() > 0));
    }

    // ------------------------------------------------------------------ reload

    @Test
    void theSamplerPeriodComesFromConfiguration() {
        this.bindingSource.publish(7L);
        final RiggedModelImpl model = model();

        model.spawn();

        assertAll(
                () -> assertEquals(7L, this.anchor.periodTicks()),
                () -> assertEquals(7L, model.scheduledPeriodTicks()));
    }

    @Test
    void theSamplerReadsItsPeriodOnceAtDispatchRatherThanPerSample() {
        final RiggedModelImpl model = model();
        model.spawn();
        final int readsAfterSpawn = this.bindingSource.reads();

        for (int tick = 0; tick < 20; tick++) {
            this.anchor.tick();
        }

        // Not thrift for its own sake: a live settings source builds its value per call, so a read per
        // sample would be an allocation per model per tick on the region threads.
        assertEquals(readsAfterSpawn, this.bindingSource.reads());
    }

    @Test
    void reloadRedispatchesTheSamplerWhenThePeriodMoves() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        this.bindingSource.publish(4L);
        assertTrue(model.reload());

        assertAll(
                () -> assertEquals(4L, this.anchor.periodTicks()),
                () -> assertEquals(4L, model.scheduledPeriodTicks()),
                () -> assertEquals(2, this.anchor.scheduleCount()),
                // Cancel before schedule, never the other way round: two live samplers would both
                // difference against the same remembered position and publish half-intervals.
                () -> assertEquals(List.of("cancel(stepping=1)", "schedule(stepping=1)"),
                        this.journal.entries()),
                () -> assertTrue(model.isAlive()));
    }

    @Test
    void reloadDoesNothingWhenThePeriodIsUnchanged() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();

        assertAll(
                () -> assertFalse(model.reload()),
                () -> assertEquals(1, this.anchor.scheduleCount()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void reloadDoesNothingForAModelThatIsNotAlive() {
        final RiggedModelImpl model = model();
        this.bindingSource.publish(4L);

        assertFalse(model.reload());
    }

    @Test
    void reloadStopsTheModelIfTheAnchorRetiredWhileRedispatching() {
        final RiggedModelImpl model = model();
        model.spawn();
        this.journal.clear();
        this.bindingSource.publish(4L);
        this.anchor.refuseScheduling();

        assertFalse(model.reload());

        // The cancel has already happened by then, so nothing is left that would ever notice the
        // anchor is gone. Stopping here is the only thing that can.
        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(1, this.stopNotifications.get()));
    }

    // ---------------------------------------------------------------- identity

    @Test
    void identityIsStableAndIndependentOfTheAnchor() {
        final RiggedModelImpl first = model();
        final RiggedModelImpl second = model();

        assertAll(
                () -> assertEquals(first.getUniqueIdentifier(), first.getUniqueIdentifier()),
                // Two models on one entity are legal — a cloak and a tail — so the entity's identity
                // cannot name a binding.
                () -> assertNotEquals(first.getUniqueIdentifier(), second.getUniqueIdentifier()),
                () -> assertSame(this.rig, first.getRig()),
                () -> assertSame(this.anchor, first.anchor()));
    }

    @Test
    void anUnspawnedModelHasNoSimulationAndAStillDriver() {
        final RiggedModelImpl model = model();

        assertAll(
                () -> assertFalse(model.isAlive()),
                () -> assertNull(model.simulation()),
                // STILL rather than null, so a step that beats the first sample simulates a motionless
                // owner instead of needing a null check in the hot path.
                () -> assertSame(PhysicsDriver.STILL, model.driver().current()));
    }

    @Test
    void theSamplerSeedsFreshSoABindDeltaIsNeverMeasured() {
        // Not through the model: the guarantee belongs to DriverSampler and this is the one place the
        // binding layer depends on it, so it is worth pinning down where a reader will look for it.
        final DriverSampler sampler = new DriverSampler(BindingFixtures.simulationSource());

        assertSame(PhysicsDriver.STILL, sampler.sample(0D, 64D, 0D, 0F));
        assertEquals(0.25F, sampler.sample(0D, 64D, 0.25D, 0F).forwardSpeed(), TOLERANCE);

        sampler.reset();
        assertSame(PhysicsDriver.STILL, sampler.sample(100D, 64D, 100D, 0F),
                "a delta across a rebind describes nothing physical");
    }

    /**
     * A scheduler that refuses to be used.
     *
     * <p>These tests step simulations directly rather than through the loop, because what they are
     * about is which simulations the loop holds and in what order they get there. A scheduler that
     * threw if asked proves the loop was never started, so nothing can be passing by accident on a
     * background task.
     */
    private static final class UnusedScheduler implements SimulationScheduler {

        @Override
        public SimulationTask runAtFixedRate(
                Runnable body,
                long initialDelay,
                long period,
                TimeUnit unit
        ) {
            throw new AssertionError("The binding tests never start the loop");
        }
    }

    /**
     * Fails the test on any diagnostic.
     *
     * <p>Nothing the binding layer does should reach the loop's error channel, and a logger that
     * swallowed one would let a reload that silently failed to rebuild look like a reload that worked.
     */
    private static final class StrictLogger implements SimulationLogger {

        @Override
        public void error(String message, Throwable thrown) {
            throw new AssertionError(message, thrown);
        }
    }
}
