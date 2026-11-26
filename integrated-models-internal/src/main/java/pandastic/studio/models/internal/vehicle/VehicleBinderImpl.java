package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.ModelManager;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleBinder;
import pandastic.studio.models.api.vehicle.VehiclePilot;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.internal.input.InputFaultSink;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds vehicles, and keeps a list of the ones that are still running.
 *
 * <p>Two responsibilities that sound separable and are not, for the reason
 * {@link pandastic.studio.models.internal.binding.ModelBinderImpl} gives: something has to be able to
 * stop every live vehicle at shutdown, because an entity-scheduled repeating task is
 * <strong>never</strong> cancelled for a plugin on disable — unlike one on the main scheduler, which the
 * platform sweeps. A control loop nobody cancelled keeps writing positions after the plugin's classloader
 * has been closed, and fails on the first lazy class load after that, during shutdown, pointing nowhere
 * near the vehicle that leaked it. The only place that can hold that list without inventing a second
 * registry is the thing that handed the vehicles out.
 *
 * <p>Membership is exactly "created and not yet stopped": a vehicle removes itself as the last step of
 * its teardown, whether that teardown came from here, from a caller, or from the platform noticing the
 * root entity is gone.
 *
 * <h2>What it refuses, and in which order</h2>
 * <ol>
 *   <li><strong>A profile the operator does not permit.</strong> Checked first because it is pure, needs
 *       no entity and no thread, and an out-of-range profile is a bug in the calling plugin rather than a
 *       condition of the world.</li>
 *   <li><strong>A root entity the client would steer.</strong> Second, and this is the refusal the whole
 *       authority model rests on. Checked by type here and again authoritatively at mount time, because
 *       the server's own answer depends on who is aboard and there is nobody aboard yet.</li>
 *   <li><strong>A call from the wrong thread.</strong> Third, before anything is allocated, because
 *       everything after it touches the entity.</li>
 *   <li><strong>A rig whose bone names no longer match its model.</strong> Last of the pure checks, and
 *       it fails with every broken name in one message rather than the first.</li>
 * </ol>
 *
 * <p>Note where the AI path's own refusals are: {@link VehicleBinder#createPiloted} is a default method
 * on the interface and makes its checks — a plan that leaves the vanilla goals in place, a species whose
 * tick does not tick its brain, a plan outside the live bounds — <em>before</em> calling in here, so the
 * authority refusal above still runs before a brain has been rebuilt. That ordering is the reason the
 * creature is given its plan after the vehicle exists rather than before; see
 * {@link pandastic.studio.models.api.vehicle.AiPilot#pending()}.
 */
public final class VehicleBinderImpl implements VehicleBinder {

    private final @NotNull VehicleHostFactory hosts;
    private final @NotNull VehicleVisualFactory visuals;
    private final @NotNull VehicleSettingsSource settings;
    private final @NotNull SimulationClock clock;
    private final @NotNull SimulationLogger logger;

    /** Where every vehicle this binder creates writes its seat chart. */
    private final @NotNull SeatChartSink chartSink;

    /**
     * Where every vehicle this binder creates reads its riders' input.
     *
     * <p>One source for the whole server rather than one per vehicle, because it is the input layer and
     * there is one of those. {@code null} means "derive it from each vehicle's own host", which is the
     * movement-only fallback a server with no input capture gets — see
     * {@link RiderInputSource#fromHost(VehicleHost)}. Held nullable rather than resolved here because
     * {@code fromHost} needs a host and a host is per vehicle.
     */
    private final @Nullable RiderInputSource inputs;

    /**
     * Where a consumer's {@link pandastic.studio.models.api.vehicle.VehicleController} goes when it
     * throws, for every vehicle this binder creates.
     *
     * <p>One sink for the whole server, because the counters are about a controller <em>class</em> and
     * not about one vehicle: forty tanks sharing a broken controller should produce one report per
     * configured interval rather than forty. {@code null} means the caller has no input layer to borrow
     * the policy from, and each vehicle falls back to reporting every occurrence through the simulation
     * logger and never detaching — see {@link VehicleImpl}'s eleven-argument constructor.
     */
    private final @Nullable InputFaultSink controllerFaults;

    /**
     * Every vehicle handed out and not yet torn down.
     *
     * <p>A concurrent set because vehicles are created from whichever thread owns their root and remove
     * themselves from whichever thread noticed they were finished — frequently a region thread for one
     * and the main thread for the other, at the same moment.
     */
    private final @NotNull Set<VehicleImpl> running = ConcurrentHashMap.newKeySet();

    /** The published registry; see {@code ModelBinderImpl} for why this is not a second copy of the set. */
    private final @NotNull ModelManager registry;

    /**
     * @param hosts    how an entity becomes something this layer may drive
     * @param visuals  where a vehicle's model comes from, or {@link VehicleVisualFactory#NONE}
     * @param settings where the bounds, the clamps and the control period come from
     * @param clock    the monotonic clock every control step differences
     * @param logger    where a control step that threw is reported
     * @param chartSink where each vehicle's seat chart is written. The whole chart is an operator-facing
     *                  report rather than a diagnostic, which is why it goes to a component logger and
     *                  not through {@code logger}
     * @param registry  where every vehicle this binder creates is published
     */
    public VehicleBinderImpl(
            @NotNull VehicleHostFactory hosts,
            @NotNull VehicleVisualFactory visuals,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull ModelManager registry
    ) {
        this(hosts, visuals, settings, clock, logger, chartSink, registry, null);
    }

    /**
     * @param hosts    how an entity becomes something this layer may drive
     * @param visuals  where a vehicle's model comes from, or {@link VehicleVisualFactory#NONE}
     * @param settings where the bounds, the clamps and the control period come from
     * @param clock    the monotonic clock every control step differences
     * @param logger   where a control step that threw is reported
     * @param chartSink where each vehicle's seat chart is written
     * @param registry where every vehicle this binder creates is published
     * @param inputs   where every vehicle reads its riders' input, or {@code null} to derive the
     *                 movement-only fallback from each vehicle's own host. The production value is
     *                 {@link RiderInputSource#of} over the plugin's {@code PlayerInputController}: with
     *                 it a consumer's per-seat listeners see all twelve mapped inputs and their timing,
     *                 without it only the seven movement keys and no edges
     */
    public VehicleBinderImpl(
            @NotNull VehicleHostFactory hosts,
            @NotNull VehicleVisualFactory visuals,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull ModelManager registry,
            @Nullable RiderInputSource inputs
    ) {
        this(hosts, visuals, settings, clock, logger, chartSink, registry, inputs, null);
    }

    /**
     * @param hosts    how an entity becomes something this layer may drive
     * @param visuals  where a vehicle's model comes from, or {@link VehicleVisualFactory#NONE}
     * @param settings where the bounds, the clamps and the control period come from
     * @param clock    the monotonic clock every control step differences
     * @param logger   where a control step that threw is reported
     * @param chartSink where each vehicle's seat chart is written
     * @param registry where every vehicle this binder creates is published
     * @param inputs   where every vehicle reads its riders' input, or {@code null} for the
     *                 movement-only fallback
     * @param controllerFaults where a consumer's {@code VehicleController} goes when it throws, or
     *                         {@code null} for the unconfigured fallback. The production value is the
     *                         input layer's own throttling sink: a controller is consumer code reacting
     *                         to rider input, so it is judged by the same policy out of the same two
     *                         keys as a bound input handler rather than by a second one
     */
    public VehicleBinderImpl(
            @NotNull VehicleHostFactory hosts,
            @NotNull VehicleVisualFactory visuals,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull ModelManager registry,
            @Nullable RiderInputSource inputs,
            @Nullable InputFaultSink controllerFaults
    ) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.visuals = Objects.requireNonNull(visuals, "visuals");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.chartSink = Objects.requireNonNull(chartSink, "chartSink");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.inputs = inputs;
        this.controllerFaults = controllerFaults;
    }

    @Override
    public @NotNull VehicleImpl create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root
    ) {
        return create(rig, profile, root, VehicleSetup.DEFAULT);
    }

    @Override
    public @NotNull VehicleImpl create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root,
            @NotNull VehicleSetup setup
    ) {
        return create(rig, profile, root, setup, VehiclePilot.NONE);
    }

    @Override
    public @NotNull VehicleImpl create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root,
            @NotNull VehicleSetup setup,
            @NotNull VehiclePilot pilot
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(pilot, "pilot");

        // One snapshot read for the whole creation, so the terrain response a vehicle inherits and the
        // ranges it is checked against come from the same generation of the file. Read separately they
        // could straddle a reload, and the combination would be one no file describes.
        final VehicleSettings current = this.settings.current();

        // 1. The terrain response, if the profile declared none. Before the validation, because what has
        //    to be inside the operator's ranges is the response the vehicle will actually use.
        final VehicleProfile effective = withInheritedTerrain(profile, current);

        // 2. The operator's ranges. Pure, so it costs nothing to do first and it names the component.
        effective.requireWithin(current.bounds());

        // 3. The authority refusal. An instanceof chain, so it touches no entity state and is legal from
        //    any thread — which is why it can run before the thread check.
        if (ClientSteeredRoots.isKnownClientSteered(root)) {
            throw new IllegalStateException("A " + root.getType() + " cannot be the root of a"
                    + " server-authoritative vehicle: the server makes its first passenger the"
                    + " controlling passenger, so the rider's client would simulate the vehicle and every"
                    + " position this plugin writes would be a correction the client has already drawn"
                    + " past. Spawn a root entity whose own class does not do that.");
        }

        final VehicleHost host = this.hosts.hostFor(root);

        // 4. The thread. Before anything is allocated, because everything after this touches the entity.
        if (!host.isOwnedByCurrentThread()) {
            throw new IllegalStateException("A vehicle on " + host.describe() + " cannot be created from"
                    + " this thread: creating it reads the root entity's heading and spawns its model,"
                    + " both of which are only defined on the thread that owns it. Schedule the creation"
                    + " on that thread.");
        }

        // 5. The rig. Nothing exists in the world yet, so a failure here costs nothing to unwind.
        final VehicleVisual visual = this.visuals.visualFor(rig, root);

        final RiderInputSource source =
                this.inputs == null ? RiderInputSource.fromHost(host) : this.inputs;
        // One construction path. A binder with no fault sink to borrow gets the same unconfigured
        // fallback a vehicle built through the shorter constructors has always got — report every
        // occurrence, never detach — rather than a second constructor to choose between.
        final InputFaultSink faults = this.controllerFaults == null
                ? VehicleImpl.reportingThrough(this.logger)
                : this.controllerFaults;
        final VehicleImpl vehicle = new VehicleImpl(rig, effective, host, visual, this.settings,
                this.clock, this.logger, this.chartSink, setup, source, faults, pilot,
                this::unregister);
        // Registered before it is started, so a start that fails halfway tears itself out of both
        // collections through the same teardown every other exit uses.
        this.running.add(vehicle);
        this.registry.addModel(vehicle);
        try {
            if (!vehicle.start()) {
                // The root vanished between the checks above and the dispatch — a race a caller cannot
                // avoid. The handle comes back already stopped rather than as an exception, because
                // there is nothing the caller did wrong and nothing it could have done; isAlive() is
                // how it finds out.
                vehicle.despawn();
            }
        } catch (final RuntimeException | Error failed) {
            // A start that threw — a rig whose spawn failed against the live model, say — must not leave
            // a registered vehicle behind. Torn down and rethrown, so the caller still hears about it.
            vehicle.despawn();
            throw failed;
        }
        return vehicle;
    }

    /**
     * {@code profile} with the operator's terrain response for its kind, if it declared none of its own.
     *
     * <h2>What counts as "declared none", and why it is an identity comparison</h2>
     * {@link TerrainResponse#NONE} is the shared constant the builder defaults to and the one
     * {@code VehicleProfile.withoutTerrain} supplies, so it is exactly the value a profile that says
     * nothing about terrain carries — and it is a constant, so comparing by identity is sound. A profile
     * that <em>spelled out</em> an all-zero response keeps it: a plugin that went to the trouble of
     * saying "this vehicle does not react to terrain" has said something, and inheriting over it would
     * make the operator's file override a statement about one vehicle rather than fill a gap in it.
     *
     * <h2>Why this is the binder's job and not the profile's</h2>
     * Because a profile is a statement about a thing and the defaults are a statement about a server, and
     * the only place that holds both is here. {@link VehicleProfile} cannot reach configuration without
     * the api gaining a dependency on it, and a vehicle cannot inherit after construction without its
     * profile being mutable.
     *
     * @param profile what the calling plugin described
     * @param current the configuration generation this whole creation is being decided against
     * @return the profile the vehicle will actually drive under
     * @throws IllegalStateException if the inherited response cannot be combined with this profile — a
     *                               bank angle on a vehicle with nothing to lean from, which
     *                               {@link VehicleProfile} refuses rather than render as a NaN roll
     */
    private static @NotNull VehicleProfile withInheritedTerrain(
            @NotNull VehicleProfile profile,
            @NotNull VehicleSettings current
    ) {
        if (profile.terrain() != TerrainResponse.NONE) {
            return profile;
        }
        final TerrainResponse inherited = current.terrainDefaults().forKind(profile.kind());
        if (inherited == TerrainResponse.NONE) {
            return profile;
        }
        try {
            return new VehicleProfile(
                    profile.maxForwardSpeedBlocksPerTick(),
                    profile.maxReverseSpeedBlocksPerTick(),
                    profile.maxStrafeSpeedBlocksPerTick(),
                    profile.accelerationBlocksPerTickSquared(),
                    profile.brakingBlocksPerTickSquared(),
                    profile.frictionPerTick(),
                    profile.turnRateDegreesPerTick(),
                    profile.boostMultiplier(),
                    profile.controlScheme(),
                    profile.kind(),
                    inherited);
        } catch (final IllegalArgumentException refused) {
            // Reported against the pair rather than against the file, because neither half is wrong on
            // its own: the operator's default is legal for the kind and the plugin's profile is legal
            // without it, and only the combination is undefined.
            throw new IllegalStateException("The terrain response this server gives a "
                    + profile.kind().key() + " vehicle cannot be applied to this profile: "
                    + refused.getMessage()
                    + ". Either give the vehicle a terrain response of its own, or change the defaults"
                    + " for that kind in vehicle.yml.", refused);
        }
    }

    /**
     * Forgets {@code vehicle}, from both this layer's shutdown list and the published registry.
     *
     * <p>Handed to every vehicle as its {@code onStopped} callback, so it runs exactly once per vehicle,
     * on whichever thread noticed it was finished.
     */
    private void unregister(@NotNull VehicleImpl vehicle) {
        this.running.remove(vehicle);
        this.registry.removeModel(vehicle);
    }

    /** Every vehicle currently running, as a snapshot. */
    public @NotNull Collection<VehicleImpl> vehicles() {
        return List.copyOf(this.running);
    }

    /** How many vehicles are running. */
    public int size() {
        return this.running.size();
    }

    /**
     * Despawns every vehicle, and must be called on plugin disable.
     *
     * <p>Iterates a snapshot rather than the live set: each vehicle removes itself as the last step of
     * its own teardown, and mutating the set under its own iterator is the kind of thing that works until
     * the day it does not.
     *
     * <p>One vehicle failing to tear down must not cost the rest theirs, so each is isolated. There is
     * nowhere useful to report it either: this runs during disable, where the plugin's own logger may
     * already be shutting down, and what matters is that every remaining control loop gets cancelled.
     *
     * @return how many vehicles were torn down
     */
    public int despawnAll() {
        int despawned = 0;
        for (final VehicleImpl vehicle : List.copyOf(this.running)) {
            try {
                vehicle.despawn();
            } catch (final RuntimeException | Error ignored) {
                // Deliberately swallowed; see above.
            }
            // Belt and braces, and only on this path: a teardown that threw before reaching its own
            // onStopped callback would otherwise leave the registry naming a vehicle that is gone.
            this.registry.removeModel(vehicle);
            despawned++;
        }
        this.running.clear();
        return despawned;
    }

    /**
     * Re-dispatches every control loop whose configured period has moved.
     *
     * <p>The vehicle layer's half of a configuration reload, and explicit for the same reason
     * {@link pandastic.studio.models.internal.sim.SimulationLoop#reload()} is: a repeating task's rate
     * belongs to the platform, so changing it means cancelling and dispatching again, which a task
     * cannot do to itself.
     *
     * <p>Everything else in {@code vehicle.yml} is read per step and therefore needs no reload path at
     * all. A profile already validated against the previous bounds is deliberately <em>not</em>
     * re-checked: a vehicle a plugin created legally should not stop working because an operator
     * narrowed a ceiling, and the solver clamps to the profile rather than to the bounds.
     *
     * @return how many control loops were re-dispatched
     */
    public int reload() {
        int redispatched = 0;
        for (final VehicleImpl vehicle : List.copyOf(this.running)) {
            if (vehicle.reload()) {
                redispatched++;
            }
        }
        return redispatched;
    }
}
