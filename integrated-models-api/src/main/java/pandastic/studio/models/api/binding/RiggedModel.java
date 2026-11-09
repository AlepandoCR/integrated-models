package pandastic.studio.models.api.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

import java.util.UUID;

/**
 * One rig driving one thing in the world: the handle a downstream plugin holds onto.
 *
 * <p>Everything that makes a rigged model work — the renderer's tracker, the pose sink, the physics
 * simulation, the per-tick sampler that feeds it — lives behind this interface and is named nowhere in
 * the published jar. A consumer gets a rig from {@link pandastic.studio.models.api.IntegratedModels},
 * hands it to a {@link ModelBinder}, and is given one of these. The four things it can then do are the
 * four methods below.
 *
 * <h2>Bound is not spawned</h2>
 * {@link ModelBinder#bind} validates the rig against the model and nothing else. {@link #spawn()} is
 * what creates the tracker, resolves every bone and starts the simulation. The split exists because the
 * two have completely different failure modes and completely different threading rules: validation is
 * pure and may run anywhere, whereas spawning touches an entity and therefore may only run on the thread
 * that owns it. Collapsing them into one call would hide the second constraint behind the first.
 *
 * <h2>Single use, deliberately</h2>
 * A model spawns once and despawns once. A despawned handle is spent: {@link #spawn()} refuses to
 * resurrect it, and binding again is the supported way to get a fresh one. This is not a limitation
 * worth working around — a respawnable handle has to decide what happens to a sink whose bone
 * transformers were cached against a tracker that no longer exists, and every answer to that is a
 * silently stale binding. Binding again is cheap: it is a validation pass and an allocation.
 *
 * <h2>Thread rules a consumer has to respect</h2>
 * <ul>
 *   <li>{@link #spawn()} must be called from the thread that owns whatever the model is attached to.
 *       For an entity-backed model that is the entity's own region thread, which is where an event
 *       handler for that entity already runs. Called from anywhere else it throws rather than
 *       corrupting a position read, because the platform will not catch this for you.</li>
 *   <li>{@link #despawn()} may be called from any thread, at any time, any number of times. It has to
 *       be: the most common caller is the platform telling us the entity is gone.</li>
 *   <li>{@link #isAlive()} and {@link #getRig()} are safe from any thread.</li>
 * </ul>
 */
public interface RiggedModel extends AutoCloseable {

    /**
     * The rig this model drives.
     *
     * <p>Fixed for the handle's whole life. A rig is immutable, so holding it is safe — but a
     * configuration reload replaces the loaded definitions, so a model bound before a reload keeps
     * driving the definition it was bound to until it is rebound.
     */
    @NotNull Rig getRig();

    /**
     * This model's identity, stable across spawn and despawn.
     *
     * <p>Not the attached entity's UUID. Two models on one entity are legal — a cloak and a tail, say —
     * so the entity's identity cannot name a binding.
     */
    @NotNull UUID getUniqueIdentifier();

    /**
     * Creates the renderer-side binding and starts simulating.
     *
     * <p>Must run on the thread that owns what this model is attached to; see the class documentation.
     *
     * @return {@code true} if the model is now alive, {@code false} if it was already alive or if the
     *         thing it is attached to vanished between binding and this call. A {@code false} return is
     *         a race a caller cannot avoid, which is why it is a return value rather than an exception
     * @throws IllegalStateException if called from the wrong thread, if this handle has already been
     *                               despawned, or if the rig cannot be bound to the live model — in
     *                               which case the message names every bone that failed, not the first
     */
    boolean spawn();

    /**
     * Stops simulating and releases everything this model holds.
     *
     * <p>Idempotent, safe from any thread, and safe to call on a model that never spawned. The teardown
     * runs exactly once however many callers reach it and from however many threads, because the thing
     * most likely to call it is the platform noticing the entity is gone at the same moment as a plugin
     * shutting down.
     */
    void despawn();

    /** Whether this model is spawned and being simulated. */
    boolean isAlive();

    /**
     * {@link #despawn()}, so a model can be managed with try-with-resources.
     *
     * <p>Declared without the checked exception {@link AutoCloseable} permits: nothing about releasing a
     * model can fail in a way a caller could handle, and a checked exception here would force a catch
     * block at every call site that does nothing.
     */
    @Override
    default void close() {
        despawn();
    }
}
