package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.spi.ModelDefinition;
import pandastic.studio.models.internal.spi.ModelRenderBackends;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A model's authored skeleton as plain geometry: a list of {@link ModelBone} and the bind pose it yields.
 *
 * <h2>Where the geometry comes from</h2>
 * A rendering library composes a bone's display transform from its own blueprint hierarchy, and none of
 * that state is readable from a live bone. Reproducing the rest transform a renderer would send is
 * therefore renderer-specific work, and it lives behind {@link ModelDefinition#bones()} in the backend
 * module rather than here — see {@code integrated-models-bettermodel} for the BetterModel reproduction
 * and the three terms it is known to approximate.
 *
 * <p>This class is the holder on the near side of that seam. Everything downstream sees {@link ModelBone}
 * and never a renderer type, which is what makes the transform arithmetic testable without a server, and
 * the bind-pose composition in {@link RigBindPose} is shared by every backend rather than reimplemented
 * per renderer.
 *
 * <p>The model-scale multiplier is deliberately <em>not</em> folded into these bones: it is a property of
 * a live instance rather than of the model, so it enters at {@link #bindPose(Rig, float, RenderSettings)}.
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
        return ModelRenderBackends.require().definition(modelId)
                .map(definition -> of(definition.modelId(), definition.bones()));
    }

    /**
     * The skeleton of a model whose bones a backend has already read.
     *
     * <p>The factory a {@link pandastic.studio.models.internal.spi.ModelRenderBackend} hands its geometry
     * through, and the only way to build one: there is no constructor, because a skeleton assembled from
     * anywhere other than a renderer's own blueprint would silently disagree with the transforms the
     * renderer sends.
     *
     * @param modelId the id the renderer has the model registered under
     * @param bones   every bone's rest transform, a parent always before its children
     */
    public static @NotNull ModelSkeleton of(@NotNull String modelId, @NotNull List<ModelBone> bones) {
        return new ModelSkeleton(modelId, bones);
    }

    /**
     * Throws unless the model {@code rig} names exists and has every bone the rig does.
     *
     * <p>Call this at load time. A rig whose bone names no longer match the model is a content error,
     * and the only person who can fix it is the one looking at the log — so it has to be loud, and it
     * has to arrive before anything spawns.
     *
     * <p>Goes through {@link ModelDefinition#boneNames()} rather than {@link #of(String)}: names are the
     * cheap answer and the geometry is the expensive one, and this check never needs a transform.
     *
     * @param settings supplies how many model bone names a failure message may list
     * @throws IllegalStateException if the model is not registered, or any bone is missing or ambiguous
     */
    public static void requireBindable(@NotNull Rig rig, @NotNull RenderSettings settings) {
        final ModelDefinition definition = ModelRenderBackends.require().requireDefinition(rig);
        RigBoneResolution.resolve(rig, definition.boneNames()).requireBindable(settings);
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
}
