package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;

import java.util.Objects;

/**
 * One model's physics: a rig, its pose, its solver state, the latest driver sample, and where the
 * finished pose goes.
 *
 * <p>Everything a step needs is here and nothing else can reach it, which is what lets
 * {@link SimulationLoop} run thousands of these from a single task and still treat each one as
 * independent. A simulation touches no entity, no world and no renderer: the owner's movement arrives
 * through {@link #driver()} as an immutable value a region thread published, and the result leaves
 * through a {@link PoseSink}.
 *
 * <p>Not thread-safe, and does not need to be. The loop's iterations are strictly serialised, so a
 * simulation is only ever stepped by one thread at a time — but not necessarily the <em>same</em>
 * thread twice, which is why none of this state may live in a {@link ThreadLocal}.
 *
 * <h2>Clearing the base pose is this class's job</h2>
 * Secondary motion <strong>adds</strong> to the pose, because it layers on animation rather than
 * replacing it, which makes rewriting the base pose each step the caller's responsibility. This is that
 * caller. Skip it and the solver's own output is fed back in as input every step: offsets compound, a
 * bone winds itself up over a few seconds, and nothing in the solver can detect it.
 *
 * <h2>At rest means silent</h2>
 * See {@link #step(long)}. This is the single biggest bandwidth lever in the design.
 *
 * <h2>One settings snapshot per step</h2>
 * The solver, the cadence and the interpolation window all come from a {@link SimulationSettingsSource},
 * read <strong>exactly once</strong> at the top of {@link #step(long)}. That is the same discipline the
 * driver sample already follows and for the same reason: reading the reference twice would let a reload
 * land between two reads, so the step could decide to emit on one cadence and then send the window of
 * another, or — worse — hand a state built by one solver to a different one.
 *
 * <p>A calibration change therefore has to invalidate state, and does: see {@link #rebuild()}.
 */
public final class ModelSimulation {

    /**
     * Nanoseconds in a second.
     *
     * <p><strong>A unit definition, not a tunable.</strong> The clock reports nanoseconds and the solver
     * wants seconds; this is the SI conversion between them and nothing an operator could set.
     */
    private static final float NANOS_PER_SECOND = 1_000_000_000F;

    private final @NotNull Rig rig;
    private final @NotNull SimulationSettingsSource settings;
    private final @NotNull Pose pose;
    private final @NotNull PoseSink sink;
    private final @NotNull DriverHolder driver;

    /**
     * The solver that created {@link #state}, and the thing a reload is detected by.
     *
     * <p>Compared by identity against the snapshot's solver at the top of every step. A calibration
     * reload replaces the solver — it cannot mutate one, since a solver is immutable so that a single
     * instance can serve the whole server — so a new reference <em>is</em> the signal that every live
     * state was built for a step size that no longer applies.
     */
    private @NotNull PhysicsSolver solver;

    /** Rebuilt whenever {@link #solver} changes; see {@link #rebuild()}. */
    private @NotNull PhysicsState state;

    /** The walk cycle, or {@code null} for a model with no locomotion — a decoration, say. */
    private final @Nullable LocomotionStage locomotion;

    /** Steps taken, and therefore the cadence's phase. */
    private long stepIndex;

    /** The clock reading of the previous step; meaningless until {@link #measured} is true. */
    private long previousNanos;
    private boolean measured;

    /** Whether the settled pose has already gone out for the current period of rest. */
    private boolean restEmitted;

    /** A simulation with its own {@link DriverHolder}. */
    public ModelSimulation(
            @NotNull Rig rig,
            @NotNull SimulationSettingsSource settings,
            @NotNull PoseSink sink
    ) {
        this(rig, settings, sink, new DriverHolder());
    }

    /**
     * A simulation sharing {@code driver} with whatever samples the entity.
     *
     * @throws IllegalArgumentException if any bone's tuning would diverge at the solver's step size —
     *                                 raised by {@link PhysicsSolver#createState}, and deliberately
     *                                 raised at bind time rather than tolerated at runtime
     */
    public ModelSimulation(
            @NotNull Rig rig,
            @NotNull SimulationSettingsSource settings,
            @NotNull PoseSink sink,
            @NotNull DriverHolder driver
    ) {
        this(rig, settings, sink, driver, null);
    }

    /**
     * A simulation that also walks.
     *
     * @param settings   where the solver, the cadence and the emission window are read from, once per
     *                   step. A model that needs its own emission rate gets its own source
     * @param locomotion the walk cycle, or {@code null} for a model with none
     * @throws IllegalArgumentException if any bone's tuning would diverge at the solver's step size, or
     *                                 if {@code locomotion}'s binding expects a different bone count
     *                                 from {@code rig}
     */
    public ModelSimulation(
            @NotNull Rig rig,
            @NotNull SimulationSettingsSource settings,
            @NotNull PoseSink sink,
            @NotNull DriverHolder driver,
            @Nullable LocomotionStage locomotion
    ) {
        this.locomotion = locomotion;
        this.rig = Objects.requireNonNull(rig, "rig");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.solver = settings.current().solver();
        this.state = this.solver.createState(rig);
        this.pose = new Pose(rig.boneCount());
    }

    /**
     * Advances the simulation to {@code nowNanos} and emits if this step is an emission step.
     *
     * <h2>Measured time, never a nominal timestep</h2>
     * The delta is the difference between this clock reading and the previous one. It is not
     * {@code 1 / rate}: the scheduler drops missed iterations instead of replaying them, so after a
     * stall the nominal timestep and the real one disagree, and a spring integrated at the wrong speed
     * either rings or crawls. The first step measures zero elapsed time — there is nothing to difference
     * against, and the solver treats a non-positive delta as a no-op — so a simulation constructed long
     * before the loop starts does not open with a jump the size of the gap.
     *
     * <h2>Why a resting simulation emits nothing, except once</h2>
     * A bone whose transform did not change needs no packet, and in a resting rig that is every bone.
     * Skipping those emissions is what makes a decorative model cost nothing instead of its full bone
     * count forever, which on a server with hundreds of them is the difference between viable and not.
     *
     * <p>But the <em>first</em> resting step still has to go out. The solver reaches rest by settling,
     * so the last pose the client was sent is the one before the settling, and going silent one update
     * early leaves every bone stopped a fraction short of where the physics actually ended — a
     * permanent, small, unexplainable offset that only ever resolves when the model next moves. So the
     * step that discovers rest emits, and only the ones after it are skipped. Motion re-arms it, so each
     * period of rest gets exactly one settling update.
     *
     * <p>The flag is set only <em>after</em> the sink returns, so a sink that throws on the settling
     * update gets another chance on the next emission step rather than silently costing the model its
     * final pose.
     *
     * <h2>Why the settings snapshot is read before anything else</h2>
     * One read, at the top, used for the whole step. The step's two decisions that depend on
     * configuration — which solver integrates, and whether and with what window to emit — have to agree
     * with each other, and a second read of the source could return a different snapshot. The guard that
     * follows it is not cosmetic either: a reloaded calibration arrives as a new solver, and a
     * {@link PhysicsState} carries limits derived from the step size of the solver that built it, so
     * handing a stale state to a new solver integrates at one rate against bounds computed for another.
     * That does not throw; it just produces motion nobody can account for.
     *
     * @param nowNanos a reading from the loop's {@link SimulationClock}
     */
    public void step(long nowNanos) {
        // ONE read, before anything else. See the note above.
        final SimulationSettings settings = this.settings.current();
        if (settings.solver() != this.solver) {
            rebuild(settings);
        }

        final float deltaSeconds = measure(nowNanos);

        // Read the published sample once, so both stages see the same snapshot. Reading it twice would
        // let a region thread's update land between them and have the gait and the secondary motion
        // disagree about how fast the owner is moving within a single step.
        final PhysicsDriver sample = this.driver.current();

        // The caller's job, and this is the caller. See the class javadoc.
        this.pose.reset();

        // Locomotion first: it writes absolute values and establishes the base pose. Physics then adds
        // secondary motion on top. See LocomotionStage for why this order is not negotiable.
        if (this.locomotion != null) {
            this.locomotion.advance(this.pose, deltaSeconds, sample);
        }

        this.solver.solve(this.rig, this.state, this.pose, deltaSeconds, sample);

        // Both layers have to be settled. Physics reaching rest while the gait is mid-stride is not an
        // edge case — it is what a model walking at constant speed looks like — and treating it as rest
        // would stop the packets with the legs still visibly swinging.
        final boolean atRest = this.state.isAtRest()
                && (this.locomotion == null || this.locomotion.isSettled());
        if (!atRest) {
            this.restEmitted = false;
        }

        final EmissionCadence cadence = settings.cadence();
        final boolean emissionStep = cadence.shouldEmit(this.stepIndex++);
        if (!emissionStep || (atRest && this.restEmitted)) {
            return;
        }

        this.sink.accept(this.rig, this.pose, cadence.interpolationTicks());
        if (atRest) {
            this.restEmitted = true;
        }
    }

    /**
     * Returns the simulation to its authored rest pose and re-arms the settling emission.
     *
     * <p>For a model being rebound or having physics disabled. Note this snaps — blending out belongs to
     * whichever layer decided to stop simulating, because a settling bone reads as the model relaxing
     * whereas a reset one reads as a glitch.
     *
     * <p>Also drops the measured timestamp, so the next step measures zero rather than the whole gap,
     * and restarts the cadence phase, so the rebound model emits immediately instead of waiting out the
     * remainder of an interval it was not part of.
     */
    public void reset() {
        this.state.reset();
        if (this.locomotion != null) {
            this.locomotion.reset();
        }
        this.pose.reset();
        this.stepIndex = 0L;
        this.measured = false;
        this.restEmitted = false;
    }

    /**
     * Discards the solver state and builds a fresh one from the current snapshot's solver.
     *
     * <h2>Why a rate change cannot keep the old state</h2>
     * A {@link PhysicsState} is created by a solver and is private to it: the angular one precomputes
     * per-bone limits from the step size and holds a fractional-time accumulator measured in it. Change
     * {@code substepRateHz} and every one of those numbers is for a step that no longer exists — so the
     * state is not merely stale, it is calibrated for a different integrator. Nothing throws; the bones
     * simply move at the wrong speed and settle at the wrong angles.
     *
     * <p>So this is the explicit path a reload takes, driven by {@link SimulationLoop#reload()} over
     * every registered simulation. {@link #step(long)} carries the same check as a safety net, but the
     * explicit call is what makes the failure visible: {@link PhysicsSolver#createState} is where a
     * tuning that would diverge for this rig is refused, and a reload wants that refusal while the
     * operator is still looking at the console, not sixteen milliseconds later inside the loop's
     * catch-all.
     *
     * <p>Rebuilding snaps the model back to its rest pose and restarts the cadence phase, exactly as
     * {@link #reset()} does — a rebuilt state has no velocity to blend out of, and pretending otherwise
     * would mean keeping numbers from the old calibration.
     *
     * @throws IllegalArgumentException if the new calibration cannot integrate this rig's tuning
     */
    public void rebuild() {
        rebuild(this.settings.current());
    }

    /** {@link #rebuild()} against a snapshot the caller has already read. */
    private void rebuild(@NotNull SimulationSettings settings) {
        final PhysicsSolver replacement = settings.solver();
        // Built before anything is dropped: if the new calibration refuses this rig, the simulation
        // keeps running on the old state rather than being left with none.
        final PhysicsState rebuilt = replacement.createState(this.rig);
        this.solver = replacement;
        this.state = rebuilt;
        reset();
    }

    /**
     * This model's walk cycle, or {@code null} if it has none.
     *
     * <p>The caller reaches through this to set the stance flags — sneaking, airborne — which are
     * inputs a region thread knows and the simulation thread must not go looking for.
     */
    public @Nullable LocomotionStage locomotion() {
        return this.locomotion;
    }

    /** The rig this simulation drives. Its name is what failures are reported against. */
    public @NotNull Rig rig() {
        return this.rig;
    }

    /**
     * The cadence this simulation emits on, as of right now.
     *
     * <p>A fresh read of the source, so it follows a reload. Callers that need the cadence and
     * something else from the same snapshot must not reach it through here twice — that is the
     * multiple-read bug this accessor cannot protect them from.
     */
    public @NotNull EmissionCadence cadence() {
        return this.settings.current().cadence();
    }

    /** The solver that created the live state. Changes when a reload replaces the calibration. */
    public @NotNull PhysicsSolver solver() {
        return this.solver;
    }

    /** Where a region thread publishes the owner's movement. See {@link DriverHolder}. */
    public @NotNull DriverHolder driver() {
        return this.driver;
    }

    /**
     * Whether everything has stopped changing, and therefore whether this model is costing any
     * bandwidth. Both the physics and the gait have to be settled.
     */
    public boolean isAtRest() {
        return this.state.isAtRest()
                && (this.locomotion == null || this.locomotion.isSettled());
    }

    /** How many steps have been taken. The cadence's phase, and a diagnostic. */
    public long stepCount() {
        return this.stepIndex;
    }

    /**
     * Seconds since the previous step, or zero for the first one.
     *
     * <p>A non-positive difference — a clock that did not advance within its own resolution, which on
     * Windows covers a whole 16 ms period — is reported as zero rather than clamped to a minimum. The
     * solver banks fractional time and discards nothing, so the missing interval is picked up by the
     * next step that does advance.
     */
    private float measure(long nowNanos) {
        if (!this.measured) {
            this.measured = true;
            this.previousNanos = nowNanos;
            return 0F;
        }
        final long elapsed = nowNanos - this.previousNanos;
        this.previousNanos = nowNanos;
        if (elapsed <= 0L) {
            return 0F;
        }
        return elapsed / NANOS_PER_SECOND;
    }
}
