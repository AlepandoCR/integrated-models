package pandastic.studio.models.internal.input;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Where a handler's exception goes, and who decides whether to keep calling it.
 *
 * <h2>Why a handler that throws must not be allowed to throw</h2>
 * A handler runs inside a player's input. On a region thread that means inside a tick; on a network
 * thread it means inside the read loop of that player's connection. An exception escaping it fails
 * whatever is above it, and because input arrives as often as the client sends it, it fails it again
 * immediately. A consumer's bug would become this plugin's repeating stack trace on somebody else's
 * region thread.
 *
 * <h2>Why reporting and deciding are one call</h2>
 * The throttle interval and the failure count are configuration, so only this side can decide; and the
 * count is changed by the act of reporting. An interface that asked "should I keep going?" separately
 * would be asking about a count the previous call had already moved.
 *
 * <h2>Threading</h2>
 * Called from any region thread and from network threads, concurrently. The implementation below holds
 * its per-binding counters in a {@link ConcurrentHashMap} and mutates one entry under its own monitor,
 * which is affordable because the only path that reaches it is a path where something is already
 * broken.
 */
public interface InputFaultSink {

    /**
     * Reports that the handler of {@code binding} threw, and answers whether to keep calling it.
     *
     * @param binding what the binding matches, as its description reads
     * @param failure what the handler threw
     * @return {@code true} to keep calling it, {@code false} once it has failed often enough that the
     *         binding should be closed
     */
    boolean failed(@NotNull String binding, @NotNull Throwable failure);

    /** A sink that reports nothing and never gives up. For tests that are asserting on something else. */
    static @NotNull InputFaultSink ignoring() {
        return (binding, failure) -> true;
    }

    /**
     * The production sink: report at most once per configured interval per binding, and stop a binding
     * that has failed as many times in a row as the configuration permits.
     *
     * <p>Consecutive failures, not total: a handler that threw once a week ago and has worked since is
     * not broken, and a counter that never reset would eventually close every binding on a long-running
     * server. A successful delivery clears the count through {@link #succeeded(String)}.
     *
     * @param settings where the interval and the limit come from
     * @param clock    the monotonic clock the interval is measured on
     * @param report   where a message and its cause go; the plugin's component logger, wrapped
     * @return the sink
     */
    static @NotNull InputFaultSink throttled(
            @NotNull InputSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull BiConsumer<String, Throwable> report
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(report, "report");
        return new Throttled(settings, clock, report);
    }

    /**
     * Notes that {@code binding}'s handler ran without throwing.
     *
     * <p>Default no-op, because the only implementation that cares is the throttling one and a test
     * sink should not have to say so.
     *
     * @param binding what the binding matches
     */
    default void succeeded(@NotNull String binding) {
        // Nothing to forget by default.
    }

    /**
     * The throttling implementation.
     *
     * <p>A nested class of an interface, so it is public whether or not that was wanted; its
     * constructor is private, which is what actually keeps {@link #throttled} the only way in.
     */
    final class Throttled implements InputFaultSink {

        private final @NotNull InputSettingsSource settings;
        private final @NotNull SimulationClock clock;
        private final @NotNull BiConsumer<String, Throwable> report;
        private final @NotNull ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

        private Throttled(
                @NotNull InputSettingsSource settings,
                @NotNull SimulationClock clock,
                @NotNull BiConsumer<String, Throwable> report
        ) {
            this.settings = settings;
            this.clock = clock;
            this.report = report;
        }

        @Override
        public boolean failed(@NotNull String binding, @NotNull Throwable failure) {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(failure, "failure");

            // One read of the settings for this decision, as the source's contract requires: the
            // interval and the limit have to come from the same generation or a reload between them
            // would throttle against one file and count against another.
            final InputSettings current = this.settings.current();
            final Counter counter = this.counters.computeIfAbsent(binding, ignored -> new Counter());
            final long now = this.clock.nanoTime();

            final boolean shouldReport;
            final int consecutive;
            synchronized (counter) {
                counter.consecutive++;
                consecutive = counter.consecutive;
                shouldReport = counter.lastReportNanos == Counter.NEVER
                        || now - counter.lastReportNanos >= current.faultLogIntervalNanos();
                if (shouldReport) {
                    counter.lastReportNanos = now;
                }
            }

            final boolean keepRunning = consecutive < current.faultsBeforeDisable();
            if (shouldReport || !keepRunning) {
                this.report.accept("An input handler bound to " + binding + " threw ("
                        + consecutive + " consecutive)"
                        + (keepRunning ? "" : "; the binding has been closed"), failure);
            }
            return keepRunning;
        }

        @Override
        public void succeeded(@NotNull String binding) {
            final Counter counter = this.counters.get(binding);
            if (counter == null) {
                return;
            }
            synchronized (counter) {
                counter.consecutive = 0;
            }
        }

        /** Per-binding state. Mutable, and only ever touched under its own monitor. */
        private static final class Counter {

            /** Sentinel for "never reported". Structural; the clock may legitimately report zero. */
            private static final long NEVER = Long.MIN_VALUE;

            private int consecutive;
            private long lastReportNanos = NEVER;
        }
    }
}
