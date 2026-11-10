package pandastic.studio.models.internal.binding;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBinderImplTest {

    private final BindingFixtures.Journal journal = new BindingFixtures.Journal();
    private final SimulationLoop loop = new SimulationLoop(
            new UnusedScheduler(), new StrictLogger(), BindingFixtures.simulationSource());
    private final BindingFixtures.MutableBindingSource bindingSource =
            new BindingFixtures.MutableBindingSource(BindingFixtures.SAMPLE_PERIOD_TICKS);

    /** A binder whose offline check runs against a model with the bones {@code modelBoneNames} has. */
    private ModelBinderImpl binder(List<String> modelBoneNames) {
        return new ModelBinderImpl(
                entity -> {
                    throw new AssertionError("These tests bind anchors directly");
                },
                this.loop,
                BindingFixtures.simulationSource(),
                this.bindingSource,
                RigBindValidator.over(modelBoneNames, BindingFixtures.renderSource()),
                LocomotionStageSource.NONE);
    }

    private ModelBinderImpl binder() {
        return binder(BindingFixtures.modelBoneNames());
    }

    // ------------------------------------------------------- offline validation

    @Test
    void bindAcceptsARigEveryBoneOfWhichTheModelHas() {
        final Rig rig = BindingFixtures.rig();

        final RiggedModelImpl model = binder().bind(rig, new FakeAnchor(this.journal));

        assertAll(
                () -> assertSame(rig, model.getRig()),
                // Validated and nothing more: no tracker, no registration, no task.
                () -> assertFalse(model.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void bindRejectsARigNamingABoneTheModelDoesNotHave() {
        final Rig rig = Rig.builder("broken", "test-model",
                        BindingFixtures.RIG_BOUNDS, BindingFixtures.PROFILE_BOUNDS)
                .root("body")
                .bone("tail", "body", pendulum())
                .build();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> binder().bind(rig, new FakeAnchor(this.journal)));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("tail"),
                        () -> "must name the bone, got: " + thrown.getMessage()),
                // The model's own bone list, because that is what somebody fixing a renamed group
                // actually needs to see.
                () -> assertTrue(thrown.getMessage().contains("cape"),
                        () -> "must list the model's bones, got: " + thrown.getMessage()));
    }

    @Test
    void bindReportsEveryMissingBoneAtOnceRatherThanTheFirst() {
        final Rig rig = Rig.builder("broken", "test-model",
                        BindingFixtures.RIG_BOUNDS, BindingFixtures.PROFILE_BOUNDS)
                .root("body")
                .bone("tail", "body", pendulum())
                .bone("whiskers", "body", pendulum())
                .bone("fringe", "body", pendulum())
                .build();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> binder().bind(rig, new FakeAnchor(this.journal)));

        // All of them, not the first. A rigger fixing a renamed group wants one edit-and-restart
        // cycle, not one per bone.
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("tail")),
                () -> assertTrue(thrown.getMessage().contains("whiskers")),
                () -> assertTrue(thrown.getMessage().contains("fringe")),
                () -> assertTrue(thrown.getMessage().contains("3 bone(s)"),
                        () -> "must count them, got: " + thrown.getMessage()));
    }

    @Test
    void bindRejectsABoneNameThatMatchesTwoModelBones() {
        // Forgiving matching on the bare name is what survives a rigger adding a tag prefix; its cost
        // is that two differently-tagged groups can collapse onto one rig name, and then the renderer
        // drives whichever it reaches first. A missing bone does nothing; an ambiguous one does
        // something wrong, so it is the harder failure of the two.
        final ModelBinderImpl binder = binder(List.of("body", "hair", "HAIR"));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> binder.bind(BindingFixtures.rig(), new FakeAnchor(this.journal)));

        assertTrue(thrown.getMessage().contains("more than one model bone"),
                () -> "must say it is ambiguous rather than missing, got: " + thrown.getMessage());
    }

    @Test
    void aRigRejectedByTheOfflineCheckIsNotTracked() {
        final ModelBinderImpl binder = binder();
        final Rig rig = Rig.builder("broken", "test-model",
                        BindingFixtures.RIG_BOUNDS, BindingFixtures.PROFILE_BOUNDS)
                .root("nonexistent")
                .build();

        assertThrows(IllegalStateException.class, () -> binder.bind(rig, new FakeAnchor(this.journal)));

        // Nothing was handed out, so nothing may be in the list the shutdown path walks.
        assertEquals(0, binder.size());
    }

    // -------------------------------------------------------------- the options

    @Test
    void bindWithoutOptionsStatesNothing() {
        final RiggedModelImpl model = binder().bind(BindingFixtures.rig(), new FakeAnchor(this.journal));

        // The two-argument form has to keep meaning exactly what it meant: everything from the server's
        // own configuration, and absent rather than a snapshot of it, so a reload still reaches it.
        assertAll(
                () -> assertSame(BindingOptions.defaults(), model.options()),
                () -> assertTrue(model.options().driverBounds().isEmpty()));
    }

    @Test
    void bindCarriesTheOptionsThroughToTheModel() {
        final BindingOptions options =
                BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 7.5F));

        final RiggedModelImpl model =
                binder().bind(BindingFixtures.rig(), new FakeAnchor(this.journal), options);

        assertSame(options, model.options(), "the binder must not interpret or rebuild them");
    }

    @Test
    void theOfflineCheckStillRunsFirstWhateverTheOptionsSay() {
        final Rig rig = Rig.builder("broken", "test-model",
                        BindingFixtures.RIG_BOUNDS, BindingFixtures.PROFILE_BOUNDS)
                .root("body")
                .bone("tail", "body", pendulum())
                .build();
        final BindingOptions options =
                BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 7.5F));

        // Every entry point funnels into one body, so a rig that cannot bind is refused the same way
        // and nothing is tracked — however the caller got in.
        assertThrows(IllegalStateException.class,
                () -> binder().bind(rig, new FakeAnchor(this.journal), options));
    }

    // ------------------------------------------------------------- the registry

    @Test
    void aBoundModelIsTrackedUntilItStops() {
        final ModelBinderImpl binder = binder();
        final FakeAnchor anchor = new FakeAnchor(this.journal);
        final RiggedModelImpl model = binder.bind(BindingFixtures.rig(), anchor);

        assertEquals(1, binder.size(), "tracked from the moment it is handed out");
        model.spawn();
        assertEquals(1, binder.size());

        model.despawn();

        // Membership is exactly "bound and not yet stopped", which is also why a handle is single use:
        // a model that could spawn again would have left the list with no way back into it.
        assertEquals(0, binder.size());
    }

    @Test
    void aModelThePlatformRetiredLeavesTheRegistryToo() {
        final ModelBinderImpl binder = binder();
        final FakeAnchor anchor = new FakeAnchor(this.journal);
        binder.bind(BindingFixtures.rig(), anchor).spawn();

        anchor.retire();

        assertEquals(0, binder.size(),
                "whichever side notices the anchor is gone, the registry has to hear about it");
    }

    @Test
    void despawnAllTearsDownEveryBoundModel() {
        final ModelBinderImpl binder = binder();
        final FakeAnchor first = new FakeAnchor(this.journal);
        final FakeAnchor second = new FakeAnchor(this.journal);
        final FakeAnchor third = new FakeAnchor(this.journal);
        binder.bind(BindingFixtures.rig("a"), first).spawn();
        binder.bind(BindingFixtures.rig("b"), second).spawn();
        // Bound and never spawned: it still has to leave the list, or the next disable walks a handle
        // nobody can use.
        binder.bind(BindingFixtures.rig("c"), third);

        assertEquals(3, binder.despawnAll());

        assertAll(
                () -> assertEquals(0, binder.size()),
                () -> assertEquals(0, this.loop.size()),
                // The point of the whole exercise: an entity-scheduled repeating task is never
                // cancelled for a plugin on disable, so a sampler nobody cancelled outlives the
                // classloader that holds its classes.
                () -> assertTrue(first.isSamplerCancelled()),
                () -> assertTrue(second.isSamplerCancelled()));
    }

    @Test
    void despawnAllIsIdempotent() {
        final ModelBinderImpl binder = binder();
        binder.bind(BindingFixtures.rig(), new FakeAnchor(this.journal)).spawn();

        assertAll(
                () -> assertEquals(1, binder.despawnAll()),
                () -> assertEquals(0, binder.despawnAll()));
    }

    @Test
    void boundModelsIsASnapshotRatherThanTheLiveSet() {
        final ModelBinderImpl binder = binder();
        final RiggedModelImpl model = binder.bind(BindingFixtures.rig(), new FakeAnchor(this.journal));

        final Collection<RiggedModelImpl> snapshot = binder.boundModels();
        model.despawn();

        assertAll(
                () -> assertEquals(1, snapshot.size(), "the snapshot must not follow the set"),
                () -> assertEquals(0, binder.size()));
    }

    // ------------------------------------------------------------------ reload

    @Test
    void reloadRedispatchesOnlyTheSamplersWhosePeriodMoved() {
        final ModelBinderImpl binder = binder();
        final FakeAnchor spawned = new FakeAnchor(this.journal);
        final FakeAnchor unspawned = new FakeAnchor(this.journal);
        binder.bind(BindingFixtures.rig("a"), spawned).spawn();
        binder.bind(BindingFixtures.rig("b"), unspawned);

        assertEquals(0, binder.reload(), "nothing moved yet");

        this.bindingSource.publish(5L);

        assertAll(
                () -> assertEquals(1, binder.reload()),
                () -> assertEquals(5L, spawned.periodTicks()),
                () -> assertFalse(unspawned.hasSampler(), "an unspawned model has no sampler to move"));
    }

    private static PhysicsProfile pendulum() {
        return PhysicsProfile
                .builder(PhysicsKind.PENDULUM, BindingFixtures.PROFILE_BOUNDS,
                        BindingFixtures.PROFILE_DEFAULTS)
                .damping(0.1F)
                .mass(1F)
                .build();
    }

    /** See {@link RiggedModelImplTest}: these tests never start the loop. */
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

    private static final class StrictLogger implements SimulationLogger {

        @Override
        public void error(String message, Throwable thrown) {
            throw new AssertionError(message, thrown);
        }
    }
}
