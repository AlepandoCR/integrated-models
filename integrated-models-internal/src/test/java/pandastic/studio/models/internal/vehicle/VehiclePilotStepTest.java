package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.vehicle.PilotContext;
import pandastic.studio.models.api.vehicle.PilotSteering;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleController;
import pandastic.studio.models.api.vehicle.VehicleControls;
import pandastic.studio.models.api.vehicle.VehicleMessages;
import pandastic.studio.models.api.vehicle.VehiclePilot;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.api.vehicle.VehicleSteering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a {@link VehiclePilot} is consulted from, who wins when a rider turns up, and what the one
 * writer to the position still is.
 *
 * <h2>Why the integration matters as much as the arithmetic</h2>
 * {@code PilotSteeringTest} proves the bridge computes the right keys. This suite proves the control loop
 * asks for them at the right moment and does nothing else with them — specifically that the synthesised
 * intent goes through the <em>same</em> path a rider's does, so a piloted vehicle is subject to its own
 * control scheme, its own profile and its own teleport threshold, and the position is written by the
 * control step and by nothing else.
 *
 * <p>It also pins the thing a consumer will trip over first: a pilot and a rider are never both
 * deciding. Everything still goes through the {@link VehicleHost} seam, so none of it needs a server.
 */
class VehiclePilotStepTest {

    private static final float EPSILON = 1.0E-5F;

    private final FakeVehicleHost host = new FakeVehicleHost();

    private final FakeVehicleVisual visual = new FakeVehicleVisual(this.host.sharedJournal())
            .locators(VehicleFixtures.locators());

    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();

    private final List<Throwable> logged = new ArrayList<>();

    private final Map<Player, InputSnapshot> inputs = new HashMap<>();

    private final Player driver = StubPlayer.named("driver");

    // ------------------------------------------------------------------ fixtures

    private @NotNull VehicleImpl vehicle(@NotNull VehiclePilot pilot) {
        return new VehicleImpl(VehicleFixtures.rig(), VehicleFixtures.profile(), this.host, this.visual,
                VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock, this::log,
                SeatChartSink.NONE,
                new VehicleSetup(VehicleMessages.SILENT, VehicleControls.NONE),
                this::snapshotOf, (described, failure) -> true, pilot, stopped -> {
                });
    }

    /**
     * A started vehicle whose clock has been seeded by one step at zero elapsed time.
     *
     * <p>The seeding step is not a workaround: the first step of a vehicle's life has nothing to
     * difference against, so it genuinely reports zero elapsed ticks — and a pilot asked over no elapsed
     * time has no authority to compare a threshold against and answers {@link RiderIntent#NONE}. Every
     * assertion here has to get past that step first, so the journal and the log are cleared afterwards.
     */
    private @NotNull VehicleImpl primed(@NotNull VehiclePilot pilot) {
        final VehicleImpl vehicle = vehicle(pilot);
        assertTrue(vehicle.start(), "the fixture host should allow a start");
        this.host.runOnce();
        this.host.clearJournal();
        this.logged.clear();
        return vehicle;
    }

    /** The same, with the pilot's own record of the seeding step cleared too. */
    private @NotNull VehicleImpl primed(@NotNull RecordingPilot pilot) {
        final VehicleImpl vehicle = primed((VehiclePilot) pilot);
        pilot.seen.clear();
        return vehicle;
    }

    private void log(@NotNull String message, @NotNull Throwable thrown) {
        this.logged.add(thrown);
    }

    private @NotNull InputSnapshot snapshotOf(@NotNull Player rider) {
        return this.inputs.getOrDefault(rider, InputSnapshot.IDLE);
    }

    private static @NotNull InputSnapshot held(@NotNull InputKey key) {
        final InputState state = InputState.of(key);
        return InputSnapshot.ofFlags(state, state, 0L);
    }

    /** A pilot that drives toward one fixed point and records every context it was handed. */
    private static final class RecordingPilot implements VehiclePilot {

        private final double targetX;
        private final double targetZ;
        private final @NotNull List<PilotContext> seen = new ArrayList<>();

        private int closed;

        RecordingPilot(double targetX, double targetZ) {
            this.targetX = targetX;
            this.targetZ = targetZ;
        }

        @Override
        public @NotNull RiderIntent steer(@NotNull PilotContext context) {
            this.seen.add(context);
            return PilotSteering.toward(context, this.targetX, this.targetZ);
        }

        @Override
        public void onClosed(@NotNull Vehicle vehicle) {
            this.closed++;
        }
    }

    // ------------------------------------------------------------------ when it is asked

    @Test
    void aPilotIsAskedOnEveryStepWithTheDriversSeatEmpty() {
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        primed(pilot);

        for (int step = 0; step < 3; step++) {
            this.clock.advanceTicks(1);
            this.host.runOnce();
        }

        assertEquals(3, pilot.seen.size(), "an empty vehicle still steps, and a pilot still decides");
    }

    @Test
    void aPilotDrivesTheVehicleThroughTheSolverRatherThanWritingAPosition() {
        // The authority argument, asserted: the only world calls are the ones the control step has always
        // made. Nothing in the pilot path teleports, sets a yaw of its own or moves the entity directly —
        // the position has one writer and it is this step.
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        primed(pilot);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of("moveLocal"), this.host.rootJournal(),
                        "one in-place move and nothing else: no teleport, no yaw write of its own, and"
                                + " certainly no second writer to the position"),
                () -> assertEquals(0.25D, this.host.z(), EPSILON,
                        "a quarter block, which is acceleration * ticks^2 — the profile's figure and not"
                                + " the pilot's. The pilot asked for a key; the solver decided how far"
                                + " that goes")
        );
    }

    @Test
    void aPilotedVehicleStillObeysItsOwnProfilesTurnRate() {
        // A target to the left means the left key, and the left key means the profile's ten degrees a
        // tick. A pilot that moved the vehicle itself would have had to invent a turn rate.
        final RecordingPilot pilot = new RecordingPilot(1000.0D, 0.0D);
        final VehicleImpl vehicle = primed(pilot);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(-10F, this.host.yaw(), EPSILON,
                        "+X is to the left of a vehicle facing +Z, and turning left decreases yaw"),
                () -> assertEquals(-1F, vehicle.getSteering().turn(), EPSILON,
                        "a full-rate turn, because a key is all a pilot can hold — the rate is the"
                                + " profile's")
        );
    }

    @Test
    void aPilotThatHasArrivedLetsTheVehicleReachTheRestFixedPoint() {
        // The whole reason the dead band is derived rather than configured. A pilot that keeps asking for
        // a correction it cannot make never goes neutral, and only a *neutral* vehicle is snapped to a
        // dead stop — one that is not writes a position every step for ever.
        final RecordingPilot pilot = new RecordingPilot(0.0D, 0.0D);
        final VehicleImpl vehicle = primed(pilot);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(1, pilot.seen.size(), "it was asked"),
                () -> assertEquals(VehicleSteering.NEUTRAL, vehicle.getSteering(),
                        "and asked for nothing, so the steering is neutral — anything else suppresses"
                                + " the rest snap for ever"),
                () -> assertTrue(this.host.rootJournal().isEmpty(),
                        "so nothing at all is written to the world")
        );
    }

    // ------------------------------------------------------------------ who wins

    @Test
    void aRiderInTheDriversSeatTakesTheVehicleOverFromThePilot() {
        // One decider at a time, with no handover state: the condition is evaluated once per step on the
        // thread that also seats people, so there is never a step where both have asked for something.
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        final VehicleImpl vehicle = primed(pilot);

        this.clock.advanceTicks(1);
        this.host.runOnce();
        final int askedBeforeBoarding = pilot.seen.size();

        assertTrue(vehicle.mount(this.driver), "the driver's seat resolves to a locator");
        this.inputs.put(this.driver, held(InputKey.BACKWARD));
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(askedBeforeBoarding, pilot.seen.size(),
                        "the pilot is not asked at all while somebody is driving"),
                () -> assertTrue(vehicle.getSteering().throttle() < 0F,
                        "and the vehicle is reversing, which is what the rider asked for and the"
                                + " opposite of what the pilot wanted")
        );
    }

    @Test
    void thePilotResumesTheMomentTheDriversSeatEmptiesAgain() {
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        final VehicleImpl vehicle = primed(pilot);

        assertTrue(vehicle.mount(this.driver));
        this.clock.advanceTicks(1);
        this.host.runOnce();
        final int askedWhileRidden = pilot.seen.size();

        assertTrue(vehicle.dismount(this.driver));
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(0, askedWhileRidden),
                () -> assertEquals(1, pilot.seen.size(), "and it is asked again with nobody aboard")
        );
    }

    @Test
    void aVehicleWithNoPilotIsNeverGivenASyntheticIntent() {
        // The identity value costs one reference comparison and nothing else, so a vehicle built the way
        // every vehicle was built before the seam existed behaves exactly as it did.
        final VehicleImpl vehicle = primed(VehiclePilot.NONE);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertSame(VehiclePilot.NONE, vehicle.pilot()),
                () -> assertTrue(vehicle.getSteering().isNeutral(),
                        "nobody is driving and nothing is piloting, so the vehicle coasts")
        );
    }

    // ------------------------------------------------------------------ containment and teardown

    @Test
    void aPilotThatThrowsLetsTheVehicleCoastInsteadOfKillingTheStep() {
        // The platform swallows whatever escapes a scheduled body and keeps the task alive, so an
        // exception let through here would silently stop the rest of the step — the seat follow, the
        // consumer's hooks — for as long as the consumer's bug lasts. A coasting vehicle is the
        // containment, and deliberately not a detach: a detached pilot would leave an autonomous vehicle
        // parked for ever with nothing in the log to connect it to.
        final VehicleImpl vehicle = primed(context -> {
            throw new IllegalStateException("the consumer's pilot is broken");
        });

        this.clock.advanceTicks(1);
        this.host.runOnce();
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(2, this.logged.size(), "reported every step, not swallowed"),
                () -> assertTrue(vehicle.getSteering().isNeutral(), "and the vehicle coasts"),
                () -> assertTrue(vehicle.isSpawned(), "and keeps running")
        );
    }

    @Test
    void aPilotIsToldOnceWhenTheVehicleIsTornDown() {
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        final VehicleImpl vehicle = primed(pilot);

        vehicle.despawn();
        vehicle.despawn();

        assertEquals(1, pilot.closed, "the teardown is latched, so the hook is bounded at once ever");
    }

    @Test
    void aPilotIsToldAfterTheConsumersControllerAndBeforeTheVisualIsReleased() {
        // The documented order. The controller holds the consumer's invariants and the pilot holds ours —
        // an AiPilot uninstalls a plan there, and a controller reading that plan's last decision in its
        // own onClosed must still be able to. Both come before the release, because releasing is what
        // ends the vehicle's existence.
        final List<String> order = new ArrayList<>();
        final VehicleController recording = new VehicleController() {
            @Override
            public void onClosed(@NotNull Vehicle closed) {
                order.add("controller");
            }
        };
        final VehiclePilot pilot = new VehiclePilot() {
            @Override
            public @NotNull RiderIntent steer(@NotNull PilotContext context) {
                order.add("steer");
                return RiderIntent.NONE;
            }

            @Override
            public void onClosed(@NotNull Vehicle closed) {
                order.add("pilot");
            }
        };
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), VehicleFixtures.profile(),
                this.host, this.visual, VehicleSettingsSource.fixed(VehicleFixtures.settings()),
                this.clock, this::log, SeatChartSink.NONE,
                VehicleSetup.silent().withController(recording),
                this::snapshotOf, (described, failure) -> true, pilot, stopped -> {
                });
        assertTrue(vehicle.start());
        this.host.runOnce();
        order.clear();
        this.host.clearJournal();

        vehicle.despawn();

        assertAll(
                () -> assertEquals(List.of("controller", "pilot"), order),
                () -> assertTrue(this.host.rootJournal().contains("visual.despawn"),
                        "and the visual is released, which happens after both")
        );
    }

    @Test
    void aPilotIsToldEvenWhenThePlatformRetiredTheRootEntity() {
        // The one teardown path with no thread to defer to: the scheduler that would have run the
        // deferred work is the one that has just retired. A pilot owning an installed plan has to be told
        // here or the plan outlives the plugin's classloader.
        final RecordingPilot pilot = new RecordingPilot(0.0D, 100.0D);
        primed(pilot);

        this.host.retire();

        assertEquals(1, pilot.closed);
    }
}
