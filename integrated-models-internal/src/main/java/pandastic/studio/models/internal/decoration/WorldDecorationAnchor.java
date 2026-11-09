package pandastic.studio.models.internal.decoration;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.RenderSettingsSource;

import java.util.Objects;
import java.util.UUID;

/**
 * One world, and the right to stand a model in it: the production {@link DecorationAnchor}.
 *
 * <p>Stateless apart from the world it belongs to, so a single instance serves every decoration in that
 * world. That is a consequence of the seam taking a placement per call rather than remembering one —
 * see {@link DecorationAnchor} for why it does.
 *
 * <h2>The region scheduler here, and the entity scheduler there</h2>
 * The entity path must use an entity's own scheduler, because that is the only dispatcher that follows
 * an entity across a region or world boundary and reports its removal. A decoration never moves itself
 * and never disappears, so the question it asks is simpler: which thread owns <em>this position</em>.
 * The region scheduler answers exactly that, and a decoration that has been moved into another region
 * asks again about its new placement rather than carrying a dispatcher that was pinned to its old one.
 *
 * <h2>What actually needs that thread</h2>
 * Almost nothing. Posing, moving, showing and hiding are packets, and a player's connection is not
 * region state. Only {@link #open} and the matching close touch anything the server owns, and only
 * because the renderer fires a platform event on both — a listener is entitled to assume that event
 * runs where events for that position run. Which is why ownership is checked at exactly those two
 * points and nowhere else in the layer.
 */
public final class WorldDecorationAnchor implements DecorationAnchor {

    /**
     * The modifier every decoration tracker is created with.
     *
     * <p><strong>Not a tunable: a correctness requirement.</strong> The renderer's default filters every
     * viewer through its own line-of-sight test, which for a decoration is wrong twice over — this
     * plugin's viewer list is the decision, and the test reads a player's eye location from whichever
     * thread asked for the spawn. The other two flags are left at their defaults because they describe
     * reactions to an entity being damaged, and a decoration has no entity.
     */
    private static final @NotNull TrackerModifier MODIFIER =
            TrackerModifier.builder().sightTrace(false).build();

    private final @NotNull Plugin plugin;
    private final @NotNull World world;
    private final @NotNull RenderSettingsSource renderSettings;

    /**
     * @param renderSettings where a bound sink reads its dirty epsilon from, once per emission. A source
     *                       rather than a snapshot because a sink outlives any one configuration
     */
    public WorldDecorationAnchor(
            @NotNull Plugin plugin,
            @NotNull World world,
            @NotNull RenderSettingsSource renderSettings
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.renderSettings = Objects.requireNonNull(renderSettings, "renderSettings");
    }

    @Override
    public @NotNull UUID worldId() {
        return this.world.getUID();
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull DecorationPlacement placement) {
        // The documented way to ask, and the only one: the owning region is a property of the chunk the
        // position falls in, which is why this takes a placement rather than answering for a world.
        return Bukkit.isOwnedByCurrentRegion(locationOf(placement));
    }

    @Override
    public boolean submit(@NotNull DecorationPlacement placement, @NotNull Runnable work) {
        Objects.requireNonNull(work, "work");
        if (Bukkit.getWorld(this.world.getUID()) == null) {
            // The world has been unloaded: nothing will ever tick that region again, so the work would
            // never run. For the one caller — releasing a tracker — that is also the one case where
            // declining costs nothing, since an unloaded world has no viewers and no displays left.
            return false;
        }
        Bukkit.getRegionScheduler().execute(this.plugin, locationOf(placement), work);
        return true;
    }

    @Override
    public @NotNull DecorationRendering open(
            @NotNull Rig rig,
            @NotNull DecorationPlacement placement
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(placement, "placement");
        final ModelRenderer renderer = BetterModel.model(rig.getModelId()).orElseThrow(
                () -> new IllegalStateException(
                        "Rig '" + rig.getName() + "' points at model '" + rig.getModelId()
                                + "', which BetterModel has not loaded. Check the model name and that"
                                + " its file is in BetterModel's model folder."));
        // The location overload, which is the whole reason a decoration is cheap: it yields a tracker
        // holding its own position, with no entity to own, to tick or to let the server tick for us.
        final DummyTracker tracker =
                renderer.create(BukkitAdapter.adapt(locationOf(placement)), MODIFIER);
        // Takes ownership of the tracker, including closing it if the rig cannot be bound.
        return TrackerDecorationRendering.bind(rig, tracker, this.world, this.renderSettings);
    }

    @Override
    public @NotNull String describe() {
        return "world '" + this.world.getName() + "'";
    }

    /** The world this anchor places decorations in. */
    public @NotNull World world() {
        return this.world;
    }

    private @NotNull Location locationOf(@NotNull DecorationPlacement placement) {
        return new Location(this.world, placement.x(), placement.y(), placement.z(),
                placement.yawDegrees(), placement.pitchDegrees());
    }

    @Override
    public @NotNull String toString() {
        return "WorldDecorationAnchor[" + describe() + "]";
    }
}
