package pandastic.studio.models.bettermodel.model;

import kr.toxicity.model.api.bone.BoneItemMapper;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.data.renderer.RendererGroup;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pandastic.studio.models.internal.render.ModelBone;
import pandastic.studio.models.internal.rig.ModelBoneLink;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a model's authored skeleton out of BetterModel and hands it on as plain geometry.
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
 * makes the transform arithmetic testable without a server — and, since the module split, what makes it
 * shared by every backend rather than reimplemented per renderer.
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
 * which is what {@link #bonesOf(ModelRenderer)} computes, with {@code modelScale} left to
 * {@code RigBindPose} because it is a property of a tracker rather than of the model. Three terms are
 * known to be approximated, all of them zero for an ordinary untagged bone — which is the only kind a
 * physics rig should name:
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
 * <h2>Nothing here is tunable</h2>
 * Every number below comes out of the model file. There is no coefficient, bound or epsilon to configure:
 * the walk reproduces a formula the renderer owns, and a key that let an operator change it would only
 * let them disagree with the packets BetterModel is sending.
 */
public final class BetterModelSkeletons {

    private BetterModelSkeletons() {
        throw new AssertionError("No instances.");
    }

    /**
     * Every bone of {@code renderer} with its rest transform, a parent always before its children.
     *
     * <p>The expensive walk. Reached through {@code ModelDefinition#bones()}, and directly by
     * {@code TrackerPoseSink.bind}, which has the live tracker's own renderer in hand and must bind
     * against that rather than against whatever is registered under the same id.
     */
    public static @NotNull List<ModelBone> bonesOf(@NotNull ModelRenderer renderer) {
        final List<ModelBone> bones = new ArrayList<>();
        for (final RendererGroup root : renderer.rendererGroups().values()) {
            // A tree root's accumulated position starts at zero — the renderer adds the root group's
            // own position back at the end of the transform, so it enters as rootPosition instead.
            final Vector3f rootPosition = new Vector3f(root.getPosition());
            collectBones(root, ModelBone.NO_PARENT, new Vector3f(), rootPosition, new Quaternionf(),
                    rootPosition, bones);
        }
        return List.copyOf(bones);
    }

    /**
     * Every bone of {@code renderer} with its parent and whether it will get a display, a parent always
     * before its children.
     *
     * <p>The cheap walk, and deliberately a walk of {@code getChildren()} rather than a call to
     * {@link ModelRenderer#flatten()}: flatten happens to be depth first today but does not promise to
     * stay so, and a rig derivation's "the first acceptable bone" is only "the most root-ward acceptable
     * bone" while the order holds.
     */
    public static @NotNull List<ModelBoneLink> boneLinksOf(@NotNull ModelRenderer renderer) {
        final List<ModelBoneLink> bones = new ArrayList<>();
        for (final RendererGroup root : renderer.rendererGroups().values()) {
            collectLinks(root, ModelBoneLink.NO_PARENT, bones);
        }
        return List.copyOf(bones);
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
    private static void collectBones(
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
            collectBones(child, index, modelPosition, groupPosition, rotation, rootPosition, out);
        }
    }

    /**
     * Appends {@code group} and everything below it, depth first so a parent always precedes its
     * children.
     */
    private static void collectLinks(
            @NotNull RendererGroup group,
            int parentIndex,
            @NotNull List<ModelBoneLink> out
    ) {
        // BoneName#name is the bare name with behavioural tag prefixes stripped, which is the identity a
        // rig keys on - see Rig#normalise for why a rig must never key on the raw identifier.
        out.add(new ModelBoneLink(group.name().name(), parentIndex, hasDisplay(group)));
        final int index = out.size() - 1;
        for (final RendererGroup child : group.getChildren().values()) {
            collectLinks(child, index, out);
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
