package pandastic.studio.models.internal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.decoration.Decoration;
import pandastic.studio.models.api.decoration.DecorationBinder;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleBinder;
import pandastic.studio.models.internal.config.ConfigFile;
import pandastic.studio.models.internal.config.ConfigReloadResult;
import pandastic.studio.models.internal.config.ConfigSnapshot;
import pandastic.studio.models.internal.decoration.DecorationSettings;
import pandastic.studio.models.internal.render.RenderSettings;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.vehicle.VehicleSettings;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the wiring is, as distinct from what any layer does.
 *
 * <h2>The four claims, and why none of them is any layer's own business</h2>
 * <ol>
 *   <li><strong>The graph is reachable and built in the one order that works.</strong> A vehicle's
 *       visual is a model handed out by the model binder, so the binder has to exist first; nothing
 *       below this type can observe that, because each layer is handed its collaborators already
 *       built.</li>
 *   <li><strong>A shutdown reaches every mandatory hook, in an order that leaves nothing
 *       scheduled.</strong> Five methods are each documented as mandatory on disable, and the cost of
 *       missing one is a repeating task that outlives the plugin's classloader — a failure that arrives
 *       during shutdown pointing at a lazy class load rather than at the model that leaked it.</li>
 *   <li><strong>A reload reaches every layer that owns a repeating task, and a refused one reaches
 *       none.</strong> Four layers own a task whose rate belongs to the platform. A layer left out
 *       keeps dispatching at the old period with nothing to report it.</li>
 *   <li><strong>Every settings source answers the same instance while the configuration behind it is
 *       unchanged.</strong> This is the regression test for the worst defect this project has had; see
 *       {@link #everySettingsSourceAnswersTheSameInstanceWhileConfigurationIsUnchanged()}.</li>
 * </ol>
 *
 * <h2>No server anywhere</h2>
 * Every platform seam is a recording double; see {@link RuntimeFixtures}. The configuration, by
 * contrast, is the real {@code ConfigService} over a temporary directory, because a fixed settings
 * source cannot be reloaded and a suite built on one would pass for a runtime that never re-read a
 * file.
 */
class ModelRuntimeTest {

    @TempDir
    Path directory;

    private final RuntimeFixtures.Journal journal = new RuntimeFixtures.Journal();

    private @NotNull RuntimeFixtures.Harness started() {
        final RuntimeFixtures.Harness harness = RuntimeFixtures.harness(this.directory, this.journal);
        this.journal.observing(harness.runtime());
        harness.runtime().start();
        return harness;
    }

    // ------------------------------------------------------------ reachability

    @Test
    void theRuntimeHandsOutAllThreeBindersAndTheAiService() {
        // The audit this work exists to answer found every one of these with zero construction sites in
        // main, which made them unreachable on a running server however well tested they were.
        final RuntimeFixtures.Harness harness = started();

        assertAll(
                () -> assertEquals(0, harness.runtime().models().size()),
                () -> assertEquals(0, harness.runtime().decorations().size()),
                () -> assertEquals(0, harness.runtime().vehicles().size()),
                () -> assertTrue(harness.runtime().creatureAi().isPresent(),
                        "the AI service has to be published when an adapter resolved"),
                () -> assertTrue(harness.runtime().loop().isRunning()),
                () -> assertTrue(harness.runtime().drivers().isRunning()),
                // Two repeating tasks for the whole server, dispatched once: the simulation loop and
                // the decoration driver loop. Anything more would be a loop per model.
                () -> assertEquals(2, harness.scheduler().dispatches().size()));
    }

    @Test
    void aModelBoundThroughTheApiSpawnsAndStepsOnTheRuntimesOwnLoop() {
        final RuntimeFixtures.Harness harness = started();
        final ModelBinder binder = harness.runtime().models();

        final RiggedModel model = binder.bind(RuntimeFixtures.rig(), RuntimeFixtures.entity());
        final boolean spawned = model.spawn();

        assertAll(
                () -> assertTrue(spawned),
                () -> assertTrue(model.isAlive()),
                // The registration is what makes the model move: a bound model nothing registered is a
                // model standing still with a tracker open.
                () -> assertEquals(1, harness.runtime().loop().size()),
                () -> assertEquals(1, harness.runtime().models().size()),
                () -> assertEquals(List.of("model0-render", "model0-schedule"),
                        this.journal.events().subList(2, this.journal.events().size()),
                        "a spawn opens the renderer-side binding and then dispatches the sampler"));
    }

    @Test
    void aDecorationBoundThroughTheApiRegistersWithBothServerWideLoops() {
        final RuntimeFixtures.Harness harness = started();
        final DecorationBinder binder = harness.runtime().decorations();

        final Decoration decoration = binder.bind(RuntimeFixtures.rig(), location());
        final boolean spawned = decoration.spawn();

        assertAll(
                () -> assertTrue(spawned),
                () -> assertTrue(decoration.isAlive()),
                () -> assertEquals(1, harness.runtime().loop().size()),
                // A decoration has no entity to be driven by, so without the driver loop it is a model
                // that hangs perfectly still forever. Both registrations, or neither is any use.
                () -> assertEquals(1, harness.runtime().drivers().size()),
                () -> assertEquals(1, harness.runtime().decorations().size()));
    }

    @Test
    void aVehicleIsBuiltOnTheModelBinderAndNotBesideIt() {
        // The construction-order proof. VehicleVisualFactory.binding(ModelBinder) means the model binder
        // has to exist before the vehicle binder; the observable consequence is that creating a vehicle
        // produces a model in the *binder's* registry, which is also what makes the model get torn down
        // at shutdown rather than leaking its sampler.
        final RuntimeFixtures.Harness harness = started();
        final VehicleBinder binder = harness.runtime().vehicles();

        final Vehicle vehicle = binder.create(
                RuntimeFixtures.rig(), RuntimeFixtures.vehicleProfile(), RuntimeFixtures.entity());

        assertAll(
                () -> assertTrue(vehicle.isAlive()),
                () -> assertEquals(1, harness.runtime().vehicles().size()),
                () -> assertEquals(1, harness.runtime().models().size(),
                        "a vehicle's visual has to be a model the binding layer handed out"),
                () -> assertEquals(1, harness.runtime().loop().size()),
                () -> assertEquals(1, harness.anchors().size()),
                () -> assertEquals(1, harness.hosts().size()));
    }

    @Test
    void theAiServiceInstallsAPlanThroughTheRuntimesOwnRuntime() {
        final RuntimeFixtures.Harness harness = started();
        final CreatureAi ai = harness.runtime().creatureAi().orElseThrow();

        final Mob mob = RuntimeFixtures.mob();
        ai.install(RuntimeFixtures.plan(), mob).join();

        assertAll(
                () -> assertEquals(1, harness.brains().liveInstallations()),
                // The bounds come from ai.yml through the runtime's own source, not from the plan.
                () -> assertEquals(RuntimeFixtures.AI_BOUNDS, ai.getBounds()));
    }

    // ---------------------------------------------------------------- shutdown

    @Test
    void shutdownStopsBothOffTickLoopsBeforeItTouchesAnythingInTheWorld() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);
        this.journal.clear();

        harness.runtime().shutdown();

        final List<String> annotated = this.journal.annotated();
        assertAll(
                () -> assertEquals(List.of("cancel", "cancel"),
                        this.journal.events().subList(0, 2),
                        "both server-wide tasks are cancelled before anything else happens"),
                // The claim the annotation exists for: by the time any teardown is recorded, neither
                // loop is live. A pass that kept running would be writing poses for models being
                // released, and no entry below could have been written while one was.
                () -> assertTrue(annotated.subList(2, annotated.size()).stream()
                                .allMatch(entry -> entry.endsWith("@loop=stopped,drivers=stopped")),
                        () -> "every teardown must happen with both loops stopped: " + annotated),
                () -> assertFalse(harness.runtime().loop().isRunning()),
                () -> assertFalse(harness.runtime().drivers().isRunning()));
    }

    @Test
    void shutdownTearsDownADependentLayerBeforeTheLayerItWasBuiltOn() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);
        this.journal.clear();

        harness.runtime().shutdown();

        // Read the whole sequence rather than spot-checking it: the ordering is the subject, and a
        // partial assertion would pass for a sequence that did the right things in the wrong order.
        //
        // model1 is the vehicle's visual and model0 is the standalone model, because the fake anchor
        // factory names them by creation order. model1 going first is the reverse-construction-order
        // claim: a vehicle is a dependent of the model binder, so it is torn down before it.
        assertEquals(List.of(
                        "cancel",             // the simulation loop
                        "cancel",             // the decoration driver loop
                        "vehicle0-cancel",    // the vehicle's control loop
                        "model1-cancel",      // its visual's sampler
                        "model1-detach",
                        "model1-close",
                        "decoration0-detach",
                        "decoration0-close",
                        "model0-cancel",      // and only then the model nothing else owned
                        "model0-detach",
                        "model0-close",
                        "ai-uninstall"),
                this.journal.events());
    }

    @Test
    void shutdownReachesEveryMandatoryHookAndLeavesNothingScheduled() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);

        harness.runtime().shutdown();

        assertAll(
                // The five methods each documented as mandatory on disable, by their effects.
                () -> assertEquals(0, harness.runtime().models().size(), "ModelBinderImpl.despawnAll"),
                () -> assertEquals(0, harness.runtime().decorations().size(),
                        "DecorationBinderImpl.despawnAll"),
                () -> assertEquals(0, harness.runtime().vehicles().size(),
                        "VehicleBinderImpl.despawnAll"),
                () -> assertEquals(0, harness.brains().liveInstallations(), "AiRuntime.shutdown"),
                () -> assertEquals(0, harness.scheduler().live(),
                        "SimulationLoop.stop and DecorationDriverLoop.stop"),

                // And the property all five exist for: a region- or entity-scheduled repeating task is
                // never swept for a plugin, so anything still dispatched here outlives the classloader.
                () -> assertTrue(harness.anchors().stream()
                                .allMatch(RecordingEntityAnchor::isSamplerCancelled),
                        "every anchor sampler has to be cancelled by hand"),
                () -> assertTrue(harness.hosts().stream()
                                .allMatch(RecordingVehicleHost::isControlCancelled),
                        "every vehicle control loop has to be cancelled by hand"),
                () -> assertTrue(harness.runtime().rigs().isEmpty()),
                () -> assertTrue(harness.runtime().isStopped()));
    }

    @Test
    void shutdownIsIdempotent() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);
        harness.runtime().shutdown();
        this.journal.clear();

        harness.runtime().shutdown();
        harness.runtime().shutdown();

        // A plugin manager that disables an already-disabled plugin, or a double onDisable, must not
        // re-run the sequence: every hook below it is idempotent, so the cost would only be a second
        // summary line claiming to have torn down nothing — but a latch is cheaper than trusting five
        // collaborators to stay idempotent forever.
        assertEquals(List.of(), this.journal.events());
    }

    // ------------------------------------------------------------------ reload

    @Test
    void anAppliedReloadRedispatchesEveryLayerThatOwnsARepeatingTask() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);
        final long loopPeriodBefore = harness.runtime().loop().scheduledPeriodNanos();
        final long driverPeriodBefore = harness.runtime().drivers().scheduledPeriodNanos();

        movePeriods();
        final ConfigReloadResult result = harness.runtime().reload();

        assertAll(
                () -> assertTrue(result.applied(), result::summary),

                // 1. The simulation loop. 40 Hz still divides the 20 Hz emission rate, so the cadence
                //    is coherent and the reload is about the dispatched period alone.
                () -> assertEquals(40, harness.runtime().loop().simulationHz()),
                () -> assertEquals(1_000_000_000L / 40,
                        harness.runtime().loop().scheduledPeriodNanos()),
                () -> assertNotEquals(loopPeriodBefore,
                        harness.runtime().loop().scheduledPeriodNanos()),

                // 2. Every bound model's own per-entity sampler.
                () -> assertTrue(harness.anchors().stream()
                                .allMatch(anchor -> anchor.scheduleCount() == 2),
                        "every sampler has to be cancelled and dispatched again"),
                () -> assertTrue(harness.anchors().stream()
                                .allMatch(anchor -> anchor.periodTicks() == 3L),
                        () -> "at the new period: " + harness.anchors().stream()
                                .map(RecordingEntityAnchor::periodTicks).toList()),

                // 3. Every vehicle's control loop.
                () -> assertEquals(2, harness.hosts().getFirst().repeatCount()),
                () -> assertEquals(2L, harness.hosts().getFirst().periodTicks()),

                // 4. The one decoration driver loop.
                () -> assertEquals(1_000_000_000L / 10,
                        harness.runtime().drivers().scheduledPeriodNanos()),
                () -> assertNotEquals(driverPeriodBefore,
                        harness.runtime().drivers().scheduledPeriodNanos()),

                // And the rigs, re-read against the snapshot that was actually published.
                () -> assertEquals(1, harness.runtime().rigs().size(),
                        "the example rig written on first start has to be picked up"));
    }

    @Test
    void aRefusedReloadRedispatchesNothingAtAll() {
        final RuntimeFixtures.Harness harness = started();
        spawnOneOfEverything(harness);
        final ConfigSnapshot live = harness.configs().snapshot();
        final long loopPeriodBefore = harness.runtime().loop().scheduledPeriodNanos();
        final long driverPeriodBefore = harness.runtime().drivers().scheduledPeriodNanos();

        // Every period moved, and one file that cannot be accepted: minMass above maxMass is legal YAML
        // and refused arithmetic. A reload is all or nothing, so the periods must not be applied either.
        movePeriods();
        RuntimeFixtures.write(this.directory, ConfigFile.PHYSICS, "minMass: 50.0");
        final ConfigReloadResult result = harness.runtime().reload();

        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertSame(live, harness.configs().snapshot(),
                        "the live snapshot has to be the same object, not an equal one"),
                () -> assertEquals(loopPeriodBefore,
                        harness.runtime().loop().scheduledPeriodNanos()),
                () -> assertEquals(driverPeriodBefore,
                        harness.runtime().drivers().scheduledPeriodNanos()),
                () -> assertTrue(harness.anchors().stream()
                                .allMatch(anchor -> anchor.scheduleCount() == 1),
                        "no sampler may be re-dispatched at a rate no file describes"),
                () -> assertEquals(1, harness.hosts().getFirst().repeatCount()),
                // Rigs too: re-reading them against a rejected snapshot would be exactly the
                // half-applied state the configuration layer exists to refuse.
                () -> assertTrue(harness.runtime().rigs().isEmpty()));
    }

    // --------------------------------------------------- the settings sources

    @Test
    void everySettingsSourceAnswersTheSameInstanceWhileConfigurationIsUnchanged() {
        // THE regression test. ModelSimulation.step detects a reloaded calibration by comparing the
        // solver it is handed against the one it holds BY IDENTITY, and rebuilds its PhysicsState when
        // they differ. A source that allocates per call therefore makes that guard fire on every step,
        // and every firing zeroes the physics, clears the pose, resets the locomotion, restarts the
        // cadence and clears the elapsed-time baseline — so the next delta is zero. The result is a
        // model frozen at its bind pose that nothing throws about and that no test of either class can
        // see, because the defect lives in the seam between them.
        //
        // This project has shipped that bug once. Every source is checked, not only the simulation's:
        // the others are not identity-compared today, and a seam that only happens not to be is one
        // refactor away from being one.
        final RuntimeFixtures.Harness harness = started();

        final SimulationSettings simulation = harness.runtime().simulationSettings().current();
        final RenderSettings render = harness.runtime().renderSettings().current();
        final DecorationSettings decoration = harness.runtime().decorationSettings().current();
        final VehicleSettings vehicle = harness.runtime().vehicleSettings().current();

        assertAll(
                () -> assertSame(simulation, harness.runtime().simulationSettings().current()),
                () -> assertSame(simulation.solver(),
                        harness.runtime().simulationSettings().current().solver(),
                        "the solver's identity is the one this contract is written for"),
                () -> assertSame(render, harness.runtime().renderSettings().current()),
                () -> assertSame(decoration, harness.runtime().decorationSettings().current()),
                () -> assertSame(vehicle, harness.runtime().vehicleSettings().current()),
                // Read a hundred times, which is three seconds of simulation at 30 Hz. Still one object.
                () -> assertTrue(IntStream.range(0, 100)
                                .allMatch(ignored -> harness.runtime().simulationSettings().current()
                                        .solver() == simulation.solver()),
                        "a hundred reads must not produce a hundred solvers"));
    }

    @Test
    void aRefusedReloadLeavesEverySettingsSourceAnsweringTheSameInstance() {
        // The other half, and the one that matters at the moment an operator breaks a file: a refused
        // reload leaves the previous snapshot live by identity, so every source built from it must keep
        // answering the same value. If it did not, a typo in physics.yml would silently reset every
        // live PhysicsState on the server.
        final RuntimeFixtures.Harness harness = started();
        final SimulationSettings before = harness.runtime().simulationSettings().current();

        RuntimeFixtures.write(this.directory, ConfigFile.PHYSICS, "minMass: 50.0");
        assertFalse(harness.runtime().reload().applied());

        assertSame(before, harness.runtime().simulationSettings().current());
    }

    @Test
    void anAppliedReloadAnswersANewInstanceSoTheIdentityGuardFiresExactlyOnce() {
        final RuntimeFixtures.Harness harness = started();
        final SimulationSettings before = harness.runtime().simulationSettings().current();
        final DecorationSettings decorationBefore =
                harness.runtime().decorationSettings().current();

        RuntimeFixtures.write(this.directory, ConfigFile.PHYSICS, "stiffnessToRate: 180.0");
        assertTrue(harness.runtime().reload().applied());

        final SimulationSettings after = harness.runtime().simulationSettings().current();
        assertAll(
                () -> assertNotSame(before, after),
                () -> assertNotSame(before.solver(), after.solver(),
                        "a new calibration has to be a new solver, or nothing rebuilds its state"),
                // And it has to settle immediately: a second read after the swap is the same object
                // again, so the guard fires once per reload rather than once per step forever.
                () -> assertSame(after, harness.runtime().simulationSettings().current()),
                // physics.yml does not describe the ambient field, but one snapshot carries every file,
                // so the decoration value is rebuilt too. Equal, and deliberately not the same object:
                // the snapshot is the unit of publication, which is what makes a mixed-generation read
                // unrepresentable.
                () -> assertEquals(decorationBefore,
                        harness.runtime().decorationSettings().current()),
                () -> assertNotSame(decorationBefore,
                        harness.runtime().decorationSettings().current()));
    }

    // ------------------------------------------------------------------ helpers

    /** One model, one decoration and one vehicle, all spawned, through the published interfaces. */
    private void spawnOneOfEverything(@NotNull RuntimeFixtures.Harness harness) {
        final Rig rig = RuntimeFixtures.rig();

        final RiggedModel model = harness.runtime().models().bind(rig, RuntimeFixtures.entity());
        model.spawn();

        final Decoration decoration = harness.runtime().decorations().bind(rig, location());
        decoration.spawn();

        harness.runtime().vehicles()
                .create(rig, RuntimeFixtures.vehicleProfile(), RuntimeFixtures.entity());

        harness.runtime().creatureAi().orElseThrow()
                .install(RuntimeFixtures.plan(), RuntimeFixtures.mob()).join();
    }

    /** Moves the configured period of all four layers that own a repeating task. */
    private void movePeriods() {
        RuntimeFixtures.write(this.directory, ConfigFile.SIMULATION,
                "loopRateHz: 40", "decorationDriverUpdateHz: 10");
        RuntimeFixtures.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 3");
        RuntimeFixtures.write(this.directory, ConfigFile.VEHICLE, "controlPeriodTicks: 2");
    }

    /**
     * A location in a world that answers nothing.
     *
     * <p>Held in a local for the duration of the call because {@link Location} keeps a reference to its
     * world that is allowed to be cleared; the decoration layer reads the world and the five numbers
     * once, here, and keeps a value rather than the location.
     */
    private @NotNull Location location() {
        final World world = world();
        return new Location(world, 0D, 64D, 0D, 0F, 0F);
    }

    private @NotNull World world() {
        return (World) Proxy.newProxyInstance(
                ModelRuntimeTest.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld";
                    default -> null;
                });
    }
}
