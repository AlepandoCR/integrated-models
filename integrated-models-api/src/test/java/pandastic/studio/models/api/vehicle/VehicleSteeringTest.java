package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seven keys to three axes, under both control schemes.
 *
 * <p>This is the layer where a sign error is cheapest to make and most expensive to notice: a vehicle
 * that turns the wrong way under a rider's hand is indistinguishable from a rider who is confused. So the
 * turn direction is asserted against the server's own yaw convention explicitly rather than against
 * whatever the implementation happens to do.
 */
class VehicleSteeringTest {

    @Test
    void forwardAndBackwardMapToTheThrottle() {
        assertAll(
                () -> assertEquals(1F, steer(RiderIntent.FORWARD_BIT).throttle()),
                () -> assertEquals(-1F, steer(RiderIntent.BACKWARD_BIT).throttle()),
                () -> assertEquals(0F, steer(0).throttle())
        );
    }

    @Test
    void opposingKeysCancelRatherThanOneWinning() {
        // Zero is the only answer that does not require remembering which key arrived first, and the
        // retained input carries no ordering — both flags simply are down.
        final VehicleSteering both = steer(RiderIntent.FORWARD_BIT | RiderIntent.BACKWARD_BIT);
        final VehicleSteering sideways = steer(RiderIntent.LEFT_BIT | RiderIntent.RIGHT_BIT);

        assertAll(
                () -> assertEquals(0F, both.throttle()),
                () -> assertEquals(0F, sideways.turn()),
                () -> assertTrue(both.isNeutral()),
                () -> assertTrue(sideways.isNeutral())
        );
    }

    @Test
    void turningLeftDecreasesYawBecauseMinecraftYawGrowsClockwise() {
        // The sign is the whole point of this test. Minecraft yaw grows clockwise from +Z, so a left
        // turn is a decrease; a positive turn value therefore has to mean right.
        assertAll(
                () -> assertEquals(-1F, steer(RiderIntent.LEFT_BIT).turn()),
                () -> assertEquals(1F, steer(RiderIntent.RIGHT_BIT).turn())
        );
    }

    @Test
    void underTurningThereIsNoStrafeAndUnderStrafingThereIsNoTurn() {
        final VehicleSteering turning =
                VehicleSteering.of(RiderIntent.ofBits(RiderIntent.LEFT_BIT), ControlScheme.TURNING);
        final VehicleSteering strafing =
                VehicleSteering.of(RiderIntent.ofBits(RiderIntent.LEFT_BIT), ControlScheme.STRAFING);

        assertAll(
                () -> assertEquals(0F, turning.strafe()),
                () -> assertEquals(-1F, turning.turn()),
                () -> assertEquals(0F, strafing.turn()),
                // Left is the positive side of the body frame, the same way the pose pipeline measures
                // side speed, so a strafing vehicle and the model on it agree about which way sideways is.
                () -> assertEquals(1F, strafing.strafe())
        );
    }

    @Test
    void sprintBoostsAndJumpIsCarriedButSneakIsNotTranslated() {
        final VehicleSteering sprinting = steer(RiderIntent.SPRINT_BIT);
        final VehicleSteering jumping = steer(RiderIntent.JUMP_BIT);
        final VehicleSteering sneaking = steer(RiderIntent.SNEAK_BIT);

        assertAll(
                () -> assertTrue(sprinting.boosting()),
                () -> assertTrue(jumping.jumping()),
                // Sneak would be an obvious brake and is unusable as one: a rider holding it is
                // dismounted by the server on the same tick, so a brake bound to it would be observable
                // for one step and then stop existing.
                () -> assertEquals(VehicleSteering.NEUTRAL, sneaking),
                // A boost with nothing held is still neutral: it multiplies a target of zero.
                () -> assertTrue(sprinting.isNeutral())
        );
    }

    @Test
    void neutralIsWhatNoKeysResolveTo() {
        assertAll(
                () -> assertEquals(VehicleSteering.NEUTRAL,
                        VehicleSteering.of(RiderIntent.NONE, ControlScheme.TURNING)),
                () -> assertTrue(VehicleSteering.NEUTRAL.isNeutral()),
                () -> assertFalse(steer(RiderIntent.FORWARD_BIT).isNeutral())
        );
    }

    @Test
    void anAxisOutsideMinusOneToOneIsRefused() {
        // The axes are fractions of what the profile permits, so a value outside the unit range would
        // silently let a caller exceed a top speed the operator validated.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSteering(1.5F, 0F, 0F, false, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new VehicleSteering(0F, 0F, Float.NaN, false, false))
        );
    }

    private static VehicleSteering steer(int bits) {
        return VehicleSteering.of(RiderIntent.ofBits(bits), ControlScheme.TURNING);
    }
}
