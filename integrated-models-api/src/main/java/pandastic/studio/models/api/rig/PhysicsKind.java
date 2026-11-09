package pandastic.studio.models.api.rig;

/**
 * How a bone's secondary motion is integrated.
 *
 * <p>Every kind is solved in <strong>angle space</strong>: a bone keeps a three-component angular
 * offset, is carried by however far its ancestors rotated last step, and is pulled back toward its
 * rest pose. No kind ever moves a bone off its pivot — see {@link pandastic.studio.models.api.pose.Pose}
 * for why that is an invariant rather than a simplification.
 */
public enum PhysicsKind {

    /**
     * A damped spring with gravity on the pitch axis. Hair, tails, anything that should hang and ring.
     */
    PENDULUM,

    /**
     * A damped spring with no gravity pull, so its rest pose is wherever the rig put it.
     * Antennae, floating ornaments, anything that should return to centre rather than hang.
     */
    SPRING,

    /**
     * {@link #PENDULUM} plus an angular collision cone: the strip may not swing further inward than
     * the angle whose chord equals {@link PhysicsProfile#collisionRadius()}. The approximation is that
     * the strip is a straight segment, which for a short strip is what it is.
     */
    CLOTH_STRIP,

    /**
     * Exponential lag with no velocity term — the bone trails its parent and settles, but never
     * overshoots or oscillates. Cheaper and unconditionally stable; use it when ringing is unwanted.
     * Driven by {@link PhysicsProfile#lag()} rather than by stiffness and damping.
     */
    DRAG
}
