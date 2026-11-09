package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An off-tick scheduler the test steps by hand.
 *
 * <p>Models the platform's documented behaviour rather than a convenient subset of it: it
 * <strong>swallows anything that escapes the body and keeps the task alive</strong>, which is what the
 * real async scheduler does and the reason a loop has to do its own catching. Escaped throwables are
 * recorded instead of rethrown, so a test can assert that nothing escaped — a failure the real platform
 * would hide.
 */
final class FakeOffTickScheduler implements SimulationScheduler {

    private final @NotNull List<Throwable> escaped = new ArrayList<>();

    private @Nullable Runnable body;
    private @Nullable FakeTask task;
    private @Nullable TimeUnit unit;
    private long initialDelay;
    private long period;
    private int scheduleCount;

    @Override
    public @NotNull SimulationTask runAtFixedRate(
            @NotNull Runnable body,
            long initialDelay,
            long period,
            @NotNull TimeUnit unit
    ) {
        this.body = body;
        this.initialDelay = initialDelay;
        this.period = period;
        this.unit = unit;
        this.scheduleCount++;
        final FakeTask scheduled = new FakeTask();
        this.task = scheduled;
        return scheduled;
    }

    /** Dispatches one iteration, as the platform would. */
    void runOnce() {
        final Runnable scheduled = this.body;
        final FakeTask current = this.task;
        if (scheduled == null || current == null) {
            throw new IllegalStateException("Nothing has been scheduled");
        }
        if (current.cancelled) {
            throw new IllegalStateException(
                    "The task is cancelled; the platform would not dispatch it again");
        }
        try {
            scheduled.run();
        } catch (final Throwable thrown) {
            // Swallowed and the task survives. Exactly what the platform does.
            this.escaped.add(thrown);
        }
    }

    /** Whether a task is scheduled and not cancelled. */
    boolean isAlive() {
        return this.task != null && !this.task.cancelled;
    }

    int scheduleCount() {
        return this.scheduleCount;
    }

    long initialDelay() {
        return this.initialDelay;
    }

    long period() {
        return this.period;
    }

    @Nullable TimeUnit unit() {
        return this.unit;
    }

    @NotNull List<Throwable> escaped() {
        return List.copyOf(this.escaped);
    }

    private static final class FakeTask implements SimulationTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
