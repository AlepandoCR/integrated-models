package pandastic.studio.models.api.physics;

/**
 * How large a per-tick position delta may be before {@link PhysicsDriver#withoutTeleports(DriverBounds)}
 * treats it as a teleport rather than as movement.
 *
 * <p>Separate from {@link PhysicsTuning} because it is a <em>filter</em> on the input rather than a
 * calibration of the solver: it answers "did this entity move or was it moved", which depends on what
 * the server does to entities — elytra, riptide, vehicles, plugins that set positions directly — and not
 * on how a spring is integrated.
 *
 * <p>Both numbers are operator-facing for the same reason. A delta that a vanilla server would never
 * produce is routine on a server with movement plugins, and a threshold too low silently discards real
 * movement — the model stops reacting to the thing it is attached to, with nothing in the log. Too high
 * and a teleport reaches the wind term, which launches every bone in the rig at once.
 *
 * @param maxStepBlocks         largest horizontal per-tick translation treated as real movement
 * @param maxVerticalStepBlocks the same vertically, where falling legitimately covers far more ground
 *                              in one tick than walking ever does
 */
public record DriverBounds(float maxStepBlocks, float maxVerticalStepBlocks) {

    public DriverBounds {
        requirePositive(maxStepBlocks, "maxStepBlocks");
        requirePositive(maxVerticalStepBlocks, "maxVerticalStepBlocks");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            // Zero would classify every movement as a teleport and zero the driver permanently, so the
            // physics would never be driven at all — a failure that looks like "physics is broken".
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }
}
