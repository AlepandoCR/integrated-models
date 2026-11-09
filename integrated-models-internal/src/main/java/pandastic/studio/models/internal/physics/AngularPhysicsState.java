package pandastic.studio.models.internal.physics;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBone;
import pandastic.studio.models.api.rig.RotationAxis;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat per-entry storage for {@link AngularPhysicsSolver}, plus everything derivable from the rig's
 * shape, computed once here so the solver's inner loop does no lookups.
 *
 * <h2>Indexed by physics entry, not by bone</h2>
 * A rig's simulated bones are usually a minority of its bones, so storing three floats per <em>bone</em>
 * would waste most of the arrays and, worse, scatter the hot data. Everything here is indexed by entry
 * {@code 0..entryCount}, with {@link #boneOf} mapping back to a pose index.
 *
 * <h2>Bound to one tuning at construction</h2>
 * The constructor stability-checks every profile against the step size the {@link PhysicsTuning} carries,
 * so a state is only valid for a solver built from that same snapshot. That is the reason tuning is
 * injected into the solver's constructor rather than passed per call: a reload has to rebuild both, and
 * making the state's validity structural beats documenting a rule a caller can forget. The only value
 * retained past construction is {@link PhysicsTuning#restEpsilonRadians()}, which {@link #isAtRest()}
 * needs; everything else is consumed here and then belongs to the solver.
 *
 * <h2>No allocation after construction</h2>
 * Every array is sized once and every scratch buffer is a field. The solver runs on a shared worker at
 * tens of hertz across every model on the server; allocating per step there is the difference between
 * invisible and a tenured-generation problem.
 */
final class AngularPhysicsState implements PhysicsState {

    /** Angular offset per entry per axis, radians. The thing the solver integrates. */
    final float[] angle;

    /** Angular velocity per entry per axis, radians per second. Always zero for {@link PhysicsKind#DRAG}. */
    final float[] velocity;

    /** The driver rotation seen last substep, so the next one can difference it. */
    final float[] parentPrevious;

    /** Per-entry per-axis lower bound, already in radians and already including a cloth cone. */
    final float[] limitMin;

    /** Per-entry per-axis upper bound. */
    final float[] limitMax;

    /** Pose bone index of each entry. */
    private final int[] bone;

    /** Pose bone indices whose rotations carry each entry. */
    private final int[][] ancestors;

    /** Entry index of the nearest simulated ancestor, or -1. Drives chains within a single substep. */
    private final int[] chainParent;

    /** The rig author's per-bone coefficients, per entry. */
    private final PhysicsProfile[] profiles;

    /** Scratch for one entry's drag torque. Reused; never escapes. */
    final float[] windScratch = new float[RotationAxis.COUNT];

    /** Leftover wall time not yet consumed by a substep. */
    float accumulator;

    /** Largest angular change observed in the last {@code solve}, for {@link #isAtRest()}. */
    private float lastMaxChange;

    private final int entryCount;

    /** Per-substep angular change below which a bone counts as having stopped moving. */
    private final float restEpsilonRadians;

    AngularPhysicsState(@NotNull Rig rig, @NotNull PhysicsTuning tuning) {
        this.restEpsilonRadians = tuning.restEpsilonRadians();

        final int[] entries = rig.physicsBoneIndices();
        this.entryCount = entries.length;

        final int width = this.entryCount * RotationAxis.COUNT;
        this.angle = new float[width];
        this.velocity = new float[width];
        this.parentPrevious = new float[width];
        this.limitMin = new float[width];
        this.limitMax = new float[width];
        this.bone = entries;
        this.ancestors = new int[this.entryCount][];
        this.chainParent = new int[this.entryCount];
        this.profiles = new PhysicsProfile[this.entryCount];

        // Entry index keyed by pose bone index, so a bone's nearest simulated ancestor is one walk up
        // the parent chain rather than a scan. -1 for bones that are not simulated.
        final int[] entryOfBone = new int[rig.boneCount()];
        java.util.Arrays.fill(entryOfBone, -1);
        for (int entry = 0; entry < this.entryCount; entry++) {
            entryOfBone[entries[entry]] = entry;
        }

        for (int entry = 0; entry < this.entryCount; entry++) {
            final int boneIndex = entries[entry];
            final RigBone rigBone = rig.bone(boneIndex);
            final PhysicsProfile profile = rigBone.physics();
            if (profile == null) {
                throw new IllegalStateException(
                        "Rig reported bone " + boneIndex + " as simulated but it carries no profile");
            }
            AngularPhysicsSolver.requireStable(tuning, profile, rig, rigBone);
            this.profiles[entry] = profile;

            final int slot = entry * RotationAxis.COUNT;
            for (int axis = 0; axis < RotationAxis.COUNT; axis++) {
                final RotationAxis rotationAxis = RotationAxis.at(axis);
                this.limitMin[slot + axis] = profile.limits().min(rotationAxis);
                this.limitMax[slot + axis] = profile.limits().max(rotationAxis);
            }

            final List<Integer> chain = new ArrayList<>();
            int walker = rigBone.parentIndex();
            int nearestSimulated = -1;
            while (walker != RigBone.NO_PARENT) {
                chain.add(walker);
                if (nearestSimulated < 0 && entryOfBone[walker] >= 0) {
                    nearestSimulated = entryOfBone[walker];
                }
                walker = rig.bone(walker).parentIndex();
            }
            final int[] ancestorIndices = new int[chain.size()];
            for (int i = 0; i < ancestorIndices.length; i++) {
                ancestorIndices[i] = chain.get(i);
            }
            this.ancestors[entry] = ancestorIndices;
            this.chainParent[entry] = nearestSimulated;
        }
    }

    /** How many bones this state simulates. */
    int entryCount() {
        return this.entryCount;
    }

    /** Pose bone index of {@code entry}. */
    int boneOf(int entry) {
        return this.bone[entry];
    }

    /** Pose bone indices carrying {@code entry}. */
    int[] ancestorsOf(int entry) {
        return this.ancestors[entry];
    }

    /** Entry index of {@code entry}'s nearest simulated ancestor, or -1. */
    int chainParentOf(int entry) {
        return this.chainParent[entry];
    }

    /** The rig author's coefficients for {@code entry}. */
    @NotNull PhysicsProfile profileOf(int entry) {
        return this.profiles[entry];
    }

    /** Records the largest angular change of a completed solve. */
    void recordMaxChange(float maxChange) {
        this.lastMaxChange = maxChange;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(this.angle, 0F);
        java.util.Arrays.fill(this.velocity, 0F);
        java.util.Arrays.fill(this.parentPrevious, 0F);
        this.accumulator = 0F;
        this.lastMaxChange = 0F;
    }

    @Override
    public boolean isAtRest() {
        return this.lastMaxChange < this.restEpsilonRadians;
    }
}
