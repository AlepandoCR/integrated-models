package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * What the packet-level input capture calls when it sees a client's movement input.
 *
 * <h2>Why this is the whole seam</h2>
 * Because the packet path buys exactly two things over the platform's own input notification, and both
 * fit in one method with a boolean return: it is called <strong>before the server has acted</strong>,
 * and what it answers decides whether the server acts at all. Everything else about the input — the
 * seven flags — is identical to what the platform publishes, so widening this seam would only move
 * code below the line where it cannot be tested.
 *
 * <h2>Threading, and the thing an implementation must not do</h2>
 * Called on the <strong>network thread</strong> of that player's connection. It owns nothing: not the
 * player, not the player's region, not any entity. An implementation may record the input and may
 * decide, and it may not read a position, a passenger list or anything else about the world. Blocking
 * here stalls that player's connection.
 *
 * <p>Which is why the implementation on the other side of this seam does nothing but fold the bits into
 * an immutable published value and run bindings that were told, through
 * {@link pandastic.studio.models.api.input.InputDispatch}, which thread they are on.
 */
@FunctionalInterface
public interface PacketInputListener {

    /**
     * The client's seven movement flags, seen before the server has applied them.
     *
     * @param player       whose connection the packet arrived on. Safe to name; not safe to read
     * @param movementBits the flags, in {@link pandastic.studio.models.api.input.InputKey}'s layout —
     *                     which is also the client's own layout for these seven bits, so the adapter
     *                     packs them with no table
     * @return {@code true} to drop the packet, so the server never sees this input at all
     */
    boolean onMovementInput(@NotNull Player player, int movementBits);
}
