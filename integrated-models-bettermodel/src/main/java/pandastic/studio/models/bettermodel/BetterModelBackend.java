package pandastic.studio.models.bettermodel;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.bettermodel.model.BetterModelDefinition;
import pandastic.studio.models.bettermodel.tracker.TrackerDecorationRendering;
import pandastic.studio.models.bettermodel.tracker.TrackerModelHandle;
import pandastic.studio.models.bettermodel.tracker.TrackerRenderBinding;
import pandastic.studio.models.bettermodel.tracker.TrackerSeatLocators;
import pandastic.studio.models.bettermodel.tracker.TrackerWardrobeRendering;
import pandastic.studio.models.internal.binding.RenderBinding;
import pandastic.studio.models.internal.decoration.DecorationRendering;
import pandastic.studio.models.internal.menu.WardrobeRendering;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.spi.ModelDefinition;
import pandastic.studio.models.internal.spi.ModelHandle;
import pandastic.studio.models.internal.spi.ModelRenderBackend;
import pandastic.studio.models.internal.vehicle.SeatLocators;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * BetterModel, as a {@link ModelRenderBackend}.
 *
 * <p>The one class that fans out to the rest of this module, and the only one a reader has to follow to
 * find where every seam's far side lives. Nothing above it in {@code integrated-models-internal} names
 * this class or any other in this module: it arrives through {@link java.util.ServiceLoader} — see
 * {@link BetterModelBackendProvider} — which is what lets a different rendering library be swapped in as
 * a different Gradle module with no edit to the engine.
 *
 * <h2>Stateless</h2>
 * One field, and it is a shared stateless collaborator. Every model lookup goes to BetterModel's own
 * static registry and every binding creates a tracker and hands it straight to the adapter that owns it,
 * so there is nothing here to keep in step with a reload. That matters for a backend specifically: the
 * renderer re-reads its own models on its own operator command and reports that to nobody, so any cache
 * here would have no event able to invalidate it.
 *
 * <h2>Nothing here is tunable</h2>
 * One constant, {@link #DECORATION_MODIFIER}, and it is a correctness requirement rather than a
 * preference — see its own documentation. Everything else arrives from a rig, a model id, an entity or
 * the {@link RenderSettingsSource} the caller hands in, which is itself configuration-backed.
 */
public final class BetterModelBackend implements ModelRenderBackend {

    /**
     * The name this backend reports itself under.
     *
     * <p><strong>Not a tunable: an identity.</strong> It appears in the enable-time log line and inside
     * the "which the renderer has not loaded" messages a rig author reads, so it has to be the name of
     * the plugin an operator installs — and there is exactly one correct value for that.
     */
    private static final @NotNull String RENDERER_NAME = "BetterModel";

    /**
     * The modifier every decoration tracker is created with.
     *
     * <p><strong>Not a tunable: a correctness requirement.</strong> The renderer's default filters every
     * viewer through its own line-of-sight test, which for a decoration is wrong twice over — this
     * plugin's viewer list is the decision, and the test reads a player's eye location from whichever
     * thread asked for the spawn. The other two flags are left at their defaults because they describe
     * reactions to an entity being damaged, and a decoration has no entity.
     */
    private static final @NotNull TrackerModifier DECORATION_MODIFIER =
            TrackerModifier.builder().sightTrace(false).build();

    /** Stateless, so one instance serves the whole server. */
    private final @NotNull WardrobeRendering wardrobe = new TrackerWardrobeRendering();

    @Override
    public @NotNull String rendererName() {
        return RENDERER_NAME;
    }

    @Override
    public @NotNull Optional<ModelDefinition> definition(@NotNull String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        final ModelRenderer renderer = BetterModel.modelOrNull(modelId);
        // A model the renderer does not have is not a fault here. Callers re-check on every use
        // precisely because a renderer reload between two of them is ordinary.
        return renderer == null ? Optional.empty() : Optional.of(new BetterModelDefinition(renderer));
    }

    @Override
    public @NotNull Collection<String> modelIds() {
        // The renderer's own key set, unmodifiable and live. Handed straight out: the seam's contract
        // says a caller that keeps it copies it.
        return BetterModel.modelKeys();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A player gets one extra step. Standing a model on a player means the renderer has to send it to
     * the player's own client as well as to everyone watching, which is its registry's {@code spawn}
     * rather than the tracker's creation — and a refusal there leaves a tracker nobody is driving, so it
     * is closed before the failure propagates.
     */
    @Override
    public @NotNull RenderBinding bindEntity(
            @NotNull Entity entity,
            @NotNull Rig rig,
            @NotNull RenderSettingsSource renderSettings
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(renderSettings, "renderSettings");
        final ModelRenderer renderer = rendererFor(rig);
        final EntityTracker tracker = renderer.create(BukkitAdapter.adapt(entity));
        // Takes ownership of the tracker, including closing it if the rig cannot be bound.
        final TrackerRenderBinding binding = TrackerRenderBinding.bind(rig, tracker, renderSettings);
        if (entity instanceof Player player
                && !tracker.registry().spawn(BukkitAdapter.adapt(player))) {
            binding.close();
            throw new IllegalStateException(
                    RENDERER_NAME + " could not spawn rig '" + rig.getName() + "' for player "
                            + player.getName() + ".");
        }
        return binding;
    }

    @Override
    public @NotNull DecorationRendering bindDecoration(
            @NotNull World world,
            @NotNull DecorationPlacement placement,
            @NotNull Rig rig,
            @NotNull RenderSettingsSource renderSettings
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(renderSettings, "renderSettings");
        final ModelRenderer renderer = rendererFor(rig);
        // The location overload, which is the whole reason a decoration is cheap: it yields a tracker
        // holding its own position, with no entity to own, to tick or to let the server tick for us.
        final DummyTracker tracker = renderer.create(
                BukkitAdapter.adapt(locationOf(world, placement)), DECORATION_MODIFIER);
        // Takes ownership of the tracker, including closing it if the rig cannot be bound.
        return TrackerDecorationRendering.bind(rig, tracker, world, renderSettings);
    }

    @Override
    public @NotNull ModelHandle attach(@NotNull Entity entity, @NotNull String modelId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(modelId, "modelId");
        final ModelRenderer renderer = BetterModel.modelOrNull(modelId);
        if (renderer == null) {
            // Reachable: ModelImpl's constructor checked, and the renderer can reload between then and
            // the first spawn. Saying so beats a null dereference inside the create call.
            throw new IllegalStateException(
                    RENDERER_NAME + " no longer has model '" + modelId
                            + "'. It was loaded when this model was constructed, so the renderer has"
                            + " reloaded its models since.");
        }
        return new TrackerModelHandle(renderer.create(BukkitAdapter.adapt(entity)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegated whole, because telling a locator from a bone is the one thing in this seam that is
     * genuinely renderer knowledge: a Blockbench locator reaches BetterModel as a
     * {@code BlueprintElement.Locator} that implements {@code BlueprintElement.Bone} and therefore appears
     * in the rendered bone tree beside the groups carrying cubes. {@code RenderedBone#isDerivedFrom} is
     * what separates them and is the only admissible test; see {@link TrackerSeatLocators}.
     */
    @Override
    public @NotNull SeatLocators seatLocators(@NotNull Entity entity, @NotNull String modelId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(modelId, "modelId");
        return TrackerSeatLocators.of(entity, modelId);
    }

    @Override
    public @NotNull WardrobeRendering wardrobe() {
        return this.wardrobe;
    }

    /**
     * The renderer for {@code rig}'s model, or a failure naming both.
     *
     * <p>{@link ModelRenderBackend#requireDefinition} would answer the same question and would build a
     * {@link ModelDefinition} to do it; the two binding methods need the {@code ModelRenderer} itself, so
     * they ask for it directly and reuse only the message.
     */
    private @NotNull ModelRenderer rendererFor(@NotNull Rig rig) {
        return BetterModel.model(rig.getModelId()).orElseThrow(() -> new IllegalStateException(
                "Rig '" + rig.getName() + "' points at model '" + rig.getModelId()
                        + "', which " + RENDERER_NAME + " has not loaded. Check the model name and that"
                        + " its file is in " + RENDERER_NAME + "'s model folder."));
    }

    private static @NotNull Location locationOf(
            @NotNull World world,
            @NotNull DecorationPlacement placement
    ) {
        return new Location(world, placement.x(), placement.y(), placement.z(),
                placement.yawDegrees(), placement.pitchDegrees());
    }

    @Override
    public @NotNull String toString() {
        return "BetterModelBackend";
    }
}
