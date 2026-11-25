package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.BoardingOutcome;
import pandastic.studio.models.api.vehicle.BoardingResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seat resolution, driver election, occupancy, following and cleanup.
 *
 * <h2>Why this is tested without a control loop around it</h2>
 * Because the seating rules are the part a server cannot be made to report on. A rider whose carrier was
 * written from the wrong region thread ends up somewhere silently; a carrier nobody released is an
 * invisible entity holding a player that no log line mentions. Both are decisions this class makes above
 * the {@link SeatCarrier} seam, so they are observable here and nowhere else.
 *
 * <h2>The expected state today is zero usable seats</h2>
 * No model in this repository contains a locator element, so every seat every rig declares resolves to
 * nothing. That is why half of these tests are about the unusable outcomes: they are not edge cases, they
 * are what an operator meets first.
 */
class VehicleSeatsTest {

    private static final double EPSILON = 1.0E-6D;

    /**
     * The seat indices {@link VehicleFixtures#seatedRig()} declares, in its file order.
     *
     * <p>Named rather than written as bare numbers because the index <em>is</em> the contract: seats are
     * numbered by declaration order with the driver first, and a rider asking for a particular one is
     * never given another. {@code crate} resolves to a bone and {@code ghost} to nothing, so only the
     * first two are ever seatable — which is what most of this suite is about.
     */
    private static final int DRIVER_SEAT = 0;
    private static final int PILLION_SEAT = 1;
    private static final int CRATE_SEAT = 2;

    private final FakeVehicleHost host = new FakeVehicleHost();
    private final SeatSettings settings = VehicleFixtures.seatSettings();

    private final Player driver = StubPlayer.named("driver");
    private final Player passenger = StubPlayer.named("passenger");

    /** The root entity's reading, which every seat position is measured from. */
    private final VehicleSample root = new VehicleSample();

    private @NotNull VehicleSeats seats(@NotNull Rig rig, @NotNull SeatLocators locators) {
        final VehicleSeats seats = new VehicleSeats(rig, this.host);
        seats.resolve(locators);
        return seats;
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void everyDeclaredSeatIsResolvedOnceAndKeptInFileOrder() {
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        final SeatChart chart = seats.chart();

        assertAll(
                () -> assertEquals(4, chart.declared()),
                () -> assertEquals(4, locators.resolutions(),
                        "a resolution is a scan of the model's bone tree, so it happens once per seat"),
                () -> assertEquals(List.of("driver", "pillion", "crate", "ghost"),
                        chart.entries().stream().map(entry -> entry.seat().name()).toList(),
                        "file order is the order riders are offered seats, so the chart keeps it"),
                () -> assertEquals(List.of(SeatLocators.Kind.LOCATOR, SeatLocators.Kind.LOCATOR,
                                SeatLocators.Kind.BONE, SeatLocators.Kind.UNRESOLVED),
                        chart.entries().stream().map(SeatChart.Entry::kind).toList()),
                () -> assertEquals(2, chart.usableCount()),
                () -> assertEquals(2, chart.unusableCount())
        );
    }

    @Test
    void aSeatNamingABoneIsNeverUsableHoweverTemptingTheNameIs() {
        // The bone called 'mount' that four of this repository's models carry. It is an ordinary bone,
        // the animation pass writes it, and a rider seated on it would be moved by whichever clip is
        // playing — so it is reported and never seated on.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());

        final BoardingResult outcome = seats.mount(this.driver, CRATE_SEAT, this.root, this.settings);

        assertAll(
                () -> assertEquals(BoardingOutcome.SEAT_UNUSABLE, outcome.outcome(),
                        "the seat exists and is not offerable, which is a different answer from an index"
                                + " no seat has — the rigger pointed at a real bone, so telling them"
                                + " 'no such seat' would send them looking for a typo"),
                () -> assertEquals(List.of(), this.host.carriers())
        );
    }

    @Test
    void aModelWithNoLocatorsLeavesEverySeatUnusableAndThatIsAReportedOutcome() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), SeatLocators.NONE);

        assertAll(
                () -> assertEquals(4, seats.chart().declared()),
                () -> assertEquals(0, seats.usableSeats()),
                () -> assertEquals(4, seats.chart().unusableCount()),
                // "nothing here can be sat on" rather than "full": the player gets a different message
                // for each, and this is the EXPECTED one today, because no model in this repository
                // carries a locator element.
                () -> assertEquals(BoardingOutcome.NO_SEAT_RESOLVED,
                        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings).outcome())
        );
    }

    // ------------------------------------------------------------------ driver election

    @Test
    void theDriverIsTheRiderInTheDriverSeatAndNobodyElse() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());

        assertTrue(seats.mount(this.passenger, PILLION_SEAT, this.root, this.settings).isAboard());
        assertAll(
                () -> assertNull(seats.driver(), "a full passenger seat does not make a driver"),
                () -> assertEquals(List.of(this.passenger), seats.riders())
        );

        assertTrue(seats.mount(this.driver, DRIVER_SEAT, this.root, this.settings).isAboard());
        assertAll(
                () -> assertSame(this.driver, seats.driver()),
                () -> assertEquals(2, seats.occupied()),
                () -> assertEquals(List.of(this.driver, this.passenger), seats.riders(),
                        "riders are published in seat order rather than in arrival order")
        );
    }

    @Test
    void theFirstFreeUsableSeatIsOfferedInFileOrder() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());

        assertAll(
                () -> assertEquals("driver",
                        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings).seatTaken().orElseThrow().name()),
                () -> assertEquals("pillion",
                        seats.mount(this.passenger, VehicleSeats.ANY_SEAT, this.root, this.settings).seatTaken().orElseThrow().name()),
                // FULL this time, not NO_SEAT_RESOLVED: two seats did resolve and both are now taken.
                // The two answers are the distinction the previous test's counterpart asserts.
                () -> assertEquals(BoardingOutcome.VEHICLE_FULL,
                        seats.mount(StubPlayer.named("third"), VehicleSeats.ANY_SEAT, this.root,
                                this.settings).outcome(),
                        "the other two seats are a bone and a name the model does not carry")
        );
    }

    @Test
    void mountingSomebodyWhoIsAlreadyAboardMovesNobodyAndSpawnsNothing() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);

        final BoardingResult again = seats.mount(this.driver, PILLION_SEAT, this.root, this.settings);

        assertAll(
                () -> assertEquals(BoardingOutcome.ALREADY_ABOARD, again.outcome()),
                () -> assertTrue(again.isAboard(), "they are aboard, which is what the caller asked"),
                () -> assertEquals(1, this.host.carriers().size())
        );
    }

    // ------------------------------------------------------------------ where the rider ends up

    @Test
    void theCarrierIsSpawnedAtTheLocatorPlusTheSeatOffsetAndTheConfiguredTrim() {
        // The pillion seat sits at the locator (0, 1, -0.5) with an offset of (0.25, 0.5, -0.75), and
        // the fixture's carrier trim is a quarter of a block. At a heading of zero the model's axes are
        // the world's, so the offset is added as authored.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        this.root.set(10.0D, 64.0D, -20.0D, 0F);

        seats.mount(this.passenger, PILLION_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        assertAll(
                () -> assertEquals(10.25D, carrier.x(), EPSILON),
                () -> assertEquals(65.75D, carrier.y(), EPSILON),
                () -> assertEquals(-21.25D, carrier.z(), EPSILON)
        );
    }

    @Test
    void aSeatOffsetTurnsWithTheVehicleRatherThanStayingPutInTheWorld() {
        // A quarter turn maps the model's +x onto the world's +z. A seat whose offset did not turn would
        // swing a rider across the model every time the vehicle changed heading.
        //
        // The locator's own position is deliberately not turned here: it arrives from the renderer
        // already in world axes with the model's rotation applied, which is the division of labour the
        // seam documents. So the pillion's locator keeps its -0.5 z and only the authored offset moves.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        this.root.set(0D, 0D, 0D, 90F);

        seats.mount(this.passenger, PILLION_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        assertAll(
                () -> assertEquals(0.75D, carrier.x(), 1.0E-4D, "the authored -0.75 z became +0.75 x"),
                () -> assertEquals(-0.25D, carrier.z(), 1.0E-4D,
                        "the authored 0.25 x became 0.25 z, on top of the locator's own -0.5"),
                () -> assertEquals(90F, carrier.yaw())
        );
    }

    @Test
    void theOffsetMathIsTheSameAtEveryQuarterTurn() {
        assertAll(
                () -> assertEquals(1D, VehicleSeats.offsetWorldX(0F, 1F, 0F), EPSILON),
                () -> assertEquals(0D, VehicleSeats.offsetWorldZ(0F, 1F, 0F), EPSILON),
                () -> assertEquals(0D, VehicleSeats.offsetWorldX(90F, 1F, 0F), 1.0E-6D),
                () -> assertEquals(1D, VehicleSeats.offsetWorldZ(90F, 1F, 0F), 1.0E-6D),
                () -> assertEquals(-1D, VehicleSeats.offsetWorldX(180F, 1F, 0F), 1.0E-6D),
                () -> assertEquals(-1D, VehicleSeats.offsetWorldZ(0F, 0F, -1F), EPSILON)
        );
    }

    // ------------------------------------------------------------------ following

    @Test
    void aLocatorThatMovedIsFollowedEvenThoughTheVehicleDidNot() {
        // The reason the follow pass is unconditional: a parked vehicle still animates, and an animated
        // locator moves the seat hanging off it.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        locators.moveLocator("saddle", 0D, 2D, 0.5D);
        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertEquals(1, carrier.moves()),
                () -> assertEquals(2.25D, carrier.y(), EPSILON)
        );
    }

    @Test
    void aMovementInsideTheDeadbandIsNotWritten() {
        // Without the floor, an idle clip costs a position packet per rider per tick for a displacement
        // nobody can resolve. The fixture's deadband is a tenth of a block.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);

        locators.moveLocator("saddle", 0D, 1.05D, 0.5D);
        seats.follow(this.root, this.settings);

        assertEquals(0, this.host.onlyCarrier().moves());
    }

    @Test
    void aDisplacementPastTheCeilingBecomesARelocationAndStopsTheFollowUntilItCompletes() {
        // What a vehicle that just crossed a region boundary does to its seats. The fixture's ceiling is
        // three blocks.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        this.root.set(500.0D, 64.0D, 500.0D, 0F);
        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertEquals(1, carrier.teleports()),
                () -> assertEquals(0, carrier.moves()),
                () -> assertTrue(carrier.hasPendingTeleport())
        );

        seats.follow(this.root, this.settings);
        assertEquals(1, carrier.teleports(),
                "nothing may be written while the carrier is transiently out of the world");

        carrier.completeTeleport(true, false);
        seats.follow(this.root, this.settings);
        assertSame(this.driver, seats.driver());
    }

    @Test
    void aRiderThePlatformDroppedWhileRelocatingIsPutBackExactlyOnce() {
        // The documented hazard: teleporting an entity that is carrying a passenger is where the
        // platform dismounts riders. It is also the only case a rider is remounted — somebody who got up
        // deliberately must not be trapped aboard.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        this.root.set(500.0D, 64.0D, 500.0D, 0F);
        seats.follow(this.root, this.settings);
        carrier.completeTeleport(true, true);

        seats.follow(this.root, this.settings);
        assertAll(
                () -> assertSame(this.driver, carrier.rider(), "the rider is put back in their seat"),
                () -> assertSame(this.driver, seats.driver()),
                () -> assertEquals(0, carrier.releases())
        );

        // And now the same empty seat without a relocation behind it, which is somebody getting up.
        carrier.seat(null);
        seats.follow(this.root, this.settings);
        assertAll(
                () -> assertNull(seats.driver()),
                () -> assertEquals(1, carrier.releases())
        );
    }

    @Test
    void aCarrierInAnotherRegionIsLeftAloneForThatStepRatherThanWrittenTo() {
        // A cross-region write on this platform is discarded silently rather than refused, so declining
        // is the only correct behaviour — and the next step picks it up.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        locators.moveLocator("saddle", 0D, 2D, 0.5D);
        carrier.owned(false);
        seats.follow(this.root, this.settings);
        assertAll(
                () -> assertEquals(0, carrier.moves()),
                () -> assertEquals(0, carrier.releases(), "it is not a teardown, it is a postponement"),
                () -> assertSame(this.driver, seats.driver())
        );

        carrier.owned(true);
        seats.follow(this.root, this.settings);
        assertEquals(1, carrier.moves());
    }

    @Test
    void aCarrierWithNoInPlaceMoveIsRelocatedEveryStep() {
        // What a server with no Mojang-mapped adapter costs: expensive rather than wrong, exactly as the
        // root entity's own movement already is.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        this.host.carrierLocalMove(false);
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        locators.moveLocator("saddle", 0D, 2D, 0.5D);
        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertEquals(1, carrier.teleports()),
                () -> assertEquals(0, carrier.moves())
        );
    }

    @Test
    void aModelThatWentAwayPutsItsRidersDown() {
        // A seat on a model that is no longer rendered is a position nobody can compute, so the rider
        // goes back on the ground rather than to wherever the carrier last happened to be.
        final FakeSeatLocators locators = VehicleFixtures.seatedLocators();
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), locators);
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        locators.modelGone();
        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertNull(seats.driver()),
                () -> assertEquals(1, carrier.releases())
        );
    }

    @Test
    void aCarrierSomebodyRemovedVacatesItsSeat() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();

        carrier.alive(false);
        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertNull(seats.driver()),
                () -> assertEquals(0, seats.occupied())
        );
    }

    // ------------------------------------------------------------------ cleanup

    @Test
    void dismountingOneRiderLeavesTheOthersAboard() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        seats.mount(this.passenger, VehicleSeats.ANY_SEAT, this.root, this.settings);

        assertAll(
                () -> assertTrue(seats.dismount(this.driver)),
                () -> assertNull(seats.driver()),
                () -> assertEquals(List.of(this.passenger), seats.riders()),
                () -> assertEquals(1, this.host.carriers().getFirst().releases()),
                () -> assertEquals(0, this.host.carriers().get(1).releases()),
                () -> assertFalse(seats.dismount(this.driver), "they are already down")
        );
    }

    @Test
    void dismountAllReleasesEveryCarrier() {
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        seats.mount(this.passenger, VehicleSeats.ANY_SEAT, this.root, this.settings);

        assertAll(
                () -> assertEquals(2, seats.dismountAll()),
                () -> assertEquals(List.of(), seats.riders()),
                () -> assertTrue(this.host.carriers().stream().allMatch(c -> c.releases() == 1))
        );
    }

    @Test
    void releaseIsIdempotentAndLeavesNoCarrierBehind() {
        // The teardown of last resort, callable from any thread, because a leaked carrier is an invisible
        // entity holding a player that nothing will ever remove.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        seats.mount(this.passenger, VehicleSeats.ANY_SEAT, this.root, this.settings);

        assertAll(
                () -> assertEquals(2, seats.release()),
                () -> assertEquals(0, seats.release(), "a second release has nothing left to do"),
                () -> assertTrue(this.host.carriers().stream().allMatch(c -> c.releases() == 1)),
                () -> assertNull(seats.driver()),
                () -> assertEquals(List.of(), seats.riders())
        );
    }

    @Test
    void followingAfterAReleaseTouchesNothing() {
        // A step already in flight when the teardown ran must not resurrect a seat.
        final VehicleSeats seats = seats(VehicleFixtures.seatedRig(), VehicleFixtures.seatedLocators());
        seats.mount(this.driver, VehicleSeats.ANY_SEAT, this.root, this.settings);
        final FakeSeatCarrier carrier = this.host.onlyCarrier();
        seats.release();

        seats.follow(this.root, this.settings);

        assertAll(
                () -> assertEquals(0, carrier.moves()),
                () -> assertEquals(1, carrier.releases())
        );
    }
}
