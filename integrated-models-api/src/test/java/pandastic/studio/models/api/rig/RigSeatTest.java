package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seat on its own, and a set of them on a rig.
 *
 * <p>The api <strong>throws</strong> here, while the rig loader clamps and warns about the same
 * mistakes. Both are deliberate: a programmatic caller passing a bad seat has a bug, and a file has an
 * author with a text editor.
 */
class RigSeatTest {

    private static Rig.Builder cart() {
        return Rig.builder("cart", "cart_model", RigFixtures.rigBounds(),
                        RigFixtures.wideProfileBounds())
                .kind(RigKind.VEHICLE)
                .seatBounds(RigFixtures.wideSeatBounds())
                .root("chassis");
    }

    @Test
    void namesALocatorAndNormalisesItTheWayABoneNameIs() {
        // A locator reaches the renderer's bone tree carrying the same kind of identifier a bone does,
        // tags and all, so a seat keyed on the raw identifier breaks the moment a rigger adds one.
        final RigSeat seat = RigSeat.of("Driver", "H_Seat_Driver", true);

        assertAll(
                () -> assertEquals("seat_driver", seat.locator()),
                // The seat's own name is ours to interpret, so it is merely lower-cased.
                () -> assertEquals("driver", seat.name()),
                () -> assertTrue(seat.driver()),
                () -> assertTrue(seat.isOnLocator()));
    }

    @Test
    void refusesASeatThatNamesNothing() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RigSeat.of(" ", "seat_a", false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RigSeat.of("driver", " ", false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RigSeat("driver", "seat_a", false, Float.NaN, 0F, 0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RigSeat("driver", "seat_a", false,
                                0F, Float.POSITIVE_INFINITY, 0F)));
    }

    @Test
    void refusesAnOffsetOutsideTheConfiguredRangeAndSaysWhy() {
        // Symmetric, because an offset is a displacement: its sign is a direction.
        final SeatBounds bounds = new SeatBounds(4, 0.5F);
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new RigSeat("driver", "seat_a", false, 0F, 0.75F, 0F).requireWithin(bounds));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("offsetY"),
                        "it must name the axis, got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("0.5"),
                        "it must quote the configured bound, got: " + thrown.getMessage()),
                () -> assertDoesNotThrow(() -> new RigSeat("driver", "seat_a", false, 0F, -0.5F, 0F)
                        .requireWithin(bounds)));
    }

    @Test
    void aVehicleIsTheOnlyKindThatMayCarrySeats() {
        // The rule the kind exists for. Checked at build() rather than at seat(), so a caller may set
        // the kind and the seats in either order.
        final Rig.Builder creature = Rig.builder("deer", "deer_model", RigFixtures.rigBounds(),
                        RigFixtures.wideProfileBounds())
                .seatBounds(RigFixtures.wideSeatBounds())
                .root("torso")
                .seat(RigSeat.of("saddle", "seat_saddle", true));

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, creature::build);
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("creature"),
                        "it must name the kind that refused, got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("vehicle"),
                        "it must name the kind that would work, got: " + thrown.getMessage()),
                // And the same seats on a vehicle are fine, which is what makes the message actionable.
                () -> assertEquals(1, creature.kind(RigKind.VEHICLE).build().seatCount()));
    }

    @Test
    void refusesASeatBeforeTheOperatorsBoundsAreKnown() {
        // An IllegalStateException rather than an IllegalArgumentException, because the seat is fine
        // and the caller has not said what this server permits. There is deliberately no default: a
        // seat budget is configuration, and a literal here would be configuration written in Java.
        final Rig.Builder builder = Rig.builder("cart", "cart_model", RigFixtures.rigBounds(),
                        RigFixtures.wideProfileBounds())
                .kind(RigKind.VEHICLE)
                .root("chassis");

        assertThrows(IllegalStateException.class, () -> builder.seat(RigSeat.of("a", "seat_a", false)));
    }

    @Test
    void refusesTwoSeatsWithTheSameName() {
        // Names are matched case-insensitively, so these are one seat declared twice and a consumer
        // asking for it would always get whichever came first.
        final Rig.Builder builder = cart().seat(RigSeat.of("bench", "seat_left", false));

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.seat(RigSeat.of("BENCH", "seat_right", false)));
        assertTrue(thrown.getMessage().contains("bench"),
                "it must name the seat, got: " + thrown.getMessage());
    }

    @Test
    void refusesASecondDriversSeat() {
        final Rig.Builder builder = cart().seat(RigSeat.of("front", "seat_front", true));

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.seat(RigSeat.of("back", "seat_back", true)));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("front"),
                        "it must name the seat that already drives, got: " + thrown.getMessage()),
                // Two passenger seats are of course fine, and so are two seats on one locator: a bench
                // is one locator and two offsets.
                () -> assertDoesNotThrow(() -> cart()
                        .seat(new RigSeat("left", "seat_bench", false, -0.3F, 0F, 0F))
                        .seat(new RigSeat("right", "seat_bench", false, 0.3F, 0F, 0F))
                        .build()));
    }

    @Test
    void refusesMoreSeatsThanTheConfiguredCeiling() {
        final Rig.Builder builder = Rig.builder("cart", "cart_model", RigFixtures.rigBounds(),
                        RigFixtures.wideProfileBounds())
                .kind(RigKind.VEHICLE)
                .seatBounds(new SeatBounds(2, 1F))
                .root("chassis")
                .seat(RigSeat.of("a", "seat_a", false))
                .seat(RigSeat.of("b", "seat_b", false));

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.seat(RigSeat.of("c", "seat_c", false)));
        assertTrue(thrown.getMessage().contains("2-seat ceiling"),
                "it must state the configured ceiling, got: " + thrown.getMessage());
    }

    @Test
    void keepsSeatsInTheOrderTheyWereAdded() {
        // File order is the order riders are offered seats in, so it is part of the rig's meaning
        // rather than an accident of how they were stored.
        final Rig rig = cart()
                .seat(RigSeat.of("c", "seat_c", false))
                .seat(RigSeat.of("a", "seat_a", true))
                .seat(RigSeat.of("b", "seat_b", false))
                .build();

        assertAll(
                () -> assertEquals(List.of("c", "a", "b"),
                        rig.seats().stream().map(RigSeat::name).toList()),
                () -> assertEquals("a", rig.driverSeat().orElseThrow().name()),
                () -> assertEquals("b", rig.findSeat("B").orElseThrow().name()),
                () -> assertTrue(rig.findSeat("nobody").isEmpty()));
    }

    @Test
    void aRigWithNoSeatsHasNoDriver() {
        final Rig rig = Rig.builder("deer", "deer_model", RigFixtures.rigBounds(),
                RigFixtures.wideProfileBounds()).root("torso").build();

        assertAll(
                () -> assertEquals(0, rig.seatCount()),
                () -> assertTrue(rig.seats().isEmpty()),
                () -> assertTrue(rig.driverSeat().isEmpty()),
                // Legal for a vehicle too: a vehicle rig whose model has no locators yet loads and
                // carries nobody, which is the expected state until a rigger adds them.
                () -> assertFalse(cart().kind(RigKind.VEHICLE).build().driverSeat().isPresent()));
    }
}
