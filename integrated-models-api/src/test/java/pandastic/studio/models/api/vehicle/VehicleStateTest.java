package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalisation, the rest test, and what the rest test deliberately ignores. */
class VehicleStateTest {

    @Test
    void theHeadingIsFoldedIntoHalfATurnEitherWay() {
        // Normalised rather than left to grow, unlike the yaw the pose pipeline samples: this one is
        // integrated every step for the life of the vehicle, so an unbounded value would spend its float
        // precision on turns that happened minutes ago.
        assertAll(
                () -> assertEquals(-10F, new VehicleState(0F, 0F, 350F).yawDegrees()),
                () -> assertEquals(10F, new VehicleState(0F, 0F, -350F).yawDegrees()),
                () -> assertEquals(-180F, new VehicleState(0F, 0F, 180F).yawDegrees(),
                        "the seam belongs to the negative side, so the range is half open"),
                () -> assertEquals(-180F, new VehicleState(0F, 0F, -180F).yawDegrees()),
                () -> assertEquals(0F, new VehicleState(0F, 0F, 3_600F).yawDegrees(),
                        "ten whole turns is no turn at all")
        );
    }

    @Test
    void aNonFiniteComponentIsRefused() {
        // A NaN that reaches a rotation deletes the model on the client rather than looking wrong.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleState(Float.NaN, 0F, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleState(0F, 0F, Float.POSITIVE_INFINITY))
        );
    }

    @Test
    void restLooksAtBothSpeedsAndNotAtTheHeading() {
        final VehicleState turnedButStill = new VehicleState(0F, 0F, 137F);

        assertAll(
                () -> assertTrue(VehicleState.STOPPED.isAtRest(0.001F)),
                () -> assertTrue(turnedButStill.isAtRest(0.001F),
                        "a vehicle that has stopped has not forgotten which way it points"),
                () -> assertFalse(new VehicleState(0.01F, 0F, 0F).isAtRest(0.001F)),
                () -> assertFalse(new VehicleState(0F, -0.01F, 0F).isAtRest(0.001F),
                        "sideways motion is motion"),
                // Strictly below, so a threshold exactly equal to the speed is not rest. The caller that
                // snaps to a stop relies on that being a strict test, or a vehicle sitting exactly on
                // the threshold would flicker between stopped and moving.
                () -> assertFalse(new VehicleState(0.001F, 0F, 0F).isAtRest(0.001F))
        );
    }

    @Test
    void withYawKeepsTheSpeeds() {
        final VehicleState moving = new VehicleState(0.5F, -0.25F, 0F);
        final VehicleState turned = moving.withYaw(90F);

        assertAll(
                () -> assertEquals(0.5F, turned.forwardSpeedBlocksPerTick()),
                () -> assertEquals(-0.25F, turned.strafeSpeedBlocksPerTick()),
                () -> assertEquals(90F, turned.yawDegrees())
        );
    }
}
