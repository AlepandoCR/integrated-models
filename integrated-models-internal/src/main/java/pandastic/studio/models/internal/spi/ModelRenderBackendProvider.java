package pandastic.studio.models.internal.spi;

import org.jetbrains.annotations.NotNull;

/**
 * How a {@link ModelRenderBackend} implementation announces itself.
 *
 * <h2>Discovered, not named</h2>
 * Implementations are found with {@link java.util.ServiceLoader}, so the class name of a backend appears
 * in exactly one place — its {@code META-INF/services} entry, inside the module that compiled it. Nothing
 * above the seam holds a string naming an implementation. That matters twice over: swapping the rendering
 * library becomes a different module on the shaded jar's classpath with no edit to
 * {@code integrated-models-internal}; and a jar assembled without a backend module fails at one
 * well-named place instead of at a link error in whichever class happened to load first.
 *
 * <h2>Construction has to be cheap and side-effect-free</h2>
 * {@link #create()} is called at most once, during plugin enable, with no entity, no world and no
 * configuration to work on. It may not touch the server, spawn anything, register a listener or read a
 * file. A backend that needs the renderer plugin to be loaded gets that for free — {@code plugin.yml}
 * declares the renderer as a hard {@code depend}, so it is enabled first — but it must not <em>use</em>
 * it here, because anything that happens at construction happens before this plugin knows whether it is
 * going to enable at all.
 *
 * <p>{@link #rendererName()} is read before {@link #create()}, so it must not depend on anything
 * {@code create} would have set up. In practice it is a constant.
 */
public interface ModelRenderBackendProvider {

    /**
     * The rendering library this provider's backend drives, as a human would name it — {@code
     * "BetterModel"}, for instance.
     *
     * <p>Used in the enable-time log line and in the failure message when no backend is found, so it is
     * the string an operator will search for. Not an identifier and not matched against anything: the
     * lookup chooses the first provider that builds, because a jar carries one backend by construction.
     */
    @NotNull String rendererName();

    /** Builds the backend. Called at most once per server start. */
    @NotNull ModelRenderBackend create();
}
