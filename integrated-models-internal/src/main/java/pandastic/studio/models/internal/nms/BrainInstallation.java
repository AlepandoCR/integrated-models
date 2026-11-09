package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;

/**
 * A rebuilt brain, live on a creature, and the way back.
 *
 * <h2>What "the way back" can and cannot restore</h2>
 * {@link #uninstall()} rebuilds the creature's own brain through its species' own factory, carrying the
 * memories it currently holds across, and puts it back. That is as close to undone as the server
 * permits, and it is close: the creature returns to being driven by the behaviour its species was
 * written with.
 *
 * <p>What it cannot restore is goals. A creature's goals are registered in its constructor and there is
 * no supported way to re-run that, so a plan that cleared them has made an irreversible change. The
 * decision is the plan's — see {@link pandastic.studio.models.api.ai.AiPlan#clearsVanillaGoals()} — and
 * it is recorded here so that an uninstall does not look like it is restoring something it is not.
 *
 * <h2>Threading</h2>
 * {@link #uninstall()} replaces a field on a live creature and stops its running behaviours, so it is
 * owning-thread only and an implementation asserts that rather than trusting its caller — the platform
 * has no strict thread-check mode and a field write from a foreign region thread fails silently.
 * {@link #isInstalled()} and {@link #describe()} are safe from anywhere.
 */
public interface BrainInstallation {

    /**
     * Raw access to the creature's memories, through the brain this installation built.
     *
     * <p>Unguarded; see {@link MemoryStore}. Valid until {@link #uninstall()}, after which a read
     * answers {@code null} and a write is discarded — which is the correct behaviour rather than a
     * failure, because the thing being written no longer drives anything.
     *
     * @return the store
     */
    @NotNull MemoryStore memories();

    /** Whether this installation is still driving the creature. */
    boolean isInstalled();

    /**
     * Stops the running behaviours and restores the creature's own brain.
     *
     * <p>Idempotent. Owning-thread only; see the class documentation.
     */
    void uninstall();

    /** This installation in one phrase, for a log line or a failure message. */
    @NotNull String describe();
}
