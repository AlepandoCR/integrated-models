package pandastic.studio.models.internal.rig;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bone.BoneItemMapper;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.data.renderer.RendererGroup;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The bones a model has, their nesting, and which of them can be driven — the whole of what
 * {@link RigFactory} needs to derive a rig, and nothing else.
 *
 * <h2>Why this is a seam</h2>
 * BetterModel is {@code compileOnly}, so there is no renderer at test time and a {@code ModelRenderer}
 * is not constructible off a live server. A derivation written against one would be untestable by
 * construction — which is the same reason {@code ModelBone} exists one layer over, and the same split
 * {@link pandastic.studio.models.internal.binding.RigBindValidator} makes. So every decision lives in
 * {@link RigFactory} against this interface, and {@link #renderer()} is thirty lines with no decision in
 * it.
 *
 * <h2>The order is part of the contract</h2>
 * A parent always precedes its children. {@link ModelRenderer#flatten()} happens to satisfy that today,
 * being depth first, but it does not promise to and the derivation's quality depends on it: "the first
 * acceptable bone" is only "the most root-ward acceptable bone" while the order holds. So
 * {@link #renderer()} walks {@code getChildren()} itself — the same walk
 * {@code ModelSkeleton.collect} performs, and deliberately not a call to it. {@code ModelSkeleton}
 * reconstructs every bone's rest transform, which is three joml copies and a quaternion composition per
 * bone, and does not carry the one bit a derivation actually needs; paying a 256-bone model's bind
 * geometry to answer a question about names would be the wrong trade in both directions.
 */
@FunctionalInterface
public interface ModelBoneSource {

    /**
     * The bones of the model registered as {@code modelId}, a parent always before its children.
     *
     * @return the bones, or empty if there is no such model — which is a fact about what the operator
     *         installed, not about the caller, and is why it is not an exception
     */
    @NotNull Optional<List<ModelBoneLink>> bonesOf(@NotNull String modelId);

    /** The source that reads BetterModel's loaded model definitions. */
    static @NotNull ModelBoneSource renderer() {
        return modelId -> {
            final ModelRenderer renderer = BetterModel.modelOrNull(modelId);
            if (renderer == null) {
                return Optional.empty();
            }
            final List<ModelBoneLink> bones = new ArrayList<>();
            for (final RendererGroup root : renderer.rendererGroups().values()) {
                collect(root, ModelBoneLink.NO_PARENT, bones);
            }
            return Optional.of(List.copyOf(bones));
        };
    }

    /**
     * Appends {@code group} and everything below it, depth first so a parent always precedes its
     * children.
     */
    private static void collect(
            @NotNull RendererGroup group,
            int parentIndex,
            @NotNull List<ModelBoneLink> out
    ) {
        // BoneName#name is the bare name with behavioural tag prefixes stripped, which is the identity a
        // rig keys on - see Rig#normalise for why a rig must never key on the raw identifier.
        out.add(new ModelBoneLink(group.name().name(), parentIndex, hasDisplay(group)));
        final int index = out.size() - 1;
        for (final RendererGroup child : group.getChildren().values()) {
            collect(child, index, out);
        }
    }

    /**
     * Whether the renderer will create a display for {@code group}.
     *
     * <p>Reproduced from {@code RenderedBone}'s own {@code dummyBone} test, which is the condition under
     * which it leaves its display null: an air item and no item mapper to replace it with anything. It is
     * reproduced rather than asked because the answer is only reachable through a bone of a live tracker,
     * and the entire point of a derivation is to happen before anything spawns.
     *
     * <p>Being wrong about it is cheap in both directions, which is what makes reproducing it acceptable:
     * a bone wrongly treated as undrivable is passed over for the next one, and a bone wrongly treated as
     * drivable fails the bind with the message that already explains exactly this ("a bone with no
     * display is a group with no cubes in it").
     */
    private static boolean hasDisplay(@NotNull RendererGroup group) {
        return !group.getItemStack().isAir() || group.getItemMapper() != BoneItemMapper.EMPTY;
    }
}
