package pandastic.studio.models.internal.menu;

import pandastic.studio.models.internal.sim.SimulationClock;

/**
 * A clock the test advances by hand, so a session timeout is an assertion rather than a sleep.
 *
 * <p>Starts at a large negative value on purpose: {@link System#nanoTime()}'s origin is arbitrary and
 * genuinely is negative on some JVMs, so an expiry test written as {@code now >= last + timeout} would
 * pass against a zero-based clock and overflow in production. The session store subtracts instead, and
 * this is what proves it.
 */
final class SteppedClock implements SimulationClock {

    private long nanos = -9_000_000_000L;

    @Override
    public long nanoTime() {
        return this.nanos;
    }

    /** Moves the clock forward by {@code seconds}. */
    void advanceSeconds(double seconds) {
        this.nanos += Math.round(seconds * 1_000_000_000.0D);
    }
}
