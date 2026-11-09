package pandastic.studio.models.api.physics;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;

/**
 * Turns a rig plus its owner's movement into bone rotations.
 *
 * <p>A solver is a <strong>pure function of its state</strong>: it reads the pose other layers have
 * already written, adds secondary motion, and writes back. It touches no entity, no world and no
 * renderer, which is what makes it legal to run on an arbitrary thread — and is why this interface
 * mentions none of those things.
 *
 * <p>Implementations are stateless and shareable; all mutable data lives in the {@link PhysicsState}
 * handed to {@link #solve}. One solver instance may therefore serve every model on the server.
 */
public interface PhysicsSolver {

    /**
     * Allocates the state for one instance of {@code rig}, precomputing whatever the solver derives
     * from the rig's shape.
     *
     * @throws IllegalArgumentException if any bone's tuning would make the integrator diverge at this
     *                                 solver's step size. Failing here is deliberate: a divergent
     *                                 spring does not look wrong, it produces NaN and deletes the
     *                                 model, and the rig author is the only person who can fix it.
     */
    @NotNull PhysicsState createState(@NotNull Rig rig);

    /**
     * Advances {@code state} by {@code deltaSeconds} and writes the result into {@code pose}.
     *
     * <p>Secondary motion is <strong>added</strong> to whatever is already in the pose, because it
     * layers on top of animation and locomotion rather than replacing them. Resetting or rewriting the
     * base pose each step is therefore the caller's job: feed the same pose back in without clearing it
     * and the offsets compound, which looks like a bone slowly winding itself up.
     *
     * <p>The solver runs a fixed internal step regardless of {@code deltaSeconds} — see
     * {@link #substepRateHz()} — because a spring is only stable below a step size its own stiffness
     * sets, and a caller's frame time is not something the solver can trust. Time beyond what the
     * substep budget covers is <strong>discarded rather than accumulated</strong>: carrying a backlog
     * guarantees that the step after a stall is far larger than the integrator is stable for, which is
     * precisely how a lag spike turns into an exploded chain.
     *
     * @param rig          the rig {@code state} was created for
     * @param state        mutable per-instance memory
     * @param pose         the pose to read ancestors from and write results into
     * @param deltaSeconds wall time to advance; non-positive is a no-op
     * @param driver       the owner's movement this step
     */
    void solve(
            @NotNull Rig rig,
            @NotNull PhysicsState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    );

    /**
     * The fixed internal step rate, in hertz.
     *
     * <p>This is a <em>stability</em> requirement, not a smoothness one. Smoothness above the server
     * tick comes from the client interpolating between the transforms it is sent, and costs nothing;
     * the substep rate exists because the permitted stiffness range diverges at coarser steps.
     */
    int substepRateHz();

    /**
     * The most substeps {@link #solve} will run for a single call.
     *
     * <p>Bounds the work one stalled frame can demand. Together with the discard rule in
     * {@link #solve}, this is what makes the solver's cost per call constant.
     */
    int maxSubsteps();
}
