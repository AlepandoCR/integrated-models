package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Everything the wardrobe needs from a model that is already in the world.
 *
 * <h2>Why this is not on {@link pandastic.studio.models.api.binding.RiggedModel}</h2>
 * Because a handle is not the authority on what a player is wearing, and three of the five operations
 * below are not expressible through one.
 *
 * <ul>
 *   <li>A {@code RiggedModel} answers {@link pandastic.studio.models.api.binding.RiggedModel#getRig()},
 *       its identity, and whether it is alive. It has no appearance, no animation and no way to ask the
 *       renderer what it is driving — the four methods it does have are the four it has — so colour,
 *       scale and animation have nowhere to go through it.</li>
 *   <li>The renderer keeps its own registry of what is on an entity, keyed by the entity and persisted in
 *       the entity's own data. A player who relogs while dressed therefore comes back wearing a model
 *       that <strong>no handle of ours exists for</strong>. {@link #wornModel} is the only thing that can
 *       see that, and {@link #strip} the only thing that can take it off.</li>
 * </ul>
 *
 * <p>So this is a seam over the renderer rather than a way around the binding layer: the handle owns the
 * rig, the simulation and the lifetime, and this owns the questions the handle has no answer for.
 *
 * <h2>Everything is addressed by model id</h2>
 * Not by handle and not by tracker, because the registry is keyed by model id and because that is the one
 * name the menus already have. Every method therefore answers {@code false} — never throws — when the
 * named model is not on that player any more: a click can arrive for a state that has already moved on,
 * which is the idempotence {@link ModelWardrobe} promises and this is where it is paid for.
 *
 * <h2>Threading</h2>
 * Every method is called on the thread that owns {@code viewer}; see {@link ModelWardrobe} for why that
 * is the whole contract on Folia. Nothing here schedules anything of its own.
 */
public interface WardrobeRendering {

    /**
     * The model the renderer currently has on {@code viewer}.
     *
     * <p>The renderer's answer rather than ours, which is what makes it worth asking: it is what the
     * player can actually see, it survives a relog that our own handles do not, and it is also how a
     * model put on by something else entirely becomes visible to the menus.
     *
     * @param viewer the player
     * @return the model id, or empty when the renderer has nothing on them
     */
    @NotNull Optional<String> wornModel(@NotNull Player viewer);

    /**
     * Takes {@code modelId} off {@code viewer} at the renderer level.
     *
     * <p>The path for a model nothing of ours holds a handle for. A model we did bind is released through
     * its handle instead, which closes the same tracker — calling this afterwards is a no-op rather than
     * a second removal.
     *
     * @param viewer  the player
     * @param modelId the model to remove
     * @return {@code true} when something was removed
     */
    boolean strip(@NotNull Player viewer, @NotNull String modelId);

    /**
     * Applies {@code appearance} to the named model on {@code viewer}.
     *
     * @param viewer     the player
     * @param modelId    the model to decorate
     * @param appearance the already-clamped state to apply
     * @return {@code true} when the model was there to be decorated
     */
    boolean decorate(
            @NotNull Player viewer,
            @NotNull String modelId,
            @NotNull ModelAppearance appearance
    );

    /**
     * Starts {@code animationId} on the named model on {@code viewer}.
     *
     * @param viewer      the player
     * @param modelId     the model to animate
     * @param animationId the animation to start
     * @return {@code true} when the animation started. {@code false} covers both "no such model on this
     *         player" and "that model declares no such animation", and the caller treats them the same:
     *         nothing is remembered as playing, so a later Stop does not claim to have stopped it
     */
    boolean play(@NotNull Player viewer, @NotNull String modelId, @NotNull String animationId);

    /**
     * Stops {@code animationId} on the named model on {@code viewer}.
     *
     * <p>The animation has to be named because the renderer offers no "stop whatever is playing": an
     * animation is stopped by name. Remembering which name that was is consequently the caller's job.
     *
     * @param viewer      the player
     * @param modelId     the model to stop it on
     * @param animationId the animation to stop
     * @return {@code true} when it was playing and is not any more
     */
    boolean stop(@NotNull Player viewer, @NotNull String modelId, @NotNull String animationId);

    /**
     * The production rendering: BetterModel's own tracker registry.
     *
     * <p>A factory rather than a constructor so that the one class in this package holding the renderer's
     * tracker imports is package-private and is never named, and therefore never loaded, by anything that
     * only wants the seam.
     *
     * @return the rendering over the installed renderer
     */
    static @NotNull WardrobeRendering trackers() {
        return new TrackerWardrobeRendering();
    }
}
