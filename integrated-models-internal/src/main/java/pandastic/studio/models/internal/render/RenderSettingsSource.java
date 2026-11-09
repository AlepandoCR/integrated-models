package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the render layer gets its current {@link RenderSettings} from.
 *
 * <h2>The seam that makes a reload possible without a rebind</h2>
 * The configuration layer publishes snapshots by swapping an immutable value behind an
 * {@code AtomicReference}; this interface is the read half of that, narrowed to what the render layer
 * needs so nothing here has to name the configuration layer at all. A live implementation is a method
 * reference to that reference's getter; a test or a caller with no configuration uses {@link #fixed}.
 *
 * <p>The contract on a consumer is the important half: <strong>call {@link #current()} once per step and
 * use the result for the whole step</strong>. Calling it per value is the bug this type is shaped to
 * avoid — see {@link RenderSettings} for what a half-reloaded emission pass does to a bone chain.
 *
 * <p>Implementations must be safe to call from any thread: a sink's {@code accept} runs on the
 * simulation thread, which is neither the main thread nor the one that published the reload.
 */
@FunctionalInterface
public interface RenderSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull RenderSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>For a caller outside the configuration layer, and for tests — which is where it earns its
     * keep, because a fixed source makes "this value was honoured" and "this value was ignored and the
     * default happened to agree" distinguishable.
     */
    static @NotNull RenderSettingsSource fixed(@NotNull RenderSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
