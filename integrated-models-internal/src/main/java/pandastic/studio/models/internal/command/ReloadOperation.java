package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;

/**
 * The reload itself, as one call.
 *
 * <h2>Why the command does not reach for the runtime</h2>
 * Because what a reload <em>is</em> belongs to the runtime and not to the command: re-reading ten files,
 * publishing a snapshot only if every one of them validated, re-dispatching the four layers that own a
 * repeating task, and reading the rig directory again. The command's job is the half the runtime cannot
 * do — deciding whether a second invocation is allowed, getting the work off the thread that is ticking
 * a region, and turning the answer into configured text.
 *
 * <p>Keeping the two apart is also what makes the command testable at all. The production
 * implementation is a lambda over the plugin, which needs a server; everything the command decides is
 * exercisable against an implementation that returns a result nobody had to load.
 *
 * <h2>Called off the ticking thread</h2>
 * Always, and that is the point of the seam the command puts in front of it: this method reads files.
 * An implementation may block for as long as the disk takes and must be safe to call from a pool thread,
 * but it will never be called concurrently with itself through the same command — see
 * {@code ReloadCommand} for the gate that guarantees that.
 */
@FunctionalInterface
public interface ReloadOperation {

    /**
     * Re-reads everything and reports what happened.
     *
     * @return the result and the rig count that followed it
     * @throws RuntimeException if the reload failed in a way the configuration layer does not model,
     *                          which a refusal is not. The command reports it and leaves the
     *                          configuration as it was
     */
    @NotNull ReloadReport perform();
}
