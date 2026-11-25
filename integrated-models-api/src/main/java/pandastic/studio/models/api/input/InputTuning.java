package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The four durations a combination is judged against, as one immutable value.
 *
 * <h2>Why they are not on the combination</h2>
 * Because they are the operator's, not the consumer's. How long two taps may be apart and still count
 * as a double tap is a statement about human hands and about the server's latency, which the plugin
 * shipping a vehicle cannot know and the operator can measure. So a combination declares its
 * <em>shape</em> and this declares the <em>timing</em>, and the two meet in
 * {@link InputCombination#matches(InputTrace, InputTuning)}.
 *
 * <p>A consumer that genuinely needs its own figure can still state one —
 * {@link InputCombination#doubleTap(InputKey, long)} and {@link InputCombination#hold(InputKey, long)}
 * take an explicit duration, and that is the same kind of declaration a vehicle profile's coefficients
 * are: a statement about <em>this</em> control scheme rather than about the server. What is not
 * available is a default written in a source file, which is why there is no no-argument constructor
 * here and no constant holding "the usual" window.
 *
 * <h2>Nanoseconds</h2>
 * The file is in milliseconds, because an operator thinks in milliseconds; the conversion happens once
 * where the file is read. Everything downstream compares against a monotonic clock, and a millisecond
 * figure would have to be widened on every comparison.
 *
 * @param doubleTapWindowNanos longest gap between two presses of a key that still counts as a double
 *                             tap
 * @param holdThresholdNanos   how long a key must be down for an unqualified
 *                             {@link InputCombination#hold(InputKey)} to match
 * @param repeatIntervalNanos  shortest interval at which a still-matching combination is dispatched
 *                             again. A held combination matches on every publication, which for a
 *                             movement key is every client tick, so without this a handler bound to
 *                             "sneak held" would run twenty times a second
 * @param staleAfterNanos      how long after the last observation a player's input stops being
 *                             trusted. A client that disconnects mid-key never sends the release, so
 *                             the last state the server saw has that key held forever
 */
public record InputTuning(
        long doubleTapWindowNanos,
        long holdThresholdNanos,
        long repeatIntervalNanos,
        long staleAfterNanos
) {

    /**
     * Nanoseconds in a millisecond.
     *
     * <p>A mathematical fact — an SI unit conversion — in the same category as
     * {@code NANOS_PER_SECOND}.
     */
    public static final long NANOS_PER_MILLISECOND = 1_000_000L;

    public InputTuning {
        requirePositive(doubleTapWindowNanos, "doubleTapWindowNanos");
        requirePositive(holdThresholdNanos, "holdThresholdNanos");
        requirePositive(repeatIntervalNanos, "repeatIntervalNanos");
        requirePositive(staleAfterNanos, "staleAfterNanos");
    }

    private static void requirePositive(long value, @NotNull String name) {
        if (value <= 0L) {
            // Zero is not a weaker setting, it is a different behaviour: a zero double-tap window can
            // never match and a zero repeat interval dispatches on every publication, which is the
            // flood this field exists to bound. Refusing it keeps the configured number and the
            // observed behaviour the same statement.
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * The tuning these four millisecond figures describe.
     *
     * <p>The form the configuration layer uses, and the form a test reads: {@code ofMillis(250, 500,
     * 250, 1500)} is legible where four nine-digit nanosecond literals are not.
     *
     * @param doubleTapWindowMillis the double-tap window
     * @param holdThresholdMillis   the default hold threshold
     * @param repeatIntervalMillis  the repeat interval
     * @param staleAfterMillis      the staleness window
     * @return the tuning
     */
    @Contract(value = "_, _, _, _ -> new", pure = true)
    public static @NotNull InputTuning ofMillis(
            long doubleTapWindowMillis,
            long holdThresholdMillis,
            long repeatIntervalMillis,
            long staleAfterMillis
    ) {
        return new InputTuning(
                doubleTapWindowMillis * NANOS_PER_MILLISECOND,
                holdThresholdMillis * NANOS_PER_MILLISECOND,
                repeatIntervalMillis * NANOS_PER_MILLISECOND,
                staleAfterMillis * NANOS_PER_MILLISECOND);
    }
}
