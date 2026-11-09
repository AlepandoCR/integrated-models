package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;

/**
 * A renderer-side binding that records what was done to it instead of sending packets.
 *
 * <p>Its sink models the real one's one load-bearing behaviour: after {@code detach} it accepts nothing.
 * That is what makes "detach happened before the tracker was released" checkable from out here — the
 * real {@code TrackerPoseSink} gates on a volatile flag for exactly this reason, and a fake that kept
 * accepting poses after detaching would let a teardown ordering bug pass.
 */
final class FakeRenderBinding implements RenderBinding {

    private final @NotNull BindingFixtures.Journal journal;
    private final @NotNull RecordingSink poseSink = new RecordingSink();

    private boolean closed;

    FakeRenderBinding(@NotNull BindingFixtures.Journal journal) {
        this.journal = journal;
    }

    @Override
    public @NotNull PoseSink sink() {
        return this.poseSink;
    }

    @Override
    public void detach() {
        this.journal.record("detach");
        this.poseSink.attached = false;
    }

    @Override
    public void close() {
        this.journal.record("close");
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    boolean isDetached() {
        return !this.poseSink.attached;
    }

    /** How many poses reached the sink, and how many were refused after detaching. */
    int accepted() {
        return this.poseSink.accepted;
    }

    int refused() {
        return this.poseSink.refused;
    }

    /** The last window the simulation asked for, which proves the cadence reached the sink. */
    int lastInterpolationTicks() {
        return this.poseSink.lastInterpolationTicks;
    }

    private static final class RecordingSink implements PoseSink {

        private boolean attached = true;
        private int accepted;
        private int refused;
        private int lastInterpolationTicks = -1;

        @Override
        public void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
            if (!this.attached) {
                this.refused++;
                return;
            }
            this.accepted++;
            this.lastInterpolationTicks = interpolationTicks;
        }
    }
}
