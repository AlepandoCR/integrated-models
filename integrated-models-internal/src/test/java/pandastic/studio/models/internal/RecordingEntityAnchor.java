package pandastic.studio.models.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.entity.Player;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.AnchorSample;
import pandastic.studio.models.internal.binding.AnchorTask;
import pandastic.studio.models.internal.binding.ModelAnchor;
import pandastic.studio.models.internal.binding.RenderBinding;

import java.util.Collections;
import java.util.Set;

/**
 * An entity anchor that records its lifecycle events under a name.
 *
 * <p>The name is what makes a teardown test able to say <em>which</em> model was released, which is the
 * whole point of having two of them: one the test bound for itself and one the vehicle layer bound for
 * its visual. A journal full of anonymous {@code close} entries would prove a teardown happened and not
 * that it happened in the order the construction dependency requires.
 *
 * <p>Reports itself as owned by the calling thread and alive, because the thread rules are not what
 * these tests are about — {@code RiggedModelImplTest} owns those, with a fake that refuses on demand.
 * Here the anchor is the seam that turns "the runtime wired the binder to a platform" into something
 * observable.
 */
final class RecordingEntityAnchor implements ModelAnchor {

    private final @NotNull RuntimeFixtures.Journal journal;
    private final @NotNull String name;

    private @Nullable Task task;
    private long periodTicks;
    private int scheduleCount;

    RecordingEntityAnchor(@NotNull RuntimeFixtures.Journal journal, @NotNull String name) {
        this.journal = journal;
        this.name = name;
    }

    @Override
    public boolean readInto(@NotNull AnchorSample sample) {
        sample.set(0D, 0D, 0D, 0F, false, false);
        return true;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return true;
    }

    @Override
    public @Nullable AnchorTask schedule(
            @NotNull Runnable body,
            @NotNull Runnable retired,
            long period
    ) {
        this.scheduleCount++;
        this.periodTicks = period;
        final Task scheduled = new Task();
        this.task = scheduled;
        this.journal.record(this.name + "-schedule");
        return scheduled;
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        work.run();
        return true;
    }

    @Override
    public @NotNull RenderBinding render(@NotNull Rig rig) {
        this.journal.record(this.name + "-render");
        return new Rendering();
    }

    @Override
    public @NotNull String describe() {
        return this.name;
    }

    /** The period the live sampler was dispatched at, which is what a reload moves. */
    long periodTicks() {
        return this.periodTicks;
    }

    /** How many times a sampler was dispatched. Two means a reload re-dispatched one. */
    int scheduleCount() {
        return this.scheduleCount;
    }

    boolean isSamplerCancelled() {
        return this.task == null || this.task.cancelled;
    }

    /** Inner rather than static, so a cancel lands in the same journal under the same name. */
    private final class Task implements AnchorTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            if (this.cancelled) {
                return;
            }
            this.cancelled = true;
            RecordingEntityAnchor.this.journal.record(RecordingEntityAnchor.this.name + "-cancel");
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }

    /** A renderer-side binding that records the two steps of its release. */
    private final class Rendering implements RenderBinding {

        private boolean closed;

        @Override
        public @NotNull PoseSink sink() {
            return (Rig rig, Pose pose, int interpolationTicks) -> {
                // Intentionally empty: these tests never step a simulation.
            };
        }

        @Override
        public void detach() {
            RecordingEntityAnchor.this.journal.record(RecordingEntityAnchor.this.name + "-detach");
        }

        @Override
        public void close() {
            this.closed = true;
            RecordingEntityAnchor.this.journal.record(RecordingEntityAnchor.this.name + "-close");
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override public void setScale(float scale) {}
        @Override public void setRotation(float pitchDegrees, float yawDegrees) {}
        @Override public void setTint(int rgb) {}
        @Override public void clearTint() {}
        @Override public void setGlow(boolean glow) {}
        @Override public void setGlowColor(int rgb) {}
        @Override public void setGlint(boolean glint) {}
        @Override public void setBrightness(int blockLight, int skyLight) {}
        @Override public void setViewRange(float blocks) {}
        @Override public void setVisible(boolean visible) {}
        @Override public void setBoneVisible(@NotNull String boneName, boolean visible) {}
        @Override public void setBoneTint(@NotNull String boneName, int rgb) {}
        @Override public void clearBoneTint(@NotNull String boneName) {}
        @Override public void setBoneGlow(@NotNull String boneName, boolean glow) {}
        @Override public @NotNull Set<String> animationNames() { return Collections.emptySet(); }
        @Override public boolean playAnimation(@NotNull String animationName, boolean loop) { return false; }
        @Override public boolean stopAnimation(@NotNull String animationName) { return false; }
        @Override public boolean replaceAnimation(@NotNull String targetAnimation, @NotNull String newAnimation) { return false; }
        @Override public boolean hide(@NotNull Player player) { return false; }
        @Override public boolean show(@NotNull Player player) { return false; }
    }
}
