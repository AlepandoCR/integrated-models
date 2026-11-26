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
     * Rotates the whole model, as the terrain layer decided it should sit.
     *
     * <h2>Why there is a per-step method here after all, and why it is not a second writer</h2>
     * The class note above refuses a per-step <em>pose</em> method, and this is not one. A pose is a
     * per-bone rotation written through {@link pandastic.studio.models.api.render.PoseSink}, and the
     * binding layer's sampler is its single writer. This is the <em>whole-model</em> rotation, which the
     * renderer applies to the model's displays outside their transformations — a different operation
     * reaching the client through different fields of a different packet. Nothing else in a vehicle's
     * lifetime writes it, so the control step is its only writer and there is no interleaving to reason
     * about.
     *
     * <p>Which also means the rig invariant is untouched: no bone leaves its pivot, because no bone is
     * addressed at all.
     *
     * <h2>The yaw has to be supplied, and that is not redundant</h2>
     * It looks redundant, because the control step has just written the same heading to the root entity.
     * It is not: setting a whole-model rotation <strong>replaces</strong> the renderer's own body
     * rotation, which is what was making the model follow the entity's heading. A caller that supplied
     * only a pitch would get a model that pitches correctly and then stops turning, with nothing to point
     * at. So this layer supplies the whole rotation, which it is entitled to do — it is authoritative
     * about the heading.
     *
     * <p>The corollary is that this must not be called at all by a vehicle whose terrain response is
     * inert. Taking over the body rotation to hand it back the value it already had is a trade of the
     * renderer's own smoothing for nothing.
     *
     * <p>Owning thread only, like everything else in a control step.
     *
     * @param pitchDegrees how far to pitch the model, in the sign the server measures pitch in: negative
     *                     is nose up
     * @param yawDegrees   the heading, which is the vehicle's own
     * @param rollDegrees  how far to roll the model, positive with its right side down. Accepted even by
     *                     a backend that cannot express it: a bank that cannot be shown is a model that
     *                     renders upright, and refusing the whole rotation over it would cost the pitch
     *                     and the heading too. See {@link RiggedVehicleVisual} for what the shipped
     *                     backend does with it and why
     */
    default void setBodyRotation(float pitchDegrees, float yawDegrees, float rollDegrees) {
        // A vehicle with no model has nothing to rotate.
    }

    /**
     * Whether any viewer currently holds this model.
     *
     * <h2>What it is for, and why it is on this seam</h2>
     * The terrain reaction is the one part of a vehicle that reads <strong>blocks</strong>, and it exists
     * entirely for the benefit of somebody looking at the model: a lean, a pitch and a step up. So a
     * vehicle no viewer holds should read no blocks, and the place that knows who holds a model is the
     * place that holds the model. See {@link TerrainSampling#onlyWhenSeen()} for the operator's switch
     * and for the one thing it costs.
     *
     * <p>The default is {@code true}, and that is the safe direction rather than the convenient one. A
     * visual that cannot answer the question — a vehicle with no model at all, a backend with no culler
     * — says "assume somebody can see it", so the sampling falls back to the deadband and the interval
     * instead of silently switching the terrain reaction off. The failure a {@code false} default would
     * produce is a vehicle that never reacts to the ground on a server where nothing tracks viewers, and
     * nothing in a test would notice.
     *
     * <p>Readable from the thread that owns the root entity, which is where a control step asks it. It is
     * one read of a concurrent set's size on the shipped backend, published by the culler's own pass.
     *
     * @return whether at least one viewer has this model's bones
     */
    default boolean hasViewers() {
        return true;
    }

    /**
     * The locators of the spawned model, which is where this vehicle's seats are.
     *
     * <p>Asked once, by {@link VehicleImpl#start()} and <strong>after</strong> {@link #spawn()}: a locator
     * is an element of a live model, so before the model stands in the world there is nothing to resolve
     * against. Owning thread only, for the same reason.
     *
     * <p>Here rather than on {@link VehicleHost} because the locators belong to the <em>model</em> and
     * the host knows only about the entity. A vehicle that carries no model therefore answers
     * {@link SeatLocators#NONE} by default, and its declared seats resolve to nothing — which is a
     * reported outcome and not a failure; see {@link SeatChartReport}.
     */
    default @NotNull SeatLocators locators() {
        return SeatLocators.NONE;
    }

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
