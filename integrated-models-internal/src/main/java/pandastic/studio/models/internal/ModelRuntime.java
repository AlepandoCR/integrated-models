package pandastic.studio.models.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.ai.AiRuntime;
import pandastic.studio.models.internal.ai.AiSettings;
import pandastic.studio.models.internal.ai.AiSettingsSource;
import pandastic.studio.models.internal.binding.AnchorFactory;
import pandastic.studio.models.internal.binding.AttackTracker;
import pandastic.studio.models.internal.binding.DerivedLocomotionStageSource;
import pandastic.studio.models.internal.binding.LocomotionStageSource;
import pandastic.studio.models.internal.binding.ModelBinderImpl;
import pandastic.studio.models.internal.binding.RigBindValidator;
import pandastic.studio.models.internal.config.ConfigReloadResult;
import pandastic.studio.models.internal.config.ConfigService;
import pandastic.studio.models.internal.config.ConfigSnapshot;
import pandastic.studio.models.internal.decoration.DecorationAnchorFactory;
import pandastic.studio.models.internal.decoration.DecorationBinderImpl;
import pandastic.studio.models.internal.decoration.DecorationDriverLoop;
import pandastic.studio.models.internal.decoration.DecorationSettings;
import pandastic.studio.models.internal.decoration.DecorationSettingsSource;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.NmsBridgeLookup;
import pandastic.studio.models.internal.nms.NmsBridgeResolution;
import pandastic.studio.models.internal.nms.RiderInputBridge;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;
import pandastic.studio.models.internal.render.RenderSettings;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.rig.RigDiagnostic;
import pandastic.studio.models.internal.rig.RigLoadResult;
import pandastic.studio.models.internal.rig.RigLoadSettings;
import pandastic.studio.models.internal.rig.RigLoader;
import pandastic.studio.models.internal.rig.ModelBoneSource;
import pandastic.studio.models.internal.rig.RigFactory;
import pandastic.studio.models.internal.sim.AsyncSimulationScheduler;
import pandastic.studio.models.internal.sim.ComponentSimulationLogger;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.vehicle.VehicleBinderImpl;
import pandastic.studio.models.internal.vehicle.VehicleHostFactory;
import pandastic.studio.models.internal.vehicle.VehicleSettings;
import pandastic.studio.models.internal.vehicle.VehicleSettingsSource;
import pandastic.studio.models.internal.vehicle.VehicleVisualFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Everything the plugin owns at runtime, assembled in one place: the configuration, the rigs read from
 * disk, the two off-tick loops, the three binders a consumer reaches through the API, and the AI layer.
 *
 * <h2>Why this is not spread across the plugin class</h2>
 * The ordering constraints here are real and most of them fail silently. Configuration has to be live
 * before a solver exists, because a solver is built <em>from</em> the calibration. Rigs have to be read
 * against the configured bounds, because a clamp target has to be the same object the rig builder later
 * validates against. The model binder has to exist before the vehicle binder, because a vehicle's
 * visual is a model bound through it. And the loops have to be the <em>last</em> thing started and the
 * <em>first</em> thing stopped. Keeping that in one type makes the order reviewable rather than
 * distributed across two lifecycle methods.
 *
 * <h2>Shutdown ordering is not a tidiness concern</h2>
 * {@link #shutdown()} is the one method here that cannot be got wrong quietly, and the plugin calls it
 * as the first statement of {@code onDisable}. A plugin's classloader is closed <em>before</em> the
 * platform runs its own task cancellation, and region- and entity-scheduled repeating tasks are
 * <strong>never</strong> swept for a plugin at all — unlike main-scheduler tasks. So a task still
 * holding these classes fails on the first lazy class load after that close, during shutdown, with a
 * stack trace that points nowhere near the model that leaked it. Every mandatory hook is named in that
 * method's own documentation, with the argument for where it sits.
 *
 * <h2>One snapshot read per question, and the same value until it moves</h2>
 * The settings seams below are built once, in the constructor, and each hands out <em>the same</em>
 * value object for as long as the configuration behind it is unchanged. That is a correctness
 * requirement rather than an optimisation for at least one of them; see {@link #simulationSettings()}
 * for what happens when it is not met, and {@link SnapshotCache} for the mechanism they share.
 * Consumers are contractually required to read a source once per step rather than per value, which is
 * what stops a step straddling a reload and mixing two generations of configuration.
 */
final class ModelRuntime {

    /** Directory under the plugin's data folder that rig files are read from. */
    private static final String RIG_DIRECTORY = "rigs";

    /** Extension a rig file must carry to be picked up. */
    private static final String RIG_EXTENSION = ".yml";

    /**
     * The movement seam for a server with no NMS adapter.
     *
     * <p>Every method of {@link MovementBridge} is a declining default, so an empty implementation is
     * the complete "there is no adapter" answer rather than a stub with holes in it: it reports no
     * support for an in-place move, which routes every vehicle step through the platform's asynchronous
     * teleport, and it reports no root as client-steered, which leaves
     * {@code ClientSteeredRoots}' own type check as the authority. A vehicle therefore works on a
     * server with no adapter — it just pays a teleport per step — which is why this is a constant here
     * instead of a reason to refuse to build the layer.
     */
    private static final @NotNull MovementBridge DECLINING_MOVEMENT = new MovementBridge() {
    };

    /**
     * The rider input seam for a server with no NMS adapter.
     *
     * <p>{@link RiderInputBridge}'s own default reads {@code Player#getCurrentInput()}, which is
     * platform API rather than NMS, so this is a fully working implementation and not a degraded one.
     * The adapter exists to let a future version read the input earlier in the tick, not to make it
     * readable at all.
     */
    private static final @NotNull RiderInputBridge DECLINING_INPUT = new RiderInputBridge() {
    };

    private final @NotNull ComponentLogger logger;
    private final @NotNull ConfigService configs;
    private final @NotNull Path dataFolder;

    private final @NotNull RenderSettingsSource renderSettings;
    private final @NotNull SimulationSettingsSource simulationSettings;
    private final @NotNull DecorationSettingsSource decorationSettings;
    private final @NotNull VehicleSettingsSource vehicleSettings;

    private final @NotNull SimulationLoop loop;
    private final @NotNull DecorationDriverLoop drivers;
    private final @NotNull ModelBinderImpl models;
    private final @NotNull DecorationBinderImpl decorations;
    private final @NotNull VehicleBinderImpl vehicles;
    private final @NotNull AiRuntime ai;
    private final @NotNull RigFactory rigFactory;

    private final @NotNull Map<String, Rig> rigs = new ConcurrentHashMap<>();

    /** Guarded by {@code this}. See {@link #shutdown()} for why a latch rather than nothing. */
    private boolean stopped;

    /**
     * Assembles the runtime against the real server.
     *
     * <p>A static factory rather than a constructor because the platform has to be reached in two
     * passes: the render seam is read out of the configuration, and three of the factories below —
     * anchors, decoration anchors and the rig validator — are built <em>from</em> that seam. A
     * constructor cannot produce a value and then use it to build its own arguments, and building the
     * render seam twice would hand the renderer and the validator two different objects describing the
     * same configuration.
     *
     * <p>Nothing is started here. A caller that decides not to enable has nothing to unwind.
     *
     * @param plugin the plugin whose data folder, schedulers and logger this runtime uses
     * @return the assembled runtime
     */
    static @NotNull ModelRuntime create(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        final ComponentLogger logger = plugin.getComponentLogger();

        // 1. Configuration, before anything that is built from a configured value. Blocking on the
        //    first load is deliberate: the plugin should not enable on defaults while a load it could
        //    have waited for completes behind it.
        final ConfigService configs = ConfigService.create(plugin);
        report(logger, configs, configs.reloadNow(), "Loaded");

        // 2. The render seam, because the three factories below are built from it.
        final RenderSettingsSource renderSettings = renderSource(configs);
        final AttackTracker attacks = new AttackTracker();
        Bukkit.getPluginManager().registerEvents(attacks, plugin);

        // 3. The NMS adapter, or the reason there is none. Resolved before the AI layer, which is the
        //    only thing that cannot be built without one, and before the vehicle hosts, which can.
        final NmsBridgeResolution nms = NmsBridgeLookup.resolve(Bukkit.getMinecraftVersion());
        final NmsBridge bridge = nms.bridge();

        // 4. The AI layer. Never refuses: a server with no adapter gets a runtime whose service is
        //    empty and whose reason start() logs once.
        final AiRuntime ai = AiRuntime.create(plugin, nms, aiSource(configs),
                (message, failure) -> logger.error(Component.text(message, NamedTextColor.RED),
                        failure));

        return new ModelRuntime(
                logger,
                configs,
                plugin.getDataFolder().toPath(),
                renderSettings,
                new AsyncSimulationScheduler(plugin),
                new ComponentSimulationLogger(logger),
                SimulationClock.SYSTEM,
                AnchorFactory.entities(plugin, renderSettings, attacks),
                DecorationAnchorFactory.worlds(plugin, renderSettings),
                VehicleHostFactory.entities(plugin,
                        bridge == null ? DECLINING_MOVEMENT : bridge.movement(),
                        bridge == null ? DECLINING_INPUT : bridge.riderInput()),
                RigBindValidator.renderer(renderSettings),
                // Rigs with conventional limb names receive the configured walk cycle; decorative rigs
                // still return null and retain their physics-only behaviour.
                new DerivedLocomotionStageSource(configs),
                ai);
    }

    /**
     * Assembles the runtime over explicit seams.
     *
     * <p>Every parameter is something only the platform can supply, which is the whole reason the list
     * is this long: the layers themselves deal in anchors, hosts, schedulers and clocks rather than in
     * entities, worlds and {@code Plugin}, so this is the one place the two meet. It is consequently
     * also the only seam a test needs — the entire object graph below, including the order it is built
     * in and the order it is torn down in, is exercisable with no server anywhere.
     *
     * <p>The loops are built but <strong>not started</strong>; see {@link #start()}.
     *
     * @param logger            where every lifecycle message goes
     * @param configs           the live configuration. Already loaded: a caller that wants the files
     *                          read has called {@link ConfigService#reloadNow()} first, because
     *                          reporting the outcome needs a logger and that is the caller's
     * @param dataFolder        the plugin's data folder, under which {@code rigs/} is read
     * @param renderSettings    where the render layer reads its settings, and what the anchor factories
     *                          and the validator were built from
     * @param scheduler         where both off-tick repeating tasks come from
     * @param simulationLogger  where a simulation or control step that threw is reported
     * @param clock             the monotonic clock the ambient field's phase and every vehicle step are
     *                          measured against
     * @param anchors           how an entity becomes something the binding layer may read
     * @param decorationAnchors how a world becomes something a decoration may stand in
     * @param hosts             how an entity becomes something the vehicle layer may drive
     * @param validator         the offline rig check every binder runs before it hands out a handle
     * @param locomotion        the walk cycle for a rig, or {@link LocomotionStageSource#NONE}
     * @param ai                the AI layer, or the reason there is none
     */
    ModelRuntime(
            @NotNull ComponentLogger logger,
            @NotNull ConfigService configs,
            @NotNull Path dataFolder,
            @NotNull RenderSettingsSource renderSettings,
            @NotNull SimulationScheduler scheduler,
            @NotNull SimulationLogger simulationLogger,
            @NotNull SimulationClock clock,
            @NotNull AnchorFactory anchors,
            @NotNull DecorationAnchorFactory decorationAnchors,
            @NotNull VehicleHostFactory hosts,
            @NotNull RigBindValidator validator,
            @NotNull LocomotionStageSource locomotion,
            @NotNull AiRuntime ai
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.renderSettings = Objects.requireNonNull(renderSettings, "renderSettings");
        this.ai = Objects.requireNonNull(ai, "ai");

        // The remaining seams are pure functions of the configuration, so they are built here rather
        // than demanded of a caller. Each is memoised against the live snapshot; see SnapshotCache.
        this.simulationSettings = simulationSource(configs);
        this.decorationSettings = decorationSource(configs);
        this.vehicleSettings = vehicleSource(configs);

        // 1. The two off-tick loops, before anything that registers with them. Both are server-wide and
        //    both are stopped first on the way out.
        this.loop = new SimulationLoop(scheduler, simulationLogger, this.simulationSettings);
        this.drivers = new DecorationDriverLoop(
                scheduler, clock, this.decorationSettings, this.simulationSettings);

        // 2. The binders. The model binder comes before the vehicle binder and that is not cosmetic: a
        //    vehicle's visual is a model bound through VehicleVisualFactory.binding(ModelBinder), so the
        //    vehicle layer is built *on* the binding layer and cannot be constructed before it. It is
        //    also why the two are torn down in the opposite order; see shutdown().
        this.models = new ModelBinderImpl(anchors, this.loop, this.simulationSettings,
                configs::binding, validator, locomotion);
        this.decorations = new DecorationBinderImpl(decorationAnchors, this.loop, this.drivers,
                this.simulationSettings, validator);
        this.vehicles = new VehicleBinderImpl(hosts, VehicleVisualFactory.binding(this.models),
                this.vehicleSettings, clock, simulationLogger);

        this.rigFactory = RigFactory.of(this::rigs, ModelBoneSource.renderer(), this::rigLoadSettings);
        loadRigs();
    }

    /**
     * Starts both off-tick loops, and says once what this server can and cannot do.
     *
     * <p>Nothing is simulated until a model, a decoration or a vehicle registers with them: starting
     * them is dispatching two empty repeating tasks, which is cheap and is what makes a later bind a
     * pure registration rather than a scheduling decision.
     *
     * <p>The order between the two does not matter — they share nothing and neither drives the other —
     * but both have to be running before the first bind, and a reader looking for the symmetry with
     * {@link #shutdown()} should find it here.
     */
    void start() {
        this.loop.start();
        this.drivers.start();

        final EmissionCadence cadence = this.simulationSettings.current().cadence();
        this.logger.info(Component.text("Simulating at " + this.loop.simulationHz()
                + " Hz, emitting at " + cadence.emissionHz() + " Hz with a "
                + cadence.interpolationTicks() + "-tick interpolation window"));

        // Logged once, and as a warning rather than an error: the rendering pipeline does not need NMS,
        // so this is a reduced feature set rather than a fault. An operator asking why the AI service is
        // absent has no other way to find out which adapters were passed over and why.
        if (this.ai.creatureAi().isEmpty()) {
            this.logger.warn(Component.text(this.ai.unavailableReason(), NamedTextColor.YELLOW));
        }
    }

    /**
     * Stops everything this plugin scheduled, and must be the first thing {@code onDisable} does.
     *
     * <h2>The order, and the argument for each position</h2>
     * <ol>
     *   <li><strong>The simulation loop, then the decoration driver loop.</strong> Both are off-tick
     *       repeating tasks, and stopping them first is what makes every step below happen with nothing
     *       concurrently stepping a simulation or publishing a driver. Torn down in the opposite order
     *       they would be reading registries that are being emptied underneath them — not a crash,
     *       because every registration is checked inside the pass, but a pass that writes poses for
     *       models that are being released.</li>
     *   <li><strong>Vehicles, then decorations, then models</strong> — the reverse of the order they
     *       were constructed in. A vehicle's visual is a model handed out by the model binder, so a
     *       vehicle is a dependent of it: tearing the models down first would leave every vehicle's
     *       teardown running against a visual that is already gone. That is survivable, because a
     *       despawn is idempotent, and it is still the wrong direction — a dependent goes before the
     *       thing it was built on, and then each layer only has to sweep what nobody else owns.</li>
     *   <li><strong>The AI layer last.</strong> It shares no task, no registry and no handle with the
     *       four above, so its position is not what makes the tasks safe — every one of its sensor
     *       tasks is cancelled synchronously by its own uninstall whenever that happens. It goes last so
     *       that the sequence reads in one direction, "stop stepping, remove from the world, stop
     *       deciding", and so that a behaviour body mid-flight on a region thread can only ever observe
     *       handles that are already gone. Every handle answers a call after its teardown as a no-op,
     *       which is exactly the state that is safe to be seen in.</li>
     *   <li><strong>The rigs.</strong> Pure data, so this is only release; it is last because a
     *       teardown above is still entitled to name the rig it was driving in a message.</li>
     * </ol>
     *
     * <h2>Why the latch</h2>
     * Every hook below is individually idempotent, so a second call would be harmless rather than
     * wrong. The latch is what makes it also silent and free: a double disable — or a plugin manager
     * that disables an already-disabled plugin — should not produce a second summary line claiming to
     * have torn down nothing. It is <em>not</em> a guard against the sequence failing partway: nothing
     * in it throws, because each binder isolates its own handles' failures.
     */
    synchronized void shutdown() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;

        // 1. Nothing may still be stepping.
        this.loop.stop();
        this.drivers.stop();

        // 2. The world, in the reverse of construction order.
        final int stoppedVehicles = this.vehicles.despawnAll();
        final int stoppedDecorations = this.decorations.despawnAll();
        final int stoppedModels = this.models.despawnAll();

        // 3. The AI layer.
        final int uninstalled = this.ai.shutdown();

        // 4. State.
        this.rigs.clear();

        this.logger.info(Component.text("Stopped: " + stoppedModels + " model(s), "
                + stoppedDecorations + " decoration(s), " + stoppedVehicles + " vehicle(s), "
                + uninstalled + " AI installation(s)"));
    }

    /** Whether {@link #shutdown()} has run. */
    synchronized boolean isStopped() {
        return this.stopped;
    }

    /** The loop every simulation is driven from. */
    @NotNull SimulationLoop loop() {
        return this.loop;
    }

    /**
     * The one task every decoration's driver is published from.
     *
     * <p>Not reachable from the plugin class, unlike {@link #loop()}: a decoration registers itself
     * with this through the binder, so nothing outside this type has a reason to hold it. It is here
     * because the shutdown and reload sequences both name it, and a test asserting those has to be able
     * to see whether it is running.
     */
    @NotNull DecorationDriverLoop drivers() {
        return this.drivers;
    }

    /** The live configuration. */
    @NotNull ConfigService configs() {
        return this.configs;
    }

    /** How a consumer puts a rig on an entity. */
    @NotNull ModelBinderImpl models() {
        return this.models;
    }

    /** How a consumer stands a rig at a location. */
    @NotNull DecorationBinderImpl decorations() {
        return this.decorations;
    }

    /** How a consumer makes an entity a vehicle. */
    @NotNull VehicleBinderImpl vehicles() {
        return this.vehicles;
    }

    /** The AI service, if this server has an NMS adapter. */
    @NotNull Optional<CreatureAi> creatureAi() {
        return this.ai.creatureAi();
    }

    /** Where the render layer reads its settings. */
    @NotNull RenderSettingsSource renderSettings() {
        return this.renderSettings;
    }

    /**
     * Where the simulation reads its rate, calibration, cadence and throttle.
     *
     * <h2>Why the built value is memoised against the snapshot</h2>
     * {@link pandastic.studio.models.internal.sim.ModelSimulation#step} detects a reloaded calibration
     * by comparing the solver it is handed against the one it holds <strong>by identity</strong>, and
     * rebuilds its {@link pandastic.studio.models.api.physics.PhysicsState} when they differ. That is
     * the right test — a state carries limits derived from the step size of the solver that built it, so
     * only "the very same solver" is safe to keep a state for — but it makes the <em>allocation</em>
     * pattern here part of the contract.
     *
     * <p>Building a fresh {@link AngularPhysicsSolver} on every call satisfies the declaration and
     * breaks the simulation completely, silently, and only in production: the identity guard then fires
     * on every step, so every step zeroes the physics state, clears the pose, resets the locomotion and
     * restarts the cadence phase. The reset also clears the elapsed-time baseline, so the very next
     * {@code measure} returns a zero delta — and a simulation integrating zero seconds sixty times a
     * second is a model frozen at its bind pose with its at-rest emission gate re-armed every step.
     * Nothing throws and no test of either class can see it, because the defect is the seam between
     * them.
     *
     * <p>So one solver per snapshot, cached by the snapshot's identity. A snapshot is swapped
     * atomically and only by a reload, which makes "the calibration changed" and "the instance changed"
     * the same event again — the guard fires exactly once per reload, which is what it was written for.
     *
     * @return the simulation seam, which is one object for the life of this runtime
     */
    @NotNull SimulationSettingsSource simulationSettings() {
        return this.simulationSettings;
    }

    /**
     * Where the decoration driver loop reads the ambient field and its pass rate.
     *
     * <p>Handed to that loop in the constructor, so nothing calls this in production. It exists because
     * the identity contract documented on {@link #simulationSettings()} applies to every source here,
     * and a seam nothing can observe is a seam nothing can regression-test.
     */
    @NotNull DecorationSettingsSource decorationSettings() {
        return this.decorationSettings;
    }

    /**
     * Where the vehicle layer reads its bounds, clamps and control period.
     *
     * <p>Observable for the same reason {@link #decorationSettings()} is.
     */
    @NotNull VehicleSettingsSource vehicleSettings() {
        return this.vehicleSettings;
    }

    /** Every rig currently loaded. */
    @NotNull Collection<Rig> rigs() {
        return List.copyOf(this.rigs.values());
    }

    /** The rig called {@code name}, matched the same way rigs match bone names. */
    @NotNull Optional<Rig> rig(@NotNull String name) {
        return Optional.ofNullable(this.rigs.get(Rig.normalise(name)));
    }

    /**
     * Resolves the rig a player wardrobe should use for a renderer model.
     *
     * <p>Authored rigs win over the pass-through rig derived from the renderer. Diagnostics are logged
     * here, where the runtime has the policy for operator-facing output, rather than from a player click.
     */
    @NotNull Rig rigForModel(@NotNull String modelId) {
        final RigLoadResult result = this.rigFactory.rigFor(modelId);
        final ConfigSnapshot.DiagnosticsOptions options = this.configs.snapshot().diagnostics();
        if (options.logRigDiagnostics()) {
            for (final RigDiagnostic diagnostic : result.diagnostics()) {
                if (options.shouldLog(diagnostic.severity())) {
                    this.logger.warn(Component.text(diagnostic.format(),
                            diagnostic.isError() ? NamedTextColor.RED : NamedTextColor.YELLOW));
                }
            }
        }
        return result.rigOrThrow();
    }

    /**
     * Re-reads every configuration file and every rig, and tells every layer that owns a repeating task
     * about it.
     *
     * <h2>A refused reload changes nothing</h2>
     * Not the configuration, not the rigs, and not one dispatched period. Re-reading rigs against a
     * snapshot the validator rejected, or re-dispatching a sampler at a rate no file describes, would
     * produce exactly the half-applied state the configuration layer exists to prevent — and the
     * previous snapshot is still live by identity, so there is nothing to apply.
     *
     * <h2>Why every layer has to be driven, and why the order between them does not matter</h2>
     * Four things in this plugin own a repeating task whose rate belongs to the platform: the
     * simulation loop, each bound model's anchor sampler, each vehicle's control loop, and the one
     * decoration driver loop. A repeating task cannot change its own period from inside its own body —
     * the only way is to cancel and dispatch again — so each of them has an explicit reload path, and
     * each compares its own configured period against its own dispatched one.
     *
     * <p>They touch disjoint tasks, so the order among the four is free. <strong>Completeness is
     * not.</strong> A layer left out of this method keeps dispatching at the old period forever, with
     * nothing to report and nothing to notice: an operator who halved the loop rate would see the
     * simulation follow and the samplers not, which reads as the new rate being broken.
     *
     * @return what the reload did
     */
    @NotNull ConfigReloadResult reload() {
        final ConfigReloadResult result = this.configs.reloadNow();
        report(this.logger, this.configs, result, "Reloaded");
        if (!result.applied()) {
            return result;
        }

        // Calibration may have moved, and a PhysicsState is sized and stability-checked against a step
        // size at construction. The loop rebuilds its registrations and re-dispatches itself if the
        // period changed.
        final int rebuilt = this.loop.reload();
        final int samplers = this.models.reload();
        final int controls = this.vehicles.reload();
        final boolean redispatchedDrivers = this.drivers.reload();

        if (rebuilt > 0 || samplers > 0 || controls > 0 || redispatchedDrivers) {
            this.logger.info(Component.text("Re-dispatched for the new configuration: "
                    + rebuilt + " simulation(s), " + samplers + " sampler(s), "
                    + controls + " vehicle control loop(s)"
                    + (redispatchedDrivers ? ", the decoration driver loop" : "")));
        }

        this.rigs.clear();
        this.rigFactory.invalidateAll();
        loadRigs();
        return result;
    }

    // ------------------------------------------------------------- settings seams

    /**
     * One value built from one snapshot, published as a pair and reused while that snapshot is live.
     *
     * <h2>Why every settings seam goes through this</h2>
     * Because one of them — the simulation's — has its identity compared by its consumer, and a seam
     * that allocates per call breaks it silently and only in production. {@link #simulationSettings()}
     * documents that failure in full. The others do not compare identity today, and they go through the
     * same mechanism anyway for two reasons: a value read once per step by every vehicle, or once per
     * pass by the whole decoration layer, should not put an allocation on that path; and a seam that
     * <em>happens</em> not to be identity-sensitive is one refactor away from being one, at which point
     * the bug is reintroduced in a class that does not mention it.
     *
     * <h2>The pair is one reference, deliberately</h2>
     * Two references — a snapshot and a value — would let a reader see a new snapshot beside the old
     * value, which is precisely the mixed-generation read the single-{@code AtomicReference} design of
     * the configuration layer exists to make unrepresentable.
     *
     * <h2>Races cost one rebuild, never a disagreement</h2>
     * Two callers arriving on a fresh snapshot both build. The loser of the {@code compareAndSet} keeps
     * its own equal-valued instance for that one call and reads the winner's on the next, so the worst
     * outcome is one extra allocation rather than two generations in flight.
     *
     * @param <ValueType> the assembled value this cache publishes
     */
    private static final class SnapshotCache<ValueType> {

        private final @NotNull Function<ConfigSnapshot, ValueType> build;
        private final @NotNull AtomicReference<Entry<ValueType>> cache = new AtomicReference<>();

        SnapshotCache(@NotNull Function<ConfigSnapshot, ValueType> build) {
            this.build = build;
        }

        @NotNull ValueType of(@NotNull ConfigSnapshot snapshot) {
            final Entry<ValueType> cached = this.cache.get();
            if (cached != null && cached.snapshot() == snapshot) {
                return cached.value();
            }

            final ValueType built = this.build.apply(snapshot);
            this.cache.compareAndSet(cached, new Entry<>(snapshot, built));

            final Entry<ValueType> published = this.cache.get();
            return published != null && published.snapshot() == snapshot
                    ? published.value()
                    : built;
        }

        /** One snapshot and the value built from it, so the pair can be published as a unit. */
        private record Entry<ValueType>(
                @NotNull ConfigSnapshot snapshot,
                @NotNull ValueType value
        ) {
        }
    }

    /**
     * Where the render layer reads its dirty epsilon and its diagnostic limit.
     *
     * <p>Package-private rather than private because it is the one source {@link #create(Plugin)} has
     * to build <em>before</em> the runtime exists — the anchor factories and the rig validator are all
     * built from it — so it cannot be an instance method, and anything assembling a runtime has to use
     * this factory rather than its own lambda. Two sources describing the same configuration would hand
     * the renderer and the validator two different objects, which is the allocation-per-call hazard
     * {@link SnapshotCache} exists to remove, one level up.
     *
     * @param configs the live configuration
     * @return the seam, which answers one object per published snapshot
     */
    static @NotNull RenderSettingsSource renderSource(@NotNull ConfigService configs) {
        final SnapshotCache<RenderSettings> cache = new SnapshotCache<>(snapshot -> new RenderSettings(
                snapshot.render().boneDirtyEpsilonRadians(),
                snapshot.render().boneNamesInMessage()));
        return () -> cache.of(configs.snapshot());
    }

    private static @NotNull SimulationSettingsSource simulationSource(@NotNull ConfigService configs) {
        final SnapshotCache<SimulationSettings> cache =
                new SnapshotCache<>(ModelRuntime::buildSimulationSettings);
        return () -> cache.of(configs.snapshot());
    }

    private static @NotNull DecorationSettingsSource decorationSource(@NotNull ConfigService configs) {
        final SnapshotCache<DecorationSettings> cache = new SnapshotCache<>(snapshot -> {
            final ConfigSnapshot.DecorationOptions field = snapshot.decoration();
            return new DecorationSettings(
                    field.driverUpdateHz(),
                    field.windBaseSpeedBlocksPerTick(),
                    field.windGustSpeedBlocksPerTick(),
                    field.windGustRateHz(),
                    field.windGustHarmonicRatio(),
                    field.windGustHarmonicWeight(),
                    field.windGustWavelengthBlocks(),
                    field.windDirectionDegrees(),
                    field.windDirectionSwayDegrees(),
                    field.windDirectionSwayRateHz(),
                    field.windVerticalFraction());
        });
        return () -> cache.of(configs.snapshot());
    }

    private static @NotNull VehicleSettingsSource vehicleSource(@NotNull ConfigService configs) {
        final SnapshotCache<VehicleSettings> cache = new SnapshotCache<>(snapshot -> {
            final ConfigSnapshot.VehicleOptions options = snapshot.vehicle();
            return new VehicleSettings(
                    snapshot.vehicleBounds(),
                    options.controlPeriodTicks(),
                    options.maxStepTicks(),
                    options.maxLocalStepBlocks(),
                    options.restSpeedBlocksPerTick());
        });
        return () -> cache.of(configs.snapshot());
    }

    /**
     * Where the AI layer reads its bounds and its fault policy.
     *
     * <p>Static, and built in {@link #create(Plugin)} rather than held as a field, because the
     * {@link AiRuntime} is the only consumer and it is assembled before this runtime exists. Nothing
     * else in the plugin reads it, so a field here would be an accessor nobody calls.
     */
    private static @NotNull AiSettingsSource aiSource(@NotNull ConfigService configs) {
        final SnapshotCache<AiSettings> cache = new SnapshotCache<>(snapshot -> new AiSettings(
                snapshot.aiBounds(),
                snapshot.ai().faultLogIntervalNanos(),
                snapshot.ai().faultsBeforeDisable()));
        return () -> cache.of(configs.snapshot());
    }

    /** The settings {@code snapshot} describes. Pure; the caching is {@link SnapshotCache}'s. */
    private static @NotNull SimulationSettings buildSimulationSettings(
            @NotNull ConfigSnapshot snapshot
    ) {
        final int loopRateHz = snapshot.simulation().loopRateHz();
        final EmissionCadence cadence = EmissionCadence.of(
                loopRateHz, snapshot.render().emissionHz(), snapshot.cadenceBounds());
        return new SimulationSettings(
                new AngularPhysicsSolver(snapshot.physics()),
                loopRateHz,
                snapshot.render().jitterTolerance() ? cadence.withJitterTolerance() : cadence,
                snapshot.driverBounds(),
                snapshot.simulation().errorLogIntervalNanos());
    }

    // -------------------------------------------------------------------- rigs

    /** The settings a rig file is read against, built from the live snapshot. */
    private @NotNull RigLoadSettings rigLoadSettings() {
        final ConfigSnapshot snapshot = this.configs.snapshot();
        return new RigLoadSettings(
                new AngularPhysicsSolver(snapshot.physics()),
                snapshot.rigBounds(),
                snapshot.profileBounds(),
                snapshot.profileDefaults(),
                snapshot.rig().stabilityMargin(),
                snapshot.rig().minReachLengthBlocks());
    }

    /**
     * Reads every {@code .yml} under {@code rigs/}.
     *
     * <p>One bad rig does not stop the others: a server with twenty rigs and one typo should load
     * nineteen and name the one that failed, rather than start with none and a stack trace.
     */
    private void loadRigs() {
        final Path directory = this.dataFolder.resolve(RIG_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            writeExampleRig(directory);
            return;
        }

        final RigLoadSettings settings = rigLoadSettings();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(RIG_EXTENSION))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(file -> loadRig(file, settings));
        } catch (final IOException unreadable) {
            this.logger.error(Component.text(
                    "Could not list the rig directory " + directory, NamedTextColor.RED), unreadable);
            return;
        }

        this.logger.info(Component.text("Loaded " + this.rigs.size() + " rig(s) from "
                + RIG_DIRECTORY + "/"));
    }

    private void loadRig(@NotNull Path file, @NotNull RigLoadSettings settings) {
        final RigLoadResult result = RigLoader.loadFile(file, settings);
        final ConfigSnapshot.DiagnosticsOptions options = this.configs.snapshot().diagnostics();
        if (options.logRigDiagnostics()) {
            for (final RigDiagnostic diagnostic : result.diagnostics()) {
                if (!options.shouldLog(diagnostic.severity())) {
                    continue;
                }
                final Component line = Component.text(diagnostic.format(),
                        diagnostic.isError() ? NamedTextColor.RED : NamedTextColor.YELLOW);
                this.logger.warn(line);
            }
        }
        result.rig().ifPresent(rig -> register(rig, file));
    }

    /**
     * Files a loaded rig under its name, refusing to overwrite one that is already there.
     *
     * <p>Two files declaring the same {@code name} used to be a silent overwrite: rigs are keyed by
     * normalised name and read in alphabetical order, so the later file simply won and the earlier one
     * vanished with nothing said. The operator's symptom was a rig that did not exist, in a file that
     * loaded without a single diagnostic — and the duplicate is most likely a copy-paste of a working
     * file whose {@code name} was never changed, which is exactly the case where silence is worst.
     *
     * <p><strong>First one wins</strong>, rather than last. Alphabetical order is an accident of file
     * naming, so neither choice is more correct — but keeping the first makes the outcome independent of
     * whether somebody renames a file later, and the warning names both files so it does not matter
     * which was kept.
     */
    private void register(@NotNull Rig rig, @NotNull Path file) {
        final String key = Rig.normalise(rig.getName());
        final Rig existing = this.rigs.putIfAbsent(key, rig);
        if (existing != null) {
            this.logger.warn(Component.text(
                    "Rig name '" + rig.getName() + "' is declared twice: " + file.getFileName()
                            + " repeats a name already loaded from another file. Keeping the first and"
                            + " ignoring this one — give one of them a different 'name'.",
                    NamedTextColor.YELLOW));
        }
    }

    /**
     * Ships the commented example on a fresh installation.
     *
     * <p>A directory with one working file in it is a better starting point than an empty one, and the
     * example is exercised by a test, so what lands on disk cannot drift from what the parser accepts.
     */
    private void writeExampleRig(@NotNull Path directory) {
        try {
            Files.createDirectories(directory);
            final Path example = directory.resolve("example" + RIG_EXTENSION);
            if (!Files.exists(example)) {
                Files.writeString(example, RigLoader.EXAMPLE, StandardCharsets.UTF_8);
                this.logger.info(Component.text(
                        "Created " + RIG_DIRECTORY + "/ with a commented example rig"));
            }
        } catch (final IOException failed) {
            throw new UncheckedIOException("Could not create the rig directory " + directory, failed);
        }
    }

    /**
     * Says what a load or a reload did, subject to the diagnostics toggles.
     *
     * <p>Static because {@link #create(Plugin)} reports the first load before this runtime exists, and
     * two copies of the wording would be two things to keep in step.
     */
    private static void report(
            @NotNull ComponentLogger logger,
            @NotNull ConfigService configs,
            @NotNull ConfigReloadResult result,
            @NotNull String verb
    ) {
        if (configs.snapshot().diagnostics().logConfigReloadSummary()) {
            if (result.applied()) {
                logger.info(Component.text(verb + " configuration: " + result.summary()));
            } else {
                logger.error(Component.text(
                        "Configuration refused, keeping the previous values: " + result.summary(),
                        NamedTextColor.RED));
            }
        }
        result.diagnostics().forEach(diagnostic -> logger.warn(
                Component.text(diagnostic.format(), NamedTextColor.YELLOW)));
    }
}
