package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The coefficients a {@link PhysicsProfile} is seeded with — the value a rig file inherits for every
 * coefficient it does not mention.
 *
 * <p>These are <strong>calibration, not cosmetics</strong>. A rig loader reads them back as the
 * fall-back for an omitted key, so a file that says only {@code kind: pendulum} is tuned entirely by
 * this object: these numbers decide how that bone actually moves. As literals in a builder they were one
 * figure per build, chosen once, for one body shape — which is the same argument that moved
 * {@link ProfileBounds} and {@link pandastic.studio.models.api.physics.PhysicsTuning} into
 * configuration.
 *
 * <h2>Gravity is per kind, because the difference is structural</h2>
 * A {@link PhysicsKind#PENDULUM} with no gravity hangs nowhere, and a {@link PhysicsKind#SPRING} with
 * gravity is not a spring — its whole definition is that it returns to wherever the rig put it. That
 * difference has to survive being configured, so there is one component per kind and
 * {@link #gravityFor(PhysicsKind)} selects between them through an exhaustive {@code switch}: adding a
 * fifth kind becomes a compile error here rather than a bone that silently seeds zero gravity.
 *
 * <p>Four named components rather than a {@code float[]} indexed by {@link Enum#ordinal()} because an
 * array component would break this record's equality and let a caller mutate a published snapshot, and
 * rather than one scalar plus a "springs are exempt" rule because an operator tuning a cloth strip and a
 * pendulum on the same server legitimately wants two numbers.
 *
 * <h2>What is deliberately not here</h2>
 * <ul>
 *   <li><strong>The collision radius.</strong> Its seed is zero, and zero means "declare no cone" rather
 *       than "a small cone": {@link Rig.Builder} folds a clearance into a pitch clamp only when it is
 *       positive. A configurable default would quietly put a pitch ceiling on every cloth strip in every
 *       rig on the server, which is the absence of a feature being turned into its presence.</li>
 *   <li><strong>The angular limits.</strong> {@link ProfileBounds#defaultLimitDegrees()} already carries
 *       them, and it belongs there: it is the half-width of a <em>bound</em>, and the builder has to take
 *       the bounds anyway. Carrying it twice would let the two copies disagree.</li>
 * </ul>
 *
 * <h2>Two layers of validation, as everywhere else in this package</h2>
 * The canonical constructor enforces only what is <strong>arithmetic</strong>: a fraction is within
 * {@code [0, 1]}, a magnitude is finite and non-negative, mass is strictly positive because it is a
 * divisor, and the reach vector is not all zero because a direction has to point somewhere. None of that
 * is a preference.
 *
 * <p>The operator's ranges are a separate question, answered by {@link #requireWithin(ProfileBounds)} —
 * because defaults and bounds are two independent sets of keys and nothing stops an operator from
 * narrowing the bounds until they exclude their own defaults. That is a configuration error worth
 * catching loudly where the two meet, since a rig file is entitled to omit every coefficient and
 * {@link PhysicsProfile.Builder#build()} validates what it then seeds.
 *
 * @param stiffness         {@code 0..1} seed for {@link PhysicsProfile#stiffness()}
 * @param damping           {@code 0..1} seed for {@link PhysicsProfile#damping()}
 * @param lag               {@code 0..1} seed for {@link PhysicsProfile#lag()}, read only by
 *                          {@link PhysicsKind#DRAG}
 * @param mass              seed for {@link PhysicsProfile#mass()}, within
 *                          {@link ProfileBounds#minMass()}{@code ..}{@link ProfileBounds#maxMass()}
 * @param wind              seed for {@link PhysicsProfile#wind()}, up to {@link ProfileBounds#maxWind()}
 * @param pendulumGravity   gravity seed for a {@link PhysicsKind#PENDULUM}
 * @param springGravity     gravity seed for a {@link PhysicsKind#SPRING}. Zero is what makes a spring a
 *                          spring; anything else turns one into a pendulum that was asked for by name
 * @param clothStripGravity gravity seed for a {@link PhysicsKind#CLOTH_STRIP}
 * @param dragGravity       gravity seed for a {@link PhysicsKind#DRAG}
 * @param reachX            x of the direction a seeded bone extends along, in model space
 * @param reachY            y of that direction. Negative is downward, which is what makes the shipped
 *                          default a bone that hangs
 * @param reachZ            z of that direction
 */
public record ProfileDefaults(
        float stiffness,
        float damping,
        float lag,
        float mass,
        float wind,
        float pendulumGravity,
        float springGravity,
        float clothStripGravity,
        float dragGravity,
        float reachX,
        float reachY,
        float reachZ
) {

    public ProfileDefaults {
        requireFraction(stiffness, "stiffness");
        requireFraction(damping, "damping");
        requireFraction(lag, "lag");
        // Mass divides the restoring and damping terms, so a seed of zero is a division by zero for
        // every bone that does not override it.
        requirePositive(mass, "mass");
        requireNonNegative(wind, "wind");
        requireNonNegative(pendulumGravity, "pendulumGravity");
        requireNonNegative(springGravity, "springGravity");
        requireNonNegative(clothStripGravity, "clothStripGravity");
        requireNonNegative(dragGravity, "dragGravity");

        requireFinite(reachX, "reachX");
        requireFinite(reachY, "reachY");
        requireFinite(reachZ, "reachZ");
        // Not normalised here: PhysicsProfile normalises on the way in, so the magnitude of this vector
        // is already irrelevant and this record carries the direction exactly as it was configured.
        // What it cannot carry is no direction at all.
        if (reachX == 0F && reachY == 0F && reachZ == 0F) {
            throw new IllegalArgumentException(
                    "The default reach vector is all zero, so a seeded bone extends in no direction");
        }
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

    private static void requireFinite(float value, @NotNull String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    /**
     * The gravity a bone of this kind is seeded with.
     *
     * <p>An exhaustive {@code switch} rather than a lookup, so a new {@link PhysicsKind} cannot be added
     * without deciding what it hangs under.
     *
     * @param kind the kind being seeded
     * @return that kind's gravity seed
     */
    @Contract(pure = true)
    public float gravityFor(@NotNull PhysicsKind kind) {
        return switch (kind) {
            case PENDULUM -> this.pendulumGravity;
            case SPRING -> this.springGravity;
            case CLOTH_STRIP -> this.clothStripGravity;
            case DRAG -> this.dragGravity;
        };
    }

    /**
     * Checks these defaults against the operator's ranges and returns them, so it chains.
     *
     * <p>This is the check that keeps the two halves of {@code physics.yml} honest. A rig file may omit
     * every coefficient, in which case the profile that gets built is made entirely of these values and
     * is then validated against {@code bounds} — so defaults outside their own bounds mean every bone
     * that trusts a default is refused, one confusing per-bone warning at a time. Checked here instead,
     * it is one complaint about two keys.
     *
     * @param bounds the ranges a profile built from these defaults will be validated against
     * @return {@code this}
     * @throws IllegalArgumentException if the seeded mass is outside {@code [minMass, maxMass]}, or a
     *                                 seeded gravity or the seeded wind exceeds its ceiling
     */
    @Contract(value = "_ -> this", pure = true)
    public @NotNull ProfileDefaults requireWithin(@NotNull ProfileBounds bounds) {
        requireRange(this.mass, bounds.minMass(), bounds.maxMass(), "mass");
        requireRange(this.wind, 0F, bounds.maxWind(), "wind");
        for (final PhysicsKind kind : PhysicsKind.values()) {
            requireRange(gravityFor(kind), 0F, bounds.maxGravity(), gravityName(kind));
        }
        return this;
    }

    /**
     * The component name of a kind's gravity seed, for a message that names something editable.
     *
     * @param kind the kind whose seed is at fault
     * @return the component name
     */
    private static @NotNull String gravityName(@NotNull PhysicsKind kind) {
        return switch (kind) {
            case PENDULUM -> "pendulumGravity";
            case SPRING -> "springGravity";
            case CLOTH_STRIP -> "clothStripGravity";
            case DRAG -> "dragGravity";
        };
    }

    private static void requireRange(float value, float min, float max, @NotNull String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("The default " + name + " must be within the configured ["
                    + min + ", " + max + "] it is validated against, but was " + value);
        }
    }
}
