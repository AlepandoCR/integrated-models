package pandastic.studio.models.bettermodel.model;

import kr.toxicity.model.api.data.renderer.ModelRenderer;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.render.ModelBone;
import pandastic.studio.models.internal.rig.ModelBoneLink;
import pandastic.studio.models.internal.spi.ModelDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * One of BetterModel's loaded models, as a {@link ModelDefinition}.
 *
 * <h2>A view, not a snapshot</h2>
 * Holds the {@code ModelRenderer} and nothing else: every method walks or reads it at the moment it is
 * called. That is the contract — see {@link ModelDefinition} for why caching here would go stale with
 * nothing able to invalidate it — and it is also what keeps the cheap questions cheap. A definition is
 * obtained at the point of use and dropped, so the renderer reference it holds never outlives the call
 * that asked for it.
 *
 * <h2>Two walks, deliberately not one</h2>
 * {@link #boneLinks()} and {@link #bones()} traverse the same hierarchy and are still two separate walks.
 * {@code bones()} reconstructs every bone's rest transform — three joml copies and a quaternion
 * composition each — and does not carry the one bit a rig derivation needs, which is whether the renderer
 * will give a bone a display. Sharing a walk would mean charging {@code RigFactory} a 256-bone model's
 * bind geometry to answer a question about names.
 */
public final class BetterModelDefinition implements ModelDefinition {

    private final @NotNull ModelRenderer renderer;

    /** @param renderer the loaded model to read; never retained beyond the call that asked for it */
    public BetterModelDefinition(@NotNull ModelRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public @NotNull String modelId() {
        return this.renderer.name();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code flatten()} walks the hierarchy without computing any geometry, and is depth first, so a
     * parent precedes its children as the contract requires. {@code BoneName#name} is the bare name with
     * behavioural tag prefixes stripped, which is the identity a rig keys on.
     */
    @Override
    public @NotNull List<String> boneNames() {
        return this.renderer.flatten().map(group -> group.name().name()).toList();
    }

    @Override
    public @NotNull List<ModelBoneLink> boneLinks() {
        return BetterModelSkeletons.boneLinksOf(this.renderer);
    }

    @Override
    public @NotNull List<ModelBone> bones() {
        return BetterModelSkeletons.bonesOf(this.renderer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The renderer's own key set, in its own order.
     */
    @Override
    public @NotNull Collection<String> animationIds() {
        return this.renderer.animations().keySet();
    }

    @Override
    public @NotNull String toString() {
        return "BetterModelDefinition[" + modelId() + "]";
    }
}
