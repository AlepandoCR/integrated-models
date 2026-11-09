package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.internal.sim.DriverSampler;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Publishes every live decoration's driver from <strong>one</strong> repeating off-tick task: the
 * decoration layer's answer to the entity sampler.
 *
 * <h2>Why a decoration needs a sampler at all</h2>
 * An entity-backed model is driven by its entity, read once per tick on the region thread that owns it.
 * A decoration has no entity, so there is nothing to read — and nothing to drive it either, which is why
 * a decoration without this loop is a model that hangs perfectly still forever. This loop is where its
 * motion comes from, and it is the exact analogue of that per-entity sampler with two differences, both
 * of which are improvements:
 *
 * <ul>
 *   <li><strong>One task for the whole server, not one per model.</strong> A per-entity sampler has to be
 *       the entity's own scheduler, because only that dispatcher follows an entity across regions. A
 *       decoration's position is this plugin's own value and its driver is pure arithmetic over numbers,
 *       so a flat iteration costs what the decorations cost rather than what the scheduler costs. A
 *       server with three hundred decorations runs one task, not three hundred.</li>
 *   <li><strong>No region thread.</strong> Nothing in a pass touches an entity, a world or a block: it
 *       reads a published placement, evaluates a closed-form field and stores an immutable value. That is
 *       what makes a decoration cheap in the way this whole layer exists to be cheap.</li>
 * </ul>
 *
 * <h2>What one pass publishes</h2>
 * The sum of two velocities, both relative to the same air, which is why adding them is correct rather
 * than convenient:
 *
 * <ol>
 *   <li><strong>Movement.</strong> The difference between this pass's placement and the previous one,
 *       scaled to blocks per tick and filtered by the same teleport thresholds the entity path uses. This
 *       is what makes a carried lantern swing and what re-arms emission after a move — and it is why a
 *       move does not publish anything itself: a caller that published its own impulse would have it
 *       overwritten by the next pass, which is a dropped impulse nobody could reproduce.</li>
 *   <li><strong>Wind.</strong> {@link AmbientWind}, evaluated at that placement and at a clock reading
 *       shared by every decoration in the pass, so the field is coherent across the world.</li>
 * </ol>
 *
 * <p>When both are zero the published value is {@link PhysicsDriver#STILL} itself — the shared constant,
 * so a calm server allocates nothing — and a decoration published a motionless driver settles and goes
 * silent. That identity is the single most important property of this class: <em>a decoration nobody
 * moves and no wind reaches costs zero packets per second</em>, and it holds because the loop keeps
 * publishing stillness rather than because it stops publishing.
 *
 * <h2>Why it keeps publishing for a still decoration</h2>
 * It would be tempting to hold only the decorations something drives. The cost of that is an impulse
 * that never decays: a decoration moved once would keep the velocity of that move forever, so its bones
 * would deflect and settle at a permanent tilt — a banner leaning in a wind that stopped minutes ago.
 * Publishing stillness is what ends a move, and it costs one store of a shared constant.
 *
 * <h2>Threading</h2>
 * The body runs on the platform async scheduler. Iterations are strictly serialised, so a registration's
 * remembered placement is plain state rather than synchronised — the scheduler establishes
 * happens-before between consecutive executions, which is the same property {@code ModelSimulation}
 * relies on. There is no thread affinity, so nothing here caches per thread.
 *
 * <h2>Shutdown</h2>
 * {@link #stop()} must be called on disable, alongside the simulation loop's. Region- and
 * entity-scheduled repeating tasks are never swept for a plugin, and the plugin's classloader is closed
 * before the platform cancels anything, so a live task holding these classes fails on its first lazy
 * class load after the close — during shutdown, attributable to nothing.
 */
public final class DecorationDriverLoop {

    /**
     * The neutral scale between a pass and a tick.
     *
     * <p><strong>An identity, not a tunable.</strong> At the rate where one pass covers one tick the
     * movement needs no conversion, and comparing against it is what keeps the common configuration free
     * of a multiply and an allocation.
     */
    private static final double UNSCALED = 1D;

    /**
     * Nanoseconds in a second.
     *
     * <p><strong>A unit definition.</strong> The clock reports nanoseconds and the field is a function
     * of seconds.
     */
    private static final double NANOS_PER_SECOND = 1_000_000_000D;

    private final @NotNull SimulationScheduler scheduler;
    private final @NotNull SimulationClock clock;
    private final @NotNull DecorationSettingsSource settings;
    private final @NotNull SimulationSettingsSource simulation;

    /**
     * Copy-on-write for the same reason the simulation loop's registration list is: it is iterated on
     * every pass, dozens of times a second, while a decoration is placed or torn down rarely and from
     * another thread. The snapshot a pass holds is also what makes a teardown mid-pass safe.
     */
    private final @NotNull List<Registration> registrations = new CopyOnWriteArrayList<>();

    /** Guarded by {@code this}, alongside {@link #scheduledPeriodNanos}. */
    private @Nullable SimulationTask task;

    /**
     * The period the live task was dispatched at, which is what {@link #reload()} compares against. A
     * repeating task's rate belongs to the platform, so the configured period and the dispatched one
     * differ exactly between a reload and the call that acts on it.
     */
    private long scheduledPeriodNanos;

    /**
     * The clock reading the field's phase is measured from.
     *
     * <p>Taken on the first pass rather than at construction or at {@link #start()}, so the field starts
     * at a known phase however long the loop sat idle, and deliberately <strong>not</strong> reset by
     * {@link #reload()}: a reload that restarted the phase would make every banner on the server jump at
     * once, which is a far louder symptom than whatever the operator was tuning.
     */
    private long originNanos;

    private boolean measured;

    /**
     * @param scheduler  where the repeating task comes from
     * @param clock      the clock the ambient field's phase is measured against
     * @param settings   the field and the pass rate, read once per pass
     * @param simulation where the teleport thresholds come from. They live in {@code simulation.yml}
     *                   because what counts as a teleport is a property of the server rather than of
     *                   decorations, and a decoration moved by a plugin can jump exactly as far as an
     *                   entity moved by one
     */
    public DecorationDriverLoop(
            @NotNull SimulationScheduler scheduler,
            @NotNull SimulationClock clock,
            @NotNull DecorationSettingsSource settings,
            @NotNull SimulationSettingsSource simulation
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    /** A loop on the real clock. */
    public DecorationDriverLoop(
            @NotNull SimulationScheduler scheduler,
            @NotNull DecorationSettingsSource settings,
            @NotNull SimulationSettingsSource simulation
    ) {
        this(scheduler, SimulationClock.SYSTEM, settings, simulation);
    }

    /**
     * Adds {@code decoration} to the set this loop drives.
     *
     * <p>Its first pass publishes stillness rather than a movement: there is no previous placement to
     * difference against, and inventing one would make a decoration's first moment of physics depend on
     * where it happened to be placed.
     *
     * @param decoration what to drive
     * @return a handle to unregister with
     * @throws IllegalStateException if it is already registered, which would publish twice per pass and
     *                               leave the second publication differencing against the first
     */
    public @NotNull Registration register(@NotNull DrivenDecoration decoration) {
        Objects.requireNonNull(decoration, "decoration");
        // Linear, and that is fine: registration happens once per decoration placed, not per pass.
        for (final Registration existing : this.registrations) {
            if (existing.decoration == decoration) {
                throw new IllegalStateException(
                        "This decoration is already registered with the driver loop");
            }
        }
        final Registration registration = new Registration(this, decoration);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Removes {@code decoration} if it is registered.
     *
     * @param decoration what to stop driving
     * @return whether it was registered
     */
    public boolean unregister(@NotNull DrivenDecoration decoration) {
        Objects.requireNonNull(decoration, "decoration");
        for (final Registration registration : this.registrations) {
            if (registration.decoration == decoration) {
                registration.unregister();
                return true;
            }
        }
        return false;
    }

    /** How many decorations this loop is driving. */
    public int size() {
        return this.registrations.size();
    }

    /**
     * Starts the single repeating task.
     *
     * <p>The initial delay equals the period, so the first pass has a full period behind it rather than
     * running immediately and differencing a placement against itself.
     *
     * @throws IllegalStateException if already running, which would have two passes differencing the
     *                               same placements and publishing half-intervals
     */
    public synchronized void start() {
        if (this.task != null) {
            throw new IllegalStateException("The decoration driver loop is already running");
        }
        schedule(this.settings.current().periodNanos());
    }

    /**
     * Cancels the repeating task. Idempotent, and must be called on disable — see the class javadoc.
     *
     * <p>Leaves the registrations alone: stopping the loop is not unbinding the decorations, and a loop
     * can be started again afterwards. What it does mean is that nothing publishes stillness any more,
     * so a decoration mid-gust keeps the last driver it was given; the simulation is being stopped at
     * the same moment for the same reason, which is why that does not matter.
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

    /**
     * The configured period, in nanoseconds. What the loop <em>should</em> run at.
     *
     * @return the configured pass interval
     */
    public long periodNanos() {
        return this.settings.current().periodNanos();
    }

    /** The period the live task was dispatched at, or the last one if the loop is stopped. */
    public synchronized long scheduledPeriodNanos() {
        return this.scheduledPeriodNanos;
    }

    /**
     * Re-dispatches the task if configuration moved its rate.
     *
     * <p>Explicit rather than detected per pass, for the same reason {@code SimulationLoop.reload()} is:
     * a repeating task's rate belongs to the platform, so changing it means cancelling and dispatching
     * again, which a task cannot do to itself from inside its own body. Everything else a reload can
     * move — every coefficient of the field — is read fresh at the top of every pass and needs no
     * action at all.
     *
     * <p>Cancels before dispatching, never the other way round: two live passes would both difference
     * against the same remembered placement.
     *
     * @return whether the task was re-dispatched
     */
    public synchronized boolean reload() {
        final long periodNanos = this.settings.current().periodNanos();
        if (this.task == null || periodNanos == this.scheduledPeriodNanos) {
            return false;
        }
        final SimulationTask running = this.task;
        this.task = null;
        running.cancel();
        schedule(periodNanos);
        return true;
    }

    private synchronized void schedule(long periodNanos) {
        this.scheduledPeriodNanos = periodNanos;
        this.task = this.scheduler.runAtFixedRate(
                this::runPass, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * One pass over every registered decoration. Package-private so tests can drive it directly.
     *
     * <p>Both configuration sources are read <strong>once</strong> here and used for the whole pass. A
     * read per decoration could straddle a reload, and because the gusts are a function of position the
     * symptom would be a seam in the weather between two neighbouring banners rather than anything that
     * looked like a configuration change.
     *
     * <p>One clock reading serves the pass for the same reason: the field is meant to be coherent across
     * the world at an instant, and a reading per decoration would smear that instant across however long
     * the pass takes.
     */
    void runPass() {
        final DecorationSettings field = this.settings.current();
        final DriverBounds bounds = this.simulation.current().driverBounds();
        final double elapsedSeconds = elapsedSeconds(this.clock.nanoTime());
        final double scale = field.passToTickScale();
        // A delta measured over one pass is judged against thresholds covering one pass, so the filter
        // asks the same question at every configured rate: "could this have been real movement".
        final DriverBounds passBounds = scale == UNSCALED ? bounds : new DriverBounds(
                (float) (bounds.maxStepBlocks() / scale),
                (float) (bounds.maxVerticalStepBlocks() / scale));

        for (final Registration registration : this.registrations) {
            // Checked inside the loop rather than relying on removal: this pass holds a snapshot taken
            // before the body ran, so a decoration torn down by another thread is still in it.
            if (!registration.active) {
                continue;
            }
            registration.publish(field, passBounds, scale, elapsedSeconds);
        }
    }

    /**
     * Seconds since the first pass, which is the phase the whole field is evaluated at.
     *
     * <p>The first pass reports zero rather than a difference against an unrelated clock origin, so a
     * decoration placed at start-up sees the field at a defined phase.
     */
    private double elapsedSeconds(long nowNanos) {
        if (!this.measured) {
            this.measured = true;
            this.originNanos = nowNanos;
            return 0D;
        }
        return (nowNanos - this.originNanos) / NANOS_PER_SECOND;
    }

    /**
     * One decoration's membership in a loop, its remembered placement, and the cheap way back out.
     *
     * <p>The remembered placement is what makes a move into a velocity, and it lives here rather than on
     * the decoration because it is the <em>loop's</em> state: it means "the placement this loop last
     * published from", which is a different fact from "where the decoration is" and would be wrong the
     * moment the decoration was registered with a second loop.
     */
    public static final class Registration {

        private final @NotNull DecorationDriverLoop loop;
        private final @NotNull DrivenDecoration decoration;

        /**
         * Volatile because it is written by whichever thread tears the decoration down and read on
         * every pass.
         */
        private volatile boolean active = true;

        /**
         * The placement the previous pass published from, or {@code null} before the first one.
         *
         * <p>Plain rather than volatile: passes are strictly serialised and the scheduler establishes
         * happens-before between them, which is the same guarantee the simulation's own per-model state
         * relies on.
         */
        private @Nullable DecorationPlacement previous;

        private Registration(@NotNull DecorationDriverLoop loop, @NotNull DrivenDecoration decoration) {
            this.loop = loop;
            this.decoration = decoration;
        }

        /** The decoration this membership is for. */
        public @NotNull DrivenDecoration decoration() {
            return this.decoration;
        }

        /** Whether the loop is still driving it. */
        public boolean isRegistered() {
            return this.active;
        }

        /**
         * Stops the loop driving this decoration. Idempotent, and safe to call from inside a pass.
         *
         * <p>Does not publish a final stillness. The caller is tearing the decoration down, which stops
         * its simulation first — so there is nobody left to read a driver, and the holder is reset by
         * that teardown anyway.
         */
        public void unregister() {
            if (!this.active) {
                return;
            }
            // Flag first, list second. A pass already in flight holds a snapshot that still contains
            // this registration, and the flag is the only thing that can stop it being driven.
            this.active = false;
            this.loop.registrations.remove(this);
        }

        /**
         * Publishes this decoration's driver for one pass.
         *
         * <p>The placement is read exactly once and serves both halves: the movement differenced against
         * the previous pass, and the phase of the gust standing at that position. Reading it twice would
         * let a move land between them.
         */
        private void publish(
                @NotNull DecorationSettings field,
                @NotNull DriverBounds passBounds,
                double scale,
                double elapsedSeconds
        ) {
            final DecorationPlacement now = this.decoration.placement();
            final PhysicsDriver movement = movementTo(now, passBounds, scale);
            this.previous = now;

            final PhysicsDriver wind = AmbientWind.driverAt(
                    field, this.decoration.ambientMotion(), now, elapsedSeconds);
            this.decoration.driver().publish(sum(movement, wind));
        }

        /** The movement since the previous pass, in blocks per tick, teleports filtered out. */
        private @NotNull PhysicsDriver movementTo(
                @NotNull DecorationPlacement now,
                @NotNull DriverBounds passBounds,
                double scale
        ) {
            final DecorationPlacement before = this.previous;
            if (before == null || before.equals(now)) {
                // The common case by a wide margin: a decoration nobody moved. Answered without
                // arithmetic and without allocation, which is what makes a still decoration free.
                return PhysicsDriver.STILL;
            }
            final PhysicsDriver perPass = DriverSampler.difference(
                    before.x(), before.y(), before.z(), before.yawDegrees(),
                    now.x(), now.y(), now.z(), now.yawDegrees(), passBounds);
            if (scale == UNSCALED) {
                return perPass;
            }
            final float factor = (float) scale;
            return new PhysicsDriver(
                    perPass.forwardSpeed() * factor,
                    perPass.sideSpeed() * factor,
                    perPass.verticalSpeed() * factor,
                    perPass.yawRateRadians() * factor);
        }

        /**
         * Two velocities relative to the same air, added.
         *
         * <p>Short-circuited on the shared {@link PhysicsDriver#STILL} both ways round, so the two
         * common cases — a still decoration in a calm world, and a still decoration in a gust — publish
         * either a constant or exactly the wind, with no allocation of their own.
         */
        private static @NotNull PhysicsDriver sum(
                @NotNull PhysicsDriver movement,
                @NotNull PhysicsDriver wind
        ) {
            if (movement == PhysicsDriver.STILL) {
                return wind;
            }
            if (wind == PhysicsDriver.STILL) {
                return movement;
            }
            return new PhysicsDriver(
                    movement.forwardSpeed() + wind.forwardSpeed(),
                    movement.sideSpeed() + wind.sideSpeed(),
                    movement.verticalSpeed() + wind.verticalSpeed(),
                    movement.yawRateRadians() + wind.yawRateRadians());
        }
    }
}
