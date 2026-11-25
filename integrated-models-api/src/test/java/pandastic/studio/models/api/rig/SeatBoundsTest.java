package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatBoundsTest {

    @Test
    void refusesASeatCeilingBelowOne() {
        // A ceiling of zero does not limit a vehicle rig, it makes every vehicle rig on the server
        // unrideable - which is a configuration an operator cannot have meant.
        assertAll(
                () -> assertRefused(() -> new SeatBounds(0, 1F), "maxSeats"),
                () -> assertRefused(() -> new SeatBounds(-1, 1F), "maxSeats"),
                () -> assertDoesNotThrow(() -> new SeatBounds(1, 0F)));
    }

    @Test
    void acceptsAZeroOffsetCeilingBecauseASeatIsItsLocator() {
        // Zero is a legal and meaningful configuration: riders sit exactly on the locator the rigger
        // placed, which is where a well-placed locator already is.
        final SeatBounds exact = new SeatBounds(2, 0F);
        assertAll(
                () -> assertEquals(0F, exact.maxSeatOffsetBlocks()),
                () -> assertDoesNotThrow(() -> RigSeat.of("s", "seat_a", false).requireWithin(exact)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RigSeat("s", "seat_a", false, 0F, 0.01F, 0F).requireWithin(exact)));
    }

    @Test
    void refusesAnOffsetCeilingThatIsNotANonNegativeNumber() {
        assertAll(
                () -> assertRefused(() -> new SeatBounds(2, -1F), "maxSeatOffsetBlocks"),
                () -> assertRefused(() -> new SeatBounds(2, Float.NaN), "maxSeatOffsetBlocks"),
                () -> assertRefused(
                        () -> new SeatBounds(2, Float.POSITIVE_INFINITY), "maxSeatOffsetBlocks"));
    }

    private static void assertRefused(Runnable construction, String component) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, construction::run);
        assertTrue(thrown.getMessage().contains(component),
                "it must name the component, got: " + thrown.getMessage());
    }
}
