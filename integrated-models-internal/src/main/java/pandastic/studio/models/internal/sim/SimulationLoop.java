package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.render.EmissionCadence;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Drives every registered {@link ModelSimulation} from <strong>one</strong> repeating off-tick task.
 *
 * <h2>One task, not one per model</h2>
 * A task per model would put the scheduler's per-task overhead — a queue entry, a deadline, a
 * dispatch — on the critical path of work that is a few hundred adds and multiplies, and would hand a
 * server with two thousand models two thousand tasks competing for the same small pool. Iterating a
 * flat list instead makes the loop's cost proportional to the simulations rather than to the scheduler,
 * and makes one clock reading serve the whole iteration so that every model advances by the same
 * measured interval.
 *
 * <h2>Threading</h2>
 * The body runs on the platform async scheduler, off the server tick and off every region thread. That
 * is legal only because a simulation touches nothing the server owns: the owner's movement reaches it
 * as an immutable value through a {@link DriverHolder} that a region thread published, and the finished
 * pose leaves through a {@link pandastic.studio.models.api.render.PoseSink} that is contractually
 * forbidden from reading an entity.
 *
 * <p>Iterations are strictly serialised — the platform reschedules after the body returns — so the
 * effective period is {@code max(period, bodyDuration)} and a simulation is never stepped concurrently
 * with itself. There is no thread affinity, though: successive iterations may run on different threads,
 * so nothing here caches per thread.
 *
 * <h2>Failure isolation</h2>
 * Every simulation's step is wrapped individually, so one model with a broken sink cannot stop the
 * others — without the per-simulation catch, the first failure in an iteration would skip every model
 * after it in the list, turning one bad model into a server-wide freeze of secondary motion that looks
 * nothing like its cause.
 *
 * <p>{@link Throwable} rather than {@link Exception}, because the platform swallows whatever escapes
 * the body and keeps the repeating task alive regardless. Letting an {@code Error} propagate therefore
 * buys nothing — the loop runs again next period either way — and costs the remaining models their
 * step. Reporting is rate-limited for the same reason: a throwing body is not a single event but a
 * stack trace every period, forever.
 *
 * <h2>Shutdown</h2>
 * {@link #stop()} must be the first statement of {@code onDisable()}. The plugin's classloader is
 * closed before the platform runs its own {@code cancelTasks}, so a live task holding these classes
 * fails on its first lazy class load after the close — during shutdown, unattributable, and after the
 * plugin has apparently stopped.
 *
 * <h2>Reload</h2>
 * The rate, the cadence, the calibration and the log throttle all come from a
 * {@link SimulationSettingsSource}. Nothing about them is latched at construction except the period the
 * platform was asked to dispatch at, which cannot be changed in place — so {@link #reload()} is the
 * path a configuration change takes, and it is explicit for the reasons set out there.
 */
public final class SimulationLoop {

    private final @NotNull SimulationScheduler scheduler;
    private final @NotNull SimulationClock clock;
    private final @NotNull SimulationLogger errors;
    private final @NotNull SimulationSettingsSource settings;

    /**
     * The period the live task was scheduled at. Guarded by {@code this}, alongside {@link #task},
     * because the two are only meaningful together: the field exists so {@link #reload()} can tell
     * whether the scheduled rate still matches the configured one.
     */
    private long scheduledPeriodNanos;

    /**
     * Copy-on-write because the loop iterates it on every one of its iterations — dozens a second at
     * any sane configured rate — while registration happens when a model is bound, which is rare and
     * off this thread. The snapshot an iteration
     * holds is also what makes mid-iteration registration safe: a simulation added by a sink is picked
     * up next iteration, not stepped with this iteration's already-stale clock reading.
     */
    private final @NotNull List<Registration> registrations = new CopyOnWriteArrayList<>();

    /**
     * Guarded by {@code this}. The lifecycle methods are called from the main thread at enable and
     * disable, so the monitor costs nothing and removes the need to reason about a half-started loop.
     */
    private @Nullable SimulationTask task;

    /** A loop on the real clock, taking its rate and throttle from {@code settings}. */
    public SimulationLoop(
            @NotNull SimulationScheduler scheduler,
            @NotNull SimulationLogger errors,
            @NotNull SimulationSettingsSource settings
    ) {
        this(scheduler, SimulationClock.SYSTEM, errors, settings);
    }

    /**
     * @param scheduler where the repeating task comes from
     * @param clock     the clock every step's delta is differenced from
     * @param errors    where failures go; wrapped in a {@link RateLimitedLogger} unless it already is
     *                  one, because an unlimited logger here produces one stack trace per period. The
     *                  throttle interval is read from {@code settings} on every report, so it follows a
     *                  reload without the logger being replaced
     * @param settings  the rate, cadence, calibration and log throttle this loop runs on
     */
    public SimulationLoop(
            @NotNull SimulationScheduler scheduler,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger errors,
            @NotNull SimulationSettingsSource settings
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.errors = RateLimitedLogger.wrapping(
                Objects.requireNonNull(errors, "errors"),
                clock,
                () -> settings.current().errorLogIntervalNanos());
    }

    /**
     * Adds {@code simulation} to the set this loop steps.
     *
     * <p>Rejects a cadence built for a different simulation rate. The cadence decides how many steps
     * separate two emissions, so a cadence built for 20 Hz driven by a 60 Hz loop emits three times as
     * often as it says and sends an interpolation window three times longer than the gap it is covering
     * — motion that eases for one third of the interval and then sits still, which reads as the model
     * being laggy rather than as a misconfiguration.
     *
     * @return a handle to unregister with, which is cheaper than {@link #unregister(ModelSimulation)}
     * @throws IllegalArgumentException if the cadence was built for another simulation rate
     * @throws IllegalStateException    if {@code simulation} is already registered, which would step it
     *                                 twice per iteration and halve its emission interval
     */
    public @NotNull Registration register(@NotNull ModelSimulation simulation) {
        Objects.requireNonNull(simulation, "simulation");

        // One read for the loop's own rate; the simulation answers from whichever source it holds,
        // which for a model with a per-model emission rate is not this one.
        final int loopHz = this.settings.current().simulationHz();
        final EmissionCadence cadence = simulation.cadence();
        final int cadenceSimulationHz = cadence.emissionHz() * cadence.stepsPerEmission();
        if (cadenceSimulationHz != loopHz) {
            throw new IllegalArgumentException(
                    "Rig '" + simulation.rig().getName() + "' carries a cadence built for "
                            + cadenceSimulationHz + " Hz but this loop steps at " + loopHz
                            + " Hz: " + cadence);
        }
        // Linear, and that is fine: registration happens once per model bind, not per step.
        for (final Registration existing : this.registrations) {
            if (existing.simulation == simulation) {
                throw new IllegalStateException(
                        "Rig '" + simulation.rig().getName() + "' is already registered with this loop");
            }
        }

        final Registration registration = new Registration(this, simulation);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Removes {@code simulation} if it is registered.
     *
     * @return whether it was registered
     */
    public boolean unregister(@NotNull ModelSimulation simulation) {
        Objects.requireNonNull(simulation, "simulation");
        for (final Registration registration : this.registrations) {
            if (registration.simulation == simulation) {
                registration.unregister();
                return true;
            }
        }
        return false;
    }

    /** How many simulations this loop is stepping. */
    public int size() {
        return this.registrations.size();
    }

    /**
     * Starts the single repeating task.
     *
     * <p>Safe to call from {@code onEnable()}: see {@link AsyncSimulationScheduler}. The initial delay
     * equals the period so that the first iteration has a full period's worth of elapsed time to
     * measure, rather than running immediately and measuring nothing.
     *
     * @throws IllegalStateException if already running, which would leave two tasks stepping the same
     *                               simulations concurrently — the one thing the serialisation
     *                               guarantee does not cover
     */
    public synchronized void start() {
        if (this.task != null) {
            throw new IllegalStateException("The simulation loop is already running");
        }
        schedule(this.settings.current().periodNanos());
    }

    /** Dispatches the repeating task at {@code periodNanos} and records what it was scheduled at. */
    private synchronized void schedule(long periodNanos) {
        // Nanoseconds rather than milliseconds: 60 Hz is 16.67 ms, and rounding that to a whole
        // millisecond is a 2% rate error in whichever direction the rounding went.
        this.scheduledPeriodNanos = periodNanos;
        this.task = this.scheduler.runAtFixedRate(
                this::iterate, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Cancels the repeating task. Idempotent, and must be the first statement of {@code onDisable()} —
     * see the class javadoc.
     *
     * <p>Leaves the registrations alone: stopping the loop is not unbinding the models, and a loop can
     * be started again afterwards.
     *
     * <p>Does not wait for an iteration already in flight; see {@link SimulationTask#cancel()}.
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

    /** Steps per second, as currently configured. */
    public int simulationHz() {
        return this.settings.current().simulationHz();
    }

    /**
     * The configured period, in nanoseconds. A floor on the interval between iterations, not a promise.
     *
     * <p>This is what the loop <em>should</em> be running at. {@link #scheduledPeriodNanos()} is what it
     * actually was dispatched at, and the two differ exactly between a reload and the {@link #reload()}
     * that acts on it.
     */
    public long periodNanos() {
        return this.settings.current().periodNanos();
    }

    /** The period the live task was dispatched at, or the last one if the loop is stopped. */
    public synchronized long scheduledPeriodNanos() {
        return this.scheduledPeriodNanos;
    }

    /**
     * Applies a configuration change: rebuilds every registered simulation's solver state, and
     * re-dispatches the task if the rate moved.
     *
     * <h2>Why this is explicit rather than detected per iteration</h2>
     * Two things a reload invalidates cannot be fixed from inside a step. The scheduled period is one:
     * the platform owns the repeating task's rate, so the only way to change it is to cancel and
     * dispatch again, which a loop cannot do from within its own body. The other is the reporting. Every
     * live {@link pandastic.studio.models.api.physics.PhysicsState} belongs to the solver that created
     * it, and building a replacement is where a calibration that would diverge for a given rig is
     * refused — so doing it here surfaces that refusal to whoever triggered the reload, naming the rig,
     * while they are still looking. Left to the next step it would surface sixteen milliseconds later
     * inside the loop's per-simulation catch, as one throttled line about a model that then silently
     * keeps running on its old state.
     *
     * <p>A simulation whose rebuild fails is reported and <strong>left on its previous state</strong>:
     * it keeps moving under the old calibration, which is wrong but continuous, rather than being left
     * with no state at all. Every other simulation is still rebuilt — one rig with tuning the new
     * calibration cannot integrate must not cost the rest of the server its reload.
     *
     * <p>Idempotent and safe to call on a stopped loop, which rebuilds the states and schedules nothing.
     *
     * <p>Individual failures go through the loop's rate-limited channel, because a reload of a server
     * whose whole calibration no longer fits its rigs would otherwise be thousands of stack traces. So
     * the <em>count</em> is the reliable signal: a caller that compares it against {@link #size()} knows
     * a reload was partial even when the throttle swallowed most of the detail.
     *
     * @return how many simulations were rebuilt
     */
    public int reload() {
        final SimulationSettings settings = this.settings.current();

        int rebuilt = 0;
        for (final Registration registration : this.registrations) {
            if (!registration.active) {
                continue;
            }
            try {
                registration.simulation.rebuild();
                rebuilt++;
            } catch (final Throwable thrown) {
                this.errors.error(
                        "Rig '" + registration.simulation.rig().getName()
                                + "' could not be rebuilt for the reloaded physics calibration;"
                                + " it keeps running on the previous one",
                        thrown);
            }
        }

        synchronized (this) {
            if (this.task != null && this.scheduledPeriodNanos != settings.periodNanos()) {
                // Cancel before dispatching, never the other way round: two live tasks would step the
                // same simulations concurrently, which is the one thing the serialisation guarantee
                // does not cover.
                final SimulationTask running = this.task;
                this.task = null;
                running.cancel();
                schedule(settings.periodNanos());
            }
        }
        return rebuilt;
    }

    /**
     * The scheduled body: one iteration, with a last-resort guard.
     *
     * <p>{@link #runIteration()} already isolates each simulation, so this outer catch only fires for a
     * failure in the loop's own machinery — the clock throwing, or an {@link OutOfMemoryError} raised
     * between two simulations. Rare, but the alternative is an unlogged throw into a platform that
     * swallows it and schedules the next iteration anyway, i.e. a loop that silently does nothing.
     */
    private void iterate() {
        try {
            runIteration();
        } catch (final Throwable thrown) {
            this.errors.error("The simulation loop failed outside any single model", thrown);
        }
    }

    /**
     * One pass over every registered simulation. Package-private so tests can drive it directly.
     *
     * <p>A single clock reading serves the whole pass. Each simulation differences it against its own
     * previous reading, so a long iteration does not accumulate error: the models stepped late this
     * iteration simply measure a correspondingly longer interval next time.
     */
    void runIteration() {
        final long nowNanos = this.clock.nanoTime();

        for (final Registration registration : this.registrations) {
            // Checked inside the loop rather than relying on removal from the list: this iteration
            // holds a snapshot taken before the body ran, so a simulation unregistered by an earlier
            // simulation's sink is still in it. Without the flag it would be stepped once more after
            // being unregistered, which for a model whose entity has just been removed is exactly the
            // step that reaches a sink with nothing left to write to.
            if (!registration.active) {
                continue;
            }
            try {
                registration.simulation.step(nowNanos);
            } catch (final Throwable thrown) {
                this.errors.error(
                        "Rig '" + registration.simulation.rig().getName()
                                + "' threw while stepping; it stays registered and will be retried",
                        thrown);
            }
        }
    }

    /**
     * A simulation's membership in a loop, and the cheap way back out of it.
     *
     * <p>Handed out by {@link #register} so that unbinding a model is a flag write and a list removal
     * rather than a scan, and so that the same simulation cannot be unregistered through a stale
     * reference to a membership it no longer has.
     */
    public static final class Registration {

        private final @NotNull SimulationLoop loop;
        private final @NotNull ModelSimulation simulation;

        /**
         * Volatile because it is written by whichever thread unbinds the model and read by the
         * simulation thread on every iteration.
         */
        private volatile boolean active = true;

        private Registration(@NotNull SimulationLoop loop, @NotNull ModelSimulation simulation) {
            this.loop = loop;
            this.simulation = simulation;
        }

        /** The simulation this membership is for. */
        public @NotNull ModelSimulation simulation() {
            return this.simulation;
        }

        /** Whether the loop is still stepping it. */
        public boolean isRegistered() {
            return this.active;
        }

        /**
         * Stops the loop stepping this simulation. Idempotent, and safe to call from inside an
         * iteration — including from a sink, and including for a different simulation than the one
         * being stepped.
         */
        public void unregister() {
            if (!this.active) {
                return;
            }
            // Flag first, list second. An iteration already in flight is holding a snapshot that still
            // contains this registration, and the flag is the only thing that can stop it being
            // stepped; reversing these two lines would let that happen.
            this.active = false;
            this.loop.registrations.remove(this);
        }
    }
}
