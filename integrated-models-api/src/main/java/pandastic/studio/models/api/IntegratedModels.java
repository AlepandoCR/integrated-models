package pandastic.studio.models.api;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.decoration.DecorationBinder;
import pandastic.studio.models.api.input.PlayerInputController;
import pandastic.studio.models.api.model.ModelManager;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.VehicleBinder;

import java.util.Collection;
import java.util.Optional;

/**
 * What a downstream plugin reaches for.
 *
 * <p>Obtainable from Bukkit's service manager, which is the lookup that survives this plugin being
 * reloaded or replaced.
 *
 * <h2>What is on here, and what deliberately is not</h2>
 * Exactly the things a consumer cannot build for itself: the rigs this server loaded, the three ways to
 * put one of them into the world — on an entity, at a location, or as something a player rides — the
 * registry of everything that is currently up, and the AI service when the server has an adapter for it.
 *
 * <p>Everything else this plugin owns is plumbing and stays off here on purpose. The simulation loop,
 * the configuration service, the rig loader and the render seam are how this plugin schedules and tunes
 * its own work; a consumer reaching one of them could change the rate every other plugin's models are
 * stepped at, and would be coupled to a type that is free to change. A consumer needs a way to bind a
 * model, which is what the binders are.
 */
public interface IntegratedModels {

    /**
     * Every model this plugin currently has in the world.
     *
     * <p>One registry for all four kinds of {@link pandastic.studio.models.api.model.Model}, populated by
     * the three binders below: a rigged model, a decoration or a vehicle is entered the moment its binder
     * hands it out and removes itself as the last step of its own teardown. So this is the answer to
     * "what does this server have up right now", and it needs no cooperation from the consumer that bound
     * the model.
     *
     * <p>It was not always. Until the four model kinds were given a common root this returned a registry
     * nothing ever called {@code addModel} on, and said so in this paragraph; a consumer that read that
     * and wrote its own bookkeeping can delete it.
     *
     * <p>Models a consumer builds by subclassing the component API
     * ({@link pandastic.studio.models.api.model.EntityModel}) are still its own to register, because
     * nothing in this plugin constructs one — {@link ModelManager#addModel} and
     * {@link ModelManager#removeModel} are public for that case.
     *
     * @return the registry, never a live view
     */
    @NotNull ModelManager getModelManager();

    /**
     * How to put a rig on an entity.
     *
     * <p>Safe to hold: the binder outlives every model it hands out and is replaced only when this
     * plugin is disabled, at which point every model it bound has already been despawned.
     *
     * @return the binding layer's entry point
     */
    @NotNull ModelBinder getModelBinder();

    /**
     * How to stand a rig at a location with no entity under it.
     *
     * @return the decoration layer's entry point
     */
    @NotNull DecorationBinder getDecorationBinder();

    /**
     * How to make an entity a vehicle that renders a rig.
     *
     * @return the vehicle layer's entry point
     */
    @NotNull VehicleBinder getVehicleBinder();

    /**
     * The creature AI service, when this server has an NMS adapter for its Minecraft version.
     *
     * <p>Empty is a supported state rather than an error, and it is the one capability on this
     * interface that can be absent: the AI layer needs Mojang-mapped server internals, which are
     * version-exact, while everything else here needs only the renderer. A server running a version no
     * shipped adapter was built for — or a jar built without the NMS module — keeps every model, every
     * decoration and every vehicle and loses this.
     *
     * <p>The reason is logged once at startup, so a consumer that finds this empty does not have to
     * explain it; what it has to do is degrade rather than refuse to enable.
     *
     * @return the service, or empty when this server has no adapter
     */
    @NotNull Optional<CreatureAi> getCreatureAi();

    /**
     * Where a player's input is read, and where a handler is bound to one input or to a combination.
     *
     * <p>Safe to hold, and <strong>never absent</strong> — which is the difference between this and
     * {@link #getCreatureAi()}. The seven movement keys come from platform API, so every server
     * observes them; what a server without this plugin's NMS layer loses is the ability to
     * <em>suppress</em> a movement key and to see one before the server acts on it. Ask
     * {@link PlayerInputController#capabilities()} rather than assuming, and
     * {@link PlayerInputController#unavailableReason()} for the sentence naming what is missing.
     *
     * <p>Reading a snapshot is one volatile read of an immutable value, so a consumer's own control
     * step should read it <em>once</em> per step rather than per key — the same discipline the rest of
     * this plugin's off-tick work follows, and for the same reason: two reads can straddle an
     * observation and disagree about what the player was holding.
     *
     * @return the input layer
     */
    @NotNull PlayerInputController getInputController();

    /**
     * Every rig loaded from disk.
     *
     * <p>A rig is a validated skeleton overlay naming bones of a model this plugin does not own. It is
     * safe to hold: rigs are immutable, so one survives a configuration reload — but a reload replaces
     * them, so a consumer that wants the current definition should look it up again rather than cache.
     *
     * @return a snapshot of the loaded rigs; never live
     */
    @NotNull Collection<Rig> getRigs();

    /**
     * The rig called {@code name}.
     *
     * <p>Matched case-insensitively, the same way a rig matches bone names, so a consumer never has to
     * know how the file spelled it.
     *
     * @param name the rig's name
     * @return the rig, or empty if no rig by that name loaded
     */
    @NotNull Optional<Rig> getRig(@NotNull String name);
}
