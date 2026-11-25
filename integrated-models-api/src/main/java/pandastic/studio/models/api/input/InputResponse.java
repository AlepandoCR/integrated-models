package pandastic.studio.models.api.input;

/**
 * What a binding intends to do about the input it matches.
 *
 * <h2>Why it is declared at registration rather than decided in the handler</h2>
 * Because suppression is a capability of the capture source, and a consumer has to be told whether it
 * is available <em>before</em> it has written a control scheme that depends on it. A handler that calls
 * a {@code suppress()} it cannot have learns at the worst possible moment: on a region thread, inside a
 * player's input, with nothing to report to. Declaring the intent means
 * {@link PlayerInputController#bind} can answer with {@link InputIssue.Kind#SUPPRESSION_UNSUPPORTED}
 * while the consumer is still in its own enable method.
 *
 * <p>Declaring it is not the same as doing it. A {@link #SUPPRESS} binding still has to call
 * {@link InputEvent#suppress()} on the deliveries it actually wants stopped — some do and some do not,
 * which is why the flag is about what the binding may ask for rather than about what it always asks
 * for.
 */
public enum InputResponse {

    /**
     * The handler only watches.
     *
     * <p>{@link InputEvent#suppress()} on such a delivery is refused rather than honoured, so a binding
     * cannot acquire by accident an effect it did not declare.
     */
    OBSERVE,

    /**
     * The handler may ask for the input to be stopped.
     *
     * <p>Registering this on a source that cannot suppress the family in question is reported, and the
     * registration still succeeds: a vehicle whose "handbrake" cannot stop the key is still better than
     * no vehicle, and the consumer now knows which half it is getting.
     */
    SUPPRESS
}
