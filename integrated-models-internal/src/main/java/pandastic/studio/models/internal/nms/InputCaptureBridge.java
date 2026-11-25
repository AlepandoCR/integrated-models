package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Reading — and refusing — a client's movement input at the packet, which is the only place it can be
 * refused.
 *
 * <h2>What this seam is for, given the platform already publishes the input</h2>
 * Not for the data. The client's movement packet carries exactly seven bits and the platform retains
 * all seven and publishes a change notification for them, so interception buys nothing in content.
 * What it buys is two things nothing above it can get:
 *
 * <ol>
 *   <li><strong>Suppression.</strong> The platform's input notification is not cancellable. Without a
 *       packet handler the strongest a plugin can do about a held key is counteract it after the fact,
 *       which the player feels as a fight with the server rather than as a disabled control. Dropping
 *       the packet is the only way an input does not happen.</li>
 *   <li><strong>Earliness.</strong> The handler runs on the network thread before the server's game
 *       packet listener applies the input, so a decision made here is a veto rather than a reaction.
 *       </li>
 * </ol>
 *
 * <h2>Absence is a supported state</h2>
 * Every method below is a declining default, so an empty implementation is the complete "there is no
 * adapter" answer rather than a stub with holes in it. A server with no adapter — the wrong Minecraft
 * version, or a build that passed {@code -PintegratedModels.nms=false} — keeps the whole movement and
 * action half of the input layer, loses suppression of movement and early observation, and says so
 * once. The rendering pipeline does not need this at all.
 *
 * <h2>Why {@link #describe()} and not an exception</h2>
 * Because the three reasons there is no packet capture look identical from above and need three
 * different operator responses: the module was left out, the adapter is for another version, or the
 * operator switched it off. The sentence is the part the operator can act on, and it has to come from
 * the side that knows.
 *
 * <h2>Nothing here is tunable</h2>
 * The switch that decides whether to install this at all is a {@code @Configurable} key read
 * <em>above</em> the seam. An implementation of this interface that read configuration would have the
 * dependency backwards.
 */
public interface InputCaptureBridge {

    /**
     * Whether this bridge can actually install a packet handler.
     *
     * <p>A separate question from "is there an {@link NmsBridge}": an adapter can be present, matched
     * to the running version, and still unable to reach the connection — a server fork that moved or
     * renamed the field the handler has to be installed on. An adapter that cannot do it must say so
     * here rather than fail per player.
     *
     * @return whether {@link #attach} can be expected to succeed
     */
    default boolean isAvailable() {
        return false;
    }

    /**
     * One sentence naming what is missing and why.
     *
     * <p>Only meaningful when {@link #isAvailable()} is {@code false}. Named so the caller can put it
     * straight into the operator-facing line about reduced capability.
     *
     * @return the reason, never blank
     */
    default @NotNull String describe() {
        return "no server-internals adapter is installed, so a movement input cannot be suppressed"
                + " and cannot be observed before the server applies it";
    }

    /**
     * Installs a handler on {@code player}'s connection.
     *
     * <p>Idempotent per player: installing twice must not produce two handlers, because a plugin reload
     * and a player relogging can both reach this for a player who is already attached.
     *
     * <p>Called from the thread that owns {@code player} — a join handler's thread — because reaching
     * the connection goes through the player.
     *
     * @param player   whose connection to install on
     * @param listener what to tell about each movement input, on the network thread
     * @return whether a handler is now installed
     */
    default boolean attach(@NotNull Player player, @NotNull PacketInputListener listener) {
        return false;
    }

    /**
     * Removes the handler from {@code player}'s connection.
     *
     * <p>Must not throw, including for a player who was never attached or whose connection is already
     * gone — which is the ordinary case, since this is called from a quit handler.
     *
     * @param player whose connection to clean up
     */
    default void detach(@NotNull Player player) {
        // Nothing was installed.
    }
}
