package pandastic.studio.models.internal.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputTrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The track is the hand-over, so what matters about it is what it publishes and when.
 *
 * <p>Three of these tests are about things that cost something to get wrong and nothing to get wrong
 * quietly: that an unchanged observation publishes nothing (two capture sources see every movement
 * change, so a track that published both would double the dispatch cost), that a momentary input stays
 * visible long enough for a polling reader and no longer, and that a forgotten player reads as idle
 * rather than as whatever was held when the connection dropped.
 */
@DisplayName("InputTrack")
final class InputTrackTest {

    private static final long MILLISECOND = 1_000_000L;
    private static final long NOW = 1_000L * MILLISECOND;

    @Test
    @DisplayName("the first observation publishes, and an identical one does not")
    void publishesOnChangeOnly() {
        final InputTrack track = new InputTrack();

        final InputSnapshot first = track.held(InputKey.FORWARD.mask(), NOW);
        assertNotNull(first);
        assertTrue(first.isDown(InputKey.FORWARD));
        assertEquals(1L, first.sequence());

        // The same bits again. On a server with both capture sources this is the ordinary case, not an
        // edge one: whichever source arrives second sees exactly this.
        assertNull(track.held(InputKey.FORWARD.mask(), NOW + MILLISECOND));
        assertSame(first, track.current());
        assertEquals(1L, track.current().sequence());
    }

    @Test
    @DisplayName("the previous state is the last published one, so the edges are the step a reader sees")
    void edgesFollowPublication() {
        final InputTrack track = new InputTrack();
        track.held(InputKey.FORWARD.mask(), NOW);

        final InputSnapshot second = track.held(
                InputKey.FORWARD.mask() | InputKey.SPRINT.mask(), NOW + 10L * MILLISECOND);

        assertNotNull(second);
        assertTrue(second.justPressed(InputKey.SPRINT));
        assertFalse(second.justPressed(InputKey.FORWARD), "forward was already down");
        assertEquals(10L * MILLISECOND, second.heldNanos(InputKey.FORWARD));
        assertEquals(0L, second.heldNanos(InputKey.SPRINT));
    }

    @Test
    @DisplayName("a momentary input publishes every time, so two clicks are two inputs")
    void pulsesAlwaysPublish() {
        final InputTrack track = new InputTrack();

        final InputSnapshot first = track.pulse(InputKey.ATTACK, NOW);
        final InputSnapshot second = track.pulse(InputKey.ATTACK, NOW + 100L * MILLISECOND);

        assertTrue(first.isDown(InputKey.ATTACK));
        assertTrue(second.isDown(InputKey.ATTACK));
        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        // Collapsing the second one would make a double tap undetectable, which is the whole reason a
        // pulse does not go through the change comparison.
        assertTrue(second.justPressed(InputKey.ATTACK));
        assertEquals(100L * MILLISECOND, second.lastPressGapNanos(InputKey.ATTACK));
    }

    @Test
    @DisplayName("a momentary bit survives into the next movement observation and then clears")
    void pulsesLingerExactlyOnePublication() {
        final InputTrack track = new InputTrack();
        track.held(InputKey.FORWARD.mask(), NOW);
        track.pulse(InputKey.USE, NOW + MILLISECOND);

        assertTrue(track.current().isDown(InputKey.USE));
        assertTrue(track.current().isDown(InputKey.FORWARD), "a pulse must not drop the held bits");

        final InputSnapshot cleared = track.held(InputKey.FORWARD.mask(), NOW + 2L * MILLISECOND);

        assertNotNull(cleared, "clearing a pulse is a change, so it is a publication");
        assertFalse(cleared.isDown(InputKey.USE));
        assertTrue(cleared.isDown(InputKey.FORWARD));
    }

    @Test
    @DisplayName("a momentary bit that nothing clears expires on the visibility window")
    void pulsesExpire() {
        // The client only sends a movement packet when the movement changes, so "the next publication"
        // can be a long time coming. Without this, one click would read as held until the player next
        // moved.
        final InputTrack track = new InputTrack();
        track.pulse(InputKey.DROP_ITEM, NOW);

        final long window = 100L * MILLISECOND;
        assertTrue(track.current(NOW + window, window).isDown(InputKey.DROP_ITEM));

        final InputSnapshot expired = track.current(NOW + window + 1L, window);
        assertFalse(expired.isDown(InputKey.DROP_ITEM));

        // Republished once rather than recomputed per read, so a second reader does no work.
        assertSame(expired, track.current());
        assertSame(expired, track.current(NOW + window + 2L, window));
    }

    @Test
    @DisplayName("a held key is untouched by the pulse expiry")
    void expiryKeepsHeldBits() {
        final InputTrack track = new InputTrack();
        track.held(InputKey.SNEAK.mask(), NOW);
        track.pulse(InputKey.ATTACK, NOW);

        final long window = 50L * MILLISECOND;
        final InputSnapshot expired = track.current(NOW + window + 1L, window);

        assertTrue(expired.isDown(InputKey.SNEAK));
        assertFalse(expired.isDown(InputKey.ATTACK));
    }

    @Test
    @DisplayName("a hotbar change carries the slot and the pulse in one publication")
    void hotbarSlot() {
        final InputTrack track = new InputTrack();

        final InputSnapshot snapshot = track.hotbarSlot(4, NOW);

        assertEquals(4, snapshot.hotbarSlot());
        assertTrue(snapshot.isDown(InputKey.SELECT_HOTBAR_SLOT));
        // The slot persists past the pulse, because it is state rather than a moment.
        final InputSnapshot later = track.held(0, NOW + MILLISECOND);
        assertNotNull(later);
        assertEquals(4, later.hotbarSlot());
        assertFalse(later.isDown(InputKey.SELECT_HOTBAR_SLOT));
    }

    @Test
    @DisplayName("a press gap is only reported once there has been an earlier press")
    void firstPressHasNoGap() {
        final InputTrack track = new InputTrack();

        final InputSnapshot first = track.held(InputKey.FORWARD.mask(), NOW);
        assertEquals(InputTrace.NO_EARLIER_PRESS, first.lastPressGapNanos(InputKey.FORWARD));

        track.held(0, NOW + 10L * MILLISECOND);
        final InputSnapshot second = track.held(InputKey.FORWARD.mask(), NOW + 60L * MILLISECOND);

        assertNotNull(second);
        assertEquals(60L * MILLISECOND, second.lastPressGapNanos(InputKey.FORWARD));
    }

    @Test
    @DisplayName("reset forgets everything, so a dropped connection stops driving")
    void reset() {
        final InputTrack track = new InputTrack();
        track.held(InputKey.FORWARD.mask() | InputKey.SPRINT.mask(), NOW);

        track.reset();

        assertSame(InputSnapshot.IDLE, track.current());
        // And the next observation starts a fresh sequence, so a reader comparing sequence numbers
        // across a relog sees a restart rather than a gap.
        final InputSnapshot fresh = track.held(InputKey.FORWARD.mask(), NOW + MILLISECOND);
        assertNotNull(fresh);
        assertEquals(1L, fresh.sequence());
        assertTrue(fresh.justPressed(InputKey.FORWARD));
    }

    @Test
    @DisplayName("bits outside the movement family are ignored by a movement observation")
    void movementIgnoresActionBits() {
        // A capture source pushing an action bit as movement would publish a press that nothing ever
        // releases: the release of a held key is the absence of its bit next time, and no movement
        // packet carries an action bit.
        final InputTrack track = new InputTrack();

        final InputSnapshot snapshot =
                track.held(InputKey.SNEAK.mask() | InputKey.ATTACK.mask(), NOW);

        assertNotNull(snapshot);
        assertTrue(snapshot.isDown(InputKey.SNEAK));
        assertFalse(snapshot.isDown(InputKey.ATTACK));
    }
}
