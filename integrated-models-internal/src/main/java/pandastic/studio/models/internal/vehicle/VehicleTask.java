package pandastic.studio.models.internal.vehicle;

/**
 * A repeating control task, reduced to the two things this layer does with it.
 *
 * <p>A seam rather than the platform's own handle for the same reason
 * {@link pandastic.studio.models.internal.binding.AnchorTask} and
 * {@link pandastic.studio.models.internal.ai.AiTask} are: everything with a decision in it belongs where
 * a test can reach it, and the real implementation is a two-line adapter over a scheduled task.
 *
 * <h2>Why the handle has to be retained</h2>
 * A repeating task dispatched through an entity's scheduler is <strong>never</strong> cancelled for a
 * plugin on disable — unlike one on the main scheduler, which the platform sweeps. A control loop whose
 * handle was dropped keeps deciding where a vehicle is after the plugin's classloader has been closed,
 * and fails on the first lazy class load after that: during shutdown, with a stack trace pointing nowhere
 * near the vehicle that leaked it. So every task this layer schedules is held and cancelled by hand.
 */
public interface VehicleTask {

    /**
     * Stops further executions.
     *
     * <p>Idempotent, safe from any thread, and safe to call from inside the task's own body — which is
     * what happens when a step finds its root entity gone.
     *
     * <p>Does <strong>not</strong> wait for an execution already in flight. The teardown covers that
     * window by clearing the gate the body checks first, rather than by hoping the window is narrow.
     */
    void cancel();

    /** Whether this task has been cancelled. */
    boolean isCancelled();
}
