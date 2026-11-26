package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.vehicle.SeatInput;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleController;
import pandastic.studio.models.api.vehicle.VehicleControls;
import pandastic.studio.models.api.vehicle.VehicleMessages;
import pandastic.studio.models.api.vehicle.VehicleSeat;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.internal.input.InputFaultSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a consumer's {@link VehicleController} is called from, in what order, and what happens when it
 * throws.
 *
 * <h2>Why the ordering is the subject rather than an implementation detail</h2>
 * Because it is the part a consumer writes code against and the part a server cannot be made to report
 * on. A controller that is told a rider's input <em>before</em> that seat's own listener, or told about a
 * step before every seat has been offered, or told a seat emptied before the seat is actually free, is
 * wrong in a way that shows up as a consumer's state machine disagreeing with the vehicle — a bug with
 * no stack trace and nothing in a log. So every promise {@code VehicleController}'s documentation makes
 * about <em>when</em> is asserted here as a sequence, not as a set of counters.
 *
 * <p>Everything a vehicle does to the world still goes through the {@link VehicleHost} seam, so none of
 * this needs a server.
 */
class VehicleControllerTest {

    /** The rig these tests use is {@code coach}, whose first two seats resolve to real locators. */
    private static final String RIG = "coach";

    private static final int DRIVER_SEAT = 0;
    private static final int PILLION_SEAT = 1;

    private static final float EPSILON = 1.0E-5F;

    private final FakeVehicleHost host = new FakeVehicleHost();

    private final FakeVehicleVisual visual = new FakeVehicleVisual(this.host.sharedJournal())
            .locators(VehicleFixtures.seatedLocators());

    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();

    /**
     * One sequence for both of the consumer's mechanisms.
     *
     * <p>Shared between the per-seat listeners and the controller on purpose: the thing under test is
     * the order the two run in relative to each other, and two separate journals could not show it.
     */
    private final List<String> events = new ArrayList<>();

    private final List<Throwable> logged = new ArrayList<>();

    /** What each rider is holding, as the control step's input source will read it. */
    private final Map<Player, InputSnapshot> inputs = new HashMap<>();

    private final RecordingController controller = new RecordingController(this.events);

    private final Player driver = StubPlayer.named("driver");
    private final Player passenger = StubPlayer.named("passenger");

    // ------------------------------------------------------------------ fixtures

    /** A setup with no messages and no per-seat controls, so the subject is the controller alone. */
    private static @NotNull VehicleSetup setup(@NotNull VehicleController controller) {
        return new VehicleSetup(VehicleMessages.SILENT, VehicleControls.NONE, controller);
    }

    private static @NotNull VehicleSetup setup(
            @NotNull VehicleControls controls,
            @NotNull VehicleController controller
    ) {
        return new VehicleSetup(VehicleMessages.SILENT, controls, controller);
    }

    private @NotNull VehicleImpl vehicle(@NotNull VehicleSetup setup) {
        return new VehicleImpl(VehicleFixtures.seatedRig(), VehicleFixtures.profile(), this.host,
                this.visual, VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                this::log, SeatChartSink.NONE, setup, this::snapshotOf, stopped -> {
                });
    }

    private @NotNull VehicleImpl vehicle(
            @NotNull VehicleSetup setup,
            @NotNull InputFaultSink faults
    ) {
        return new VehicleImpl(VehicleFixtures.seatedRig(), VehicleFixtures.profile(), this.host,
                this.visual, VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                this::log, SeatChartSink.NONE, setup, this::snapshotOf, faults, stopped -> {
                });
    }

    /**
     * A started vehicle whose clock has been seeded by one step at zero elapsed time.
     *
     * <p>The seeding step is not a workaround: the first step of a vehicle's life has nothing to
     * difference against, so it genuinely reports zero elapsed ticks. Every assertion about
     * {@code deltaTicks} has to get past it first, and the journal is cleared afterwards.
     */
    private @NotNull VehicleImpl primed(@NotNull VehicleSetup setup) {
        final VehicleImpl vehicle = vehicle(setup);
        assertTrue(vehicle.start(), "the fixture host should allow a start");
        this.host.runOnce();
        this.host.clearJournal();
        this.events.clear();
        return vehicle;
    }

    private void log(@NotNull String message, @NotNull Throwable thrown) {
        this.logged.add(thrown);
    }

    private @NotNull InputSnapshot snapshotOf(@NotNull Player rider) {
        return this.inputs.getOrDefault(rider, InputSnapshot.IDLE);
    }

    /** A snapshot with {@code key} held and no history, which is what a steering input looks like. */
    private static @NotNull InputSnapshot held(@NotNull InputKey key) {
        final InputState state = InputState.of(key);
        return InputSnapshot.ofFlags(state, state, 0L);
    }

    private void boardBoth(@NotNull VehicleImpl vehicle) {
        assertTrue(vehicle.mount(this.driver), "the driver's seat resolves to a locator");
        assertTrue(vehicle.mount(this.passenger), "the pillion resolves to a locator too");
        this.events.clear();
    }

    // ------------------------------------------------------------------ the step's order

    @Test
    void everyOccupiedSeatIsOfferedItsInputBeforeTheWholeVehicleHook() {
        // The order the api promises: every seat, in index order so the driver first, and only then the
        // step hook — which is what lets a controller resolve two riders asking for opposite things.
        final VehicleImpl vehicle = primed(setup(this.controller));
        boardBoth(vehicle);
        this.inputs.put(this.driver, held(InputKey.FORWARD));

        this.clock.advanceTicks(2);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(
                        List.of("input:#0 driver active", "input:#1 passenger idle", "step"),
                        this.events),
                () -> assertEquals(2F, this.controller.lastDeltaTicks, EPSILON,
                        "deltaTicks is the measured, clamped gap the solver integrated — not the"
                                + " configured three-tick control period, which the platform is free"
                                + " not to have honoured")
        );
    }

    @Test
    void aSeatWhoseRiderIsHoldingNothingIsStillOfferedEveryStep() {
        // The promise that makes a throttle possible: a controller is told every step, not on change, so
        // "nothing is held" has to arrive as a call rather than as silence. Three steps with an idle
        // rider are three calls.
        final VehicleImpl vehicle = primed(setup(this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.events.clear();

        for (int step = 0; step < 3; step++) {
            this.clock.advanceTicks(1);
            this.host.runOnce();
        }

        assertAll(
                () -> assertEquals(List.of(
                        "input:#0 driver idle", "step",
                        "input:#0 driver idle", "step",
                        "input:#0 driver idle", "step"), this.events),
                () -> assertTrue(this.controller.seen.get(DRIVER_SEAT).isIdle(),
                        "and the value says so, which is what isIdle() is for")
        );
    }

    @Test
    void aStepWithNobodyAboardStillClosesWithTheWholeVehicleHook() {
        // A parked, empty vehicle still steps, and a controller animating something still has to
        // animate it. The per-seat half is simply empty.
        primed(setup(this.controller));

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertEquals(List.of("step"), this.events);
    }

    @Test
    void aStepThatDecidesNothingBecauseARelocationIsInFlightOffersNothingAtAll() {
        // A host with no in-place move makes every displacement a relocation, which is what a server
        // with no adapter looks like. While one is in flight the root is transiently out of the world,
        // so the step decides nothing — and the api says so rather than pretending a step happened.
        this.host.localMove(false);
        final VehicleImpl vehicle = primed(setup(this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.inputs.put(this.driver, held(InputKey.FORWARD));
        this.events.clear();

        this.clock.advanceTicks(1);
        this.host.runOnce();
        final List<String> duringRelocation = List.copyOf(this.events);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(vehicle.isRelocating(), "the fake leaves the teleport pending"),
                () -> assertEquals(List.of(), duringRelocation,
                        "the step that started the relocation returned before the dispatch"),
                () -> assertEquals(List.of(), this.events,
                        "and so does every step until the platform reports back")
        );

        // The relocation completes and the host gains an in-place move, so the next step is an ordinary
        // one. Both halves are needed: with no in-place move every displacement is another relocation,
        // which is a real and expensive state rather than a quirk of the fake.
        this.host.completeTeleport(true);
        this.host.localMove(true);
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertEquals(List.of("input:#0 driver active", "step"), this.events,
                "once the relocation finishes the hooks resume, so nothing is permanently lost");
    }

    // ------------------------------------------------------------------ coexistence with the controls

    @Test
    void aSeatsOwnListenerRunsBeforeTheControllerAndBothSeeOneValue() {
        // Specific before general, which is the order VehicleControls already composes a seat's own
        // bindings in — index, then name, then catch-all — extended one step outwards. One SeatInput is
        // built and both are given it, so they cannot disagree about what the rider did.
        final AtomicReference<SeatInput> seenByListener = new AtomicReference<>();
        final VehicleControls controls = VehicleControls.builder()
                .onSeat(DRIVER_SEAT, input -> {
                    this.events.add("listener:#" + input.seatIndex());
                    seenByListener.set(input);
                })
                .build();
        final VehicleImpl vehicle = primed(setup(controls, this.controller));
        boardBoth(vehicle);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of(
                        "listener:#0", "input:#0 driver idle", "input:#1 passenger idle", "step"),
                        this.events,
                        "the driver's listener, then the controller for that seat, then the seat the"
                                + " consumer wired nothing for, then the whole-vehicle hook"),
                () -> assertSame(seenByListener.get(), this.controller.seen.get(DRIVER_SEAT),
                        "the same instance, not an equal one: one read, one allocation, one truth")
        );
    }

    @Test
    void aSeatWithNoListenerIsStillOfferedToTheController() {
        // Otherwise a consumer that supplies both would have to wire a no-op listener for every seat it
        // wanted the controller to hear about, which is the duplication the controller exists to avoid.
        final VehicleControls controls = VehicleControls.forSeat(
                DRIVER_SEAT, input -> this.events.add("listener:#" + input.seatIndex()));
        final VehicleImpl vehicle = primed(setup(controls, this.controller));
        boardBoth(vehicle);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertTrue(this.controller.seen.containsKey(PILLION_SEAT),
                () -> "the pillion has no listener and must still reach the controller: " + this.events);
    }

    @Test
    void aVehicleWithNeitherMechanismDispatchesNothing() {
        // The common case, and the reason the pass is gated: no listener, no controller, no SeatInput
        // built at all.
        final VehicleImpl vehicle = primed(VehicleSetup.DEFAULT);
        assertTrue(vehicle.mount(this.driver));

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertSame(VehicleControllerDispatch.INERT, vehicle.controllerDispatch(),
                        "a vehicle with no controller shares one inert dispatch"),
                () -> assertFalse(vehicle.controllerDispatch().isActive()),
                () -> assertEquals(List.of(), this.events)
        );
    }

    // ------------------------------------------------------------------ seating

    @Test
    void onSeatedArrivesAfterTheSeatIsTheirsAndIsNotAStepHook() {
        // From inside the boarding call rather than from a step, and after the occupancy has been
        // published — which is what lets the hook's own documentation promise that getSeating() agrees
        // with what it is told.
        final VehicleImpl vehicle = primed(setup(this.controller));

        assertTrue(vehicle.mount(this.driver));

        assertEquals(List.of("seated:#0 driver occupied=1"), this.events,
                "no step has run, so a hook that waited for one would be a hook that arrived late");
    }

    @Test
    void onVacatedArrivesWithTheSeatFreeAndTheOccupancyNotYetRepublished() {
        // The asymmetry with onSeated, asserted rather than left to be discovered: a pass that empties
        // several seats publishes one occupancy afterwards rather than one per seat, so the value other
        // threads read still names the departing rider during this call. The hook's arguments are the
        // truth, and its documentation says so.
        final VehicleImpl vehicle = primed(setup(this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.events.clear();

        assertTrue(vehicle.dismount(this.driver));

        assertAll(
                () -> assertEquals(List.of("vacated:#0 driver occupied=1"), this.events),
                () -> assertEquals(0, vehicle.getSeating().occupied(),
                        "republished by the time the dismount call returns")
        );
    }

    // ------------------------------------------------------------------ teardown

    @Test
    void onClosedIsTheLastHookAndRunsWhileTheRidersAreStillAboard() {
        // Before the riders are put down, because the call is where a controller forgets them. And once:
        // three things can tear a vehicle down and they can decide it simultaneously.
        final VehicleImpl vehicle = primed(setup(this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.events.clear();

        vehicle.despawn();
        vehicle.despawn();

        assertAll(
                () -> assertEquals(List.of("closed riders=1"), this.events,
                        "the rider is still aboard for the call, and the call happens exactly once"),
                () -> assertEquals(1, this.controller.closedCount),
                () -> assertEquals(0, vehicle.getRiders().size(),
                        "by the time despawn() returns they are not")
        );
    }

    @Test
    void onClosedStillArrivesWhenThePlatformRetiresTheRootEntity() {
        // The path with no thread promise at all: a retired callback runs in critical code that may not
        // remove an entity and cannot defer, so the hook is called inline on whatever thread got there.
        // A controller that was never told would leak whatever it opened.
        final VehicleImpl vehicle = primed(setup(this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.events.clear();

        this.host.retire();

        assertAll(
                () -> assertEquals(List.of("closed riders=1"), this.events),
                () -> assertTrue(vehicle.isStopped())
        );
    }

    @Test
    void aControllerThatStopsTheVehicleFromInsideAHookGetsNothingAfterOnClosed() {
        // onStep's documentation offers "deciding to shut down" as a use, so the teardown really does
        // run inside a hook's own frame. Without a latch the rest of the step would carry on calling a
        // controller that had already been told the vehicle was gone — the seat after this one, and the
        // step hook itself — which would make "the last call" false in the one case a consumer wrote.
        final AtomicReference<Vehicle> self = new AtomicReference<>();
        final VehicleController suicidal = new VehicleController() {

            @Override
            public void onPlayerInput(@NotNull SeatInput input) {
                VehicleControllerTest.this.events.add("input:#" + input.seatIndex());
                input.vehicle().despawn();
            }

            @Override
            public void onStep(@NotNull Vehicle vehicle, float deltaTicks) {
                VehicleControllerTest.this.events.add("step");
            }

            @Override
            public void onClosed(@NotNull Vehicle vehicle) {
                VehicleControllerTest.this.events.add("closed");
                self.set(vehicle);
            }
        };
        final VehicleImpl vehicle = primed(setup(suicidal));
        boardBoth(vehicle);

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of("input:#0", "closed"), this.events,
                        "the second seat and the step hook are both after the teardown, so neither"
                                + " happens"),
                () -> assertSame(vehicle, self.get()),
                () -> assertTrue(vehicle.controllerDispatch().isClosed()),
                () -> assertFalse(vehicle.controllerDispatch().isActive())
        );
    }

    // ------------------------------------------------------------------ faults

    @Test
    void aControllerThatThrowsCostsItsOwnHookAndNothingElse() {
        // The containment that matters: the platform swallows whatever escapes a scheduled body and
        // keeps the task alive, so an exception let through would silently stop the rest of the step —
        // including the seats after this one — for as long as the consumer's bug lasted.
        this.controller.throwIn(RecordingController.ON_PLAYER_INPUT);
        final VehicleImpl vehicle = primed(setup(this.controller));
        boardBoth(vehicle);

        this.clock.advanceTicks(1);
        this.host.runOnce();
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of(
                        "input:#0 driver idle", "input:#1 passenger idle", "step",
                        "input:#0 driver idle", "input:#1 passenger idle", "step"), this.events,
                        "the seat after the throwing one is still offered, the whole-vehicle hook still"
                                + " runs, and the next step still happens"),
                () -> assertEquals(4, this.logged.size(),
                        "two seats a step, each reported: the unconfigured fallback policy reports"
                                + " every occurrence because it has no interval to throttle against"),
                () -> assertTrue(this.host.isTaskAlive(), "and the control loop is not cancelled")
        );
    }

    @Test
    void aThrowingSeatListenerStillLeavesTheControllerItsTurn() {
        // The two mechanisms are contained separately. If one containment wrapped both, a consumer's
        // broken lambda would take out the controller that was supposed to be independent of it.
        final VehicleControls controls = VehicleControls.forSeat(DRIVER_SEAT, input -> {
            this.events.add("listener:#" + input.seatIndex());
            throw new IllegalStateException("the consumer's lambda is broken");
        });
        final VehicleImpl vehicle = primed(setup(controls, this.controller));
        assertTrue(vehicle.mount(this.driver));
        this.events.clear();

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of("listener:#0", "input:#0 driver idle", "step"), this.events),
                () -> assertEquals(1, this.logged.size()),
                () -> assertFalse(vehicle.controllerDispatch().isDetached(),
                        "somebody else's failure must not count against the controller")
        );
    }

    @Test
    void aControllerThatKeepsThrowingIsDetachedAndTheVehicleCarriesOn() {
        // The input layer's policy, applied to a controller: contain, count the consecutive failures,
        // and stop calling it once there have been as many as the configuration permits. The vehicle
        // keeps driving on its profile with none of the behaviour the controller was supplying, which is
        // a degraded vehicle rather than a frozen one.
        final CountingFaultSink faults = new CountingFaultSink(2);
        final VehicleImpl vehicle = vehicle(setup(this.controller), faults);
        assertTrue(vehicle.start());
        this.host.runOnce();
        assertTrue(vehicle.mount(this.driver));
        // Broken from here on, so the counted failures are the two steps below and not the seeding one.
        this.controller.throwIn(RecordingController.ON_STEP);
        this.events.clear();

        this.clock.advanceTicks(1);
        this.host.runOnce();
        final boolean detachedAfterOne = vehicle.controllerDispatch().isDetached();

        this.clock.advanceTicks(1);
        this.host.runOnce();
        final boolean detachedAfterTwo = vehicle.controllerDispatch().isDetached();

        this.events.clear();
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertFalse(detachedAfterOne,
                        "one failure is a bad step, not a broken controller"),
                () -> assertTrue(detachedAfterTwo),
                () -> assertEquals(List.of(), this.events,
                        "a detached controller is offered no input and no step at all"),
                () -> assertTrue(vehicle.isSpawned(), "the vehicle is not the thing that was broken"),
                () -> assertTrue(this.host.isTaskAlive()),
                () -> assertEquals(2, faults.failures.size()),
                () -> assertTrue(faults.failures.getFirst().contains("onStep"),
                        () -> "the report names the hook: " + faults.failures),
                () -> assertTrue(faults.failures.getFirst().contains(RIG),
                        () -> "and the rig, because the counter is per kind rather than per vehicle: "
                                + faults.failures),
                () -> assertTrue(faults.successes.stream().anyMatch(key -> key.contains("onPlayerInput")),
                        () -> "a hook that did not throw resets its own count, which is what makes the"
                                + " limit consecutive rather than cumulative: " + faults.successes)
        );
    }

    @Test
    void aDetachedControllerIsStillToldTheVehicleClosed() {
        // The one asymmetry in the detach, and it is deliberate: the call is bounded — once, ever — and
        // it is the only hook whose purpose is releasing what the consumer opened.
        final CountingFaultSink faults = new CountingFaultSink(1);
        this.controller.throwIn(RecordingController.ON_STEP);
        final VehicleImpl vehicle = vehicle(setup(this.controller), faults);
        assertTrue(vehicle.start());
        this.host.runOnce();
        assertTrue(vehicle.controllerDispatch().isDetached(),
                "one failure is enough with this sink's limit");
        this.events.clear();

        vehicle.despawn();

        assertEquals(List.of("closed riders=0"), this.events);
    }

    // ------------------------------------------------------------------ the setup's shape

    @Test
    void aSetupCarriesExactlyOneControllerAndDefaultsToNoneOfThem() {
        final VehicleController other = new VehicleController() {
        };
        final VehicleSetup withOne = VehicleSetup.DEFAULT.withController(this.controller);

        assertAll(
                () -> assertSame(VehicleController.NONE, VehicleSetup.DEFAULT.controller(),
                        "never null, so a control step has nothing to check"),
                () -> assertSame(VehicleController.NONE,
                        new VehicleSetup(VehicleMessages.SILENT, VehicleControls.NONE).controller(),
                        "the two-argument form is the shape this record had before controllers, and"
                                + " VehicleController.NONE is the identity rather than a choice"),
                () -> assertSame(this.controller, withOne.controller()),
                () -> assertSame(VehicleSetup.DEFAULT.messages(), withOne.messages(),
                        "withController changes one component and nothing else"),
                () -> assertSame(VehicleSetup.DEFAULT.controls(), withOne.controls()),
                () -> assertSame(other, withOne.withController(other).controller(),
                        "it replaces rather than composes: a kind of vehicle has one owner of its"
                                + " invariants, and composition is what VehicleControls is for"),
                () -> assertSame(VehicleController.NONE, VehicleSetup.silent().controller()),
                () -> assertThrows(NullPointerException.class,
                        () -> withOne.withController(null)),
                () -> assertSame(VehicleControllerDispatch.INERT,
                        VehicleControllerDispatch.of(VehicleController.NONE,
                                InputFaultSink.ignoring(), RIG),
                        "and a vehicle that was given none costs nothing per vehicle")
        );
    }

    // ------------------------------------------------------------------ doubles

    /**
     * A controller that writes down every hook it receives, and can be told to throw in one of them.
     *
     * <p>Appends to the sequence the per-seat listeners also append to, which is what makes the order
     * between the two mechanisms assertable.
     */
    private static final class RecordingController implements VehicleController {

        private static final @NotNull String ON_PLAYER_INPUT = "onPlayerInput";
        private static final @NotNull String ON_STEP = "onStep";

        private final @NotNull List<String> events;

        /** The last value each seat was offered, so a test can assert on identity. */
        private final @NotNull Map<Integer, SeatInput> seen = new HashMap<>();

        private float lastDeltaTicks = Float.NaN;
        private int closedCount;
        private @Nullable String throwingHook;

        private RecordingController(@NotNull List<String> events) {
            this.events = events;
        }

        private void throwIn(@NotNull String hook) {
            this.throwingHook = hook;
        }

        /** Recorded first and thrown second, so a test can tell "was not called" from "threw". */
        private void maybeThrow(@NotNull String hook) {
            if (hook.equals(this.throwingHook)) {
                throw new IllegalStateException("the consumer's controller is broken in " + hook);
            }
        }

        @Override
        public void onPlayerInput(@NotNull SeatInput input) {
            this.events.add("input:#" + input.seatIndex() + ' ' + input.rider().getName()
                    + (input.isIdle() ? " idle" : " active"));
            this.seen.put(input.seatIndex(), input);
            maybeThrow(ON_PLAYER_INPUT);
        }

        @Override
        public void onSeated(
                @NotNull Vehicle vehicle,
                @NotNull VehicleSeat seat,
                @NotNull Player rider
        ) {
            this.events.add("seated:#" + seat.index() + ' ' + rider.getName()
                    + " occupied=" + vehicle.getSeating().occupied());
        }

        @Override
        public void onVacated(
                @NotNull Vehicle vehicle,
                @NotNull VehicleSeat seat,
                @NotNull Player rider
        ) {
            this.events.add("vacated:#" + seat.index() + ' ' + rider.getName()
                    + " occupied=" + vehicle.getSeating().occupied());
        }

        @Override
        public void onStep(@NotNull Vehicle vehicle, float deltaTicks) {
            this.events.add("step");
            this.lastDeltaTicks = deltaTicks;
            maybeThrow(ON_STEP);
        }

        @Override
        public void onClosed(@NotNull Vehicle vehicle) {
            this.events.add("closed riders=" + vehicle.getRiders().size());
            this.closedCount++;
        }
    }

    /**
     * A fault sink that keeps the keys it was told about and gives up after a fixed number of failures.
     *
     * <p>Stands in for {@link InputFaultSink#throttled} without its clock and its configuration: the
     * throttling and the counting are that type's own tested behaviour, and what is under test here is
     * what this layer does with the answer.
     */
    private static final class CountingFaultSink implements InputFaultSink {

        private final int limit;
        private final @NotNull List<String> failures = new ArrayList<>();
        private final @NotNull List<String> successes = new ArrayList<>();

        private CountingFaultSink(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean failed(@NotNull String binding, @NotNull Throwable failure) {
            assertNotNull(failure, "a sink is never told about a failure that is not one");
            this.failures.add(binding);
            return this.failures.size() < this.limit;
        }

        @Override
        public void succeeded(@NotNull String binding) {
            this.successes.add(binding);
        }
    }
}
