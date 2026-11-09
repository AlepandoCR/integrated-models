package pandastic.studio.models.api.rig;

/**
 * The ranges a {@link PhysicsProfile} is accepted within, and the two derived values a rig compile needs.
 *
 * <p>These are bounds rather than calibration: they say what a rig author may write, not what the solver
 * does with it. They belong to configuration because the honest answer to "how heavy may a bone be" is
 * "whatever this server's models are scaled for" — a range tuned for player-sized humanoids refuses
 * every coefficient a thirty-block boss needs, and a range wide enough for both admits tunings that
 * diverge for the small ones.
 *
 * <p>Note that staying inside every range here is <strong>not</strong> sufficient for stability. The
 * damping term carries its own, tighter bound that couples to the step size — see
 * {@link pandastic.studio.models.api.physics.PhysicsTuning#dampingToRate()} — and a solver checks it
 * separately at rig compile time.
 *
 * @param minMass                  floor on {@link PhysicsProfile#mass()}. Mass divides the restoring and
 *                                 damping terms, so a light bone is a fast one and therefore the
 *                                 unstable one; this floor is what keeps the stiffest legal tuning
 *                                 integrable at the configured step
 * @param maxMass                  ceiling on {@link PhysicsProfile#mass()}
 * @param maxGravity               ceiling on {@link PhysicsProfile#gravity()}
 * @param maxWind                  ceiling on {@link PhysicsProfile#wind()}
 * @param maxCollisionRadius       ceiling on {@link PhysicsProfile#collisionRadius()}, in blocks
 * @param collisionRadiusToRadians blocks of clearance to radians of swing, used to fold a
 *                                 {@link PhysicsKind#CLOTH_STRIP}'s collision cone into a plain pitch
 *                                 clamp at rig compile time. A chord-to-angle approximation, so it is
 *                                 only right for strips of roughly the length it was calibrated against
 * @param defaultLimitDegrees      half-width of the symmetric angular bound applied when a rig omits one
 */
public record ProfileBounds(
        float minMass,
        float maxMass,
        float maxGravity,
        float maxWind,
        float maxCollisionRadius,
        float collisionRadiusToRadians,
        float defaultLimitDegrees
) {

    public ProfileBounds {
        // A mass floor of zero would admit a division by zero in the solver's restoring term.
        requirePositive(minMass, "minMass");
        requirePositive(maxMass, "maxMass");
        requireOrdered(minMass, maxMass, "minMass", "maxMass");
        requireNonNegative(maxGravity, "maxGravity");
        requireNonNegative(maxWind, "maxWind");
        requireNonNegative(maxCollisionRadius, "maxCollisionRadius");
        // Zero would fold every cloth strip's clearance into a pitch ceiling of zero, freezing it
        // against its own pivot — the opposite of what declaring a clearance is asking for.
        requirePositive(collisionRadiusToRadians, "collisionRadiusToRadians");
        requireNonNegative(defaultLimitDegrees, "defaultLimitDegrees");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(name + " must be finite and at least 0 but was " + value);
        }
    }

    private static void requireOrdered(float min, float max, String minName, String maxName) {
        if (min > max) {
            throw new IllegalArgumentException(
                    "Inverted range: " + minName + "=" + min + " > " + maxName + "=" + max
                            + ", which would refuse every value");
        }
    }
}
