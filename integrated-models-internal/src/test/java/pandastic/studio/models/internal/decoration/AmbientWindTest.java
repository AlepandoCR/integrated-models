package pandastic.studio.models.internal.decoration;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.PhysicsDriver;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ambient field, driven as the pure function it is.
 *
 * <h2>Every coefficient is proved read, and proved with an odd value</h2>
 * Every field below is built from numbers that appear nowhere in the shipped configuration, and every
 * assertion is an exact arithmetic consequence of one of them. A test using plausible figures cannot
 * distinguish "reads its configuration" from "ignores it and happens to agree", which for a layer whose
 * entire purpose is to be configurable is the only thing worth proving.
 *
 * <h2>The phases are chosen so the expected value is exact</h2>
 * A quarter cycle puts a sine at exactly one, so the gust is {@code ±1} rather than something to be
 * approximated — which lets the assertions name the coefficient they are testing instead of a number
 * derived from three of them.
 */
class AmbientWindTest {

    private static final float TOLERANCE = 1.0E-6F;

    /** Nothing round, and off the origin, so a dropped spatial term shows up. */
    private static final DecorationPlacement AT_ORIGIN = new DecorationPlacement(0D, 70D, 0D, 0F, 0F);

    /**
     * A field with one steady component and nothing else moving: no gust, no sway.
     *
     * @param baseSpeed the steady air speed
     * @param bearing   the direction the wind blows toward, in degrees
     */
    private static DecorationSettings steady(float baseSpeed, float bearing) {
        return new DecorationSettings(
                20, baseSpeed, 0F, 0.5F, 3F, 0F, 17F, bearing, 0F, 0F, 0.3F);
    }

    /**
     * A field that is nothing but a gust, at half a hertz, so a quarter cycle is exactly half a second.
     *
     * @param gustSpeed        the gust amplitude
     * @param harmonicWeight   how much of it comes from the second oscillation
     * @param verticalFraction how much of it lifts
     */
    private static DecorationSettings gusting(
            float gustSpeed,
            float harmonicWeight,
            float verticalFraction
    ) {
        return new DecorationSettings(
                20, 0F, gustSpeed, 0.5F, 3F, harmonicWeight, 17F, 0F, 0F, 0F, verticalFraction);
    }

    // ------------------------------------------------------------------ stillness

    @Test
    void aShelteredDecorationGetsTheStillConstantItself() {
        final PhysicsDriver driver =
                AmbientWind.driverAt(steady(0.25F, 0F), AmbientMotion.NONE, AT_ORIGIN, 3.25D);

        // Identity, not equality: a calm server must allocate nothing per decoration per pass, and the
        // whole at-rest argument rests on the published value being this exact object.
        assertSame(PhysicsDriver.STILL, driver);
    }

    @Test
    void aCalmFieldGetsTheStillConstantEvenWhenFullyExposed() {
        final DecorationSettings calm = new DecorationSettings(
                20, 0F, 0F, 0.5F, 3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F);

        assertAll(
                () -> assertTrue(calm.isCalm()),
                () -> assertSame(PhysicsDriver.STILL,
                        AmbientWind.driverAt(calm, AmbientMotion.EXPOSED, AT_ORIGIN, 3.25D)));
    }

    // ------------------------------------------------------------------ the steady half

    @Test
    void aSteadyWindIsConstantInTimeWhichIsWhyItCostsNothing() {
        final DecorationSettings field = steady(0.25F, 0F);

        final PhysicsDriver first = AmbientWind.driverAt(field, AmbientMotion.EXPOSED, AT_ORIGIN, 0D);
        final PhysicsDriver later = AmbientWind.driverAt(field, AmbientMotion.EXPOSED, AT_ORIGIN, 97.5D);

        assertAll(
                // The documented claim that a steady wind is free: it is a constant force, so the
                // solver settles under it and the emission gate goes quiet. That only holds if the
                // driver genuinely does not change.
                () -> assertEquals(first, later),
                // A bearing of zero blows toward +Z, so a decoration facing +Z is moving backwards
                // relative to the air at exactly the configured speed.
                () -> assertEquals(-0.25F, first.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, first.sideSpeed(), TOLERANCE),
                () -> assertEquals(0F, first.verticalSpeed(), TOLERANCE),
                () -> assertEquals(0F, first.yawRateRadians(), TOLERANCE));
    }

    @Test
    void theBearingIsReadFromConfiguration() {
        // Ninety degrees clockwise from +Z is -X, so the air moves along -X and the decoration's
        // velocity relative to it is +X — which for a decoration facing +Z is entirely sideways.
        final PhysicsDriver driver =
                AmbientWind.driverAt(steady(0.25F, 90F), AmbientMotion.EXPOSED, AT_ORIGIN, 4D);

        assertAll(
                () -> assertEquals(0F, driver.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0.25F, driver.sideSpeed(), TOLERANCE));
    }

    @Test
    void theWindIsProjectedIntoTheDecorationsOwnFrame() {
        final DecorationSettings field = steady(0.25F, 0F);
        final DecorationPlacement turned = new DecorationPlacement(0D, 70D, 0D, 90F, 0F);

        final PhysicsDriver driver = AmbientWind.driverAt(field, AmbientMotion.EXPOSED, turned, 4D);

        // The same wind, on a decoration turned a quarter turn: what was along its facing is now along
        // its side. Without the projection a rotated banner would be driven as though it faced north.
        assertAll(
                () -> assertEquals(0F, driver.forwardSpeed(), TOLERANCE),
                () -> assertEquals(-0.25F, driver.sideSpeed(), TOLERANCE));
    }

    // ------------------------------------------------------------------ the gusting half

    @Test
    void theGustAmplitudeAndVerticalFractionAreReadFromConfiguration() {
        // A quarter cycle at half a hertz is half a second, where the primary sine is exactly one.
        final PhysicsDriver peak = AmbientWind.driverAt(
                gusting(0.4F, 0F, 0.3F), AmbientMotion.EXPOSED, AT_ORIGIN, 0.5D);

        assertAll(
                () -> assertEquals(-0.4F, peak.forwardSpeed(), TOLERANCE),
                // Only the gust lifts, and it lifts by the configured fraction of its own amplitude.
                () -> assertEquals(-0.12F, peak.verticalSpeed(), TOLERANCE));
    }

    @Test
    void theGustIsZeroAtItsOwnOriginSoAFieldStartsWhereTheSteadyOneIs() {
        final PhysicsDriver atOrigin = AmbientWind.driverAt(
                gusting(0.4F, 0F, 0.3F), AmbientMotion.EXPOSED, AT_ORIGIN, 0D);

        // Not decoration: it is what makes the first pass of the driver loop deterministic, and the
        // reason a decoration does not jump the instant it is placed.
        assertAll(
                () -> assertEquals(0F, atOrigin.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, atOrigin.verticalSpeed(), TOLERANCE));
    }

    @Test
    void theHarmonicRatioAndWeightAreReadFromConfiguration() {
        final PhysicsDriver primary = AmbientWind.driverAt(
                gusting(0.4F, 0F, 0F), AmbientMotion.EXPOSED, AT_ORIGIN, 0.5D);
        final PhysicsDriver harmonic = AmbientWind.driverAt(
                gusting(0.4F, 1F, 0F), AmbientMotion.EXPOSED, AT_ORIGIN, 0.5D);

        assertAll(
                // At a quarter cycle the primary sine is +1 and the third harmonic is -1, so a field
                // made only of the harmonic blows the opposite way. Nothing but reading both the ratio
                // and the weight produces that.
                () -> assertEquals(-0.4F, primary.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0.4F, harmonic.forwardSpeed(), TOLERANCE));
    }

    @Test
    void aGustVariesOverTimeWhichIsWhyItCostsBandwidth() {
        final DecorationSettings field = gusting(0.4F, 0.4F, 0.3F);

        final PhysicsDriver first =
                AmbientWind.driverAt(field, AmbientMotion.EXPOSED, AT_ORIGIN, 0.25D);
        final PhysicsDriver later =
                AmbientWind.driverAt(field, AmbientMotion.EXPOSED, AT_ORIGIN, 0.6D);

        assertNotEquals(first, later);
    }

    // ------------------------------------------------------------------ space and exposure

    @Test
    void theGustTravelsSoNeighboursAreOutOfPhaseByTheConfiguredWavelength() {
        final DecorationSettings field = gusting(0.4F, 0F, 0F);
        // The bearing is zero, so distance along the wind is distance along +Z.
        final DecorationPlacement upwind = new DecorationPlacement(0D, 70D, 0D, 0F, 0F);
        final DecorationPlacement aWavelengthAway = new DecorationPlacement(0D, 70D, 17D, 0F, 0F);
        final DecorationPlacement halfAWavelengthAway = new DecorationPlacement(0D, 70D, 8.5D, 0F, 0F);

        final PhysicsDriver here = AmbientWind.driverAt(field, AmbientMotion.EXPOSED, upwind, 0.5D);
        final PhysicsDriver full =
                AmbientWind.driverAt(field, AmbientMotion.EXPOSED, aWavelengthAway, 0.5D);
        final PhysicsDriver half =
                AmbientWind.driverAt(field, AmbientMotion.EXPOSED, halfAWavelengthAway, 0.5D);

        assertAll(
                // A full wavelength is a full cycle: the same part of the weather, which is what makes
                // the wavelength the thing that decorrelates a row of banners rather than a tuning.
                () -> assertEquals(here.forwardSpeed(), full.forwardSpeed(), TOLERANCE),
                // Half a wavelength is the opposite part of it.
                () -> assertEquals(-here.forwardSpeed(), half.forwardSpeed(), TOLERANCE));
    }

    @Test
    void exposureScalesTheWholeFieldLinearly() {
        final DecorationSettings field = gusting(0.4F, 0.4F, 0.3F);

        final PhysicsDriver exposed =
                AmbientWind.driverAt(field, AmbientMotion.EXPOSED, AT_ORIGIN, 1.37D);
        final PhysicsDriver sheltered =
                AmbientWind.driverAt(field, AmbientMotion.of(0.25F), AT_ORIGIN, 1.37D);

        assertAll(
                () -> assertEquals(exposed.forwardSpeed() * 0.25F, sheltered.forwardSpeed(), TOLERANCE),
                () -> assertEquals(exposed.sideSpeed() * 0.25F, sheltered.sideSpeed(), TOLERANCE),
                () -> assertEquals(exposed.verticalSpeed() * 0.25F, sheltered.verticalSpeed(),
                        TOLERANCE));
    }

    @Test
    void theDirectionSwayAmplitudeAndRateAreReadFromConfiguration() {
        // A quarter cycle of the sway at half a hertz is half a second, where its sine is exactly one,
        // so the bearing is displaced by the whole configured amplitude.
        final DecorationSettings swaying = new DecorationSettings(
                20, 0.25F, 0F, 0.5F, 3F, 0F, 17F, 0F, 90F, 0.5F, 0F);

        final PhysicsDriver driver =
                AmbientWind.driverAt(swaying, AmbientMotion.EXPOSED, AT_ORIGIN, 0.5D);

        // Swung a quarter turn from a bearing of zero, the wind that was along the decoration's facing
        // is now entirely along its side.
        assertAll(
                () -> assertEquals(0F, driver.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0.25F, driver.sideSpeed(), TOLERANCE));
    }

    @Test
    void aSwayRateOfZeroMeansNoSwayAtAllWhateverItsAmplitude() {
        final DecorationSettings frozen = new DecorationSettings(
                20, 0.25F, 0F, 0.5F, 3F, 0F, 17F, 0F, 90F, 0F, 0F);
        final DecorationPlacement offTheOrigin = new DecorationPlacement(31.5D, 70D, -12.5D, 0F, 0F);

        final PhysicsDriver driver =
                AmbientWind.driverAt(frozen, AmbientMotion.EXPOSED, offTheOrigin, 9.75D);

        // The decoration is deliberately nowhere near the origin: the sway is a property of the whole
        // field rather than of a position, so switching it off must leave the bearing alone everywhere
        // rather than displacing it by a constant that varies from one decoration to the next.
        assertAll(
                () -> assertEquals(-0.25F, driver.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, driver.sideSpeed(), TOLERANCE));
    }

    @Test
    void aDecorationNeverSpinsOnItsOwn() {
        final PhysicsDriver driver = AmbientWind.driverAt(
                gusting(0.4F, 0.4F, 0.3F), AmbientMotion.EXPOSED, AT_ORIGIN, 1.37D);

        // Yaw rate arrives through a move, which the driver loop adds; the field itself never turns a
        // decoration, because a banner in the wind does not rotate about its own pole.
        assertEquals(0F, driver.yawRateRadians(), TOLERANCE);
    }
}
