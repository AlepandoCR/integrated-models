package pandastic.studio.models.internal.binding;

/**
 * The repeating per-tick sampler of one model, reduced to the two things the binding layer does with it.
 *
 * <p>A seam rather than a platform type for the same reason
 * {@link pandastic.studio.models.internal.sim.SimulationTask} is one: everything with a decision in it
 * belongs where a test can reach it, and the real implementation is a two-line adapter over an entity
 * scheduler's handle.
 *
 * <h2>Why the handle has to be retained</h2>
 * Region- and entity-scheduled repeating tasks are <strong>never</strong> cancelled for a plugin on
 * disable — unlike the main scheduler's, which the platform sweeps. A sampler whose handle was dropped
 * keeps running after the plugin's classloader has been closed, and fails on the first lazy class load
 * after that, during shutdown, pointing nowhere near the model that leaked it. So every task this layer
 * schedules is held and cancelled by hand.
 */
public interface AnchorTask {

    /**
     * Stops further executions.
     *
     * <p>Idempotent, safe from any thread, and safe to call from inside the task's own body — which is
     * exactly what happens when a sampler discovers its anchor is gone and tears the model down from
     * there.
     *
     * <p>Does <strong>not</strong> wait for an execution already in flight. The binding layer never
     * needs it to: the sampler only writes into its own model's driver holder and gait state, so a body
     * that lands after the cancel writes into state nothing reads any more.
     */
    void cancel();

    /** Whether this task has been cancelled. */
    boolean isCancelled();
}
