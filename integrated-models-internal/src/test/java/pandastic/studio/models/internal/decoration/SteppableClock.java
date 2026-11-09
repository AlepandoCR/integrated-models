package pandastic.studio.models.internal.decoration;

import pandastic.studio.models.internal.sim.SimulationClock;

/**
 * A clock the test advances by hand.
 *
 * <p>Starts at a large negative value on purpose: {@link System#nanoTime()}'s origin is arbitrary and
 * genuinely is negative on some JVMs, so anything that assumed a positive or zero-based reading would
 * pass against a clock starting at zero and fail in production. The ambient field's phase is measured
 * from the first pass for exactly that reason, and this is what proves it.
 */
final class SteppableClock implements SimulationClock {

    private long nanos = -7_000_000_000L;

    @Override
    public long nanoTime() {
        return this.nanos;
    }

    void advanceNanos(long by) {
        this.nanos += by;
    }

    void advanceSeconds(double seconds) {
        advanceNanos(Math.round(seconds * 1_000_000_000.0D));
    }
}
