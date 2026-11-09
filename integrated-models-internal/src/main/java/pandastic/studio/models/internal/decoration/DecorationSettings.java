package pandastic.studio.models.internal.decoration;

/**
 * Everything the decoration layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.binding.BindingSettings}
 * and {@link pandastic.studio.models.internal.sim.SimulationSettings}: a value a consumer reads once
 * and uses for a whole pass, never a setting per call site. No component has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * <h2>The ambient field is weather, not decoration state</h2>
 * Every component below describes the <em>world's</em> wind: how hard it blows, which way, how it gusts,
 * how far apart two gusts are. What an individual decoration carries is one exposure scalar — see
 * {@link pandastic.studio.models.api.decoration.AmbientMotion} — so an operator tunes the weather once
 * and a plugin decides which decorations stand in it.
 *
 * <h2>Two of these components decide whether a decoration is free</h2>
 * A steady wind is <strong>silent</strong>. A constant force deflects a sprung bone to a new equilibrium
 * and the solver settles there, at which point the emission gate stops the packets — so
 * {@code windBaseSpeedBlocksPerTick} on its own costs one update per decoration, ever. It is
 * {@code windGustSpeedBlocksPerTick} that costs bandwidth, continuously, because a bone chasing a moving
 * target never settles. An operator who wants the look for free sets the base and zeroes the gust.
 *
 * @param driverUpdateHz               how often a decoration's driver is recomputed and published. The
 *                                     decoration layer's equivalent of an entity sampler's period, and
 *                                     the same trade: this is what bounds how stale the physics' idea of
 *                                     the wind and of a move can be. Twenty is the useful figure because
 *                                     the driver is denominated in blocks per server tick; above it the
 *                                     extra passes recompute a field that cannot have changed meaningfully
 * @param windBaseSpeedBlocksPerTick   steady air speed past a fully exposed decoration. Deflects and then
 *                                     settles, so it is the cheap half of the look
 * @param windGustSpeedBlocksPerTick   amplitude of the time-varying air speed. The expensive half: a
 *                                     decoration in a gusting field never settles and therefore emits at
 *                                     the full cadence for as long as it is exposed
 * @param windGustRateHz               frequency of the primary gust oscillation. A rate rather than a
 *                                     period because the second harmonic is expressed as a ratio of it
 * @param windGustHarmonicRatio        frequency of the second gust oscillation, as a multiple of the
 *                                     first. An irrational-looking ratio is the point: two sines at a
 *                                     simple ratio repeat on a short, visible cycle, and a repeating
 *                                     flap is what makes a banner read as an animation rather than as
 *                                     weather
 * @param windGustHarmonicWeight       how much of the gust comes from the second oscillation, {@code 0}
 *                                     for a pure sine and {@code 1} for only the harmonic
 * @param windGustWavelengthBlocks     distance between two gust crests. This is the component that
 *                                     decorrelates neighbours: gusts travel along the wind direction, so
 *                                     two decorations a wavelength apart are a full cycle out of step and
 *                                     a row of banners ripples instead of flapping in unison
 * @param windDirectionDegrees         compass direction the wind blows toward, measured as the platform
 *                                     measures yaw — zero toward {@code +Z}, growing clockwise
 * @param windDirectionSwayDegrees     how far the direction wanders either side of that. Zero holds the
 *                                     wind to one bearing, which looks mechanical on a long exposure
 * @param windDirectionSwayRateHz      how fast the direction wanders. Zero means it does not, whatever
 *                                     the sway amplitude says
 * @param windVerticalFraction         updraft carried by a gust, as a fraction of its horizontal
 *                                     amplitude. What stops a banner looking like it is being pushed
 *                                     along a rail
 */
public record DecorationSettings(
        int driverUpdateHz,
        float windBaseSpeedBlocksPerTick,
        float windGustSpeedBlocksPerTick,
        float windGustRateHz,
        float windGustHarmonicRatio,
        float windGustHarmonicWeight,
        float windGustWavelengthBlocks,
        float windDirectionDegrees,
        float windDirectionSwayDegrees,
        float windDirectionSwayRateHz,
        float windVerticalFraction
) {

    /**
     * Nanoseconds in a second.
     *
     * <p><strong>A unit definition, not a tunable.</strong> It is the SI relationship between the
     * configured rate in hertz and the scheduler period in nanoseconds.
     */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * Server ticks in a second.
     *
     * <p><strong>A protocol fact, not a tunable.</strong> A {@link pandastic.studio.models.api.physics.PhysicsDriver}
     * is denominated in blocks per server tick, and the driver loop runs at its own configured rate, so
     * this is the constant that converts between the two. The same number the emission cadence and the
     * view settings document.
     */
    private static final double TICKS_PER_SECOND = 20D;

    public DecorationSettings {
        if (driverUpdateHz < 1) {
            throw new IllegalArgumentException(
                    "driverUpdateHz must be at least 1 but was " + driverUpdateHz);
        }
        requireNotNegative(windBaseSpeedBlocksPerTick, "windBaseSpeedBlocksPerTick");
        requireNotNegative(windGustSpeedBlocksPerTick, "windGustSpeedBlocksPerTick");
        requirePositive(windGustRateHz, "windGustRateHz");
        requirePositive(windGustHarmonicRatio, "windGustHarmonicRatio");
        requireFraction(windGustHarmonicWeight, "windGustHarmonicWeight");
        // A divisor: the spatial phase of a gust is the distance along the wind over this length, so
        // zero is undefined rather than merely small.
        requirePositive(windGustWavelengthBlocks, "windGustWavelengthBlocks");
        requireFinite(windDirectionDegrees, "windDirectionDegrees");
        requireNotNegative(windDirectionSwayDegrees, "windDirectionSwayDegrees");
        // Zero is a legal rate here and not for the gust: a sway of zero frequency contributes exactly
        // no sway, whereas a gust frozen at zero frequency would leave a per-position constant offset
        // that no operator could have meant.
        requireNotNegative(windDirectionSwayRateHz, "windDirectionSwayRateHz");
        requireNotNegative(windVerticalFraction, "windVerticalFraction");
    }

    /**
     * The interval between two driver passes, in nanoseconds.
     *
     * <p>Derived rather than configured, and in nanoseconds rather than milliseconds for the same reason
     * the simulation loop's period is: a rate that does not divide a thousand rounds to a whole
     * millisecond at a cost of several percent.
     *
     * @return the pass interval, in nanoseconds
     */
    public long periodNanos() {
        return NANOS_PER_SECOND / this.driverUpdateHz;
    }

    /**
     * What one pass's worth of movement has to be multiplied by to become blocks per server tick.
     *
     * <p>A driver's speeds are per server tick, which is what a raw delta between two consecutive ticks
     * already is. The driver loop does not run on ticks, so a decoration moved once between two passes
     * has covered its distance in {@code 1 / driverUpdateHz} seconds rather than in one tick, and
     * publishing the raw delta would make a move's apparent speed depend on a rate that is supposed to
     * be invisible.
     *
     * @return the scale from blocks per pass to blocks per tick
     */
    public double passToTickScale() {
        return TICKS_PER_SECOND / this.driverUpdateHz;
    }

    /**
     * Whether the configured field moves anything at all.
     *
     * <p>True when both the steady and the gusting components are zero, in which case even a fully
     * exposed decoration is driven by nothing and settles. Worth having as a question because it is the
     * one configuration under which a whole server of exposed decorations is still free.
     *
     * @return whether the ambient field is calm
     */
    public boolean isCalm() {
        return this.windBaseSpeedBlocksPerTick == 0F && this.windGustSpeedBlocksPerTick == 0F;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    private static void requireNotNegative(float value, String name) {
        requireFinite(value, name);
        if (value < 0F) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
    }

    private static void requirePositive(float value, String name) {
        requireFinite(value, name);
        if (value <= 0F) {
            throw new IllegalArgumentException(name + " must be greater than zero but was " + value);
        }
    }

    private static void requireFraction(float value, String name) {
        requireFinite(value, name);
        if (value < 0F || value > 1F) {
            throw new IllegalArgumentException(name + " must be between 0 and 1 but was " + value);
        }
    }
}
