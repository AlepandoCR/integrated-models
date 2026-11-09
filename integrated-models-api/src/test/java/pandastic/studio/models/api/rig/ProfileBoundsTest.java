package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBoundsTest {

    private static final ProfileDefaults DEFAULTS = RigFixtures.stockProfileDefaults();

    private static ProfileBounds bounds(
            float minMass, float maxMass, float maxGravity, float maxWind,
            float maxCollisionRadius, float collisionRadiusToRadians, float defaultLimitDegrees
    ) {
        return new ProfileBounds(minMass, maxMass, maxGravity, maxWind, maxCollisionRadius,
                collisionRadiusToRadians, defaultLimitDegrees);
    }

    @Test
    void refusesANonPositiveMassFloorOrCeiling() {
        assertAll(
                // Mass divides the restoring term, so a floor of zero would admit a division by zero.
                () -> assertRefused(() -> bounds(0F, 10F, 4F, 4F, 2F, 1.7F, 45F), "minMass"),
                () -> assertRefused(() -> bounds(-1F, 10F, 4F, 4F, 2F, 1.7F, 45F), "minMass"),
                () -> assertRefused(() -> bounds(0.05F, 0F, 4F, 4F, 2F, 1.7F, 45F), "maxMass"));
    }

    @Test
    void refusesANonPositiveClearanceConversion() {
        // Zero would fold every cloth strip's clearance into a pitch ceiling of zero, freezing it
        // against its own pivot — the opposite of what declaring a clearance asks for.
        assertAll(
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, 2F, 0F, 45F),
                        "collisionRadiusToRadians"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, 2F, -1.7F, 45F),
                        "collisionRadiusToRadians"));
    }

    @Test
    void refusesANegativeCeilingButAcceptsZeroWhereZeroIsAChoice() {
        assertAll(
                () -> assertRefused(() -> bounds(0.05F, 10F, -1F, 4F, 2F, 1.7F, 45F), "maxGravity"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, -1F, 2F, 1.7F, 45F), "maxWind"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, -1F, 1.7F, 45F),
                        "maxCollisionRadius"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, -1F),
                        "defaultLimitDegrees"),
                // Forbidding gravity, wind, clearance or any swing at all is a legitimate server.
                () -> assertDoesNotThrow(() -> bounds(0.05F, 10F, 0F, 0F, 0F, 1.7F, 0F)));
    }

    @Test
    void refusesAnInvertedMassRange() {
        assertAll(
                () -> assertRefused(() -> bounds(10F, 0.05F, 4F, 4F, 2F, 1.7F, 45F), "Inverted range"),
                // Equal is not inverted: pinning mass to one value is legitimate.
                () -> assertDoesNotThrow(() -> bounds(1F, 1F, 4F, 4F, 2F, 1.7F, 45F)));
    }

    @Test
    void refusesNaNAndInfinity() {
        assertAll(
                () -> assertRefused(() -> bounds(Float.NaN, 10F, 4F, 4F, 2F, 1.7F, 45F), "minMass"),
                () -> assertRefused(() -> bounds(0.05F, Float.NaN, 4F, 4F, 2F, 1.7F, 45F), "maxMass"),
                () -> assertRefused(() -> bounds(0.05F, 10F, Float.NaN, 4F, 2F, 1.7F, 45F),
                        "maxGravity"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, Float.NaN, 2F, 1.7F, 45F), "maxWind"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, Float.NaN, 1.7F, 45F),
                        "maxCollisionRadius"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, 2F, Float.NaN, 45F),
                        "collisionRadiusToRadians"),
                () -> assertRefused(() -> bounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, Float.NaN),
                        "defaultLimitDegrees"),
                () -> assertRefused(
                        () -> bounds(0.05F, Float.POSITIVE_INFINITY, 4F, 4F, 2F, 1.7F, 45F),
                        "maxMass"));
    }

    @Test
    void honoursADeliberatelyOddConfigurationRatherThanSubstitutingADefault() {
        // Seven numbers, none of them one the removed constants could produce, every one read back and
        // then observed to actually change what the api accepts and derives.
        final ProfileBounds odd = bounds(0.4F, 0.6F, 0.125F, 0.25F, 0.05F, 0.4F, 12.5F);

        assertAll(
                () -> assertEquals(0.4F, odd.minMass()),
                () -> assertEquals(0.6F, odd.maxMass()),
                () -> assertEquals(0.125F, odd.maxGravity()),
                () -> assertEquals(0.25F, odd.maxWind()),
                () -> assertEquals(0.05F, odd.maxCollisionRadius()),
                () -> assertEquals(0.4F, odd.collisionRadiusToRadians()),
                () -> assertEquals(12.5F, odd.defaultLimitDegrees()),
                // The default limit is derived from it rather than from the removed 45-degree constant.
                () -> assertEquals((float) Math.toRadians(12.5F),
                        PhysicsProfile.builder(PhysicsKind.SPRING, odd, DEFAULTS).mass(0.5F).wind(0.2F)
                                .build().limits().maxPitch(), 1.0E-6F),
                // And every ceiling bites at the configured value rather than the removed one.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.SPRING, odd, DEFAULTS).mass(1F).wind(0.2F)
                                .build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).mass(0.5F).wind(0.2F)
                                .gravity(0.2F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).mass(0.5F)
                                .gravity(0.1F).wind(0.3F).build()),
                () -> assertDoesNotThrow(() -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS)
                        .mass(0.5F).gravity(0.125F).wind(0.25F).build()));
    }

    private static void assertRefused(Runnable construction, String expectedInMessage) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, construction::run);
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must say what is wrong, got: " + thrown.getMessage());
    }
}
