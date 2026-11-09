package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigBoundsTest {

    @Test
    void refusesACeilingBelowOne() {
        // A rig with no bones is refused at build time anyway, so a ceiling below one would make every
        // rig on the server unloadable rather than merely limited.
        assertAll(
                () -> assertRefused(0),
                () -> assertRefused(-1),
                () -> assertRefused(Integer.MIN_VALUE),
                () -> assertDoesNotThrow(() -> new RigBounds(1)));
    }

    @Test
    void honoursADeliberatelyOddConfigurationRatherThanSubstitutingADefault() {
        // The removed constant was 256. A ceiling of 5 is honoured literally; a surviving fallback would
        // let the sixth bone through.
        final RigBounds odd = new RigBounds(5);
        assertEquals(5, odd.maxBones());

        final Rig.Builder builder =
                Rig.builder("odd", "m", odd, RigFixtures.wideProfileBounds()).root("b0");
        for (int i = 1; i < 5; i++) {
            final int index = i;
            assertDoesNotThrow(() -> builder.bone("b" + index, "b0", null));
        }
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.bone("b5", "b0", null));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("5-bone ceiling"),
                        "it must state the configured ceiling, got: " + thrown.getMessage()),
                () -> assertEquals(5, builder.build().boneCount()));
    }

    private static void assertRefused(int maxBones) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new RigBounds(maxBones));
        assertTrue(thrown.getMessage().contains("maxBones"),
                "it must name the component, got: " + thrown.getMessage());
    }
}
