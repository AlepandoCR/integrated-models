package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;

/**
 * An anchor the test drives by hand, modelling the entity scheduler's documented behaviour rather than a
 * convenient subset of it.
 *
 * <p>Three of those documented behaviours are the reason the binding layer looks the way it does, so the
 * fake reproduces all three: scheduling can <strong>fail</strong> and return nothing if the anchor is
 * already gone; the retired callback fires <strong>once</strong> and is the only notification that it
 * went; and deferred work can be <strong>refused</strong> because there is no longer a thread to run it
 * on. A fake that always succeeded would make three of this layer's branches unreachable.
 *
 * <p>Nothing here is thread-safe and nothing needs to be: a test drives it from one thread, and the
 * ownership flag is what stands in for the platform's threading rather than real threads.
 */
final class FakeAnchor implements ModelAnchor {

    private final @NotNull BindingFixtures.Journal journal;
    private final @NotNull List<Runnable> submitted = new ArrayList<>();

    private @Nullable FakeRenderBinding rendering;
    private @Nullable RuntimeException renderFailure;

    private boolean alive = true;
    private boolean owned = true;
    private boolean schedulingRefused;
    private boolean submissionRefused;

    private @Nullable Runnable body;
    private @Nullable Runnable retired;
    private @Nullable FakeAnchorTask task;
    private long periodTicks;
    private int scheduleCount;
    private @Nullable Runnable onSchedule;

    private double x;
    private double y;
    private double z;
    private float yawDegrees;
    private boolean sneaking;
    private boolean airborne;

    FakeAnchor(@NotNull BindingFixtures.Journal journal) {
        this.journal = journal;
    }

    // ---------------------------------------------------------------- the seam

    @Override
    public boolean readInto(@NotNull AnchorSample sample) {
        if (!this.alive) {
            return false;
        }
        sample.set(this.x, this.y, this.z, this.yawDegrees, this.sneaking, this.airborne);
        return true;
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return this.owned;
    }

    @Override
    public @Nullable AnchorTask schedule(
            @NotNull Runnable samplerBody,
            @NotNull Runnable retiredCallback,
            long period
    ) {
        this.scheduleCount++;
        this.journal.record("schedule");
        if (this.onSchedule != null) {
            this.onSchedule.run();
        }
        if (this.schedulingRefused || !this.alive) {
            return null;
        }
        this.body = samplerBody;
        this.retired = retiredCallback;
        this.periodTicks = period;
        final FakeAnchorTask scheduled = new FakeAnchorTask();
        this.task = scheduled;
        return scheduled;
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        this.journal.record("submit");
        if (this.submissionRefused || !this.alive) {
            return false;
        }
        this.submitted.add(work);
        return true;
    }

    @Override
    public @NotNull RenderBinding render(@NotNull Rig rig) {
        this.journal.record("render");
        if (this.renderFailure != null) {
            throw this.renderFailure;
        }
        final FakeRenderBinding opened = new FakeRenderBinding(this.journal);
        this.rendering = opened;
        return opened;
    }

    @Override
    public @NotNull String describe() {
        return "fake anchor";
    }

    // ------------------------------------------------------------ test driving

    /** Runs the sampler once, as the platform would. */
    void tick() {
        final FakeAnchorTask current = this.task;
        if (current == null) {
            throw new IllegalStateException("No sampler has been scheduled");
        }
        if (current.cancelled) {
            throw new IllegalStateException(
                    "The sampler is cancelled; the platform would not dispatch it again");
        }
        runSamplerBody();
    }

    /**
     * Runs the sampler body regardless of cancellation, which is the one thing cancelling cannot
     * prevent: a repeating task's cancel does not wait for a body already in flight.
     */
    void tickEvenIfCancelled() {
        runSamplerBody();
    }

    private void runSamplerBody() {
        final Runnable scheduled = this.body;
        if (scheduled == null) {
            throw new IllegalStateException("No sampler has been scheduled");
        }
        scheduled.run();
    }

    /** Removes the anchor and fires the platform's retired callback, exactly as Folia does. */
    void retire() {
        this.alive = false;
        final Runnable callback = this.retired;
        final FakeAnchorTask current = this.task;
        if (current != null) {
            current.cancelled = true;
        }
        if (callback != null) {
            callback.run();
        }
    }

    /** Kills the anchor without the platform noticing, so the sampler is what discovers it. */
    void die() {
        this.alive = false;
    }

    /** Runs whatever was deferred through {@link #submit}. */
    int runSubmitted() {
        final List<Runnable> pending = List.copyOf(this.submitted);
        this.submitted.clear();
        pending.forEach(Runnable::run);
        return pending.size();
    }

    void moveTo(double newX, double newY, double newZ, float newYawDegrees) {
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.yawDegrees = newYawDegrees;
    }

    void stance(boolean nowSneaking, boolean nowAirborne) {
        this.sneaking = nowSneaking;
        this.airborne = nowAirborne;
    }

    void owned(boolean nowOwned) {
        this.owned = nowOwned;
    }

    void refuseScheduling() {
        this.schedulingRefused = true;
    }

    void refuseSubmission() {
        this.submissionRefused = true;
    }

    void failRenderWith(@NotNull RuntimeException failure) {
        this.renderFailure = failure;
    }

    /** Runs {@code hook} inside {@link #schedule}, so a test can observe the state at that instant. */
    void onSchedule(@NotNull Runnable hook) {
        this.onSchedule = hook;
    }

    @Nullable FakeRenderBinding rendering() {
        return this.rendering;
    }

    int scheduleCount() {
        return this.scheduleCount;
    }

    long periodTicks() {
        return this.periodTicks;
    }

    boolean isSamplerCancelled() {
        return this.task == null || this.task.cancelled;
    }

    boolean hasSampler() {
        return this.task != null;
    }

    int pendingSubmissions() {
        return this.submitted.size();
    }

    /** Inner rather than static so a cancel lands in the same journal as everything else. */
    private final class FakeAnchorTask implements AnchorTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            if (this.cancelled) {
                return;
            }
            FakeAnchor.this.journal.record("cancel");
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
