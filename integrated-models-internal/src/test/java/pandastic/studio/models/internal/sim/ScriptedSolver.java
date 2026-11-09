package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A solver that records what it was told and lets the test decide when the simulation is at rest.
 *
 * <p>It mimics the one property of the real solver that the caller has to cooperate with: it
 * <strong>adds</strong> its offset to the pose rather than setting it. So a caller that forgets to
 * clear the base pose produces a visibly compounding rotation, which is what makes that bug testable at
 * all.
 */
final class ScriptedSolver implements PhysicsSolver {

    /** The pitch added to bone 0 on every solve. */
    static final float OFFSET_PER_SOLVE = 0.125F;

    private final @NotNull List<Float> deltas = new ArrayList<>();
    private final @NotNull List<PhysicsDriver> drivers = new ArrayList<>();

    private int restAfterSolves = Integer.MAX_VALUE;
    private int solveCount;
    private @Nullable RuntimeException failure;

    @Override
    public @NotNull PhysicsState createState(@NotNull Rig rig) {
        return new ScriptedState();
    }

    @Override
    public int substepRateHz() {
        return 60;
    }

    @Override
    public int maxSubsteps() {
        return 4;
    }

    @Override
    public void solve(
            @NotNull Rig rig,
            @NotNull PhysicsState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    ) {
        this.deltas.add(deltaSeconds);
        this.drivers.add(driver);
        this.solveCount++;
        if (this.failure != null) {
            throw this.failure;
        }
        pose.add(0, OFFSET_PER_SOLVE, 0F, 0F);
        ((ScriptedState) state).atRest = this.solveCount > this.restAfterSolves;
    }

    /** The simulation reports at rest from solve number {@code solves + 1} onward. */
    void restAfter(int solves) {
        this.restAfterSolves = solves;
    }

    /** Never reach rest again. */
    void neverRest() {
        this.restAfterSolves = Integer.MAX_VALUE;
    }

    void failWith(@Nullable RuntimeException failure) {
        this.failure = failure;
    }

    /** The {@code deltaSeconds} of solve {@code index}, counting from zero. */
    float deltaAt(int index) {
        return Objects.requireNonNull(this.deltas.get(index));
    }

    /** The most recent {@code deltaSeconds}. */
    float lastDelta() {
        return deltaAt(this.deltas.size() - 1);
    }

    int deltaCount() {
        return this.deltas.size();
    }

    @NotNull List<PhysicsDriver> drivers() {
        return this.drivers;
    }

    int solveCount() {
        return this.solveCount;
    }

    /** Rest is driven by the script, so {@code reset} only has to clear the flag. */
    private static final class ScriptedState implements PhysicsState {

        private boolean atRest;

        @Override
        public void reset() {
            this.atRest = true;
        }

        @Override
        public boolean isAtRest() {
            return this.atRest;
        }
    }
}
