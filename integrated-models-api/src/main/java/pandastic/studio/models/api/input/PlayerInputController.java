package pandastic.studio.models.api.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every input a player gives, mapped, published and bindable.
 *
 * <h2>The two ways to use it, and which one a vehicle wants</h2>
 * <strong>Polling</strong> — {@link #snapshot(Player)} — is the one a vehicle, a model or anything else
 * with its own scheduled step should use. The capture layer publishes an immutable
 * {@link InputSnapshot} and a step reads it <em>once</em>, on its own thread, at its own rate. That is
 * the only arrangement that is correct on a regionised server: an input arrives on the player's region
 * thread or on a network thread, neither of which may touch the thing being driven, so the value has to
 * cross as a value. It is the same hand-over the pose pipeline's driver sample makes, for the same
 * reason.
 *
 * <p><strong>Binding</strong> — {@link #bind} — is for the things polling cannot express: a moment
 * rather than a state, and a condition over several inputs at once. A double tap, a chord, a hold
 * threshold, "use while sneaking but not while sprinting". The handler runs on whichever thread
 * observed the input, which is why {@link InputEvent} carries {@link InputDispatch} and a way across to
 * the player's own region.
 *
 * <p>The two are not alternatives. A vehicle's steering polls the snapshot every control step and binds
 * a handful of combinations for the gear changes and the horn — and both see the same published value,
 * so they cannot disagree about what the rider did.
 *
 * <h2>Read the snapshot once per step</h2>
 * Not once per question. Two reads inside one step can straddle a publication, which gives one decision
 * two different inputs. Hold it, ask it everything, finish the step. {@link InputSnapshot#sequence()}
 * exists so that a step can tell whether anything has arrived since the last one without reading twice.
 *
 * <h2>What this server can do is a question, not an assumption</h2>
 * Capture sits behind a seam with two implementations and the second one needs a server-internals
 * adapter for the running Minecraft version. Where there is none — including a build that deliberately
 * left the adapter module out — movement and action observation and action suppression all keep
 * working, movement suppression and early observation do not, and {@link #unavailableReason()} says so
 * in one sentence. {@link #capabilities()} is the machine-readable form and
 * {@link #bind} applies it to a specific binding, which is where a consumer actually needs it.
 *
 * @see InputCombination
 * @see InputCapability
 */
public interface PlayerInputController {

    /**
     * What this server's capture layer can do.
     *
     * <p>Fixed for the life of the plugin: capture sources are resolved at enable, so this does not
     * change under a configuration reload and a consumer may read it once. What a reload <em>can</em>
     * change is {@link #tuning()}.
     *
     * @return an unmodifiable set, never empty — the platform source is always present
     */
    @NotNull Set<InputCapability> capabilities();

    /**
     * Whether a key of {@code family} can be seen at all on this server.
     *
     * @param family the family
     * @return whether its inputs reach this controller
     */
    default boolean canObserve(@NotNull InputFamily family) {
        return capabilities().contains(InputCapability.observing(family));
    }

    /**
     * Whether a key of {@code family} can be stopped from taking effect.
     *
     * <p>Ask this before writing a control scheme that depends on it. A binding that declares
     * {@link InputResponse#SUPPRESS} without the capability is told at registration rather than
     * silently ignored, but a consumer that can degrade gracefully would rather know first.
     *
     * @param family the family
     * @return whether {@link InputEvent#suppress()} can succeed for that family
     */
    default boolean canSuppress(@NotNull InputFamily family) {
        return capabilities().contains(InputCapability.suppressing(family));
    }

    /**
     * Why this server's input capture is less than complete, if it is.
     *
     * <p>Empty when every capability is present. An {@link Optional} rather than a thrown exception or
     * a boolean, for the reason the AI service's own availability is one: a reduced feature set is a
     * supported state of this plugin, and the thing a consumer or an operator needs is the sentence
     * explaining which half is missing and why — the Minecraft version, or an adapter left out of the
     * build.
     *
     * @return the reason, or empty when nothing is missing
     */
    @NotNull Optional<String> unavailableReason();

    /**
     * The durations an unqualified hold or double tap is judged against.
     *
     * <p>Operator configuration, so it changes on reload. A consumer that wants to show a player "hold
     * sneak for 0.5s" should read it rather than assume it, and should read it when it draws rather
     * than cache it.
     *
     * @return the tuning in effect
     */
    @NotNull InputTuning tuning();

    /**
     * What {@code player} is asking for right now.
     *
     * <p>Never {@code null}: a player who has not been observed, or whose last observation is older
     * than the configured staleness window, reads as {@link InputSnapshot#IDLE}. The staleness part is
     * not a detail — a client that disconnects mid-key never sends the release, so the last state the
     * server saw has that key held forever, and a vehicle reading it would drive away with nobody on
     * it.
     *
     * <p>Safe from any thread and cheap: one volatile read and a comparison.
     *
     * @param player whose input to read
     * @return the published snapshot, or {@link InputSnapshot#IDLE}
     */
    @NotNull InputSnapshot snapshot(@NotNull Player player);

    /**
     * The same thing by identity, for a caller holding a {@link UUID} rather than a {@link Player}.
     *
     * <p>Worth having separately because it is the form that is safe to call about a player the caller
     * may not touch: resolving a {@link UUID} to a {@link Player} is a server lookup, while this is a
     * map read.
     *
     * @param playerId whose input to read
     * @return the published snapshot, or {@link InputSnapshot#IDLE}
     */
    @NotNull InputSnapshot snapshot(@NotNull UUID playerId);

    /** The flags alone. Convenience over {@link #snapshot(Player)} for a caller with no history to ask about. */
    default @NotNull InputState state(@NotNull Player player) {
        return snapshot(player).state();
    }

    /**
     * Whether {@code player} is holding {@code key}.
     *
     * <p>A convenience, and one to use carefully: asking it twice in one step is the two-read hazard
     * this type's notes warn about. A step that asks more than one question should hold a snapshot.
     *
     * @param player whose input to read
     * @param key    the key to test
     * @return whether it is down as of the published snapshot
     */
    default boolean isDown(@NotNull Player player, @NotNull InputKey key) {
        return snapshot(player).isDown(key);
    }

    /**
     * Binds {@code handler} to {@code combination} for every player.
     *
     * <p>Observing only. The overload taking an {@link InputResponse} is the one that may suppress.
     *
     * @param combination what to match
     * @param handler     what to run
     * @return the registration, carrying anything worth saying about it
     */
    default @NotNull InputRegistration bind(
            @NotNull InputCombination combination,
            @NotNull InputHandler handler
    ) {
        return bind(combination, InputResponse.OBSERVE, handler);
    }

    /**
     * Binds {@code handler} to {@code combination} for every player, declaring what it may do.
     *
     * @param combination what to match
     * @param response    whether the handler may ask for the input to be stopped
     * @param handler     what to run
     * @return the registration, carrying anything worth saying about it
     */
    @NotNull InputRegistration bind(
            @NotNull InputCombination combination,
            @NotNull InputResponse response,
            @NotNull InputHandler handler
    );

    /**
     * Binds {@code handler} to {@code combination} for one player only.
     *
     * <p>Scoped by identity rather than by reference, so it survives the player reconnecting into a new
     * {@link Player} instance — which is the behaviour a vehicle wants, since a rider who rejoins is
     * still the rider.
     *
     * <p>A player-scoped binding and a server-wide one are only reported as shadowing each other when
     * they could both fire for the same player; two bindings scoped to different players never are.
     *
     * @param playerId    whose input to match
     * @param combination what to match
     * @param response    whether the handler may ask for the input to be stopped
     * @param handler     what to run
     * @return the registration, carrying anything worth saying about it
     */
    @NotNull InputRegistration bind(
            @NotNull UUID playerId,
            @NotNull InputCombination combination,
            @NotNull InputResponse response,
            @NotNull InputHandler handler
    );

    /**
     * Every finding across every live binding, recomputed now.
     *
     * <p>What {@link #bind} reports is one binding against the ones that existed when it was
     * registered. This is the whole registry against itself, which is the only way to see a shadow that
     * appeared because a <em>later</em> binding was more general than an earlier one — the order a
     * plugin registers in is not the order a reader would have chosen.
     *
     * <p>Allocates and compares every pair, so it is for a diagnostic command or a consumer's own
     * start-up check rather than for a step.
     *
     * @return an unmodifiable list, empty when nothing is worth saying
     */
    @NotNull List<InputIssue> audit();

    /**
     * How many bindings are live.
     *
     * @return the count, which the configured limit bounds
     */
    int bindingCount();
}
