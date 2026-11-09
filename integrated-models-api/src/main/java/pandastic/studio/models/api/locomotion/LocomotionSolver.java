package pandastic.studio.models.api.locomotion;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.pose.Pose;

/**
 * Turns an owner's movement into a walk cycle: the layer that establishes the primary pose each step,
 * before secondary motion perturbs it.
 *
 * <p>A locomotion solver is a <strong>pure function of its arguments</strong> — a configuration, a
 * driver, a delta and a mutable state in, bone rotations out. It touches no entity, no world, no
 * renderer and no clock, which is the entire reason it is legal to run off the server tick. On a
 * regionised server an entity may only be read from the thread that owns it; sampling a handful of
 * scalars there and handing them over as values is the safety argument, and this interface mentioning no
 * platform type is what keeps that argument checkable.
 *
 * <h2>Locomotion sets; physics adds</h2>
 * {@link PhysicsSolver#solve} is explicitly additive, because secondary motion layers on top of whatever
 * primary pose already exists — which leaves somebody responsible for writing that primary pose, and
 * this is it. So this solver <strong>assigns</strong> absolute rotations to the bones its
 * {@link LocomotionBinding} names, and touches nothing else. Two consequences:
 *
 * <ul>
 *   <li>The caller does <em>not</em> need to clear the bones locomotion owns. It overwrites them.</li>
 *   <li>Locomotion must run <em>before</em> physics on the same pose. Reversed, the additive layer's
 *       offsets are overwritten and the model loses its secondary motion entirely — which looks like
 *       physics being broken rather than like an ordering mistake.</li>
 * </ul>
 *
 * <h2>Evaluated, not integrated</h2>
 * A gait is a closed-form function of a phase that advances with distance travelled, not a differential
 * equation. There is therefore no substep rate here and no stability bound to respect: the solver
 * advances the phase by the ground covered, then evaluates. That is not a performance choice. A second
 * integrator would inherit the first one's fixed-step requirement, and the thing that keeps feet from
 * sliding is precisely that the cycle is a function of distance rather than of time.
 *
 * <p>Implementations are stateless and shareable; all mutable data lives in the {@link GaitState}. One
 * instance may serve every model on the server.
 */
public interface LocomotionSolver {

    /**
     * Allocates the state for one model driven through {@code binding}.
     *
     * <p>The binding is needed because the state remembers the pose width it was built for: a state
     * stepped against a binding for a different rig would write rotations into whatever bone happened to
     * sit at that index, and a silently mismatched skeleton is far harder to diagnose than a thrown
     * exception.
     */
    @NotNull GaitState createState(@NotNull LocomotionBinding binding);

    /**
     * Advances {@code state} by {@code deltaSeconds} and writes the resulting pose.
     *
     * <p>Only the bones {@code binding} names are written, and those are written absolutely. Every other
     * bone in {@code pose} is left exactly as it was found.
     *
     * <p>A non-positive or non-finite delta is a no-op rather than an error: a caller differencing
     * timestamps will hand over a zero sooner or later, and a gait has nothing to do with zero elapsed
     * time. A very large delta is clamped rather than honoured, because the phase is advanced by distance
     * and a ten-second step would advance it by an arbitrary number of cycles — which, modulo one, is an
     * arbitrary phase. Clamping costs a little travelled distance after a stall; not clamping costs the
     * legs snapping to a random point in the stride.
     *
     * @param binding      which bones to drive, and the leg geometry to solve them with
     * @param profile      how this model walks
     * @param state        mutable per-model memory, also carrying the requested stance flags
     * @param pose         the pose to write into; must be as wide as {@code binding} expects
     * @param deltaSeconds wall time to advance
     * @param driver       the owner's movement this step, in blocks per server tick
     * @throws IllegalArgumentException if {@code state} came from another solver, or if the pose and the
     *                                 binding disagree about how many bones the rig has
     */
    void solve(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull GaitState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    );

    /**
     * Horizontal speed, in blocks per server tick, below which the gait settles instead of advancing.
     *
     * <p>Exposed because it is a contract rather than an implementation detail: a caller that smooths or
     * quantises its own speed sampling needs to know what counts as standing still, and a model that
     * micro-steps on the spot is the most visible bug this layer can have.
     */
    float movementDeadband();
}
