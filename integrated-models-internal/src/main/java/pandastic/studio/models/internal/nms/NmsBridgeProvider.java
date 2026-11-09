package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;

/**
 * How an {@link NmsBridge} implementation announces itself, and the only thing it is allowed to
 * assert about where it will work.
 *
 * <h2>Discovered, not named</h2>
 * Implementations are found with {@link java.util.ServiceLoader}, so the class name of an adapter
 * appears in exactly one place — its {@code META-INF/services} entry, inside the module that
 * compiled it. Nothing above the seam holds a string naming an implementation. That matters twice
 * over: a module that is excluded from the build takes its service entry with it, so "no adapter
 * shipped" and "no adapter for this version" reduce to the same, already-handled case; and adding a
 * 26.4 adapter later is a new module plus a new service file, with no edit to this package.
 *
 * <h2>A provider states a fact; the lookup makes the decision</h2>
 * {@link #supportedMinecraftVersion()} is a declaration, not a predicate. The provider does not get
 * to decide whether it is compatible, because an adapter asked "do you support 26.4?" has every
 * incentive to say yes and no way to know. {@link NmsBridgeLookup} owns the comparison, so the
 * matching rule is written once, is visible, and is testable without a server.
 *
 * <h2>Construction has to be cheap and side-effect-free</h2>
 * {@link #create()} is called during plugin enable, on the main thread, with no entity and no world
 * to work on. It may not touch the server, register anything, or read configuration. Anything that
 * has to happen once per server — registering a {@code MemoryModuleType}, for instance, which is
 * silently useless unregistered — belongs to a later, explicit install step on the bridge itself,
 * where a failure can be reported against a model instead of against the plugin's startup.
 */
public interface NmsBridgeProvider {

    /**
     * The exact Minecraft version this adapter was compiled against, in the form the server reports
     * its own version.
     *
     * <p>Not a range and not a minimum. NMS signatures are not stable between versions, and 26.3
     * exists on Folia's alpha channel only, where they can move between two builds nobody chose to
     * upgrade between. A range would convert a clean "no adapter for this server" into a
     * {@link NoSuchMethodError} raised from inside a tick.
     */
    @NotNull
    String supportedMinecraftVersion();

    /**
     * Builds the bridge. Called at most once per server start, and only after the lookup has
     * confirmed the version matches.
     */
    @NotNull
    NmsBridge create();
}
