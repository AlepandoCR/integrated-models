package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

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
 * from a static method on BetterModel's entry point, which cannot be faked and cannot even be
 * <em>loaded</em> in this project's test source set — the renderer is {@code testCompileOnly}, so there
 * is no runtime copy of it. Keeping the static calls in one small adapter means every decision about
 * order, membership and counting lives on this side of the seam, where a test can reach it.
 *
 * <h2>No caching, by contract</h2>
 * An implementation answers from whatever the renderer holds at the moment it is asked.
 * <strong>Both methods are expected to be called once per screen build</strong>, and neither may hold a
 * copy between builds: BetterModel reloads its own models on its own operator command and reports that
 * to nobody, so a cached list has no event that could invalidate it and would go stale with nothing to
 * point at. See {@link LibraryModelCatalogue} for the cost of that decision, which is one copy and one
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
     * The production library: BetterModel's own model registry.
     *
     * <p>A factory rather than a constructor so that the one class in this package holding a
     * {@code kr.toxicity} import is package-private and is never named, and therefore never loaded, by
     * anything that only wants the seam.
     *
     * @return the library over the installed renderer
     */
    static @NotNull ModelLibrary renderer() {
        return new RendererModelLibrary();
    }
}
