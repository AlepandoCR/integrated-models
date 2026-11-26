package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleKind;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VehicleSettings.TerrainDefaults}: what a vehicle inherits when its own profile says nothing
 * about terrain.
 *
 * <p>Four components rather than a map, so the kinds are exhaustive at compile time: a fifth
 * {@link VehicleKind} is a compile error in {@code forKind} rather than a {@code null} at the point a
 * vehicle of that kind is bound. These tests pin the mapping, because a transposed pair of components
 * would give every boat a submarine's dive and nothing would throw.
 */
class VehicleTerrainDefaultsTest {

    private static final TerrainResponse GROUND = new TerrainResponse(8F, 25F, 0F, 1F, 3, 0.2F);
    private static final TerrainResponse FLYER = TerrainResponse.banking(30F, 0.25F);
    private static final TerrainResponse SUBMARINE = new TerrainResponse(15F, 0F, 20F, 0F, 1, 0.1F);
    private static final TerrainResponse BOAT = new TerrainResponse(12F, 0F, 8F, 0F, 1, 0.15F);

    private static VehicleSettings.TerrainDefaults defaults() {
        return new VehicleSettings.TerrainDefaults(GROUND, FLYER, SUBMARINE, BOAT);
    }

    @Test
    void eachKindIsAnsweredWithItsOwnResponse() {
        final VehicleSettings.TerrainDefaults defaults = defaults();

        assertAll(
                () -> assertSame(GROUND, defaults.forKind(VehicleKind.GROUND)),
                () -> assertSame(FLYER, defaults.forKind(VehicleKind.FLYER)),
                () -> assertSame(SUBMARINE, defaults.forKind(VehicleKind.SUBMARINE)),
                () -> assertSame(BOAT, defaults.forKind(VehicleKind.BOAT))
        );
    }

    @Test
    void everyKindIsAnswered() {
        // The reason the components are four fields and not a map: there is no kind this can miss.
        final VehicleSettings.TerrainDefaults defaults = defaults();

        assertAll(() -> {
            for (final VehicleKind kind : VehicleKind.values()) {
                assertFalse(defaults.forKind(kind).isInert(),
                        () -> kind + " was answered with a response no term of which can move a model");
            }
        });
    }

    @Test
    void theInertDefaultsAreTheIdentityAndAreShared() {
        // Not a tuning: every kind inherits TerrainResponse.NONE, which is what every vehicle did before
        // this type existed. Shared, so identity comparison across two bundles answers as expected.
        assertAll(
                () -> assertTrue(VehicleSettings.TerrainDefaults.INERT.isInert()),
                () -> assertSame(TerrainResponse.NONE,
                        VehicleSettings.TerrainDefaults.INERT.forKind(VehicleKind.GROUND)),
                () -> assertSame(TerrainResponse.NONE,
                        VehicleSettings.TerrainDefaults.INERT.forKind(VehicleKind.BOAT))
        );
    }

    @Test
    void aBundleBuiltWithoutTerrainInheritsNothing() {
        // The compatibility constructor: the shape this record had before the terrain layer, which every
        // caller that has nothing to say about terrain still uses. It has to behave exactly as it did.
        assertSame(VehicleSettings.TerrainDefaults.INERT, VehicleFixtures.settings().terrainDefaults());
    }

    @Test
    void oneDefaultsObjectIsNotInertBecauseOfOneInertKind() {
        // isInert is the whole bundle's answer, not any one kind's: a server that zeroed its flyers has
        // not stopped its carts from climbing.
        final VehicleSettings.TerrainDefaults partly = new VehicleSettings.TerrainDefaults(
                GROUND, TerrainResponse.NONE, TerrainResponse.NONE, TerrainResponse.NONE);

        assertFalse(partly.isInert());
    }
}
