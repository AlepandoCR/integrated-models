package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the clip-driving layer gets its current {@link ClipSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot swap, narrowed so nothing here names the
 * configuration layer. A live implementation is a method reference to {@code ConfigService::clips}; a
 * test uses {@link #fixed}.
 *
 * <p>The contract on a consumer is the important half: <strong>call {@link #current()} once per decision
 * and use the result for the whole decision.</strong> See {@link ClipSettings} for what a half-reloaded
 * decision does to the hysteresis.
 *
 * <p>Implementations must be safe to call from any thread. In practice this one is read from a region
 * thread, because the clip selection runs beside the anchor sample that feeds it.
 */
@FunctionalInterface
public interface ClipSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull ClipSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>For tests, where it earns its keep by making "this value was honoured" and "this value was
     * ignored and the default happened to agree" distinguishable.
     */
    static @NotNull ClipSettingsSource fixed(@NotNull ClipSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
