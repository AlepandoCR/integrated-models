package pandastic.studio.models.api.pose;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.RotationAxis;

/**
 * A full set of bone rotations for one rig, as a flat {@code float[]} of
 * {@code boneCount * }{@link RotationAxis#COUNT} radians laid out
 * {@code [pitch0, yaw0, roll0, pitch1, ...]}.
 *
 * <h2>Rotation only, and that is a contract</h2>
 * A pose carries <strong>no positions</strong>, and no solver may move a bone off its pivot. Two
 * reasons, in order of weight. A chain solved in world space produces positions that then have to be
 * converted back into rotations which keep every bone attached — so the rotation is the real output
 * either way, and solving for it directly removes a lossy round trip. And a bone that leaves its pivot
 * detaches from its parent in the renderer, which looks like a broken model rather than like physics.
 * There is deliberately no positional accessor on this type; adding one would silently make the
 * invariant unenforceable.
 *
 * <h2>Mutable, reused, single-owner</h2>
 * Poses are mutated in place every step and are <strong>not</strong> thread-safe. One pose belongs to
 * one simulation, and the simulation thread owns it. Publishing a pose to a renderer means copying the
 * values out under whatever barrier the renderer seam defines — never handing this object across a
 * thread boundary.
 */
public final class Pose {

    private final float[] rotations;
    private final int boneCount;

    /** A pose sized for {@code boneCount} bones, every rotation zero. */
    public Pose(int boneCount) {
        if (boneCount <= 0) {
            throw new IllegalArgumentException("A pose needs at least one bone, got " + boneCount);
        }
        this.boneCount = boneCount;
        this.rotations = new float[boneCount * RotationAxis.COUNT];
    }

    /** How many bones this pose holds. */
    public int boneCount() {
        return this.boneCount;
    }

    /** The rotation of {@code bone} about {@code axis}, in radians. */
    public float get(int bone, @NotNull RotationAxis axis) {
        return this.rotations[slot(bone) + axis.ordinal()];
    }

    /** The pitch of {@code bone}, in radians. */
    public float pitch(int bone) {
        return this.rotations[slot(bone)];
    }

    /** The yaw of {@code bone}, in radians. */
    public float yaw(int bone) {
        return this.rotations[slot(bone) + 1];
    }

    /** The roll of {@code bone}, in radians. */
    public float roll(int bone) {
        return this.rotations[slot(bone) + 2];
    }

    /** Replaces the rotation of {@code bone} about {@code axis}. */
    public void set(int bone, @NotNull RotationAxis axis, float radians) {
        this.rotations[slot(bone) + axis.ordinal()] = radians;
    }

    /** Replaces all three rotations of {@code bone}. */
    public void set(int bone, float pitch, float yaw, float roll) {
        final int slot = slot(bone);
        this.rotations[slot] = pitch;
        this.rotations[slot + 1] = yaw;
        this.rotations[slot + 2] = roll;
    }

    /** Adds to the rotation of {@code bone} about {@code axis}. */
    public void add(int bone, @NotNull RotationAxis axis, float radians) {
        this.rotations[slot(bone) + axis.ordinal()] += radians;
    }

    /** Adds to all three rotations of {@code bone}. */
    public void add(int bone, float pitch, float yaw, float roll) {
        final int slot = slot(bone);
        this.rotations[slot] += pitch;
        this.rotations[slot + 1] += yaw;
        this.rotations[slot + 2] += roll;
    }

    /** Zeroes every rotation, returning the rig to its authored rest pose. */
    public void reset() {
        java.util.Arrays.fill(this.rotations, 0F);
    }

    /** Copies {@code other}'s rotations into this pose. */
    public void copyFrom(@NotNull Pose other) {
        if (other.boneCount != this.boneCount) {
            throw new IllegalArgumentException(
                    "Pose width mismatch: this holds " + this.boneCount + " bones, other holds "
                            + other.boneCount);
        }
        System.arraycopy(other.rotations, 0, this.rotations, 0, this.rotations.length);
    }

    /**
     * Whether every rotation is finite.
     *
     * <p>A diverged integrator produces infinities and NaN, and a NaN rotation reaches the client as a
     * bone that vanishes or fills the screen. Cheap enough to assert in tests and at seams.
     */
    public boolean isFinite() {
        for (final float rotation : this.rotations) {
            if (!Float.isFinite(rotation)) {
                return false;
            }
        }
        return true;
    }

    private int slot(int bone) {
        if (bone < 0 || bone >= this.boneCount) {
            throw new IndexOutOfBoundsException(
                    "Bone " + bone + " is outside this pose's 0.." + (this.boneCount - 1) + " range");
        }
        return bone * RotationAxis.COUNT;
    }
}
