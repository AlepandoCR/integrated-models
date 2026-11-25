package pandastic.studio.models.api.input;

/**
 * The two kinds of player input, split by how the client sends them.
 *
 * <h2>Why the split is in the type system rather than in a comment</h2>
 * Because the two families are observable by different means, with different guarantees, and a
 * consumer binding a handler has to be told which one it is asking for. The movement family is
 * <em>retained</em> state the client re-sends every client tick and the server keeps on the player, so
 * the honest question about it is "is this key down". The action family is <em>momentary</em>: each one
 * arrives on its own packet when it happens and nothing retains it, so the honest question is "did this
 * just happen".
 *
 * <p>A capture source may be able to see one family and not the other, and may be able to suppress one
 * and not the other. {@link InputCapability} is declared per family for exactly that reason, and
 * {@link PlayerInputController#canObserve(InputFamily)} is the question a consumer asks before it
 * relies on an answer.
 *
 * @see InputKey
 * @see InputCapability
 */
public enum InputFamily {

    /**
     * The seven held movement keys: forward, backward, left, right, jump, sneak and sprint.
     *
     * <p>Reachable without server internals: the client sends them as one bitfield once per client
     * tick, the server retains the latest value on the player, and the platform publishes both a
     * change notification and an accessor for the retained value. Every member is
     * {@link InputKind#HELD}.
     *
     * <p>What the platform does <strong>not</strong> offer for this family is cancellation — the change
     * notification is not a cancellable event — so suppressing a movement input needs a source that can
     * reach the packet.
     */
    MOVEMENT,

    /**
     * The momentary action inputs: attack, use, drop, swap hands and hotbar slot selection.
     *
     * <p>None of them is part of the movement bitfield; each arrives on its own packet. Every member is
     * {@link InputKind#PULSE} — true for exactly one published snapshot and then gone, because nothing
     * anywhere retains "the player dropped an item a moment ago".
     *
     * <p>Unlike the movement family, the platform's own notifications for these <em>are</em>
     * cancellable, so an event-based source can suppress them. That asymmetry is the whole reason
     * {@link InputCapability} has four observe/suppress combinations rather than two.
     */
    ACTION
}
