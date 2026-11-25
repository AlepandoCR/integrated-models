package pandastic.studio.models.api.input;

/**
 * Whether an input has a duration or only a moment.
 *
 * <h2>Why this is not derivable from the family</h2>
 * Today it is — every {@link InputFamily#MOVEMENT} key is {@link #HELD} and every
 * {@link InputFamily#ACTION} key is {@link #PULSE} — and it is still declared separately, because the
 * two statements are about different things. The family says where an input comes from and therefore
 * who can see it; the kind says how long it is true for and therefore which combination forms mean
 * anything against it. A reader asking "can I write {@code hold(key, 500ms)} for this key" is asking
 * the second question, and answering it from the first would be a coincidence that a later key could
 * break silently.
 *
 * @see InputKey#kind()
 */
public enum InputKind {

    /**
     * The input is a state with a duration: it goes down, stays down across any number of snapshots,
     * and comes up.
     *
     * <p>{@link InputTrace#heldNanos(InputKey)} is meaningful, so {@link InputCombination#hold} and a
     * chord of several keys both mean what they look like. {@link InputTrace#justPressed(InputKey)} is
     * the edge and is strictly rarer than {@link InputTrace#isDown(InputKey)}.
     */
    HELD,

    /**
     * The input is a single moment: it is true for exactly one published snapshot.
     *
     * <p>So {@link InputTrace#isDown(InputKey)} and {@link InputTrace#justPressed(InputKey)} are the
     * same question, {@link InputTrace#heldNanos(InputKey)} is never more than the time between two
     * publications, and a {@link InputCombination#hold} on a pulse key can never match. A chord
     * combining a pulse with held keys is the useful form — "use while sneaking" — because the pulse is
     * what dates the match and the held keys are the context it happened in.
     *
     * <p>A player holding the attack button produces a <em>repeated</em> pulse rather than a held
     * state, because that is what the client sends: there is no single packet that says "attack is
     * still down". A consumer that wants "is mining" should treat the repeat as the signal rather than
     * expect a duration.
     */
    PULSE
}
