package pandastic.studio.models.api.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IkToleranceTest {

    @Test
    void refusesANonPositiveOrNonFiniteTolerance() {
        assertAll(
                // Zero leaves atan2(0, 0) reachable, which is the exact case this value excludes.
                () -> assertRefused(0F),
                () -> assertRefused(-1.0E-4F),
                () -> assertRefused(Float.NaN),
                () -> assertRefused(Float.POSITIVE_INFINITY));
    }

    @Test
    void honoursADeliberatelyOddToleranceRatherThanSubstitutingADefault() {
        // A quarter of a block is four orders of magnitude above the removed 1.0E-4, so a target this
        // solver would once have solved normally must now fold completely. Nothing but the injected
        // value can produce that.
        final IkTolerance coarse = new IkTolerance(0.25F);
        assertEquals(0.25F, coarse.degenerateTargetBlocks());

        final TwoBoneIk.Result folded = TwoBoneIk.solve(1F, 1F, 0.2F, 0F, 1F, coarse);
        assertAll(
                () -> assertEquals(0F, folded.upperAngle()),
                () -> assertEquals((float) Math.PI, folded.lowerAngle(), 1.0E-5F));

        // The same target with a fine tolerance is solved rather than folded.
        final TwoBoneIk.Result solved = TwoBoneIk.solve(1F, 1F, 0.2F, 0F, 1F, new IkTolerance(1.0E-4F));
        assertTrue(solved.lowerAngle() < (float) Math.PI - 1.0E-3F,
                "a reachable target must not be treated as degenerate, got " + solved.lowerAngle());
    }

    private static void assertRefused(float value) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new IkTolerance(value));
        assertTrue(thrown.getMessage().contains("degenerateTargetBlocks"),
                "it must name the component, got: " + thrown.getMessage());
    }
}
