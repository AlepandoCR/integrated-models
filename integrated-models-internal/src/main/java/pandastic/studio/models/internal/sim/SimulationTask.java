package pandastic.studio.models.internal.sim;

/**
 * A handle on a running repeating task, narrowed to the two operations the loop needs.
 *
 * <p>Exists so {@link SimulationLoop} never names a platform task type and can therefore be driven by
 * a fake in tests.
 *
 * <h2>Retaining the handle is not optional</h2>
 * The plugin's classloader is closed <em>before</em> the platform runs its own {@code cancelTasks}, so a
 * task still alive at that point blows up on its first lazy class load after the close — as a stack
 * trace nobody can attribute, during shutdown, after the plugin has apparently stopped. Cancelling
 * through a retained handle as the first statement of {@code onDisable()} is the only ordering that
 * avoids it.
 */
public interface SimulationTask {

    /**
     * Prevents any further iteration from being dispatched.
     *
     * <p>Does <strong>not</strong> interrupt an iteration already in flight: the body may still be
     * running when this returns, and will run to completion. Anything a sink touches must therefore
     * tolerate being touched once more after shutdown has begun.
     *
     * <p>Idempotent.
     */
    void cancel();

    /** Whether {@link #cancel()} has been called. */
    boolean isCancelled();
}
