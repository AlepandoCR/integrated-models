package pandastic.studio.models.bettermodel.tracker;

import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import kr.toxicity.model.api.util.function.BonePredicate;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import org.jetbrains.annotations.NotNull;
import org.bukkit.entity.Player;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.bettermodel.render.TrackerPoseSink;
import pandastic.studio.models.internal.binding.RenderBinding;
import pandastic.studio.models.internal.render.RenderSettingsSource;

import java.util.Objects;
import java.util.Set;

/**
 * A live BetterModel tracker and the sink writing poses onto it.
 *
 * <p>The adapter on the far side of the {@link RenderBinding} seam, and deliberately a class that cannot
 * be exercised without a server: {@code Tracker} is a sealed class permitting only the renderer's own two
 * subclasses, so an instance of it is not constructible in a test. Everything with a decision in it
 * therefore lives on the other side of the seam, in {@code integrated-models-internal}, and what is left
 * here is delegation.
 *
 * <h2>Detach and close are not the same call</h2>
 * The renderer offers no way to remove a frame handler once registered, so a sink stops emitting by
 * refusing to rather than by being unhooked — which is what {@link TrackerPoseSink#detach()} does, and
 * why "stop writing" is cheap, thread-free and separable from "release the tracker".
 *
 * <p>{@link #close()} is neither of those: it despawns the renderer's displays and removes whatever real
 * entities it created for hitboxes. It is latched on the renderer's side, so calling it twice is free —
 * but it is world mutation, which is why the binding layer routes it through its own anchor rather than
 * calling it whenever it feels like it.
 */
public final class TrackerRenderBinding implements RenderBinding {

    private final @NotNull Tracker tracker;
    private final @NotNull TrackerPoseSink sink;

    private TrackerRenderBinding(@NotNull Tracker tracker, @NotNull TrackerPoseSink sink) {
        this.tracker = tracker;
        this.sink = sink;
    }

    /**
     * Binds {@code rig} to {@code tracker}, taking ownership of the tracker.
     *
     * <p>Takes ownership in the strong sense: if the bind fails, the tracker is closed here rather than
     * handed back to the caller. A tracker that is spawned but cannot be driven is a model standing in
     * the world that nothing holds a reference to, so nothing would ever close it — and the bind failing
     * is the expected outcome of a rig whose bone names have gone stale, which is the common case rather
     * than the exotic one.
     *
     * @param settings where the sink reads its dirty epsilon from, once per emission
     * @throws IllegalStateException if the tracker renders a different model than the rig names, or any
     *                               bone is missing, ambiguous, or has no display to drive — with the
     *                               complete list of names in the message
     */
    public static @NotNull TrackerRenderBinding bind(
            @NotNull Rig rig,
            @NotNull Tracker tracker,
            @NotNull RenderSettingsSource settings
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(settings, "settings");
        try {
            return new TrackerRenderBinding(tracker, TrackerPoseSink.bind(rig, tracker, settings));
        } catch (final RuntimeException | Error failure) {
            tracker.close();
            throw failure;
        }
    }

    @Override
    public @NotNull PoseSink sink() {
        return this.sink;
    }

    @Override
    public void detach() {
        this.sink.detach();
    }

    @Override
    public void close() {
        // Detached first even though the caller already has: this method is public and the ordering is
        // the whole point of it. The renderer latches its own close, so the second call is a no-op.
        this.sink.detach();
        this.tracker.close();
    }

    @Override
    public boolean isClosed() {
        return this.tracker.isClosed();
    }

    @Override
    public void setScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0F) {
            throw new IllegalArgumentException("scale must be finite and greater than zero");
        }
        this.tracker.scaler(ModelScaler.entity().multiply(scale));
    }

    @Override
    public void setRotation(float pitchDegrees, float yawDegrees) {
        this.tracker.rotation(() -> new ModelRotation(pitchDegrees, yawDegrees));
    }

    @Override
    public void setTint(int rgb) {
        this.tracker.update(TrackerUpdateAction.tint(rgb));
    }

    @Override
    public void clearTint() {
        this.tracker.update(TrackerUpdateAction.previousTint());
    }

    @Override
    public void setGlow(boolean glow) {
        this.tracker.update(TrackerUpdateAction.glow(glow));
    }

    @Override
    public void setGlowColor(int rgb) {
        this.tracker.update(TrackerUpdateAction.glowColor(rgb));
    }

    @Override
    public void setGlint(boolean glint) {
        this.tracker.update(TrackerUpdateAction.enchant(glint));
    }

    @Override
    public void setBrightness(int blockLight, int skyLight) {
        this.tracker.update(TrackerUpdateAction.brightness(
                Math.clamp(blockLight, 0, 15),
                Math.clamp(skyLight, 0, 15)));
    }

    @Override
    public void setViewRange(float blocks) {
        if (!Float.isFinite(blocks) || blocks <= 0F) {
            throw new IllegalArgumentException("view range must be finite and greater than zero");
        }
        this.tracker.update(TrackerUpdateAction.viewRange(blocks));
    }

    @Override
    public void setVisible(boolean visible) {
        this.tracker.update(TrackerUpdateAction.togglePart(visible));
    }

    @Override
    public void setBoneVisible(@NotNull String boneName, boolean visible) {
        this.tracker.update(
                TrackerUpdateAction.togglePart(visible),
                BonePredicate.name(boneName).withChildren());
    }

    @Override
    public void setBoneTint(@NotNull String boneName, int rgb) {
        this.tracker.update(
                TrackerUpdateAction.tint(rgb),
                BonePredicate.name(boneName).withChildren());
    }

    @Override
    public void clearBoneTint(@NotNull String boneName) {
        this.tracker.update(
                TrackerUpdateAction.previousTint(),
                BonePredicate.name(boneName).withChildren());
    }

    @Override
    public void setBoneGlow(@NotNull String boneName, boolean glow) {
        this.tracker.update(
                TrackerUpdateAction.glow(glow),
                BonePredicate.name(boneName).withChildren());
    }

    @Override
    public @NotNull Set<String> animationNames() {
        return Set.copyOf(this.tracker.renderer().animations().keySet());
    }

    @Override
    public boolean playAnimation(@NotNull String animationName, boolean loop) {
        final AnimationModifier modifier = AnimationModifier.builder()
                .type(loop ? AnimationIterator.Type.LOOP : AnimationIterator.Type.PLAY_ONCE)
                .speed(1F)
                .start(1)
                .end(0)
                .build();
        return this.tracker.animate(animationName, modifier);
    }

    /**
     * {@inheritDoc}
     *
     * <p>BetterModel calls the two durations {@code start} and {@code end} and documents them as "lerp-in
     * time" and "lerp-out time", in ticks. They are the renderer's own blend, so the clip layer's
     * configured values go straight through.
     */
    @Override
    public boolean playAnimation(
            @NotNull String animationName,
            boolean loop,
            int blendInTicks,
            int blendOutTicks
    ) {
        final AnimationModifier modifier = AnimationModifier.builder()
                .type(loop ? AnimationIterator.Type.LOOP : AnimationIterator.Type.PLAY_ONCE)
                .speed(1F)
                .start(Math.max(0, blendInTicks))
                .end(Math.max(0, blendOutTicks))
                .build();
        return this.tracker.animate(animationName, modifier);
    }

    @Override
    public boolean stopAnimation(@NotNull String animationName) {
        return this.tracker.stopAnimation(animationName);
    }

    @Override
    public boolean replaceAnimation(@NotNull String targetAnimation, @NotNull String newAnimation) {
        final AnimationModifier modifier = AnimationModifier.builder()
                .type(AnimationIterator.Type.LOOP)
                .speed(1F)
                .start(1)
                .end(0)
                .build();
        return this.tracker.replace(targetAnimation, newAnimation, modifier);
    }


    @Override
    public boolean hide(@NotNull Player player) {
        return this.tracker.hide(BukkitAdapter.adapt(player));
    }

    @Override
    public boolean show(@NotNull Player player) {
        return this.tracker.show(BukkitAdapter.adapt(player));
    }

    /** The tracker, for a caller that needs to decorate the model it is already driving. */
    public @NotNull Tracker tracker() {
        return this.tracker;
    }

    @Override
    public @NotNull String toString() {
        return "TrackerRenderBinding[" + this.tracker.name() + ", closed=" + isClosed()
                + ", emitting=" + this.sink.isActive() + "]";
    }
}
