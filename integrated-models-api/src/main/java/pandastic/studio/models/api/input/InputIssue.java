package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Something worth saying about a binding, said at registration.
 *
 * <h2>Why registration returns findings instead of just working</h2>
 * Four of the five things that can be wrong with an input binding are invisible at runtime: a handler
 * that never fires, a handler that fires on every match of another one, a suppression that is silently
 * ignored, and an input family nothing on this server can see. None of them throws, none of them logs
 * on its own, and all of them look to the consumer like "my combination does not work". So
 * {@link PlayerInputController#bind} hands back a list of them and a consumer logs what it chooses to
 * — which is also the only arrangement that lets a consumer <em>accept</em> an overlap on purpose,
 * because a modifier layered on a base action is a legitimate design and a hard refusal would forbid
 * it.
 *
 * <p>Two of the kinds refuse the binding outright; see {@link #isRefusal()}. The rest are advisory and
 * the registration is live.
 *
 * @param kind    what sort of finding this is
 * @param message one sentence, already naming both combinations where two are involved
 * @param other   the combination this finding is about the relationship to, or {@code null} when the
 *                finding is about the new binding alone
 */
public record InputIssue(
        @NotNull Kind kind,
        @NotNull String message,
        @Nullable InputCombination other
) {

    public InputIssue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /**
     * A finding about the new binding alone.
     *
     * @param kind    what sort of finding
     * @param message the sentence
     * @return the issue
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull InputIssue of(@NotNull Kind kind, @NotNull String message) {
        return new InputIssue(kind, message, null);
    }

    /** Whether this finding means the binding was not registered. */
    @Contract(pure = true)
    public boolean isRefusal() {
        return this.kind.refuses;
    }

    /** What sort of finding an {@link InputIssue} is. */
    public enum Kind {

        /**
         * No input can ever satisfy the combination.
         *
         * <p>Either it requires and forbids the same key, or it asks for a hold on a momentary input.
         * Refused, because the alternative is a handler that is guaranteed never to run and a consumer
         * with nothing to look at.
         */
        UNSATISFIABLE(true),

        /**
         * The controller is already holding as many bindings as it is configured to.
         *
         * <p>Refused. The limit exists because every binding is evaluated against every publication for
         * every player it is scoped to, so an unbounded registry is a per-client-tick cost a consumer
         * can impose on a server without meaning to.
         */
        LIMIT_REACHED(true),

        /**
         * Another binding has the same requirements.
         *
         * <p>Both fire, in registration order; nothing is dropped and nothing wins. Reported because a
         * consumer that registered the same combination twice — commonly by calling its own setup
         * method twice — has no other way to see it.
         */
        EQUIVALENT(false),

        /**
         * This binding fires whenever an existing one does.
         *
         * <p>Its requirements are weaker. "Sneak" over an existing "sneak + use".
         */
        SHADOWS(false),

        /**
         * An existing binding fires whenever this one does.
         *
         * <p>The mirror of {@link #SHADOWS}, and the more common accident: the general binding was
         * registered first and the specific one looks broken because both handlers run.
         */
        SHADOWED_BY(false),

        /**
         * The binding declared {@link InputResponse#SUPPRESS} for a family this server cannot suppress.
         *
         * <p>Not refused: the binding still observes, which is most of its value, and refusing would
         * take a working half away to punish a missing one. See {@link InputCapability} for which
         * source brings which.
         */
        SUPPRESSION_UNSUPPORTED(false),

        /**
         * The combination names a key of a family nothing on this server can observe.
         *
         * <p>Not refused, for a reason specific to this project's deployment story: capture capability
         * is resolved at enable and a server that gains the missing source — by installing the adapter
         * module, or by upgrading to a version an adapter exists for — should find the binding already
         * there rather than need the consumer to re-register. So the handler is live and simply never
         * matches until the capability arrives.
         */
        FAMILY_UNOBSERVABLE(false);

        private final boolean refuses;

        Kind(boolean refuses) {
            this.refuses = refuses;
        }

        /** Whether a finding of this kind means the binding was not registered. */
        public boolean refuses() {
            return this.refuses;
        }
    }
}
