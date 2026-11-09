package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.locomotion.LocomotionSolver;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;

import java.util.Objects;

/**
 * The walk cycle of one model, bundled so a {@link ModelSimulation} can carry it as a single optional
 * collaborator rather than four loosely related fields.
 *
 * <p>A stage belongs to exactly one simulation: it owns a mutable {@link GaitState}, so sharing one
 * between two models would have them walk in lockstep and fight over each other's stance flags.
 *
 * <h2>Order within a step is not negotiable</h2>
 * Locomotion runs <strong>before</strong> physics, and after the pose has been cleared. Locomotion
 * writes absolute values — it establishes the base pose — whereas physics adds to whatever it finds.
 * Run them the other way round and the gait overwrites the secondary motion it is supposed to cause;
 * skip the clear and both compound. {@link ModelSimulation#step(long)} is the only place that ordering
 * is expressed.
 */
public final class LocomotionStage {

    private final @NotNull LocomotionSolver solver;
    private final @NotNull LocomotionBinding binding;
    private final @NotNull GaitProfile profile;
    private final @NotNull GaitState state;

    /**
     * A stage driving {@code binding} with {@code solver}.
     *
     * @throws IllegalArgumentException if the solver rejects the binding
     */
    public LocomotionStage(
            @NotNull LocomotionSolver solver,
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile
    ) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.state = solver.createState(binding);
    }

    /** Advances the gait and writes it into {@code pose}. */
    void advance(@NotNull Pose pose, float deltaSeconds, @NotNull PhysicsDriver driver) {
        this.solver.solve(this.binding, this.profile, this.state, pose, deltaSeconds, driver);
    }

    /**
     * Whether the gait has stopped changing.
     *
     * <p>Consulted alongside the physics before a simulation goes silent. Both have to be settled: a
     * model walking on the spot can easily have resting secondary motion while its legs are mid-stride,
     * and treating that as "at rest" would stop the packets while the legs were still visibly swinging.
     */
    boolean isSettled() {
        return this.state.isSettled();
    }

    /** Returns the gait to its rest pose. */
    void reset() {
        this.state.reset();
    }

    /**
     * The mutable gait state, which is also where the stance flags live.
     *
     * <p>Exposed because sneaking and being airborne are inputs a region thread knows and the
     * simulation thread cannot read for itself. Writing a {@code boolean} is as safe across that
     * boundary as the rest of the driver seam.
     */
    public @NotNull GaitState state() {
        return this.state;
    }

    /** Which bones this stage drives. */
    public @NotNull LocomotionBinding binding() {
        return this.binding;
    }

    /** How this model walks. */
    public @NotNull GaitProfile profile() {
        return this.profile;
    }
}
