package pandastic.studio.models.bettermodel.tracker;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.platform.PlatformBillboard;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.component.animation.AnimationMetadata;
import pandastic.studio.models.internal.spi.ModelHandle;

import java.util.Objects;

/**
 * A live BetterModel entity tracker, as a {@link ModelHandle}.
 *
 * <p>The adapter the component API drives, and deliberately a class that cannot be exercised without a
 * server: {@code EntityTracker} is one of the two subclasses {@code Tracker} permits, so one is not
 * constructible in a test. Everything with a decision in it therefore lives on the other side of the
 * seam — in {@code ModelImpl}, {@code ModelDecoratorImpl} and {@code ModelAnimatorImpl} — and what is
 * left here is sixteen translations with no branch in them but the one {@link #playAnimation} needs.
 *
 * <h2>Two conventions that differ from the rig pipeline's, on purpose</h2>
 * {@link #setScale(float)} uses {@code ModelScaler.value(scale)} and
 * {@link TrackerRenderBinding#setScale(float)} uses {@code ModelScaler.entity().multiply(scale)}. The
 * first replaces whatever the entity's scale attribute would have contributed; the second composes with
 * it. Likewise the brightness here is passed through and the rig pipeline's is clamped to the protocol's
 * range. Both differences are the behaviour each path has always had, and
 * {@link ModelHandle} says why they are not reconciled.
 *
 * <h2>Threading</h2>
 * Every method touches renderer state belonging to one entity, and every caller is on the thread that
 * owns that entity — {@code ModelImpl} schedules its own work on the entity's own {@code EntityScheduler},
 * which is what makes that true on Folia. Nothing here checks it, because the check belongs where the
 * dispatch decision is made.
 */
public final class TrackerModelHandle implements ModelHandle {

    private final @NotNull EntityTracker tracker;

    /** @param tracker the live tracker rendering the model; this handle does not own its creation */
    public TrackerModelHandle(@NotNull EntityTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    @Override
    public void close() {
        this.tracker.close();
    }

    @Override
    public boolean isClosed() {
        return this.tracker.isClosed();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Through the renderer's own channel lookup rather than by adapting the Bukkit player: a renderer
     * only knows a player whose connection it saw, and asking it is how a player it never saw becomes a
     * {@code false} rather than a null dereference.
     */
    @Override
    public boolean hide(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        return BetterModel.player(player.getUniqueId())
                .map(channelHandler -> this.tracker.hide(channelHandler.player()))
                .orElse(false);
    }

    @Override
    public boolean show(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        return BetterModel.player(player.getUniqueId())
                .map(channelHandler -> this.tracker.show(channelHandler.player()))
                .orElse(false);
    }

    @Override
    public void setScale(float scale) {
        this.tracker.scaler(ModelScaler.value(scale));
    }

    @Override
    public void setDamageTint(int rgb) {
        this.tracker.damageTintValue(rgb);
    }

    @Override
    public void setGlowColor(int rgb) {
        this.tracker.update(TrackerUpdateAction.glowColor(rgb));
    }

    @Override
    public void setGlowing(boolean glowing) {
        this.tracker.update(TrackerUpdateAction.glow(glowing));
    }

    @Override
    public void setEnchantmentGlint(boolean enchanted) {
        this.tracker.update(TrackerUpdateAction.enchant(enchanted));
    }

    @Override
    public void setBillboard(@NotNull Display.Billboard billboard) {
        final var platformBillboard = switch (billboard) {
            case Display.Billboard.FIXED -> PlatformBillboard.FIXED;
            case Display.Billboard.VERTICAL -> PlatformBillboard.VERTICAL;
            case Display.Billboard.HORIZONTAL -> PlatformBillboard.HORIZONTAL;
            case Display.Billboard.CENTER -> PlatformBillboard.CENTER;
        };

        this.tracker.update(TrackerUpdateAction.billboard(platformBillboard));
    }

    @Override
    public void setBrightness(int blockLightLevel, int skyLightLevel) {
        this.tracker.update(TrackerUpdateAction.brightness(blockLightLevel, skyLightLevel));
    }

    @Override
    public void setViewRange(float viewRange) {
        this.tracker.update(TrackerUpdateAction.viewRange(viewRange));
    }

    @Override
    public void setTint(int rgb) {
        this.tracker.update(TrackerUpdateAction.tint(rgb));
    }

    @Override
    public void setRotation(float pitchDegrees, float yawDegrees) {
        this.tracker.rotation(() -> new ModelRotation(pitchDegrees, yawDegrees));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Four requests, four of the renderer's own overloads, chosen the way the component API has always
     * chosen them: no metadata and no callback is the bare {@code animate}; a callback with no metadata
     * pairs it with {@code AnimationModifier.DEFAULT}, because the overload that takes a callback has no
     * form without a modifier.
     */
    @Override
    public boolean playAnimation(
            @NotNull String animationId,
            @Nullable AnimationMetadata metadata,
            @Nullable Runnable onEnd
    ) {
        Objects.requireNonNull(animationId, "animationId");
        if (onEnd == null) {
            return metadata == null
                    ? this.tracker.animate(animationId)
                    : this.tracker.animate(animationId, AnimationModifiers.of(metadata));
        }
        final AnimationModifier modifier = metadata == null
                ? AnimationModifier.DEFAULT
                : AnimationModifiers.of(metadata);
        return this.tracker.animate(animationId, modifier, onEnd);
    }

    @Override
    public boolean stopAnimation(@NotNull String animationId) {
        Objects.requireNonNull(animationId, "animationId");
        return this.tracker.stopAnimation(animationId);
    }

    @Override
    public @NotNull String toString() {
        return "TrackerModelHandle[" + this.tracker.name() + ", closed=" + isClosed() + "]";
    }
}
