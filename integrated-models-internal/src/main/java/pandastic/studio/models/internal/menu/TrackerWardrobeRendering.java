package pandastic.studio.models.internal.menu;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * BetterModel's tracker registry, as a {@link WardrobeRendering}.
 *
 * <p>The adapter on the far side of that seam, and deliberately the only class in this package that
 * cannot be exercised without the renderer installed: a {@code Tracker} is a sealed class permitting only
 * the renderer's own subclasses, so one is not constructible here and there is no runtime copy of the
 * renderer in this project's test source set either. Everything with a decision in it therefore lives on
 * the other side of the seam, and what is left is five lookups and four delegations.
 *
 * <h2>The registry is reached by UUID</h2>
 * Not by adapting the Bukkit entity, which routes through the renderer's NMS layer for no gain: the
 * registry is indexed by UUID and a player's is the one identifier this layer always has. It also makes
 * the lookup legal for a player who has gone — it answers {@code null} rather than touching an entity.
 *
 * <h2>One color, applied twice</h2>
 * The appearance screen offers a single color, so it is applied as both the body tint and the glow
 * outline's color. A model tinted red whose outline was still white would read as a bug in the swatch
 * rather than as two settings, and there is only one slider set to blame.
 *
 * <h2>Scale composes with the entity's own</h2>
 * {@code ModelScaler.entity().multiply(scale)} rather than {@code ModelScaler.value(scale)}. The
 * renderer's default makes a model follow its entity's scale attribute, and replacing the scaler
 * outright would silently detach a disguise from a player shrunk by a potion or a plugin. The slider is a
 * multiplier on top of whatever the player already is.
 *
 * <h2>Two toggles this cannot honor</h2>
 * {@link ModelAppearance#physics()} and {@link ModelAppearance#firstPersonArms()} are not applied here,
 * because neither is expressible: the renderer's api jar has no first-person-arms concept at all, and
 * secondary motion is simulated on our side of the seam by a handle that offers no pause. Both are
 * carried in the value and remembered by {@link RiggedModelWardrobe} so the screen opens where the player
 * left it; neither changes what is rendered.
 */
final class TrackerWardrobeRendering implements WardrobeRendering {

    @Override
    public @NotNull Optional<String> wornModel(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        final EntityTrackerRegistry registry = registryOf(viewer);
        if (registry == null || registry.isClosed()) {
            return Optional.empty();
        }
        // The first tracker, which for a disguise is the only one. A player carrying several models is
        // somebody else's arrangement, and answering with one of them is still better than answering
        // with nothing: the menus can then at least offer to take it off.
        final EntityTracker first = registry.first();
        return first == null || first.isClosed() ? Optional.empty() : Optional.of(first.name());
    }

    @Override
    public boolean strip(@NotNull Player viewer, @NotNull String modelId) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(modelId, "modelId");
        final EntityTrackerRegistry registry = registryOf(viewer);
        // The registry's own removal, which closes the tracker and rewrites what it had persisted for
        // this entity. Doing it by hand would leave the entity claiming to wear a model that is gone.
        return registry != null && registry.remove(modelId);
    }

    @Override
    public boolean decorate(
            @NotNull Player viewer,
            @NotNull String modelId,
            @NotNull ModelAppearance appearance
    ) {
        Objects.requireNonNull(appearance, "appearance");
        final EntityTracker tracker = trackerOf(viewer, modelId);
        if (tracker == null) {
            return false;
        }
        tracker.update(TrackerUpdateAction.glow(appearance.glow()));
        tracker.update(TrackerUpdateAction.glowColor(appearance.packedRgb()));
        tracker.update(TrackerUpdateAction.tint(appearance.packedRgb()));
        tracker.scaler(ModelScaler.entity().multiply(appearance.scale()));
        return true;
    }

    @Override
    public boolean play(
            @NotNull Player viewer,
            @NotNull String modelId,
            @NotNull String animationId
    ) {
        Objects.requireNonNull(animationId, "animationId");
        final EntityTracker tracker = trackerOf(viewer, modelId);
        return tracker != null && tracker.animate(animationId);
    }

    @Override
    public boolean stop(
            @NotNull Player viewer,
            @NotNull String modelId,
            @NotNull String animationId
    ) {
        Objects.requireNonNull(animationId, "animationId");
        final EntityTracker tracker = trackerOf(viewer, modelId);
        return tracker != null && tracker.stopAnimation(animationId);
    }

    /**
     * The live tracker rendering {@code modelId} on {@code viewer}, or {@code null}.
     *
     * <p>A closed tracker is reported as absent rather than handed out. The registry drops a tracker when
     * it closes, but the two happen in that order, so a lookup racing a teardown can see one — and every
     * operation on a closed tracker is a silent no-op, which is the shape of bug that presents as a
     * colour slider that stopped working.
     */
    private static @Nullable EntityTracker trackerOf(@NotNull Player viewer, @NotNull String modelId) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(modelId, "modelId");
        final EntityTrackerRegistry registry = registryOf(viewer);
        if (registry == null) {
            return null;
        }
        final EntityTracker tracker = registry.tracker(modelId);
        return tracker == null || tracker.isClosed() ? null : tracker;
    }

    private static @Nullable EntityTrackerRegistry registryOf(@NotNull Player viewer) {
        return BetterModel.registryOrNull(viewer.getUniqueId());
    }

    @Override
    public @NotNull String toString() {
        return "TrackerWardrobeRendering";
    }
}
