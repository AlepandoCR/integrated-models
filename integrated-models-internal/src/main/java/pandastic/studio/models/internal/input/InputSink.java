package pandastic.studio.models.internal.input;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputKey;

import java.util.UUID;

/**
 * Where a capture source pushes what it saw.
 *
 * <h2>Why the answer is a boolean</h2>
 * Because suppression has to flow back. A source that can stop an input — the packet one — needs to
 * know whether any handler asked it to, and it needs to know synchronously, before it decides what to
 * do with the packet it is holding. One call that records the input, dispatches the bindings and
 * answers "somebody suppressed this" keeps that decision in one place; a source that had to ask
 * afterwards would be asking about a dispatch that had already finished.
 *
 * <p>A source with nothing to suppress with ignores the answer, which is the ordinary case.
 *
 * <h2>Threading</h2>
 * Called from wherever the observation happened — a region thread or a network thread — and the
 * delivery says which. An implementation therefore has to be safe for both at once, and must not
 * block: a network thread blocked here is that player's connection stalled, and a region thread
 * blocked here is a tick stalled.
 */
public interface InputSink {

    /**
     * The seven held movement bits, as of now.
     *
     * <p>Idempotent in effect: pushing the same bits twice publishes once, because the second push
     * finds the state unchanged. Which matters, because on a server with both capture sources running
     * every movement change is seen twice.
     *
     * @param delivery     who, when, which thread, and how to stop it
     * @param movementBits the held bits, in {@link InputKey}'s layout
     * @return whether a handler asked for this input to be stopped
     */
    boolean movement(@NotNull InputDelivery delivery, int movementBits);

    /**
     * One momentary input.
     *
     * @param delivery who, when, which thread, and how to stop it
     * @param key      the momentary key; must belong to
     *                 {@link pandastic.studio.models.api.input.InputFamily#ACTION}
     * @return whether a handler asked for this input to be stopped
     */
    boolean pulse(@NotNull InputDelivery delivery, @NotNull InputKey key);

    /**
     * A change of selected hotbar slot.
     *
     * <p>Separate from {@link #pulse} because it carries a value as well as a moment, and both halves
     * have to land in the same publication — a handler that reads the new slot off the snapshot must
     * not see the pulse before the number.
     *
     * @param delivery who, when, which thread, and how to stop it
     * @param slot     the newly selected slot
     * @return whether a handler asked for this input to be stopped
     */
    boolean hotbarSlot(@NotNull InputDelivery delivery, int slot);

    /**
     * Forgets a player.
     *
     * <p>Takes a {@link UUID} rather than a {@link org.bukkit.entity.Player} because the caller is a
     * quit handler, and by the time some of those run the entity is no longer something to hold on to.
     *
     * @param playerId whose state to drop
     */
    void forget(@NotNull UUID playerId);
}
