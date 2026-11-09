package pandastic.studio.models.internal.render;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.data.renderer.RendererGroup;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a model's authored skeleton out of BetterModel once, and hands it on as plain geometry.
 *
 * <h2>The only class that knows the renderer's transform pipeline</h2>
 * BetterModel composes a bone's display transform from its blueprint hierarchy — origins differenced
 * against the parent's, rotated by the parent's accumulated rotation, offset by the bone's item
 * transform and finally multiplied by the tracker's model scale. None of that state is readable from a
 * live bone: {@code RenderedBone}'s accumulated {@code BoneMovement} is private and its parent link is
 * package-private. What <em>is</em> public is the static blueprint the bones were built from, so this
 * class walks that instead and reproduces the rest transform the renderer would send.
 *
 * <p>Everything downstream therefore sees {@link ModelBone} and never a renderer type, which is what
 * makes the transform arithmetic testable without a server.
 *
 * <h2>What it reproduces, and what it does not</h2>
 * At rest, with no animation playing, the display transform BetterModel sends is
 *
 * <pre>
 *   position = (itemOffset ⊗ rotation + modelPosition + rootPosition) · modelScale + itemPosition
 *   scale    = itemScale · modelScale
 *   rotation = Π rest rotations down the hierarchy
 * </pre>
 *
 * which is what this walk computes, with {@code modelScale} left to {@link RigBindPose} because it is a
 * property of a tracker rather than of the model. Three terms are known to be approximated, all of them
 * zero for an ordinary untagged bone — which is the only kind a physics rig should name:
 *
 * <ul>
 *   <li>{@code itemOffset} is folded in rotated by the <em>rest</em> rotation, so a bone carrying a
 *       non-zero item offset drifts by a fraction of that offset while it rotates.</li>
 *   <li>{@code itemPosition} is scaled by the model scale here and is not by the renderer, so a
 *       non-unit-scaled model with a tag-mapped item transform is off by {@code (scale − 1)} of it.</li>
 *   <li>A bone's item stack can be swapped at runtime (tint, item mappers); the scale captured here is
 *       the authored one.</li>
 * </ul>
 *
 * <h2>Also the load-time validator</h2>
 * {@link #requireBindable(Rig, RenderSettings)} answers "does this rig name bones this model has"
 * without spawning anything, so a mistyped or renamed bone is a startup error rather than something a
 * player discovers.
 */
public final class ModelSkeleton {

    private final @NotNull String modelId;
    private final @NotNull List<ModelBone> bones;
    private final @NotNull List<String> boneNames;

    private ModelSkeleton(@NotNull String modelId, @NotNull List<ModelBone> bones) {
        this.modelId = modelId;
        this.bones = List.copyOf(bones);
        final List<String> names = new ArrayList<>(bones.size());
        for (final ModelBone bone : bones) {
            names.add(bone.name());
        }
        this.boneNames = List.copyOf(names);
    }

    /** The skeleton of the model registered as {@code modelId}, or empty if the renderer has no such model. */
    public static @NotNull Optional<ModelSkeleton> of(@NotNull String modelId) {
        final ModelRenderer renderer = BetterModel.modelOrNull(modelId);
        return renderer == null ? Optional.empty() : Optional.of(of(renderer));
    }

    /** The skeleton of {@code renderer}. */
    public static @NotNull ModelSkeleton of(@NotNull ModelRenderer renderer) {
        final List<ModelBone> bones = new ArrayList<>();
        for (final RendererGroup root : renderer.rendererGroups().values()) {
            // A tree root's accumulated position starts at zero — the renderer adds the root group's
            // own position back at the end of the transform, so it enters as rootPosition instead.
            final Vector3f rootPosition = new Vector3f(root.getPosition());
            collect(root, ModelBone.NO_PARENT, new Vector3f(), rootPosition, new Quaternionf(),
                    rootPosition, bones);
        }
        return new ModelSkeleton(renderer.name(), bones);
    }

    /**
     * Every bone name in {@code renderer}, bare and in no particular order.
     *
     * <p>The cheap path for validation: {@code flatten()} walks the hierarchy without computing any
     * geometry, so a loader can check a rig against a model it will never spawn.
     */
    public static @NotNull List<String> boneNamesOf(@NotNull ModelRenderer renderer) {
        return renderer.flatten().map(group -> group.name().name()).toList();
    }

    /**
     * Throws unless the model {@code rig} names exists and has every bone the rig does.
     *
     * <p>Call this at load time. A rig whose bone names no longer match the model is a content error,
     * and the only person who can fix it is the one looking at the log — so it has to be loud, and it
     * has to arrive before anything spawns.
     *
     * @param settings supplies how many model bone names a failure message may list
     * @throws IllegalStateException if the model is not registered, or any bone is missing or ambiguous
     */
    public static void requireBindable(@NotNull Rig rig, @NotNull RenderSettings settings) {
        final ModelRenderer renderer = BetterModel.modelOrNull(rig.getModelId());
        if (renderer == null) {
            throw new IllegalStateException(
                    "Rig '" + rig.getName() + "' points at model '" + rig.getModelId()
                            + "', which BetterModel has not loaded. Check the model name and that its"
                            + " file is in BetterModel's model folder.");
        }
        RigBoneResolution.resolve(rig, boneNamesOf(renderer)).requireBindable(settings);
    }

    /** The model this skeleton was read from. */
    public @NotNull String getModelId() {
        return this.modelId;
    }

    /** Every bone, a parent always before its children. */
    public @NotNull List<ModelBone> bones() {
        return this.bones;
    }

    /** Every bone's bare name, in the same order as {@link #bones()}. */
    public @NotNull List<String> boneNames() {
        return this.boneNames;
    }

    /**
     * Binds {@code rig} against this skeleton.
     *
     * @param modelScale the tracker's model scale multiplier
     * @param settings   the snapshot whose diagnostic limit shapes a bind failure's message
     * @throws IllegalStateException if any rig bone is missing from this model or ambiguous within it
     */
    public @NotNull RigBindPose bindPose(
            @NotNull Rig rig,
            float modelScale,
            @NotNull RenderSettings settings
    ) {
        return RigBindPose.of(rig, this.bones, modelScale, settings);
    }

    /**
     * Appends {@code group} and everything below it, depth first so a parent always precedes its children.
     *
     * @param parentModelPosition the parent's accumulated position, zero at a tree root
     * @param parentGroupPosition the parent's authored origin, which the child's origin is differenced
     *                            against exactly as the renderer does
     * @param parentRotation      the parent's absolute rest rotation, identity at a tree root
     * @param rootPosition        the origin of the tree this group belongs to
     */
    private static void collect(
            @NotNull RendererGroup group,
            int parentIndex,
            @NotNull Vector3f parentModelPosition,
            @NotNull Vector3f parentGroupPosition,
            @NotNull Quaternionf parentRotation,
            @NotNull Vector3f rootPosition,
            @NotNull List<ModelBone> out
    ) {
        // Live internal state: RendererGroup#getPosition hands out the field itself. Every expression
        // below writes into a fresh destination rather than into it.
        final Vector3f groupPosition = group.getPosition();

        // Authored rotations are degrees in ZYX order; Float3#toQuaternionZYX is the same composition
        // the renderer's own MathUtil#toQuaternion performs when it builds a bone's default frame.
        final Quaternionf rotation =
                new Quaternionf(parentRotation).mul(group.getParent().rotation().toQuaternionZYX());

        final Vector3f modelPosition = parentIndex == ModelBone.NO_PARENT
                ? new Vector3f()
                : groupPosition.sub(parentGroupPosition, new Vector3f())
                        .rotate(parentRotation)
                        .add(parentModelPosition);

        // Already a copy; RendererGroup#getItemStack copies before returning.
        final TransformedItemStack item = group.getItemStack();
        final Vector3f restPosition = new Vector3f(item.offset())
                .rotate(rotation)
                .add(modelPosition)
                .add(rootPosition)
                .add(item.position());

        out.add(new ModelBone(group.name().name(), parentIndex, restPosition, rotation, item.scale()));
        final int index = out.size() - 1;

        for (final RendererGroup child : group.getChildren().values()) {
            collect(child, index, modelPosition, groupPosition, rotation, rootPosition, out);
        }
    }
}
