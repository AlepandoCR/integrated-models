package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bitfield, both ways, for every value it can take.
 *
 * <p>Enumerating all 128 combinations is not thoroughness for its own sake: the encoding's whole purpose
 * is that the value the platform hands us and the value this record decodes are the same seven facts, and
 * a transposed pair of masks is invisible in any single hand-written case. One loop proves it for the
 * whole domain.
 */
class RiderIntentTest {

    @Test
    void everyBitCombinationSurvivesTheRoundTrip() {
        assertAll(java.util.stream.IntStream.rangeClosed(0, RiderIntent.ALL_BITS)
                .mapToObj(bits -> () -> assertEquals(bits, RiderIntent.ofBits(bits).toBits(),
                        () -> "bitfield " + bits + " did not survive the round trip")));
    }

    @Test
    void eachMaskSetsExactlyItsOwnKey() {
        assertAll(
                () -> assertEquals(new RiderIntent(true, false, false, false, false, false, false),
                        RiderIntent.ofBits(RiderIntent.FORWARD_BIT)),
                () -> assertEquals(new RiderIntent(false, true, false, false, false, false, false),
                        RiderIntent.ofBits(RiderIntent.BACKWARD_BIT)),
                () -> assertEquals(new RiderIntent(false, false, true, false, false, false, false),
                        RiderIntent.ofBits(RiderIntent.LEFT_BIT)),
                () -> assertEquals(new RiderIntent(false, false, false, true, false, false, false),
                        RiderIntent.ofBits(RiderIntent.RIGHT_BIT)),
                () -> assertEquals(new RiderIntent(false, false, false, false, true, false, false),
                        RiderIntent.ofBits(RiderIntent.JUMP_BIT)),
                () -> assertEquals(new RiderIntent(false, false, false, false, false, true, false),
                        RiderIntent.ofBits(RiderIntent.SNEAK_BIT)),
                () -> assertEquals(new RiderIntent(false, false, false, false, false, false, true),
                        RiderIntent.ofBits(RiderIntent.SPRINT_BIT))
        );
    }

    @Test
    void theSevenMasksAreDistinctAndCoverAllBits() {
        final int[] masks = {
                RiderIntent.FORWARD_BIT, RiderIntent.BACKWARD_BIT, RiderIntent.LEFT_BIT,
                RiderIntent.RIGHT_BIT, RiderIntent.JUMP_BIT, RiderIntent.SNEAK_BIT,
                RiderIntent.SPRINT_BIT
        };
        int union = 0;
        for (final int mask : masks) {
            // A mask that overlapped another would make two keys the same key, which the round trip
            // above cannot see because it would be symmetric.
            assertEquals(0, union & mask, () -> "mask " + mask + " overlaps an earlier one");
            union |= mask;
        }
        assertEquals(RiderIntent.ALL_BITS, union, "ALL_BITS is not the union of the seven masks");
    }

    @Test
    void bitsAboveTheEncodingAreIgnoredRatherThanRefused() {
        // The server masks the packet's field to seven bits, so a higher bit cannot arrive from a
        // client. One arriving from a caller is a packing mistake on its side, and refusing it would
        // turn a cosmetic error into a vehicle that stops responding.
        final RiderIntent decoded = RiderIntent.ofBits(RiderIntent.FORWARD_BIT | 1 << 20);

        assertAll(
                () -> assertTrue(decoded.forward()),
                () -> assertEquals(RiderIntent.FORWARD_BIT, decoded.toBits(),
                        "the unknown bit was carried back out")
        );
    }

    @Test
    void noneIsEmptyAndIdle() {
        assertAll(
                () -> assertEquals(0, RiderIntent.NONE.toBits()),
                () -> assertTrue(RiderIntent.NONE.isIdle()),
                () -> assertFalse(RiderIntent.ofBits(RiderIntent.SNEAK_BIT).isIdle(),
                        "a held key is not idle even when it steers nothing")
        );
    }
}
