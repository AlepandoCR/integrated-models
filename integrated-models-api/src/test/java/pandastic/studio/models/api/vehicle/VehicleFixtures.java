package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

/**
 * The bounds and profiles the vehicle tests in this package share.
 *
 * <h2>Why the numbers are deliberately not the shipped defaults</h2>
 * Because a test that uses the production defaults cannot tell a layer that reads its configuration from
 * one that ignores it and happens to agree. Every figure here is round, unusual, and chosen so that the
 * expected answers are exact in binary floating point — which is what lets the solver's assertions be
 * equalities rather than tolerances.
 */
final class VehicleFixtures {

    /** Wide enough to admit every profile below, and nothing here tests the bounds themselves. */
    static final @NotNull VehicleBounds BOUNDS = new VehicleBounds(2F, 1F, 45F, 4F);

    private VehicleFixtures() {
    }

    /**
     * A vehicle that accelerates at a quarter of a block per tick squared to a top speed of one, brakes
     * twice as hard, turns ten degrees a tick and halves its speed every tick while coasting.
     *
     * <p>The friction of a half is the important choice: it makes the geometric decay exact in binary, so
     * a test can assert that two steps of one tick agree with one step of two to the last bit.
     */
    static @NotNull VehicleProfile car() {
        return VehicleProfile.builder(BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .build();
    }

    /** The same vehicle, with the rider's left and right keys sliding it instead of turning it. */
    static @NotNull VehicleProfile hovercraft() {
        return VehicleProfile.builder(BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .strafeSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .controls(ControlScheme.STRAFING)
                .build();
    }

    /** A vehicle with a boost, for the one behaviour the multiplier changes. */
    static @NotNull VehicleProfile boosted() {
        return VehicleProfile.builder(BOUNDS)
                .forwardSpeed(1F)
                .acceleration(1F)
                .braking(1F)
                .friction(0.5F)
                .turnRate(10F)
                .boost(2F)
                .build();
    }

    /** The intent for exactly the keys named. */
    static @NotNull RiderIntent holding(int bits) {
        return RiderIntent.ofBits(bits);
    }
}
