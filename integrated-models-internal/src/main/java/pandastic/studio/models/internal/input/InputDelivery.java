package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputDispatch;
import pandastic.studio.models.api.input.InputEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The context one observation arrives in: who, when, on which thread, and whether it can be stopped.
 *
 * <h2>Why a record and not four parameters</h2>
 * Because all four travel together through every method of {@link InputSink}, and because three of
 * them are properties of the <em>capture source</em> rather than of the input. Bundling them is what
 * lets a source be written as "build my delivery once, push whatever I saw" instead of repeating its
 * own thread and its own suppression ability at every call site, which is exactly the kind of
 * repetition that ends with one call site saying it is on a region thread when it is not.
 *
 * <h2>Suppression lives here because it is per-delivery, not per-source</h2>
 * The platform source can stop an action input, because the platform's action notifications are
 * cancellable, and cannot stop a movement input, because its movement notification is not. So "can
 * this be suppressed" is not a property the source can answer once — it depends on which input this
 * is. The source supplies the suppressor it actually has for the observation in hand, and
 * {@link InputEvent#UNSUPPORTED} where it has none.
 *
 * @param player     whose input this is
 * @param nowNanos   the monotonic time of the observation, taken by the source at the moment it saw it
 *                   rather than by the sink, so a queueing delay does not read as a longer hold
 * @param dispatch   which thread the sink and therefore every handler is on
 * @param suppressor how to stop this input, answering whether the stop took effect;
 *                   {@link InputEvent#UNSUPPORTED} when it cannot be stopped
 */
public record InputDelivery(
        @NotNull Player player,
        long nowNanos,
        @NotNull InputDispatch dispatch,
        @NotNull BooleanSupplier suppressor
) {

    public InputDelivery {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(suppressor, "suppressor");
    }

    /**
     * An observation that cannot be stopped.
     *
     * @param player   whose input
     * @param nowNanos when it was seen
     * @param dispatch which thread
     * @return the delivery
     */
    public static @NotNull InputDelivery observing(
            @NotNull Player player,
            long nowNanos,
            @NotNull InputDispatch dispatch
    ) {
        return new InputDelivery(player, nowNanos, dispatch, InputEvent.UNSUPPORTED);
    }

    /**
     * Whose input this is, by identity.
     *
     * <p>Safe from any thread, unlike almost everything else on a {@link Player}: the identity is
     * immutable and does not involve reading the entity.
     *
     * @return the player's unique id
     */
    public @NotNull UUID playerId() {
        return this.player.getUniqueId();
    }
}
