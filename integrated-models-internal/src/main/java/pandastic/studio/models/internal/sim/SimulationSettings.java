package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.render.EmissionCadence;

import java.util.Objects;

/**
 * Everything the simulation layer reads out of configuration, as one immutable snapshot.
 *
 * <h2>Why the solver is in here</h2>
 * It looks like a collaborator rather than a setting, and it is both. A solver is built from a
 * {@link pandastic.studio.models.api.physics.PhysicsTuning}, and tuning is configuration — so reloading
 * the calibration means <em>replacing the solver</em>, since the solver is immutable by design and its
 * step size is baked into every {@link pandastic.studio.models.api.physics.PhysicsState} it has created.
 * Carrying it in the snapshot is what lets {@link ModelSimulation} notice that the calibration moved and
 * rebuild its state, instead of integrating a 60 Hz state with a 30 Hz integrator and producing motion
 * that is wrong in a way nothing throws about.
 *
 * <p>It also keeps {@code ModelSimulation} solver-agnostic: the snapshot hands it a
 * {@link PhysicsSolver}, so nothing in this package names the real one.
 *
 * <h2>Rate coherence is validated here, which is what makes a reload refusable</h2>
 * A cadence decides how many steps separate two emissions, so a cadence built for one simulation rate
 * driven at another emits at the wrong frequency and sends an interpolation window that does not match
 * the gap it covers — motion that eases for part of the interval and then sits still, which reads as the
 * server being laggy rather than as a misconfiguration. Checking it in this constructor means a bad
 * combination is refused while the snapshot is being built, i.e. the whole reload fails and the running
 * configuration stays in force, rather than being discovered one model at a time afterwards.
 *
 * <h2>Per-model overrides come from per-model sources</h2>
 * A snapshot carries one cadence. A model that needs its own emission rate is given its own
 * {@link SimulationSettingsSource} rather than a mutable field here — which also keeps the
 * read-once-per-step contract intact, because a per-model source is read by exactly one simulation.
 *
 * @param solver                the configured solver every registered simulation integrates with.
 *                              Immutable and stateless, so one instance serves the whole server
 * @param simulationHz          steps per second of the loop, and the rate every cadence must be built
 *                              for. A stability requirement set by the stiffest spring the bounds
 *                              permit, not a smoothness one
 * @param cadence               how often a finished pose goes on the wire, and the interpolation window
 *                              sent with it
 * @param driverBounds          the teleport filter {@link DriverSampler} applies to a position delta
 * @param errorLogIntervalNanos how often a repeated simulation failure may be reported. The platform
 *                              keeps a throwing repeating task alive, so an unlimited logger here is one
 *                              stack trace per period forever; zero means report everything
 */
public record SimulationSettings(
        @NotNull PhysicsSolver solver,
        int simulationHz,
        @NotNull EmissionCadence cadence,
        @NotNull DriverBounds driverBounds,
        long errorLogIntervalNanos
) {

    public SimulationSettings {
        Objects.requireNonNull(solver, "solver");
        Objects.requireNonNull(cadence, "cadence");
        Objects.requireNonNull(driverBounds, "driverBounds");
        if (simulationHz < 1) {
            throw new IllegalArgumentException(
                    "A simulation rate of " + simulationHz + " Hz is not a rate");
        }
        if (cadence.emissionHz() * cadence.stepsPerEmission() != simulationHz) {
            throw new IllegalArgumentException(
                    "Cadence " + cadence + " is built for "
                            + cadence.emissionHz() * cadence.stepsPerEmission()
                            + " Hz but the simulation rate is " + simulationHz + " Hz");
        }
        if (errorLogIntervalNanos < 0L) {
            throw new IllegalArgumentException(
                    "errorLogIntervalNanos cannot be negative but was " + errorLogIntervalNanos);
        }
    }

    /**
     * The interval between two loop iterations, in nanoseconds.
     *
     * <p>Derived rather than configured, and in nanoseconds rather than milliseconds: 60 Hz is 16.67 ms,
     * and rounding that to a whole millisecond is a 2% rate error in whichever direction it rounded.
     */
    public long periodNanos() {
        return NANOS_PER_SECOND / this.simulationHz;
    }

    /**
     * Nanoseconds in a second.
     *
     * <p><strong>A unit definition, not a tunable.</strong> It is the SI relationship between the two
     * units this record mixes — a configured rate in hertz and a scheduler period in nanoseconds — so
     * there is nothing an operator could usefully set it to.
     */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
}
