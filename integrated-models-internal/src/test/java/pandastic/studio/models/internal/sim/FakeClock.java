package pandastic.studio.models.internal.sim;

/**
 * A clock the test advances by hand.
 *
 * <p>Starts at a large negative value on purpose: {@link System#nanoTime()}'s origin is arbitrary and
 * genuinely is negative on some JVMs, so anything that assumed a positive or zero-based reading would
 * pass against a clock starting at zero and fail in production.
 */
final class FakeClock implements SimulationClock {

    private long nanos = -4_000_000_000L;

    @Override
    public long nanoTime() {
        return this.nanos;
    }

    void advanceNanos(long nanos) {
        this.nanos += nanos;
    }

    void advanceMillis(long millis) {
        advanceNanos(millis * 1_000_000L);
    }

    void advanceSeconds(double seconds) {
        advanceNanos(Math.round(seconds * 1_000_000_000.0D));
    }

    void set(long nanos) {
        this.nanos = nanos;
    }
}
