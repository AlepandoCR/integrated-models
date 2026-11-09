package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.PhysicsDriver;

/**
 * The ambient field, evaluated at one place and one moment: a pure function from configuration, time
 * and a position to a {@link PhysicsDriver}.
 *
 * <h2>Why the wind is published as the model's own velocity</h2>
 * The solver's drag term takes the owner's movement and computes the apparent wind as its negation,
 * because that is what the entity path hands it: a model being carried through still air. Air moving
 * past a stationary model is the same situation in a different frame — so the right thing to publish is
 * the decoration's velocity <em>relative to the air</em>, which for a decoration that is not moving is
 * exactly the negation of the wind.
 *
 * <p>That is not a trick to reuse a formula; it is the only framing under which a carried lantern in a
 * gust is describable at all. Movement and wind are both velocities relative to the same air, so they
 * <strong>add</strong>, and the solver needs no knowledge that decorations exist.
 *
 * <h2>Why two sines</h2>
 * One sine is a metronome. The eye reads a pure oscillation as an animation loop within about three
 * cycles, and a banner that visibly loops is worse than one that does not move — a still banner reads as
 * a static prop, a looping one reads as a broken prop. Two oscillations at a ratio that is not a simple
 * fraction have a repeat period long enough that nobody waits for it, at the cost of one extra sine per
 * decoration per pass.
 *
 * <h2>Why the phase depends on where the decoration is</h2>
 * Gusts travel. The phase is advanced by the distance along the wind direction, divided by a configured
 * wavelength, so a row of banners ripples in sequence as a gust passes along it rather than flapping in
 * unison. It is also what makes the field <em>stateless</em>: every decoration's phase is derived from
 * its own position and one shared clock, so nothing has to be remembered per decoration and a decoration
 * that moves simply finds itself in a different part of the weather.
 *
 * <h2>Double precision, on purpose</h2>
 * The phase is {@code rate * elapsedSeconds}, and a server runs for weeks. In {@code float}, consecutive
 * values of a seconds count are already 0.5 s apart after three weeks of uptime, which quantises the
 * gust into visible steps and then freezes it. The arithmetic is therefore done in {@code double} and
 * only the result — four floats the solver consumes — is narrowed.
 */
public final class AmbientWind {

    /**
     * One turn, in radians.
     *
     * <p><strong>A mathematical fact, not a tunable.</strong> The configured rates are in hertz and the
     * sines want radians, so this is the conversion between a cycle and an angle.
     */
    private static final double TAU = 2D * Math.PI;

    /**
     * The neutral weight of the primary oscillation.
     *
     * <p><strong>Structure, not a tunable.</strong> The two oscillations are a blend, so their weights
     * sum to one and the primary's is whatever the configured harmonic weight leaves.
     */
    private static final double FULL_WEIGHT = 1D;

    private AmbientWind() {
    }

    /**
     * The driver a decoration at {@code placement} is pushed by at {@code elapsedSeconds}.
     *
     * <p>Returns {@link PhysicsDriver#STILL} — the shared constant, so a calm server allocates nothing —
     * whenever the decoration is sheltered or the field is calm. That is the identity the whole
     * bandwidth argument rests on: a decoration published a motionless driver settles, and a settled
     * decoration emits nothing.
     *
     * @param settings       the field, read once per pass by the caller
     * @param motion         how exposed this decoration is
     * @param placement      where it stands, which sets its phase in the travelling gust
     * @param elapsedSeconds seconds since the driver loop's origin, shared by every decoration so that
     *                       the field is coherent across the world
     * @return the velocity of this decoration relative to the air, in its own frame
     */
    @Contract(pure = true)
    public static @NotNull PhysicsDriver driverAt(
            @NotNull DecorationSettings settings,
            @NotNull AmbientMotion motion,
            @NotNull DecorationPlacement placement,
            double elapsedSeconds
    ) {
        if (motion.isStill() || settings.isCalm()) {
            return PhysicsDriver.STILL;
        }

        final double bearing = Math.toRadians(settings.windDirectionDegrees());
        // Distance along the wind, in the platform's yaw convention: a bearing of zero points at +Z,
        // so its direction vector is (-sin, cos) in (x, z).
        final double along = -placement.x() * Math.sin(bearing) + placement.z() * Math.cos(bearing);
        final double spatialCycles = along / settings.windGustWavelengthBlocks();

        // Subtracted rather than added, so the pattern travels *with* the wind: a decoration downwind
        // sees the crest later than one upwind.
        final double gustPhase =
                TAU * (settings.windGustRateHz() * elapsedSeconds - spatialCycles);
        final double harmonicWeight = settings.windGustHarmonicWeight();
        final double gust = Math.sin(gustPhase) * (FULL_WEIGHT - harmonicWeight)
                + Math.sin(gustPhase * settings.windGustHarmonicRatio()) * harmonicWeight;

        // No spatial term here, unlike the gust. A change of wind direction is a large-scale event —
        // the whole field turns together — whereas a gust is local. It also makes a sway rate of zero
        // mean exactly no sway: with a spatial term it would instead be a constant direction offset
        // that varied from one decoration to the next, which is not what switching something off means.
        final double swayPhase = TAU * settings.windDirectionSwayRateHz() * elapsedSeconds;
        final double direction = bearing
                + Math.toRadians(settings.windDirectionSwayDegrees()) * Math.sin(swayPhase);

        final double strength = motion.strength();
        final double speed = (settings.windBaseSpeedBlocksPerTick()
                + settings.windGustSpeedBlocksPerTick() * gust) * strength;
        // Only the gust lifts. A steady updraft would hold every bone at a constant angle off its rest
        // pose for the model's whole life, which reads as a rig that was exported wrong.
        final double lift = settings.windGustSpeedBlocksPerTick() * gust
                * settings.windVerticalFraction() * strength;

        // The air's velocity in world space, then negated: what the solver wants is the decoration's
        // velocity relative to the air. See the class documentation.
        final double relativeX = Math.sin(direction) * speed;
        final double relativeZ = -Math.cos(direction) * speed;

        // Into the decoration's own frame — forward along its facing, side to its left — which is the
        // same projection the entity sampler applies to a position delta.
        final double heading = placement.yawRadians();
        final double sin = Math.sin(heading);
        final double cos = Math.cos(heading);
        final float forward = narrowed(-relativeX * sin + relativeZ * cos);
        final float side = narrowed(relativeX * cos + relativeZ * sin);
        final float vertical = narrowed(-lift);

        if (!Float.isFinite(forward) || !Float.isFinite(side) || !Float.isFinite(vertical)) {
            // Unreachable for settings the validator admitted and an exposure the api admitted, both of
            // which are finite. Kept because the alternative to degrading to stillness is a
            // non-finite rotation, which reaches a client as a bone that disappears or swallows the
            // screen — the same reason the entity sampler answers STILL for a non-finite coordinate.
            return PhysicsDriver.STILL;
        }
        // A decoration does not turn on its own: a yaw change arrives through a move, and the loop adds
        // that in. Zero here is the additive identity rather than a simplification.
        return new PhysicsDriver(forward, side, vertical, 0F);
    }

    /**
     * {@code value} as a float, with a negative zero folded onto zero.
     *
     * <p>Not pedantry. Negating a zero leaves {@code -0.0}, and a record compares its float components
     * with {@code Float.compare} — so a field that is genuinely unchanging would produce drivers that
     * compare <em>unequal</em> to each other as a sine crossed zero, purely from the sign of a zero.
     * Anything downstream that treats "the same driver" as meaning something would be wrong in a way
     * nobody could see, since every other use of the value behaves identically either way.
     *
     * @param value the computed component
     * @return the component as a float, never negative zero
     */
    private static float narrowed(double value) {
        // True for both zeros, which is the whole point: the comparison is numeric and the fold is not.
        return value == 0D ? 0F : (float) value;
    }
}
