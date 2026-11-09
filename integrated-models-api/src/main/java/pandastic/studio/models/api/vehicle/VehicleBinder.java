package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

/**
 * How a consumer says "make this entity a vehicle that drives like this and looks like that".
 *
 * <p>The one entry point into the vehicle layer, and the only type in it that names a platform object
 * besides the rider.
 *
 * <h2>The caller supplies the root entity, on purpose</h2>
 * Three reasons, and the first is the important one.
 *
 * <ol>
 *   <li><strong>The choice of entity type decides who is authoritative.</strong> The server hands
 *       control of a vehicle to its rider's client whenever the root entity's own class says the first
 *       passenger is its controlling passenger — which is what a boat, a saddled pig, a horse, a camel
 *       and a strider all say. Mount a rider on one of those and the client simulates the vehicle and
 *       the server merely checks its homework; every position this layer writes is then fighting a
 *       prediction that has already been drawn. That is not a thing a plugin can opt out of per entity,
 *       so the only correct answer is to <em>pick a different root</em>, and the only place that
 *       decision can be made is where the entity is spawned. {@link #create} checks it and refuses, so
 *       a wrong choice fails at creation with a sentence rather than at drive time with rubber-banding.</li>
 *   <li>The root's type decides its hitbox, its gravity, its collision and whether it steps up a slab —
 *       all of which this layer deliberately leaves to the server's own entity physics rather than
 *       reimplementing badly.</li>
 *   <li>The root's lifetime is the caller's. A vehicle despawned at shutdown should leave an entity
 *       behind that the next start can pick up, which is only possible if this layer never created it.</li>
 * </ol>
 *
 * <h2>Creating validates; it does not drive</h2>
 * {@link #create} resolves the rig against the model, refuses a client-controlled root, spawns the visual
 * and starts the control loop, all on the root entity's own thread. It is therefore the one call in this
 * interface with a thread rule, and the rule is the same one the whole project has: touch an entity only
 * from the thread that owns it, because on a regionised server doing otherwise is undefined rather than
 * merely racy, and nothing will tell you.
 */
public interface VehicleBinder {

    /**
     * Makes {@code root} a vehicle driving {@code rig} under {@code profile}.
     *
     * <p>Must be called from the thread that owns {@code root}. In practice that is the body of a task on
     * its own scheduler, or an event handler for it.
     *
     * @param rig     the rig to render on it, as loaded from this plugin's rig directory
     * @param profile how it drives, already validated against the server's bounds
     * @param root    the entity carrying the hitbox, the collision and the replicated position. Must not
     *                be a type whose own class makes its first passenger the controlling passenger; see
     *                the class documentation
     * @return the vehicle, already alive and being controlled — except in one case, which is the only
     *         thing a caller has to check for: if {@code root} vanished between this call and the control
     *         loop being dispatched, the handle comes back already despawned and
     *         {@link Vehicle#isAlive()} answers {@code false}. That is a race a caller cannot avoid and
     *         cannot act on, which is why it is a return value rather than an exception
     * @throws IllegalStateException if called off {@code root}'s owning thread; if {@code root} is a type
     *                               the server would let a rider's client simulate; if the renderer has
     *                               no model by the name the rig points at; or if the model is missing
     *                               any bone the rig names, in which case the message lists every
     *                               unresolved name rather than the first
     * @throws IllegalArgumentException if {@code profile} is outside the bounds the operator configured,
     *                                  naming the component and the bound
     */
    @NotNull Vehicle create(@NotNull Rig rig, @NotNull VehicleProfile profile, @NotNull Entity root);
}
