package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.rig.Rig;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The write path, over the binding layer and the renderer's own registry.
 *
 * <h2>What dressing somebody actually is</h2>
 * Three steps, and each belongs to a different owner. A rig is resolved for the model id by
 * {@link ModelRigs}, because which rig drives which model is the rig directory's policy. The rig is bound
 * to the player through {@link ModelBinder}, which validates it against the model offline and hands back
 * a {@link RiggedModel}; spawning that handle is what creates the renderer's tracker, resolves every bone
 * and starts the simulation. Everything afterwards — colour, scale, animation, taking it off again — is
 * an operation on the <em>live model</em> and goes through {@link WardrobeRendering}, because a handle
 * exposes none of it.
 *
 * <h2>Why a handle is kept and what it is kept for</h2>
 * Not to answer questions. {@link #wornModel} prefers the handle only as a cheap shortcut and falls back
 * to the renderer, which is the authority: the renderer's registry is keyed by the entity and persisted in
 * the entity's own data, so a player who relogs while dressed comes back wearing a model <em>no handle of
 * ours exists for</em>. A wardrobe that answered from its own map alone would report that player as
 * wearing nothing, offer them a second copy of the same model, and have no way to take the first one off.
 *
 * <p>The handle is kept because something has to own the rig, the simulation and the lifetime, and
 * because releasing it is how the tracker is closed in the one order the binding layer documents as safe.
 *
 * <h2>What is remembered, and why there is nothing to persist</h2>
 * Two things, both in memory and both per player: the appearance last applied, and the animation last
 * started. The second is not a convenience — the renderer stops an animation <em>by name</em> and offers
 * no "stop whatever is playing", so a Stop button has nowhere else to get the name from. The first is
 * because the renderer exposes no way to read a tint or a glow back off a model, only to set them.
 *
 * <p>Neither is worth storing on disk. A disguise itself needs no storage of ours at all: the renderer
 * already writes what a player wears into that player's own persistent data, and a rig is derivable from
 * the model id, so there is nothing left for this class to own that outlives the process. A player
 * therefore keeps their colour and their animation for as long as they keep their model within one run of
 * the server, and starts from the configured defaults after a restart.
 *
 * <h2>Memory is bounded by what is live</h2>
 * Nothing here listens for a player leaving, so an entry outlives the player it describes. Every
 * {@link #wear} therefore sweeps entries whose handle is no longer alive before adding its own, which
 * bounds the maps by the number of live disguises plus whatever has died since somebody last dressed — on
 * a server where anybody uses the menu at all, that is the live set. A scheduled sweep instead would be
 * one more repeating task to retain and cancel on disable, which is the trade
 * {@link MenuSessions} documents for the same reason.
 *
 * <h2>Threading</h2>
 * Every method is called on the thread that owns {@code viewer}, which is what makes
 * {@link RiggedModel#spawn()} legal here at all. The maps are concurrent because the sweep walks entries
 * belonging to other players, from whichever thread happened to dress somebody.
 */
public final class RiggedModelWardrobe implements ModelWardrobe {

    private final @NotNull ModelBinder binder;
    private final @NotNull ModelRigs rigs;
    private final @NotNull WardrobeRendering rendering;
    private final @NotNull WardrobeFailureSink failures;

    /** Every disguise this wardrobe put on and has not taken off. */
    private final @NotNull Map<UUID, Worn> worn = new ConcurrentHashMap<>();

    /** The appearance each player last applied. Absent means "never applied anything". */
    private final @NotNull Map<UUID, ModelAppearance> appearances = new ConcurrentHashMap<>();

    /** The animation each player last started, so a Stop button has a name to stop. */
    private final @NotNull Map<UUID, String> animations = new ConcurrentHashMap<>();

    /**
     * @param binder    how a rig is put on a player
     * @param rigs      which rig drives a model id
     * @param rendering how a model already in the world is read and changed
     * @param failures  where a model that could not be put on is reported
     */
    public RiggedModelWardrobe(
            @NotNull ModelBinder binder,
            @NotNull ModelRigs rigs,
            @NotNull WardrobeRendering rendering,
            @NotNull WardrobeFailureSink failures
    ) {
        this.binder = Objects.requireNonNull(binder, "binder");
        this.rigs = Objects.requireNonNull(rigs, "rigs");
        this.rendering = Objects.requireNonNull(rendering, "rendering");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    @Override
    public @NotNull Optional<String> wornModel(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        final Worn ours = this.worn.get(viewer.getUniqueId());
        if (ours != null && ours.model().isAlive()) {
            return Optional.of(ours.modelId());
        }
        return this.rendering.wornModel(viewer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Takes whatever they wore off first, so a replacement is never a second model standing in the
     * same place. That also forgets their colour and their animation: an appearance belongs to the model
     * it was applied to, and carrying it onto a different model would set a value the player never chose
     * for it.
     *
     * <p>Every failure on the way in is a no-op for the player and a line for the console. There are
     * three, and none of them is something the player did: no rig could be produced for the model, the
     * rig's bone names no longer match it, or the handle refused to spawn. The fourth outcome — a spawn
     * that answers {@code false} — is a player who logged out between the click and this call, which is a
     * race nobody can avoid and not worth reporting.
     */
    @Override
    public void wear(@NotNull Player viewer, @NotNull String modelId) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(modelId, "modelId");

        remove(viewer);
        sweep();

        final Rig rig;
        try {
            rig = this.rigs.rigFor(modelId);
        } catch (final RuntimeException unavailable) {
            this.failures.failed("No rig is available for the model '" + modelId + "', so "
                    + viewer.getName() + " could not be dressed in it", unavailable);
            return;
        }

        final RiggedModel model;
        try {
            model = this.binder.bind(rig, viewer);
        } catch (final RuntimeException unbindable) {
            this.failures.failed("The rig '" + rig.getName() + "' cannot be bound to the model '"
                    + modelId + "', so " + viewer.getName() + " could not be dressed in it", unbindable);
            return;
        }

        final boolean spawned;
        try {
            spawned = model.spawn();
        } catch (final RuntimeException failed) {
            // The handle is spent either way, and a bound-but-unspawned one left behind would keep the
            // binder's own shutdown sweep believing there is something to tear down.
            model.despawn();
            this.failures.failed("The model '" + modelId + "' could not be spawned on "
                    + viewer.getName(), failed);
            return;
        }
        if (!spawned) {
            model.despawn();
            return;
        }

        this.worn.put(viewer.getUniqueId(), new Worn(modelId, model));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two removals, because there are two ways a player can be wearing something. A model this
     * wardrobe bound is released through its handle, which closes the tracker in the order the binding
     * layer documents. Anything the renderer still has on them afterwards — a disguise restored from
     * their own persistent data after a relog, which no handle of ours survived — is taken off at the
     * renderer level. Both are idempotent, so a second press does nothing and says nothing.
     */
    @Override
    public void remove(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        final UUID player = viewer.getUniqueId();

        this.appearances.remove(player);
        this.animations.remove(player);

        final Worn ours = this.worn.remove(player);
        if (ours != null) {
            ours.model().despawn();
        }

        // Asked after the handle was released, because releasing it is what removes our own tracker from
        // the registry — so what is left here is only ever something we never owned.
        this.rendering.wornModel(viewer)
                .ifPresent(remaining -> this.rendering.strip(viewer, remaining));
    }

    @Override
    public @NotNull ModelAppearance readAppearance(
            @NotNull Player viewer,
            @NotNull ModelAppearance defaults
    ) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(defaults, "defaults");
        return this.appearances.getOrDefault(viewer.getUniqueId(), defaults);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Remembered first and applied second, and remembered even when there is nothing to apply it to.
     * The screen reads this value back to decide where its controls open, so a submission that was
     * accepted and then silently forgotten would present as sliders that jump back on the next build.
     */
    @Override
    public void applyAppearance(@NotNull Player viewer, @NotNull ModelAppearance appearance) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(appearance, "appearance");
        this.appearances.put(viewer.getUniqueId(), appearance);
        wornModel(viewer).ifPresent(modelId -> this.rendering.decorate(viewer, modelId, appearance));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Remembered only once the renderer says it started. An animation the model does not declare is
     * not playing, and recording it anyway would give the Stop button a name to stop that never ran.
     */
    @Override
    public void playAnimation(@NotNull Player viewer, @NotNull String animationId) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(animationId, "animationId");
        wornModel(viewer).ifPresent(modelId -> {
            if (this.rendering.play(viewer, modelId, animationId)) {
                this.animations.put(viewer.getUniqueId(), animationId);
            }
        });
    }

    @Override
    public void stopAnimation(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        final String playing = this.animations.remove(viewer.getUniqueId());
        if (playing == null) {
            return;
        }
        wornModel(viewer).ifPresent(modelId -> this.rendering.stop(viewer, modelId, playing));
    }

    /** How many disguises this wardrobe currently holds a handle for. */
    public int size() {
        return this.worn.size();
    }

    /**
     * Drops every entry whose handle is no longer alive.
     *
     * <p>A handle dies without telling us: the binding layer releases a model when its entity retires,
     * which is what happens to every disguise when its wearer logs out. The entry left behind describes
     * nothing, and the player's remembered colour and animation describe a model that is gone, so all
     * three go together.
     */
    private void sweep() {
        final Iterator<Map.Entry<UUID, Worn>> entries = this.worn.entrySet().iterator();
        while (entries.hasNext()) {
            final Map.Entry<UUID, Worn> entry = entries.next();
            if (entry.getValue().model().isAlive()) {
                continue;
            }
            entries.remove();
            this.appearances.remove(entry.getKey());
            this.animations.remove(entry.getKey());
        }
    }

    @Override
    public @NotNull String toString() {
        return "RiggedModelWardrobe[" + this.worn.size() + " worn]";
    }

    /**
     * One disguise this wardrobe put on: what it is, and the handle that owns it.
     *
     * @param modelId the renderer's model id, which is also the key its tracker is registered under
     * @param model   the handle, held so the rig and the simulation can be released
     */
    private record Worn(@NotNull String modelId, @NotNull RiggedModel model) {
    }

    /**
     * The appearance a player last applied, for a caller that wants it without a fallback.
     *
     * <p>Package-private and used by nothing in production: {@link #readAppearance} is the path a screen
     * takes, and it cannot distinguish "never applied" from "applied the defaults" because it must not —
     * a screen opens at a value either way. This exists so a test can tell the two apart.
     *
     * @param viewer the player
     * @return what they applied, or {@code null} when they never applied anything
     */
    @Nullable ModelAppearance appliedAppearance(@NotNull Player viewer) {
        return this.appearances.get(viewer.getUniqueId());
    }

    /**
     * The animation a player has playing, as far as this wardrobe knows.
     *
     * <p>Package-private for the reason above: production reads it only through {@link #stopAnimation}.
     *
     * @param viewer the player
     * @return the animation id, or {@code null} when nothing is playing
     */
    @Nullable String playingAnimation(@NotNull Player viewer) {
        return this.animations.get(viewer.getUniqueId());
    }
}
