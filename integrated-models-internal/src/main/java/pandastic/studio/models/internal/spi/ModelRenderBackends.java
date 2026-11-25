package pandastic.studio.models.internal.spi;

import org.jetbrains.annotations.NotNull;

/**
 * The one backend this server has, resolved once and remembered.
 *
 * <h2>Why a holder rather than a constructor parameter</h2>
 * Because the call sites it serves are already static factories over a renderer's own static registry —
 * {@link pandastic.studio.models.internal.render.ModelSkeleton#of(String)},
 * {@link pandastic.studio.models.internal.rig.ModelBoneSource#renderer()},
 * {@link pandastic.studio.models.internal.menu.ModelLibrary#renderer()},
 * {@link pandastic.studio.models.internal.menu.WardrobeRendering#trackers()} and the two anchors. Every
 * one of them already had process-wide reach and already said so in its name; threading a backend
 * instance through all of them would change six public signatures and four wiring sites to express
 * exactly the lifetime this class expresses in six lines.
 *
 * <p>Each of those factories is also the <em>production</em> side of a seam whose test side takes an
 * explicit value. Nothing in the test suite reaches this class, and nothing should: a test that needs a
 * fake renderer uses {@code ModelBoneSource}'s lambda form, {@code RigBindValidator.over(...)},
 * {@code AnchorFactory}'s or {@code ModelLibrary}'s own functional shape. That is what makes a
 * process-wide holder acceptable here and would not make it acceptable anywhere else.
 *
 * <h2>No setter, and therefore no state to get wrong</h2>
 * Resolution happens in a class-initialiser holder, so it runs exactly once, is published safely to every
 * thread by the JVM's own class-initialisation barrier, and cannot be re-pointed. There is no
 * {@code install} step to forget, no order dependency with plugin enable, and no window in which one
 * caller sees a backend and another does not.
 *
 * <p>The consequence is that a backend is resolved lazily, at first use. Plugin enable deliberately forces
 * it — {@code IntegratedModelsPlugin.onEnable} reads {@link #resolution()} as its first statement and
 * refuses to enable when it is absent — so that the operator reads the explanation once, at startup,
 * instead of meeting {@link ModelRenderBackendResolution#require()}'s exception from inside a bind.
 */
public final class ModelRenderBackends {

    private ModelRenderBackends() {
        throw new AssertionError("No instances.");
    }

    /**
     * What the {@link java.util.ServiceLoader} scan found, including everything it passed over.
     *
     * <p>Idempotent and cheap after the first call. {@code IntegratedModelsPlugin.onEnable} logs
     * {@link ModelRenderBackendResolution#describe()} from here, which is the only place that message is
     * expected to be read.
     */
    public static @NotNull ModelRenderBackendResolution resolution() {
        return Holder.RESOLUTION;
    }

    /**
     * The backend, or a failure explaining what was not found.
     *
     * <p>The method every production factory in this project calls. It cannot return {@code null} and it
     * cannot return a half-built backend, so no caller has to decide what to do about a missing renderer
     * — enable has already refused in that case, and reaching this from a plugin that did enable means a
     * backend exists.
     *
     * @throws IllegalStateException if no backend was found, carrying
     *                               {@link ModelRenderBackendResolution#describe()}
     */
    public static @NotNull ModelRenderBackend require() {
        return Holder.RESOLUTION.require();
    }

    /**
     * Initialisation-on-demand holder.
     *
     * <p>Not a {@code static final} field on the outer class: that would run the scan the first time
     * anything touched {@link ModelRenderBackends}, including a diagnostic that only wanted to name the
     * class. A nested holder defers it to the first call that genuinely needs an answer, with the same
     * exactly-once and safe-publication guarantees and no lock of our own.
     */
    private static final class Holder {

        private static final @NotNull ModelRenderBackendResolution RESOLUTION =
                ModelRenderBackendLookup.resolve();

        private Holder() {
            throw new AssertionError("No instances.");
        }
    }
}
