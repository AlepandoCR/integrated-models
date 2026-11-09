package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A {@link SimulationLogger} that lets one report through per interval and counts the rest.
 *
 * <h2>Why this is not optional</h2>
 * The platform swallows an exception that escapes a repeating task's body but <strong>does not cancel
 * the task</strong>. So a model whose sink throws does not fail once — it fails again every period, and
 * at the 60 Hz simulation rate that is sixty stack traces a second, indefinitely, from one broken
 * model. That fills the disk, pushes every other line out of the log, and costs more time formatting
 * traces than the simulation costs running. The failure is worth exactly one line and a count.
 *
 * <p>The suppressed count is carried into the next report rather than dropped: "this failed 1 800 times"
 * and "this failed once" call for different responses, and without the count they look identical.
 *
 * <h2>Timing comes from the injected clock</h2>
 * Not from {@code System.nanoTime()} directly, so a test can prove the limiting rather than sleep and
 * hope. Interval comparison is a subtraction of two clock readings, which stays correct across a
 * {@code nanoTime} rollover — an absolute deadline would not.
 *
 * <h2>The interval is read per decision, and there is no default</h2>
 * It arrives as a {@link LongSupplier} so an operator who turns the throttle down while a model is
 * failing sees the effect without the logger being rebuilt — which matters because this is the one
 * component an operator reaches for <em>while</em> something is going wrong. One read per
 * {@link #admit}, inside the same monitor that moves the counters, so a change cannot be observed
 * halfway through a decision.
 *
 * <p>No default interval, because a default here would be the hardcoded tunable this injection exists
 * to remove — and the one that would be hardest to notice, since a wrong throttle presents as "the log
 * is quiet", which looks like the thing working.
 */
public final class RateLimitedLogger implements SimulationLogger {

    private final @NotNull SimulationLogger delegate;
    private final @NotNull SimulationClock clock;
    private final @NotNull LongSupplier intervalNanos;

    /** All three guarded by {@code this}. */
    private long lastReportNanos;
    private boolean reportedOnce;
    private long suppressed;
    private long suppressedTotal;

    /** Limits {@code delegate} to one report per {@code intervalNanos}, fixed for its lifetime. */
    public RateLimitedLogger(
            @NotNull SimulationLogger delegate,
            @NotNull SimulationClock clock,
            long intervalNanos
    ) {
        this(delegate, clock, constant(intervalNanos));
    }

    /**
     * Limits {@code delegate} to one report per whatever {@code intervalNanos} answers at the time.
     *
     * @param intervalNanos consulted once per report decision. A negative answer is treated as zero
     *                      rather than throwing: this type is on the failure path, and a throw from the
     *                      error reporter would replace a logged problem with an unlogged one. The
     *                      snapshot a live source hands out has already refused a negative value, so
     *                      the clamp is a backstop
     */
    public RateLimitedLogger(
            @NotNull SimulationLogger delegate,
            @NotNull SimulationClock clock,
            @NotNull LongSupplier intervalNanos
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.intervalNanos = Objects.requireNonNull(intervalNanos, "intervalNanos");
    }

    private static @NotNull LongSupplier constant(long intervalNanos) {
        if (intervalNanos < 0L) {
            throw new IllegalArgumentException("A rate limit interval cannot be negative");
        }
        return () -> intervalNanos;
    }

    /**
     * {@code delegate} if it already rate-limits, otherwise {@code delegate} wrapped in a limiter
     * reading {@code intervalNanos}.
     *
     * <p>Lets {@link SimulationLoop} make limiting mandatory without taking the choice of interval away
     * from a caller who cares: pass a plain logger and get the configured interval, pass a limiter you
     * built yourself and keep it. Wrapping a limiter in a limiter would not be wrong, merely confusing —
     * the inner one would never see enough traffic to fire, so the effective interval would silently be
     * the outer one.
     */
    public static @NotNull RateLimitedLogger wrapping(
            @NotNull SimulationLogger delegate,
            @NotNull SimulationClock clock,
            @NotNull LongSupplier intervalNanos
    ) {
        if (delegate instanceof RateLimitedLogger limited) {
            return limited;
        }
        return new RateLimitedLogger(delegate, clock, intervalNanos);
    }

    @Override
    public void error(@NotNull String message, @NotNull Throwable thrown) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(thrown, "thrown");

        final String decorated = admit(message);
        if (decorated == null) {
            return;
        }
        // Delegated outside the lock: the delegate formats a stack trace and writes to a file, and
        // holding a monitor across that would serialise every region thread that happens to report.
        this.delegate.error(decorated, thrown);
    }

    /**
     * The message to report, or {@code null} if this one is being suppressed.
     *
     * <p>Synchronised rather than built out of atomics because the decision and the two counters have to
     * move together — and because this is only reached when something has already failed, so the cost of
     * a monitor is irrelevant next to the cost of the failure. Plain fields would not do: the loop has
     * no thread affinity, so successive iterations may report from different threads.
     */
    private synchronized @Nullable String admit(@NotNull String message) {
        final long now = this.clock.nanoTime();
        // One read, inside the monitor, so the decision and the counters move under one interval.
        final long interval = intervalNanos();
        if (this.reportedOnce && now - this.lastReportNanos < interval) {
            this.suppressed++;
            this.suppressedTotal++;
            return null;
        }

        final long hidden = this.suppressed;
        this.suppressed = 0L;
        this.lastReportNanos = now;
        this.reportedOnce = true;
        if (hidden == 0L) {
            return message;
        }
        return message + " (" + hidden + " further failure"
                + (hidden == 1L ? "" : "s") + " suppressed since the last report)";
    }

    /** How many reports have been suppressed in total. For diagnostics and tests. */
    public synchronized long suppressedTotal() {
        return this.suppressedTotal;
    }

    /** The interval between permitted reports, in nanoseconds, as currently configured. */
    public long intervalNanos() {
        return Math.max(0L, this.intervalNanos.getAsLong());
    }
}
