package pandastic.studio.models.internal.spi;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the one {@link ModelRenderBackend} the shaded jar shipped, or decides that there is none and says
 * why.
 *
 * <h2>First one that builds wins</h2>
 * No matching rule, deliberately — unlike the NMS seam, which matches a provider's declared Minecraft
 * version character for character. There is nothing to match on here: a backend is not compatible with a
 * server version, it is compatible with a <em>rendering library</em>, and the only honest test of that is
 * whether its classes link. Which they either do, in which case it is the backend, or they do not, in
 * which case it is rejected with the linkage error attached. One jar carries one backend module by
 * construction, so "first that builds" is not an arbitrary choice between equals; it is the one
 * candidate.
 *
 * <h2>Our classloader, not the context classloader</h2>
 * {@link ServiceLoader#load(Class)} uses the thread context classloader, which during plugin enable on
 * Paper is not reliably the plugin's own. The two-argument form with
 * {@code ModelRenderBackendLookup.class.getClassLoader()} asks the loader that definitely contains the
 * shaded jar, which is the only place a backend of ours can be.
 *
 * <h2>A broken provider may not take the plugin down with it</h2>
 * Service loading is the one place where a class that cannot link is reached by iterating rather than by
 * calling, so the failure arrives as a {@link ServiceConfigurationError} or a {@link LinkageError} from
 * {@code hasNext()}. A backend compiled against a rendering library the server does not have installed
 * is exactly that case, and it is also the case most likely to happen in the field — an operator who
 * removed the renderer plugin. Both are caught, recorded as rejections, and reported, so the message the
 * operator reads names the missing library instead of being a stack trace from the middle of enable.
 *
 * <p>Iteration <em>stops</em> at such an error rather than skipping past it. {@code ServiceLoader}'s
 * iterator does not always advance when {@code hasNext()} throws, so "continue and try the next one"
 * risks an unbounded loop during plugin enable. Stopping is deterministic, costs only the providers after
 * the broken one, and there is one provider.
 *
 * <h2>Nothing here is tunable</h2>
 * There is no key, bound or default to configure: the providers come from the classpath and the selection
 * rule is a correctness decision rather than a preference. An operator who wants a different renderer
 * installs a jar built with a different backend module, which is the whole point of the module split.
 */
public final class ModelRenderBackendLookup {

    private ModelRenderBackendLookup() {
        throw new AssertionError("No instances.");
    }

    /** Resolves against whatever backends the shaded jar shipped. */
    public static @NotNull ModelRenderBackendResolution resolve() {
        final ServiceLoader<ModelRenderBackendProvider> loader = ServiceLoader.load(
                ModelRenderBackendProvider.class, ModelRenderBackendLookup.class.getClassLoader());

        final List<ModelRenderBackendProvider> discovered = new ArrayList<>();
        final List<String> rejections = new ArrayList<>();
        try {
            for (final ModelRenderBackendProvider provider : loader) {
                discovered.add(provider);
            }
        } catch (final ServiceConfigurationError | LinkageError error) {
            rejections.add("a backend on the classpath could not be loaded, so discovery stopped there ("
                    + error + "). The usual cause is that the rendering library this jar was built "
                    + "against is not installed on the server");
        }

        return resolve(discovered, rejections);
    }

    /**
     * Resolves against an explicit provider list.
     *
     * <p>The seam that makes the selection rule testable without a server, a jar or a
     * {@code META-INF/services} file. Providers are considered in iteration order and the first one that
     * builds wins, so the order a caller supplies them in is the order they are preferred in.
     *
     * @param providers the candidates, in preference order
     */
    public static @NotNull ModelRenderBackendResolution resolve(
            @NotNull Iterable<? extends ModelRenderBackendProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        return resolve(providers, new ArrayList<>());
    }

    /**
     * The shared body. {@code rejections} arrives pre-seeded so that a discovery failure reported by
     * {@link #resolve()} keeps its place at the front of the explanation.
     */
    private static @NotNull ModelRenderBackendResolution resolve(
            @NotNull Iterable<? extends ModelRenderBackendProvider> providers,
            @NotNull List<String> rejections) {
        ModelRenderBackend chosen = null;
        String chosenRenderer = null;

        for (final ModelRenderBackendProvider provider : providers) {
            final String providerName = provider.getClass().getName();
            try {
                final String renderer =
                        Objects.requireNonNull(provider.rendererName(), "rendererName");

                if (chosen != null) {
                    rejections.add(providerName + " (for " + renderer
                            + ") was not needed; an earlier backend already built");
                    continue;
                }

                chosen = Objects.requireNonNull(provider.create(), "create");
                chosenRenderer = renderer;
            } catch (final RuntimeException | LinkageError failure) {
                // A provider whose own class linked but whose backend's does not fails here rather than
                // during discovery, because create() is the first call that touches it. Same rule:
                // record it and let enable report one sentence instead of throwing a stack trace.
                rejections.add(providerName + " failed to build its backend (" + failure + ")");
            }
        }

        return new ModelRenderBackendResolution(chosen, chosenRenderer, rejections);
    }
}
