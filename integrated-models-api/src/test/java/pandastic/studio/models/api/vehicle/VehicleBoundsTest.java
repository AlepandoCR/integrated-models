package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the operator's ranges themselves have to be before any profile is measured against them. */
class VehicleBoundsTest {

    @Test
    void aCeilingOfZeroWouldRefuseEveryProfile() {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(0F, 1F, 45F, 2F)).getMessage()
                        .contains("maxSpeedBlocksPerTick")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(1F, 0F, 45F, 2F)).getMessage()
                        .contains("maxAccelerationBlocksPerTickSquared")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(1F, 1F, 0F, 2F)).getMessage()
                        .contains("maxTurnRateDegreesPerTick"))
        );
    }

    @Test
    void theBoostCeilingFloorIsTheNeutralMultiplierRatherThanZero() {
        // A ceiling below one would refuse even a profile that declares no boost at all, which is every
        // profile a plugin writes before it thinks about boosting.
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(1F, 1F, 45F, 0.5F)).getMessage()
                        .contains("maxBoostMultiplier")),
                () -> assertEquals(1F,
                        VehicleBounds.withoutTerrain(1F, 1F, 45F, 1F).maxBoostMultiplier(),
                        "exactly one is legal and means the boost key does nothing")
        );
    }

    @Test
    void aNonFiniteCeilingIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(Float.NaN, 1F, 45F, 2F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> VehicleBounds.withoutTerrain(1F, 1F, 45F, Float.POSITIVE_INFINITY))
        );
    }

    @Test
    void theFourArgumentFactoryAdmitsExactlyTheInertResponse() {
        // What a configuration that declares no terrain ceilings permits, and the reason it is stated as
        // a test: the ceiling it supplies has to accept every profile that was legal before the terrain
        // layer existed — all of which carry TerrainResponse.NONE — and refuse the first one that asks
        // for a lean nobody permitted, by name.
        final VehicleBounds ported = VehicleBounds.withoutTerrain(1F, 1F, 45F, 2F);

        assertAll(
                () -> assertSame(VehicleBounds.NO_TERRAIN, ported.terrain()),
                () -> assertDoesNotThrow(() -> TerrainResponse.NONE.requireWithin(ported.terrain())),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> TerrainResponse.banking(10F, 0.25F).requireWithin(ported.terrain()))
                        .getMessage().contains("bankDegrees"),
                        "a refusal naming the term, not a silently flattened vehicle")
        );
    }

    @Test
    void theTerrainCeilingsAreCarriedRatherThanOptional() {
        // Nested rather than flattened, so there is one place the six terrain ceilings live; and
        // required rather than nullable, so no reader has to decide what a missing half means.
        final TerrainBounds terrain = new TerrainBounds(30F, 25F, 20F, 1F, 8, 0.05F);

        assertAll(
                () -> assertSame(terrain,
                        new VehicleBounds(1F, 1F, 45F, 2F, terrain).terrain()),
                () -> assertThrows(NullPointerException.class,
                        () -> new VehicleBounds(1F, 1F, 45F, 2F, null))
        );
    }
}
