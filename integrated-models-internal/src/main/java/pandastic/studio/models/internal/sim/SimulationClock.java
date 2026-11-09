package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

/**
 * The monotonic clock the simulation measures a step with.
 *
 * <h2>Why the loop may not count iterations</h2>
 * The scheduler that drives the loop <strong>drops missed iterations rather than replaying them</strong>,
 * and the phase shifts when it does. So the iteration number is not a clock: after one stall, step
 * {@code n} is no longer at {@code n/rate} seconds, and a solver fed a nominal timestep integrates a
 * spring at a speed the world no longer runs at. Every delta therefore comes from differencing this
 * clock, never from multiplying a step count by a period.
 *
 * <p>Nanoseconds, not milliseconds, for two reasons. The source is monotonic, so it is immune to a
 * system clock being stepped backwards by NTP — which with a wall clock would hand the solver a negative
 * delta. And the resolution matters: the OS timer is granular to roughly 1 ms on Linux but
 * <strong>1–16 ms on Windows</strong>, which against a 16.67 ms period is large relative jitter. A
 * millisecond-resolution clock would quantise that jitter into deltas of 0 and 16 ms rather than merely
 * reporting it.
 *
 * <h2>Why it is an interface</h2>
 * So the loop's timing behaviour is unit-testable. A test advances a fake clock by whatever irregular
 * amounts it wants and asserts on what the solver was told, without a server, a sleep, or a flaky
 * tolerance.
 */
@FunctionalInterface
public interface SimulationClock {

    /** Nanoseconds since an arbitrary origin. Only differences are meaningful; the value may be negative. */
    long nanoTime();

    /** The real clock. {@link System#nanoTime()} is monotonic and is what production uses. */
    @NotNull SimulationClock SYSTEM = System::nanoTime;
}
