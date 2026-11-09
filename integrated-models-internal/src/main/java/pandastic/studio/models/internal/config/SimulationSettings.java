package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code simulation.yml}: the asynchronous loop's rate, how loudly it is allowed to
 * complain, the two numeric guards the driver and the IK solver need, and the ambient field that drives
 * a model with no entity under it.
 *
 * <p>The loop rate and {@link PhysicsSettings#substepRateHz()} are separate values that default to the
 * same number, and keeping them separate is deliberate. The substep rate is a stability requirement of
 * the integrator; the loop rate is how often the server bothers to ask. Running the loop slower than
 * the substep rate is legal and simply means each step integrates more than one substep — which is
 * what {@link PhysicsSettings#maxSubsteps()} bounds. Running it faster buys nothing, since emission is
 * capped far below either.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.sim.SimulationSettings} is a different type with the same
 * simple name: it is the assembled, already-validated bundle the loop reads once per step, solver and
 * cadence included. This one is the <em>file</em> — the keys, the comments and the defaults. Nothing
 * outside this package should ever need to name this interface; what the rest of the plugin sees is
 * {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "SimulationConfig", format = ConfigFormat.YAML)
public interface SimulationSettings {

    /**
     * Steps per second of the asynchronous simulation loop.
     *
     * <p>Must stay at or above {@link RenderSettings#emissionHz()}: emission happens on a whole number
     * of simulation steps, so a loop slower than the emission rate cannot deliver it.
     *
     * @return the loop rate, in hertz
     */
    @ConfigValue(comment = "Steps per second of the asynchronous simulation loop. Must stay at or above emissionHz in render.yml, since emission happens on a whole number of simulation steps.")
    default int loopRateHz() {
        return 60;
    }

    /**
     * Seconds between repeat reports of a failing simulation.
     *
     * <p>A simulation that throws throws every step, which without rate limiting is sixty stack traces
     * a second per model: that fills the disk, pushes every other line out of the log, and costs more
     * time formatting traces than the simulation costs running. The suppressed count is carried into
     * the next report rather than dropped, because "this failed 1800 times" and "this failed once" call
     * for different responses and look identical without it.
     *
     * @return the minimum interval between reports, in seconds
     */
    @ConfigValue(comment = "Seconds between repeat reports of a failing simulation. A broken simulation throws every step, so without this it is sixty stack traces a second per model.")
    default float errorLogIntervalSeconds() {
        return 5F;
    }

    /**
     * Largest per-tick horizontal translation treated as real movement, in blocks.
     *
     * <p>A delta beyond this is a teleport, and feeding a teleport to the wind term launches every bone
     * at once. Filtering it here is what lets the gait and the physics both assume a plausible speed.
     *
     * @return the horizontal step ceiling, in blocks per tick
     */
    @ConfigValue(comment = "Largest per-tick horizontal movement in blocks still treated as real movement. Anything beyond this is a teleport, and a teleport fed to the wind term launches every bone at once.")
    default float maxStepBlocks() {
        return 1.5F;
    }

    /**
     * Largest per-tick vertical translation treated as real movement, in blocks.
     *
     * <p>Higher than the horizontal ceiling because falling legitimately covers more ground in a tick
     * than walking does.
     *
     * @return the vertical step ceiling, in blocks per tick
     */
    @ConfigValue(comment = "Largest per-tick vertical movement in blocks still treated as real movement. Higher than the horizontal ceiling, because falling legitimately covers more ground.")
    default float maxVerticalStepBlocks() {
        return 6F;
    }

    /**
     * Distance below which an inverse-kinematics target counts as coincident with the limb root, in
     * blocks.
     *
     * <p>Without the guard {@code atan2(0, 0)} decides the limb's direction, which is to say nothing
     * does. At or below this distance the limb folds completely instead.
     *
     * @return the degenerate-target threshold, in blocks
     */
    @ConfigValue(comment = "Distance in blocks below which an IK target counts as sitting on the limb root. Without it atan2(0, 0) picks the limb's direction, which is to say nothing does.")
    default float ikDegenerateTargetBlocks() {
        return 1.0E-4F;
    }

    // =================================================================================================
    // Decorations: the ambient field, and how often a decoration's driver is recomputed.
    //
    // These live here rather than in a file of their own because they describe a *driver*, which is
    // what the rest of this file is about: the teleport thresholds above filter the driver an entity
    // produces, and these produce the driver a decoration has instead of an entity. An operator
    // reasoning about either is reasoning about the same question — what is allowed to push a model
    // around — and the two rates they trade against each other (loopRateHz and the pass rate below)
    // are then visible on one screen.
    //
    // Every one of them is the *world's* weather. What an individual decoration carries is a single
    // exposure scalar a plugin sets per decoration, so three hundred banners need one calibration.
    // =================================================================================================

    /**
     * How often a decoration's driver is recomputed and published, in hertz.
     *
     * <p>The decoration layer's equivalent of an entity sampler's period: it bounds how stale the
     * physics' idea of the wind, and of a move somebody just made, can be. Twenty is the useful figure
     * because a driver is denominated in blocks per <em>server tick</em>; above it the extra passes
     * recompute a field that cannot have changed by anything a client could see, and above
     * {@link #loopRateHz()} they publish samples the simulation never reads at all.
     *
     * @return the driver pass rate, in hertz
     */
    @ConfigValue(comment = "How often a decoration's driver is recomputed, in hertz. Twenty matches the tick a driver's speeds are denominated in; above loopRateHz the extra passes publish samples nothing reads.")
    default int decorationDriverUpdateHz() {
        return 20;
    }

    /**
     * Steady air speed past a fully exposed decoration, in blocks per tick.
     *
     * <p>The cheap half of the look, and the only half that is free. A constant force deflects a sprung
     * bone to a new equilibrium and the solver settles there, after which the emission gate stops the
     * packets — so a server whose decorations only ever feel this costs one update each, ever.
     *
     * @return the steady air speed, in blocks per tick
     */
    @ConfigValue(comment = "Steady air speed in blocks per tick past a fully exposed decoration. Free: a constant force deflects a bone to a new rest angle, the solver settles, and the packets stop.")
    default float decorationWindBaseSpeedBlocksPerTick() {
        return 0.02F;
    }

    /**
     * Amplitude of the time-varying air speed, in blocks per tick.
     *
     * <p>The expensive half: a bone chasing a moving target never settles, so an exposed decoration
     * emits at the full cadence for as long as this is non-zero. Zero is the setting for a server that
     * wants the deflection of a steady wind and none of the bandwidth of a gusting one.
     *
     * @return the gust amplitude, in blocks per tick
     */
    @ConfigValue(comment = "Amplitude in blocks per tick of the gusting air speed. This is the half that costs bandwidth: a gusting decoration never settles, so it emits for as long as it is exposed. Zero disables gusting.")
    default float decorationWindGustSpeedBlocksPerTick() {
        return 0.04F;
    }

    /**
     * Frequency of the primary gust oscillation, in hertz.
     *
     * <p>A rate rather than a period, because the second oscillation is expressed as a multiple of it.
     *
     * @return the gust rate, in hertz
     */
    @ConfigValue(comment = "Frequency in hertz of the primary gust oscillation. Low: a gust that cycles faster than about once a second reads as a vibration rather than as weather.")
    default float decorationWindGustRateHz() {
        return 0.3F;
    }

    /**
     * Frequency of the second gust oscillation, as a multiple of the first.
     *
     * <p>A ratio that is not a simple fraction is the entire point. One sine is a metronome, and the eye
     * reads a pure oscillation as an animation loop within about three cycles — a banner that visibly
     * loops is worse than one that does not move, because a still banner reads as a prop and a looping
     * one reads as a broken prop. Two oscillations at an awkward ratio have a repeat period nobody waits
     * for.
     *
     * <p>Note that the product of this and {@link #decorationWindGustRateHz()} is the fastest thing in
     * the field: past half the emission rate in {@code render.yml} it cannot be represented in what goes
     * on the wire, and the result is aliasing rather than detail.
     *
     * @return the harmonic ratio
     */
    @ConfigValue(comment = "Frequency of the second gust oscillation as a multiple of the first. Deliberately not a simple fraction: two sines at a tidy ratio repeat on a short, visible cycle and the banner reads as a looping animation.")
    default float decorationWindGustHarmonicRatio() {
        return 2.7F;
    }

    /**
     * How much of the gust comes from the second oscillation, as a fraction.
     *
     * <p>Zero is a pure sine and one is only the harmonic; anything between is the blend that makes the
     * field look unrepeating.
     *
     * @return the harmonic weight, between zero and one
     */
    @ConfigValue(comment = "How much of the gust comes from the second oscillation, 0 for a pure sine and 1 for only the harmonic.")
    default float decorationWindGustHarmonicWeight() {
        return 0.35F;
    }

    /**
     * Distance between two gust crests, in blocks.
     *
     * <p>The value that decorrelates neighbours. Gusts travel along the wind direction, so two
     * decorations a wavelength apart are a full cycle out of step and a row of banners ripples in
     * sequence instead of flapping in unison. A very long wavelength puts the whole world in phase,
     * which is the unison this exists to avoid.
     *
     * @return the gust wavelength, in blocks
     */
    @ConfigValue(comment = "Distance in blocks between two gust crests. Gusts travel along the wind, so this is what makes a row of banners ripple in sequence instead of flapping in unison.")
    default float decorationWindGustWavelengthBlocks() {
        return 24F;
    }

    /**
     * Compass direction the wind blows toward, in degrees.
     *
     * <p>Measured as the platform measures yaw: zero points toward {@code +Z} and the angle grows
     * clockwise, so it reads the same way as an entity's facing.
     *
     * @return the wind bearing, in degrees
     */
    @ConfigValue(comment = "Direction the wind blows toward, in degrees, measured like an entity's yaw: zero faces +Z and grows clockwise.")
    default float decorationWindDirectionDegrees() {
        return 45F;
    }

    /**
     * How far the wind direction wanders either side of its bearing, in degrees.
     *
     * <p>Zero holds the wind to one bearing exactly, which looks mechanical on anything watched for more
     * than a few seconds.
     *
     * @return the direction sway amplitude, in degrees
     */
    @ConfigValue(comment = "How far in degrees the wind direction wanders either side of its bearing. Zero holds it to one bearing, which looks mechanical.")
    default float decorationWindDirectionSwayDegrees() {
        return 12F;
    }

    /**
     * How fast the wind direction wanders, in hertz.
     *
     * <p>Zero means it does not, whatever the sway amplitude says — which is the coherent way to switch
     * the sway off, since a frozen sway contributes nothing rather than a constant offset.
     *
     * @return the direction sway rate, in hertz
     */
    @ConfigValue(comment = "How fast in hertz the wind direction wanders. Zero disables the sway whatever its amplitude says. Slower than the gust rate, or the wind reads as turbulence rather than as weather.")
    default float decorationWindDirectionSwayRateHz() {
        return 0.07F;
    }

    /**
     * Updraft carried by a gust, as a fraction of its horizontal amplitude.
     *
     * <p>What stops a banner looking like it is being pushed along a rail. Only the gust lifts: a steady
     * updraft would hold every bone at a constant angle off its rest pose for the model's whole life,
     * which reads as a rig that was exported wrong rather than as wind.
     *
     * @return the vertical fraction, between zero and one
     */
    @ConfigValue(comment = "Updraft carried by a gust, as a fraction of its horizontal amplitude. Only the gust lifts; a steady updraft would hold every bone at a permanent angle off its rest pose.")
    default float decorationWindVerticalFraction() {
        return 0.25F;
    }
}
