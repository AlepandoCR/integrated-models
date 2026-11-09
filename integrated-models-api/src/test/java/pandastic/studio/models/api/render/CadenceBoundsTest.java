package pandastic.studio.models.api.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CadenceBoundsTest {

    @Test
    void refusesAFloorBelowOneBecauseZeroIsASnapRatherThanAWindow() {
        assertAll(
                () -> assertRefused(0, 59, "minInterpolationTicks"),
                () -> assertRefused(-1, 59, "minInterpolationTicks"));
    }

    @Test
    void refusesAnInvertedRange() {
        assertAll(
                () -> assertRefused(10, 4, "Inverted range"),
                // Equal is not inverted: pinning the window to one value is a legitimate choice.
                () -> assertDoesNotThrow(() -> new CadenceBounds(4, 4)));
    }

    @Test
    void honoursADeliberatelyOddConfigurationRatherThanSubstitutingADefault() {
        // A floor of 3 and a ceiling of 7 bracket out both removed constants (1 and 59), so every
        // assertion below fails if either one is still doing the clamping.
        final CadenceBounds odd = new CadenceBounds(3, 7);
        assertAll(
                () -> assertEquals(3, odd.minInterpolationTicks()),
                () -> assertEquals(7, odd.maxInterpolationTicks()),
                // 20 Hz wants a 1-tick window; the configured floor raises it to 3.
                () -> assertEquals(3, EmissionCadence.of(60, 20, odd).interpolationTicks()),
                // 1 Hz wants 20; the configured ceiling lowers it to 7.
                () -> assertEquals(7, EmissionCadence.of(60, 1, odd).interpolationTicks()),
                // And the cadence keeps the bounds, so widening respects the same ceiling.
                () -> assertEquals(odd, EmissionCadence.of(60, 20, odd).bounds()),
                () -> assertEquals(4,
                        EmissionCadence.of(60, 20, odd).withJitterTolerance().interpolationTicks()));
    }

    @Test
    void theCeilingBindsHoweverManyTimesTheWindowIsWidened() {
        final CadenceBounds odd = new CadenceBounds(3, 7);
        EmissionCadence widened = EmissionCadence.of(60, 20, odd);
        for (int i = 0; i < 50; i++) {
            widened = widened.withJitterTolerance();
        }
        assertEquals(7, widened.interpolationTicks());
    }

    private static void assertRefused(int min, int max, String expectedInMessage) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CadenceBounds(min, max));
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must say what is wrong, got: " + thrown.getMessage());
    }
}
