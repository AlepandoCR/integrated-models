package pandastic.studio.models.api.input;

import org.jetbrains.annotations.NotNull;

/**
 * Everything a combination is allowed to ask about a player's input.
 *
 * <h2>Why a combination matches against this and not against an {@link InputState}</h2>
 * Because half the useful combinations are about <em>change</em> rather than about state. "Jump was
 * just pressed", "sneak has been held for half a second", "forward was tapped twice" cannot be
 * answered by a bitfield, and reconstructing them in the consumer means every consumer keeps its own
 * history and gets the edge cases wrong separately. So the capture layer keeps the history once, and
 * this is the read model it exposes.
 *
 * <h2>Everything here is derivable from one published value</h2>
 * Deliberately. {@link InputSnapshot} implements this interface and is immutable, so a matcher holds
 * one object for the whole evaluation and cannot see two generations of input inside one decision —
 * the same discipline the simulation's driver sample and configuration snapshot already have. A trace
 * that queried a live tracker per method would reintroduce exactly that hazard, which is why no method
 * here is allowed to need anything the snapshot does not carry.
 *
 * <h2>Nanoseconds, and only differences</h2>
 * Every time on this interface comes from a monotonic source, so the absolute value means nothing and
 * only differences do. Milliseconds would have been enough for a double-tap window and are not enough
 * for the window's own measurement: the OS timer is granular to 1–16 ms on Windows, which against a
 * 250 ms window quantises the answer near the boundary.
 */
public interface InputTrace {

    /**
     * The gap reported for a key that has only ever been pressed once, or never.
     *
     * <p>Structure: the identity element for "no earlier press", chosen as the maximum so that every
     * {@code gap <= window} test fails for it without the test having to special-case it.
     */
    long NO_EARLIER_PRESS = Long.MAX_VALUE;

    /** The input as of this trace. */
    @NotNull InputState state();

    /**
     * The input as of the publication before this one.
     *
     * <p>What makes the edges derivable without a history. For the very first trace of a player's
     * session this is {@link InputState#NONE}, so a key already down when the player was first
     * observed reads as a press — which is the useful answer, since nothing before that point exists.
     */
    @NotNull InputState previous();

    /**
     * When this trace was published, on the monotonic clock the capture layer uses.
     *
     * <p>Only differences against {@link #heldNanos(InputKey)} and the tuning windows are meaningful.
     */
    long nowNanos();

    /**
     * How long {@code key} has been down, or {@code 0} when it is up.
     *
     * <p>Measured from the press to {@link #nowNanos()}, so it does not grow while a trace is held: a
     * consumer that keeps one snapshot for a whole control step sees one duration for that step, which
     * is what makes a {@link InputCombination#hold} decision reproducible within the step.
     *
     * @param key the key to measure
     * @return the hold duration in nanoseconds, never negative
     */
    long heldNanos(@NotNull InputKey key);

    /**
     * The gap between the most recent press of {@code key} and the press before it.
     *
     * <p>{@link #NO_EARLIER_PRESS} when there has been at most one press. This is the one quantity a
     * double tap needs and the reason the capture layer keeps two press times per key rather than a
     * list: a third tap is a second double tap, so nothing is lost by not remembering the whole
     * sequence.
     *
     * @param key the key to measure
     * @return the gap in nanoseconds, or {@link #NO_EARLIER_PRESS}
     */
    long lastPressGapNanos(@NotNull InputKey key);

    /** Whether {@code key} is down as of {@link #state()}. */
    default boolean isDown(@NotNull InputKey key) {
        return state().isDown(key);
    }

    /** Whether {@code key} went down between {@link #previous()} and {@link #state()}. */
    default boolean justPressed(@NotNull InputKey key) {
        return state().isDown(key) && !previous().isDown(key);
    }

    /** Whether {@code key} came up between {@link #previous()} and {@link #state()}. */
    default boolean justReleased(@NotNull InputKey key) {
        return !state().isDown(key) && previous().isDown(key);
    }

    /** The selected hotbar slot, or {@link InputState#UNKNOWN_HOTBAR_SLOT}. */
    default int hotbarSlot() {
        return state().hotbarSlot();
    }
}
