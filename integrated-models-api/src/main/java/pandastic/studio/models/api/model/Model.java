package pandastic.studio.models.api.model;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One model this plugin put into the world: the root of the only model family there is.
 *
 * <p>Everything a consumer can hold is one of these — a
 * {@link pandastic.studio.models.api.binding.RiggedModel} on an entity, a
 * {@link pandastic.studio.models.api.decoration.Decoration} standing on its own, a
 * {@link pandastic.studio.models.api.vehicle.Vehicle} somebody rides, or an {@link EntityModel} built out
 * of components. They differ in how they are <em>created</em> and in what extra capabilities they carry;
 * they do not differ in what a model <em>is</em>, which is what this interface states.
 *
 * <h2>Why this type exists, and what it cost not to have it</h2>
 * It did not exist for a while. The component API ({@link EntityModel} and its components) and the rig
 * pipeline ({@code RiggedModel}, {@code Decoration}, {@code Vehicle}) grew as two disjoint hierarchies
 * that shared no supertype, so there was no answer to "give me everything this plugin has in the world",
 * no single meaning for "despawned", and two unrelated vocabularies for the same eight appearance
 * operations — one of them a component carrying setters, the other a flat method per setter. A plugin
 * holding a mixed collection had to know which kind each element was in order to shut it down. This
 * interface is the answer: one identity, one end of life, one notion of being spawned, one way to show a
 * model to a player, and one appearance vocabulary.
 *
 * <h2>What is deliberately not here</h2>
 * <strong>Spawning.</strong> It is the one thing the kinds genuinely disagree about: an
 * {@link EntityModel} spawns at a {@code Location} or attaches to an existing entity, a
 * {@link pandastic.studio.models.api.binding.RiggedModel} spawns with no argument on its owner's thread,
 * and a {@link pandastic.studio.models.api.vehicle.Vehicle} is already running when its binder hands it
 * back. A common {@code spawn()} would have to be a no-op on at least one of them, and a lifecycle method
 * that silently does nothing is worse than a method that is not there. The half that matters to the
 * holder of a mixed collection — {@link #despawn()} — <em>is</em> common.
 *
 * <p><strong>Ticking.</strong> {@link EntityModel} is its own
 * {@link pandastic.studio.models.api.util.schedule.TickerController} because its components are driven by
 * one task on its base entity's own scheduler. A decoration has no entity and therefore no entity
 * scheduler: it is stepped by a shared off-tick loop it does not own and cannot hand out. Promising a
 * {@code ScheduledTask} from here would be promising something most of the kinds cannot produce.
 *
 * <h2>Thread rules</h2>
 * {@link #despawn()} and {@link #close()} are safe from any thread, any number of times, on every
 * implementation — the most common caller is the platform noticing that the thing a model was attached to
 * is gone, and the second most common is a plugin shutting down. Every getter here is safe from any
 * thread. Anything that touches the world states its own rule on the subinterface that declares it.
 */
public interface Model extends AutoCloseable {

    /**
     * This model's identity, stable for its whole life including across spawn and despawn.
     *
     * <p>Never the attached entity's UUID. Two models on one entity are legal — a cloak and a tail, say —
     * so an entity's identity cannot name a model.
     *
     * @return the identifier, and the key {@link ModelManager} stores this model under
     */
    @NotNull UUID getUniqueIdentifier();

    /**
     * A human-readable name, for logs, menus and diagnostics.
     *
     * <p>Defaults to the rig's own name, which for a model bound without a rig file is the renderer's
     * model id; see {@link Rig#modelOnly(String)}.
     *
     * @return the name; never blank
     */
    default @NotNull String getName() {
        return getRig().getName();
    }

    /**
     * The rig this model drives.
     *
     * <p>Never absent, because "the renderer's authored model with none of our bones driven" is itself a
     * rig — {@link Rig#modelOnly(String)} — rather than the absence of one. That is what lets the rig sit
     * on the root: a consumer asking what a model is does not have to branch on which kind it holds.
     *
     * <p>Fixed for the model's whole life. A rig is immutable, so holding it is safe, but a configuration
     * reload replaces the loaded definitions — a model bound before a reload keeps driving the definition
     * it was bound to until it is rebound.
     *
     * @return the rig
     */
    @NotNull Rig getRig();

    /**
     * The entity this model is attached to, when it is attached to one.
     *
     * <p>Empty for a model with nothing underneath it, and empty for a kind that deliberately does not
     * expose what it is standing on — a {@link pandastic.studio.models.api.vehicle.Vehicle}'s root entity
     * is an implementation choice its own interface keeps to itself.
     *
     * <p>Attaching a model to an entity never replaces or owns it: the entity keeps its AI, its traits
     * and its metadata, and the renderer's display entities are separate things posed on top of it.
     *
     * @return the entity, or empty
     */
    default @NotNull Optional<Entity> getEntity() {
        return Optional.empty();
    }

    /**
     * Whether this model is in the world and being driven.
     *
     * <p>The one question every kind answers the same way, which is why it is the method the others are
     * written in terms of.
     *
     * @return whether it is spawned
     */
    boolean isSpawned();

    /**
     * {@link #isSpawned()}, under the name the rig pipeline has always used for it.
     *
     * <p>Kept because it reads better for the kinds that are handed back already running — "is this
     * vehicle alive" rather than "is this vehicle spawned" — and because it is the name every piece of
     * published documentation about them uses. It is an alias, not a second state: an implementation
     * overrides {@link #isSpawned()} and this follows it.
     *
     * @return whether it is spawned
     */
    default boolean isAlive() {
        return isSpawned();
    }

    /**
     * Takes this model out of the world and releases everything it holds.
     *
     * <p>Idempotent, safe from any thread, and safe on a model that never spawned. The teardown runs
     * exactly once however many callers reach it and from however many threads.
     *
     * <p>What it releases is this plugin's: trackers, tasks, loop registrations and the
     * {@link ModelManager} entry. An entity the <em>caller</em> supplied is the caller's and outlives the
     * model — except on {@link EntityModel}, which removes the entity it spawned itself; that
     * subinterface says so.
     */
    void despawn();

    /**
     * {@link #despawn()}, so any model can be managed with try-with-resources.
     *
     * <p>Declared without the checked exception {@link AutoCloseable} permits: nothing about releasing a
     * model can fail in a way a caller could handle, and a checked exception here would force a catch
     * block at every call site that does nothing with it.
     */
    @Override
    default void close() {
        despawn();
    }

    /**
     * Shows this model to one player.
     *
     * <p>Defaults to doing nothing and saying so, for the kinds whose visibility is the server's own
     * entity tracking rather than this plugin's — a
     * {@link pandastic.studio.models.api.vehicle.Vehicle} is seen by whoever can see its root entity, and
     * there is no per-viewer decision for a caller to make. Every kind that owns its viewer list
     * implements it.
     *
     * @param player who to show it to
     * @return whether this was a change — {@code false} if they already had it, if this model is not
     *         spawned, or if this kind does not decide who sees it
     */
    default boolean show(@NotNull Player player) {
        return false;
    }

    /**
     * Hides this model from one player.
     *
     * @param player who to take it away from
     * @return whether this was a change; see {@link #show(Player)} for when it cannot be
     */
    default boolean hide(@NotNull Player player) {
        return false;
    }

    /**
     * Shows this model to {@code player} and hides it from everybody else.
     *
     * <p>Iterates the players online at the moment of the call, so somebody who joins afterwards gets
     * whatever the server's own tracking gives them. The player list is global and safe to read from any
     * thread; what it does with each player is {@link #show(Player)} and {@link #hide(Player)}, so this
     * inherits their thread rule rather than relaxing it.
     *
     * @param player the only player who should see it
     */
    default void hideForAllExcept(@NotNull Player player) {
        final UUID kept = player.getUniqueId();
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(kept)) {
                show(online);
            } else {
                hide(online);
            }
        }
    }

    /**
     * Shows this model to everybody except {@code player}, who it is hidden from.
     *
     * @param player the only player who should not see it
     */
    default void showForAllExcept(@NotNull Player player) {
        final UUID hidden = player.getUniqueId();
        for (final Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(hidden)) {
                hide(online);
            } else {
                show(online);
            }
        }
    }

    /**
     * Changes the scale of the whole model.
     *
     * <p>This and the setters below are the one appearance vocabulary. A kind that cannot express one of
     * them ignores it rather than throwing, because a consumer tinting every model it owns should not
     * have to ask each one what it is; {@link EntityModel} routes the ones its
     * {@link pandastic.studio.models.api.model.component.decoration.ModelDecorator} expresses to that
     * component, so the component API and the rig pipeline are two spellings of one operation rather than
     * two operations.
     *
     * @param scale scale multiplier; 1.0 keeps the model's original size
     */
    default void setScale(float scale) {}

    /**
     * Adds a fixed pitch/yaw rotation to the model.
     *
     * @param pitchDegrees pitch in degrees
     * @param yawDegrees yaw in degrees
     */
    default void setRotation(float pitchDegrees, float yawDegrees) {}

    /** Applies a tint to every visible bone. The RGB value uses {@code 0xRRGGBB}. */
    default void setTint(int rgb) {}

    /** Removes the model tint. */
    default void clearTint() {}

    /** Enables or disables the whole-model glow outline. */
    default void setGlow(boolean glow) {}

    /** Changes the whole-model glow outline color. The RGB value uses {@code 0xRRGGBB}. */
    default void setGlowColor(int rgb) {}

    /** Enables or disables the enchanted-item glint. */
    default void setGlint(boolean glint) {}

    /** Changes block and sky brightness for every display bone. */
    default void setBrightness(int blockLight, int skyLight) {}

    /** Changes the model's rendering distance. */
    default void setViewRange(float blocks) {}

    /** Shows or hides the whole model, without changing who counts as a viewer of it. */
    default void setVisible(boolean visible) {}

    /** Shows or hides one rendered bone and, where supported, its children. */
    default void setBoneVisible(@NotNull String boneName, boolean visible) {}

    /** Tints one rendered bone and, where supported, its children. */
    default void setBoneTint(@NotNull String boneName, int rgb) {}

    /** Removes tint from one rendered bone. */
    default void clearBoneTint(@NotNull String boneName) {}

    /** Enables or disables glow on one rendered bone. */
    default void setBoneGlow(@NotNull String boneName, boolean glow) {}

    /** Names available for {@link #playAnimation(String)} and {@link #replaceAnimation(String, String)}. */
    default @NotNull Set<String> getAnimationNames() {
        return Collections.emptySet();
    }

    /**
     * Starts a looping animation.
     *
     * @param animationName the clip to play
     * @return true if the animation exists and was started
     */
    default boolean playAnimation(@NotNull String animationName) {
        return false;
    }

    /** Starts an animation once and lets it finish at its last frame. */
    default boolean playAnimationOnce(@NotNull String animationName) {
        return false;
    }

    /** Stops a currently running animation. */
    default boolean stopAnimation(@NotNull String animationName) {
        return false;
    }

    /** Stops {@code targetAnimation} and starts {@code newAnimation} in its place. */
    default boolean replaceAnimation(@NotNull String targetAnimation, @NotNull String newAnimation) {
        return false;
    }
}
