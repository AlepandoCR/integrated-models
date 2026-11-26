package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.rig.RigKind;
import pandastic.studio.models.api.rig.RigSeat;
import pandastic.studio.models.api.rig.SeatBounds;
import pandastic.studio.models.api.vehicle.ControlScheme;
import pandastic.studio.models.api.vehicle.TerrainBounds;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleBounds;
import pandastic.studio.models.api.vehicle.VehicleKind;
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

    /**
     * Terrain ceilings wide enough for any response a test here describes, so that a refusal in these
     * tests is about the layer under test rather than about the operator's angles.
     */
    static final @NotNull TerrainBounds TERRAIN_BOUNDS =
            new TerrainBounds(30F, 25F, 20F, 1F, 8, 0.05F);

    static final @NotNull VehicleBounds VEHICLE_BOUNDS =
            new VehicleBounds(2F, 1F, 45F, 4F, TERRAIN_BOUNDS);

    /** Four seats and a blocks-sized trim, so neither ceiling is the thing a seat test is about. */
    static final @NotNull SeatBounds SEAT_BOUNDS = new SeatBounds(4, 2F);

    private VehicleFixtures() {
    }

    /**
     * A rig with one bone and one driver's seat on the locator {@code saddle}.
     *
     * <p>One seat rather than none, because every test about stepping needs somebody whose input is read
     * and the driver's seat is where that somebody sits. The locator's name is deliberately not the
     * bone's: a seat is a locator and a seat naming a bone is the mistake the layer reports rather than
     * honours, so a fixture that blurred the two would make a passing test meaningless.
     */
    static @NotNull Rig rig() {
        return Rig.builder("cart", "cart-model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .kind(RigKind.VEHICLE)
                .seatBounds(SEAT_BOUNDS)
                .seat(RigSeat.of("saddle", "saddle", true))
                .build();
    }

    /**
     * A rig whose four seats cover every outcome a seat can have.
     *
     * <p>In file order, which is the order riders are offered them: the driver's seat on a real locator,
     * a passenger's seat on a real locator with an offset, a seat naming a <em>bone</em> — the rigger's
     * mistake — and a seat naming something the model does not carry at all. Three of the four states
     * matter more than the one that works, because two of them are what an operator will actually meet:
     * no model in this repository contains a locator yet.
     */
    static @NotNull Rig seatedRig() {
        return Rig.builder("coach", "coach-model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .kind(RigKind.VEHICLE)
                .seatBounds(SEAT_BOUNDS)
                .seat(RigSeat.of("driver", "saddle", true))
                .seat(new RigSeat("pillion", "pillion", false, 0.25F, 0.5F, -0.75F))
                .seat(RigSeat.of("crate", "mount", false))
                .seat(RigSeat.of("ghost", "nowhere", false))
                .build();
    }

    /** The locators {@link #rig()}'s single seat resolves against: one, half a block up. */
    static @NotNull FakeSeatLocators locators() {
        return new FakeSeatLocators().locator("saddle", 0D, 0.5D, 0D);
    }

    /**
     * The locators {@link #seatedRig()} resolves against.
     *
     * <p>Two genuine locators, one ordinary bone called {@code mount} — the bone four of this
     * repository's models carry, which is not a seat and must not become one — and nothing at all for the
     * fourth seat.
     */
    static @NotNull FakeSeatLocators seatedLocators() {
        return new FakeSeatLocators()
                .locator("saddle", 0D, 1D, 0.5D)
                .locator("pillion", 0D, 1D, -0.5D)
                .bone("mount");
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

    /**
     * {@link #profile()} with a kind and a terrain response.
     *
     * <p>The speeds are the same, so a terrain assertion is never also an assertion about how fast the
     * vehicle is: a full-rate turn is ten degrees a tick and full acceleration is a quarter of a block
     * per tick squared, both of which make the fraction of a full-rate input exact rather than
     * approximate.
     *
     * @param kind    the medium, which selects the terms honoured
     * @param terrain the ceilings, which must be inside {@link #TERRAIN_BOUNDS}
     */
    static @NotNull VehicleProfile terrainProfile(
            @NotNull VehicleKind kind,
            @NotNull TerrainResponse terrain
    ) {
        return VehicleProfile.builder(VEHICLE_BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .kind(kind)
                .terrain(terrain)
                .build();
    }

    /**
     * {@link #strafingProfile()} with a kind and a terrain response, for the bank's other denominator.
     *
     * <p>Under {@link ControlScheme#STRAFING} the lean is scaled by the side speed rather than by the
     * yaw rate, and the two are different code paths with the same ceiling.
     */
    static @NotNull VehicleProfile strafingTerrainProfile(
            @NotNull VehicleKind kind,
            @NotNull TerrainResponse terrain
    ) {
        return VehicleProfile.builder(VEHICLE_BOUNDS)
                .forwardSpeed(1F)
                .strafeSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .controls(ControlScheme.STRAFING)
                .kind(kind)
                .terrain(terrain)
                .build();
    }

    /**
     * A response that reaches its angle in one tick, so a test can assert on the target itself.
     *
     * <p>A blend of exactly one snaps, which is legal and is the only setting under which the angle a
     * step produces <em>is</em> the angle the ceilings and the inputs describe. Every other blend makes
     * an assertion about a target also an assertion about the blend.
     *
     * @param bank       the roll at a full-rate turn
     * @param slope      the pitch on the steepest grade
     * @param accel      the pitch under full acceleration
     * @param stepHeight the tallest step, in blocks
     * @param stepTicks  how many ticks a climb is spread over
     */
    static @NotNull TerrainResponse snapping(
            float bank,
            float slope,
            float accel,
            float stepHeight,
            int stepTicks
    ) {
        return new TerrainResponse(bank, slope, accel, stepHeight, stepTicks, 1F);
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
        return new VehicleSettings(VEHICLE_BOUNDS, 3L, 6F, 2F, 0.05F, seatSettings());
    }

    /** The same settings with a different control period, for the reload path. */
    static @NotNull VehicleSettings settingsWithPeriod(long controlPeriodTicks) {
        return new VehicleSettings(VEHICLE_BOUNDS, controlPeriodTicks,
                Math.max(6F, controlPeriodTicks), 2F, 0.05F, seatSettings());
    }

    /**
     * Seat settings whose every figure is as deliberately odd as the rest of this class.
     *
     * <p>A carrier type nobody would default to, a trim of a quarter block, a three-block carrier step
     * ceiling and a tenth-of-a-block deadband — each large enough to be asserted against without a
     * tolerance and none of them the shipped default, so a test that agrees with the configuration is
     * testing that the configuration is read.
     *
     * <p>The chart's labels are plain words rather than the shipped MiniMessage, because a test about
     * alignment and about which state a seat is in should not also be a test of a colour tag.
     */
    static @NotNull SeatSettings seatSettings() {
        return new SeatSettings("MAGMA_CUBE", 0.25F, 3F, 0.1F, true, chartLabels());
    }

    /** The same seat settings with the chart switched off, which is the operator's own verbosity key. */
    static @NotNull SeatSettings silentSeatSettings() {
        return new SeatSettings("MAGMA_CUBE", 0.25F, 3F, 0.1F, false, chartLabels());
    }

    /**
     * The usual settings with a terrain response for every kind that a profile can inherit.
     *
     * <p>Deliberately not inert, because the whole of what the inheritance does is visible only when the
     * default is something: a vehicle whose profile says nothing about terrain and whose server does
     * either keeps behaving exactly as before, or picks the server's answer up, and only a non-identity
     * default can tell the two apart.
     */
    static @NotNull VehicleSettings settingsWithTerrainDefaults() {
        return new VehicleSettings(VEHICLE_BOUNDS, 3L, 6F, 2F, 0.05F, seatSettings(),
                new VehicleSettings.TerrainDefaults(
                        snapping(8F, 20F, 0F, 1F, 3),
                        TerrainResponse.banking(12F, 1F),
                        TerrainResponse.banking(6F, 1F),
                        snapping(10F, 0F, 14F, 0F, 1)));
    }

    /**
     * The usual settings with a rule about how often the ground may be read.
     *
     * <p>A one-tick control period rather than the three the rest of this class uses, because a sampling
     * assertion is about how many of a vehicle's <em>steps</em> take a reading and a period of three
     * would make every figure here a question about the period as well. The period only decides how often
     * the platform dispatches the body, and these tests dispatch it by hand.
     *
     * @param sampling how often a reading may be taken
     */
    static @NotNull VehicleSettings settingsWithSampling(@NotNull TerrainSampling sampling) {
        return new VehicleSettings(VEHICLE_BOUNDS, 1L, 6F, 2F, 0.05F, seatSettings(),
                VehicleSettings.TerrainDefaults.INERT, sampling);
    }

    /** Settings carrying {@code seats}, for a test about one seat figure. */
    static @NotNull VehicleSettings settingsWithSeats(@NotNull SeatSettings seats) {
        return new VehicleSettings(VEHICLE_BOUNDS, 3L, 6F, 2F, 0.05F, seats);
    }

    /** Chart labels with no markup in them, so an assertion can be about the text. */
    static @NotNull SeatSettings.Chart chartLabels() {
        return new SeatSettings.Chart(
                "top <title>",
                "chart <rig> on <model> (<declared>)",
                "| ",
                "<seat>",
                "LOCATOR",
                "BONE",
                "MISSING",
                "driver",
                "passenger",
                "offset <x> <y> <z>",
                "on-locator",
                "end <usable>/<declared> usable, <unusable> unusable",
                "fix the locator",
                "bottom",
                2);
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
