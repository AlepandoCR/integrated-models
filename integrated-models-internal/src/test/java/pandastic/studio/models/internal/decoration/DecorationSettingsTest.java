package pandastic.studio.models.internal.decoration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the decoration layer's settings refuse, and the two numbers they derive.
 *
 * <p>The record throws rather than clamping, which is the same split the rest of the project makes: a
 * rig file is written by a human and is clamped with a warning, while these values arrive from a
 * validator that has already refused the whole reload — so anything reaching this constructor out of
 * range is a defect in the mapper rather than operator input, and swallowing it would hide which of the
 * two is wrong.
 */
class DecorationSettingsTest {

    private static final double TOLERANCE = 1.0E-9D;

    /** The field every case below mutates one component of. */
    private static DecorationSettings settings(
            int driverUpdateHz,
            float base,
            float gust,
            float gustRate,
            float ratio,
            float weight,
            float wavelength,
            float bearing,
            float sway,
            float swayRate,
            float vertical
    ) {
        return new DecorationSettings(driverUpdateHz, base, gust, gustRate, ratio, weight, wavelength,
                bearing, sway, swayRate, vertical);
    }

    private static DecorationSettings valid() {
        return settings(20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aPassRateBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(0, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
    }

    @Test
    void negativeAirSpeedsAreRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, -0.01F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, -0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)));
    }

    @Test
    void aFrozenGustIsRefusedBecauseItWouldBeAPerPositionConstant() {
        // Zero is not "no gust": the gust's phase carries a spatial term, so a frozen oscillation
        // leaves every decoration at its own constant offset — a wind that varies from one banner to
        // the next and never changes. Zeroing the gust *speed* is how an operator turns gusting off.
        assertThrows(IllegalArgumentException.class,
                () -> settings(20, 0.03F, 0.05F, 0F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
    }

    @Test
    void aZeroHarmonicRatioIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(20, 0.03F, 0.05F, 0.37F, 0F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
    }

    @Test
    void aHarmonicWeightOutsideAFractionIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, -0.1F, 17F, 33F, 9F, 0.11F, 0.3F)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 1.1F, 17F, 33F, 9F, 0.11F, 0.3F)));
    }

    @Test
    void aZeroWavelengthIsRefusedBecauseItIsDividedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 0F, 33F, 9F, 0.11F, 0.3F));
    }

    @Test
    void aNonFiniteBearingIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, Float.NaN, 9F, 0.11F, 0.3F)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, Float.POSITIVE_INFINITY, 9F, 0.11F,
                        0.3F)));
    }

    @Test
    void negativeSwayAndNegativeLiftAreRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, -1F, 0.11F, 0.3F)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, -0.11F, 0.3F)),
                () -> assertThrows(IllegalArgumentException.class, () -> settings(
                        20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, -0.3F)));
    }

    @Test
    void aSwayRateOfZeroIsAccepted() {
        // Deliberately legal where a gust rate of zero is not: a sway of zero frequency contributes
        // exactly no sway, because the sway's phase has no spatial term to leave behind.
        final DecorationSettings frozen =
                settings(20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0F, 0.3F);

        assertEquals(0F, frozen.windDirectionSwayRateHz());
    }

    // ------------------------------------------------------------------ derived

    @Test
    void thePassPeriodComesFromTheConfiguredRate() {
        assertAll(
                () -> assertEquals(50_000_000L, valid().periodNanos()),
                // A rate that does not divide a thousand, which is exactly why the period is kept in
                // nanoseconds: in milliseconds this would round to 143 and run 0.1% slow.
                () -> assertEquals(142_857_142L,
                        settings(7, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                                .periodNanos()));
    }

    @Test
    void movementIsScaledByHowMuchOfATickAPassCovers() {
        assertAll(
                // Twenty passes a second is one per tick, so a move needs no conversion at all — which
                // is the case the loop short-circuits.
                () -> assertEquals(1D, valid().passToTickScale(), TOLERANCE),
                // Five passes a second is one per four ticks, so a delta measured over a pass is four
                // ticks' worth of movement.
                () -> assertEquals(4D,
                        settings(5, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                                .passToTickScale(), TOLERANCE),
                () -> assertEquals(0.5D,
                        settings(40, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                                .passToTickScale(), TOLERANCE));
    }

    @Test
    void aFieldIsCalmExactlyWhenNeitherComponentMoves() {
        assertAll(
                () -> assertFalse(valid().isCalm()),
                // A steady wind is not calm: it deflects, which is the half of the look that costs
                // nothing because the solver settles under a constant force.
                () -> assertFalse(settings(20, 0.03F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                        .isCalm()),
                () -> assertFalse(settings(20, 0F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                        .isCalm()),
                () -> assertTrue(settings(20, 0F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F)
                        .isCalm()));
    }
}
