package pandastic.studio.models.internal.binding;

import kr.toxicity.model.api.tracker.Tracker;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.render.TrackerPoseSink;

import java.util.Objects;

/**
 * A live BetterModel tracker and the sink writing poses onto it.
 *
 * <p>The adapter on the far side of the {@link RenderBinding} seam, and deliberately the only class in
 * this package that cannot be exercised without a server: {@code Tracker} is a sealed class permitting
 * only the renderer's own two subclasses, so an instance of it is not constructible here. Everything
 * with a decision in it therefore lives on the other side, and what is left is three delegations.
 *
 * <h2>Detach and close are not the same call</h2>
 * The renderer offers no way to remove a frame handler once registered, so a sink stops emitting by
 * refusing to rather than by being unhooked — which is what {@link TrackerPoseSink#detach()} does, and
 * why "stop writing" is cheap, thread-free and separable from "release the tracker".
 *
 * <p>{@link #close()} is neither of those: it despawns the renderer's displays and removes whatever real
 * entities it created for hitboxes. It is latched on the renderer's side, so calling it twice is free —
 * but it is world mutation, which is why the binding routes it through {@link ModelAnchor} rather than
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
