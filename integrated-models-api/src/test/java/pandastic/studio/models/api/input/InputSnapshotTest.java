package pandastic.studio.models.api.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot is the value that crosses a thread boundary, so the only two things worth asserting about
 * it are that it cannot be changed after publication and that it answers the timing questions a
 * combination asks.
 *
 * <p>The immutability test is not ceremony. The capture layer owns mutable per-key arrays that it folds
 * every observation into, and it publishes <em>copies</em> of them; a snapshot that aliased those
 * arrays would appear to change under a reader that was holding it for a whole control step, which is
 * precisely the mixed-generation read the whole publication design exists to make impossible.
 */
@DisplayName("InputSnapshot")
final class InputSnapshotTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("the timing arrays are copied in, so a later write by the capture layer is not visible")
    void arraysAreCopied() {
        final long[] downSince = new long[InputKey.COUNT];
        final long[] gaps = new long[InputKey.COUNT];
        Arrays.fill(gaps, InputTrace.NO_EARLIER_PRESS);
        downSince[InputKey.SNEAK.bit()] = SECOND;

        final InputSnapshot snapshot = InputSnapshot.of(
                InputState.of(InputKey.SNEAK), InputState.NONE, 2L * SECOND, downSince, gaps, 1L);

        assertEquals(SECOND, snapshot.heldNanos(InputKey.SNEAK));

        // The capture layer keeps folding into its own arrays. The published snapshot must not move.
        downSince[InputKey.SNEAK.bit()] = 0L;
        gaps[InputKey.SNEAK.bit()] = 1L;

        assertEquals(SECOND, snapshot.heldNanos(InputKey.SNEAK));
        assertEquals(InputTrace.NO_EARLIER_PRESS, snapshot.lastPressGapNanos(InputKey.SNEAK));
    }

    @Test
    @DisplayName("a hold is only measured for a key that is down")
    void heldOnlyWhileDown() {
        final long[] downSince = new long[InputKey.COUNT];
        downSince[InputKey.FORWARD.bit()] = 0L;

        final InputSnapshot snapshot = InputSnapshot.of(
                InputState.NONE, InputState.of(InputKey.FORWARD), SECOND,
                downSince, new long[InputKey.COUNT], 3L);

        // The press time is still in the array; the key is up, so the answer is zero rather than "a
        // second". A released key that reported a growing hold would make hold() match after release.
        assertEquals(0L, snapshot.heldNanos(InputKey.FORWARD));
        assertTrue(snapshot.justReleased(InputKey.FORWARD));
        assertFalse(snapshot.justPressed(InputKey.FORWARD));
    }

    @Test
    @DisplayName("edges come from the state pair, with no history needed")
    void edges() {
        final InputSnapshot snapshot = InputSnapshot.ofFlags(
                InputState.of(InputKey.JUMP), InputState.of(InputKey.SNEAK), SECOND);

        assertTrue(snapshot.justPressed(InputKey.JUMP));
        assertTrue(snapshot.justReleased(InputKey.SNEAK));
        assertFalse(snapshot.justPressed(InputKey.SNEAK));
        assertFalse(snapshot.justReleased(InputKey.JUMP));
    }

    @Test
    @DisplayName("ofFlags answers no hold and no earlier press, which is honest rather than lossy")
    void flagsOnlyHasNoHistory() {
        final InputSnapshot snapshot = InputSnapshot.ofFlags(
                InputState.of(InputKey.SNEAK), InputState.NONE, SECOND);

        assertEquals(0L, snapshot.heldNanos(InputKey.SNEAK));
        assertEquals(InputTrace.NO_EARLIER_PRESS, snapshot.lastPressGapNanos(InputKey.SNEAK));
        // Which means a hold or a double tap evaluated against it does not match, and that is the
        // correct answer for a caller that has observed no history.
        assertFalse(InputCombination.hold(InputKey.SNEAK).matches(snapshot, tuning()));
        assertFalse(InputCombination.doubleTap(InputKey.SNEAK).matches(snapshot, tuning()));
    }

    @Test
    @DisplayName("the idle snapshot holds nothing and knows no slot")
    void idle() {
        assertEquals(InputState.NONE, InputSnapshot.IDLE.state());
        assertEquals(InputState.NONE, InputSnapshot.IDLE.previous());
        assertEquals(InputState.UNKNOWN_HOTBAR_SLOT, InputSnapshot.IDLE.hotbarSlot());
        assertEquals(0L, InputSnapshot.IDLE.sequence());
        for (final InputKey key : InputKey.values()) {
            assertFalse(InputSnapshot.IDLE.isDown(key));
            assertEquals(0L, InputSnapshot.IDLE.heldNanos(key));
            assertEquals(InputTrace.NO_EARLIER_PRESS, InputSnapshot.IDLE.lastPressGapNanos(key));
        }
    }

    @Test
    @DisplayName("staleness is measured from the publication time")
    void staleness() {
        final InputSnapshot snapshot = InputSnapshot.ofFlags(
                InputState.of(InputKey.FORWARD), InputState.NONE, SECOND);

        assertFalse(snapshot.isStale(SECOND + 100L, SECOND));
        assertFalse(snapshot.isStale(2L * SECOND, SECOND));
        assertTrue(snapshot.isStale(2L * SECOND + 1L, SECOND));
    }

    @Test
    @DisplayName("equality covers the timing arrays, not just the flags")
    void equality() {
        final long[] gaps = new long[InputKey.COUNT];
        final long[] otherGaps = new long[InputKey.COUNT];
        otherGaps[InputKey.FORWARD.bit()] = 42L;

        final InputSnapshot first = InputSnapshot.of(
                InputState.NONE, InputState.NONE, SECOND, new long[InputKey.COUNT], gaps, 1L);
        final InputSnapshot same = InputSnapshot.of(
                InputState.NONE, InputState.NONE, SECOND, new long[InputKey.COUNT], gaps, 1L);
        final InputSnapshot different = InputSnapshot.of(
                InputState.NONE, InputState.NONE, SECOND, new long[InputKey.COUNT], otherGaps, 1L);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    @DisplayName("an array of the wrong length is refused")
    void lengthIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> InputSnapshot.of(
                InputState.NONE, InputState.NONE, 0L, new long[1], new long[InputKey.COUNT], 0L));
        assertThrows(IllegalArgumentException.class, () -> InputSnapshot.of(
                InputState.NONE, InputState.NONE, 0L, new long[InputKey.COUNT], new long[1], 0L));
    }

    private static InputTuning tuning() {
        return InputTuning.ofMillis(250L, 500L, 250L, 1_500L);
    }
}
