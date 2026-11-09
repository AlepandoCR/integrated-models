package pandastic.studio.models.internal.ai;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.nms.AiFaultSink;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Lets one report per body per interval through, counts the rest, and stops a body that keeps failing.
 *
 * <h2>Why both halves are necessary</h2>
 * A behaviour runs every tick, so a behaviour that throws throws every tick. Two different things go
 * wrong with that and they need two different answers.
 *
 * <p>The <strong>log</strong> goes first. Twenty stack traces a second per creature fills the disk,
 * pushes every other line out of the console, and costs more time formatting traces than the AI layer
 * costs running. One line per interval, with the suppressed count carried into the next one, because
 * "this failed 1 800 times" and "this failed once" call for different responses and look identical
 * without the number.
 *
 * <p>The <strong>creature</strong> goes second, and this is the half that is easy to miss. A behaviour
 * that fails is still the highest-priority candidate that <em>can</em> be considered, so the brain keeps
 * choosing it and nothing below it ever gets a turn. The creature is not merely logging — it is stuck,
 * and will be until the server restarts. So after a configured number of consecutive failures the body
 * is stopped and the creature carries on with the rest of its plan, which is a degraded creature rather
 * than a frozen one.
 *
 * <h2>Consecutive, not cumulative</h2>
 * A success resets the count. A behaviour that fails once an hour because of something transient should
 * not be stopped on its tenth hour; one that fails ten times in ten ticks is broken. Only the failure
 * path calls {@link #failed}, so the reset has to be explicit — {@link #succeeded} — and the adapter
 * calls it after a body returns normally.
 *
 * <h2>Timing comes from an injected clock</h2>
 * Not {@code System.nanoTime()} directly, so a test can prove the throttling rather than sleep and
 * hope. Interval comparison is a subtraction of two readings, which stays correct across a
 * {@code nanoTime} rollover where an absolute deadline would not.
 *
 * <h2>Threading</h2>
 * Shared across every region thread that has a creature with a plan on it, so the counters are held
 * under a monitor. That is affordable precisely because the only path reaching them is one where
 * something is already broken; the success path takes the same monitor but does nothing but a map
 * removal, and only for a body that had previously failed.
 */
public final class ThrottledFaultSink implements AiFaultSink {

    /** What to do with a report that got past the throttle. */
    @FunctionalInterface
    public interface Reporter {

        /**
         * Reports one failure.
         *
         * @param message   a complete sentence naming the kind, the body and the suppressed count
         * @param failure   what was thrown
         */
        void report(@NotNull String message, @NotNull Throwable failure);
    }

    /** Per-body state, guarded by the enclosing monitor. */
    private static final class Fault {

        private long consecutive;
        private long suppressed;
        private long lastReportNanos;
        private boolean reportedOnce;
    }

    private final @NotNull Reporter reporter;
    private final @NotNull AiSettingsSource settings;
    private final @NotNull LongSupplier clock;

    /**
     * Keyed by kind and name, so a behaviour and a sensor that happen to share a name are counted
     * apart. Entries are removed on success, so a healthy server's map is empty.
     *
     * <p>Guarded by {@code this}.
     */
    private final @NotNull Map<String, Fault> faults = new HashMap<>();

    /**
     * @param reporter where a report that got past the throttle goes
     * @param settings consulted per decision, so an operator can retune during an incident
     * @param clock    a monotonic nanosecond clock, injected so the throttling is testable
     */
    public ThrottledFaultSink(
            @NotNull Reporter reporter,
            @NotNull AiSettingsSource settings,
            @NotNull LongSupplier clock
    ) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized boolean failed(
            @NotNull String kind,
            @NotNull String name,
            @NotNull Throwable failure
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(failure, "failure");

        final AiSettings current = this.settings.current();
        final Fault fault = this.faults.computeIfAbsent(key(kind, name), ignored -> new Fault());
        fault.consecutive++;

        final boolean keepRunning = fault.consecutive < current.faultsBeforeDisable();
        final long now = this.clock.getAsLong();

        // A body that is being stopped is always reported, whatever the throttle says: it is the last
        // thing anybody will hear about that behaviour, and suppressing it would leave a creature
        // quietly missing part of its plan.
        if (!keepRunning || admits(fault, now, current.faultLogIntervalNanos())) {
            this.reporter.report(describe(kind, name, fault, keepRunning), failure);
            fault.lastReportNanos = now;
            fault.reportedOnce = true;
            fault.suppressed = 0L;
        } else {
            fault.suppressed++;
        }

        if (!keepRunning) {
            // Nothing will call failed() for this body again, so the entry is dead weight.
            this.faults.remove(key(kind, name));
        }
        return keepRunning;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cheap for the overwhelmingly common case: a body that has never failed has no entry, so this
     * is a map lookup that misses. The monitor is still taken, which is the price of the counters being
     * correct across region threads and is one uncontended lock acquisition per body invocation.
     */
    @Override
    public synchronized void succeeded(@NotNull String kind, @NotNull String name) {
        this.faults.remove(key(kind, name));
    }

    /**
     * How many consecutive failures {@code name} currently has.
     *
     * <p>For tests and for a diagnostic command. Zero for a body that has never failed or has since
     * succeeded.
     *
     * @param kind {@link AiFaultSink#BEHAVIOUR} or {@link AiFaultSink#SENSOR}
     * @param name the body's name
     * @return the consecutive failure count
     */
    public synchronized long consecutiveFailures(@NotNull String kind, @NotNull String name) {
        final Fault fault = this.faults.get(key(kind, name));
        return fault == null ? 0L : fault.consecutive;
    }

    /**
     * Whether the throttle lets this report through.
     *
     * <p>The first failure of a body always goes through, because the whole point of a throttle is to
     * suppress repeats and the first one is not a repeat.
     */
    private static boolean admits(@NotNull Fault fault, long now, long intervalNanos) {
        return !fault.reportedOnce || now - fault.lastReportNanos >= intervalNanos;
    }

    private static @NotNull String key(@NotNull String kind, @NotNull String name) {
        return kind + '/' + name;
    }

    /** One sentence carrying the kind, the name, the suppressed count and the verdict. */
    private static @NotNull String describe(
            @NotNull String kind,
            @NotNull String name,
            @NotNull Fault fault,
            boolean keepRunning
    ) {
        final StringBuilder message = new StringBuilder("The ").append(kind).append(" '")
                .append(name).append("' threw");
        if (fault.suppressed > 0L) {
            message.append(" (").append(fault.suppressed)
                    .append(" further failure(s) suppressed since the last report)");
        }
        if (keepRunning) {
            message.append(". It will keep being run; failure ").append(fault.consecutive);
        } else {
            message.append(". It has now failed ").append(fault.consecutive)
                    .append(" times in a row and will not be run again, so the creature is missing")
                    .append(" that part of its plan until the plan is reinstalled");
        }
        return message.toString();
    }
}
