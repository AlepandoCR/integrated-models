package pandastic.studio.models.api.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoBoneIkTest {

    /** Assertion tolerance in blocks. The solver is analytic, so this only absorbs float rounding. */
    private static final float TOLERANCE = 1.0E-4F;

    /**
     * The degenerate-target tolerance handed to the solver, which is a different thing entirely: it is
     * the model-scale-dependent value the solver used to hardcode and now takes as an argument.
     */
    private static final IkTolerance TOLERANCE_VALUE = new IkTolerance(1.0E-4F);

    /**
     * Forward kinematics, so a reachable target can be checked by actually placing the limb rather
     * than by restating the solver's own arithmetic.
     */
    private static float[] tipOf(float upper, float lower, TwoBoneIk.Result result) {
        final double first = result.upperAngle();
        final double second = first + result.lowerAngle();
        return new float[]{
                (float) (upper * Math.cos(first) + lower * Math.cos(second)),
                (float) (upper * Math.sin(first) + lower * Math.sin(second))
        };
    }

    private static void assertReaches(float upper, float lower, float down, float forward, float bend) {
        final TwoBoneIk.Result result =
                TwoBoneIk.solve(upper, lower, down, forward, bend, TOLERANCE_VALUE);
        final float[] tip = tipOf(upper, lower, result);
        assertAll(
                () -> assertEquals(down, tip[0], TOLERANCE, "down component"),
                () -> assertEquals(forward, tip[1], TOLERANCE, "forward component"));
    }

    @Test
    void placesTheTipOnAReachableTarget() {
        assertReaches(1F, 1F, 1F, 0F, 1F);
        assertReaches(1F, 1F, 0.8F, 0.4F, 1F);
        assertReaches(0.9F, 0.6F, 1.1F, -0.3F, 1F);
        assertReaches(0.5F, 1.2F, 0.6F, 0.9F, 1F);
    }

    @Test
    void mirroringTheBendStillReachesTheTarget() {
        assertReaches(1F, 1F, 0.8F, 0.4F, -1F);
        assertReaches(0.9F, 0.6F, 1.1F, -0.3F, -1F);
    }

    @Test
    void bendSignFlipsTheJointButNotTheTarget() {
        final TwoBoneIk.Result knee = TwoBoneIk.solve(1F, 1F, 1F, 0F, 1F, TOLERANCE_VALUE);
        final TwoBoneIk.Result elbow = TwoBoneIk.solve(1F, 1F, 1F, 0F, -1F, TOLERANCE_VALUE);

        assertAll(
                () -> assertEquals(-knee.upperAngle(), elbow.upperAngle(), TOLERANCE),
                () -> assertEquals(-knee.lowerAngle(), elbow.lowerAngle(), TOLERANCE),
                () -> assertTrue(knee.lowerAngle() > 0F, "a knee folds one way"),
                () -> assertTrue(elbow.lowerAngle() < 0F, "an elbow folds the other"));
    }

    @Test
    void straightensRatherThanInvertsWhenOutOfReach() {
        final TwoBoneIk.Result result = TwoBoneIk.solve(1F, 1F, 5F, 0F, 1F, TOLERANCE_VALUE);

        assertAll(
                // The joint goes straight: clamping the cosine saturates the bend at zero.
                () -> assertEquals(0F, result.lowerAngle(), TOLERANCE),
                // And the limb points at the target rather than away from it.
                () -> assertEquals(0F, result.upperAngle(), TOLERANCE));

        final float[] tip = tipOf(1F, 1F, result);
        assertAll(
                () -> assertEquals(2F, tip[0], TOLERANCE, "fully extended toward the target"),
                () -> assertEquals(0F, tip[1], TOLERANCE));
    }

    @Test
    void aimsAtAnOutOfReachTargetThatIsOffAxis() {
        final TwoBoneIk.Result result = TwoBoneIk.solve(1F, 1F, 3F, 3F, 1F, TOLERANCE_VALUE);
        // Straight, and pointing along the 45-degree line to the target.
        assertAll(
                () -> assertEquals(0F, result.lowerAngle(), TOLERANCE),
                () -> assertEquals(Math.PI / 4, result.upperAngle(), TOLERANCE));
    }

    @Test
    void foldsCompletelyOnADegenerateTarget() {
        final TwoBoneIk.Result result = TwoBoneIk.solve(1F, 1F, 0F, 0F, 1F, TOLERANCE_VALUE);
        assertAll(
                () -> assertEquals(0F, result.upperAngle()),
                () -> assertEquals((float) Math.PI, result.lowerAngle(), TOLERANCE));

        final TwoBoneIk.Result mirrored = TwoBoneIk.solve(1F, 1F, 0F, 0F, -1F, TOLERANCE_VALUE);
        assertEquals((float) -Math.PI, mirrored.lowerAngle(), TOLERANCE);
    }

    @Test
    void neverProducesNonFiniteAngles() {
        final float[][] targets = {
                {0F, 0F}, {100F, 100F}, {-50F, 0F}, {0F, -50F}, {1.0E-5F, 0F}
        };
        for (final float[] target : targets) {
            final TwoBoneIk.Result result =
                    TwoBoneIk.solve(1F, 1F, target[0], target[1], 1F, TOLERANCE_VALUE);
            assertTrue(Float.isFinite(result.upperAngle()), "upper finite for " + target[0]);
            assertTrue(Float.isFinite(result.lowerAngle()), "lower finite for " + target[1]);
        }
    }

    @Test
    void reportsReach() {
        assertAll(
                () -> assertTrue(TwoBoneIk.isWithinReach(1F, 1F, 1F, 0F)),
                () -> assertTrue(TwoBoneIk.isWithinReach(1F, 1F, 2F, 0F), "exactly extended counts"),
                () -> assertFalse(TwoBoneIk.isWithinReach(1F, 1F, 2.1F, 0F)),
                () -> assertFalse(TwoBoneIk.isWithinReach(0.5F, 0.5F, 1F, 1F)));
    }

    @Test
    void refusesADegenerateSegment() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> TwoBoneIk.solve(0F, 1F, 1F, 0F, 1F, TOLERANCE_VALUE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> TwoBoneIk.solve(1F, -1F, 1F, 0F, 1F, TOLERANCE_VALUE)));
    }
}
