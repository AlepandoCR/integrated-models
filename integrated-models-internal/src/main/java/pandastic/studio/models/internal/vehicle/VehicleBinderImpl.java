package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.VehicleBinder;
import pandastic.studio.models.api.vehicle.VehicleProfile;
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
 */
public final class VehicleBinderImpl implements VehicleBinder {

    private final @NotNull VehicleHostFactory hosts;
    private final @NotNull VehicleVisualFactory visuals;
    private final @NotNull VehicleSettingsSource settings;
    private final @NotNull SimulationClock clock;
    private final @NotNull SimulationLogger logger;

    /**
     * Every vehicle handed out and not yet torn down.
     *
     * <p>A concurrent set because vehicles are created from whichever thread owns their root and remove
     * themselves from whichever thread noticed they were finished — frequently a region thread for one
     * and the main thread for the other, at the same moment.
     */
    private final @NotNull Set<VehicleImpl> running = ConcurrentHashMap.newKeySet();

    /**
     * @param hosts    how an entity becomes something this layer may drive
     * @param visuals  where a vehicle's model comes from, or {@link VehicleVisualFactory#NONE}
     * @param settings where the bounds, the clamps and the control period come from
     * @param clock    the monotonic clock every control step differences
     * @param logger   where a control step that threw is reported
     */
    public VehicleBinderImpl(
            @NotNull VehicleHostFactory hosts,
            @NotNull VehicleVisualFactory visuals,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger
    ) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.visuals = Objects.requireNonNull(visuals, "visuals");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public @NotNull VehicleImpl create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(root, "root");

        // 1. The operator's ranges. Pure, so it costs nothing to do first and it names the component.
        profile.requireWithin(this.settings.current().bounds());

        // 2. The authority refusal. An instanceof chain, so it touches no entity state and is legal from
        //    any thread — which is why it can run before the thread check.
        if (ClientSteeredRoots.isKnownClientSteered(root)) {
            throw new IllegalStateException("A " + root.getType() + " cannot be the root of a"
                    + " server-authoritative vehicle: the server makes its first passenger the"
                    + " controlling passenger, so the rider's client would simulate the vehicle and every"
                    + " position this plugin writes would be a correction the client has already drawn"
                    + " past. Spawn a root entity whose own class does not do that.");
        }

        final VehicleHost host = this.hosts.hostFor(root);

        // 3. The thread. Before anything is allocated, because everything after this touches the entity.
        if (!host.isOwnedByCurrentThread()) {
            throw new IllegalStateException("A vehicle on " + host.describe() + " cannot be created from"
                    + " this thread: creating it reads the root entity's heading and spawns its model,"
                    + " both of which are only defined on the thread that owns it. Schedule the creation"
                    + " on that thread.");
        }

        // 4. The rig. Nothing exists in the world yet, so a failure here costs nothing to unwind.
        final VehicleVisual visual = this.visuals.visualFor(rig, root);

        final VehicleImpl vehicle = new VehicleImpl(rig, profile, host, visual, this.settings,
                this.clock, this.logger, this.running::remove);
        // Registered before it is started, so a start that fails halfway tears itself out of the set
        // through the same teardown every other exit uses.
        this.running.add(vehicle);
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
