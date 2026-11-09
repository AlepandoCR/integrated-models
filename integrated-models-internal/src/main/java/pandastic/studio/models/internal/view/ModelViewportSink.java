package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * How a culling decision reaches one model's renderer: show it to a viewer, or take it away.
 *
 * <h2>Showing means re-sending everything, and that is not an optimisation choice</h2>
 * Bone transforms go out as <em>deltas</em>, gated by a dirty tracker that keeps the last value it
 * emitted per bone. That makes "stop sending" and "send nothing because nothing moved" the same wire
 * behaviour — which is exactly why culling cannot simply be "skip the updates".
 *
 * <p>A viewer who merely missed updates keeps whatever pose they last received, forever, because every
 * bone the tracker considers clean is a bone it will never mention again. The model would come back
 * frozen in the pose it had when they walked away, and nothing would ever repair it: a lid stuck at
 * the angle it had, indefinitely.
 *
 * <p>So {@link #show} is a <strong>spawn plus a full-state send</strong>, and an implementation must
 * reset its dirty baseline for that viewer rather than resume. {@link #hide} is a genuine despawn. The
 * cost of that is real — a full respawn is every bone at once — and it is what
 * {@link ViewSettings#spawnBudgetPerViewer()} exists to meter.
 *
 * <h2>Only models</h2>
 * An implementation touches this plugin's own bone displays and nothing else. Vanilla entity tracking
 * is not ours to interfere with: a player still sees the base entity, its nameplate and its hitbox on
 * the server's own terms. What disappears is the rendered model, and with it the physics traffic that
 * was the entire cost.
 */
public interface ModelViewportSink {

    /**
     * Spawns this model's bones for {@code viewer} and sends their complete current state.
     *
     * <p>Must not be a resume. See the interface documentation.
     */
    void show(@NotNull UUID viewer);

    /** Despawns this model's bones for {@code viewer}, after which they cost nothing. */
    void hide(@NotNull UUID viewer);

    /** A sink that does nothing, for a model with no renderer bound yet and for tests. */
    @NotNull ModelViewportSink NONE = new ModelViewportSink() {
        @Override
        public void show(@NotNull UUID viewer) {
        }

        @Override
        public void hide(@NotNull UUID viewer) {
        }
    };
}
