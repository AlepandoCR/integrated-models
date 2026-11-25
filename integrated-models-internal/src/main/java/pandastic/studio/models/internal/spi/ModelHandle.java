package pandastic.studio.models.internal.spi;

import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.component.animation.AnimationMetadata;

/**
 * One model rendered on one entity by the component API, and the sixteen operations that API performs
 * on it.
 *
 * <h2>Why this is not {@link pandastic.studio.models.internal.binding.RenderBinding}</h2>
 * They look alike and they are not interchangeable. A {@code RenderBinding} is the rig pipeline's handle:
 * it carries a {@link pandastic.studio.models.api.render.PoseSink}, every bone of a rig is resolved
 * against it at bind time, and its visual setters are the ones {@code RiggedModel} exposes — with that
 * layer's own conventions, including a scale that composes with the entity's own and a brightness that
 * is clamped to the protocol's range.
 *
 * <p>This is the component API's handle: no rig, no sink, no bones, and the conventions
 * {@code ModelDecoratorImpl} has always had — a scale that <em>replaces</em> the entity's rather than
 * multiplying it, and a brightness passed through unclamped. Folding the two together would silently
 * change one of those for whichever caller lost, which is exactly the class of change nobody notices
 * until a model is the wrong size on a shrunk player.
 *
 * <h2>Every method names its caller</h2>
 * {@link #close()} and {@link #isClosed()} are {@code ModelImpl}'s; {@link #hide(Player)} and
 * {@link #show(Player)} are {@code ModelImpl}'s viewer methods; the ten visual setters are
 * {@link pandastic.studio.models.internal.model.component.decoration.ModelDecoratorImpl}'s, one each;
 * and the two animation methods are
 * {@link pandastic.studio.models.internal.model.component.animation.ModelAnimatorImpl}'s.
 *
 * <h2>Threading</h2>
 * Every method touches renderer state belonging to an entity, so all of them are called from the thread
 * that owns that entity. {@code ModelImpl} schedules its own work on the entity's own
 * {@code EntityScheduler}, which is what makes that true on Folia.
 */
public interface ModelHandle {

    /**
     * Releases the model: despawns its displays and removes whatever real entities the renderer created
     * for hitboxes.
     *
     * <p>Idempotent. Caller: {@code ModelImpl.despawn()}.
     */
    void close();

    /** Whether the model has been released. Caller: {@code ModelImpl.isSpawned()}. */
    boolean isClosed();

    /**
     * Stops sending this model to {@code player}.
     *
     * <p>Caller: {@code ModelImpl.hide(Player)}, which then also hides the base entity itself. Answers
     * {@code false} when the renderer does not know this player — which is not a fault, only a player
     * whose connection the renderer never saw.
     */
    boolean hide(@NotNull Player player);

    /** The inverse of {@link #hide(Player)}. Caller: {@code ModelImpl.show(Player)}. */
    boolean show(@NotNull Player player);

    /**
     * Sets the model's scale, <em>replacing</em> whatever the entity's own scale would have contributed.
     *
     * <p>Caller: {@code ModelDecoratorImpl.setScale(float)}. Note the difference from
     * {@link pandastic.studio.models.internal.binding.RenderBinding#setScale(float)}, which multiplies
     * the entity's instead; see the class documentation.
     */
    void setScale(float scale);

    /** Caller: {@code ModelDecoratorImpl.setDamageTint(int)}. The tint flashed when the entity is hurt. */
    void setDamageTint(int rgb);

    /** Caller: {@code ModelDecoratorImpl.setGlowColor(int)}. */
    void setGlowColor(int rgb);

    /** Caller: {@code ModelDecoratorImpl.setGlowing(boolean)}. */
    void setGlowing(boolean glowing);

    /** Caller: {@code ModelDecoratorImpl.setEnchantmentGlint(boolean)}. */
    void setEnchantmentGlint(boolean enchanted);

    /**
     * Caller: {@code ModelDecoratorImpl.setBillboard(Display.Billboard)}.
     *
     * <p>Bukkit's own enum, deliberately: it is the vocabulary the component API's consumers already
     * hold, and translating it into a renderer's equivalent is a backend's job rather than theirs.
     */
    void setBillboard(@NotNull Display.Billboard billboard);

    /**
     * Caller: {@code ModelDecoratorImpl.setBrightness(int, int)}.
     *
     * <p>Passed through unclamped, which is this path's long-standing behaviour. The rig pipeline's
     * equivalent clamps; see the class documentation for why the two are not merged.
     */
    void setBrightness(int blockLightLevel, int skyLightLevel);

    /** Caller: {@code ModelDecoratorImpl.setViewRange(float)}. */
    void setViewRange(float viewRange);

    /** Caller: {@code ModelDecoratorImpl.setTint(int)}. */
    void setTint(int rgb);

    /** Caller: {@code ModelDecoratorImpl.setRotation(float, float)}. Degrees, pitch then yaw. */
    void setRotation(float pitchDegrees, float yawDegrees);

    /**
     * Plays a named animation clip the model declares.
     *
     * <p>Caller: {@code ModelAnimatorImpl.setAnimation(String)} and
     * {@code ModelAnimatorImpl.setAnimation(String, Runnable)}. Both parameters are nullable and the
     * four combinations are genuinely four different requests, which is why this is one method rather
     * than two overloads:
     *
     * <ul>
     *   <li>no metadata, no callback — the renderer's own defaults, start to finish;</li>
     *   <li>no metadata, a callback — the renderer's defaults, with {@code onEnd} run when the clip
     *       finishes;</li>
     *   <li>metadata, no callback — the clip shaped by {@code metadata};</li>
     *   <li>both — the clip shaped by {@code metadata}, with {@code onEnd} at the end of it.</li>
     * </ul>
     *
     * <p>{@code metadata} is the api-module interface, so a backend reads its accessors and maps them
     * onto its own animation options. A metadata value whose options a backend cannot express is applied
     * as far as it goes rather than refused: the alternative is a clip that does not play at all because
     * one unsupported field was set.
     *
     * @param animationId the clip to play, as the model declares it
     * @param metadata    how to play it, or {@code null} for the renderer's defaults
     * @param onEnd       run when the clip finishes, or {@code null} for nothing
     * @return {@code false} when the model declares no such clip, or when the renderer declined to play
     *         it — the caller logs that rather than failing
     */
    boolean playAnimation(
            @NotNull String animationId,
            @Nullable AnimationMetadata metadata,
            @Nullable Runnable onEnd);

    /**
     * Stops the named clip.
     *
     * <p>Caller: {@code ModelAnimatorImpl.stopAnimation()}. The clip has to be named because a renderer
     * offers no "stop whatever is playing"; remembering which name that was is the caller's job.
     *
     * @return {@code true} when it was playing and is not any more
     */
    boolean stopAnimation(@NotNull String animationId);
}
