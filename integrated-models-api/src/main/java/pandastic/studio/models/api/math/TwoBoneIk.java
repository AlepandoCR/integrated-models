package pandastic.studio.models.api.math;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Closed-form inverse kinematics for a two-segment limb: given where the end should be, the angles
 * that put it there.
 *
 * <p><strong>Analytic, non-iterative, exact.</strong> Two applications of the law of cosines, no
 * convergence loop and no early exit. That matters beyond speed: an iterative solver's output depends
 * on its iteration count, so a limb solved under load would differ from the same limb solved idle —
 * and the difference reads as a foot sliding.
 *
 * <p>Angles come back in the limb's own plane, as radians about the pitch axis: the limb is assumed to
 * bend in one plane, which is what a knee and an elbow do. Everything is rotation, so the result drops
 * straight into a {@link pandastic.studio.models.api.pose.Pose} without violating its pivot invariant.
 */
public final class TwoBoneIk {

    private TwoBoneIk() {
    }

    /**
     * The two angles that place the limb's tip at the target.
     *
     * @param upperLength length of the segment attached to the root, in blocks
     * @param lowerLength length of the segment attached to the tip, in blocks
     * @param down        how far below the root the target sits
     * @param forward     how far in front of the root the target sits
     * @param bendSign    {@code +1} to bend one way, {@code -1} the other. A knee and an elbow fold in
     *                    opposite directions, and this is the only thing that distinguishes them
     * @param tolerance   how close the target has to be to the root to count as sitting on it. A
     *                    distance in blocks, so it belongs to whoever knows the scale the model was
     *                    authored at rather than to this solver
     * @return the rotation to apply to the upper and lower segments
     */
    @Contract(value = "_, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result solve(
            float upperLength,
            float lowerLength,
            float down,
            float forward,
            float bendSign,
            @NotNull IkTolerance tolerance
    ) {
        if (upperLength <= 0F || lowerLength <= 0F) {
            throw new IllegalArgumentException(
                    "Both segments need a positive length, got " + upperLength + " and " + lowerLength);
        }

        final double a = upperLength;
        final double b = lowerLength;
        final double distanceSquared = (double) down * down + (double) forward * forward;
        final double distance = Math.sqrt(distanceSquared);

        if (distance < tolerance.degenerateTargetBlocks()) {
            // Fully folded: the tip is at the root, so the lower segment doubles back on the upper.
            return new Result(0F, (float) (bendSign * Math.PI));
        }

        // Interior angle at the joint. Clamping the COSINE rather than the distance is what makes an
        // out-of-reach target straighten the limb instead of inverting the joint, and it costs no
        // branch: beyond reach the cosine saturates at -1 and the bend goes to zero.
        final double cosJoint = clamp((a * a + b * b - distanceSquared) / (2 * a * b));
        final double bend = Math.PI - Math.acos(cosJoint);

        // Angle between the upper segment and the line to the target.
        final double cosRoot = clamp((a * a + distanceSquared - b * b) / (2 * a * distance));
        final double root = Math.acos(cosRoot);

        final double toTarget = Math.atan2(forward, down);
        return new Result(
                (float) (toTarget - bendSign * root),
                (float) (bendSign * bend));
    }

    /**
     * Whether a target is within reach, which callers may want to know in order to lean or step rather
     * than let the limb straighten and fall short.
     */
    @Contract(pure = true)
    public static boolean isWithinReach(
            float upperLength,
            float lowerLength,
            float down,
            float forward
    ) {
        final double distanceSquared = (double) down * down + (double) forward * forward;
        final double reach = (double) upperLength + lowerLength;
        return distanceSquared <= reach * reach;
    }

    private static double clamp(double cosine) {
        if (cosine < -1D) {
            return -1D;
        }
        return cosine > 1D ? 1D : cosine;
    }

    /**
     * The solved limb.
     *
     * @param upperAngle rotation of the segment attached to the root, radians about pitch
     * @param lowerAngle rotation of the segment attached to the tip, relative to the upper one
     */
    public record Result(float upperAngle, float lowerAngle) {
    }
}
