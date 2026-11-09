package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBone;

import java.util.ArrayList;
import java.util.List;

/**
 * A rig frozen against one model: every bone's rest transform, and the arithmetic that turns a
 * {@link Pose} into the absolute transforms a display entity wants.
 *
 * <h2>Why absolute, and why that forces a hierarchy walk</h2>
 * A display entity's transform is not relative to anything — the renderer composes a bone's parents into
 * it before sending it, and {@code DisplayTransformer#transform} writes the finished product. So a pose
 * cannot be handed over bone by bone: rotating a rig's root has to move and re-orient every bone under
 * it, or the model comes apart. This class does that composition once per emission, in rig order, which
 * {@link Rig} guarantees is topological.
 *
 * <h2>Deviation from rest, rather than a rebuild from scratch</h2>
 * For each bone it carries {@code D}, the rotation by which the bone currently deviates from its
 * authored rest orientation, in model space:
 *
 * <pre>
 *   absolute[i] = D[parent] · rest[i] · delta[i]
 *   D[i]        = absolute[i] · rest[i]⁻¹
 *   position[i] = position[parent] + D[parent] ⊗ (restPosition[i] − restPosition[parent])
 * </pre>
 *
 * with {@code D} the identity above a rig root. Two properties make this the right formulation. With a
 * zero pose every {@code D} is the identity and the output is <em>exactly</em> the rest transform — bit
 * for bit, so binding a rig can never nudge a model that nothing is driving. And every bone between two
 * rig bones is carried rigidly, which is correct precisely because the rig does not drive it: the
 * authored geometry of the gap is preserved instead of being approximated.
 *
 * <p>{@code delta[i]} is right-multiplied, so the pose rotates a bone in its <strong>own</strong> frame
 * — the renderer's local-rotation position. That is the frame the physics solver works in: it already
 * accumulates each ancestor's pose angle into its driver term, so its output is an offset from the
 * bone's own rest orientation and nothing else.
 *
 * <p>A bone's own rotation never moves its own position. That is {@link Pose}'s rotation-only contract
 * holding rather than a simplification: a bone that leaves its pivot detaches from its parent in the
 * renderer, which reads as a broken model rather than as physics.
 *
 * <h2>No allocation after construction</h2>
 * Every quaternion and vector this needs is allocated at bind time — one per bone for the rest data and
 * one per bone for the working data. {@link #compose} does arithmetic into those and nothing else, which
 * is what makes it legal to call at the simulation rate across every model on the server.
 */
public final class RigBindPose {

    private final @NotNull Rig rig;
    private final int[] parentIndices;
    private final int[] modelIndices;
    private final float modelScale;

    private final Quaternionf[] restRotation;
    private final Quaternionf[] restRotationInverse;
    private final Vector3f[] restPosition;
    private final Vector3f[] restScale;

    /** {@code restPosition[i] − restPosition[parent]}, the rigid segment a parent's rotation swings. */
    private final Vector3f[] segment;

    private final Quaternionf[] absoluteRotation;
    private final Quaternionf[] deviation;
    private final Vector3f[] absolutePosition;
    private final Quaternionf delta = new Quaternionf();

    private RigBindPose(
            @NotNull Rig rig,
            @NotNull List<ModelBone> modelBones,
            float modelScale,
            @NotNull RenderSettings settings
    ) {
        final RigBoneResolution resolution = RigBoneResolution.resolve(rig, namesOf(modelBones));
        resolution.requireBindable(settings);

        final int bones = rig.boneCount();
        this.rig = rig;
        this.modelScale = modelScale;
        this.parentIndices = new int[bones];
        this.modelIndices = new int[bones];
        this.restRotation = new Quaternionf[bones];
        this.restRotationInverse = new Quaternionf[bones];
        this.restPosition = new Vector3f[bones];
        this.restScale = new Vector3f[bones];
        this.segment = new Vector3f[bones];
        this.absoluteRotation = new Quaternionf[bones];
        this.deviation = new Quaternionf[bones];
        this.absolutePosition = new Vector3f[bones];

        for (int bone = 0; bone < bones; bone++) {
            final RigBone rigBone = rig.bone(bone);
            final ModelBone modelBone = modelBones.get(resolution.modelIndexOf(bone));

            this.parentIndices[bone] = rigBone.parentIndex();
            this.modelIndices[bone] = resolution.modelIndexOf(bone);
            this.restRotation[bone] = new Quaternionf(modelBone.restRotation());
            this.restRotationInverse[bone] = this.restRotation[bone].invert(new Quaternionf());
            this.restPosition[bone] = new Vector3f(modelBone.restPosition()).mul(modelScale);
            // The renderer clamps a display's scale at zero before sending it; a negative component is
            // a mirrored item rather than a small one, which is never what a scaler meant.
            this.restScale[bone] = new Vector3f(modelBone.restScale()).mul(modelScale).max(new Vector3f());

            this.segment[bone] = new Vector3f();
            if (!rigBone.isRoot()) {
                this.restPosition[bone].sub(this.restPosition[rigBone.parentIndex()], this.segment[bone]);
            }

            // Seeded with the rest transform so that reading this before the first compose reports
            // where the model already is, rather than the origin.
            this.absoluteRotation[bone] = new Quaternionf(this.restRotation[bone]);
            this.deviation[bone] = new Quaternionf();
            this.absolutePosition[bone] = new Vector3f(this.restPosition[bone]);
        }
    }

    /**
     * Binds {@code rig} to {@code modelBones}, scaling every length by {@code modelScale}.
     *
     * <p>Takes a {@link RenderSettings} snapshot rather than a source: binding happens once and the
     * only tunable it reads is how long the failure message may be. A caller whose configuration
     * reloads has nothing to recompute here — the geometry is the model's, not the operator's.
     *
     * @param modelBones the model's bones in hierarchy order, a parent always before its children
     * @param modelScale the renderer's model scale multiplier, which it applies to both position and
     *                   scale before sending them
     * @param settings   the snapshot whose diagnostic limit shapes a bind failure's message
     * @throws IllegalStateException    if any rig bone is missing from the model or ambiguous within it
     * @throws IllegalArgumentException if the model bone list is not in hierarchy order, or the scale is
     *                                 not a positive finite number
     */
    public static @NotNull RigBindPose of(
            @NotNull Rig rig,
            @NotNull List<ModelBone> modelBones,
            float modelScale,
            @NotNull RenderSettings settings
    ) {
        if (!(modelScale > 0F) || !Float.isFinite(modelScale)) {
            throw new IllegalArgumentException(
                    "Model scale must be a positive finite number, got " + modelScale);
        }
        for (int index = 0; index < modelBones.size(); index++) {
            final int parent = modelBones.get(index).parentIndex();
            if (parent >= index) {
                throw new IllegalArgumentException(
                        "Model bone '" + modelBones.get(index).name() + "' at index " + index
                                + " is parented to index " + parent + ", which does not precede it;"
                                + " the bone list must be in hierarchy order");
            }
        }
        return new RigBindPose(rig, modelBones, modelScale, settings);
    }

    /** The rig this is bound to. */
    public @NotNull Rig getRig() {
        return this.rig;
    }

    /** How many bones, i.e. the pose width {@link #compose} expects. */
    public int boneCount() {
        return this.parentIndices.length;
    }

    /** The renderer's model scale multiplier that was folded into the rest data. */
    public float getModelScale() {
        return this.modelScale;
    }

    /** The index within the model's bone list that rig bone {@code bone} resolved to. */
    public int modelIndexOf(int bone) {
        return this.modelIndices[bone];
    }

    /**
     * Recomputes every bone's absolute transform from {@code pose}.
     *
     * <p>Reads the pose and nothing else, so it is safe to call wherever the pose is owned. Results are
     * read back through {@link #rotation}, {@link #position} and {@link #scale} and remain valid until
     * the next call.
     *
     * @throws IllegalArgumentException if the pose was built for a different bone count
     */
    public void compose(@NotNull Pose pose) {
        if (pose.boneCount() != boneCount()) {
            throw new IllegalArgumentException(
                    "Pose holds " + pose.boneCount() + " bones but rig '" + this.rig.getName()
                            + "' has " + boneCount());
        }

        for (int bone = 0; bone < this.parentIndices.length; bone++) {
            final int parent = this.parentIndices[bone];
            PoseQuaternions.fromPose(pose, bone, this.delta);

            if (parent < 0) {
                this.absoluteRotation[bone].set(this.restRotation[bone]).mul(this.delta);
                this.absolutePosition[bone].set(this.restPosition[bone]);
            } else {
                this.absoluteRotation[bone]
                        .set(this.deviation[parent])
                        .mul(this.restRotation[bone])
                        .mul(this.delta);
                this.deviation[parent].transform(this.segment[bone], this.absolutePosition[bone]);
                this.absolutePosition[bone].add(this.absolutePosition[parent]);
            }

            this.deviation[bone].set(this.absoluteRotation[bone]).mul(this.restRotationInverse[bone]);
        }
    }

    /** The absolute rotation last composed for {@code bone}, written into {@code dest}. */
    public @NotNull Quaternionf rotation(int bone, @NotNull Quaternionf dest) {
        return dest.set(this.absoluteRotation[bone]);
    }

    /** The absolute position last composed for {@code bone}, written into {@code dest}. */
    public @NotNull Vector3f position(int bone, @NotNull Vector3f dest) {
        return dest.set(this.absolutePosition[bone]);
    }

    /**
     * {@code bone}'s display scale, written into {@code dest}.
     *
     * <p>Constant: a pose carries no scale, so this never changes after binding. It is still read every
     * emission because the renderer's transform call takes all three components together.
     */
    public @NotNull Vector3f scale(int bone, @NotNull Vector3f dest) {
        return dest.set(this.restScale[bone]);
    }

    /** {@code bone}'s authored rest rotation, written into {@code dest}. */
    public @NotNull Quaternionf restRotation(int bone, @NotNull Quaternionf dest) {
        return dest.set(this.restRotation[bone]);
    }

    /** {@code bone}'s authored rest position, written into {@code dest}. */
    public @NotNull Vector3f restPosition(int bone, @NotNull Vector3f dest) {
        return dest.set(this.restPosition[bone]);
    }

    private static @NotNull List<String> namesOf(@NotNull List<ModelBone> modelBones) {
        final List<String> names = new ArrayList<>(modelBones.size());
        for (final ModelBone bone : modelBones) {
            names.add(bone.name());
        }
        return names;
    }
}
