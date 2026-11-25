package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.render.PoseSink;

/**
 * The renderer half of a bound model: a live tracker and the sink that writes poses onto it.
 *
 * <p>A seam, because the type on the other side of it cannot be faked. A rendering library's live
 * tracker is not constructible without a server — BetterModel's, for instance, is a sealed class
 * permitting only its own two subclasses — so there is no way to exercise the binding's ordering, its
 * idempotence or its teardown against a real one in a unit test. Narrowing the renderer down to the three
 * operations the binding layer actually performs on it moves every decision to this side of the seam,
 * where a test can drive it, and leaves one unfakeable adapter behind, in the backend module.
 *
 * <p>Obtained from {@link pandastic.studio.models.internal.spi.ModelRenderBackend#bindEntity}, which is
 * also where the ownership rule for a bind that fails is written down.
 *
 * <h2>Detaching and closing are separate, and the order matters</h2>
 * {@link #detach()} stops this binding writing to the tracker. It touches no world state, cannot fail,
 * and is safe from any thread — which is what makes it usable as the first move of a teardown, before
 * anything is released.
 *
 * <p>{@link #close()} releases the tracker, which sends despawn packets and removes whatever real
 * entities the renderer created for hitboxes. That is world mutation, so it is region work, and
 * {@link RiggedModelImpl} is careful about where it happens.
 *
 * <p>Teardown therefore always detaches first and closes second. Reversed, a pose emission already in
 * flight would be writing transforms through a tracker that has just been released.
 */
public interface RenderBinding {

    /**
     * Where a finished pose goes.
     *
     * <p>Bound to one rig and one tracker, with every bone resolved to a cached display transformer at
     * bind time. Stable for this binding's whole life: the simulation holds the reference it is given
     * here and never looks it up again.
     */
    @NotNull PoseSink sink();

    /**
     * Stops emitting, without releasing the tracker.
     *
     * <p>Idempotent and safe from any thread. Needed as a distinct step because the renderer offers no
     * way to remove a frame handler once registered, so "stop writing" and "release the tracker" are
     * genuinely two different operations rather than one with a tidier name.
     */
    void detach();

    /**
     * Releases the tracker.
     *
     * <p>Idempotent. May only be called from a thread allowed to touch the anchor's world state, which
     * is why {@link RiggedModelImpl} routes it through {@link ModelAnchor} rather than calling it
     * directly.
     */
    void close();

    /** Whether the tracker has been released. */
    boolean isClosed();

    default void setScale(float scale) {}
    default void setRotation(float pitchDegrees, float yawDegrees) {}
    default void setTint(int rgb) {}
    default void clearTint() {}
    default void setGlow(boolean glow) {}
    default void setGlowColor(int rgb) {}
    default void setGlint(boolean glint) {}
    default void setBrightness(int blockLight, int skyLight) {}
    default void setViewRange(float blocks) {}
    default void setVisible(boolean visible) {}
    default void setBoneVisible(@NotNull String boneName, boolean visible) {}
    default void setBoneTint(@NotNull String boneName, int rgb) {}
    default void clearBoneTint(@NotNull String boneName) {}
    default void setBoneGlow(@NotNull String boneName, boolean glow) {}
    default @NotNull java.util.Set<String> animationNames() { return java.util.Collections.emptySet(); }
    default boolean playAnimation(@NotNull String animationName, boolean loop) { return false; }

    /**
     * {@link #playAnimation(String, boolean)} with the renderer's blend durations stated.
     *
     * <p>Exists because the clip-driving layer changes which of a model's own clips is playing whenever
     * its owner changes locomotion state, and a clip that snaps in reads as the model teleporting into a
     * new pose. The durations are configuration — see {@code gait.yml}'s {@code clipBlendInTicks} — so
     * they have to reach the renderer rather than being whatever the two-argument form happens to use.
     *
     * <p>Defaults to the two-argument form, so a backend that cannot express a blend plays the clip
     * anyway rather than refusing it. That is the same "as far as it goes" rule {@code ModelHandle}
     * applies to animation metadata, and for the same reason: a clip that does not play at all because
     * one unsupported field was set is the worse failure.
     *
     * @param blendInTicks  ticks to blend into the clip over; zero snaps
     * @param blendOutTicks ticks to blend out of it over; zero snaps
     */
    default boolean playAnimation(
            @NotNull String animationName,
            boolean loop,
            int blendInTicks,
            int blendOutTicks
    ) {
        return playAnimation(animationName, loop);
    }
    default boolean stopAnimation(@NotNull String animationName) { return false; }
    default boolean replaceAnimation(@NotNull String targetAnimation, @NotNull String newAnimation) { return false; }
    default boolean hide(@NotNull org.bukkit.entity.Player player) { return false; }
    default boolean show(@NotNull org.bukkit.entity.Player player) { return false; }
}
