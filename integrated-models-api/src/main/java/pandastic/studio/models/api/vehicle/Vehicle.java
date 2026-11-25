package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.Model;

import java.util.List;
import java.util.Optional;

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
 *
 * <h2>It is a {@link Model}</h2>
 * Identity, name, rig, spawned state, {@code despawn}/{@code close}, viewers and the appearance surface
 * are that interface's, so a plugin holding a mixed collection of this plugin's models can shut a vehicle
 * down and ask what it is without knowing it is a vehicle. What this subinterface adds is everything
 * about <em>driving</em>: the profile, the state, the steering and the rider.
 */
public interface Vehicle extends Model {

    /**
     * {@inheritDoc}
     *
     * <p>Always empty on a vehicle, deliberately. The root entity is an implementation choice this
     * interface does not expose — a caller that could reach it could mount a rider on it directly, which
     * is precisely the thing {@link VehicleBinder#create} refuses in order to stay authoritative.
     */
    @Override
    default @NotNull Optional<Entity> getEntity() {
        return Optional.empty();
    }

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
     * Who is <strong>driving</strong>, if anybody.
     *
     * <p>The rider in the seat the rig declared as the driver's, which is the one whose input steers.
     * Empty when that seat is free, when the rig declares none, and when its locator did not resolve —
     * so a vehicle can be full of passengers and answer empty here, which is the right answer for a
     * question whose subject is who is steering. {@link #getRiders()} is everybody.
     *
     * <p>Empty rather than stale when the driver dismounted, disconnected or was removed: the control
     * loop re-checks every seat each step, so nothing about the answer depends on an event having been
     * delivered. Nobody is promoted into the empty seat; see {@link #board(Player)}.
     *
     * @return the driver, or empty
     */
    @NotNull Optional<Player> getRider();

    /**
     * Every seat this vehicle's rig declares, indexed, with the driver's first.
     *
     * <p>Fixed for the vehicle's life and safe from any thread: it is derived from the rig, which does
     * not change. Which of these seats anybody can actually sit on is {@link #getSeating()}'s question,
     * because that depends on the live model.
     *
     * @return the plan
     */
    @NotNull SeatPlan getSeatPlan();

    /**
     * Who is in which seat, right now.
     *
     * <p>A snapshot, published whole by the thread that seats people, and safe to read from any thread.
     * Reading it twice can straddle two steps, so a caller asking more than one question should take one
     * occupancy and use it — the same rule {@link #getState()} has.
     *
     * @return the current occupancy
     */
    @NotNull SeatOccupancy getSeating();

    /** Everybody aboard, in seat index order — so the driver first, when there is one. */
    default @NotNull List<Player> getRiders() {
        return getSeating().riders();
    }

    /** What this vehicle says to its riders. The consumer's, supplied at creation. */
    @NotNull VehicleMessages getMessages();

    /** What each seat's inputs do beyond steering. The consumer's, supplied at creation. */
    @NotNull VehicleControls getControls();

    /**
     * Seats {@code rider} in the lowest free seat.
     *
     * <p>The boolean form of {@link #board(Player)}, kept because it is the question most callers have.
     * Everything the richer form says about the boarding rule, the notifications and the driver's seat
     * applies unchanged.
     *
     * <p>Must run on the thread that owns the root entity; see the class documentation.
     *
     * @param rider who to seat
     * @return {@code true} if the rider is now aboard
     * @throws IllegalStateException if called from a thread that does not own the root entity, or if
     *                               seating them in the driver's seat would hand the vehicle's movement
     *                               to their client
     */
    boolean mount(@NotNull Player rider);

    /**
     * Seats {@code rider} in the lowest free seat, and says what happened.
     *
     * <h2>The boarding rule</h2>
     * The <strong>lowest free index</strong>, where free means usable and empty. Since the driver's seat
     * is {@link SeatPlan#DRIVER_INDEX}, the first player to board drives, the second takes index 1 — the
     * second seat — and so on until every usable seat is taken.
     *
     * <p>Full is a normal outcome: the rider is told, through this vehicle's own
     * {@link VehicleNotice#VEHICLE_FULL} message, and {@link BoardingOutcome#VEHICLE_FULL} comes back.
     * It is neither a silent {@code false} nor an exception.
     *
     * <h2>What happens when the driver leaves and passengers stay</h2>
     * <strong>Nothing is promoted.</strong> The driver's seat empties, the vehicle coasts to a stop and
     * carries its passengers undriven, and the next player to board takes the driver's seat — because
     * boarding fills the lowest free index and that is now the lowest free index.
     *
     * <p>Three reasons, and the first is about the player rather than the code. Promotion would put
     * somebody in control of a moving vehicle without their asking, and the one thing it cannot do is
     * ask them. It would also have to move them between seats mid-step, which means releasing one
     * carrier and spawning another and re-asking the authority question about the new seat — a question
     * whose answer can be "no", at which point a passenger who was riding happily has been thrown off.
     * And it would break what a seat index is for: a consumer who wired seat 2 to aim a weapon would
     * find that rider steering instead, with no event to react to.
     *
     * <p>A consumer that <em>wants</em> promotion has it in one call and in the open:
     * {@code vehicle.board(passenger, SeatPlan.DRIVER_INDEX)} after
     * {@link #dismount(Player)}. Deliberately two steps, because each of them is a thing the plugin
     * should mean.
     *
     * <p>Must run on the thread that owns the root entity.
     *
     * @param rider who to seat
     * @return what happened, and which seat when it worked
     * @throws IllegalStateException if called from a thread that does not own the root entity, or if
     *                               seating them in the driver's seat would hand the vehicle's movement
     *                               to their client — the authority refusal, which is a bug in the
     *                               caller's choice of entity type rather than a condition of the world
     */
    @NotNull BoardingResult board(@NotNull Player rider);

    /**
     * Seats {@code rider} in one particular seat.
     *
     * <p>For a menu, a command or a consumer that knows which seat it means. Unlike
     * {@link #board(Player)} this never falls back to another seat: an occupied one answers
     * {@link BoardingOutcome#SEAT_TAKEN} and an index no seat has answers
     * {@link BoardingOutcome#NO_SUCH_SEAT}, both with the configured message.
     *
     * <p>Must run on the thread that owns the root entity.
     *
     * @param rider     who to seat
     * @param seatIndex the seat's index, as {@link #getSeatPlan()} numbers them
     * @return what happened
     * @throws IllegalStateException if called from a thread that does not own the root entity, or if the
     *                               seat is the driver's and their client would be the authority
     */
    @NotNull BoardingResult board(@NotNull Player rider, int seatIndex);

    /**
     * Puts every rider back on the ground.
     *
     * <p>Must run on the thread that owns the root entity. Note that a rider can also leave without this
     * being called at all: holding sneak dismounts through the server's own path, and the control loop
     * notices on its next step and sends {@link VehicleNotice#DISMOUNTED} then.
     *
     * @return whether there was anybody to remove
     * @throws IllegalStateException if called from a thread that does not own the root entity
     */
    boolean dismount();

    /**
     * Puts one rider back on the ground, leaving everybody else aboard.
     *
     * <p>Must run on the thread that owns the root entity.
     *
     * @param rider who to put down
     * @return whether they were in one of this vehicle's seats
     * @throws IllegalStateException if called from a thread that does not own the root entity
     */
    boolean dismount(@NotNull Player rider);

    /**
     * {@inheritDoc}
     *
     * <p>On a vehicle, "spawned" is "being controlled and rendered". {@link #isAlive()} is the name the
     * vehicle layer's own documentation uses for it and answers the same question.
     */
    @Override
    boolean isSpawned();

    /**
     * Stops the control loop, puts any rider down and releases the visual.
     *
     * <p>Idempotent and safe from any thread. Does <strong>not</strong> remove the root entity: the
     * caller supplied it, so the caller owns its lifetime — a vehicle despawned at shutdown should leave
     * behind an entity that can be picked up again on the next start.
     */
    @Override
    void despawn();
}
