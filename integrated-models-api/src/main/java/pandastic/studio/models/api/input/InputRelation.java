package pandastic.studio.models.api.input;

/**
 * How two combinations stand to each other, as far as their key requirements can say.
 *
 * <h2>What this is for</h2>
 * A consumer binding "sneak" and "sneak + use" has written something that will surprise it: every
 * sneak-and-use will also fire the sneak handler, because sneak is held in both. That is sometimes
 * exactly what was wanted — a modifier layered on a base action — and sometimes a bug, and nothing in
 * the plugin can tell which. What it can do is say so at registration instead of letting the consumer
 * discover it from a player's complaint. {@link InputCombination#relationTo(InputCombination)} answers
 * this and {@link PlayerInputController#bind} reports it as an {@link InputIssue}.
 *
 * <h2>Conservative on purpose</h2>
 * The relation is computed from the keys a combination <em>necessarily</em> requires and
 * <em>necessarily</em> forbids, which is all that is decidable without evaluating both against every
 * possible history. For combinations that are pure conjunctions of held and not-held keys that is
 * exact. For the temporal and disjunctive forms — a press, a hold, a double tap, an {@code anyOf} — it
 * is a necessary condition only, so the answer errs towards {@link #OVERLAPS}: it may report that two
 * combinations can both fire when the timing in fact keeps them apart. Erring the other way would mean
 * staying silent about a genuine shadow, which is the failure this type exists to prevent.
 */
public enum InputRelation {

    /**
     * The two cannot both match, because one requires a key the other forbids.
     *
     * <p>The clean case: "sneak + use" and "use without sneak" are two bindings that never collide, and
     * a consumer writing a modifier pair should be seeing this.
     */
    INDEPENDENT,

    /**
     * The two have the same requirements.
     *
     * <p>Either they are equal, or they are two conjunctions over the same keys. Both handlers fire on
     * every match, in registration order — nothing is dropped and nothing wins — which is reportable
     * precisely because a consumer that wrote the second one by accident has no other way to find out.
     */
    EQUIVALENT,

    /**
     * This combination fires whenever the other does, and sometimes when it does not.
     *
     * <p>"Sneak" shadows "sneak + use". The weaker condition is the shadowing one.
     */
    SHADOWS,

    /**
     * The other combination fires whenever this one does.
     *
     * <p>The mirror of {@link #SHADOWS}, reported so a registration can say which side of the relation
     * the <em>new</em> binding is on without the caller re-deriving it.
     */
    SHADOWED_BY,

    /**
     * They can both match and neither contains the other.
     *
     * <p>Also what is reported whenever the shapes involved make containment undecidable; see the type
     * note. Normal, common, and not on its own worth a diagnostic — which is why
     * {@link InputIssue.Kind} has no entry for it.
     */
    OVERLAPS
}
