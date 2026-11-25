package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.spi.ModelDefinition;
import pandastic.studio.models.internal.spi.ModelRenderBackend;
import pandastic.studio.models.internal.spi.ModelRenderBackends;

import java.util.Collection;
import java.util.List;

/**
 * What the renderer has loaded, reduced to the two questions a menu asks of it.
 *
 * <h2>Why this exists beside {@link ModelCatalogue}</h2>
 * They look like the same interface and they answer different questions.
 * {@link ModelCatalogue} is what the <em>menus</em> may offer: a stable order, a membership test the
 * picker cannot skip, and a count a player is told. This one is what the <em>renderer</em> happens to
 * hold right now, in whatever order its own map iterates. The catalogue is the policy; this is the fact.
 *
 * <p>Separating them is also the only way the policy is testable. Every answer below ultimately comes
 * from the installed rendering backend, which cannot be faked and cannot even be <em>loaded</em> in this
 * project's test source set — the renderer lives in another module and is {@code compileOnly} even there,
 * so there is no runtime copy of it. Keeping the backend calls behind this seam means every decision
 * about order, membership and counting lives on this side of it, where a test can reach it.
 *
 * <h2>No caching, by contract</h2>
 * An implementation answers from whatever the renderer holds at the moment it is asked.
 * <strong>Both methods are expected to be called once per screen build</strong>, and neither may hold a
 * copy between builds: a rendering library reloads its own models on its own operator command and reports
 * that to nobody, so a cached list has no event that could invalidate it and would go stale with nothing
 * to point at. See {@link LibraryModelCatalogue} for the cost of that decision, which is one copy and one
 * sort per screen.
 */
public interface ModelLibrary {

    /**
     * Every model id the renderer currently has loaded, in its own order.
     *
     * @return the ids; may be an unmodifiable live view, so a caller that keeps it copies it first
     */
    @NotNull Collection<String> modelIds();

    /**
     * Every animation id the named model declares, in its own order.
     *
     * @param modelId the model to ask about
     * @return the animation ids, empty when the model declares none or is not loaded. A model that
     *         vanished between two clicks is the ordinary case rather than the exotic one, which is why
     *         it is an empty answer rather than a failure
     */
    @NotNull Collection<String> animationIds(@NotNull String modelId);

    /**
     * The production library: the installed rendering backend's own model registry.
     *
     * <p>Two lookups and no decision, which is why it is a lambda here rather than a class: everything
     * renderer-specific is already behind {@link ModelRenderBackend}, in another module, and this seam
     * exists only so the policy above it can be tested without one.
     *
     * <p>A model that vanished between two clicks answers an empty animation list rather than failing.
     * That is the ordinary case rather than the exotic one — the picker re-checks membership on every
     * click precisely because a renderer reload between two clicks is ordinary — and the honest answer
     * for a model that has gone is "it declares no animations".
     *
     * @return the library over the installed renderer
     */
    static @NotNull ModelLibrary renderer() {
        return new ModelLibrary() {

            @Override
            public @NotNull Collection<String> modelIds() {
                // The renderer's own key set, which may be unmodifiable and live. Handed straight out:
                // the contract says a caller that keeps it copies it, and LibraryModelCatalogue does
                // exactly that when it sorts.
                return ModelRenderBackends.require().modelIds();
            }

            @Override
            public @NotNull Collection<String> animationIds(@NotNull String modelId) {
                return ModelRenderBackends.require().definition(modelId)
                        .map(ModelDefinition::animationIds)
                        .orElseGet(List::of);
            }

            @Override
            public @NotNull String toString() {
                return "ModelLibrary[renderer]";
            }
        };
    }
}
