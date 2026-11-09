package pandastic.studio.models.api.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverBoundsTest {

    @Test
    void refusesANonPositiveOrNonFiniteThreshold() {
        assertAll(
                // Zero would classify every movement as a teleport and zero the driver permanently, so
                // the physics would never be driven at all.
                () -> assertRefused(0F, 6F, "maxStepBlocks"),
                () -> assertRefused(-1F, 6F, "maxStepBlocks"),
                () -> assertRefused(1.5F, 0F, "maxVerticalStepBlocks"),
                () -> assertRefused(Float.NaN, 6F, "maxStepBlocks"),
                () -> assertRefused(1.5F, Float.NaN, "maxVerticalStepBlocks"),
                () -> assertRefused(Float.POSITIVE_INFINITY, 6F, "maxStepBlocks"));
    }

    @Test
    void honoursADeliberatelyOddConfigurationRatherThanSubstitutingADefault() {
        // Both thresholds are far below the removed 1.5 and 6, so a delta the old constants would have
        // waved through must now be discarded. Nothing but the injected value can produce that.
        final DriverBounds tight = new DriverBounds(0.37F, 0.9F);
        assertAll(
                () -> assertEquals(0.37F, tight.maxStepBlocks()),
                () -> assertEquals(0.9F, tight.maxVerticalStepBlocks()));

        final PhysicsDriver brisk = new PhysicsDriver(0.5F, 0.2F, 0F, 0.1F);
        final PhysicsDriver filtered = brisk.withoutTeleports(tight);
        assertAll(
                () -> assertEquals(0F, filtered.forwardSpeed(),
                        "0.5 is past a 0.37 ceiling, so it is a teleport under this configuration"),
                () -> assertEquals(0F, filtered.sideSpeed(),
                        "both horizontal components go together: a teleport has no direction"),
                () -> assertEquals(0F, filtered.verticalSpeed()),
                () -> assertEquals(0.1F, filtered.yawRateRadians(),
                        "a yaw rate is not a translation and survives"));

        final PhysicsDriver falling = new PhysicsDriver(0F, 0F, 1F, 0F);
        assertEquals(0F, falling.withoutTeleports(tight).verticalSpeed(),
                "1 block is past a 0.9 ceiling even though the removed constant was 6");
    }

    @Test
    void aDeltaInsideTheBoundsIsReturnedUntouched() {
        final DriverBounds generous = new DriverBounds(4F, 12F);
        final PhysicsDriver driver = new PhysicsDriver(1.9F, -1.9F, 7F, 0.3F);
        assertAll(
                () -> assertSame(driver, driver.withoutTeleports(generous),
                        "no copy when nothing is filtered"),
                // And the same delta against a configuration that calls it a teleport.
                () -> assertEquals(PhysicsDriver.STILL.forwardSpeed(),
                        driver.withoutTeleports(new DriverBounds(1F, 1F)).forwardSpeed()));
    }

    @Test
    void theThresholdIsExclusiveSoAValueExactlyAtItStillCounts() {
        final DriverBounds bounds = new DriverBounds(0.37F, 0.9F);
        final PhysicsDriver atTheLimit = new PhysicsDriver(0.37F, 0F, 0.9F, 0F);
        assertSame(atTheLimit, atTheLimit.withoutTeleports(bounds));
    }

    private static void assertRefused(float step, float vertical, String expectedInMessage) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DriverBounds(step, vertical));
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must name the component, got: " + thrown.getMessage());
    }
}
