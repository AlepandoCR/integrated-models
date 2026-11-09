package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RotationAxis;

/**
 * Decides which bones are worth putting on the wire, by remembering what was last emitted for each.
 *
 * <h2>This is the bandwidth lever</h2>
 * A resting rig is the common case: a cloak hangs still, hair hangs still, and the simulation keeps
 * producing the same angles for it forever. Without this check every emission writes every bone, so a
 * decorative 40-bone model costs 800 display transforms a second per viewer for motion nobody can see.
 * With it, a settled rig costs nothing at all and the cost of a model scales with how much of it is
 * actually moving. That is the difference between a feature that ships and one that gets removed the
 * first time somebody puts fifty of these in a lobby.
 *
 * <h2>A bone is dirty if any rig ancestor is dirty</h2>
 * A display's transform is <strong>absolute</strong>: when a parent rotates, its children's absolute
 * rotations and positions change even though their own angles did not. Comparing only a bone's own
 * triple would therefore leave a whole chain behind whenever its root turned — a chain that visibly
 * detaches and then snaps back the next time its own angles happen to move. So the decision is
 * {@code own changed || parent was admitted}, which is also why {@link #admit} must be called for every
 * bone in ascending index order: {@link Rig} guarantees a parent precedes its children, and this class
 * leans on that to resolve the chain in one pass.
 *
 * <h2>The baseline is what was emitted, not what was last seen</h2>
 * A rejected bone does not update the baseline. Sub-epsilon changes therefore accumulate until they
 * cross it rather than being discarded one at a time, so a bone drifting slowly still arrives — just
 * late — instead of never moving at all.
 *
 * <h2>The epsilon is injected, and there is no default</h2>
 * It is the bandwidth lever, so it is configuration: see {@link RenderSettings#dirtyEpsilonRadians()}
 * for why the right value depends on model scale rather than on anything this class can know. A default
 * here would be a hardcoded tunable, and worse, one that silently shadowed a configured value whenever a
 * call site forgot to pass it — so the only way to get a tracker is to say what threshold it uses.
 *
 * <p>It is fixed for the lifetime of a tracker, and that is load-bearing rather than an omission: a
 * threshold that could change between two {@link #admit} calls of the same pass could admit a parent and
 * reject its child. A caller whose configuration reloaded builds a new tracker — which also forgets
 * every baseline, so the rig is re-emitted once against the new threshold instead of carrying decisions
 * made under the old one.
 */
public final class BoneDirtyTracker {

    private final int[] parentIndices;
    private final float[] emitted;
    private final boolean[] everEmitted;
    private final boolean[] admitted;
    private final float epsilon;

    /**
     * A tracker for a rig whose bone {@code i} has parent {@code parentIndices[i]}, or a negative value
     * if it is a root.
     *
     * @throws IllegalArgumentException if a bone's parent does not precede it, which would make the
     *                                 single-pass ancestor propagation read a stale decision
     */
    public BoneDirtyTracker(int[] parentIndices, float epsilonRadians) {
        if (parentIndices.length == 0) {
            throw new IllegalArgumentException("A dirty tracker needs at least one bone");
        }
        if (!(epsilonRadians >= 0F) || !Float.isFinite(epsilonRadians)) {
            throw new IllegalArgumentException("Epsilon must be finite and non-negative, got " + epsilonRadians);
        }
        for (int bone = 0; bone < parentIndices.length; bone++) {
            if (parentIndices[bone] >= bone) {
                throw new IllegalArgumentException(
                        "Bone " + bone + " is parented to " + parentIndices[bone]
                                + ", which does not precede it; the bone list must be topological");
            }
        }
        this.parentIndices = parentIndices.clone();
        this.emitted = new float[parentIndices.length * RotationAxis.COUNT];
        this.everEmitted = new boolean[parentIndices.length];
        this.admitted = new boolean[parentIndices.length];
        this.epsilon = epsilonRadians;
    }

    /** A tracker over {@code rig}'s hierarchy at the threshold {@code settings} configures. */
    public static @NotNull BoneDirtyTracker of(@NotNull Rig rig, @NotNull RenderSettings settings) {
        return of(rig, settings.dirtyEpsilonRadians());
    }

    /** A tracker over {@code rig}'s hierarchy at a caller-chosen epsilon. */
    public static @NotNull BoneDirtyTracker of(@NotNull Rig rig, float epsilonRadians) {
        final int[] parents = new int[rig.boneCount()];
        for (int bone = 0; bone < parents.length; bone++) {
            parents[bone] = rig.bone(bone).parentIndex();
        }
        return new BoneDirtyTracker(parents, epsilonRadians);
    }

    /** How many bones this tracker covers. */
    public int boneCount() {
        return this.everEmitted.length;
    }

    /** The angular change this tracker treats as movement, in radians. */
    public float epsilonRadians() {
        return this.epsilon;
    }

    /**
     * Whether {@code bone} should be emitted this pass, recording its angles if so.
     *
     * <p>Must be called exactly once per bone per emission, in ascending index order — the ancestor
     * propagation reads the decision already made for this bone's parent.
     *
     * @return {@code true} if the bone moved beyond the epsilon or an ancestor did
     */
    public boolean admit(int bone, float pitch, float yaw, float roll) {
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw) || !Float.isFinite(roll)) {
            // A diverged integrator produces infinities and then NaN, and a NaN quaternion reaches the
            // client as a bone that vanishes or swallows the screen. Freezing the bone at its last good
            // transform — and not poisoning the baseline with the bad value — degrades into a stuck
            // bone instead of a broken model, and leaves the pose recoverable once the solver settles.
            this.admitted[bone] = false;
            return false;
        }

        final int slot = bone * RotationAxis.COUNT;
        final boolean ownChanged = !this.everEmitted[bone]
                || Math.abs(pitch - this.emitted[slot]) > this.epsilon
                || Math.abs(yaw - this.emitted[slot + 1]) > this.epsilon
                || Math.abs(roll - this.emitted[slot + 2]) > this.epsilon;

        final int parent = this.parentIndices[bone];
        final boolean dirty = ownChanged || (parent >= 0 && this.admitted[parent]);

        if (ownChanged) {
            this.emitted[slot] = pitch;
            this.emitted[slot + 1] = yaw;
            this.emitted[slot + 2] = roll;
            this.everEmitted[bone] = true;
        }
        this.admitted[bone] = dirty;
        return dirty;
    }

    /**
     * Forgets every baseline, so the next pass emits the whole rig.
     *
     * <p>The right thing to do whenever the client's idea of the model may have been thrown away —
     * a respawned display, a viewer who just came into range — because the baseline describes what the
     * client has, not what the simulation produced.
     */
    public void reset() {
        java.util.Arrays.fill(this.emitted, 0F);
        java.util.Arrays.fill(this.everEmitted, false);
        java.util.Arrays.fill(this.admitted, false);
    }
}
