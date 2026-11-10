package pandastic.studio.models.internal.menu;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * BetterModel's model registry, as a {@link ModelLibrary}.
 *
 * <p>The adapter on the far side of that seam, and deliberately the only class in this package that
 * cannot be exercised without the renderer installed: both answers come from static methods on
 * {@code BetterModel}, which has no instance to stand in for and no runtime copy in this project's test
 * source set. Everything with a decision in it therefore lives on the other side of the seam and what is
 * left here is two lookups.
 *
 * <h2>Stateless on purpose</h2>
 * No field, no cache, no reload hook. The renderer re-reads its own models on its own operator command
 * and announces that to nobody, so anything kept here would be a copy with no event able to invalidate
 * it — and the symptom of that is a picker offering a model the renderer no longer has, which then fails
 * at the moment a player presses Wear rather than at the moment the list was built.
 */
final class RendererModelLibrary implements ModelLibrary {

    @Override
    public @NotNull Collection<String> modelIds() {
        // The renderer's own key set, unmodifiable and live. Handed straight out: the contract says a
        // caller that keeps it copies it, and LibraryModelCatalogue does exactly that when it sorts.
        return BetterModel.modelKeys();
    }

    @Override
    public @NotNull Collection<String> animationIds(@NotNull String modelId) {
        final ModelRenderer renderer = BetterModel.modelOrNull(modelId);
        // A model the renderer does not have is not a fault here. The picker re-checks membership on
        // every click precisely because a reload between two clicks is ordinary, and the honest answer
        // for a model that has gone is "it declares no animations".
        return renderer == null ? List.of() : renderer.animations().keySet();
    }

    @Override
    public @NotNull String toString() {
        return "RendererModelLibrary";
    }
}
