package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;

/**
 * Where the menu layer reads its live {@link MenuSettings} from.
 *
 * <p>The contract on a consumer is the important half: <strong>call {@link #current()} once per screen
 * build and use that value for the whole build</strong>. Reading it per label would let a reload land
 * between two labels and produce a screen whose title came from one generation and whose buttons came
 * from another — a mixed screen no configuration file describes. That is the same discipline the
 * simulation follows for its own settings and the culler for its, and for the same reason.
 *
 * <p>A build is cheap and happens on a click, so holding the value for its duration costs nothing: the
 * next screen the player opens is already built against the next generation.
 */
@FunctionalInterface
public interface MenuSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull MenuSettings current();

    /** A source that always answers {@code settings}. For tests and for a fixed configuration. */
    static @NotNull MenuSettingsSource fixed(@NotNull MenuSettings settings) {
        return () -> settings;
    }
}
