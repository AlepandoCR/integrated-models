package pandastic.studio.models.internal.decoration;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.tracker.DummyTracker;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.TrackerRenderBinding;
import pandastic.studio.models.internal.render.RenderSettingsSource;

import java.util.Objects;
import java.util.UUID;

/**
 * A live BetterModel dummy tracker, the sink writing poses onto it, and the three operations a
 * decoration needs that an entity-backed binding gets from its entity.
 *
 * <p>The adapter on the far side of the {@link DecorationRendering} seam, and deliberately the only
 * class in this package that cannot be exercised without a server: {@code Tracker} is a sealed class
 * permitting only the renderer's own two subclasses, so an instance of it is not constructible here.
 * Everything with a decision in it therefore lives on the other side of the seam, and what is left is
 * delegation.
 *
 * <h2>Why it delegates the pose path instead of reimplementing it</h2>
 * A dummy tracker and an entity tracker are the same thing to the pose path: both are a {@code Tracker}
 * with resolvable bones, and {@link TrackerRenderBinding} already owns the bind, the detach-then-close
 * ordering and the "take ownership of a tracker whose bind failed" rule. Reimplementing that here would
 * be a second copy of the one piece of this plugin that cannot be unit-tested. So the pose half is the
 * entity path's, verbatim, and only the three decoration-specific operations are new.
 *
 * <h2>Sight tracing is switched off, and that is not a preference</h2>
 * The renderer's default modifier filters every viewer through a line-of-sight test. For a decoration
 * that is wrong twice over: this plugin's viewer list <em>is</em> the decision — a plugin showing a
 * decoration to one player means it, and a distance culler driving the same seam has already decided —
 * and the test reads a player's eye location from whichever thread asked, which for a show issued off
 * the owning region thread is exactly the undefined read the rest of this layer is built to avoid.
 */
final class TrackerDecorationRendering implements DecorationRendering {

    private final @NotNull World world;
    private final @NotNull DummyTracker tracker;
    private final @NotNull TrackerRenderBinding poses;

    private TrackerDecorationRendering(
            @NotNull World world,
            @NotNull DummyTracker tracker,
            @NotNull TrackerRenderBinding poses
    ) {
        this.world = world;
        this.tracker = tracker;
        this.poses = poses;
    }

    /**
     * Binds {@code rig} to {@code tracker}, taking ownership of the tracker.
     *
     * <p>Ownership in the strong sense: a bind that fails closes the tracker rather than handing it
     * back, because a tracker standing in the world that nothing holds a reference to is a tracker
     * nothing will ever close. That is delegated along with everything else — see
     * {@link TrackerRenderBinding#bind}.
     *
     * @param rig      the rig to drive
     * @param tracker  the dummy tracker to drive it through
     * @param world    the world the tracker stands in, needed to build the platform locations a move
     *                 takes
     * @param settings where the sink reads its dirty epsilon from, once per emission
     * @return the rendering
     * @throws IllegalStateException if the tracker renders a different model than the rig names, or any
     *                               bone is missing, ambiguous, or has no display to drive
     */
    static @NotNull TrackerDecorationRendering bind(
            @NotNull Rig rig,
            @NotNull DummyTracker tracker,
            @NotNull World world,
            @NotNull RenderSettingsSource settings
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(settings, "settings");
        return new TrackerDecorationRendering(
                world, tracker, TrackerRenderBinding.bind(rig, tracker, settings));
    }

    @Override
    public @NotNull PoseSink sink() {
        return this.poses.sink();
    }

    @Override
    public void detach() {
        this.poses.detach();
    }

    @Override
    public void close() {
        this.poses.close();
    }

    @Override
    public boolean isClosed() {
        return this.poses.isClosed();
    }

    @Override
    public void setScale(float scale) {
        this.poses.setScale(scale);
    }

    @Override
    public void setRotation(float pitchDegrees, float yawDegrees) {
        this.poses.setRotation(pitchDegrees, yawDegrees);
    }

    @Override
    public void setTint(int rgb) {
        this.poses.setTint(rgb);
    }

    @Override
    public void clearTint() {
        this.poses.clearTint();
    }

    @Override
    public void setGlow(boolean glow) {
        this.poses.setGlow(glow);
    }

    @Override
    public void setGlowColor(int rgb) {
        this.poses.setGlowColor(rgb);
    }

    @Override
    public void setGlint(boolean glint) {
        this.poses.setGlint(glint);
    }

    @Override
    public void setBrightness(int blockLight, int skyLight) {
        this.poses.setBrightness(blockLight, skyLight);
    }

    @Override
    public void setViewRange(float blocks) {
        this.poses.setViewRange(blocks);
    }

    @Override
    public void setVisible(boolean visible) {
        this.poses.setVisible(visible);
    }

    @Override
    public void setBoneVisible(@NotNull String boneName, boolean visible) {
        this.poses.setBoneVisible(boneName, visible);
    }

    @Override
    public void setBoneTint(@NotNull String boneName, int rgb) {
        this.poses.setBoneTint(boneName, rgb);
    }

    @Override
    public void clearBoneTint(@NotNull String boneName) {
        this.poses.clearBoneTint(boneName);
    }

    @Override
    public void setBoneGlow(@NotNull String boneName, boolean glow) {
        this.poses.setBoneGlow(boneName, glow);
    }

    @Override
    public @NotNull java.util.Set<String> animationNames() {
        return this.poses.animationNames();
    }

    @Override
    public boolean playAnimation(@NotNull String animationName, boolean loop) {
        return this.poses.playAnimation(animationName, loop);
    }

    @Override
    public boolean stopAnimation(@NotNull String animationName) {
        return this.poses.stopAnimation(animationName);
    }

    @Override
    public boolean replaceAnimation(@NotNull String targetAnimation, @NotNull String newAnimation) {
        return this.poses.replaceAnimation(targetAnimation, newAnimation);
    }

    @Override
    public boolean hide(@NotNull org.bukkit.entity.Player player) {
        return hide(player.getUniqueId());
    }

    @Override
    public boolean show(@NotNull org.bukkit.entity.Player player) {
        return show(player.getUniqueId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>One packet per viewer and nothing else: the renderer compares the new location against the one
     * it holds and returns early if they match, so a move to the same spot is free, and a move for a
     * decoration nobody can see builds no packets at all.
     */
    @Override
    public void moveTo(@NotNull DecorationPlacement placement) {
        Objects.requireNonNull(placement, "placement");
        this.tracker.location(BukkitAdapter.adapt(locationOf(placement)));
    }

    @Override
    public boolean show(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        if (this.tracker.isClosed()) {
            return false;
        }
        final PlatformPlayer player = playerOf(viewer);
        if (player == null) {
            return false;
        }
        // The renderer's own spawn: every bone, with its current transform, in one bundle. Not a
        // resume — see ModelViewportSink for why that distinction is load-bearing.
        this.tracker.spawn(player);
        return true;
    }

    @Override
    public boolean hide(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        if (this.tracker.isClosed()) {
            return false;
        }
        final PlatformPlayer player = playerOf(viewer);
        return player != null && this.tracker.remove(player);
    }

    /** The tracker, for a caller that needs to decorate the model it is already driving. */
    @NotNull DummyTracker tracker() {
        return this.tracker;
    }

    /**
     * The renderer's view of one online player, or {@code null} if they are not online.
     *
     * <p>Through the renderer's own adapter rather than through {@code Bukkit.getPlayer}: it is the
     * lookup the renderer performs for every packet it sends anyway, so it is the one whose threading
     * assumptions are already satisfied by a packet path that is region-independent.
     */
    private static @Nullable PlatformPlayer playerOf(@NotNull UUID viewer) {
        return BetterModel.platform().adapter().player(viewer);
    }

    private @NotNull Location locationOf(@NotNull DecorationPlacement placement) {
        return new Location(this.world, placement.x(), placement.y(), placement.z(),
                placement.yawDegrees(), placement.pitchDegrees());
    }

    @Override
    public @NotNull String toString() {
        return "TrackerDecorationRendering[" + this.tracker.name() + " in " + this.world.getName()
                + ", closed=" + isClosed() + "]";
    }
}
