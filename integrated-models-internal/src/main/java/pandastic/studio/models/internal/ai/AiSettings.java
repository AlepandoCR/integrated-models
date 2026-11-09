package pandastic.studio.models.internal.ai;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiBounds;

import java.util.Objects;

/**
 * Everything the AI layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.render.RenderSettings},
 * {@link pandastic.studio.models.internal.sim.SimulationSettings} and
 * {@link pandastic.studio.models.internal.binding.BindingSettings}: a value a consumer reads once and
 * uses for a whole operation, never a setting per call site. No field has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.config.AiSettings} is a different type with the same simple
 * name: that one is the <em>file</em> — the keys, the comments and the defaults — and this one is the
 * assembled, already-validated bundle this layer reads. The project has the same deliberate shadow for
 * render and simulation, and wiring code needs one qualified name.
 *
 * @param bounds              the limits a plan is validated against; see {@link AiBounds}
 * @param faultLogIntervalNanos minimum interval between two reports of a failing body. A body that
 *                            throws throws every tick, so without a throttle one broken behaviour is
 *                            twenty stack traces a second per creature — which fills the disk and
 *                            costs more time formatting traces than the AI costs running. Nanoseconds
 *                            because that is the unit a monotonic clock compares in; the file says
 *                            seconds
 * @param faultsBeforeDisable consecutive failures after which a body is stopped. A behaviour that
 *                            cannot run is better removed than retried forever: left in place it keeps
 *                            being the highest-priority candidate that fails, so nothing below it ever
 *                            gets a turn and the creature is stuck with no symptom beyond the log
 */
public record AiSettings(
        @NotNull AiBounds bounds,
        long faultLogIntervalNanos,
        int faultsBeforeDisable
) {

    public AiSettings {
        Objects.requireNonNull(bounds, "bounds");
        if (faultLogIntervalNanos < 0L) {
            throw new IllegalArgumentException(
                    "faultLogIntervalNanos must not be negative but was " + faultLogIntervalNanos);
        }
        if (faultsBeforeDisable < 1) {
            // Zero would stop a body before it had failed at all, which is indistinguishable from a
            // plan whose behaviours never start.
            throw new IllegalArgumentException("faultsBeforeDisable must be at least 1 but was "
                    + faultsBeforeDisable + "; a body has to be allowed to fail once before it can be"
                    + " said to have failed too often");
        }
    }
}
