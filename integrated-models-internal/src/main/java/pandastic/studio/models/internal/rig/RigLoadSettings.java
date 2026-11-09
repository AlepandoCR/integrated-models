package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

/**
 * Everything {@link RigLoader} needs from configuration in order to read a file, as one immutable
 * snapshot.
 *
 * <h2>Why the loader is handed a solver rather than a step rate</h2>
 * The damping clamp — the one clamp an author has no way to anticipate, since staying inside every
 * documented range is not sufficient for stability — goes through
 * {@link AngularPhysicsSolver#maxStableDamping(float, int)}, which divides by the
 * {@code dampingToRate} of <em>one configured solver</em>. Handing the loader a loose number instead
 * would let it clamp against a calibration the solver that actually runs the rig is not using, and a
 * value clamped against the wrong factor is worse than one left alone: the rig loads, reports that it
 * was made safe, and then diverges anyway.
 *
 * <p>So the solver arrives by reference and {@link #substepRateHz()} is read off it. That also removes
 * the two defects the previous arrangement had at once — it no longer hardcodes 60 Hz, and it no longer
 * constructs a throwaway solver just to ask one what its rate is.
 *
 * <h2>Bounds, not calibration</h2>
 * {@link #rigBounds()} and {@link #profileBounds()} are what {@link Rig.Builder} validates against, and
 * the loader needs the same two objects so that the value it clamps to is the value the builder will
 * accept. A loader clamping against one set of bounds and a builder validating against another is how
 * the backstop in {@code RigFileReader.build} starts firing on well-formed files.
 *
 * <h2>The bounds have to admit the defaults, and this is where that is enforced</h2>
 * A file may declare only a bone's kind, in which case its profile is built entirely out of
 * {@link #profileDefaults()} and then validated against {@link #profileBounds()}. Defaults outside their
 * own bounds therefore refuse every bone that trusted a default, as a per-bone warning quoting a value
 * that appears nowhere in the file. So the pairing is checked once, here, at construction: a loader that
 * could not possibly produce a loadable rig should not be assembled in the first place.
 * {@link pandastic.studio.models.internal.config.ConfigMapper} has already refused the same combination
 * with a diagnostic naming the file and the key, so this throw is the backstop for a caller that built
 * its settings some other way.
 *
 * @param solver               the configured solver that will run the rigs this loader produces. Its
 *                             calibration decides the damping ceiling and its tuning carries the substep
 *                             rate the ceiling is quoted at
 * @param rigBounds            the structural limits every loaded rig is built within, including the bone
 *                             ceiling the loader trims to
 * @param profileBounds        the ranges every coefficient is clamped into. They must admit
 *                             {@link #profileDefaults()}, since the loader reads those back as the
 *                             fall-back for a value a file omits — which this record enforces rather
 *                             than documents, see below
 * @param profileDefaults      the coefficients a file inherits for anything it does not declare. The
 *                             loader reads them back for every omitted key, so they are the entire
 *                             tuning of a bone whose physics block names only its kind
 * @param stabilityMargin      how far inside the damping bound a clamped value is placed, as a fraction
 *                             of the bound. The bound is exclusive and the comparison that enforces it
 *                             happens in float arithmetic, so a value landing exactly on it would still
 *                             be refused; this is the margin that survives the rounding. Configurable
 *                             because how much headroom is wanted is a judgement — nearer to 1 keeps
 *                             more of the author's damping, further from it tolerates a solver whose
 *                             calibration is reloaded to something slightly stiffer
 * @param minReachLengthBlocks length below which a reach vector counts as pointing nowhere, so the
 *                             loader substitutes the configured seed instead of letting the api refuse a
 *                             vector it cannot normalise. Configurable for the same reason as
 *                             {@link pandastic.studio.models.api.math.IkTolerance}: it is a length, and
 *                             the models it is applied to are not all the same size
 */
public record RigLoadSettings(
        @NotNull AngularPhysicsSolver solver,
        @NotNull RigBounds rigBounds,
        @NotNull ProfileBounds profileBounds,
        @NotNull ProfileDefaults profileDefaults,
        float stabilityMargin,
        float minReachLengthBlocks
) {

    public RigLoadSettings {
        profileDefaults.requireWithin(profileBounds);
        if (!Float.isFinite(stabilityMargin) || stabilityMargin <= 0F || stabilityMargin >= 1F) {
            // At or above 1 the clamped value lands on or past an exclusive bound, so the solver
            // refuses the rig the loader just reported it had made safe. At or below 0 every damped
            // bone silently loses its damping.
            throw new IllegalArgumentException(
                    "stabilityMargin must be finite and within (0, 1) but was " + stabilityMargin);
        }
        if (!Float.isFinite(minReachLengthBlocks) || minReachLengthBlocks <= 0F) {
            throw new IllegalArgumentException(
                    "minReachLengthBlocks must be finite and positive but was " + minReachLengthBlocks);
        }
    }

    /**
     * The substep rate the damping bound is checked against, in hertz.
     *
     * <p>Read from {@link #solver()} rather than carried separately, because a copy that could disagree
     * with the solver would make the clamp quote one rate and enforce another.
     */
    public int substepRateHz() {
        return this.solver.substepRateHz();
    }
}
