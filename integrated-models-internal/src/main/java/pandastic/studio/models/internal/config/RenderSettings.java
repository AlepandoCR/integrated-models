package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code render.yml}: how often a pose leaves the server, and how little a bone has to
 * move before it is worth a packet.
 *
 * <p>The emission rate is the one value in this project where turning it <em>up</em> makes things
 * worse. A display entity consumes its pending-update flags exactly once per client tick, so a second
 * packet inside the same tick does not add an interpolation step — it only replaces the target the
 * single step will aim at. Sixty updates a second therefore produce no more visible state changes than
 * twenty; they discard two thirds and pay full bandwidth for it. The protocol ceiling of twenty is a
 * fact about the client, not a preference, which is why it stays a constant in the api and only shows
 * up here as the bound {@link ConfigMapper} refuses to let {@link #emissionHz()} cross.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.render.RenderSettings} is a different type with the same
 * simple name: it is the narrow, already-validated value the render package reads once per step. This
 * one is the <em>file</em> — the keys, the comments and the defaults. Nothing outside this package
 * should ever need to name this interface; what the rest of the plugin sees is
 * {@link ConfigSnapshot.RenderOptions}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "RenderConfig", format = ConfigFormat.YAML)
public interface RenderSettings {

    /**
     * Pose updates sent per second.
     *
     * <p>Capped at the client tick rate, because that is how often a display entity can start a new
     * interpolation at all. Lowering it costs nothing visible as long as the interpolation window
     * stretches to match: the client re-evaluates interpolation progress every rendered frame using
     * the partial tick, so one update spread over a window yields as many intermediate poses as the
     * viewer's monitor can draw.
     *
     * @return the emission rate, in hertz
     */
    @ConfigValue(comment = "Pose updates sent per second. Capped at the client tick rate of 20, above which extra packets are discarded unseen. Lower is cheaper and, with a matched window, looks identical.")
    default int emissionHz() {
        return 20;
    }

    /**
     * Smallest interpolation window that still interpolates, in client ticks.
     *
     * <p>Zero would mean "snap", which is a different mode rather than a shorter window, so this is
     * the floor rather than a tuning value.
     *
     * @return the minimum interpolation window, in ticks
     */
    @ConfigValue(comment = "Smallest interpolation window in client ticks. Zero would mean 'snap', which is a different mode rather than a shorter window, so one is the floor.")
    default int minInterpolationTicks() {
        return 1;
    }

    /**
     * Largest interpolation window worth sending, in client ticks.
     *
     * <p>The server re-sends an entity's position unprompted every 60 ticks, restarting the client's
     * countdown, so a window at or beyond that can never complete. Raising this past 59 buys a window
     * the client will always abandon.
     *
     * @return the maximum interpolation window, in ticks
     */
    @ConfigValue(comment = "Largest interpolation window in client ticks. The server re-sends an entity's position every 60 ticks and restarts the countdown, so a window of 60 or more never completes.")
    default int maxInterpolationTicks() {
        return 59;
    }

    /**
     * Whether to send an interpolation window one tick longer than the emission interval.
     *
     * <p>Matching the window to the interval is optimal; one tick over buys tolerance for a late packet
     * at the cost of a tick of lag. Off by default because the lag is paid on every update and the
     * tolerance is only used when a packet is late.
     *
     * @return {@code true} to overshoot the window by one tick
     */
    @ConfigValue(comment = "Send an interpolation window one tick longer than the emission interval. Buys tolerance for a late packet at the cost of a tick of lag on every update.")
    default boolean jitterTolerance() {
        return false;
    }

    /**
     * Angular change below which a bone counts as unmoved and is not re-emitted, in radians.
     *
     * <p>Roughly 0.006 degrees, which at a one-block lever arm displaces the bone by about 1e-4 blocks
     * — two orders of magnitude below anything a client can draw. It must sit at or above the solver's
     * per-substep rest threshold in {@code physics.yml}, so that a rig the physics considers settled is
     * a rig this tracker considers silent even after several substeps of float noise;
     * {@link ConfigMapper} enforces that across the two files.
     *
     * @return the dirty-tracking epsilon, in radians
     */
    @ConfigValue(comment = "Angular change in radians below which a bone is not re-emitted. Must stay at or above restEpsilonRadians in physics.yml, or a settled model keeps sending packets forever.")
    default float boneDirtyEpsilonRadians() {
        return 1.0E-4F;
    }

    /**
     * How many model bone names an unbindable-rig message lists before it summarises the rest.
     *
     * <p>A bone resolution failure is reported with the model's actual bone names, because the useful
     * half of "bone 'tail' is missing" is the list of names that are not missing. A model with hundreds
     * of bones would otherwise put a page of text in the console for one typo.
     *
     * @return the number of names to list in full
     */
    @ConfigValue(comment = "How many model bone names a rig binding error lists before summarising the rest. Enough to spot a typo, few enough not to put a page of text in the console.")
    default int boneNamesInMessage() {
        return 32;
    }
}
