package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.sim.RateLimitedLogger;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs the per-viewer culler from <strong>one</strong> repeating off-tick task, and then stops the work
 * the decision says nobody is watching.
 *
 * <h2>The loop this layer was missing</h2>
 * {@link ViewportCuller} is a stateless pass function and {@link ViewerSnapshot} is an immutable value;
 * between them they had everything except something to call them. This is that, and it is deliberately
 * the smallest possible addition: it reads the configuration once, reads the index once, calls the pass,
 * and applies the gate. Every decision is still the culler's.
 *
 * <h2>Rate: the sampling interval, and nothing faster</h2>
 * The period is {@link ViewSettings#periodNanos()}, which is {@code sampleIntervalTicks} — two ticks, ten
 * passes a second, by default. Running it at the simulation's rate is the specific mistake this layer
 * exists to prevent: a cull decision is a function of positions that change at most once a tick, so three
 * passes per tick produce three identical answers and pay for all three. And the interval cannot be
 * chosen freely in the other direction either, because the distance hysteresis is <em>proven</em> against
 * it — see {@link ViewSettings#periodNanos()} for why one key governs both tasks.
 *
 * <h2>Threading</h2>
 * The body runs on the platform async scheduler, off the tick and off every region thread, which is legal
 * because a pass touches nothing the server owns: it reads immutable snapshots a region thread published
 * and positions the models published for themselves, and what it writes is packets and one volatile flag
 * per model. Iterations are strictly serialised — the platform reschedules after the body returns — so
 * nothing here is stepped concurrently with itself.
 *
 * <h2>What the gate actually saves, and what it cannot</h2>
 * A pass ends by calling {@link CullableModel#setSimulated(boolean)} for every model, which takes an
 * unseen model's simulation off the simulation loop's per-iteration work. That skips, per step and per
 * model: the pose reset, the gait evaluation, the spring integration over every bone at the configured
 * substep rate, the at-rest test and the emission. It is the whole of {@code ModelSimulation.step}.
 *
 * <p>It does <strong>not</strong> skip the per-entity driver sample, and that is not an oversight. The
 * sample is what publishes the position this pass decides from, and that position can only come from
 * reading the entity on the thread that owns it — so a culler that stopped the sampler would be deciding
 * from a position that stopped changing, which is a model that disappears once and never comes back. It
 * is also where a dead entity is noticed. The sampler is consequently the one per-tick cost a bound model
 * keeps paying while nobody can see it.
 *
 * <h2>Failure isolation</h2>
 * The pass is wrapped once rather than per model, because unlike the simulation loop a failure here is
 * not attributable to one model: the culler iterates viewers on the outside and models on the inside, and
 * a partial pass is self-correcting anyway — the next one re-derives every decision from scratch, since
 * the only state is each model's viewport and that is exactly what a partial pass leaves consistent.
 * {@link Throwable} rather than {@link Exception}, because the platform swallows whatever escapes the body
 * and reschedules regardless, so letting an {@code Error} through buys nothing and costs the report.
 *
 * <h2>Shutdown</h2>
 * {@link #stop()} must run on disable, alongside the other two loops'. See
 * {@link pandastic.studio.models.internal.sim.SimulationLoop} for why a surviving off-tick task holding
 * these classes fails unattributably after the classloader closes.
 */
public final class ViewLoop {

    private final @NotNull SimulationScheduler scheduler;
    private final @NotNull SimulationLogger errors;
    private final @NotNull ViewSettingsSource settings;
    private final @NotNull ViewerIndex viewers;
    private final @NotNull ViewportCuller culler = new ViewportCuller();

    /**
     * Where the models come from.
     *
     * <p>Copy-on-write for the reason the other loops' registration lists are: it is iterated on every
     * pass while a source is added exactly once, at assembly.
     */
    private final @NotNull List<CullableModelSource> sources = new CopyOnWriteArrayList<>();

    /** Guarded by {@code this}, alongside {@link #scheduledPeriodNanos}. */
    private @Nullable SimulationTask task;

    /**
     * The period the live task was dispatched at, which is what {@link #reload()} compares against. A
     * repeating task's rate belongs to the platform, so the configured period and the dispatched one
     * differ exactly between a reload and the call that acts on it.
     */
    private long scheduledPeriodNanos;

    /** What the last pass did, for the health and statistics reports. */
    private volatile @NotNull Result lastResult = Result.IDLE;

    /**
     * @param scheduler where the repeating task comes from
     * @param clock     the clock the error throttle measures against
     * @param errors    where a failed pass is reported; wrapped in a {@link RateLimitedLogger} unless it
     *                  already is one, because a throwing pass is not one event but one stack trace per
     *                  period, forever
     * @param settings  the rate and every culling bound, read once per pass
     * @param viewers   the index the per-player samplers publish into
     */
    public ViewLoop(
            @NotNull SimulationScheduler scheduler,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger errors,
            @NotNull ViewSettingsSource settings,
            @NotNull ViewerIndex viewers
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.viewers = Objects.requireNonNull(viewers, "viewers");
        this.errors = RateLimitedLogger.wrapping(
                Objects.requireNonNull(errors, "errors"),
                Objects.requireNonNull(clock, "clock"),
                // The simulation's own throttle would be the obvious thing to share and is the wrong
                // one: it lives in simulation.yml and is about a model that throws every step, where
                // this fires at most ten times a second. One pass period is the only interval that is
                // self-evidently right here — it reports every pass that fails, and no more often than
                // passes happen.
                () -> this.settings.current().periodNanos());
    }

    /**
     * Adds a place this loop reads models from.
     *
     * <p>Called once per binder at assembly. Not a per-model registration; see
     * {@link CullableModelSource} for why the binders' own membership is the authority.
     *
     * @param source where to read models from
     */
    public void addSource(@NotNull CullableModelSource source) {
        this.sources.add(Objects.requireNonNull(source, "source"));
    }

    /** How many sources this loop reads. */
    public int sourceCount() {
        return this.sources.size();
    }

    /**
     * Starts the single repeating task.
     *
     * <p>The initial delay equals the period so the first pass runs against an index the samplers have
     * had a chance to fill. A pass against an empty index is not harmless: every model would be held by
     * nobody and every model would be suspended, and although the next pass repairs it, that is a visible
     * stutter at enable for no reason.
     *
     * @throws IllegalStateException if already running, which would have two passes deciding the same
     *                               viewports and each undoing the other's spawn budget
     */
    public synchronized void start() {
        if (this.task != null) {
            throw new IllegalStateException("The view loop is already running");
        }
        schedule(this.settings.current().periodNanos());
    }

    private synchronized void schedule(long periodNanos) {
        this.scheduledPeriodNanos = periodNanos;
        this.task = this.scheduler.runAtFixedRate(
                this::iterate, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Cancels the repeating task. Idempotent, and must run on disable.
     *
     * <p>Deliberately leaves every model where the last pass left it, including suspended. Resuming them
     * all first would be the wrong order: this is called during a shutdown whose next steps stop the
     * simulation loop and despawn everything, so a resume would restart work that is about to be torn
     * down. A loop that is stopped and started again — which only happens in a test — repairs every
     * model on its first pass.
     */
    public synchronized void stop() {
        final SimulationTask running = this.task;
        if (running == null) {
            return;
        }
        this.task = null;
        running.cancel();
    }

    /** Whether the repeating task is live. */
    public synchronized boolean isRunning() {
        return this.task != null;
    }

    /** The configured period, in nanoseconds. What the loop <em>should</em> run at. */
    public long periodNanos() {
        return this.settings.current().periodNanos();
    }

    /** The period the live task was dispatched at, or the last one if the loop is stopped. */
    public synchronized long scheduledPeriodNanos() {
        return this.scheduledPeriodNanos;
    }

    /** What the most recent pass did. {@link Result#IDLE} before the first one. */
    public @NotNull Result lastResult() {
        return this.lastResult;
    }

    /**
     * Re-dispatches the task if configuration moved the pass rate.
     *
     * <p>Explicit rather than detected per pass, for the reason every repeating task in this plugin
     * carries a {@code reload()}: a task cannot change its own period from inside its own body. Every
     * other value a reload can move — every range, angle, margin and budget — is read fresh at the top of
     * the next pass and needs no action at all.
     *
     * @return whether the task was re-dispatched
     */
    public synchronized boolean reload() {
        final long periodNanos = this.settings.current().periodNanos();
        if (this.task == null || periodNanos == this.scheduledPeriodNanos) {
            return false;
        }
        // Cancel before dispatching, never the other way round.
        final SimulationTask running = this.task;
        this.task = null;
        running.cancel();
        schedule(periodNanos);
        return true;
    }

    /** The scheduled body. See the class note on why the catch is here and not per model. */
    private void iterate() {
        try {
            this.lastResult = runPass();
        } catch (final Throwable thrown) {
            this.errors.error("The view culling pass failed; it will be retried next period", thrown);
        }
    }

    /**
     * One pass. Package-private so a test can drive it directly.
     *
     * <h2>One read of each of the two moving things</h2>
     * The configuration is read <strong>once</strong> and used for the whole pass, which is
     * {@link ViewSettingsSource}'s stated contract and not a performance choice: a read per model would
     * let a reload land mid-pass and produce a decision that admitted a model on one generation's range
     * and then measured its angle against another's. The viewer map is read once for the same reason — a
     * second read could see a different set of players, so a model could be hidden from somebody the pass
     * then decided was not there.
     *
     * @return what the pass did
     */
    @NotNull Result runPass() {
        final ViewSettings current = this.settings.current();
        final Map<UUID, ViewerSnapshot> viewing = this.viewers.viewers();
        final List<CullableModel> models = collect();

        final ViewportCuller.Result decided = this.culler.pass(models, viewing, current);

        // The gate, after the decision and never before it: the question is "does anybody hold this
        // model now", and this pass's own shows and hides are part of the answer.
        int suspended = 0;
        int resumed = 0;
        int simulated = 0;
        int withheld = 0;
        for (final CullableModel model : models) {
            // A model that is not a culling decision is not a suspension decision either. Without this
            // the two would contradict each other: isCullable() false makes the culler hide the model
            // from everybody, so "nobody holds it" would be true of exactly the models this layer has
            // been told it may not touch.
            final boolean cullable = model.isCullable();
            final boolean wanted = !cullable || model.viewport().shownCount() > 0;
            if (model.setSimulated(wanted)) {
                if (wanted) {
                    resumed++;
                } else {
                    suspended++;
                }
            }
            if (model.isSimulated()) {
                simulated++;
            } else if (cullable) {
                // Counted here and not as "models minus simulated": a model that has not spawned yet is
                // not being stepped either, and letting it inflate the saving would make this figure
                // claim credit for work that never existed.
                withheld++;
            }
        }

        return new Result(
                decided, models.size(), viewing.size(), simulated, withheld, suspended, resumed);
    }

    /**
     * Every model every source holds, flattened.
     *
     * <p>Materialised rather than streamed because the list is walked twice — once by the culler and once
     * by the gate — and because a source's own collection is a snapshot whose contents must not change
     * between those two walks: a model that vanished in between would be decided for and then not gated,
     * which would leave it suspended with nothing left to resume it.
     */
    private @NotNull List<CullableModel> collect() {
        final List<CullableModel> models = new ArrayList<>();
        for (final CullableModelSource source : this.sources) {
            final Collection<? extends CullableModel> held = source.cullableModels();
            models.addAll(held);
        }
        return models;
    }

    /**
     * What a pass did, including the part that is the whole point.
     *
     * @param decided   the culler's own counters: shows, hides, deferred shows, forgotten viewers
     * @param models    how many models the pass decided about
     * @param viewers   how many viewers it decided for
     * @param simulated how many of those models are still being stepped after the pass
     * @param withheld  how many spawned, cullable models are <strong>not</strong> being stepped because
     *                  nobody can see them. The one figure here that says whether this layer is paying
     *                  for itself, and the one a test asserts against
     * @param suspended how many stopped being stepped on this pass
     * @param resumed   how many started again
     */
    public record Result(
            @NotNull ViewportCuller.Result decided,
            int models,
            int viewers,
            int simulated,
            int withheld,
            int suspended,
            int resumed
    ) {

        /** Before the first pass. */
        public static final @NotNull Result IDLE =
                new Result(new ViewportCuller.Result(0, 0, 0, 0), 0, 0, 0, 0, 0, 0);

        public Result {
            Objects.requireNonNull(decided, "decided");
        }

        /** Whether this pass changed nothing, which is the common case to skip logging. */
        public boolean isIdle() {
            return this.decided.isIdle() && this.suspended == 0 && this.resumed == 0;
        }
    }
}
