package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the simulation layer gets its current {@link SimulationSettings} from.
 *
 * <h2>The seam a reload arrives through</h2>
 * The configuration layer publishes snapshots by swapping an immutable value behind an
 * {@code AtomicReference}; this is the read half of that, narrowed so nothing in this package has to
 * name the configuration layer. A live implementation is a method reference to that getter; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Call it once per step</h2>
 * This is the whole contract, and it is the same rule the driver sample already follows: a step that
 * read the reference twice could straddle a reload and have two of its stages disagree. Concretely,
 * {@link ModelSimulation#step(long)} reads one snapshot and uses that one snapshot's solver, cadence and
 * interpolation window for the entire step — so a reload lands cleanly between two steps, never inside
 * one.
 *
 * <p>Implementations must be safe to call from any thread. The loop body runs on the platform async
 * scheduler with no thread affinity, and {@link DriverSampler} reads a snapshot on the owning region
 * thread, so a given source is read concurrently from several threads as a matter of course.
 *
 * <h2>The solver's identity is part of the contract</h2>
 * Two consecutive calls that describe the same configuration <strong>must return the same
 * {@link SimulationSettings#solver()} instance</strong>. This is not an optimisation request.
 * {@link ModelSimulation#step(long)} detects a reloaded calibration by comparing the solver it is handed
 * against the one it holds by identity, because a {@code PhysicsState} carries limits derived from the
 * step size of the solver that built it and only "the very same solver" is safe to keep a state for.
 *
 * <p>An implementation that allocates a solver per call therefore makes that guard fire on every step,
 * and every firing zeroes the physics state, clears the pose, resets the locomotion and restarts the
 * cadence phase — including the elapsed-time baseline, so the next delta is zero. The result is a model
 * frozen at its bind pose that nothing throws about and no unit test of either class can see, because
 * the defect lives in neither of them. Build the value once per published snapshot and hand the same
 * one out until the snapshot changes.
 */
@FunctionalInterface
public interface SimulationSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull SimulationSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from deliberately unusual values is
     * what distinguishes "this component reads its configuration" from "this component ignores its
     * configuration and the default happened to agree".
     */
    static @NotNull SimulationSettingsSource fixed(@NotNull SimulationSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
