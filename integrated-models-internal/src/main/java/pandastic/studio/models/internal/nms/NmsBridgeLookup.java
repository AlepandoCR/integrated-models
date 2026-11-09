package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Picks the one {@link NmsBridge} that was compiled for the server actually running, or decides that
 * there is none and says why.
 *
 * <h2>Exact version match, deliberately</h2>
 * A provider is accepted only when {@link NmsBridgeProvider#supportedMinecraftVersion()} equals the
 * version string the server reports, character for character. No ranges, no "26.3 or newer", no
 * major/minor prefix match. An NMS method that was renamed or re-signatured between two versions
 * does not announce itself at load time — it fails as a {@link NoSuchMethodError} the first time the
 * AI layer runs, inside a tick, on a region thread, with a stack trace that names our class rather
 * than the mismatch. Refusing up front converts that into a startup log line. Folia's 26.3 line
 * living on the alpha channel makes this more than theoretical: the server underneath can move four
 * builds without anyone upgrading a Minecraft version.
 *
 * <h2>Our classloader, not the context classloader</h2>
 * {@link ServiceLoader#load(Class)} uses the thread context classloader, which during plugin enable
 * on Paper is not reliably the plugin's own. The two-argument form with
 * {@code NmsBridgeLookup.class.getClassLoader()} asks the loader that definitely contains the
 * shaded jar, which is the only place a provider of ours can be.
 *
 * <h2>A broken provider may not take the plugin down with it</h2>
 * Service loading is the one place where a class that cannot link is reached by iterating rather
 * than by calling, so the failure arrives as a {@link ServiceConfigurationError} or a
 * {@link LinkageError} from {@code hasNext()}. An adapter compiled against NMS types that the
 * running server does not have is exactly that case, and it is also the case most likely to happen
 * in the field. Both are caught, recorded as rejections, and reported — never propagated, because
 * the rendering pipeline does not need NMS and must keep working without it.
 *
 * <p>Iteration <em>stops</em> at such an error rather than skipping past it. {@code ServiceLoader}'s
 * iterator does not always advance when {@code hasNext()} throws, so "continue and try the next one"
 * risks an unbounded loop during plugin enable. Stopping is deterministic, costs only the providers
 * after the broken one, and there is one provider.
 *
 * <h2>Nothing here is tunable</h2>
 * There is no key, bound or default to configure: the version comes from the server, the providers
 * come from the classpath, and the matching rule is a correctness decision rather than a preference.
 */
public final class NmsBridgeLookup {

    private NmsBridgeLookup() {
        throw new AssertionError("No instances.");
    }

    /**
     * Resolves against whatever adapters the shaded jar shipped.
     *
     * @param minecraftVersion the running server's Minecraft version, as it reports it
     */
    @NotNull
    public static NmsBridgeResolution resolve(@NotNull String minecraftVersion) {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");

        final ServiceLoader<NmsBridgeProvider> loader =
                ServiceLoader.load(NmsBridgeProvider.class, NmsBridgeLookup.class.getClassLoader());

        final List<NmsBridgeProvider> discovered = new ArrayList<>();
        final List<String> rejections = new ArrayList<>();
        try {
            for (final NmsBridgeProvider provider : loader) {
                discovered.add(provider);
            }
        } catch (final ServiceConfigurationError | LinkageError error) {
            rejections.add("an adapter on the classpath could not be loaded, so discovery stopped "
                    + "there (" + error + ")");
        }

        return resolve(minecraftVersion, discovered, rejections);
    }

    /**
     * Resolves against an explicit provider list.
     *
     * <p>The seam that makes the matching rule testable without a server, a jar or a
     * {@code META-INF/services} file. Providers are considered in iteration order and the first
     * match wins, so the order a caller supplies them in is the order they are preferred in.
     *
     * @param minecraftVersion the version to match against
     * @param providers        the candidates, in preference order
     */
    @NotNull
    public static NmsBridgeResolution resolve(
            @NotNull String minecraftVersion,
            @NotNull Iterable<? extends NmsBridgeProvider> providers) {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(providers, "providers");
        return resolve(minecraftVersion, providers, new ArrayList<>());
    }

    /**
     * The shared body. {@code rejections} arrives pre-seeded so that a discovery failure reported by
     * {@link #resolve(String)} keeps its place at the front of the explanation.
     */
    @NotNull
    private static NmsBridgeResolution resolve(
            @NotNull String minecraftVersion,
            @NotNull Iterable<? extends NmsBridgeProvider> providers,
            @NotNull List<String> rejections) {
        NmsBridge chosen = null;

        for (final NmsBridgeProvider provider : providers) {
            final String providerName = provider.getClass().getName();
            try {
                final String supported = Objects.requireNonNull(
                        provider.supportedMinecraftVersion(),
                        "supportedMinecraftVersion");

                if (chosen != null) {
                    rejections.add(providerName + " (for " + supported
                            + ") was not needed; an earlier adapter already matched");
                    continue;
                }
                if (!minecraftVersion.equals(supported)) {
                    rejections.add(providerName + " is for Minecraft " + supported);
                    continue;
                }

                chosen = Objects.requireNonNull(provider.create(), "create");
            } catch (final RuntimeException | LinkageError failure) {
                // A provider for the right version whose classes are absent fails here rather than
                // during discovery, because create() is the first call that touches them. Same
                // rule: record it and keep the plugin alive.
                rejections.add(providerName + " failed to build its adapter (" + failure + ")");
            }
        }

        return new NmsBridgeResolution(chosen, minecraftVersion, rejections);
    }
}
