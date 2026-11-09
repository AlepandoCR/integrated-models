package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;

/**
 * The rig a vehicle carries, reduced to its lifecycle.
 *
 * <h2>Why this seam has no per-step method, and why that is the design rather than an omission</h2>
 * Because the visual is driven by the vehicle's <em>entity</em>, not by the vehicle's control loop, and
 * there must be exactly one writer.
 *
 * <p>The pose pipeline already has everything it needs. A rigged model bound to the root entity samples
 * that entity once per tick on its own thread, differences the position into a body-frame speed and a
 * yaw rate, publishes it as an immutable driver, and the gait turns that into a stride and the physics
 * into secondary motion. A vehicle that moves its root entity therefore makes its own wheels turn and its
 * own legs swing <em>by construction</em> — the same way a walking mob does, through the same code, with
 * no vehicle-specific wiring at all.
 *
 * <p>Pushing the commanded motion into the model directly would look better on paper: it is a tick
 * earlier and it survives the teleport filter. It would also mean two writers into the one volatile the
 * pipeline publishes through — the binding layer's sampler and the vehicle's control step, both on the
 * same region thread, interleaved in whatever order the tick happens to run them. The result is not a
 * memory race but it is worse than one: it is two plausible values and no way to say which was used.
 * One writer, and the writer is the layer whose job that is.
 *
 * <p>The consequence is worth stating plainly, because it is what the configuration cross-check in
 * {@code vehicle.yml} exists for: the gait sees the vehicle's movement only while that movement stays
 * under the pose pipeline's own teleport threshold. A vehicle configured faster than that threshold has a
 * visual that freezes at top speed, which is why the two files are validated against each other instead
 * of being left to disagree.
 *
 * <h2>So what is left is spawn and release, and that is not nothing</h2>
 * Spawning resolves every rig bone against the live model and fails with the complete list of names that
 * did not resolve; releasing has to happen on a thread allowed to mutate the world, exactly once,
 * however many directions the teardown arrives from. Both are real, both have thread rules, and both are
 * things a test needs to observe without a renderer.
 */
public interface VehicleVisual {

    /**
     * Creates the renderer-side binding and starts posing.
     *
     * <p>Must run on the thread that owns the root entity.
     *
     * @return {@code false} if the visual was already alive, or if the thing it attaches to vanished
     *         between creation and this call
     * @throws IllegalStateException if the rig cannot be bound to the live model, in which case the
     *                               message names every bone that failed rather than the first
     */
    boolean spawn();

    /**
     * Stops posing and releases everything the visual holds.
     *
     * <p>Idempotent, safe from any thread, and safe to call on a visual that never spawned.
     */
    void despawn();

    /** Whether the visual is spawned and being posed. */
    boolean isAlive();

    /**
     * A vehicle with no model on it.
     *
     * <p>Not a test double: a vehicle whose root entity is itself the thing the player sees — a cart
     * built out of block displays somebody else owns, a vehicle still being prototyped — is a legitimate
     * shape, and it should not have to invent a rig to get a control loop.
     */
    @NotNull VehicleVisual NONE = new VehicleVisual() {

        @Override
        public boolean spawn() {
            return true;
        }

        @Override
        public void despawn() {
            // Nothing to release.
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public @NotNull String toString() {
            return "VehicleVisual.NONE";
        }
    };
}
