package pandastic.studio.models.internal.spi;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.RenderBinding;
import pandastic.studio.models.internal.decoration.DecorationRendering;
import pandastic.studio.models.internal.menu.WardrobeRendering;
import pandastic.studio.models.internal.render.RenderSettingsSource;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything this plugin needs from a rendering library, and nothing it does not.
 *
 * <h2>Why a seam at all</h2>
 * The whole of this project's own work — rigs, poses, physics, the gait, emission cadence, culling,
 * menus, the AI layer — is arithmetic and ordering over plain values. Exactly one thing is not: turning a
 * finished pose into packets a client understands, which is a renderer's job. BetterModel is the renderer
 * today and will not be the only one, so the renderer-specific half lives in its own Gradle module and
 * reaches this project only through this interface.
 *
 * <p>The surface below is <strong>derived, not designed</strong>: every method exists because a named
 * caller in {@code integrated-models-internal} needs it, and each one says which. A method with no caller
 * does not belong here, because an implementor of a second backend would have to write it and could not
 * find out what for.
 *
 * <h2>The dependency runs backwards from the jar</h2>
 * A backend module declares {@code implementation(project(":integrated-models-internal"))} and this module
 * declares nothing about any backend on any classpath. Discovery is {@link java.util.ServiceLoader} — see
 * {@link ModelRenderBackendProvider} — so nothing above this seam holds a string naming an implementation,
 * and "no backend installed" is a state the plugin is written against rather than a link error.
 *
 * <h2>Nothing here is tunable</h2>
 * There is no key, bound or default on this interface. What a backend is asked for comes from a rig, a
 * model id or an entity; <em>how</em> it is asked — the emission rate, the dirty epsilon, the diagnostic
 * limits — arrives through {@link RenderSettingsSource}, which is already configuration-backed and is
 * read once per emission by the sink the backend returns.
 *
 * <h2>Threading</h2>
 * {@link #definition(String)}, {@link #modelIds()} and {@link #rendererName()} read a renderer's own
 * static registry and are safe from any thread. The three binding methods and {@link #attach} create
 * world state, so they are called only from a thread that owns the entity or the position concerned —
 * the anchors in {@code internal.binding} and {@code internal.decoration} guarantee that, and a backend
 * may assume it.
 */
public interface ModelRenderBackend {

    /**
     * The rendering library this backend drives, for the one log line emitted at plugin enable.
     *
     * <p>Caller: {@link ModelRenderBackendResolution#describe()}. A free-form name rather than an enum,
     * because this module must not hold a list of the backends that exist.
     */
    @NotNull String rendererName();

    /**
     * The static facts about one loaded model, or empty when the renderer has no such model.
     *
     * <p>Empty is not a fault: it is a statement about what the operator installed. Callers:
     * {@link pandastic.studio.models.internal.render.ModelSkeleton#of(String)} (and through it
     * {@code ModelBinderImpl.bind} and {@code DerivedLocomotionStageSource.stageFor}),
     * {@link pandastic.studio.models.internal.render.ModelSkeleton#requireBindable},
     * {@link pandastic.studio.models.internal.rig.ModelBoneSource#renderer()},
     * {@link pandastic.studio.models.internal.menu.ModelLibrary#renderer()} and
     * {@code ModelImpl}'s constructor.
     *
     * <p>Not cached, by contract — see {@link ModelDefinition}.
     */
    @NotNull Optional<ModelDefinition> definition(@NotNull String modelId);

    /**
     * Every model id the renderer currently has loaded, in its own order.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.menu.ModelLibrary#renderer()}, which is read
     * once per menu screen build. May be an unmodifiable live view; a caller that keeps it copies it.
     */
    @NotNull Collection<String> modelIds();

    /**
     * Spawns {@code rig}'s model on {@code entity} and binds the rig's bones to it.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.binding.EntityAnchor#render(Rig)}. Takes
     * ownership in the strong sense — a bind that fails must release whatever it created rather than hand
     * it back, because a model standing in the world that nothing holds a reference to is a model nothing
     * will ever close.
     *
     * @param entity         the entity to stand the model on; owned by the calling thread
     * @param rig            the rig to drive, which names the model through {@link Rig#getModelId()}
     * @param renderSettings where the returned sink reads its dirty epsilon from, once per emission. A
     *                       source rather than a snapshot because a sink outlives any one configuration
     * @throws IllegalStateException if the renderer has not loaded the rig's model, if any bone is
     *                               missing, ambiguous or has no display to drive, or if the model could
     *                               not be spawned
     */
    @NotNull RenderBinding bindEntity(
            @NotNull Entity entity,
            @NotNull Rig rig,
            @NotNull RenderSettingsSource renderSettings);

    /**
     * Stands {@code rig}'s model at {@code placement} in {@code world}, with no entity behind it.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.decoration.WorldDecorationAnchor#open}. The same
     * ownership rule as {@link #bindEntity}.
     *
     * <p>A backend must switch off any line-of-sight filtering it applies to viewers by default. That is
     * a correctness requirement rather than a preference: this plugin's viewer list <em>is</em> the
     * decision — a consumer showing a decoration to one player means it, and the distance culler driving
     * the same seam has already decided — and such a test reads a player's eye location from whichever
     * thread asked, which for a show issued off the owning region thread is an undefined read.
     *
     * @param world          the world to stand it in; its region owns the calling thread
     * @param placement      where to stand it
     * @param rig            the rig to drive
     * @param renderSettings where the returned sink reads its dirty epsilon from, once per emission
     * @throws IllegalStateException on the same conditions as {@link #bindEntity}
     */
    @NotNull DecorationRendering bindDecoration(
            @NotNull World world,
            @NotNull DecorationPlacement placement,
            @NotNull Rig rig,
            @NotNull RenderSettingsSource renderSettings);

    /**
     * Renders {@code modelId} on {@code entity} without a rig, and hands back the visual-state handle.
     *
     * <p>Caller: {@code ModelImpl.createTracker()}, which is the component API's path rather than the rig
     * pipeline's. No bones are resolved and no pose is ever written through the result — the component
     * API drives animations the renderer already owns and the visual state in
     * {@link pandastic.studio.models.internal.model.component.decoration.ModelDecoratorImpl}.
     *
     * @param entity  the entity to render on; owned by the calling thread
     * @param modelId the model to render, already known to exist because {@code ModelImpl}'s constructor
     *                refused otherwise
     * @throws IllegalStateException if the renderer no longer has {@code modelId}
     */
    @NotNull ModelHandle attach(@NotNull Entity entity, @NotNull String modelId);

    /**
     * The locators of {@code modelId} as it is currently rendered on {@code entity}.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.vehicle.SeatLocatorSource#renderer()}, and
     * through it every vehicle that resolves a seat. A <strong>locator</strong> is the empty, cube-less
     * marker a rigger places in Blockbench, and it is the only thing a rider may be seated on: a bone is
     * geometry the animation pass writes, so a seat on one would move with whichever clip is playing.
     * Telling the two apart is therefore a question only a renderer can answer, which is why it is here
     * and not computed from {@link #definition(String)} — a blueprint says what the model contains, and
     * this asks what is standing in the world.
     *
     * <p>Keyed on the entity and the model id rather than on a {@link RenderBinding}, so that the answer
     * is about the model actually rendered there. A renderer keeps its own registry of what is on an
     * entity; a vehicle whose visual somebody else spawned is therefore seatable, and one whose visual
     * failed to spawn resolves to {@link pandastic.studio.models.internal.vehicle.SeatLocators#NONE}
     * rather than to a stale handle.
     *
     * <p>Owning thread only: it reads a live model. An implementation must <em>not</em> answer with a
     * resolution for a name that turned out to be an ordinary bone — it answers
     * {@link pandastic.studio.models.internal.vehicle.SeatLocators.Kind#BONE}, and the seating layer
     * reports it as the rigger's mistake rather than seating anybody.
     *
     * @param entity  the entity the model stands on; owned by the calling thread
     * @param modelId the model the rig names
     * @return the locators, or {@code SeatLocators.NONE} when the renderer has no such model on that
     *         entity — which is a fact about the world rather than a fault
     */
    @NotNull pandastic.studio.models.internal.vehicle.SeatLocators seatLocators(
            @NotNull Entity entity,
            @NotNull String modelId);

    /**
     * What a player is wearing, and the four operations the wardrobe performs on it.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.menu.WardrobeRendering#trackers()}. A renderer
     * keeps its own registry of what is on an entity, keyed by the entity and persisted with it, and that
     * registry is the authority the wardrobe menu reads and writes — this plugin's own handles know
     * nothing about a model somebody else put on a player.
     *
     * <p>Stateless and shareable, so an implementation may return the same instance every call.
     */
    @NotNull WardrobeRendering wardrobe();

    /**
     * Throws unless {@code modelId} is loaded, with the message a rig author needs.
     *
     * <p>A default rather than a method, because every backend would write the same four lines and the
     * wording is this project's rather than a renderer's. Callers: {@link #bindEntity} and
     * {@link #bindDecoration} implementations, and {@code ModelImpl}'s constructor by way of
     * {@link #definition(String)}.
     */
    default @NotNull ModelDefinition requireDefinition(@NotNull Rig rig) {
        Objects.requireNonNull(rig, "rig");
        return definition(rig.getModelId()).orElseThrow(() -> new IllegalStateException(
                "Rig '" + rig.getName() + "' points at model '" + rig.getModelId()
                        + "', which " + rendererName() + " has not loaded. Check the model name and that"
                        + " its file is in " + rendererName() + "'s model folder."));
    }
}
