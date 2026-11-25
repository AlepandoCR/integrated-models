package pandastic.studio.models.bettermodel.render;

import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.AnimatedRotationSource;
import pandastic.studio.models.internal.render.AnimatedRotations;
import pandastic.studio.models.internal.render.AnimationDelta;
import pandastic.studio.models.internal.render.AnimationOwnership;

import java.util.Arrays;

/**
 * The BetterModel half of {@link AnimatedRotationSource}: reads the live rotation one of the model's own
 * clips has moved a bone to, and reports it as a delta from the bone's authored rest orientation.
 *
 * <h2>The accessor this is built on, and why it took finding</h2>
 * BetterModel accumulates a bone's animated transform into a {@code BoneMovement} held by a
 * {@code RenderedBone.BoneStateHandler}. Both the field and the inner class are <strong>private</strong>,
 * which is why {@code BetterModelSkeletons} reconstructs the rest transform from the blueprint rather than
 * reading it off a live bone, and why {@code CLAUDE.md} lists that reconstruction as unvalidated.
 *
 * <p>There is nevertheless exactly one public window onto the part of that state we need:
 *
 * <pre>
 *   RenderedBone#worldRotation()  →  state(null).worldRotation()
 *                                 →  lerp(before.rawRotation(), current.rawRotation(), progress)
 * </pre>
 *
 * <p>The name is misleading — nothing about it is world space. {@code BoneMovement#rawRotation} is
 * documented as "the local rotation of the bone in Euler angles (degrees)", and the renderer's own
 * parent-composition pass in {@code BoneStateHandler#after()} rewrites {@code position}, {@code scale}
 * and the {@code rotation} <em>quaternion</em> while leaving {@code rawRotation} untouched. So what comes
 * back is the bone's <strong>local</strong> orientation in degrees, interpolated between the previous and
 * the current keyframe by exactly the progress the renderer is itself showing — which is precisely the
 * value our pose has to be composed on top of.
 *
 * <p>Equally important, it is the <em>sum</em> of two things and not just the clip:
 * {@code AnimationKeyframe.ArrayProgress#animate} computes
 * {@code destRawRot = defaultFrame.rawRotation + keyframeRotation}, with {@code defaultFrame} the frame
 * {@code RendererGroup#create} built from the blueprint. The clip's own contribution is therefore the
 * difference against the authored rest rotation, which is public as
 * {@code RenderedBone#getGroup().getParent().rotation()}.
 *
 * <h2>What is reachable and what is not</h2>
 * <ul>
 *   <li><strong>Rotation: yes</strong>, by the route above.</li>
 *   <li><strong>Position and scale: no.</strong> {@code BoneMovement#position} and {@code #scale} have no
 *       public window at all. {@code RenderedBone#worldPosition()} exists but is the fully composed world
 *       point — parent chain, item offset, root group origin, model scale and the entity's own yaw and
 *       pitch all folded in — so it cannot be turned back into a local offset without inverting the whole
 *       pipeline. A clip that keyframes a bone's <em>position</em> is consequently not reproducible here,
 *       which is the limitation {@link AnimatedRotations} already documents and the reason
 *       {@link AnimationOwnership#RENDERER} is the default.</li>
 * </ul>
 *
 * <h2>The arithmetic is not here</h2>
 * {@link AnimationDelta} owns it, on the engine side of the backend seam, because the composition is the
 * part worth a test and the bone lookup is the part that needs a server. It also explains there why a
 * plain Euler subtraction is the wrong answer and by how much.
 *
 * <h2>Exactly zero when no clip is moving the bone</h2>
 * Two short-circuits, and <strong>both are required</strong> — the arithmetic underneath them is
 * <em>not</em> bit-exact at the identity, for the reason {@link AnimationDelta} measures. Together they
 * are what makes {@link AnimationOwnership#COMPOSE} unable to change a bone nothing is animating:
 *
 * <ol>
 *   <li>A bone with no animation ever started has {@code runningAnimation() == null} and is reported as
 *       zero without arithmetic. Note this is advisory in one direction only: the renderer nulls its
 *       keyframe but not its iterator when a clip ends, so it can keep answering non-null — which costs
 *       the arithmetic and nothing else, because the second test then catches it.</li>
 *   <li>A bone an animation covers but never keyframes has its animated frame <em>copied</em> from its
 *       default frame — {@code AnimationProgress.EmptyProgress#animate} is {@code dest.set(movement)} —
 *       so {@code live} equals {@code rest} bit for bit and {@link AnimationDelta#isRest} says so.</li>
 * </ol>
 *
 * <h2>Two known transients, both bounded and both documented rather than worked around</h2>
 * <ul>
 *   <li>{@code before} starts as the zero vector and is only copied from {@code current} on a tick that
 *       shifts a keyframe. A clip added on the same tick the tracker spawned can therefore be sampled
 *       once while {@code before} still reads zero, which yields a delta interpolated from the wrong
 *       origin. It lasts one keyframe at most and self-corrects; the alternative is reaching for state
 *       that is private.</li>
 *   <li>{@code runningAnimation()} can stay non-null after a clip has finished, because the renderer
 *       nulls its keyframe but not its iterator. That only ever costs the arithmetic above, which then
 *       reports the rest orientation it has converged back to.</li>
 * </ul>
 *
 * <h2>Threading and allocation</h2>
 * {@code worldRotation()} takes the bone's own {@code DuplexLock} read lock, so it is safe off the server
 * tick and it touches no entity, no world and no block — which is what makes this legal to call from the
 * simulation thread. It does allocate: the only public form returns a fresh {@code Vector3f} and there is
 * no destination overload, so one sample costs one vector per animated bone. At the emission cadence
 * rather than the simulation rate, which is why it is acceptable.
 */
public final class TrackerAnimatedRotations implements AnimatedRotationSource {

    private final int[] boneIndices;
    private final RenderedBone[] bones;

    /** The authored rest orientation per entry, in degrees, as the renderer's default frame holds it. */
    private final Vector3f[] restDegrees;

    /** {@code Q(rest)⁻¹} per entry, precomputed because the rest orientation never changes. */
    private final Quaternionf[] restInverse;

    /** Scratch, reached only from {@link #sample}, which has a single owner per binding. */
    private final Quaternionf live = new Quaternionf();
    private final Quaternionf delta = new Quaternionf();
    private final Vector3f euler = new Vector3f();

    private TrackerAnimatedRotations(
            int[] boneIndices,
            RenderedBone[] bones,
            Vector3f[] restDegrees,
            Quaternionf[] restInverse
    ) {
        this.boneIndices = boneIndices;
        this.bones = bones;
        this.restDegrees = restDegrees;
        this.restInverse = restInverse;
    }

    /**
     * A source reading the animation-owned bones {@code rig} declares off {@code tracker}.
     *
     * <p>Answers {@link AnimatedRotationSource#NONE} for a rig that declares none — which is every rig of
     * a model with no clips in it, {@code krampus} included — so the common case allocates nothing and
     * the emission path short-circuits on {@link AnimatedRotations#isEmpty()}.
     *
     * <p>A bone that does not resolve is <em>skipped</em> rather than refused. That is not leniency: the
     * sink's own bind resolves every bone of the rig and fails with the complete list of names, so by the
     * time this runs an unresolved bone is impossible — and if the two ever disagreed, the right outcome
     * is a bone with no animation delta rather than a binding that cannot open.
     *
     * @param rig     the rig whose {@code animation-owned} bones are wanted
     * @param tracker the live tracker to read them from
     */
    public static @NotNull AnimatedRotationSource of(@NotNull Rig rig, @NotNull Tracker tracker) {
        final int[] owned = rig.animationOwnedBoneIndices();
        if (owned.length == 0) {
            return AnimatedRotationSource.NONE;
        }

        final int[] indices = new int[owned.length];
        final RenderedBone[] bones = new RenderedBone[owned.length];
        final Vector3f[] restDegrees = new Vector3f[owned.length];
        final Quaternionf[] restInverse = new Quaternionf[owned.length];
        int resolved = 0;

        for (final int bone : owned) {
            // The same tag-stripped, case-insensitive match the sink binds with; see trap 2 in
            // TrackerPoseSink's class documentation for why a raw name lookup is wrong here.
            final RenderedBone rendered = tracker.bone(BonePredicate.name(rig.bone(bone).name()));
            if (rendered == null) {
                continue;
            }
            // Float3 is marked @ApiStatus.Internal, and there is no alternative: it is the only public
            // route to the authored rotation, and that rotation is literally the vector the renderer
            // stored as the bone's default frame — RendererGroup's constructor is
            // `this.rotation = group.rotation().toVector()` and it hands that field straight to
            // BoneMovement#rawRotation. toVector() copies, so nothing here aliases renderer state.
            final Vector3f rest = rendered.getGroup().getParent().rotation().toVector();
            indices[resolved] = bone;
            bones[resolved] = rendered;
            restDegrees[resolved] = rest;
            restInverse[resolved] = AnimationDelta.restInverse(rest, new Quaternionf());
            resolved++;
        }

        if (resolved == 0) {
            return AnimatedRotationSource.NONE;
        }
        return new TrackerAnimatedRotations(
                Arrays.copyOf(indices, resolved),
                Arrays.copyOf(bones, resolved),
                Arrays.copyOf(restDegrees, resolved),
                Arrays.copyOf(restInverse, resolved));
    }

    @Override
    public boolean sample(@NotNull AnimatedRotations dest) {
        boolean any = false;
        for (int entry = 0; entry < this.bones.length; entry++) {
            final RenderedBone bone = this.bones[entry];
            final int index = this.boneIndices[entry];

            if (bone.runningAnimation() == null) {
                // Nothing has ever driven this bone, so its animated frame is still the default one.
                // Left as "no delta" rather than written as zero: dest was cleared by the caller.
                continue;
            }

            // Allocates, unavoidably: this is the only public form and it has no destination overload.
            // One vector per animated bone per emission, at the cadence rather than the step rate.
            final Vector3f current = bone.worldRotation();
            if (AnimationDelta.isRest(this.restDegrees[entry], current)) {
                // A bone a clip covers but does not keyframe. Left as "no delta" rather than written as
                // zero: the caller cleared dest, so absence already says it.
                continue;
            }

            AnimationDelta.of(
                    this.restInverse[entry], current, this.live, this.delta, this.euler);
            dest.set(index, this.euler.x, this.euler.y, this.euler.z);
            any |= dest.hasDelta(index);
        }
        return any;
    }

    /** How many animation-owned bones this source resolved. A diagnostic, and what a test asserts on. */
    public int resolvedBoneCount() {
        return this.bones.length;
    }
}
