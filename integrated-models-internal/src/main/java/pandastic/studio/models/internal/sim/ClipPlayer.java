package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

/**
 * Starts and stops one of a model's own animation clips: the renderer half of {@link ClipStage}.
 *
 * <h2>A seam, because the other side cannot be faked</h2>
 * A live tracker is not constructible without a server, so the state machine that decides <em>which</em>
 * clip should be playing has to be testable against something else. Narrowing the renderer down to the
 * two operations the clip layer performs on it puts every decision on this side, where a test can drive
 * it, and leaves nothing behind but delegation.
 *
 * <h2>Threading — this one is region work</h2>
 * Unlike {@link pandastic.studio.models.api.render.PoseSink}, an implementation of this touches the
 * renderer's own animation state for a live tracker, so it is called from the thread that owns the model's
 * anchor and from nowhere else. That is why the clip decision runs beside the anchor sample rather than
 * inside the simulation step: the speed it needs is already in hand there, a clip changes on the scale of
 * hundreds of milliseconds rather than of a simulation substep, and the alternative is a hop from the
 * simulation thread back to a region for every decision.
 */
public interface ClipPlayer {

    /**
     * Plays {@code clip} on a loop, blending in and out over the given durations.
     *
     * @param clip          the clip's name, exactly as the rigger spelled it in the model file
     * @param blendInTicks  ticks to blend into the clip over; zero snaps
     * @param blendOutTicks ticks to blend out of it over; zero snaps
     * @return {@code false} when the model declares no such clip, or the renderer declined to play it.
     *         Not a fault worth throwing over — a rig naming a clip the model does not have is a content
     *         error, and the caller reports it once rather than every sample
     */
    boolean play(@NotNull String clip, int blendInTicks, int blendOutTicks);

    /**
     * Stops {@code clip}.
     *
     * @return {@code true} when it was playing and is not any more
     */
    boolean stop(@NotNull String clip);

    /**
     * A player that does nothing and reports that it did.
     *
     * <p>For a model whose rendering has gone away underneath a decision already in flight, and for a
     * test that only cares about the state machine.
     */
    @NotNull ClipPlayer NONE = new ClipPlayer() {

        @Override
        public boolean play(@NotNull String clip, int blendInTicks, int blendOutTicks) {
            return false;
        }

        @Override
        public boolean stop(@NotNull String clip) {
            return false;
        }
    };
}
