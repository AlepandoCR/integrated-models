package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the decoration layer gets its current {@link DecorationSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot publication, narrowed so nothing in this
 * package has to name that layer. A live implementation is a lambda over the live snapshot; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Read once per pass, never per decoration</h2>
 * {@link DecorationDriverLoop} takes exactly one snapshot at the top of a pass and drives every
 * decoration from it. Reading it per decoration would let a reload land inside one pass, so two
 * decorations standing a block apart would be driven by two different winds — and because the gusts are
 * a function of position, the symptom would be a seam in the weather rather than anything that looks
 * like a configuration change.
 *
 * <p>Implementations must be safe to call from any thread: the loop reads it from an asynchronous pool
 * thread with no affinity, and a reload is published from another.
 */
@FunctionalInterface
public interface DecorationSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull DecorationSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from deliberately unusual
     * coefficients is what distinguishes a layer that reads its configuration from one that ignores it
     * and agrees with the default by accident.
     *
     * @param settings the settings to answer with
     * @return the source
     */
    static @NotNull DecorationSettingsSource fixed(@NotNull DecorationSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
