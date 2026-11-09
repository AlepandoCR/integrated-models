package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;

/**
 * Where the command layer reads its live {@link CommandSettings} from.
 *
 * <p>The contract on a consumer is the important half: <strong>call {@link #current()} once per
 * invocation and use that value for every line of the reply</strong>. The command under this seam is
 * the one thing on the server that can change its own wording while it runs, so reading the source per
 * line is not a theoretical hazard — a reload that edits {@code command.yml} would answer with a header
 * from one generation and diagnostics from the next, and the operator would be reading a reply that no
 * file describes. One read, held across both halves of the invocation, makes that unrepresentable.
 *
 * <p>Same discipline the simulation follows for its settings, the culler for its and the menu layer for
 * its, and for the same reason.
 */
@FunctionalInterface
public interface CommandSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull CommandSettings current();

    /** A source that always answers {@code settings}. For tests and for a fixed configuration. */
    static @NotNull CommandSettingsSource fixed(@NotNull CommandSettings settings) {
        return () -> settings;
    }
}
