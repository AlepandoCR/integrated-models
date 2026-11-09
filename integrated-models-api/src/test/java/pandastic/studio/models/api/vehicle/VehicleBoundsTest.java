package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the operator's ranges themselves have to be before any profile is measured against them. */
class VehicleBoundsTest {

    @Test
    void aCeilingOfZeroWouldRefuseEveryProfile() {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(0F, 1F, 45F, 2F)).getMessage()
                        .contains("maxSpeedBlocksPerTick")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(1F, 0F, 45F, 2F)).getMessage()
                        .contains("maxAccelerationBlocksPerTickSquared")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(1F, 1F, 0F, 2F)).getMessage()
                        .contains("maxTurnRateDegreesPerTick"))
        );
    }

    @Test
    void theBoostCeilingFloorIsTheNeutralMultiplierRatherThanZero() {
        // A ceiling below one would refuse even a profile that declares no boost at all, which is every
        // profile a plugin writes before it thinks about boosting.
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(1F, 1F, 45F, 0.5F)).getMessage()
                        .contains("maxBoostMultiplier")),
                () -> assertEquals(1F, new VehicleBounds(1F, 1F, 45F, 1F).maxBoostMultiplier(),
                        "exactly one is legal and means the boost key does nothing")
        );
    }

    @Test
    void aNonFiniteCeilingIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(Float.NaN, 1F, 45F, 2F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleBounds(1F, 1F, 45F, Float.POSITIVE_INFINITY))
        );
    }
}
