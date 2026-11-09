package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

import java.util.Optional;
import java.util.UUID;

/**
 * One modelled vehicle: the handle a downstream plugin holds onto.
 *
 * <h2>Server-authoritative, and what that costs</h2>
 * The server decides where this vehicle is. The rider's keys are an <em>input</em> to that decision and
 * nothing more — no position the client sends is ever accepted, and no prediction the client makes is
 * ever honoured. The rider therefore sees their own input a round trip late, which is the price of a
 * vehicle whose speed, turn rate and collision behaviour are things the server can state rather than
 * things the client can be persuaded of.
 *
 * <p>The alternative is worse and is easy to reach by accident: a vehicle whose
 * {@code getControllingPassenger()} is the rider is a vehicle the <em>client</em> simulates and whose
 * position the server merely validates. That is a different product. It is smoother, it is unauthoritative,
 * and any server-side movement applied to it fights the client's prediction forever — the vehicle
 * visibly rubber-bands between what the server decided and what the client already drew.
 * {@link VehicleBinder#create} refuses a root entity the server would hand to the client for exactly
 * this reason, which is a refusal at mount time rather than a mystery at drive time.
 *
 * <h2>One server entity, however many displays</h2>
 * A vehicle is a single root entity carrying the hitbox, the collision and the position the server
 * replicates, with the rig's display entities posed on top of it by the rendering pipeline. It is
 * <strong>not</strong> several server entities that have to tick in lockstep, and that is not a
 * simplification: a construct spread across several entities cannot be coherently ticked on a regionised
 * server, because the entities can end up in different regions and there is no moment at which both
 * threads agree about where the construct is.
 *
 * <h2>Thread rules a consumer has to respect</h2>
 * <ul>
 *   <li>{@link #mount(Player)} and {@link #dismount()} must be called from the thread that owns the root
 *       entity — which is where an event handler for that entity, or a task on its own scheduler,
 *       already runs. Called from anywhere else they throw rather than corrupting a mount.</li>
 *   <li>{@link #despawn()} may be called from any thread, at any time, any number of times. It has to
 *       be: the most common caller is the platform telling us the entity is gone, and the second most
 *       common is the plugin shutting down.</li>
 *   <li>{@link #getState()}, {@link #getSteering()}, {@link #getRider()}, {@link #isAlive()},
 *       {@link #getProfile()} and {@link #getRig()} are safe from any thread. Each is one read of one
 *       immutable value, published whole.</li>
 * </ul>
 *
 * <h2>Single use</h2>
 * A vehicle is created once and despawned once, like a
 * {@link pandastic.studio.models.api.binding.RiggedModel} and for the same reason: the visual it carries
 * caches a transformer per bone against a tracker, and a handle that could be revived would have to
 * decide what those point at after the tracker is gone. Every answer to that is a vehicle that looks
 * alive and renders nowhere.
 */
public interface Vehicle extends AutoCloseable {

    /**
     * This vehicle's identity, stable across its whole life.
     *
     * <p>Not the root entity's UUID. The root entity is an implementation choice this interface does not
     * expose, and two vehicles sharing one root is a shape the layer below permits.
     *
     * @return the identifier
     */
    @NotNull UUID getUniqueIdentifier();

    /** The rig driving this vehicle's visual. */
    @NotNull Rig getRig();

    /** How this vehicle drives. Fixed for its life; create another to change it. */
    @NotNull VehicleProfile getProfile();

    /**
     * How the vehicle is moving right now.
     *
     * <p>A snapshot, published whole by the control loop. Reading it twice can straddle two steps, so a
     * caller that needs a coherent pair of numbers should take one state and use it.
     *
     * @return the current state
     */
    @NotNull VehicleState getState();

    /**
     * What the rider is currently asking for, as the control loop last resolved it.
     *
     * <p>{@link VehicleSteering#NEUTRAL} when nobody is riding. Exposed so a plugin can react to the
     * jump key, which this layer carries and deliberately does not act on.
     *
     * @return the current steering
     */
    @NotNull VehicleSteering getSteering();

    /**
     * Who is riding, if anybody.
     *
     * <p>Empty rather than stale when the rider dismounted, disconnected or was removed: the control
     * loop re-checks the passenger each step, so nothing about the answer depends on an event having
     * been delivered.
     *
     * @return the rider, or empty
     */
    @NotNull Optional<Player> getRider();

    /**
     * Seats {@code rider}.
     *
     * <p>Must run on the thread that owns the root entity; see the class documentation.
     *
     * @param rider who to seat
     * @return {@code true} if the rider is now aboard; {@code false} if somebody else already is, if
     *         this vehicle is not alive, or if the platform refused the mount — which it does for a
     *         cross-region mount, and which is a race a caller cannot avoid rather than a mistake it made
     * @throws IllegalStateException if called from a thread that does not own the root entity
     */
    boolean mount(@NotNull Player rider);

    /**
     * Puts the current rider back on the ground.
     *
     * <p>Must run on the thread that owns the root entity. Note that a rider can also leave without this
     * being called at all: holding sneak dismounts through the server's own path, and the control loop
     * notices on its next step.
     *
     * @return whether there was a rider to remove
     * @throws IllegalStateException if called from a thread that does not own the root entity
     */
    boolean dismount();

    /** Whether this vehicle is being controlled and rendered. */
    boolean isAlive();

    /**
     * Stops the control loop, puts any rider down and releases the visual.
     *
     * <p>Idempotent and safe from any thread. Does <strong>not</strong> remove the root entity: the
     * caller supplied it, so the caller owns its lifetime — a vehicle despawned at shutdown should leave
     * behind an entity that can be picked up again on the next start.
     */
    void despawn();

    /**
     * {@link #despawn()}, so a vehicle can be managed with try-with-resources.
     *
     * <p>Declared without the checked exception {@link AutoCloseable} permits: nothing about releasing a
     * vehicle can fail in a way a caller could handle.
     */
    @Override
    default void close() {
        despawn();
    }
}
