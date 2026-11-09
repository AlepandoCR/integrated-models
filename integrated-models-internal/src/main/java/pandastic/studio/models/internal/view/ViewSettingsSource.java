package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

/**
 * Where the culler reads its live {@link ViewSettings} from.
 *
 * <p>The contract on a consumer is the important half: <strong>call {@link #current()} once per pass
 * and use that value for the whole pass</strong>. Reading it per model, or worse per (model, viewer)
 * pair, lets a reload land in the middle and produce a pass that admits a model on one generation's
 * range and then measures its angle against another's — a mixed decision no configuration describes.
 * That is the same discipline the simulation already follows for its driver sample and its own
 * settings, and for the same reason.
 */
@FunctionalInterface
public interface ViewSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull ViewSettings current();

    /** A source that always answers {@code settings}. For tests and for a fixed configuration. */
    static @NotNull ViewSettingsSource fixed(@NotNull ViewSettings settings) {
        return () -> settings;
    }
}
