package pandastic.studio.models.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The off-tick scheduler both server-wide loops are dispatched through, recording rather than running.
 *
 * <p>One instance serves the simulation loop and the decoration driver loop, exactly as the production
 * assembly hands them one {@code AsyncSimulationScheduler}. Dispatches are kept in order, so a test can
 * say both which period a loop asked for and <em>that it asked again</em> — which is the only
 * observable difference between a reload that reached a loop and one that did not.
 *
 * <p>Nothing runs a body. These tests are about assembly, teardown and re-dispatch; what a pass does
 * once it runs belongs to {@code SimulationLoopTest} and {@code DecorationDriverLoopTest}, which both
 * drive their bodies directly.
 */
final class RecordingScheduler implements SimulationScheduler {

    private final @NotNull RuntimeFixtures.Journal journal;
    private final @NotNull List<Dispatch> dispatches = new ArrayList<>();

    RecordingScheduler(@NotNull RuntimeFixtures.Journal journal) {
        this.journal = journal;
    }

    @Override
    public @NotNull SimulationTask runAtFixedRate(
            @NotNull Runnable body,
            long initialDelay,
            long period,
            @NotNull TimeUnit unit
    ) {
        final Dispatch dispatch = new Dispatch(unit.toNanos(period));
        this.dispatches.add(dispatch);
        this.journal.record("dispatch");
        return dispatch;
    }

    /** Every dispatch, in order, including the ones a reload replaced. */
    @NotNull List<Dispatch> dispatches() {
        return List.copyOf(this.dispatches);
    }

    /** How many tasks are dispatched and not cancelled. Zero after a complete shutdown. */
    int live() {
        return (int) this.dispatches.stream().filter(dispatch -> !dispatch.isCancelled()).count();
    }

    /** One dispatched repeating task, and whether anybody cancelled it. */
    final class Dispatch implements SimulationTask {

        private final long periodNanos;
        private boolean cancelled;

        private Dispatch(long periodNanos) {
            this.periodNanos = periodNanos;
        }

        long periodNanos() {
            return this.periodNanos;
        }

        @Override
        public void cancel() {
            if (this.cancelled) {
                return;
            }
            this.cancelled = true;
            RecordingScheduler.this.journal.record("cancel");
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
