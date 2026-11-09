package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.render.PoseSink;

/**
 * The renderer half of a bound model: a live tracker and the sink that writes poses onto it.
 *
 * <p>A seam, because the type on the other side of it cannot be faked. BetterModel's {@code Tracker} is
 * a sealed class permitting only its own two subclasses, so there is no way to construct one without a
 * server — and therefore no way to exercise the binding's ordering, its idempotence or its teardown
 * against a real tracker in a unit test. Narrowing the renderer down to the three operations the binding
 * layer actually performs on it moves every decision to this side of the seam, where a test can drive
 * it, and leaves exactly one unfakeable adapter behind: {@link TrackerRenderBinding}.
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
}
