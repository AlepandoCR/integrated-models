package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputCapability;

import java.util.Set;

/**
 * How input gets observed. The seam with two implementations.
 *
 * <h2>Why a seam, when one of the two is always present</h2>
 * Not for choice — a server runs both when it can. The seam exists because the two implementations
 * cannot live in the same module and cannot be built the same way:
 *
 * <ul>
 *   <li>{@link PlatformInputCaptureSource} needs nothing but {@code paper-api}. It is always present,
 *       it is correct on a regionised server with no argument needed, and it is what makes the input
 *       layer work on a build that left the server-internals module out.</li>
 *   <li>the packet source is compiled against Mojang-mapped server internals, which this repository
 *       isolates in its own module for build reasons documented on
 *       {@link pandastic.studio.models.internal.nms.NmsBridge}. It is reached through
 *       {@link pandastic.studio.models.internal.nms.InputCaptureBridge} and wrapped by
 *       {@link BridgeInputCaptureSource}.</li>
 * </ul>
 *
 * <p>So the seam is what lets the whole input layer, including the dispatcher and every combination,
 * be compiled and tested against plain {@code paper-api} — and lets "the packet source is absent" be a
 * runtime state the layer is written against rather than a build failure.
 *
 * <h2>Capabilities are declared, not discovered</h2>
 * {@link #capabilities()} is read at enable and published through
 * {@link pandastic.studio.models.api.input.PlayerInputController#capabilities()}, which is what lets a
 * consumer be told at registration that the suppression it asked for is not available here. A source
 * that over-declares produces exactly the silent failure the whole arrangement exists to prevent, so
 * the rule is: declare a capability only if every delivery of that kind can actually do it.
 */
public interface InputCaptureSource {

    /**
     * A short name for diagnostics.
     *
     * <p>It ends up in the one sentence an operator reads about why their input capture is partial, so
     * it has to name the source rather than the class.
     *
     * @return the name, never blank
     */
    @NotNull String name();

    /**
     * What this source can observe and suppress.
     *
     * <p>Fixed for the life of the source: it is a statement about what it is, not about what the
     * configuration currently allows. A source the operator has switched off is not started at all.
     *
     * @return an unmodifiable set
     */
    @NotNull Set<InputCapability> capabilities();

    /**
     * Starts observing, pushing everything into {@code sink}.
     *
     * <p>Called once, at enable, from the plugin's enable thread. A source that has to register a
     * listener or allocate anything does it here rather than in its constructor, so that an assembled
     * but unstarted runtime has nothing to unwind.
     *
     * @param sink where observations go
     */
    void start(@NotNull InputSink sink);

    /**
     * Stops observing.
     *
     * <p>Must be idempotent and must not throw. Called as part of plugin shutdown, which happens
     * before the platform's own cleanup and before the classloader closes — so a source that left a
     * handler installed would fail on the first lazy class load after that, in a stack trace pointing
     * nowhere near here.
     */
    void stop();

    /**
     * Installs anything this source needs per player.
     *
     * <p>A no-op for the platform source, which listens server-wide. The packet source needs it: a
     * handler goes into that player's own network pipeline, and there is nowhere to put it before the
     * player exists.
     *
     * @param player the player who just became observable
     */
    default void attach(@NotNull Player player) {
        // Nothing per-player by default; see the packet source for the case that needs it.
    }

    /**
     * Removes whatever {@link #attach(Player)} installed.
     *
     * @param player the player who is leaving
     */
    default void detach(@NotNull Player player) {
        // Nothing per-player by default.
    }
}
