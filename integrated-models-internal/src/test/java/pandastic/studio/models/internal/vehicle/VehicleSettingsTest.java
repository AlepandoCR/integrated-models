package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assembled bundle, and the one relationship between its components that is not obvious. */
class VehicleSettingsTest {

    @Test
    void aPeriodBelowATickIsRefusedRatherThanSilentlyBecomingOne() {
        // The platform treats any period below one tick as one, so a zero or negative figure does not do
        // what a file saying it would mean. Refusing is what makes the configured value and the
        // scheduled value the same number.
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 0L, 6F, 2F, 0.05F))
                        .getMessage().contains("controlPeriodTicks")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, -1L, 6F, 2F, 0.05F))
        );
    }

    @Test
    void aStepClampBelowTheControlPeriodIsRefused() {
        // Not a tidiness check. With the ceiling below the period, every ordinary step is clamped, so
        // every vehicle on the server covers less ground than its profile says — silently, with nothing
        // wrong except the speed.
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 4L, 2F, 2F, 0.05F))
                .getMessage().contains("maxStepTicks"));
    }

    @Test
    void aClampExactlyEqualToThePeriodIsAccepted() {
        // The boundary is legal: a server that never stalls integrates exactly its own period, so
        // equality is the tightest honest clamp rather than an off-by-one.
        assertEquals(3F,
                new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 3L, 3F, 2F, 0.05F).maxStepTicks());
    }

    @Test
    void theThresholdsHaveToBePositive() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 1L, 6F, 0F, 0.05F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSettings(VehicleFixtures.VEHICLE_BOUNDS, 1L, 6F, 2F, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSettings(
                                VehicleFixtures.VEHICLE_BOUNDS, 1L, 6F, 2F, Float.NaN))
        );
    }

    @Test
    void aFixedSourceAnswersTheSameBundleEveryTime() {
        final VehicleSettings settings = VehicleFixtures.settings();
        final VehicleSettingsSource source = VehicleSettingsSource.fixed(settings);

        assertAll(
                () -> assertSame(settings, source.current()),
                () -> assertSame(settings, source.current())
        );
    }
}
