package pandastic.studio.models.nms;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.NmsBridgeProvider;

/**
 * Announces this module's adapter to {@code NmsBridgeLookup} through {@code META-INF/services}.
 *
 * <h2>Why the version is not written here</h2>
 * {@link NmsTarget#MINECRAFT_VERSION} is generated from the Folia dev bundle this module was
 * compiled against. A literal in this file would be a second source of truth that the build cannot
 * check: raise the {@code folia} version in the catalog and the adapter would compile against the
 * new server while still announcing the old version, so the lookup would reject it on every server
 * — correct code, never selected, nothing logged beyond "no adapter matches". Deriving it removes
 * the possibility rather than documenting the hazard.
 *
 * <h2>Nothing happens in the constructor</h2>
 * {@code ServiceLoader} instantiates this class during plugin enable, and a provider that touched
 * the server there would do so before the registries it needs are populated. Construction is
 * therefore trivial and {@link #create()} only allocates: anything that has to happen once per
 * server — registering a {@code MemoryModuleType}, which is silently useless unregistered — belongs
 * to an explicit install step on the bridge, where a failure can be attributed to a model rather
 * than to the plugin's startup.
 */
public final class FoliaNmsBridgeProvider implements NmsBridgeProvider {

    /**
     * Public and no-argument because {@code ServiceLoader} requires it.
     */
    public FoliaNmsBridgeProvider() {
        // Intentionally empty; see the class javadoc.
    }

    @Override
    @NotNull
    public String supportedMinecraftVersion() {
        return NmsTarget.MINECRAFT_VERSION;
    }

    @Override
    @NotNull
    public NmsBridge create() {
        return new FoliaNmsBridge();
    }
}
