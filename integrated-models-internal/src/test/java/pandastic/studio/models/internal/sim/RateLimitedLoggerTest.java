package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitedLoggerTest {

    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private final FakeClock clock = new FakeClock();
    private final RecordingLogger delegate = new RecordingLogger();
    private final RateLimitedLogger limiter =
            new RateLimitedLogger(this.delegate, this.clock, INTERVAL_NANOS);

    private void report(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            this.limiter.error("step failed", new IllegalStateException("boom"));
        }
    }

    @Test
    void theFirstFailureAlwaysGetsThrough() {
        report(1);

        assertAll(
                () -> assertEquals(1, this.delegate.count()),
                () -> assertEquals("step failed", this.delegate.last(),
                        "a lone failure is reported verbatim, with nothing to count"),
                () -> assertEquals(0L, this.limiter.suppressedTotal()));
    }

    @Test
    void everythingInsideTheIntervalIsSuppressed() {
        report(60);

        assertAll(
                // The platform keeps a throwing repeating task alive, so one broken model at 60 Hz is
                // sixty stack traces a second until somebody notices.
                () -> assertEquals(1, this.delegate.count()),
                () -> assertEquals(59L, this.limiter.suppressedTotal()));
    }

    @Test
    void reportsAgainOnceTheIntervalHasElapsedAndCarriesTheCount() {
        report(60);
        this.clock.advanceNanos(INTERVAL_NANOS);
        report(1);

        assertAll(
                () -> assertEquals(2, this.delegate.count()),
                // "this failed 59 times" and "this failed once" call for different responses, and
                // without the count they look identical.
                () -> assertTrue(this.delegate.last().contains("59 further failures suppressed"),
                        this.delegate.last()),
                () -> assertEquals(59L, this.limiter.suppressedTotal()));
    }

    @Test
    void theCountIsPerReportNotCumulative() {
        report(5);
        this.clock.advanceNanos(INTERVAL_NANOS);
        report(3);
        this.clock.advanceNanos(INTERVAL_NANOS);
        report(1);

        assertAll(
                () -> assertEquals(3, this.delegate.count()),
                () -> assertTrue(this.delegate.messages().get(1).contains("4 further failures"),
                        this.delegate.messages().get(1)),
                () -> assertTrue(this.delegate.messages().get(2).contains("2 further failures"),
                        this.delegate.messages().get(2)),
                () -> assertEquals(6L, this.limiter.suppressedTotal()));
    }

    @Test
    void oneSuppressedFailureIsSingular() {
        report(2);
        this.clock.advanceNanos(INTERVAL_NANOS);
        report(1);

        assertTrue(this.delegate.last().contains("1 further failure suppressed"),
                this.delegate.last());
    }

    @Test
    void justInsideTheIntervalIsStillSuppressed() {
        report(1);
        this.clock.advanceNanos(INTERVAL_NANOS - 1L);
        report(1);
        assertEquals(1, this.delegate.count());

        this.clock.advanceNanos(1L);
        report(1);
        assertEquals(2, this.delegate.count());
    }

    @Test
    void survivesANanoTimeRollover() {
        // nanoTime's origin is arbitrary and the counter does wrap. An absolute deadline would be
        // past-due for the next 292 years after a wrap; differencing two readings stays correct.
        this.clock.set(Long.MAX_VALUE - INTERVAL_NANOS / 2L);
        report(1);
        this.clock.advanceNanos(INTERVAL_NANOS / 4L);
        report(1);
        assertEquals(1, this.delegate.count(), "still inside the interval, across the wrap");

        this.clock.advanceNanos(INTERVAL_NANOS);
        report(1);
        assertEquals(2, this.delegate.count(), "and reports again once it has really elapsed");
    }

    @Test
    void anIntervalOfZeroLetsEverythingThrough() {
        final RateLimitedLogger unlimited =
                new RateLimitedLogger(this.delegate, this.clock, 0L);

        for (int attempt = 0; attempt < 5; attempt++) {
            unlimited.error("step failed", new IllegalStateException("boom"));
        }

        assertAll(
                () -> assertEquals(5, this.delegate.count()),
                () -> assertEquals(0L, unlimited.suppressedTotal()));
    }

    @Test
    void wrappingAnAlreadyLimitedLoggerDoesNotLimitItTwice() {
        // Double wrapping would not be wrong, only confusing: the inner limiter would never see enough
        // traffic to fire, so the effective interval would silently be the outer one.
        assertAll(
                () -> assertSame(this.limiter, RateLimitedLogger.wrapping(
                        this.limiter, this.clock, () -> 7L)),
                // And a plain logger is wrapped at the interval it was handed, not at one this class
                // kept for itself.
                () -> assertEquals(7L, RateLimitedLogger.wrapping(
                        this.delegate, this.clock, () -> 7L).intervalNanos()));
    }

    @Test
    void readsTheIntervalFromTheSupplierOnEveryDecision() {
        // The throttle is the one setting an operator reaches for *while* something is going wrong, so
        // a limiter that latched it at construction would be the least useful place to cache.
        final long[] interval = {INTERVAL_NANOS};
        final RateLimitedLogger following =
                new RateLimitedLogger(this.delegate, this.clock, () -> interval[0]);

        following.error("first", new IllegalStateException("boom"));
        this.clock.advanceNanos(INTERVAL_NANOS / 2L);
        following.error("still inside", new IllegalStateException("boom"));
        assertEquals(1, this.delegate.count());

        // Halve the interval. The half-elapsed gap is now a whole one.
        interval[0] = INTERVAL_NANOS / 4L;
        following.error("now outside", new IllegalStateException("boom"));

        assertAll(
                () -> assertEquals(2, this.delegate.count(),
                        "shortening the interval has to take effect immediately"),
                () -> assertEquals(INTERVAL_NANOS / 4L, following.intervalNanos()));
    }

    @Test
    void anIntervalThatGrowsStartsSuppressingAgain() {
        final long[] interval = {0L};
        final RateLimitedLogger following =
                new RateLimitedLogger(this.delegate, this.clock, () -> interval[0]);

        following.error("one", new IllegalStateException("boom"));
        following.error("two", new IllegalStateException("boom"));
        assertEquals(2, this.delegate.count(), "zero means report everything");

        interval[0] = INTERVAL_NANOS;
        following.error("three", new IllegalStateException("boom"));

        assertEquals(2, this.delegate.count(), "and raising it quietens the next one");
    }

    @Test
    void refusesANegativeFixedIntervalAndANullDelegate() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RateLimitedLogger(this.delegate, this.clock, -1L)),
                () -> assertThrows(NullPointerException.class,
                        () -> new RateLimitedLogger(null, this.clock, INTERVAL_NANOS)),
                () -> assertThrows(NullPointerException.class,
                        () -> this.limiter.error("message", null)),
                // A supplier is trusted rather than validated, because this type is on the failure
                // path and a throw here would replace a logged problem with an unlogged one. A
                // negative answer degrades to "report everything".
                () -> assertEquals(0L,
                        new RateLimitedLogger(this.delegate, this.clock, () -> -5L).intervalNanos()));
    }
}
