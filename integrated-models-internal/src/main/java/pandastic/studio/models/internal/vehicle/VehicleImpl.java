package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleMove;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleState;
import pandastic.studio.models.api.vehicle.VehicleSteering;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One server-authoritative vehicle: a root entity, a control loop on that entity's own thread, and the
 * rig riding on top of it.
 *
 * <h2>The authority model, stated once</h2>
 * The server decides where this vehicle is. Each step reads the rider's held keys, resolves them through
 * the profile's control scheme, integrates the measured elapsed time, and writes the result to the root
 * entity. No position the client sends is read, and the client is never permitted to simulate the
 * vehicle — see {@link #mount(Player)} for the check that enforces the second half, which is the one
 * that is easy to lose by accident.
 *
 * <h2>Which thread does what</h2>
 * Two threads touch a vehicle and they touch disjoint things.
 *
 * <p>The <strong>owning region thread</strong> runs the control step. It is the only thread that reads
 * the root entity, reads the rider's input, writes a position or a heading, mounts or dismounts. What
 * leaves a step is two immutable values stored into two volatile fields — the state and the resolved
 * steering — plus the rider reference. Those single stores are what carry the happens-before edge: a
 * reader that sees a new state sees all three of its components, from the same step.
 *
 * <p><strong>Any other thread</strong> may read those three values and may ask this vehicle to stop.
 * Nothing else. In particular a caller that holds the {@link Player} this vehicle reports as its rider
 * must not read that player's position from another region's thread; the reference is published so that
 * a plugin can identify the rider, not so that it can inspect them.
 *
 * <h2>Why the control step does not count ticks</h2>
 * Because the scheduler drops missed iterations rather than replaying them. A step that assumed its own
 * nominal period would make every vehicle on a loaded server cover a distance that depends on the load,
 * and would make the same vehicle behave differently on two servers with the same configuration. So each
 * step differences a monotonic clock and hands the gap to {@link VehicleStepper}, which clamps it.
 *
 * <h2>Start order</h2>
 * <ol>
 *   <li><strong>Spawn the visual.</strong> Every rig bone is resolved against the live model and the
 *       whole list of failures is reported at once. First because it is the step most likely to fail and
 *       the cheapest to unwind — nothing is scheduled yet.</li>
 *   <li><strong>Seed the heading from the entity.</strong> A vehicle starts facing wherever its root
 *       entity faces. Starting at yaw zero would spin every vehicle to due south the moment it was
 *       created, which looks like a bug in the model.</li>
 *   <li><strong>Schedule the control loop.</strong> Last, because it is the only thing that can tear the
 *       vehicle down from another direction, and it must not be able to do that to state that does not
 *       exist yet.</li>
 * </ol>
 * A failure at any point unwinds what came before it. A half-started vehicle is worse than a failed one:
 * it leaves a tracker in the world that nothing will ever close.
 *
 * <h2>Teardown order, and stopping exactly once</h2>
 * The rule is <em>stop deciding, then release what was being decided about</em>: cancel the loop, forget
 * the rider, then put the rider down and release the visual. Three things can decide a vehicle is
 * finished and they can decide it simultaneously — the platform's retired callback, a step finding its
 * root gone, and a caller, usually the plugin shutting down — so all three go through one monitor and one
 * latch.
 *
 * <p>The retired path is the exception, and it is the platform's rule rather than ours: a retired
 * callback runs in critical code that may not remove entities, and it is unschedulable because the
 * scheduler that would run the deferred work is the one that has just retired. So that path cancels and
 * releases the visual and leaves the dismount alone — the root entity is gone, so there is nobody left
 * aboard it.
 */
public final class VehicleImpl implements Vehicle {

    private final @NotNull UUID uniqueIdentifier = UUID.randomUUID();
    private final @NotNull Rig rig;
    private final @NotNull VehicleProfile profile;
    private final @NotNull VehicleHost host;
    private final @NotNull VehicleVisual visual;
    private final @NotNull VehicleSettingsSource settings;
    private final @NotNull SimulationClock clock;
    private final @NotNull SimulationLogger logger;

    /** Told once, when this vehicle has finished tearing down. See {@link VehicleBinderImpl}. */
    private final @NotNull Consumer<VehicleImpl> onStopped;

    /**
     * Reused every step, and confined to the owning region thread.
     *
     * <p>Along with {@link #previousNanos}, this is why a step allocates nothing but the immutable
     * values it publishes.
     */
    private final @NotNull VehicleSample sample = new VehicleSample();

    /** Published by the control step, read from anywhere. One store per step, of a complete value. */
    private volatile @NotNull VehicleState state = VehicleState.STOPPED;

    /** Published by the control step, read from anywhere. */
    private volatile @NotNull VehicleSteering steering = VehicleSteering.NEUTRAL;

    /**
     * Published by the control step, read from anywhere.
     *
     * <p>A reference, not a snapshot: identifying the rider is safe from any thread and inspecting them
     * is not. See the threading note.
     */
    private volatile @Nullable Player rider;

    /** The control step's own gate. A body that lands after the teardown finds false and does nothing. */
    private volatile boolean alive;

    /**
     * Whether a relocation is in flight.
     *
     * <p>Set on the region thread and cleared on whichever thread finishes the relocation, which is why
     * it is volatile. While it is set the control loop decides nothing and writes nothing: a
     * cross-region teleport removes the entity and its whole passenger tree from the world before
     * re-adding it, so for part of that window there is no entity to move and moving it anyway fails
     * confusingly later rather than immediately.
     */
    private volatile boolean relocating;

    /** Region-confined: the clock reading the previous step took. */
    private long previousNanos;

    /** Region-confined: whether {@link #previousNanos} means anything yet. */
    private boolean clockSeeded;

    /** The single-use latch. Guarded by {@code this}. */
    private boolean stopped;

    /** Guarded by {@code this}: only the lifecycle methods touch these. */
    private @Nullable VehicleTask controlTask;

    /**
     * The period the live control loop was dispatched at, which is what {@link #reload()} compares
     * against. A repeating task's rate belongs to the platform, so the configured period and the
     * scheduled one can differ — between a reload and the call that acts on it.
     */
    private long scheduledPeriodTicks;

    /**
     * @param rig       the rig this vehicle's visual drives, held for diagnostics and for the api
     * @param profile   how this vehicle drives, already validated against the operator's bounds
     * @param host      the root entity, and the only thing allowed to touch it
     * @param visual    the model riding on it, or {@link VehicleVisual#NONE}
     * @param settings  where the clamps and thresholds come from
     * @param clock     the monotonic clock each step differences
     * @param logger    where a step that threw is reported. The platform swallows anything that escapes
     *                  a scheduled body and keeps the task alive, so a vehicle that throws every step
     *                  would otherwise be silent forever
     * @param onStopped told once when this vehicle has torn down, whether it ever started or not
     */
    public VehicleImpl(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull VehicleHost host,
            @NotNull VehicleVisual visual,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull Consumer<VehicleImpl> onStopped
    ) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.host = Objects.requireNonNull(host, "host");
        this.visual = Objects.requireNonNull(visual, "visual");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull Rig getRig() {
        return this.rig;
    }

    @Override
    public @NotNull VehicleProfile getProfile() {
        return this.profile;
    }

    @Override
    public @NotNull VehicleState getState() {
        return this.state;
    }

    @Override
    public @NotNull VehicleSteering getSteering() {
        return this.steering;
    }

    @Override
    public @NotNull Optional<Player> getRider() {
        return Optional.ofNullable(this.rider);
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    /**
     * Spawns the visual, seeds the heading and starts the control loop.
     *
     * <p>Called by {@link VehicleBinderImpl} and not part of the published api, because a vehicle a
     * consumer can see is one that is already running — the two-phase bind-then-spawn split that
     * {@link pandastic.studio.models.api.binding.RiggedModel} needs buys a vehicle nothing, since there
     * is no offline question to answer about one.
     *
     * <p>See the class documentation for the order and for why each step is where it is.
     *
     * @return {@code true} if the vehicle is now running; {@code false} if it was already running, or if
     *         the root entity vanished between construction and this call, which is a race the caller
     *         cannot avoid
     * @throws IllegalStateException if called off the root entity's owning thread, if this vehicle has
     *                               already stopped, or if the rig cannot be bound to the live model
     */
    public synchronized boolean start() {
        if (this.stopped) {
            throw new IllegalStateException("Vehicle " + this.uniqueIdentifier + " on "
                    + this.host.describe() + " has already been despawned. A handle is single use —"
                    + " create another rather than reviving one, because its visual's cached bone"
                    + " transformers belong to a tracker that no longer exists.");
        }
        if (this.alive) {
            return false;
        }
        requireOwningThread("started");
        if (!this.host.isAlive()) {
            return false;
        }

        final long periodTicks = this.settings.current().controlPeriodTicks();

        // 1. The visual, because it is the step most likely to fail and nothing is scheduled yet.
        if (!this.visual.spawn()) {
            return false;
        }

        boolean committed = false;
        try {
            // 2. The heading. A vehicle starts facing wherever its root entity faces; see the class
            //    note. The position is read in the same call and deliberately not kept — every step
            //    reads it fresh, so that a vehicle somebody else moved carries on from where it is.
            if (this.host.readInto(this.sample)) {
                this.state = new VehicleState(0F, 0F, this.sample.yawDegrees());
            }
            this.steering = VehicleSteering.NEUTRAL;
            this.clockSeeded = false;
            this.relocating = false;

            // Visible to the step from here on, so it is set before anything is scheduled.
            this.alive = true;

            // 3. The loop.
            final VehicleTask scheduled =
                    this.host.repeat(this::step, this::onRootRetired, periodTicks);
            if (scheduled == null) {
                // The root retired between the check above and this call. Neither callback will ever
                // run, so nothing would ever tear this down; the unwind below is the only exit.
                return false;
            }

            this.controlTask = scheduled;
            this.scheduledPeriodTicks = periodTicks;
            committed = true;
            return true;
        } finally {
            // One unwind for both ways out: a step that threw, and a root that retired mid-start. The
            // flag rather than a catch, so no failure path can be added later without passing here.
            if (!committed) {
                this.alive = false;
                this.visual.despawn();
            }
        }
    }

    @Override
    public boolean mount(@NotNull Player newRider) {
        Objects.requireNonNull(newRider, "rider");
        requireOwningThread("mounted");
        if (!this.alive || this.rider != null) {
            return false;
        }
        if (!this.host.mount(newRider)) {
            // The platform guards a mount across a region boundary and refuses it. A race the caller
            // cannot avoid, so a return value rather than an exception.
            return false;
        }

        // The authority check, and it has to be here rather than at creation: the server's answer
        // depends on who the first passenger is, so before the mount there is nothing to ask about.
        // A root that reaches this is one the type-level refusal in VehicleBinderImpl did not know
        // about, which means the list it consults is out of date — so the vehicle is put back the way
        // it was and the failure is loud, because the alternative is a vehicle that rubber-bands for
        // the rest of its life and never says why.
        if (this.host.isClientSteered(newRider)) {
            this.host.dismount();
            throw new IllegalStateException("Mounting " + newRider.getName() + " on "
                    + this.host.describe() + " would hand the vehicle's movement to their client: the"
                    + " server reports them as its controlling passenger, so every position this plugin"
                    + " writes would be a correction the client has already drawn past. Use a root entity"
                    + " type whose own class does not make its first passenger the driver.");
        }

        this.rider = newRider;
        return true;
    }

    @Override
    public boolean dismount() {
        requireOwningThread("dismounted");
        if (this.rider == null) {
            return false;
        }
        this.rider = null;
        this.steering = VehicleSteering.NEUTRAL;
        return this.host.dismount();
    }

    @Override
    public void despawn() {
        stop(false);
    }

    /**
     * The platform telling us the root entity is gone — the authoritative notification, and the only one
     * for an entity that was removed rather than merely killed.
     */
    private void onRootRetired() {
        stop(true);
    }

    /**
     * The one teardown, reached from every direction and run once.
     *
     * @param rootRetired whether this is the platform's retired callback, which may not mutate the world
     *                    and cannot defer; see the class documentation
     */
    private synchronized void stop(boolean rootRetired) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.alive = false;

        // 1. The decider. Its body only writes this vehicle's own fields, so one already in flight is
        //    harmless — the gate above is what stops it doing anything.
        final VehicleTask scheduled = this.controlTask;
        this.controlTask = null;
        if (scheduled != null) {
            scheduled.cancel();
        }

        // 2. The published values, so a reader that outlives the vehicle sees a stopped one rather than
        //    whatever it was doing when it died.
        this.rider = null;
        this.steering = VehicleSteering.NEUTRAL;
        // The heading is kept and the speeds are not. A reader that outlives the vehicle should see one
        // that has stopped where it was, rather than one still travelling or one spun to due south.
        this.state = new VehicleState(0F, 0F, this.state.yawDegrees());
        this.relocating = false;

        // 3. and 4. Put the rider down, release the visual.
        release(rootRetired);

        this.onStopped.accept(this);
    }

    /**
     * Puts the rider down and releases the visual, on a thread allowed to do each.
     *
     * <p>Three cases, and the awkward one is real. On the owning thread — where a step's own teardown and
     * a well-behaved caller both run — both happen inline. Off it, both are handed to the root's
     * scheduler, because ejecting a passenger is a world mutation. From a retired callback neither can be
     * scheduled at all, since the scheduler that would have run the deferred work is the one that just
     * retired — but there is also nothing to eject, because the root entity is what has gone.
     *
     * <p>The visual releases itself from any thread, so it is the one thing that happens in every case.
     */
    private void release(boolean rootRetired) {
        if (rootRetired) {
            this.visual.despawn();
            return;
        }
        if (this.host.isOwnedByCurrentThread()) {
            this.host.dismount();
            this.visual.despawn();
            return;
        }
        final boolean deferred = this.host.submit(() -> {
            this.host.dismount();
            this.visual.despawn();
        });
        if (!deferred) {
            // No thread left to defer to, which means the root is gone and there is nobody aboard it.
            this.visual.despawn();
        }
    }

    /**
     * One control step: read the root, resolve the rider's keys, decide, apply.
     *
     * <p>Runs on the owning region thread, once per configured period. Everything that can go wrong in
     * here is caught and reported rather than allowed to escape, because the platform swallows anything
     * that escapes a scheduled body and keeps the task alive — so an uncaught failure would be a vehicle
     * that quietly stops responding and never explains itself.
     */
    private void step() {
        try {
            stepOnce();
        } catch (final RuntimeException thrown) {
            this.logger.error("Vehicle " + this.uniqueIdentifier + " on " + this.host.describe()
                    + " failed a control step", thrown);
        }
    }

    private void stepOnce() {
        if (!this.alive) {
            return;
        }

        // The clock advances whether or not this step decides anything, so a step that is skipped
        // discards its own elapsed time rather than handing it to the next one. The alternative lets a
        // vehicle "catch up" across a relocation, which is exactly the jump the clamp exists to prevent.
        final long now = this.clock.nanoTime();
        final long elapsedNanos = this.clockSeeded ? now - this.previousNanos : 0L;
        this.previousNanos = now;
        this.clockSeeded = true;

        if (this.relocating) {
            return;
        }
        if (!this.host.readInto(this.sample)) {
            // The other side of the exactly-once guarantee: a root that is dead but whose scheduler has
            // not retired yet is noticed here rather than never. Safe to tear down from inside the body
            // — this is an ordinary repeating task, not the platform's critical retired callback.
            stop(false);
            return;
        }

        // One read for the whole step. Reading it per value would let a reload land between the clamp on
        // the elapsed time and the threshold that decides whether the result is a teleport.
        final VehicleSettings current = this.settings.current();

        // The seat is re-checked every step rather than tracked through events, so a rider who left by
        // holding sneak, disconnected, died or was removed is noticed on the next step with nothing
        // having had to be delivered. The check is only ever allowed to *clear* the rider: somebody who
        // appeared in the seat without going through mount() never had the authority check run against
        // them, so this layer declines to drive for them.
        final Player known = this.rider;
        final Player seated = this.host.rider();
        if (known != null && !known.equals(seated)) {
            this.rider = null;
        }
        final Player driving = this.rider;
        final RiderIntent intent = driving == null
                ? RiderIntent.NONE
                : this.host.intentOf(driving);

        final VehicleState previous = this.state;
        final VehicleStepper.Plan plan =
                VehicleStepper.plan(previous, intent, this.profile, current, elapsedNanos);

        this.steering = plan.steering();
        this.state = plan.move().next();
        if (plan.isIdle()) {
            return;
        }
        apply(plan, previous);
    }

    /**
     * Writes one plan to the world.
     *
     * <p>Heading first, then the displacement, matching the order the solver integrated them in: a
     * vehicle that turns while accelerating travels along the heading it ends the step with, so applying
     * the translation first would put it a half-step behind its own controls.
     *
     * <p>A {@link MoveKind#LOCAL} displacement becomes a relocation when the host has no in-place move —
     * which is what a server running with no Mojang-mapped adapter looks like. That is expensive rather
     * than wrong: every step becomes a scheduled transaction, and the vehicle still goes where the server
     * said.
     *
     * <p>{@link MoveKind#NONE} with a changed heading is a real case rather than a leftover: a vehicle
     * turning on the spot has no displacement at all and still has to be written, which is why the
     * heading is applied on its own branch instead of alongside the move.
     */
    private void apply(@NotNull VehicleStepper.Plan plan, @NotNull VehicleState previous) {
        final VehicleMove move = plan.move();

        if (plan.kind() == MoveKind.TELEPORT
                || (plan.kind() == MoveKind.LOCAL && !this.host.supportsLocalMove())) {
            // The relocation carries the heading with it, so there is nothing to write separately.
            relocate(move);
            return;
        }

        if (move.yawDegrees() != previous.yawDegrees()) {
            this.host.setYaw(move.yawDegrees());
        }
        if (plan.kind() == MoveKind.LOCAL) {
            // Partly applied is a normal outcome, not a failure: the entity ends up where the blocks let
            // it go, which is what gives a vehicle its wall stop and its slab climb. The state keeps the
            // commanded speed either way, because the next step reads the real position back and the
            // displacement it computes is measured from there.
            this.host.moveLocal(move.deltaX(), move.deltaY(), move.deltaZ());
        }
    }

    /**
     * Sends the vehicle to an absolute destination through the platform's asynchronous teleport.
     *
     * <p>The destination is the position just read plus the decided displacement, never a position this
     * layer remembered: a vehicle that was pushed, blocked or relocated by somebody else has to carry on
     * from where it actually is.
     *
     * <p>Marks the vehicle as relocating before starting, so the loop stops deciding until the platform
     * reports back. The callback does nothing but clear that flag, because it runs on whichever thread
     * finished the relocation — which is the destination region's, not ours.
     */
    private void relocate(@NotNull VehicleMove move) {
        this.relocating = true;
        this.host.teleport(
                this.sample.x() + move.deltaX(),
                this.sample.y() + move.deltaY(),
                this.sample.z() + move.deltaZ(),
                move.yawDegrees(),
                succeeded -> this.relocating = false);
    }

    /**
     * Re-dispatches the control loop if configuration moved its period.
     *
     * <p>Explicit rather than detected per step, for the reason
     * {@link pandastic.studio.models.internal.binding.RiggedModelImpl#reload()} is: a repeating task's
     * rate belongs to the platform, so the only way to change it is to cancel and dispatch again, which
     * a task cannot do to itself from inside its own body.
     *
     * <p>Cancels before dispatching, never the other way round: two live loops would both difference the
     * same clock field and each would see half the elapsed time, so the vehicle would travel at half
     * speed while apparently being decided twice as often.
     *
     * @return whether the loop was re-dispatched
     */
    public synchronized boolean reload() {
        if (!this.alive) {
            return false;
        }
        final long periodTicks = this.settings.current().controlPeriodTicks();
        if (periodTicks == this.scheduledPeriodTicks) {
            return false;
        }

        final VehicleTask previous = this.controlTask;
        this.controlTask = null;
        if (previous != null) {
            previous.cancel();
        }

        final VehicleTask scheduled = this.host.repeat(this::step, this::onRootRetired, periodTicks);
        if (scheduled == null) {
            // The root retired while we were re-dispatching, and we have just cancelled the only task
            // that would have told us. Nothing else will, so stop here.
            stop(false);
            return false;
        }
        this.controlTask = scheduled;
        this.scheduledPeriodTicks = periodTicks;
        return true;
    }

    /** The root entity, and the only thing allowed to touch it. */
    public @NotNull VehicleHost host() {
        return this.host;
    }

    /** The model riding on this vehicle. */
    public @NotNull VehicleVisual visual() {
        return this.visual;
    }

    /** Whether this handle has torn down and can no longer start. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    /** The period the live control loop was dispatched at, or the last one if it is not running. */
    public synchronized long scheduledPeriodTicks() {
        return this.scheduledPeriodTicks;
    }

    /** Whether a relocation is in flight, during which the loop decides nothing. */
    public boolean isRelocating() {
        return this.relocating;
    }

    private void requireOwningThread(@NotNull String verb) {
        if (this.host.isOwnedByCurrentThread()) {
            return;
        }
        // Guarded here because the platform will not guard it: a position write or a mount from a thread
        // that does not own the entity is undefined rather than merely racy, and it fails silently.
        throw new IllegalStateException("Vehicle " + this.uniqueIdentifier + " on "
                + this.host.describe() + " cannot be " + verb + " from this thread: the operation reads"
                + " and writes the root entity, which is only defined on the thread that owns it."
                + " Schedule it on that thread.");
    }

    @Override
    public @NotNull String toString() {
        return "Vehicle[" + this.rig.getName() + " on " + this.host.describe()
                + ", alive=" + this.alive + "]";
    }
}
