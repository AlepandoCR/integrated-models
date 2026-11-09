package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

/**
 * Where a simulation failure goes.
 *
 * <p>A seam rather than a direct {@code ComponentLogger} call, for the same reason
 * {@link SimulationScheduler} is one: the rate limiter in {@link RateLimitedLogger} is real logic and
 * has to be testable, and a test cannot usefully implement Adventure's logger. The Adventure binding is
 * one delegating method in {@link ComponentSimulationLogger}.
 *
 * <p>Implementations are called from the simulation thread and must be thread-safe.
 */
@FunctionalInterface
public interface SimulationLogger {

    /**
     * Reports that something threw.
     *
     * @param message what was being attempted, naming the rig where one is known
     * @param thrown  the failure
     */
    void error(@NotNull String message, @NotNull Throwable thrown);

    /** Discards everything. For tests and for a caller that has its own reporting. */
    @NotNull SimulationLogger DISCARDING = (message, thrown) -> {
    };
}
