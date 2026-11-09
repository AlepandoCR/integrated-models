package pandastic.studio.models.api.locomotion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingBoundsTest {

    @Test
    void refusesANonPositiveFloorOrCeiling() {
        assertAll(
                () -> assertRefused(() -> new BindingBounds(0F, 1F, 0.5F, 2F, 2F), "minSegmentLength"),
                () -> assertRefused(() -> new BindingBounds(-1F, 1F, 0.5F, 2F, 2F), "minSegmentLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 0F, 0.5F, 2F, 2F), "maxSegmentLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 0F, 2F, 2F), "minStrideLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 0.5F, 0F, 2F), "maxStrideLength"),
                // A scale ceiling of zero would force every gait scale to zero, i.e. refuse to animate.
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 0.5F, 2F, 0F), "maxScale"));
    }

    @Test
    void refusesAnInvertedRange() {
        assertAll(
                () -> assertRefused(() -> new BindingBounds(2F, 1F, 0.5F, 2F, 2F), "Inverted range"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 3F, 2F, 2F), "Inverted range"),
                // Equal is not inverted: a configuration pinning a single legal value is legitimate.
                () -> assertDoesNotThrow(() -> new BindingBounds(1F, 1F, 2F, 2F, 1F)));
    }

    @Test
    void refusesNaNAndInfinity() {
        assertAll(
                () -> assertRefused(() -> new BindingBounds(Float.NaN, 1F, 0.5F, 2F, 2F),
                        "minSegmentLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, Float.NaN, 0.5F, 2F, 2F),
                        "maxSegmentLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, Float.NaN, 2F, 2F),
                        "minStrideLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 0.5F, Float.NaN, 2F),
                        "maxStrideLength"),
                () -> assertRefused(() -> new BindingBounds(0.1F, 1F, 0.5F, 2F, Float.NaN), "maxScale"),
                () -> assertRefused(
                        () -> new BindingBounds(0.1F, Float.POSITIVE_INFINITY, 0.5F, 2F, 2F),
                        "maxSegmentLength"));
    }

    @Test
    void honoursADeliberatelyOddConfigurationRatherThanSubstitutingADefault() {
        // None of these five numbers is one the removed constants could produce, so every assertion
        // below fails if a hardcoded fallback survived anywhere on the path.
        final BindingBounds odd = new BindingBounds(0.019F, 0.44F, 3.1F, 3.2F, 0.6F);

        assertAll(
                () -> assertEquals(0.019F, odd.minSegmentLength()),
                () -> assertEquals(0.44F, odd.maxSegmentLength()),
                () -> assertEquals(3.1F, odd.minStrideLength()),
                () -> assertEquals(3.2F, odd.maxStrideLength()),
                () -> assertEquals(0.6F, odd.maxScale()),
                // The stride range is a narrow window the old defaults sit entirely outside of.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> toned(1.3F).requireWithin(odd)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> toned(8F).requireWithin(odd)),
                () -> assertDoesNotThrow(() -> toned(3.15F).requireWithin(odd)),
                // And the scale ceiling is below the calibrated 1, so even an unscaled profile is out.
                () -> assertThrows(IllegalArgumentException.class, () -> GaitProfile
                        .builder(GaitFixtures.tuning()).strideLength(3.15F).legSwing(1F).build()
                        .requireWithin(odd)));
    }

    /** A profile whose scales all sit under the odd 0.6 ceiling, so only the stride is under test. */
    private static GaitProfile toned(float strideLength) {
        return GaitProfile.builder(GaitFixtures.tuning())
                .strideLength(strideLength)
                .legSwing(0.5F)
                .armSwing(0.5F)
                .torsoSway(0.5F)
                .breathDepth(0.5F)
                .build();
    }

    private static void assertRefused(Runnable construction, String expectedInMessage) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, construction::run);
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must say what is wrong, got: " + thrown.getMessage());
    }
}
