package pandastic.studio.models.api.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an {@link InputState} is worth depends on two properties and nothing else: two states that mean
 * the same thing are equal, and no operation on one changes it.
 *
 * <p>Equality is load-bearing beyond the usual reason. The capture layer publishes a new snapshot only
 * when the state actually moved, and "actually moved" is this record's {@code equals} — so a state that
 * compared unequal to an identical one would make every repeated observation a publication, and on a
 * server where two capture sources see the same movement that is a doubling of the dispatch cost with
 * nothing to show for it.
 */
@DisplayName("InputState")
final class InputStateTest {

    @Test
    @DisplayName("two states built from the same keys are equal and hash alike")
    void equality() {
        final InputState first = InputState.of(InputKey.SPRINT, InputKey.JUMP);
        final InputState second = InputState.ofBits(
                InputKey.JUMP.mask() | InputKey.SPRINT.mask());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("a different hotbar slot is a different state")
    void slotParticipatesInEquality() {
        final InputState unknown = InputState.of(InputKey.SNEAK);

        assertNotEquals(unknown, unknown.withHotbarSlot(0));
        assertEquals(InputState.UNKNOWN_HOTBAR_SLOT, unknown.hotbarSlot());
    }

    @Test
    @DisplayName("bits outside the layout are masked off rather than kept")
    void unknownBitsAreMasked() {
        // The movement field is seven bits wide and the server masks it, so a higher bit cannot arrive
        // from a client. One arriving from a caller is a packing mistake, and keeping it would make two
        // states that mean the same thing compare unequal.
        final InputState masked = InputState.ofBits(InputKey.ALL_BITS | (1 << 20));

        assertEquals(InputKey.ALL_BITS, masked.bits());
        assertEquals(InputState.ofBits(InputKey.ALL_BITS), masked);
    }

    @Test
    @DisplayName("with() leaves the original alone and returns the same instance for a no-op")
    void immutability() {
        final InputState original = InputState.of(InputKey.FORWARD);

        final InputState pressed = original.with(InputKey.SNEAK, true);
        assertTrue(pressed.isDown(InputKey.SNEAK));
        assertFalse(original.isDown(InputKey.SNEAK), "the original must not have changed");

        // Returning this rather than an equal copy is what keeps a per-tick fold allocation-free.
        assertSame(original, original.with(InputKey.SNEAK, false));
        assertSame(original, original.with(InputKey.FORWARD, true));
    }

    @Test
    @DisplayName("withoutPulses clears the action bits and keeps the held ones")
    void pulsesAreCleared() {
        final InputState both = InputState.of(InputKey.SNEAK, InputKey.ATTACK, InputKey.DROP_ITEM);

        final InputState held = both.withoutPulses();

        assertTrue(held.isDown(InputKey.SNEAK));
        assertFalse(held.isDown(InputKey.ATTACK));
        assertFalse(held.isDown(InputKey.DROP_ITEM));
        assertEquals(0, held.actionBits());
        // Nothing to clear is answered with the same instance, for the reason with() is.
        assertSame(held, held.withoutPulses());
    }

    @Test
    @DisplayName("the movement and action halves of the bitfield do not overlap")
    void familiesPartitionTheBitfield() {
        final InputState everything = InputState.ofBits(InputKey.ALL_BITS);

        assertEquals(InputKey.MOVEMENT_BITS, everything.movementBits());
        assertEquals(InputKey.ACTION_BITS, everything.actionBits());
        assertEquals(0, InputKey.MOVEMENT_BITS & InputKey.ACTION_BITS);
        assertEquals(InputKey.ALL_BITS, InputKey.MOVEMENT_BITS | InputKey.ACTION_BITS);
    }

    @Test
    @DisplayName("keys() reports exactly what is down, and nothing when idle")
    void keys() {
        assertEquals(Set.of(), InputState.NONE.keys());
        assertEquals(
                Set.of(InputKey.LEFT, InputKey.USE),
                InputState.of(InputKey.LEFT, InputKey.USE).keys());
        assertTrue(InputState.NONE.isIdle());
        assertFalse(InputState.of(InputKey.USE).isIdle());
    }

    @Test
    @DisplayName("every key round-trips through its own bit")
    void bitsRoundTrip() {
        for (final InputKey key : InputKey.values()) {
            assertSame(key, InputKey.ofBit(key.bit()), "bit " + key.bit() + " must map back to " + key);
            assertTrue(InputState.of(key).isDown(key));
            assertEquals(key.mask(), InputState.of(key).bits());
        }
    }

    @Test
    @DisplayName("a slot outside the hotbar is refused rather than masked")
    void slotIsValidated() {
        // Unlike a stray bit, an out-of-range slot is not something a caller forgot to clear: it is a
        // number that means nothing, and silently accepting it would put it on a snapshot a consumer
        // reads as the selected slot.
        assertThrows(IllegalArgumentException.class, () -> new InputState(0, InputState.HOTBAR_SLOTS));
        assertThrows(IllegalArgumentException.class, () -> new InputState(0, -2));
    }

    @Test
    @DisplayName("describe() names the keys that are down")
    void describe() {
        assertEquals("idle", InputState.NONE.describe());
        assertEquals("sneak+use", InputState.of(InputKey.USE, InputKey.SNEAK).describe());
    }
}
