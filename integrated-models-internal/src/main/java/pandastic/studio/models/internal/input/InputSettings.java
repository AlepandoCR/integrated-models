package pandastic.studio.models.internal.input;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputTuning;

import java.util.Objects;

/**
 * Everything the input layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.binding.BindingSettings}
 * and {@link pandastic.studio.models.internal.sim.SimulationSettings}: a value a consumer reads once
 * and uses for a whole operation, never a setting per call site. No field has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.config.InputSettings} is a different type with the same
 * simple name: that one is the <em>file</em> — the keys, the comments and the defaults of
 * {@code input.yml} — and this one is the assembled, already-validated bundle the input layer reads.
 * The project has the same deliberate shadow for render, simulation, view, binding and command.
 *
 * @param tuning                     the four durations a combination is judged against
 * @param packetSourceEnabled        whether the server-internals capture source is used when one is
 *                                   available. An operator kill switch rather than a feature flag: the
 *                                   packet source puts a handler in every player's network pipeline,
 *                                   and an operator debugging a connection problem needs to be able to
 *                                   take it out without removing the plugin
 * @param actionCaptureEnabled       whether the five momentary action inputs are observed at all.
 *                                   Observing them means listening to five more event types for every
 *                                   player on the server, which a server running only vehicles with
 *                                   movement-only control schemes is paying for nothing
 * @param pulseVisibilityNanos       how long a momentary input stays visible to a <em>polling</em>
 *                                   reader. See {@link InputTrack} for why this is not zero and not
 *                                   unbounded
 * @param maxBindings                how many bindings the controller will hold. Every binding is
 *                                   evaluated against every publication for every player it is scoped
 *                                   to, so this is the bound on a cost a consumer can impose on the
 *                                   server
 * @param faultLogIntervalNanos      nanoseconds between repeat reports of one failing handler
 * @param faultsBeforeDisable        consecutive failures after which a handler's binding is closed
 */
public record InputSettings(
        @NotNull InputTuning tuning,
        boolean packetSourceEnabled,
        boolean actionCaptureEnabled,
        long pulseVisibilityNanos,
        int maxBindings,
        long faultLogIntervalNanos,
        int faultsBeforeDisable
) {

    public InputSettings {
        Objects.requireNonNull(tuning, "tuning");
        if (pulseVisibilityNanos <= 0L) {
            throw new IllegalArgumentException(
                    "pulseVisibilityNanos must be positive but was " + pulseVisibilityNanos);
        }
        if (maxBindings < 1) {
            throw new IllegalArgumentException("maxBindings must be at least 1 but was " + maxBindings);
        }
        if (faultLogIntervalNanos <= 0L) {
            throw new IllegalArgumentException(
                    "faultLogIntervalNanos must be positive but was " + faultLogIntervalNanos);
        }
        if (faultsBeforeDisable < 1) {
            throw new IllegalArgumentException(
                    "faultsBeforeDisable must be at least 1 but was " + faultsBeforeDisable);
        }
    }
}
