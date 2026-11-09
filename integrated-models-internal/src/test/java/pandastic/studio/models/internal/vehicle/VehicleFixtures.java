package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.vehicle.ControlScheme;
import pandastic.studio.models.api.vehicle.VehicleBounds;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.internal.sim.SimulationClock;

/**
 * The rig, bounds, profile and settings the vehicle tests in this package share.
 *
 * <h2>Why none of the numbers are the shipped defaults</h2>
 * Because a test built on the production defaults cannot tell a layer that reads its configuration from
 * one that ignores it and happens to agree. Every figure here is deliberately odd — a control period of
 * three ticks, a local-step threshold of two blocks, a rest speed of a twentieth — so that an assertion
 * about it is an assertion about the configuration being honoured.
 */
final class VehicleFixtures {

    static final @NotNull RigBounds RIG_BOUNDS = new RigBounds(64);

    static final @NotNull ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.1F, 100F, 50F, 50F, 8F, 1F, 90F);

    static final @NotNull VehicleBounds VEHICLE_BOUNDS = new VehicleBounds(2F, 1F, 45F, 4F);

    private VehicleFixtures() {
    }

    /** A rig with one bone, because nothing in this package looks at the skeleton. */
    static @NotNull Rig rig() {
        return Rig.builder("cart", "cart-model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .build();
    }

    /**
     * A vehicle that reaches a block a tick, turns ten degrees a tick and halves its speed each tick
     * while coasting.
     *
     * <p>The friction of a half makes the geometric decay exact in binary, which is what lets a test
     * assert where the vehicle ends up rather than that it ended up roughly there.
     */
    static @NotNull VehicleProfile profile() {
        return VehicleProfile.builder(VEHICLE_BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .build();
    }

    /** The same vehicle under the other control scheme. */
    static @NotNull VehicleProfile strafingProfile() {
        return VehicleProfile.builder(VEHICLE_BOUNDS)
                .forwardSpeed(1F)
                .strafeSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .controls(ControlScheme.STRAFING)
                .build();
    }

    /**
     * Settings whose every figure is unusual: a three-tick control period, a six-tick clamp, a two-block
     * local-step threshold and a rest speed of a twentieth of a block a tick.
     */
    static @NotNull VehicleSettings settings() {
        return new VehicleSettings(VEHICLE_BOUNDS, 3L, 6F, 2F, 0.05F);
    }

    /** The same settings with a different control period, for the reload path. */
    static @NotNull VehicleSettings settingsWithPeriod(long controlPeriodTicks) {
        return new VehicleSettings(VEHICLE_BOUNDS, controlPeriodTicks,
                Math.max(6F, controlPeriodTicks), 2F, 0.05F);
    }

    /**
     * A clock the test advances by hand.
     *
     * <p>Starts at a large negative value on purpose: {@link System#nanoTime()}'s origin is arbitrary and
     * genuinely is negative on some JVMs, so anything that assumed a positive or zero-based reading would
     * pass against a clock starting at zero and fail in production.
     */
    static final class SteppableClock implements SimulationClock {

        private long nanos = -9_000_000_000L;

        @Override
        public long nanoTime() {
            return this.nanos;
        }

        /** Advances by whole server ticks, which is the unit every speed in this layer is in. */
        void advanceTicks(double ticks) {
            this.nanos += Math.round(ticks * (double) VehicleStepper.NANOS_PER_TICK);
        }

        void advanceNanos(long by) {
            this.nanos += by;
        }
    }
}
