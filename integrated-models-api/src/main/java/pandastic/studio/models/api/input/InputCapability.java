package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * What a server's input capture can actually do.
 *
 * <h2>Why a consumer has to be able to ask</h2>
 * Because the answer depends on what is installed, and because the failure mode of not knowing is
 * silence. A plugin that binds a suppressing handler to a movement key on a server whose capture layer
 * cannot suppress movement gets a handler that runs, reports success, and changes nothing — and the
 * first person to find out is a player. {@link PlayerInputController#bind} reports a missing capability
 * as an {@link InputIssue} at registration, which is the last moment at which the information is still
 * actionable.
 *
 * <h2>The matrix, which is the surprising part</h2>
 * Two capture sources, two families of input, and two things a consumer might want to do with one. The
 * combinations are <strong>not</strong> symmetrical, and the asymmetry is the platform's rather than
 * this plugin's: the platform's movement notification is not cancellable and its action notifications
 * are. Everything else in this type is a consequence of that one fact.
 *
 * <table border="1">
 *   <caption>What each source can do with each family</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link InputFamily#MOVEMENT} — the seven held keys</th>
 *     <th>{@link InputFamily#ACTION} — the five momentary inputs</th>
 *   </tr>
 *   <tr>
 *     <th>Platform source<br>(always present)</th>
 *     <td><strong>observe</strong> — {@link #OBSERVE_MOVEMENT}<br>
 *         suppress: <strong>no</strong>, the notification is not cancellable</td>
 *     <td><strong>observe</strong> — {@link #OBSERVE_ACTIONS}<br>
 *         <strong>suppress</strong> — {@link #SUPPRESS_ACTIONS}</td>
 *   </tr>
 *   <tr>
 *     <th>Packet source<br>(needs a server-internals adapter)</th>
 *     <td><strong>observe</strong>, and before the server acts —
 *         {@link #OBSERVE_BEFORE_SERVER}<br>
 *         <strong>suppress</strong> — {@link #SUPPRESS_MOVEMENT}</td>
 *     <td>nothing it adds: the platform already sees and stops these</td>
 *   </tr>
 * </table>
 *
 * <p>So on a server with no adapter the one cell that is empty is <em>stopping a movement key</em>, and
 * {@link InputResponse#SUPPRESS} on a movement combination is the one binding that is reported at
 * registration and then does less than it says. Observation of both families, and suppression of
 * actions, are always available. The three questions a consumer should actually ask are
 * {@link PlayerInputController#canObserve(InputFamily)},
 * {@link PlayerInputController#canSuppress(InputFamily)} and — once, at start-up —
 * {@link PlayerInputController#unavailableReason()}.
 *
 * <h2>The two sources, and what each brings</h2>
 * Capture sits behind a seam with two implementations, and neither is a superset of the other in every
 * respect:
 *
 * <ul>
 *   <li><strong>The platform source.</strong> Built on the platform's own input notification and its
 *       cancellable action events. It needs no server internals, is correct on a regionised server
 *       without any extra argument, and is always present. It brings
 *       {@link #OBSERVE_MOVEMENT}, {@link #OBSERVE_ACTIONS} and {@link #SUPPRESS_ACTIONS} — the action
 *       notifications are cancellable, the movement one is not.</li>
 *   <li><strong>The packet source.</strong> Reaches the client's movement packet directly. It is the
 *       only thing that can stop a movement input from being applied at all, and the only thing that
 *       sees an input before the server has acted on it. It brings {@link #SUPPRESS_MOVEMENT} and
 *       {@link #OBSERVE_BEFORE_SERVER}, and it needs a server-internals adapter for the running
 *       Minecraft version. Where there is none — including a build that left the adapter module out —
 *       those two capabilities are absent, everything else keeps working, and the reason is logged
 *       once.</li>
 * </ul>
 *
 * @see PlayerInputController#capabilities()
 */
public enum InputCapability {

    /** The seven held movement keys can be observed. */
    OBSERVE_MOVEMENT,

    /** The five momentary action inputs can be observed. */
    OBSERVE_ACTIONS,

    /**
     * A movement input can be prevented from reaching the server.
     *
     * <p>Needs the packet source: the platform's movement notification is not cancellable, so without
     * it the best a handler can do is counteract the input afterwards, which the player feels as a
     * fight rather than as a disabled key.
     */
    SUPPRESS_MOVEMENT,

    /**
     * An action input can be prevented from taking effect.
     *
     * <p>Available from the platform source, because the action notifications are cancellable.
     */
    SUPPRESS_ACTIONS,

    /**
     * An input is observed before the server has acted on it.
     *
     * <p>Not a different <em>input</em>, a different <em>moment</em>. Only the packet source has it, and
     * what it is worth is the difference between deciding before the server moves the player and
     * deciding after. Nothing in this api requires it; a consumer that wants to veto rather than react
     * does.
     */
    OBSERVE_BEFORE_SERVER;

    /**
     * The observation capability for {@code family}.
     *
     * @param family the family
     * @return {@link #OBSERVE_MOVEMENT} or {@link #OBSERVE_ACTIONS}
     */
    @Contract(pure = true)
    public static @NotNull InputCapability observing(@NotNull InputFamily family) {
        return Objects.requireNonNull(family, "family") == InputFamily.MOVEMENT
                ? OBSERVE_MOVEMENT
                : OBSERVE_ACTIONS;
    }

    /**
     * The suppression capability for {@code family}.
     *
     * @param family the family
     * @return {@link #SUPPRESS_MOVEMENT} or {@link #SUPPRESS_ACTIONS}
     */
    @Contract(pure = true)
    public static @NotNull InputCapability suppressing(@NotNull InputFamily family) {
        return Objects.requireNonNull(family, "family") == InputFamily.MOVEMENT
                ? SUPPRESS_MOVEMENT
                : SUPPRESS_ACTIONS;
    }
}
