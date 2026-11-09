package pandastic.studio.models.api.decoration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a placement refuses, and the two derived values the layers above it rely on.
 *
 * <p>The refusals are the point. A non-finite coordinate here would reach the renderer as a teleport
 * packet whose position is {@code NaN}, which on a client is a model that disappears rather than one
 * that looks wrong — and the only place to catch it is where the value is made, because every layer
 * below this one treats a placement as already valid.
 */
class DecorationPlacementTest {

    private static final double TOLERANCE = 1.0E-9D;

    @Test
    void aNonFinitePositionIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecorationPlacement(Double.NaN, 70D, 0D, 0F, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecorationPlacement(0D, Double.POSITIVE_INFINITY, 0D, 0F, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecorationPlacement(0D, 70D, Double.NEGATIVE_INFINITY, 0F, 0F)));
    }

    @Test
    void aNonFiniteRotationIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecorationPlacement(0D, 70D, 0D, Float.NaN, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecorationPlacement(0D, 70D, 0D, 0F, Float.NaN)));
    }

    @Test
    void anUnpitchedPlacementIsTheCommonCase() {
        final DecorationPlacement upright = DecorationPlacement.of(11.5D, 64.25D, -7.5D, 33F);

        assertAll(
                () -> assertEquals(11.5D, upright.x(), TOLERANCE),
                () -> assertEquals(64.25D, upright.y(), TOLERANCE),
                () -> assertEquals(-7.5D, upright.z(), TOLERANCE),
                () -> assertEquals(33F, upright.yawDegrees()),
                () -> assertEquals(0F, upright.pitchDegrees()));
    }

    @Test
    void movingKeepsTheOrientationAndTurningKeepsThePosition() {
        final DecorationPlacement tilted = new DecorationPlacement(11.5D, 64.25D, -7.5D, 33F, -12F);

        final DecorationPlacement moved = tilted.at(1.5D, 2.5D, 3.5D);
        final DecorationPlacement turned = tilted.withYaw(90F);

        assertAll(
                () -> assertEquals(33F, moved.yawDegrees()),
                () -> assertEquals(-12F, moved.pitchDegrees()),
                () -> assertEquals(1.5D, moved.x(), TOLERANCE),
                () -> assertEquals(90F, turned.yawDegrees()),
                () -> assertEquals(-12F, turned.pitchDegrees()),
                () -> assertEquals(11.5D, turned.x(), TOLERANCE));
    }

    @Test
    void yawIsOfferedInRadiansBecauseEveryProjectionNeedsIt() {
        final DecorationPlacement quarterTurn = DecorationPlacement.of(0D, 70D, 0D, 90F);

        assertEquals(Math.PI / 2D, quarterTurn.yawRadians(), TOLERANCE);
    }

    @Test
    void placementsCompareByValueSoAnUnmovedDecorationIsFreeToDetect() {
        final DecorationPlacement here = new DecorationPlacement(11.5D, 64.25D, -7.5D, 33F, 0F);

        assertAll(
                // The driver loop's cheapest and most common path: a placement that equals the previous
                // one is a decoration nobody moved, answered without arithmetic or allocation.
                () -> assertEquals(here, new DecorationPlacement(11.5D, 64.25D, -7.5D, 33F, 0F)),
                () -> assertNotEquals(here, here.withYaw(34F)),
                () -> assertNotEquals(here, new DecorationPlacement(11.5D, 64.25D, -7.5D, 33F, 1F)));
    }
}
