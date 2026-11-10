package pandastic.studio.models.api.locomotion;

/**
 * The per-model memory a {@link LocomotionSolver} carries between steps: where in the walk cycle the
 * model is, how strongly the cycle is being expressed, and how far through each stance blend it has got.
 *
 * <p>Opaque by design, exactly like {@link pandastic.studio.models.api.physics.PhysicsState}. A state is
 * created by the solver that will consume it and belongs to one model — two models sharing a state would
 * share a phase, which is visible immediately as a crowd marching in lockstep.
 *
 * <h2>Stance flags are inputs, and they live here rather than in the signature</h2>
 * "Airborne" and "sneaking" are not speeds, so they are not in
 * {@link pandastic.studio.models.api.physics.PhysicsDriver}; but they are also not instantaneous, because
 * both are blended rather than snapped. A blend needs a target and a current value, and the current value
 * has to survive between steps — so the state is where both belong. Set them on the thread that owns the
 * entity, in the same place the driver's speeds are sampled, and the solver will ease toward them.
 *
 * <p>Not thread-safe. Like a {@link pandastic.studio.models.api.pose.Pose}, a state is owned by whichever
 * thread is stepping it.
 */
public interface GaitState {

    /**
     * Requests one generic right-hand attack swing. The request is consumed by the locomotion solver
     * on its next step; repeated requests restart the swing from its beginning.
     *
     * <p>The default is a no-op so custom gait implementations that do not support attack posing remain
     * source-compatible. The built-in solver uses the request when the corresponding arm roles exist.
     */
    default void triggerAttack() {
    }

    /**
     * Returns the gait to a standing rest: phase zero, no amplitude, no blends, no elapsed time.
     *
     * <p>Note this snaps, and that snapping is wrong for a model that is visibly walking — resetting
     * mid-stride teleports the legs. Easing out belongs to the layer that decides to stop driving the
     * model, which can simply stop feeding it speed and let the deadband settle it.
     */
    void reset();

    /**
     * Position in the walk cycle, {@code [0, 1)}. One full cycle is one step with each leg.
     *
     * <p>Advances with <em>distance travelled</em>, not with time. That is the single property that keeps
     * feet from sliding, and it is why a gait is evaluated from a phase rather than integrated.
     */
    float phase();

    /**
     * How strongly the walk cycle is expressed, {@code [0, 1]}, eased toward the owner's speed.
     *
     * <p>Zero inside the movement deadband, saturating at full walking speed. This is what makes a model
     * settle into a stand instead of mincing on the spot.
     */
    float amplitude();

    /** How airborne the model is, {@code [0, 1]}. At {@code 1} the walk cycle contributes nothing. */
    float airborneBlend();

    /** How crouched the model is, {@code [0, 1]}. Scales the torso's crouch pitch. */
    float sneakBlend();

    /**
     * Wall time this gait has been stepped, in seconds, wrapped at a whole number of breathing cycles.
     *
     * <p>Wrapped rather than left to grow, for two reasons that point the same way: a float odometer
     * loses resolution against a fixed oscillation frequency after a few hours, and wrapping at a whole
     * number of cycles makes the wrap continuous — the breathing does not jump when it happens.
     */
    float elapsedSeconds();

    /** Whether the owner is being reported as airborne. The blend eases toward this. */
    boolean isAirborne();

    /** Requests the airborne stance. Call from the thread that owns the entity. */
    void setAirborne(boolean airborne);

    /** Whether the owner is being reported as sneaking. */
    boolean isSneaking();

    /** Requests the crouched stance. */
    void setSneaking(boolean sneaking);

    /**
     * Whether the walk cycle has stopped contributing — amplitude decayed and both stance blends
     * resolved.
     *
     * <p><strong>Not</strong> the bandwidth lever {@link
     * pandastic.studio.models.api.physics.PhysicsState#isAtRest()} is. A settled gait still breathes, so
     * the pose keeps changing and the bones still need packets. What this answers is the narrower question
     * of whether the locomotion layer is done reacting to the last movement.
     */
    boolean isSettled();
}
