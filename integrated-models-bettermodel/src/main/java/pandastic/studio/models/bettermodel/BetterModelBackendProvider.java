package pandastic.studio.models.bettermodel;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.spi.ModelRenderBackend;
import pandastic.studio.models.internal.spi.ModelRenderBackendProvider;

/**
 * Announces {@link BetterModelBackend} to {@link java.util.ServiceLoader}.
 *
 * <p>Named in exactly one place — {@code META-INF/services/} plus this class's own fully qualified name —
 * and nowhere in {@code integrated-models-internal}. That is the whole mechanism by which a second
 * renderer becomes a second module rather than an edit to the engine.
 *
 * <h2>Why this class carries no BetterModel reference</h2>
 * Deliberately: a provider is reached by <em>iterating</em> the service loader, so its own class has to
 * link before anything else can be decided. If this class named a {@code kr.toxicity} type in a field, a
 * signature or a constant, a server without BetterModel installed would fail inside
 * {@code ServiceLoader}'s {@code hasNext()} and the lookup would only be able to report that discovery
 * broke. Keeping the renderer behind {@link #create()} moves that failure one step later, to a place
 * where it can be attributed to this provider by name.
 *
 * <p>In practice it does not arise: {@code plugin.yml} declares {@code depend: [BetterModel]}, so the
 * server refuses to enable Integrated Models at all without it. The ordering above is what makes the
 * message right when something else goes wrong — a BetterModel major version that moved a class, for
 * instance.
 */
public final class BetterModelBackendProvider implements ModelRenderBackendProvider {

    /**
     * Required by {@link java.util.ServiceLoader}, which instantiates a provider through its public
     * no-argument constructor.
     */
    public BetterModelBackendProvider() {
        // Nothing to do, and nothing that may be done: see ModelRenderBackendProvider on why
        // construction has to be free of side effects.
    }

    @Override
    public @NotNull String rendererName() {
        // A constant, and not read off the backend: this is called before create(), so it must not
        // depend on anything create() would have set up.
        return "BetterModel";
    }

    @Override
    public @NotNull ModelRenderBackend create() {
        return new BetterModelBackend();
    }
}
