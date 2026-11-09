package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The tuning of one physics bone.
 *
 * <p>Every coefficient here is <strong>dimensionless and author-facing</strong> — the numbers a rig
 * file carries. Turning them into angular rates is the solver's job, through calibration constants it
 * documents and owns. That split is deliberate: it keeps rig files portable across solver revisions,
 * and it keeps the magic numbers in one place where their reasoning is written down.
 *
 * <p>The permitted ranges are enforced rather than trusted, because a solver fed a stiffness of 50 does
 * not misbehave gracefully — it diverges. The angular solver documents the two stability bounds that
 * make {@link #mass()}'s floor non-negotiable, and refuses tuning that crosses either — note in
 * particular that staying inside every range is <em>not</em> sufficient on its own: damping must also
 * stay below mass.
 *
 * <h2>Two layers of validation</h2>
 * The canonical constructor enforces only what is <strong>arithmetic</strong>: nothing is NaN or
 * infinite, a fraction is within {@code [0, 1]}, a magnitude is non-negative, mass is strictly positive
 * because it is a divisor, and the reach vector has a direction. None of that is a preference.
 *
 * <p>The operator's ranges are checked by {@link #requireWithin(ProfileBounds)}, which
 * {@link Builder#build()} calls for you because the builder is handed the bounds up front. A profile
 * assembled through the canonical constructor directly — as {@link Rig.Builder} does when it folds a
 * collision cone — is re-checked by whoever has the bounds. {@code Rig.Builder} does exactly that for
 * every bone it accepts, so the "a programmatic caller passing a bad coefficient has a bug, so this
 * throws" guarantee still holds end to end.
 *
 * @param kind            how this bone is integrated
 * @param stiffness       {@code 0..1}, how hard the bone is pulled back to its rest pose. Ignored by {@link PhysicsKind#DRAG}
 * @param damping         {@code 0..1}, how fast its motion bleeds off. Ignored by {@link PhysicsKind#DRAG}
 * @param lag             {@code 0..1}, the fraction of its offset a {@link PhysicsKind#DRAG} bone keeps each step. Ignored by every other kind
 * @param gravity         downward pull on the pitch axis, up to {@link ProfileBounds#maxGravity()}. Seeded per kind from {@link ProfileDefaults}, which is where a {@link PhysicsKind#SPRING}'s zero comes from
 * @param mass            within {@link ProfileBounds#minMass()}{@code ..}{@link ProfileBounds#maxMass()}, divides the restoring and damping terms. Lighter means twitchier <em>and</em> closer to instability
 * @param wind            how strongly the owner's movement drags the bone, up to {@link ProfileBounds#maxWind()}
 * @param collisionRadius the clearance in blocks a {@link PhysicsKind#CLOTH_STRIP} keeps from its pivot, up to {@link ProfileBounds#maxCollisionRadius()}. Folded into a pitch clamp at compile time
 * @param reachX          x of the unit vector the bone extends along, in model space
 * @param reachY          y of that vector
 * @param reachZ          z of that vector
 * @param limits          per-axis angular bounds
 */
public record PhysicsProfile(
        @NotNull PhysicsKind kind,
        float stiffness,
        float damping,
        float lag,
        float gravity,
        float mass,
        float wind,
        float collisionRadius,
        float reachX,
        float reachY,
        float reachZ,
        @NotNull AxisLimits limits
) {

    public PhysicsProfile {
        requireFraction(stiffness, "stiffness");
        requireFraction(damping, "damping");
        requireFraction(lag, "lag");
        requireNonNegative(gravity, "gravity");
        // Mass divides the restoring and damping terms, so zero is a division by zero and negative
        // inverts the spring. Neither is a range an operator can widen into.
        requirePositive(mass, "mass");
        requireNonNegative(wind, "wind");
        requireNonNegative(collisionRadius, "collisionRadius");

        final float length = (float) Math.sqrt(
                (double) reachX * reachX + (double) reachY * reachY + (double) reachZ * reachZ);
        if (length < 1.0E-4F) {
            throw new IllegalArgumentException("Degenerate reach vector: it has no direction");
        }
        // Normalised on the way in so the wind torque never has to, and so two rigs that differ only
        // in the magnitude of this vector behave identically.
        reachX /= length;
        reachY /= length;
        reachZ /= length;
    }

    private static void requireFraction(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (value < 0F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " is a fraction and must be within [0, 1] but was " + value);
        }
    }

    private static void requireNonNegative(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(
                    name + " is a magnitude and must be finite and at least 0 but was " + value);
        }
    }

    private static void requirePositive(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireRange(float value, float min, float max, @NotNull String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be within [" + min + ", " + max + "] but was " + value);
        }
    }

    /**
     * A builder seeded with {@code defaults}, this {@code kind}'s own gravity among them, and with
     * {@code bounds}' default angular limits.
     *
     * <p>The two configuration objects are deliberately separate arguments. {@code defaults} says what
     * an unspecified coefficient is; {@code bounds} says what any coefficient is allowed to be. Nothing
     * here checks the first against the second — {@link Builder#build()} validates the <em>final</em>
     * values, so pairing defaults with bounds that would refuse an untouched seed is legal as long as
     * the caller sets it. The pairing is enforced where it actually matters, by
     * {@link ProfileDefaults#requireWithin(ProfileBounds)}, which a loader calls once instead of
     * discovering the mismatch one bone at a time.
     *
     * @param bounds   the operator's ranges. Supplied here rather than at {@code build()} because the
     *                 builder needs {@link ProfileBounds#defaultLimitDegrees()} to seed the limits as
     *                 well as the ranges to validate against, and taking it twice would let the two
     *                 disagree
     * @param defaults the coefficients an unset value is seeded with
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull Builder builder(
            @NotNull PhysicsKind kind,
            @NotNull ProfileBounds bounds,
            @NotNull ProfileDefaults defaults
    ) {
        return new Builder(kind, bounds, defaults);
    }

    /** Whether this profile's restoring force is integrated with a velocity term. */
    public boolean isSpringLike() {
        return this.kind != PhysicsKind.DRAG;
    }

    /**
     * Checks this profile against the operator's ranges and returns it, so it chains.
     *
     * @throws IllegalArgumentException if mass is outside {@code [minMass, maxMass]} or gravity, wind or
     *                                 the collision radius exceeds its ceiling
     */
    @Contract(value = "_ -> this", pure = true)
    public @NotNull PhysicsProfile requireWithin(@NotNull ProfileBounds bounds) {
        requireRange(this.mass, bounds.minMass(), bounds.maxMass(), "mass");
        requireRange(this.gravity, 0F, bounds.maxGravity(), "gravity");
        requireRange(this.wind, 0F, bounds.maxWind(), "wind");
        requireRange(this.collisionRadius, 0F, bounds.maxCollisionRadius(), "collisionRadius");
        return this;
    }

    /**
     * Mutable builder for a {@link PhysicsProfile}.
     *
     * <p>Every seed comes from the {@link ProfileDefaults} this builder was handed, because a rig loader
     * reads the seeds back as the fall-back for a coefficient its file omits — so a literal here would
     * be the tuning of every bone that does not override it, decided at compile time. The one exception
     * is the collision radius, and it is not calibration: see the field.
     */
    public static final class Builder {

        private final @NotNull PhysicsKind kind;
        private final @NotNull ProfileBounds bounds;
        private float stiffness;
        private float damping;
        private float lag;
        private float gravity;
        private float mass;
        private float wind;

        /**
         * Left at zero, which is the absence of a collision cone rather than a small one.
         *
         * <p>{@link Rig.Builder} folds a clearance into a pitch clamp only while this is positive, so
         * zero means "this bone declares no clearance" — a feature not asked for. Seeding it from
         * configuration would put a pitch ceiling on every cloth strip that never mentioned one.
         */
        private float collisionRadius;

        private float reachX;
        private float reachY;
        private float reachZ;
        private @NotNull AxisLimits limits;

        private Builder(
                @NotNull PhysicsKind kind,
                @NotNull ProfileBounds bounds,
                @NotNull ProfileDefaults defaults
        ) {
            this.kind = kind;
            this.bounds = bounds;
            this.limits = AxisLimits.symmetricDegrees(bounds.defaultLimitDegrees());
            this.stiffness = defaults.stiffness();
            this.damping = defaults.damping();
            this.lag = defaults.lag();
            this.mass = defaults.mass();
            this.wind = defaults.wind();
            // Per kind, because a pendulum with no gravity hangs nowhere and a spring with gravity is
            // not a spring. Which figure each kind gets is the operator's to decide; that they are
            // allowed to differ is not.
            this.gravity = defaults.gravityFor(kind);
            this.reachX = defaults.reachX();
            this.reachY = defaults.reachY();
            this.reachZ = defaults.reachZ();
        }

        public @NotNull Builder stiffness(float stiffness) {
            this.stiffness = stiffness;
            return this;
        }

        public @NotNull Builder damping(float damping) {
            this.damping = damping;
            return this;
        }

        public @NotNull Builder lag(float lag) {
            this.lag = lag;
            return this;
        }

        public @NotNull Builder gravity(float gravity) {
            this.gravity = gravity;
            return this;
        }

        public @NotNull Builder mass(float mass) {
            this.mass = mass;
            return this;
        }

        public @NotNull Builder wind(float wind) {
            this.wind = wind;
            return this;
        }

        public @NotNull Builder collisionRadius(float collisionRadius) {
            this.collisionRadius = collisionRadius;
            return this;
        }

        public @NotNull Builder reach(float x, float y, float z) {
            this.reachX = x;
            this.reachY = y;
            this.reachZ = z;
            return this;
        }

        public @NotNull Builder limits(@NotNull AxisLimits limits) {
            this.limits = limits;
            return this;
        }

        /** Validates against both the arithmetic invariants and the operator's ranges, then freezes. */
        public @NotNull PhysicsProfile build() {
            return new PhysicsProfile(
                    this.kind, this.stiffness, this.damping, this.lag, this.gravity, this.mass,
                    this.wind, this.collisionRadius,
                    this.reachX, this.reachY, this.reachZ, this.limits)
                    .requireWithin(this.bounds);
        }
    }
}
