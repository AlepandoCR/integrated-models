package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The invisible entity one rider rides, kept on top of their seat's locator.
 *
 * <h2>Why a carrier exists at all</h2>
 * Because a locator is a point and nobody can ride a point. A seat is a position on a <em>model</em>, and
 * a model is display entities posed by packets: there is no server-side thing there to be a vehicle. The
 * protocol only seats a player on an entity, so the seat has to be an entity that goes where the locator
 * goes. One per <em>occupied</em> seat, created when a rider sits down and removed when they get up, so a
 * vehicle with four declared seats and one rider carries one carrier and not four.
 *
 * <h2>Why it is not a passenger of the root entity</h2>
 * It would be tidier and it cannot work. The server computes a passenger's position from its vehicle, so
 * a carrier riding the root would sit wherever the root's own passenger offset puts it — which is one
 * place, is not the locator, and cannot be moved. A carrier is therefore a free entity this layer writes
 * positions to, and the rider rides the carrier.
 *
 * <h2>Folia: one thread, and it is the root's</h2>
 * Every method here is confined to the thread that owns the <em>root</em> entity, which is where the
 * vehicle's control loop runs. That is sound because a carrier sits within a block of the model it is
 * following and Folia groups nearby loaded chunks into one ticking region, so the two entities are owned
 * together. It is nevertheless checked rather than assumed — see {@link #isOwnedByCurrentThread()} — and a
 * step that finds otherwise skips this carrier for that step instead of writing across regions, because a
 * cross-region write on this platform fails silently rather than throwing.
 *
 * <p>The exception is {@link #release()}, which has to work from a teardown triggered anywhere, including
 * from the platform's own retired callback for a root entity that no longer exists. A carrier therefore
 * releases itself through its <strong>own</strong> entity scheduler rather than the root's. That is the
 * whole reason the method exists instead of a caller calling {@code remove()}: at the moment it matters
 * most, the root's scheduler is the one thing guaranteed to be dead.
 */
public interface SeatCarrier {

    /** Whether the carrier entity still exists. Only meaningful on the owning thread. */
    boolean isAlive();

    /**
     * Whether the calling thread may read and mutate the carrier.
     *
     * <p>Asked every step rather than assumed; see the class note. The platform has no strict checking
     * mode, so an unguarded write from the wrong thread produces a position that was never applied and no
     * diagnostic at all.
     */
    boolean isOwnedByCurrentThread();

    /**
     * Fills {@code sample} with the carrier's current world position.
     *
     * <p>Owning thread only. Read fresh every step rather than remembered, because the carrier is an
     * entity in the world: it can have been pushed, blocked or moved by somebody else, and a displacement
     * measured from a remembered position would snap it back.
     *
     * @return {@code false} if the carrier is gone, in which case its seat is vacated
     */
    boolean readInto(@NotNull SeatSample sample);

    /**
     * Moves the carrier by a displacement, in place, and sets its heading.
     *
     * <p>The ordinary path and the preferred one: it writes inside the chunks the region already owns and
     * it does not disturb the passenger, unlike a teleport. It is the same collision-aware move the root
     * entity uses, which for the default carrier is a distinction without a difference — a marker carrier
     * has no bounding box, so there is nothing for it to collide with and the seat goes exactly where the
     * model says. An operator who configures a carrier type that does have a hitbox gets that hitbox's
     * wall stop, which shows up as a rider lagging their seat near geometry.
     *
     * @return whether the write was applied
     */
    boolean moveLocal(double deltaX, double deltaY, double deltaZ, float yawDegrees);

    /** Whether {@link #moveLocal} does anything on this carrier; fixed for its life. */
    boolean supportsLocalMove();

    /**
     * Relocates the carrier and its rider to an absolute position, asynchronously.
     *
     * <p>The slow path, for a displacement too large to write in place — which in practice means the
     * vehicle itself teleported across a region boundary and its seats have to follow. A transaction
     * rather than a write: the platform removes the entity from the world, moves it and re-adds it on the
     * destination region later, and <strong>it may put the rider down in the process</strong>. Which is
     * why the outcome is reported rather than returned, and why {@link VehicleSeats} re-asserts the seat
     * afterwards instead of assuming it survived.
     *
     * @param whenDone told whether the relocation happened, on whichever thread finished it. Must
     *                 therefore do nothing but publish a flag
     */
    void teleport(double x, double y, double z, float yawDegrees, @NotNull VehicleHost.BooleanSink whenDone);

    /**
     * Seats {@code rider} on the carrier.
     *
     * <p>Owning thread only. The platform refuses a mount across a region boundary, which arrives here as
     * {@code false} because it is a race a caller cannot avoid rather than a mistake it made.
     */
    boolean mount(@NotNull Player rider);

    /** Who is riding the carrier, asked rather than remembered. Owning thread only. */
    @Nullable Player rider();

    /**
     * Whether the server would let {@code rider}'s client simulate this carrier.
     *
     * <p>The same question {@link VehicleHost#isClientSteered(Player)} answers about the root, asked
     * again about the thing the rider is actually riding — because with a carrier in between, the root's
     * answer is about the carrier and not about the player. A {@code true} means the operator configured
     * a carrier type whose own class hands control to its first passenger, and every position this layer
     * writes for that seat is a correction the client has already drawn past.
     */
    boolean isClientSteered(@NotNull Player rider);

    /**
     * Puts the rider down and removes the carrier, on a thread allowed to do both.
     *
     * <p>Idempotent, safe from any thread, and the only teardown this interface offers — see the class
     * note for why a carrier releases itself through its own scheduler rather than being removed by its
     * caller. A release that cannot be scheduled means the entity is already gone, which is the outcome
     * the caller wanted.
     */
    void release();
}
