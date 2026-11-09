package pandastic.studio.models.api.physics;

import pandastic.studio.models.api.rig.Rig;

/**
 * The per-rig-instance memory a {@link PhysicsSolver} carries between steps: angular offsets,
 * velocities, and whatever the solver precomputed from the {@link Rig}.
 *
 * <p>Opaque by design. A state is created by the solver that will consume it, belongs to exactly one
 * rig instance, and is never shared between two — including between two models that happen to use the
 * same rig definition.
 *
 * <p>Not thread-safe. Like {@link pandastic.studio.models.api.pose.Pose}, a state is owned by the
 * thread stepping it.
 */
public interface PhysicsState {

    /**
     * Returns every bone to rest and clears all velocity.
     *
     * <p>Note this snaps. Blending out over a few steps is the right behaviour when physics is being
     * disabled for a live model, because a settling bone reads as the model relaxing whereas a reset
     * one reads as a glitch; that blend belongs to the layer that decides to stop simulating, not here.
     */
    void reset();

    /**
     * Whether the simulation has stopped changing.
     *
     * <p>This is the bandwidth lever, not a diagnostic. A bone whose transform did not change needs no
     * packet, and in a resting rig that is every bone — so "at rest" is the difference between a
     * decorative model costing nothing and costing its full bone count forever.
     *
     * <p>Note that at rest is <em>not</em> the same as at zero: a bone whose limits were tightened —
     * a cloth strip resting against its collision cone, say — settles against a bound rather than at
     * its authored angle. The property worth asserting is that the state stops changing, never that it
     * reaches a particular value.
     */
    boolean isAtRest();
}
