package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.config.ConfigReloadResult;

/**
 * What one reload did, in the two pieces the command can report.
 *
 * <h2>Why the rig count is carried rather than looked up</h2>
 * A reload is two things happening together: the configuration layer publishes a snapshot or refuses to,
 * and — only if it published — the runtime re-reads every rig against the new bounds. The first half
 * reports itself in full through {@link ConfigReloadResult}; the second half reports nothing, because
 * the rigs live in a map the runtime owns and a rig that failed to compile is simply not in it.
 *
 * <p>So the count is taken by the same {@link ReloadOperation} that performed the reload, immediately
 * afterwards, and travels with the result. Asking for it separately later would be a second observation
 * of a value a concurrent reload could already have changed, which is the mixed-generation read this
 * project avoids everywhere else.
 *
 * @param result what the configuration layer did, including every diagnostic behind a refusal
 * @param rigs   how many rigs are loaded now. Unchanged from before when {@code result} was refused,
 *               because a refused reload re-reads nothing
 */
public record ReloadReport(@NotNull ConfigReloadResult result, int rigs) {

    public ReloadReport {
        if (rigs < 0) {
            throw new IllegalArgumentException("rigs cannot be negative, but was " + rigs);
        }
    }
}
