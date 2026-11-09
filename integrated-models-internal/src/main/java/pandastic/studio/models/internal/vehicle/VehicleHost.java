package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.vehicle.RiderIntent;

import java.util.UUID;

/**
 * One root entity, the thread allowed to touch it, and every way this layer is permitted to move it.
 *
 * <h2>The seam the whole Folia argument rests on</h2>
 * On a regionised server an entity may only be touched from the thread ticking the region that owns it,
 * and the platform has no strict checking mode — a cross-region read or write fails <em>silently</em>
 * rather than throwing, leaving a torn coordinate or a position that was never applied. So every
 * operation that touches the world lives behind this one object, whose implementation is the only place
 * in the vehicle layer that names a platform type. Everything else in the package — the steering
 * translation, the solver, the step plan, the teleport decision, the mount rules, the teardown ordering —
 * is logic over this seam and is exercised against a substitute.
 *
 * <h2>Why the scheduler is the entity's and not its region's</h2>
 * An entity scheduler follows its entity across region and world changes, and reports its removal
 * through a retired callback. A region-scheduled task is pinned to the region it was posted from, so a
 * control loop dispatched that way writes positions from the wrong thread the moment the vehicle drives
 * over a boundary — which is precisely what a vehicle does for a living, and it does it silently.
 *
 * <h2>Why there are two ways to move and they are not interchangeable</h2>
 * {@link #moveLocal} writes inside the chunks the region already owns and is the ordinary case;
 * {@link #teleport} is a scheduled transaction that removes the entity and its whole passenger tree from
 * the world and re-adds it on the destination region, later. A ticking region cannot expand its owned
 * chunk set mid-tick, so there is no third option and no trick: a displacement that might leave the
 * owned set has to take the slow path. Which of the two a step needs is
 * {@link pandastic.studio.models.api.vehicle.MoveKind}'s decision, made above this seam from a
 * configured threshold.
 *
 * <p>{@link #moveLocal} may also be unavailable — see {@link #supportsLocalMove()} — because it is the
 * one operation here that needs the Mojang-mapped seam, and that seam can be absent on a Minecraft
 * version no shipped adapter was built for. A host that answers false is not broken; it is a host whose
 * every move costs a teleport.
 */
public interface VehicleHost {

    /**
     * The root entity's identity.
     *
     * <p>On the seam rather than read through the entity, because it is the one thing about the root this
     * layer needs from a thread that may not own it: the teardown path runs from anywhere and has to say
     * which vehicle it belonged to. An identity never changes, so a host is expected to capture it once.
     *
     * @return the root entity's unique identifier
     */
    @NotNull UUID rootId();

    /** Whether the root entity still exists. Only meaningful on the owning thread. */
    boolean isAlive();

    /**
     * Whether the calling thread is the one allowed to read and mutate the root entity.
     *
     * <p>The guard on this layer's own entry points. The platform will not guard them, so an entry point
     * that happens to be called from the wrong thread produces a corrupt read or a position write that
     * goes nowhere, with no diagnostic at all.
     */
    boolean isOwnedByCurrentThread();

    /**
     * Fills {@code sample} with the root entity's current position and heading.
     *
     * <p><strong>Owning thread only</strong> — in practice from the body of a task {@link #repeat}
     * dispatched. Everything about the value is undefined otherwise.
     *
     * @param sample a buffer the caller owns and reuses; untouched if this returns {@code false}
     * @return {@code false} if the root entity no longer exists, in which case the vehicle driving it
     *         should stop. This is the second of the two ways a vehicle learns its root is gone, and the
     *         one that catches an entity that is dead but whose scheduler has not been retired yet
     */
    boolean readInto(@NotNull VehicleSample sample);

    /**
     * Dispatches {@code body} every {@code periodTicks} on the thread allowed to touch the root entity.
     *
     * @param body        the control step, run on the owning thread; never concurrently with itself
     * @param retired     run instead, once, if the root entity is removed. For an entity this is the
     *                    <em>only</em> notification the platform gives that it is gone, which is what
     *                    makes it the authoritative teardown trigger. The platform documents it as
     *                    critical code: it must not remove entities, load chunks or load worlds, so the
     *                    callback a vehicle installs here does cheap bookkeeping and nothing else
     * @param periodTicks ticks between executions
     * @return the handle to cancel with, or {@code null} if the root was already gone — in which case
     *         neither callback will ever run, so the caller owns the unwind
     */
    @Nullable VehicleTask repeat(@NotNull Runnable body, @NotNull Runnable retired, long periodTicks);

    /**
     * Runs {@code work} on the thread allowed to touch the root entity, soon.
     *
     * <p>For the pieces of teardown that mutate the world — putting a rider down, releasing the visual —
     * when the teardown was triggered from somewhere else, which is the normal case for a plugin shutting
     * down.
     *
     * @return {@code false} if there is no such thread any more, meaning the root is gone and
     *         {@code work} will never run
     */
    boolean submit(@NotNull Runnable work);

    /**
     * Whether {@link #moveLocal} does anything on this host.
     *
     * <p>Fixed for the host's life, so a caller may read it once. See the class note for what a
     * {@code false} costs.
     */
    boolean supportsLocalMove();

    /**
     * Moves the root entity by a displacement, with collision, inside the owned chunks.
     *
     * <p>Collision is the point: the displacement may be only partly applied, because the entity ends up
     * where the world let it go rather than where it was asked to. That is what gives a vehicle the
     * step-up, the slab climb and the wall stop that make it feel like it is on the ground, and it is why
     * this layer writes horizontal motion and lets the entity's own physics do the falling.
     *
     * <p>Owning thread only, and only for a displacement the caller has already decided is small enough.
     *
     * @return whether the move was applied at all
     */
    boolean moveLocal(double deltaX, double deltaY, double deltaZ);

    /**
     * Sets the root entity's heading.
     *
     * <p>Separate from the movement calls because it is legal on its own: a vehicle turning on the spot
     * has no displacement to write, and a rotation is not subject to the owned-chunk rule. Owning thread
     * only.
     *
     * @param yawDegrees the heading, as the server measures yaw
     */
    void setYaw(float yawDegrees);

    /**
     * Relocates the root entity and its whole passenger tree to an absolute position, asynchronously.
     *
     * <p>The slow path, and a transaction rather than a write: the platform removes the entity from its
     * world, moves it, and re-adds it on the destination region at some later point. Between those the
     * entity is transiently in a removed state, which is why a caller must not keep moving it in the
     * meantime and why this reports completion rather than returning a boolean.
     *
     * <p>Callable from the owning thread; the platform schedules the rest itself.
     *
     * @param x           destination world x
     * @param y           destination world y
     * @param z           destination world z
     * @param yawDegrees  destination heading
     * @param whenDone    told whether the relocation succeeded, on whichever thread finished it. Must
     *                    therefore do nothing but publish a flag
     */
    void teleport(double x, double y, double z, float yawDegrees, @NotNull BooleanSink whenDone);

    /**
     * Who is riding the root entity, if anybody.
     *
     * <p>Asked every step rather than tracked through events, so that a rider who dismounted by holding
     * sneak, disconnected, died or was removed is noticed on the next step without anything having had to
     * be delivered. Owning thread only.
     *
     * @return the rider, or {@code null}
     */
    @Nullable Player rider();

    /**
     * Seats {@code rider} on the root entity.
     *
     * <p>Owning thread only. The platform guards a mount across a region boundary and refuses it, which
     * is a {@code false} rather than an exception because it is a race a caller cannot avoid.
     *
     * @return whether the rider is now aboard
     */
    boolean mount(@NotNull Player rider);

    /**
     * Puts every passenger of the root entity back on the ground.
     *
     * <p>Owning thread only.
     *
     * @return whether there was anybody to remove
     */
    boolean dismount();

    /**
     * Whether the server would let {@code rider}'s client simulate this vehicle's movement.
     *
     * <p>The question {@link pandastic.studio.models.api.vehicle.Vehicle} refuses to be built without an
     * answer to. A {@code true} means every position this layer writes is a correction the client has
     * already drawn past, and the symptom is permanent rubber-banding rather than an error.
     *
     * <p>Owning thread only, because the authoritative answer reads the entity's own passenger list.
     *
     * @param rider the prospective rider
     * @return whether the client would be authoritative
     */
    boolean isClientSteered(@NotNull Player rider);

    /**
     * What {@code rider} is currently holding.
     *
     * <p>The retained value, not an event: see
     * {@link pandastic.studio.models.internal.nms.RiderInputBridge}. Readable from the region that owns
     * the player, which in the ridden case is the region that owns the root — the platform force-resyncs
     * a passenger that drifts more than a chunk from its vehicle, so the two cannot be apart.
     *
     * @param rider whose input to read
     * @return the held keys, or {@link RiderIntent#NONE} if they cannot be read
     */
    @NotNull RiderIntent intentOf(@NotNull Player rider);

    /** This root entity in one short phrase, for a log line or a failure message. */
    @NotNull String describe();

    /**
     * Where {@link #teleport} reports its outcome.
     *
     * <p>A one-method callback rather than a {@code CompletableFuture} so that nothing in this package
     * has to name the platform's completion type, and so that a substitute host can complete it
     * synchronously — which is what makes the teleport path testable at all.
     */
    @FunctionalInterface
    interface BooleanSink {

        /**
         * Reports the outcome.
         *
         * <p>Called on whichever thread finished the relocation, which is <strong>not</strong>
         * necessarily the one that started it. An implementation may therefore do nothing but publish.
         *
         * @param succeeded whether the relocation happened
         */
        void accept(boolean succeeded);
    }
}
