package pandastic.studio.models.api;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.decoration.DecorationBinder;
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
 * Exactly the four things a consumer cannot build for itself: the rigs this server loaded, and the
 * three ways to put one of them into the world — on an entity, at a location, or as something a player
 * rides — plus the AI service when the server has an adapter for it.
 *
 * <p>Everything else this plugin owns is plumbing and stays off here on purpose. The simulation loop,
 * the configuration service, the rig loader and the render seam are how this plugin schedules and tunes
 * its own work; a consumer reaching one of them could change the rate every other plugin's models are
 * stepped at, and would be coupled to a type that is free to change. A consumer needs a way to bind a
 * model, which is what the binders are.
 */
public interface IntegratedModels {

    /**
     * The registry of the older component-based model API.
     *
     * <p><strong>It is permanently empty, and this is the one method on this interface that does not
     * work.</strong> Nothing in this plugin ever calls {@link ModelManager#addModel}, so the collection
     * it hands back starts empty and stays that way. The component API it belongs to was carried over
     * from the project this one was ported from, where it was already unwired, and it has no concrete
     * model type: {@code ModelImpl} is abstract and nothing extends it.
     *
     * <p>Said plainly because the trap is sitting next to four methods that do work. If you want to put
     * a rig into the world, use {@link #getModelBinder()}, {@link #getDecorationBinder()} or
     * {@link #getVehicleBinder()}. Reach for this only if you are deliberately subclassing the
     * component API yourself and registering your own models in it.
     *
     * @return the component registry, which nothing populates
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
