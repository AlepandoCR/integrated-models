package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;

/**
 * Where a failure that reached the command goes, in addition to whoever typed it.
 *
 * <h2>Why both halves are needed</h2>
 * The operator gets a sentence, because they are the one who can act on it. The console gets the stack
 * trace, because a reload that threw rather than being refused is usually a defect rather than a typo —
 * an unreadable directory, or the plugin being disabled while the reload was in flight — and the
 * sentence alone names neither the layer nor the line. Dropping the second half would make the one
 * failure shape this command cannot explain the one it says least about.
 *
 * <h2>Why a seam rather than a logger</h2>
 * The same argument {@link pandastic.studio.models.internal.sim.SimulationLogger} makes for itself: a
 * test cannot usefully implement Adventure's logger, and the decision worth testing here is
 * <em>whether</em> a failure is reported at all, not how it is formatted. The Adventure binding is one
 * lambda at the registration site.
 *
 * <p>Declared here rather than reusing that interface because this layer has nothing else to do with the
 * simulation loop, and a command importing the loop's logger would be the only edge between the two
 * packages — a dependency that says something untrue about how they relate.
 *
 * <p>Implementations are called from the thread the reload ran on, which is not a region thread, and
 * must be thread-safe.
 */
@FunctionalInterface
public interface CommandFailureSink {

    /**
     * Reports that the reload threw.
     *
     * @param message what was being attempted
     * @param thrown  the failure
     */
    void failed(@NotNull String message, @NotNull Throwable thrown);

    /** Discards everything. For tests and for a caller that has its own reporting. */
    @NotNull CommandFailureSink DISCARDING = (message, thrown) -> {
    };
}
