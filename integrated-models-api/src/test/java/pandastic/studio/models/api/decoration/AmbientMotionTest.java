package pandastic.studio.models.api.decoration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One decoration's exposure to the ambient field.
 *
 * <p>The identity of {@link AmbientMotion#NONE} is load-bearing rather than cosmetic: the driver loop
 * tests exposure before it does any arithmetic, and the whole bandwidth argument is that a sheltered
 * decoration is published the shared motionless driver and therefore settles.
 */
class AmbientMotionTest {

    @Test
    void shelteredAndExposedAreTheTwoIdentities() {
        assertAll(
                () -> assertEquals(0F, AmbientMotion.NONE.strength()),
                () -> assertTrue(AmbientMotion.NONE.isStill()),
                // The neutral multiplier, so the field an operator calibrated is the field a fully
                // exposed decoration feels — which is what makes the configured defaults describe
                // something observable.
                () -> assertEquals(1F, AmbientMotion.EXPOSED.strength()),
                () -> assertFalse(AmbientMotion.EXPOSED.isStill()));
    }

    @Test
    void zeroExposureIsTheSharedConstant() {
        // By identity: the layer below compares against the constant before it does any work, and a
        // separate equal instance would quietly cost every sheltered decoration a full evaluation of
        // the field.
        assertSame(AmbientMotion.NONE, AmbientMotion.of(0F));
    }

    @Test
    void anExposureAboveFullIsLegal() {
        // A flag on a mast reasonably catches more wind than the calibration's nominal exposure, and a
        // ceiling here would be a number invented in the api to bound a field configured elsewhere.
        final AmbientMotion gale = AmbientMotion.of(2.5F);

        assertAll(
                () -> assertEquals(2.5F, gale.strength()),
                () -> assertFalse(gale.isStill()));
    }

    @Test
    void aNegativeOrNonFiniteExposureIsRefused() {
        assertAll(
                // Negative exposure would be a wind that blows the other way for one decoration, which
                // is a direction rather than an exposure — and the direction is configuration.
                () -> assertThrows(IllegalArgumentException.class, () -> AmbientMotion.of(-0.5F)),
                () -> assertThrows(IllegalArgumentException.class, () -> AmbientMotion.of(Float.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AmbientMotion.of(Float.POSITIVE_INFINITY)));
    }
}
