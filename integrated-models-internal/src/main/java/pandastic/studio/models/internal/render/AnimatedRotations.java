package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RotationAxis;

import java.util.Arrays;

/**
 * How far each animation-owned bone has been moved <em>by the model's own clip</em>, right now, relative
 * to the rest orientation the rigger authored — in the bone's own frame, in radians.
 *
 * <h2>What this is for</h2>
 * {@link RigBindPose} composes a rig's pose against the <em>authored rest</em> orientation of each bone.
 * That is exact for a bone nothing else drives and wrong for a bone a clip is driving, because the
 * renderer is simultaneously composing the same display against the animated orientation instead. Handed
 * one of these, {@code RigBindPose} composes against the animated orientation too, and the two writers
 * stop disagreeing. See {@link AnimationOwnership} for the full statement of the defect and the three
 * answers to it; this type carries the data the {@link AnimationOwnership#COMPOSE} answer needs.
 *
 * <h2>A delta, and therefore exactly zero when no clip is running</h2>
 * Deliberately not "the animated orientation" but "the animated orientation minus the authored one".
 * That is what makes the composition free of behaviour change: a bone whose clip is not playing has a
 * delta of exactly {@code (0, 0, 0)}, {@link #hasDelta(int)} answers {@code false}, and
 * {@code RigBindPose} evaluates the same expression it always did rather than multiplying in a
 * near-identity rotation and landing a float rounding away from where it used to.
 *
 * <p>The zero test is exact equality rather than an epsilon, and that is a structural fact about the
 * renderer rather than a tolerance worth configuring: a bone with no keyframe has its animated frame
 * <em>copied</em> from its authored frame, so the subtraction cancels bit for bit. An epsilon here would
 * be a tunable with no value an operator could want other than zero.
 *
 * <h2>Rotation only, and per bone only</h2>
 * Two limitations worth stating because neither is visible from the call site.
 *
 * <ul>
 *   <li><strong>Position keyframes are not carried.</strong> A pose is rotation-only by construction —
 *       see {@link pandastic.studio.models.api.pose.Pose} — so there is nowhere in the pipeline for a
 *       clip's translation of a bone to go. A bone whose clip moves it off its pivot therefore rotates
 *       with the clip and sits at its authored offset. That is why {@link AnimationOwnership#RENDERER}
 *       is the default: every walk cycle in this repository's models keyframes position on the torso.</li>
 *   <li><strong>The chain is the rig's, not the model's.</strong> A delta is applied in the owning
 *       bone's own frame and propagates to its <em>rig</em> descendants through the bind pose's
 *       hierarchy walk. A bone the clip animates that the rig does not name contributes nothing, so a
 *       rig that skips an intermediate animated bone carries its subtree rigidly past it.</li>
 * </ul>
 *
 * <h2>Ownership and threading</h2>
 * Mutable, allocated once per binding, and owned by the one sink that fills it. It is written by an
 * {@link AnimatedRotationSource} and read by {@link RigBindPose#compose} within a single emission pass,
 * under whatever lock that sink already holds. Nothing here is thread-safe and nothing needs to be.
 */
public final class AnimatedRotations {

    /**
     * The overlay of a rig in which no bone is animation-owned.
     *
     * <p>Zero-width rather than a rig-sized buffer of {@code false}, which is what makes
     * {@link #hasDelta(int)} index-tolerant: a caller may ask about any bone of any rig and get
     * {@code false}. Shared and immutable in effect — {@link #clear()} has nothing to clear and
     * {@link #set} cannot be reached, because no bone is owned.
     */
    public static final @NotNull AnimatedRotations NONE = new AnimatedRotations(new boolean[0]);

    private final boolean[] owned;
    private final boolean[] present;
    private final float[] radians;

    private AnimatedRotations(boolean[] owned) {
        this.owned = owned;
        this.present = new boolean[owned.length];
        this.radians = new float[owned.length * RotationAxis.COUNT];
    }

    /**
     * An overlay sized for {@code rig}, with the bones it declares animation-owned marked as such.
     *
     * <p>Answers {@link #NONE} for a rig that declares none, so the common case — a decorative rig, or
     * any rig of a model with no clips in it at all — allocates nothing and short-circuits every test
     * below. {@code krampus}, the one model in this repository with no animations, lands here.
     */
    public static @NotNull AnimatedRotations of(@NotNull Rig rig) {
        if (rig.animationOwnedBoneCount() == 0) {
            return NONE;
        }
        final boolean[] owned = new boolean[rig.boneCount()];
        for (final int bone : rig.animationOwnedBoneIndices()) {
            owned[bone] = true;
        }
        return new AnimatedRotations(owned);
    }

    /** How many bones this overlay covers. Zero for {@link #NONE}. */
    public int boneCount() {
        return this.owned.length;
    }

    /** Whether no bone at all is animation-owned, i.e. whether this overlay can ever carry a delta. */
    public boolean isEmpty() {
        return this.owned.length == 0;
    }

    /** Whether the rig declared that a clip keyframes {@code bone}. */
    public boolean isOwned(int bone) {
        return bone >= 0 && bone < this.owned.length && this.owned[bone];
    }

    /**
     * Forgets the previous pass's deltas.
     *
     * <p>Called before sampling, by the sink rather than by the source: a source that failed, that is
     * not installed, or that could not resolve a bone must leave the overlay saying "no delta" rather
     * than carrying the last successful sample forward, and the only way to guarantee that is for the
     * caller to clear first and the source to write only what it knows.
     */
    public void clear() {
        Arrays.fill(this.present, false);
        Arrays.fill(this.radians, 0F);
    }

    /**
     * Records {@code bone}'s current animation delta, in radians, in the bone's own frame.
     *
     * <p>A triple of exact zeroes is recorded as "no delta", which is the normal state of an
     * animation-owned bone whose clip is not currently playing — see the class note on why the test is
     * exact.
     *
     * @throws IllegalArgumentException if {@code bone} is not animation-owned. Not lenient, because a
     *                                 source writing an unowned bone is a source that resolved the wrong
     *                                 bone, and silently honouring it would overlay an animation onto a
     *                                 bone the rig promised nothing was animating
     */
    public void set(int bone, float pitchRadians, float yawRadians, float rollRadians) {
        if (!isOwned(bone)) {
            throw new IllegalArgumentException(
                    "Bone " + bone + " is not animation-owned, so it carries no animation delta;"
                            + " a source may only write the bones the rig declared");
        }
        if (!Float.isFinite(pitchRadians) || !Float.isFinite(yawRadians) || !Float.isFinite(rollRadians)) {
            // A non-finite delta would reach the client as a quaternion with a NaN in it, which draws
            // as a bone that vanishes or swallows the screen. Treated as "no delta", which leaves the
            // bone at the pose the rig composed rather than at nothing.
            this.present[bone] = false;
            return;
        }

        final int slot = bone * RotationAxis.COUNT;
        this.radians[slot] = pitchRadians;
        this.radians[slot + 1] = yawRadians;
        this.radians[slot + 2] = rollRadians;
        this.present[bone] = pitchRadians != 0F || yawRadians != 0F || rollRadians != 0F;
    }

    /** Whether {@code bone} is currently being moved by a clip. */
    public boolean hasDelta(int bone) {
        return bone >= 0 && bone < this.present.length && this.present[bone];
    }

    /** {@code bone}'s animation delta about X, in radians. Zero unless {@link #hasDelta(int)}. */
    public float pitch(int bone) {
        return this.radians[bone * RotationAxis.COUNT];
    }

    /** {@code bone}'s animation delta about Y, in radians. Zero unless {@link #hasDelta(int)}. */
    public float yaw(int bone) {
        return this.radians[bone * RotationAxis.COUNT + 1];
    }

    /** {@code bone}'s animation delta about Z, in radians. Zero unless {@link #hasDelta(int)}. */
    public float roll(int bone) {
        return this.radians[bone * RotationAxis.COUNT + 2];
    }

    /** How many bones currently carry a delta. A diagnostic, and what a test asserts on. */
    public int deltaCount() {
        int count = 0;
        for (final boolean carried : this.present) {
            if (carried) {
                count++;
            }
        }
        return count;
    }
}
