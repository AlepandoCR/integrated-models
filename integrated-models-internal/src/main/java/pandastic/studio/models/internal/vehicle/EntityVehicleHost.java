package pandastic.studio.models.internal.vehicle;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.RiderInputBridge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A vehicle hosted on a Bukkit entity: the only class in this package that names a platform type.
 *
 * <h2>The entity scheduler, not the region scheduler</h2>
 * A vehicle changes region for a living. The entity scheduler is the only dispatcher that tracks that:
 * it guarantees the body runs on whichever region owns the entity <em>at that moment</em>, with the
 * entity actually present, and it reports the entity's removal through a retired callback. A region
 * scheduler is pinned to the region the task was posted from, so the first time the vehicle drives across
 * a boundary it is being written from the wrong thread — silently, because the platform has no strict
 * check to fire.
 *
 * <p>Two of its documented properties shape the code below. Scheduling can fail, returning null, if the
 * entity is already gone; and the retired callback runs in critical code that must not remove entities,
 * load chunks or load worlds. The first is why {@link #repeat} is nullable and the second is why
 * {@link VehicleImpl} declines to dismount from that path.
 *
 * <h2>The three ways this class can move an entity, and why there are three</h2>
 * <ul>
 *   <li>{@link #setYaw(float)} goes through plain Bukkit. A rotation is not subject to the owned-chunk
 *       rule, so no adapter is needed and none is used.</li>
 *   <li>{@link #moveLocal} goes through the Mojang-mapped seam, because the operation that applies a
 *       displacement <em>with collision</em> is not exposed by Bukkit at all. Without it a vehicle would
 *       either pass through walls or have to reimplement collision, and the second is worse than the
 *       first.</li>
 *   <li>{@link #teleport} goes through the platform's own asynchronous teleport, deliberately and not
 *       through the seam. It is the supported path for leaving a region, it carries the whole passenger
 *       tree, and it has to keep working when there is no adapter at all.</li>
 * </ul>
 *
 * <h2>Why ownership is checked rather than assumed</h2>
 * {@code Bukkit.isOwnedByCurrentRegion(entity)} is documented as the only appropriate way to check for
 * ownership of an entity, because retrieving its location is undefined unless the current region owns it.
 * Undefined, not racy: there is no exception and no log line, just a coordinate assembled from two
 * different ticks — or a position write that is discarded. The check costs a thread-local read, and the
 * alternative is a class of corruption that cannot be reproduced on demand.
 */
public final class EntityVehicleHost implements VehicleHost {

    /**
     * Initial delay of the control loop, in ticks.
     *
     * <p><strong>A platform constraint, not a tunable.</strong> The entity scheduler treats any delay
     * below one tick as one tick, so this is the earliest a dispatched task can run — and the earliest is
     * what is wanted, because until the first step lands the vehicle is not being driven.
     */
    private static final long INITIAL_DELAY_TICKS = 1L;

    /**
     * Delay of a deferred teardown step, in ticks.
     *
     * <p><strong>The same platform constraint.</strong> {@link #submit} exists to get world mutation onto
     * the owning thread, and one tick is the soonest the scheduler will do that.
     */
    private static final long DEFERRED_DELAY_TICKS = 1L;

    /**
     * Pitch written alongside every heading.
     *
     * <p><strong>Structural.</strong> This layer is authoritative about the heading and has no opinion
     * about the pitch, and the platform's rotation call takes both — so the pitch has to be something.
     * Level is the only value that is not a decision: a vehicle's model is posed by the rig, so a pitched
     * root entity would tilt the hitbox away from the thing a player sees without changing the thing
     * they see.
     */
    private static final float LEVEL_PITCH_DEGREES = 0F;

    private final @NotNull Plugin plugin;
    private final @NotNull Entity entity;
    private final @NotNull UUID rootId;
    private final @NotNull MovementBridge movement;
    private final @NotNull RiderInputBridge input;
    private final boolean localMove;

    /**
     * @param plugin   whose scheduler owns the control loop
     * @param entity   the root entity
     * @param movement the Mojang-mapped movement seam. Never absent as an object: a server with no
     *                 adapter gets a bridge whose methods all decline, which this class turns into
     *                 "every move is a teleport" rather than into a vehicle that does not work
     * @param input    where the rider's held keys are read from
     */
    public EntityVehicleHost(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull MovementBridge movement,
            @NotNull RiderInputBridge input
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.movement = Objects.requireNonNull(movement, "movement");
        this.input = Objects.requireNonNull(input, "input");
        // Captured once, because it is the one thing the teardown path needs from a thread that may not
        // own the entity, and because reading it after removal is not guaranteed to work.
        this.rootId = entity.getUniqueId();
        // Asked once. The answer is a property of the adapter rather than of the entity, so a per-step
        // call would be a virtual dispatch per vehicle per tick for a constant.
        this.localMove = movement.supportsLocalMove();
    }

    @Override
    public @NotNull UUID rootId() {
        return this.rootId;
    }

    @Override
    public boolean isAlive() {
        return this.entity.isValid();
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return Bukkit.isOwnedByCurrentRegion(this.entity);
    }

    @Override
    public boolean readInto(@NotNull VehicleSample sample) {
        Objects.requireNonNull(sample, "sample");
        if (!this.entity.isValid()) {
            return false;
        }
        // One read of each coordinate, straight into the caller's buffer. Legal only because this runs
        // on the owning region thread, and cheap enough per tick per vehicle: the component getters
        // avoid allocating the Location that getLocation() would.
        sample.set(
                this.entity.getX(),
                this.entity.getY(),
                this.entity.getZ(),
                this.entity.getYaw());
        return true;
    }

    @Override
    public @Nullable VehicleTask repeat(
            @NotNull Runnable body,
            @NotNull Runnable retired,
            long periodTicks
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(retired, "retired");
        // The platform hands the body its own handle; the vehicle has no use for it, since it keeps the
        // one returned here and that is the handle it must cancel by hand on disable.
        final ScheduledTask task = this.entity.getScheduler().runAtFixedRate(
                this.plugin, ignoredTask -> body.run(), retired, INITIAL_DELAY_TICKS, periodTicks);
        return task == null ? null : new PlatformVehicleTask(task);
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        Objects.requireNonNull(work, "work");
        // No retired callback: the whole reason to defer is that the work must not run in critical code,
        // and a retired callback is critical code. A false return says it will never run, which is what
        // the caller needs to know.
        return this.entity.getScheduler().execute(this.plugin, work, null, DEFERRED_DELAY_TICKS);
    }

    @Override
    public boolean supportsLocalMove() {
        return this.localMove;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ) {
        return this.movement.moveLocal(this.entity, deltaX, deltaY, deltaZ);
    }

    @Override
    public void setYaw(float yawDegrees) {
        this.entity.setRotation(yawDegrees, LEVEL_PITCH_DEGREES);
    }

    @Override
    public void teleport(
            double x,
            double y,
            double z,
            float yawDegrees,
            @NotNull BooleanSink whenDone
    ) {
        Objects.requireNonNull(whenDone, "whenDone");
        // The destination's world is the entity's own: this layer never moves a vehicle between worlds,
        // because a world change is a content decision rather than a movement one.
        final Location destination = new Location(
                this.entity.getWorld(), x, y, z, yawDegrees, LEVEL_PITCH_DEGREES);
        this.entity.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                // whenComplete rather than thenAccept: a relocation that failed has to clear the gate
                // just as surely as one that succeeded, or the vehicle never decides anything again.
                .whenComplete((succeeded, failure) ->
                        whenDone.accept(failure == null && Boolean.TRUE.equals(succeeded)));
    }

    @Override
    public @Nullable Player rider() {
        // The first passenger, which is the server's own notion of the one in the driving seat. A
        // vehicle with a non-player passenger has no rider as far as this layer is concerned; the
        // passenger still travels with it, because the platform moves the whole tree.
        final List<Entity> passengers = this.entity.getPassengers();
        if (passengers.isEmpty()) {
            return null;
        }
        final Entity first = passengers.getFirst();
        return first instanceof final Player player ? player : null;
    }

    @Override
    public boolean mount(@NotNull Player newRider) {
        Objects.requireNonNull(newRider, "rider");
        // addPassenger rather than the rider's own startRiding, because the vehicle is the thing this
        // thread owns. The platform guards a mount whose two entities are in different regions and
        // refuses it, which arrives here as false.
        return this.entity.addPassenger(newRider);
    }

    @Override
    public boolean dismount() {
        return this.entity.eject();
    }

    @Override
    public boolean isClientSteered(@NotNull Player candidate) {
        Objects.requireNonNull(candidate, "rider");
        // The authoritative answer when an adapter is present. When one is not, the bridge declines and
        // this returns false — which is why the type-level refusal in ClientSteeredRoots exists and is
        // consulted as well rather than instead.
        return this.movement.isClientSteered(this.entity, candidate);
    }

    @Override
    public @NotNull RiderIntent intentOf(@NotNull Player candidate) {
        Objects.requireNonNull(candidate, "rider");
        return RiderIntent.ofBits(this.input.currentInput(candidate));
    }

    @Override
    public @NotNull String describe() {
        return this.entity.getType() + " " + this.rootId;
    }

    /** The root entity this host drives. */
    public @NotNull Entity entity() {
        return this.entity;
    }

    @Override
    public @NotNull String toString() {
        return "EntityVehicleHost[" + describe() + "]";
    }

    /**
     * Adapts a platform task to the two methods the vehicle layer uses.
     *
     * <p>{@code cancel()} returns nothing useful on this scheduler and {@code isCancelled()} is the whole
     * of the state worth exposing — a task whose entity retired reports itself cancelled, which is how a
     * diagnostic can tell a loop that was stopped from one that stopped itself.
     */
    private record PlatformVehicleTask(@NotNull ScheduledTask task) implements VehicleTask {

        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return this.task.isCancelled();
        }
    }
}
