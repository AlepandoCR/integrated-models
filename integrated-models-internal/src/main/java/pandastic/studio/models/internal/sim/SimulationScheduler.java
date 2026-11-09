package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Where the simulation loop gets its repeating off-tick task from.
 *
 * <p>One method, so that the only untestable part of the loop is a delegation thin enough to read in
 * one glance ({@link AsyncSimulationScheduler}) and everything with logic in it can be driven by a fake
 * step by step. The loop holds no platform type for the same reason.
 *
 * <h2>The behaviour the loop depends on</h2>
 * An implementation must match the platform async scheduler on all four of these, because the loop is
 * built on them rather than merely tolerant of them:
 *
 * <ol>
 *   <li><strong>Iterations are strictly serialised.</strong> The task reschedules itself after the body
 *       returns, so two iterations never overlap and the effective period is
 *       {@code max(period, bodyDuration)}. This is what lets a simulation own unsynchronised state.</li>
 *   <li><strong>No thread affinity.</strong> Successive iterations may run on different threads, so
 *       nothing in the loop may use a {@link ThreadLocal} or a per-thread cached buffer.</li>
 *   <li><strong>Missed iterations are dropped, never replayed</strong>, and the phase shifts. Hence
 *       {@link SimulationClock} — see its javadoc.</li>
 *   <li><strong>An exception escaping the body is swallowed and the repeating task survives.</strong>
 *       Nothing self-heals by throwing; a throwing body simply runs again next period, which is why the
 *       loop catches and rate-limits rather than relying on the platform.</li>
 * </ol>
 */
@FunctionalInterface
public interface SimulationScheduler {

    /**
     * Schedules {@code body} to repeat off the server tick until the returned task is cancelled.
     *
     * @param body         the iteration; invoked on an arbitrary thread, never concurrently with itself
     * @param initialDelay delay before the first iteration, in {@code unit}
     * @param period       delay between iterations, in {@code unit}; a floor, not a guarantee
     * @param unit         the unit {@code initialDelay} and {@code period} are expressed in
     * @return a handle to retain and cancel on shutdown
     */
    @NotNull SimulationTask runAtFixedRate(
            @NotNull Runnable body,
            long initialDelay,
            long period,
            @NotNull TimeUnit unit
    );
}
