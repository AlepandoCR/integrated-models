package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a profile accepts, what the operator's bounds then refuse, and which of the two says so.
 *
 * <p>The division is the thing worth testing. The constructor enforces what is arithmetically required —
 * finite, non-negative, a fraction where a fraction is meant — and the bounds enforce what the server
 * permits. A profile can be perfectly well formed and still be refused, and the two failures have to name
 * different things.
 */
class VehicleProfileTest {

    @Test
    void aBuilderThatSaysNothingProducesNeutralValues() {
        final VehicleProfile neutral = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .acceleration(0.5F)
                .braking(0.5F)
                .build();

        assertAll(
                // Zero means "this vehicle does not do that at all", which is what a one-way conveyor
                // and a turning vehicle respectively are.
                () -> assertEquals(0F, neutral.maxReverseSpeedBlocksPerTick()),
                () -> assertEquals(0F, neutral.maxStrafeSpeedBlocksPerTick()),
                () -> assertEquals(0F, neutral.frictionPerTick()),
                () -> assertEquals(0F, neutral.turnRateDegreesPerTick()),
                // The multiplicative identity, not zero: a profile that says nothing about boost does
                // not boost, and a zero multiplier would stop it moving when the rider sprints.
                () -> assertEquals(1F, neutral.boostMultiplier()),
                () -> assertSame(ControlScheme.TURNING, neutral.controlScheme(),
                        "the default scheme should be what every vanilla mount does")
        );
    }

    @Test
    void theBoostedSpeedIsTheProductAndIsWhatTheThresholdsHaveToClear() {
        final VehicleProfile boosted = VehicleFixtures.boosted();

        assertEquals(2F, boosted.boostedForwardSpeedBlocksPerTick(),
                "the figure every movement threshold on the server has to be compared against");
    }

    @Test
    void theConstructorRefusesWhatIsArithmeticallyImpossible() {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(0F, 0.1F, 0.1F, 0F, 1F)).getMessage()
                        .contains("maxForwardSpeedBlocksPerTick"),
                        "a vehicle with no top speed cannot move and is not a vehicle"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0F, 0.1F, 0F, 1F)).getMessage()
                        .contains("accelerationBlocksPerTickSquared"),
                        "a vehicle that cannot accelerate never reaches its own top speed"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0.1F, 0.1F, 1.5F, 1F)).getMessage()
                        .contains("frictionPerTick"),
                        "friction is the share of speed lost per tick, so above one it is not a share"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0.1F, 0.1F, 0F, 0.5F)).getMessage()
                        .contains("boostMultiplier"),
                        "a multiplier below its own identity would make sprinting a brake"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profile(Float.NaN, 0.1F, 0.1F, 0F, 1F))
        );
    }

    @Test
    void theBoundsRefuseWhatTheOperatorDoesNotPermitAndNameBoth() {
        // One narrow bound per case, each wide enough everywhere else: a bounds that narrowed two
        // ceilings at once would only ever report whichever is checked first, so the test would be
        // asserting the order of the checks rather than which key each one names.
        final VehicleBounds slow = new VehicleBounds(0.5F, 1F, 45F, 4F);
        final VehicleBounds unboostable = new VehicleBounds(2F, 1F, 45F, 1F);
        final VehicleBounds sluggish = new VehicleBounds(2F, 0.1F, 45F, 4F);

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.car().requireWithin(slow)).getMessage()
                        .contains("maxSpeedBlocksPerTick"),
                        "the complaint has to name the bound, because the fix might be in either"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.boosted().requireWithin(unboostable)).getMessage()
                        .contains("maxBoostMultiplier")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.car().requireWithin(sluggish)).getMessage()
                        .contains("maxAccelerationBlocksPerTickSquared")),
                // The reverse and strafe speeds are held to the same single speed ceiling, which is the
                // point of there being one: an operator capping a server's vehicle speed should not have
                // to think about which direction it is in.
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleProfile.builder(slow)
                                .forwardSpeed(0.5F).reverseSpeed(0.9F)
                                .acceleration(0.1F).braking(0.1F).build()).getMessage()
                        .contains("maxReverseSpeedBlocksPerTick"))
        );
    }

    @Test
    void aProfileInsideTheBoundsComesBackUnchanged() {
        final VehicleProfile car = VehicleFixtures.car();

        assertSame(car, car.requireWithin(VehicleFixtures.BOUNDS),
                "requireWithin is a check, not a transformation");
    }

    @Test
    void theBuilderValidatesAgainstTheBoundsItWasGiven() {
        // The bounds are supplied to builder() rather than to build() so that a caller cannot assemble a
        // profile and then forget to check it.
        final VehicleBounds narrow = new VehicleBounds(0.25F, 1F, 45F, 4F);

        assertThrows(IllegalArgumentException.class, () -> VehicleProfile.builder(narrow)
                .forwardSpeed(1F)
                .acceleration(0.25F)
                .braking(0.25F)
                .build());
    }

    private static VehicleProfile profile(
            float forward,
            float acceleration,
            float braking,
            float friction,
            float boost
    ) {
        return new VehicleProfile(forward, 0F, 0F, acceleration, braking, friction, 0F, boost,
                ControlScheme.TURNING);
    }
}
