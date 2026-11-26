package pandastic.studio.models.internal.vehicle;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.BoardingOutcome;
import pandastic.studio.models.api.vehicle.BoardingResult;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.PilotContext;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.SeatInput;
import pandastic.studio.models.api.vehicle.SeatInputListener;
import pandastic.studio.models.api.vehicle.SeatOccupancy;
import pandastic.studio.models.api.vehicle.SeatPlan;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleController;
import pandastic.studio.models.api.vehicle.VehicleControls;
import pandastic.studio.models.api.vehicle.VehicleMessages;
import pandastic.studio.models.api.vehicle.VehicleMove;
import pandastic.studio.models.api.vehicle.VehicleNotice;
import pandastic.studio.models.api.vehicle.VehicleNoticeContext;
import pandastic.studio.models.api.vehicle.VehiclePilot;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSeat;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.api.vehicle.VehicleState;
import pandastic.studio.models.api.vehicle.VehicleSteering;
import pandastic.studio.models.internal.input.InputFaultSink;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One server-authoritative vehicle: a root entity, a control loop on that entity's own thread, and the
 * rig riding on top of it.
 *
 * <h2>The authority model, stated once</h2>
 * The server decides where this vehicle is. Each step reads the rider's held keys, resolves them through
 * the profile's control scheme, integrates the measured elapsed time, and writes the result to the root
 * entity. No position the client sends is read, and the client is never permitted to simulate the
 * vehicle — see {@link #mount(Player)} for the check that enforces the second half, which is the one
 * that is easy to lose by accident.
 *
 * <h2>Which thread does what</h2>
 * Two threads touch a vehicle and they touch disjoint things.
 *
 * <p>The <strong>owning region thread</strong> runs the control step. It is the only thread that reads
 * the root entity, reads the rider's input, writes a position or a heading, mounts or dismounts. What
 * leaves a step is two immutable values stored into two volatile fields — the state and the resolved
 * steering — plus the rider reference. Those single stores are what carry the happens-before edge: a
 * reader that sees a new state sees all three of its components, from the same step.
 *
 * <p><strong>Any other thread</strong> may read those three values and may ask this vehicle to stop.
 * Nothing else. In particular a caller that holds the {@link Player} this vehicle reports as its rider
 * must not read that player's position from another region's thread; the reference is published so that
 * a plugin can identify the rider, not so that it can inspect them.
 *
 * <h2>Why the control step does not count ticks</h2>
 * Because the scheduler drops missed iterations rather than replaying them. A step that assumed its own
 * nominal period would make every vehicle on a loaded server cover a distance that depends on the load,
 * and would make the same vehicle behave differently on two servers with the same configuration. So each
 * step differences a monotonic clock and hands the gap to {@link VehicleStepper}, which clamps it.
 *
 * <h2>Start order</h2>
 * <ol>
 *   <li><strong>Spawn the visual.</strong> Every rig bone is resolved against the live model and the
 *       whole list of failures is reported at once. First because it is the step most likely to fail and
 *       the cheapest to unwind — nothing is scheduled yet.</li>
 *   <li><strong>Seed the heading from the entity.</strong> A vehicle starts facing wherever its root
 *       entity faces. Starting at yaw zero would spin every vehicle to due south the moment it was
 *       created, which looks like a bug in the model.</li>
 *   <li><strong>Resolve the seats and log the chart.</strong> After the visual, because a seat is a
 *       locator of a live model, and before the loop, because a seat that did not resolve has to be
 *       reported whether or not the vehicle is ever driven.</li>
 *   <li><strong>Schedule the control loop.</strong> Last, because it is the only thing that can tear the
 *       vehicle down from another direction, and it must not be able to do that to state that does not
 *       exist yet.</li>
 * </ol>
 * A failure at any point unwinds what came before it. A half-started vehicle is worse than a failed one:
 * it leaves a tracker in the world that nothing will ever close.
 *
 * <h2>Teardown order, and stopping exactly once</h2>
 * The rule is <em>stop deciding, then release what was being decided about</em>: cancel the loop, publish
 * a stopped vehicle, tell the consumer's controller, tell the pilot, then put the rider down and release
 * the visual. The controller comes before the release and not after, because
 * {@link VehicleController#onClosed} is where a controller forgets its riders and the api promises they
 * are still aboard for it; the pilot comes after the controller, because what it releases is this
 * plugin's own — an {@link pandastic.studio.models.api.vehicle.AiPilot} uninstalls its plan there, and a
 * controller that reads that plan's last decision must still be able to. Three things can decide a
 * vehicle is
 * finished and they can decide it simultaneously — the platform's retired callback, a step finding its
 * root gone, and a caller, usually the plugin shutting down — so all three go through one monitor and one
 * latch.
 *
 * <p>The retired path is the exception, and it is the platform's rule rather than ours: a retired
 * callback runs in critical code that may not remove entities, and it is unschedulable because the
 * scheduler that would run the deferred work is the one that has just retired. So that path cancels and
 * releases the visual and leaves the root's own dismount alone — the root entity is gone, so there is
 * nobody left aboard it. The <em>carriers</em> are still released, because each of them has its own
 * entity scheduler; see {@link VehicleSeats#release()}.
 *
 * <h2>Where the riders are</h2>
 * Not on the root entity. A rider sits on a <strong>locator</strong> of the model — the marker a rigger
 * placed in Blockbench — through an invisible carrier entity {@link VehicleSeats} keeps on top of it. The
 * root entity carries the hitbox, the collision and the position the server replicates, and nobody rides
 * it: a vehicle whose rig declares no usable seat cannot be ridden at all, which is deliberate rather
 * than a gap. Mounting on the root is the thing a seat on a locator replaces, and there is no fallback
 * to it, because a fallback is how the distinction would quietly be lost again.
 *
 * <p>The authority check survives that change and is applied to the <em>driver's</em> seat only. A
 * passenger whose client believes it is steering a carrier steers nothing anybody can see; a driver whose
 * client believes it is steering is the permanent rubber-banding this whole design exists to avoid.
 *
 * <h2>Boarding, and what happens when the driver leaves</h2>
 * Seats are indexed with the driver's at {@link SeatPlan#DRIVER_INDEX} and boarding takes the lowest
 * free one, so the first player to board drives and the rest fill up behind them. Full is a message, not
 * an exception: see {@link #board(Player)}.
 *
 * <p>When the driver leaves, <strong>nobody is promoted</strong>. The seat empties, the steering goes
 * neutral on the same step, the vehicle coasts to a stop under its own friction while still carrying its
 * passengers, and the next player to board gets the driver's seat because it is now the lowest free
 * index. The reasoning is on {@link pandastic.studio.models.api.vehicle.Vehicle#board(Player)}; the short
 * version is that promotion would put somebody in control without asking, would have to re-ask the
 * authority question about a seat they are already in, and would make a seat index stop meaning what a
 * consumer wired it to mean.
 *
 * <h2>One input read per rider per step</h2>
 * {@link VehicleSeats#readInputs} runs at the top of the step, before anything is decided. The driver's
 * snapshot becomes the {@link RiderIntent} the solver sees; every occupied seat's snapshot is handed to
 * that seat's {@link SeatInputListener} after the move is applied. So the steering and a consumer's
 * listener see the same input from the same instant, and a listener that reacts to the jump key cannot
 * disagree with the step that did or did not act on it.
 *
 * <h2>Where the consumer's two mechanisms run, in order</h2>
 * A consumer may supply {@link VehicleControls} — a map of per-seat listeners — or a
 * {@link VehicleController} — one object per kind of vehicle — or both. Both are driven from the same
 * pass over the same values, in one order, which is the order {@code VehicleController}'s own
 * documentation promises:
 *
 * <ol>
 *   <li>the move is applied, the model's rotation is pushed and the seats are followed, so nothing below
 *       sees a rider whose seat this step has just taken away;</li>
 *   <li>then, per occupied seat in index order, the driver first: that seat's composed
 *       {@link SeatInputListener}, then {@link VehicleController#onPlayerInput} — the same
 *       {@link SeatInput} instance for both, so they cannot disagree;</li>
 *   <li>then {@link VehicleController#onStep} once, with the measured ticks this step integrated.</li>
 * </ol>
 *
 * <p>Specific before general, which is what {@link VehicleControls#listenerFor} already does within one
 * seat — index, then name, then catch-all — with the controller as the most general case of all. The
 * whole pass is skipped when there is nothing wired, which costs one reference comparison and is the
 * common case.
 *
 * <h2>Where the intent comes from when nobody is driving</h2>
 * The solver has always read the <strong>driver's seat</strong>, and it still does. What
 * {@link VehiclePilot} adds is a source for the steps on which that seat is empty: one call, before
 * anything is decided, whose answer is fed to the same {@link VehicleSteering#of} translation a rider's
 * keys go through. So a piloted vehicle is subject to its own control scheme, its own profile and its own
 * terrain response, and the position still has exactly one writer — this step.
 *
 * <p>A rider wins, unconditionally and with no handover logic: the pilot is asked only while
 * {@link VehicleSeats#driver()} is {@code null}, so the moment somebody boards the driver's seat their
 * keys steer and the moment they leave the pilot resumes. There is never a step with two deciders,
 * because the condition is evaluated once per step on the thread that also seats people.
 *
 * <p>The pilot's own failures are contained and the vehicle coasts; unlike a {@link VehicleController} it
 * is not detached, because detaching it would leave an autonomous vehicle parked for ever with nothing
 * saying why. See {@link #pilotIntent}.
 *
 * <p>The other three hooks hang off what already noticed the thing they are about:
 * {@link VehicleController#onSeated} and {@link VehicleController#onVacated} off {@link SeatEvents},
 * after the rider's own message, and {@link VehicleController#onClosed} off {@link #stop(boolean)},
 * before the riders are put down. None of them is on the step, which is why the controller's own
 * documentation states a different thread rule for the last of them.
 */
public final class VehicleImpl implements Vehicle {

    private final @NotNull UUID uniqueIdentifier = UUID.randomUUID();
    private final @NotNull Rig rig;
    private final @NotNull VehicleProfile profile;
    private final @NotNull VehicleHost host;
    private final @NotNull VehicleVisual visual;
    private final @NotNull VehicleSettingsSource settings;
    private final @NotNull SimulationClock clock;
    private final @NotNull SimulationLogger logger;

    /** Where this vehicle's seat chart is written when it binds. */
    private final @NotNull SeatChartSink chartSink;

    /** What this vehicle says to its riders, and what each seat's inputs do. The consumer's. */
    private final @NotNull VehicleSetup setup;

    /** Where a rider's input is read, once per occupied seat per step. */
    private final @NotNull RiderInputSource inputs;

    /**
     * What steers this vehicle while the driver's seat is empty.
     *
     * <p>{@link VehiclePilot#NONE} when the consumer supplied none, which is the common case and is why
     * {@link #piloted} exists rather than a null check — the gate is one reference comparison made once,
     * at construction, against an immutable field.
     */
    private final @NotNull VehiclePilot pilot;

    /**
     * Whether {@link #pilot} is anything at all.
     *
     * <p>Asked once at construction, because the pilot is fixed for this vehicle's life. It gates the
     * whole synthetic-input path, so a vehicle with no pilot pays one boolean read per step for the seam
     * existing.
     */
    private final boolean piloted;

    /**
     * The consumer's {@link VehicleController}, contained.
     *
     * <p>{@link VehicleControllerDispatch#INERT} when the setup supplied none, which is the common case
     * and costs nothing per step. Every call into the consumer goes through this and never through
     * {@code setup.controller()} directly — that is where the exception policy lives.
     */
    private final @NotNull VehicleControllerDispatch controller;

    /**
     * Told when a seat fills or empties, which is where the messages come from.
     *
     * <p>A field holding one instance rather than {@code implements SeatEvents}, so that
     * {@code onSeated} and {@code onVacated} do not become part of this class's own surface. Allocated
     * once at construction, which is the same cost.
     */
    private final @NotNull SeatEvents seatEvents = new SeatEvents() {

        @Override
        public void onSeated(@NotNull VehicleSeat seat, @NotNull Player rider) {
            seated(seat, rider);
        }

        @Override
        public void onVacated(@NotNull VehicleSeat seat, @NotNull Player rider) {
            vacated(seat, rider);
        }
    };

    /**
     * Where each occupied seat's input is handed to the consumer's listener.
     *
     * <p>A field rather than a lambda built per step, because a control step must allocate nothing and a
     * capturing lambda in the step body would allocate once per step per vehicle.
     */
    private final @NotNull VehicleSeats.SeatVisitor inputVisitor = this::dispatchSeatInput;

    /** Every seat of this vehicle, the riders in them and the carriers they ride. */
    private final @NotNull VehicleSeats seats;

    /**
     * The consumer's listener per seat index, resolved once when the seats are.
     *
     * <p>Resolved at start rather than per step because {@link VehicleControls#listenerFor} composes,
     * and composing allocates. {@code null} at an index means that seat has nothing wired, which is the
     * common case and the one the dispatch skips without building a {@link SeatInput} at all.
     *
     * <p>Empty until {@link #start()}, and region-confined thereafter: written by the start and read by
     * the step, both on the thread that owns the root entity.
     */
    private @Nullable SeatInputListener @NotNull [] seatListeners = new SeatInputListener[0];

    /** Told once, when this vehicle has finished tearing down. See {@link VehicleBinderImpl}. */
    private final @NotNull Consumer<VehicleImpl> onStopped;

    /**
     * Reused every step, and confined to the owning region thread.
     *
     * <p>Along with {@link #previousNanos}, this is why a step allocates nothing but the immutable
     * values it publishes.
     */
    private final @NotNull VehicleSample sample = new VehicleSample();

    /**
     * Reused every step, and confined to the owning region thread.
     *
     * <p>Filled by {@link VehicleHost#probeTerrain} before anything is decided and read once, by the
     * terrain solver, inside the same step. A buffer for the reason {@link #sample} is one; see
     * {@link TerrainSample}.
     */
    private final @NotNull TerrainSample terrain = new TerrainSample();

    /**
     * Decides whether {@link #terrain} is worth filling again, and is the whole of the block-read saving.
     *
     * <p>Reused every step and confined to the owning region thread, like the sample it gates. It is
     * asked from the position and heading this step has already read, so the decision costs no world
     * access at all — see {@link TerrainSampleGate}.
     */
    private final @NotNull TerrainSampleGate terrainGate = new TerrainSampleGate();

    /**
     * Whether this vehicle's profile asks for any terrain reaction at all.
     *
     * <p>Asked once at construction, because it is a property of an immutable profile. It gates the
     * probe, so a vehicle that says nothing about terrain reads no blocks — and it gates the rotation
     * push, which matters more: taking over the renderer's whole-model rotation to hand it back the
     * heading it already had would trade the renderer's own body smoothing for nothing. See
     * {@link VehicleVisual#setBodyRotation}.
     */
    private final boolean terrainActive;

    /**
     * How far above and below itself a probe looks for a surface, in blocks.
     *
     * <p>Derived from the profile rather than configured: the step height is how far up the vehicle cares
     * about, and one block is how far it has to see either way for a grade between two adjacent columns
     * to exist at all. A key for it would be a second statement of the step height that could disagree
     * with the first.
     */
    private final float terrainReachBlocks;

    /** Published by the control step, read from anywhere. One store per step, of a complete value. */
    private volatile @NotNull VehicleState state = VehicleState.STOPPED;

    /**
     * Published by the control step, read from anywhere.
     *
     * <p>How the model is sitting on the terrain, and the state the next step's blend starts from. One
     * store of one complete value per step, for the reason {@link #state} is.
     */
    private volatile @NotNull TerrainAttitude attitude = TerrainAttitude.LEVEL;

    /** Published by the control step, read from anywhere. */
    private volatile @NotNull VehicleSteering steering = VehicleSteering.NEUTRAL;

    /** The control step's own gate. A body that lands after the teardown finds false and does nothing. */
    private volatile boolean alive;

    /**
     * Whether a relocation is in flight.
     *
     * <p>Set on the region thread and cleared on whichever thread finishes the relocation, which is why
     * it is volatile. While it is set the control loop decides nothing and writes nothing <em>to the
     * root</em>: a cross-region teleport removes the entity and its whole passenger tree from the world
     * before re-adding it, so for part of that window there is no entity to move and moving it anyway
     * fails confusingly later rather than immediately.
     *
     * <p>It is deliberately <strong>not</strong> a gate on the two things that are not writes to the
     * root. The model's rotation is a renderer-side value that reaches no entity, and the seat carriers
     * are their own entities with their own schedulers and their own ownership checks — so both continue
     * across the step that starts a relocation. Gating them was this layer's longest-standing visible
     * defect: a moving vehicle showed no lean and left its riders behind.
     */
    private volatile boolean relocating;

    /** Region-confined: the clock reading the previous step took. */
    private long previousNanos;

    /** Region-confined: whether {@link #previousNanos} means anything yet. */
    private boolean clockSeeded;

    /**
     * Region-confined: the rotation last handed to the visual, and whether one ever was.
     *
     * <p>The emission gate. A rotation travels as two bytes, so an angle that has moved by less than one
     * of them is a packet that cannot change a pixel; {@link TerrainSolver#isVisiblyDifferent} is where
     * that fact is written down. Kept here rather than derived from the attitude because the question is
     * "what does the client already have", which is about what was sent and not about what was decided.
     */
    private float sentPitchDegrees;

    /** Region-confined: see {@link #sentPitchDegrees}. */
    private float sentYawDegrees;

    /** Region-confined: whether a rotation has ever been pushed, so the first one is always sent. */
    private boolean rotationSeeded;

    /** The single-use latch. Guarded by {@code this}. */
    private boolean stopped;

    /** Guarded by {@code this}: only the lifecycle methods touch these. */
    private @Nullable VehicleTask controlTask;

    /**
     * The period the live control loop was dispatched at, which is what {@link #reload()} compares
     * against. A repeating task's rate belongs to the platform, so the configured period and the
     * scheduled one can differ — between a reload and the call that acts on it.
     */
    private long scheduledPeriodTicks;

    /**
     * @param rig       the rig this vehicle's visual drives, held for diagnostics and for the api
     * @param profile   how this vehicle drives, already validated against the operator's bounds
     * @param host      the root entity, and the only thing allowed to touch it
     * @param visual    the model riding on it, or {@link VehicleVisual#NONE}
     * @param settings  where the clamps and thresholds come from
     * @param clock     the monotonic clock each step differences
     * @param logger    where a step that threw is reported. The platform swallows anything that escapes
     *                  a scheduled body and keeps the task alive, so a vehicle that throws every step
     *                  would otherwise be silent forever
     * @param chartSink where the seat chart is written when this vehicle starts, or
     *                  {@link SeatChartSink#NONE}. The operator's own switch is read from configuration
     *                  rather than expressed by passing {@code NONE} — a caller that has a logger should
     *                  hand it over and let the file decide
     * @param onStopped told once when this vehicle has torn down, whether it ever started or not
     */
    public VehicleImpl(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull VehicleHost host,
            @NotNull VehicleVisual visual,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull Consumer<VehicleImpl> onStopped
    ) {
        this(rig, profile, host, visual, settings, clock, logger, chartSink, VehicleSetup.DEFAULT,
                RiderInputSource.fromHost(host), onStopped);
    }

    /**
     * @param rig       the rig this vehicle's visual drives, held for diagnostics and for the api
     * @param profile   how this vehicle drives, already validated against the operator's bounds
     * @param host      the root entity, and the only thing allowed to touch it
     * @param visual    the model riding on it, or {@link VehicleVisual#NONE}
     * @param settings  where the clamps and thresholds come from
     * @param clock     the monotonic clock each step differences
     * @param logger    where a step that threw is reported, and where a consumer's seat listener that
     *                  threw is reported too — the platform swallows anything that escapes a scheduled
     *                  body and keeps the task alive, so neither would otherwise be heard of again
     * @param chartSink where the seat chart is written when this vehicle starts
     * @param setup     what this vehicle says to its riders and what each seat's inputs do. The
     *                  consumer's, per kind of vehicle; {@link VehicleSetup#DEFAULT} is this plugin's
     *                  own messages and no per-seat controls
     * @param inputs    where a rider's input is read. {@link RiderInputSource#of} over the input
     *                  controller is the production source;
     *                  {@link RiderInputSource#fromHost(VehicleHost)} is the movement-only fallback a
     *                  server with no input layer gets
     * @param onStopped told once when this vehicle has torn down, whether it ever started or not
     */
    public VehicleImpl(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull VehicleHost host,
            @NotNull VehicleVisual visual,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull VehicleSetup setup,
            @NotNull RiderInputSource inputs,
            @NotNull Consumer<VehicleImpl> onStopped
    ) {
        this(rig, profile, host, visual, settings, clock, logger, chartSink, setup, inputs,
                reportingThrough(logger), onStopped);
    }

    /**
     * @param rig       the rig this vehicle's visual drives, held for diagnostics and for the api
     * @param profile   how this vehicle drives, already validated against the operator's bounds
     * @param host      the root entity, and the only thing allowed to touch it
     * @param visual    the model riding on it, or {@link VehicleVisual#NONE}
     * @param settings  where the clamps and thresholds come from
     * @param clock     the monotonic clock each step differences
     * @param logger    where a step that threw is reported, and where a consumer's seat listener that
     *                  threw is reported too
     * @param chartSink where the seat chart is written when this vehicle starts
     * @param setup     what this vehicle says to its riders, what each seat's inputs do, and the one
     *                  controller that owns this kind of vehicle's behaviour
     * @param inputs    where a rider's input is read
     * @param faults    where the consumer's {@link VehicleController} goes when it throws, and what
     *                  decides whether to keep calling it. The production value is the input layer's own
     *                  throttling sink, so a controller and a bound input handler are judged by one
     *                  policy out of one pair of keys; see {@link VehicleControllerDispatch}
     * @param onStopped told once when this vehicle has torn down, whether it ever started or not
     */
    public VehicleImpl(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull VehicleHost host,
            @NotNull VehicleVisual visual,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull VehicleSetup setup,
            @NotNull RiderInputSource inputs,
            @NotNull InputFaultSink faults,
            @NotNull Consumer<VehicleImpl> onStopped
    ) {
        this(rig, profile, host, visual, settings, clock, logger, chartSink, setup, inputs, faults,
                VehiclePilot.NONE, onStopped);
    }

    /**
     * @param rig       the rig this vehicle's visual drives, held for diagnostics and for the api
     * @param profile   how this vehicle drives, already validated against the operator's bounds
     * @param host      the root entity, and the only thing allowed to touch it
     * @param visual    the model riding on it, or {@link VehicleVisual#NONE}
     * @param settings  where the clamps and thresholds come from
     * @param clock     the monotonic clock each step differences
     * @param logger    where a step that threw is reported, and where a consumer's seat listener or pilot
     *                  that threw is reported too
     * @param chartSink where the seat chart is written when this vehicle starts
     * @param setup     what this vehicle says to its riders, what each seat's inputs do, and the one
     *                  controller that owns this kind of vehicle's behaviour
     * @param inputs    where a rider's input is read
     * @param faults    where the consumer's {@link VehicleController} goes when it throws
     * @param pilot     what steers this vehicle while the driver's seat is empty, or
     *                  {@link VehiclePilot#NONE}. Fixed for the vehicle's life, so there is never a step
     *                  on which the thing deciding has changed under a rider
     * @param onStopped told once when this vehicle has torn down, whether it ever started or not
     */
    public VehicleImpl(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull VehicleHost host,
            @NotNull VehicleVisual visual,
            @NotNull VehicleSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull SimulationLogger logger,
            @NotNull SeatChartSink chartSink,
            @NotNull VehicleSetup setup,
            @NotNull RiderInputSource inputs,
            @NotNull InputFaultSink faults,
            @NotNull VehiclePilot pilot,
            @NotNull Consumer<VehicleImpl> onStopped
    ) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.host = Objects.requireNonNull(host, "host");
        this.visual = Objects.requireNonNull(visual, "visual");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.chartSink = Objects.requireNonNull(chartSink, "chartSink");
        this.setup = Objects.requireNonNull(setup, "setup");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.pilot = Objects.requireNonNull(pilot, "pilot");
        // One comparison against the identity value, made once: the pilot is final, so the answer cannot
        // change and a step need not re-ask it.
        this.piloted = pilot != VehiclePilot.NONE;
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
        this.seats = new VehicleSeats(rig, host, this.seatEvents);
        this.controller = VehicleControllerDispatch.of(
                setup.controller(), Objects.requireNonNull(faults, "faults"), rig.getName());
        // Both derived from an immutable profile, so both are constants for this vehicle's life.
        this.terrainActive = !profile.terrain().isInert();
        this.terrainReachBlocks = Math.max(
                profile.terrain().stepHeightBlocks(), TerrainSample.PROBE_RUN_BLOCKS);
    }

    /**
     * The fault policy a caller that supplied none gets: report every time, never give up.
     *
     * <p>Not a second policy — it is the <em>unconfigured</em> one. The throttling interval and the
     * number of consecutive failures that detaches a controller are both operator configuration, and a
     * caller that handed over no sink has not said what either should be; inventing a figure here is
     * exactly the hardcoded tunable this project refuses. Reporting every occurrence and never detaching
     * is what a consumer's seat input listener has always got on this path, so a vehicle built through
     * the shorter constructor behaves as it did before controllers existed.
     *
     * <p>The production path supplies the real sink; see the twelve-argument constructor.
     *
     * <p>Package-private rather than private so that {@link VehicleBinderImpl} can name it and keep one
     * construction path: a binder with no sink to borrow needs this same fallback, and letting it reach
     * the fallback is better than giving it a second constructor to pick between — two paths through a
     * creation are two places a later argument can be forgotten.
     */
    static @NotNull InputFaultSink reportingThrough(@NotNull SimulationLogger logger) {
        Objects.requireNonNull(logger, "logger");
        return (described, failure) -> {
            logger.error(described + " threw", failure);
            return true;
        };
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull Rig getRig() {
        return this.rig;
    }

    @Override
    public @NotNull VehicleProfile getProfile() {
        return this.profile;
    }

    @Override
    public @NotNull VehicleState getState() {
        return this.state;
    }

    @Override
    public @NotNull VehicleSteering getSteering() {
        return this.steering;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The rider in the <strong>driver's</strong> seat, which is the one whose input this vehicle is
     * steered by. Empty when the driver's seat is free, when the rig declares no driver's seat, and when
     * the rig's seats did not resolve — a vehicle can be full of passengers and have no rider by this
     * definition, which is the right answer for a method whose subject is who is driving.
     *
     * @see #getRiders()
     */
    @Override
    public @NotNull Optional<Player> getRider() {
        return Optional.ofNullable(this.seats.driver());
    }

    @Override
    public @NotNull SeatPlan getSeatPlan() {
        return this.seats.plan();
    }

    @Override
    public @NotNull SeatOccupancy getSeating() {
        return this.seats.occupancy();
    }

    @Override
    public @NotNull VehicleMessages getMessages() {
        return this.setup.messages();
    }

    @Override
    public @NotNull VehicleControls getControls() {
        return this.setup.controls();
    }

    /**
     * Everybody aboard, in seat index order, driver first.
     *
     * <p>Overridden rather than inherited from {@link Vehicle#getRiders()}'s default only to say where
     * it comes from: one read of one immutable occupancy, published whole by the thread that seats
     * people, and therefore safe from any thread.
     */
    @Override
    public @NotNull List<Player> getRiders() {
        return this.seats.riders();
    }

    /**
     * Every seat this vehicle's rig declared and what each one turned out to be.
     *
     * <p>Empty until {@link #start()} has resolved them. This is what the seat chart is drawn from, and
     * it is kept rather than discarded so that a later question — why can nobody sit on this vehicle —
     * has an answer that does not require re-reading the log.
     */
    public @NotNull SeatChart getSeatChart() {
        return this.seats.chart();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implements the root's {@code isSpawned()}, which is what {@code isAlive()} delegates to — the
     * two are one state under two names; see {@link pandastic.studio.models.api.model.Model#isAlive()}.
     */
    @Override
    public boolean isSpawned() {
        return this.alive;
    }

    /**
     * Spawns the visual, seeds the heading and starts the control loop.
     *
     * <p>Called by {@link VehicleBinderImpl} and not part of the published api, because a vehicle a
     * consumer can see is one that is already running — the two-phase bind-then-spawn split that
     * {@link pandastic.studio.models.api.binding.RiggedModel} needs buys a vehicle nothing, since there
     * is no offline question to answer about one.
     *
     * <p>See the class documentation for the order and for why each step is where it is.
     *
     * @return {@code true} if the vehicle is now running; {@code false} if it was already running, or if
     *         the root entity vanished between construction and this call, which is a race the caller
     *         cannot avoid
     * @throws IllegalStateException if called off the root entity's owning thread, if this vehicle has
     *                               already stopped, or if the rig cannot be bound to the live model
     */
    public synchronized boolean start() {
        if (this.stopped) {
            throw new IllegalStateException("Vehicle " + this.uniqueIdentifier + " on "
                    + this.host.describe() + " has already been despawned. A handle is single use —"
                    + " create another rather than reviving one, because its visual's cached bone"
                    + " transformers belong to a tracker that no longer exists.");
        }
        if (this.alive) {
            return false;
        }
        requireOwningThread("started");
        if (!this.host.isAlive()) {
            return false;
        }

        // One read for the whole start, so the period the loop is dispatched at and the seat chart's
        // labels come from the same generation of the configuration.
        final VehicleSettings current = this.settings.current();
        final long periodTicks = current.controlPeriodTicks();

        // 1. The visual, because it is the step most likely to fail and nothing is scheduled yet.
        if (!this.visual.spawn()) {
            return false;
        }

        boolean committed = false;
        try {
            // 2. The heading. A vehicle starts facing wherever its root entity faces; see the class
            //    note. The position is read in the same call and deliberately not kept — every step
            //    reads it fresh, so that a vehicle somebody else moved carries on from where it is.
            if (this.host.readInto(this.sample)) {
                this.state = new VehicleState(0F, 0F, this.sample.yawDegrees());
            }
            this.steering = VehicleSteering.NEUTRAL;

            // 3. The seats, which can only be resolved now: a seat is a locator of a live model, so
            //    before the visual spawned there was nothing to resolve against. A seat that resolved to
            //    a bone, or to nothing at all, leaves the vehicle startable with the seats it has — the
            //    chart is where that is reported, and it is reported at every start rather than once,
            //    because it is a statement about this model as it is loaded now.
            reportSeats(this.seats.resolve(this.visual.locators()), current);

            //    The consumer's per-seat listeners, resolved against the plan once. Here rather than per
            //    step because resolving composes several bindings into one and composing allocates; a
            //    control step must not. A binding addressing a seat the rig does not have is reported
            //    rather than refused — a plugin wiring four seats against a rig a rigger is still
            //    editing should not fail to create its vehicle over seat three not existing yet.
            resolveSeatListeners();

            this.clockSeeded = false;
            this.relocating = false;

            // Visible to the step from here on, so it is set before anything is scheduled.
            this.alive = true;

            // 4. The loop.
            final VehicleTask scheduled =
                    this.host.repeat(this::step, this::onRootRetired, periodTicks);
            if (scheduled == null) {
                // The root retired between the check above and this call. Neither callback will ever
                // run, so nothing would ever tear this down; the unwind below is the only exit.
                return false;
            }

            this.controlTask = scheduled;
            this.scheduledPeriodTicks = periodTicks;
            committed = true;
            return true;
        } finally {
            // One unwind for both ways out: a step that threw, and a root that retired mid-start. The
            // flag rather than a catch, so no failure path can be added later without passing here.
            if (!committed) {
                this.alive = false;
                this.visual.despawn();
            }
        }
    }

    @Override
    public boolean mount(@NotNull Player newRider) {
        return board(newRider).isAboard();
    }

    @Override
    public @NotNull BoardingResult board(@NotNull Player newRider) {
        return boardAt(newRider, VehicleSeats.ANY_SEAT);
    }

    @Override
    public @NotNull BoardingResult board(@NotNull Player newRider, int seatIndex) {
        if (seatIndex == VehicleSeats.ANY_SEAT) {
            // The sentinel is "no particular seat", so an api caller passing it through this overload
            // means the same thing as calling the other one. Refusing it would make -1 a different
            // request depending on which method received it.
            return board(newRider);
        }
        return boardAt(newRider, seatIndex);
    }

    /**
     * Seats {@code newRider} in the seat called {@code seatName}.
     *
     * <p>Kept alongside the indexed form because a name survives a rigger reordering the rig file and an
     * index does not, so this is what a menu or a command built from a rig's own seat names would use.
     * Resolved through {@link SeatPlan} so that exactly one place knows how a name maps to an index.
     *
     * <p>Must run on the thread that owns the root entity.
     *
     * @param newRider who to seat
     * @param seatName the seat's own id, matched case-insensitively, or {@code null} for the lowest free
     *                 seat
     * @return what happened
     * @throws IllegalStateException if called off the owning thread, or if the driver's seat would hand
     *                               the vehicle's movement to the rider's client
     */
    public @NotNull BoardingResult boardNamed(@NotNull Player newRider, @Nullable String seatName) {
        Objects.requireNonNull(newRider, "rider");
        if (seatName == null) {
            return board(newRider);
        }
        final Optional<VehicleSeat> named = this.seats.plan().findSeat(seatName);
        if (named.isEmpty()) {
            notify(newRider, BoardingResult.NO_SUCH_SEAT);
            return BoardingResult.NO_SUCH_SEAT;
        }
        return boardAt(newRider, named.get().index());
    }

    /**
     * The one boarding path: the lowest free seat, or the one addressed.
     *
     * <p>Every refusal is told to the player through this vehicle's own messages before it is returned,
     * which is what "full means full, not a silent no-op and not an exception" comes down to. The one
     * refusal that is <em>also</em> an exception is the authority one, and deliberately: a message tells
     * the player why they are not aboard, and the exception tells the plugin that chose the entity type
     * that its choice cannot work. Dropping the throw would leave a vehicle that silently never seats a
     * driver, which is the failure this project has gone furthest out of its way to make loud.
     */
    private @NotNull BoardingResult boardAt(@NotNull Player newRider, int seatIndex) {
        Objects.requireNonNull(newRider, "rider");
        requireOwningThread("mounted");
        if (!this.alive) {
            notify(newRider, BoardingResult.NOT_ALIVE);
            return BoardingResult.NOT_ALIVE;
        }
        if (!this.host.readInto(this.sample)) {
            // The root went between the gate above and this read. A race the caller cannot avoid, and
            // the control step will tear the vehicle down on its own next pass.
            notify(newRider, BoardingResult.NOT_ALIVE);
            return BoardingResult.NOT_ALIVE;
        }

        final VehicleSettings current = this.settings.current();
        final BoardingResult result =
                this.seats.mount(newRider, seatIndex, this.sample, current.seats());
        if (!result.isAboard()) {
            notify(newRider, result);
            return result;
        }

        final VehicleSeat seat = result.seat();
        if (result.outcome() == BoardingOutcome.SEATED && seat != null && seat.isDriver()
                && (this.host.isClientSteered(newRider) || this.seats.isClientSteered(newRider))) {
            // The authority check, kept from the days when a rider sat on the root itself and now asked
            // about the thing they are actually riding as well. It has to be here rather than at creation
            // for the same reason it always did: the server's answer depends on who is aboard, so before
            // the mount there is nothing to ask about. Asked for the driver's seat only — a passenger's
            // client steering a carrier steers nothing anybody can see.
            this.seats.dismount(newRider);
            notify(newRider, BoardingResult.of(BoardingOutcome.CANNOT_BOARD, seat));
            throw new IllegalStateException("Seating " + newRider.getName() + " in the driver's seat '"
                    + seat.name() + "' (index " + seat.index() + ") of " + this.host.describe()
                    + " would hand the vehicle's movement to their client: the server reports them as"
                    + " the controlling passenger of what they are riding, so every position this plugin"
                    + " writes would be a correction the client has already drawn past. Use a root"
                    + " entity and a seat carrier type whose own classes do not make their first"
                    + " passenger the driver.");
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Puts <strong>everybody</strong> down, not only the driver, which is what the method meant when a
     * vehicle could carry one rider and is what a caller ejecting a vehicle wants. One rider at a time is
     * {@link #dismount(Player)}.
     */
    @Override
    public boolean dismount() {
        requireOwningThread("dismounted");
        final boolean removed = this.seats.dismountAll() > 0;
        if (removed) {
            this.steering = VehicleSteering.NEUTRAL;
        }
        // Belt and braces, and the one remaining use of the root's own eject: nobody should be riding
        // the root, because this layer never puts them there — but a passenger somebody else added is
        // put down too rather than left aboard a vehicle that reports itself empty.
        final boolean ejected = this.host.dismount();
        return removed || ejected;
    }

    @Override
    public boolean dismount(@NotNull Player seated) {
        Objects.requireNonNull(seated, "rider");
        requireOwningThread("dismounted");
        return this.seats.dismount(seated);
    }

    @Override
    public void despawn() {
        stop(false);
    }

    /**
     * The platform telling us the root entity is gone — the authoritative notification, and the only one
     * for an entity that was removed rather than merely killed.
     */
    private void onRootRetired() {
        stop(true);
    }

    /**
     * The one teardown, reached from every direction and run once.
     *
     * @param rootRetired whether this is the platform's retired callback, which may not mutate the world
     *                    and cannot defer; see the class documentation
     */
    private synchronized void stop(boolean rootRetired) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.alive = false;

        // 1. The decider. Its body only writes this vehicle's own fields, so one already in flight is
        //    harmless — the gate above is what stops it doing anything.
        final VehicleTask scheduled = this.controlTask;
        this.controlTask = null;
        if (scheduled != null) {
            scheduled.cancel();
        }

        // 2. The published values, so a reader that outlives the vehicle sees a stopped one rather than
        //    whatever it was doing when it died. The riders are cleared by the release below, which is
        //    also what removes their carriers — the two cannot be separated without leaving an invisible
        //    entity in the world that nothing reports.
        this.steering = VehicleSteering.NEUTRAL;
        // The heading is kept and the speeds are not. A reader that outlives the vehicle should see one
        // that has stopped where it was, rather than one still travelling or one spun to due south.
        this.state = new VehicleState(0F, 0F, this.state.yawDegrees());
        // The same split the state makes: how the model was sitting is kept, the climb that will never
        // finish is not. A reader that outlives the vehicle sees one that stopped where it was, leaning
        // how it was, rather than one still part-way up a block.
        this.attitude = this.attitude.withoutClimb();
        this.relocating = false;

        // 3. The consumer's controller, once, and before anything is released: the api promises its
        //    riders are still aboard for this call, because the call is where a controller forgets them.
        //    On whatever thread got here — a caller's, a step's, or the platform's retired callback,
        //    which cannot defer to anywhere else — which is why that hook alone carries no thread
        //    promise. A detached controller still gets it; see VehicleControllerDispatch.
        this.controller.closed(this);

        // 4. The pilot, after the consumer's own controller and before anything is released. After,
        //    because the controller holds the consumer's invariants and this holds ours — an AiPilot
        //    uninstalls a plan here, and a controller that wanted to read that plan's last decision in
        //    onClosed must still be able to. Before the release, because releasing is what ends the
        //    vehicle's existence and a pilot is told about a vehicle rather than about a wreck.
        closePilot();

        // 5. and 6. Put the rider down, release the visual.
        release(rootRetired);

        this.onStopped.accept(this);
    }

    /**
     * Puts the riders down and releases the carriers and the visual, each on a thread allowed to do it.
     *
     * <h2>The carriers go first, from whatever thread this is</h2>
     * Because a leaked carrier is the worst thing this layer can leave behind: an invisible entity
     * holding a player that nothing will ever remove. {@link VehicleSeats#release()} is callable from
     * anywhere — each carrier schedules its own removal on its own entity scheduler — so it does not
     * depend on the root still existing, on this being the owning thread, or on anything below here
     * succeeding. It is therefore done before the two steps that can fail.
     *
     * <h2>Then the root's own eject and the visual</h2>
     * Three cases, and the awkward one is real. On the owning thread — where a step's own teardown and a
     * well-behaved caller both run — both happen inline. Off it, both are handed to the root's scheduler,
     * because ejecting a passenger is a world mutation. From a retired callback neither can be scheduled
     * at all, since the scheduler that would have run the deferred work is the one that just retired —
     * but there is also nothing to eject, because the root entity is what has gone.
     *
     * <p>The visual releases itself from any thread, so it is the one thing that happens in every case.
     */
    private void release(boolean rootRetired) {
        this.seats.release();
        if (rootRetired) {
            this.visual.despawn();
            return;
        }
        if (this.host.isOwnedByCurrentThread()) {
            this.host.dismount();
            this.visual.despawn();
            return;
        }
        final boolean deferred = this.host.submit(() -> {
            this.host.dismount();
            this.visual.despawn();
        });
        if (!deferred) {
            // No thread left to defer to, which means the root is gone and there is nobody aboard it.
            this.visual.despawn();
        }
    }

    /**
     * Writes the seat chart, if the operator wants it.
     *
     * <p>The switch is read from configuration rather than from whether a sink was supplied, so that an
     * operator turning the chart off silences it without the wiring changing. A rig that declared no
     * seats produces no lines at all — see {@link SeatChartReport#lines} for why announcing "0 seats" for
     * every model on the server would be noise.
     */
    private void reportSeats(@NotNull SeatChart chart, @NotNull VehicleSettings current) {
        if (!current.seats().logSeatChart()) {
            return;
        }
        SeatChartReport.emit(this.chartSink, chart, this.rig.getName(), this.rig.getModelId(),
                current.seats().chart());
    }

    /**
     * One control step: read the root, resolve the rider's keys, decide, apply.
     *
     * <p>Runs on the owning region thread, once per configured period. Everything that can go wrong in
     * here is caught and reported rather than allowed to escape, because the platform swallows anything
     * that escapes a scheduled body and keeps the task alive — so an uncaught failure would be a vehicle
     * that quietly stops responding and never explains itself.
     */
    private void step() {
        try {
            stepOnce();
        } catch (final RuntimeException thrown) {
            this.logger.error("Vehicle " + this.uniqueIdentifier + " on " + this.host.describe()
                    + " failed a control step", thrown);
        }
    }

    private void stepOnce() {
        if (!this.alive) {
            return;
        }

        // The clock advances whether or not this step decides anything, so a step that is skipped
        // discards its own elapsed time rather than handing it to the next one. The alternative lets a
        // vehicle "catch up" across a relocation, which is exactly the jump the clamp exists to prevent.
        final long now = this.clock.nanoTime();
        final long elapsedNanos = this.clockSeeded ? now - this.previousNanos : 0L;
        this.previousNanos = now;
        this.clockSeeded = true;

        if (this.relocating) {
            // A step that lands while one is still in flight. Nothing is decided and nothing is pushed:
            // there is no reading of the root to decide from, so there is also no new rotation and no new
            // seat target — unlike the step that *started* the relocation, which decided a move and
            // therefore still has both to deliver. See #pushRotation.
            return;
        }
        if (!this.host.readInto(this.sample)) {
            // The other side of the exactly-once guarantee: a root that is dead but whose scheduler has
            // not retired yet is noticed here rather than never. Safe to tear down from inside the body
            // — this is an ordinary repeating task, not the platform's critical retired callback.
            stop(false);
            return;
        }

        // One read for the whole step. Reading it per value would let a reload land between the clamp on
        // the elapsed time and the threshold that decides whether the result is a teleport.
        final VehicleSettings current = this.settings.current();

        // And one read of the host's capability, beside it and for the same reason: the decision below
        // and the application that follows it must agree about which of the two ways of moving this step
        // is using, and two reads could straddle them. Read here rather than copied into a field of this
        // class, because the host already caches it — the seam promises it is fixed for the host's life
        // — and a second copy would be the same fact stated twice with nothing keeping the two in step.
        // The read is one virtual call returning a cached boolean.
        final boolean localMove = this.host.supportsLocalMove();

        // One input read per occupied seat, before anything is decided. The driver's becomes the intent
        // the solver sees and every seat's is handed to its listener after the move — so the steering and
        // a consumer's listener cannot disagree about what a rider did within one step. Reading the
        // source twice is the straddling hazard the snapshot read above avoids the same way.
        this.seats.readInputs(this.inputs);

        // Only the driver's input steers. A seat that emptied — a rider who held sneak, disconnected,
        // died or was removed — is noticed by the follow pass below, which reports it; by the time the
        // next step reads this there is no driver and the vehicle coasts. Nobody is promoted into the
        // empty seat.
        //
        // Unless something is piloting it, in which case an empty driver's seat is where the pilot's
        // synthesised intent comes in. A rider wins outright: the question is asked once per step, on the
        // thread that also seats people, so there is never a step with two deciders and no handover state
        // to get wrong.
        final RiderIntent intent = this.piloted && this.seats.driver() == null
                ? pilotIntent(current, elapsedNanos)
                : RiderInputSource.intentOf(this.seats.driverInput().state());

        // At most one terrain reading per step, taken before anything is decided and read once — the same
        // rule the snapshot and the rider's input follow, and for the same reason: two reads inside one
        // step can disagree, and then the lean and the climb are answering different hillsides.
        sampleTerrain(current, now);

        final VehicleState previous = this.state;
        final TerrainAttitude previousAttitude = this.attitude;
        final VehicleStepper.Plan plan = VehicleStepper.plan(previous, intent, this.profile, current,
                elapsedNanos, previousAttitude, this.terrain, localMove);

        this.steering = plan.steering();
        this.state = plan.move().next();
        this.attitude = plan.terrain().next();

        // One read of the relocation gate for the whole tail of this step, so the two decisions that
        // depend on it cannot disagree about whether a relocation was started — the completion callback
        // runs on the destination region's thread and may clear the flag at any point.
        boolean relocated = false;
        if (!plan.isIdle()) {
            apply(plan, previous);
            relocated = this.relocating;
            if (!relocated && !this.host.readInto(this.sample)) {
                // Re-read, because the write that just happened may have been partly applied: a vehicle
                // stopped by a wall is somewhere other than where it was asked to go, and a seat computed
                // from the commanded position would put its rider inside the wall. Skipped for a
                // relocation, where there is deliberately nothing to read back — see #relocate, which
                // leaves the sample describing the destination it commanded.
                stop(false);
                return;
            }
        }

        // Before the follow pass, because the whole-model rotation moves the locators a seat hangs off:
        // a model that has just pitched has its seats somewhere other than where a level one did, and a
        // follow computed first would put every rider a step behind the lean.
        //
        // And on a displaced step as much as a parked one, which is the owner's rule and was the defect
        // here: the early return a relocation used to take meant a terrain-configured vehicle showed its
        // lean and its pitch only while standing still. See #pushRotation for why a relocation in flight
        // is not a reason to withhold it.
        pushRotation(plan);

        // Then, and unconditionally. A parked vehicle still animates, and an animated locator moves the
        // seat hanging off it — gating this on the vehicle having moved would leave a rider behind
        // whenever an idle clip breathed. It is also what carries the riders across a relocation: the
        // carriers are not passengers of the root, so the platform's own teleport does not bring them,
        // and a step that returned before this left every rider of a relocating vehicle standing where
        // the vehicle used to be until some later step happened not to displace it.
        this.seats.follow(this.sample, current.seats());

        if (relocated) {
            // The one thing a relocation still costs, and it is what the published api already says
            // about one rather than this class's convenience: a step whose root is transiently out of
            // the world offers a consumer nothing it could act on, because acting means touching an
            // entity that is mid-teleport — so VehicleController's hooks are not called. The rotation
            // and the seats above are this plugin's own, they touch no root, and they are about where
            // the vehicle is going, which this step knows exactly.
            return;
        }

        // Last, after the follow pass, so neither a listener nor the controller ever sees a rider whose
        // seat this step has just given up. Skipped entirely when the consumer wired neither, which is
        // the common case and costs one reference comparison and one volatile read.
        if (this.controller.isActive() || !this.setup.controls().isEmpty()) {
            this.seats.forEachOccupied(this.inputVisitor);
        }

        // Then the whole-vehicle hook, after every seat's input has been offered — which is what lets a
        // controller resolve two riders asking for opposite things. The measured, clamped ticks rather
        // than the configured period: the platform drops missed iterations instead of replaying them, so
        // the period is not how much time has passed. A controller that detached inside the pass above
        // does not get this call, because isActive() is read again rather than cached.
        this.controller.step(this, plan.ticks());
    }

    /**
     * Fills {@link #terrain} for this step, or decides that the last reading will do.
     *
     * <h2>This is the only thing in the plugin that reads blocks, so it is the only thing rationed</h2>
     * A reading walks a column of the world under the vehicle and a second column one block ahead of it,
     * which at the shipped reach is five block reads on flat ground and seven at worst. Everything else a
     * control step does is arithmetic or a position write. On a regionised server a block read cannot be
     * moved off the thread that owns the chunk, so there is no "do it asynchronously" available here and
     * never will be — the only lever is doing it <em>less</em>, and that lever is
     * {@link TerrainSampleGate} plus the viewer gate below.
     *
     * <p>Three outcomes, in the order they are decided:
     *
     * <ol>
     *   <li><strong>The profile is inert to terrain.</strong> The sample is cleared and nothing is read,
     *       which is every vehicle that existed before the terrain layer. Decided from a field computed
     *       at construction, so it costs nothing per step.</li>
     *   <li><strong>Nobody can see it.</strong> The last reading is kept and marked second-hand, so the
     *       model holds the attitude it had instead of snapping level the moment it is culled — and when
     *       a viewer arrives the vehicle is already sitting the way the ground said it should. Reading
     *       nothing is the right cost for a model nobody holds, and the honest price is in
     *       {@link TerrainSampling#onlyWhenSeen()}: an unseen vehicle does not arm a climb, because
     *       arming one requires a reading taken this step.</li>
     *   <li><strong>The gate says the last reading still describes the ground.</strong> Same handling:
     *       kept, and marked second-hand.</li>
     * </ol>
     *
     * <p>A suppressed reading is deliberately <em>not</em> {@link TerrainSample#unavailable()}. "I have
     * no answer" and "my answer is from four ticks ago" lead to different decisions — the first blends
     * the model flat, the second holds it where the hillside put it — and conflating them would make a
     * saving look like a reading that failed.
     *
     * @param current  the configuration generation this whole step is being decided against
     * @param nowNanos this step's clock reading, which is the one the gate ages a reading against
     */
    private void sampleTerrain(@NotNull VehicleSettings current, long nowNanos) {
        if (!this.terrainActive) {
            this.terrain.unavailable();
            return;
        }
        final TerrainSampling sampling = current.terrainSampling();
        if (sampling.onlyWhenSeen() && !this.visual.hasViewers()) {
            this.terrain.markReused();
            return;
        }
        // The position this step has already read and the heading of the step that has just finished.
        // The heading rather than the one about to be decided because the reading is an input to that
        // decision and cannot be taken from its output; a vehicle turns at most its own turn rate in a
        // step, so the probe is at most that far off the heading it ends up on.
        if (!this.terrainGate.readingDue(sampling, this.sample.x(), this.sample.y(), this.sample.z(),
                this.state.yawDegrees(), nowNanos)) {
            this.terrain.markReused();
            return;
        }
        this.host.probeTerrain(this.terrain, this.terrainReachBlocks, this.state.yawDegrees());
    }

    /**
     * Hands this step's attitude to the visual, if a client could see the difference.
     *
     * <h2>Why the gate is the wire's resolution and not an epsilon</h2>
     * A rotation travels as one byte per axis, so two angles that round to the same byte are the same
     * rotation to everything downstream — the renderer's own pipeline compares them that way too, and
     * drops the second. Sending both therefore costs the full price of a packet for a difference nobody
     * can be shown. The stored attitude keeps its full precision and keeps blending, so a change below a
     * byte is deferred to whichever later step crosses one rather than being lost.
     *
     * <p>The first push is unconditional. Until one has happened the renderer is still using its own body
     * rotation, so there is no previous value to compare against and "unchanged" would mean "never
     * started".
     *
     * <h2>What is not gated</h2>
     * The yaw is part of the comparison, not exempt from it. A vehicle driving in a straight line on flat
     * ground pushes nothing at all, which is the common case; one that is turning pushes on the steps its
     * heading crosses a byte on, which is what the client could draw anyway.
     *
     * <p>Neither is the <strong>relocation</strong>, which is the one that was gated and should not have
     * been: a vehicle whose every displaced step went through the asynchronous teleport pushed a rotation
     * only on the steps it was standing still, so its lean and its pitch were visible only while parked.
     * That is now unconditional, and the reason it is safe is worth stating because it does not look safe.
     * Three facts, in the order they stop mattering:
     *
     * <ol>
     *   <li><strong>This is not a write to the root entity, and it reaches no entity at all.</strong> It
     *       ends at the renderer's whole-model rotation, which on the shipped backend is a
     *       <em>supplier</em> stored on the tracker. The packets come from the renderer's own tick, on
     *       the renderer's own pool, addressed to the display entities it owns — none of which is the
     *       root, and none of which is region-bound. A rotation pushed while the root is mid-teleport is
     *       therefore a field assignment, and the pipeline's own equality check drops it if it says the
     *       same thing as the last one.</li>
     *   <li><strong>The path it does take is already guarded.</strong> The visual reaches the model
     *       through the binding layer, whose control call declines on a released model and defers to the
     *       entity's own scheduler when the calling thread does not own it — which is exactly the state a
     *       cross-region teleport puts us in. So even on a backend where the push did touch the entity,
     *       it would be staged and flushed on the far side rather than applied from here.</li>
     *   <li><strong>It is rare now.</strong> With the movement adapter present a relocation is what the
     *       seam always documented it as — a displacement too large for the configured threshold — rather
     *       than every step of every vehicle.</li>
     * </ol>
     *
     * <h2>It does not fight the pose pipeline, and that was checked rather than assumed</h2>
     * The whole-model rotation and {@code TrackerPoseSink} write different things through different
     * calls: the rotation becomes the display's own orientation, while the sink writes a bone's
     * interpolated transformation — position, scale and local rotation. The whole-model rotation is not
     * one of the four values the sink sends, so there is no field for the two to take turns overwriting.
     * This is the trap that made a decorated model's scale last exactly until the next emission, and it
     * does not apply here.
     *
     * @param plan the step that has just been decided and applied
     */
    private void pushRotation(@NotNull VehicleStepper.Plan plan) {
        if (!this.terrainActive) {
            // See the field: a vehicle with nothing to say about terrain leaves the renderer's own body
            // rotation alone, because taking it over would replace it with the value it already had.
            return;
        }
        final float pitch = plan.terrain().pitchDegrees();
        final float yaw = plan.move().yawDegrees();
        if (this.rotationSeeded
                && !TerrainSolver.isVisiblyDifferent(pitch, this.sentPitchDegrees)
                && !TerrainSolver.isVisiblyDifferent(yaw, this.sentYawDegrees)) {
            return;
        }
        this.sentPitchDegrees = pitch;
        this.sentYawDegrees = yaw;
        this.rotationSeeded = true;
        // The roll is carried and the shipped backend drops it; see RiggedVehicleVisual for why that is
        // a renderer limit rather than a choice, and why the angle is computed anyway.
        this.visual.setBodyRotation(pitch, yaw, plan.terrain().bankDegrees());
    }

    /**
     * Asks the pilot what to hold this step.
     *
     * <h2>Why the elapsed time is converted twice</h2>
     * The pilot needs the measured, clamped tick count, and so does {@link VehicleStepper#plan}, which
     * computes it from the same nanoseconds a moment later. Both call {@link VehicleStepper#elapsedTicks}
     * on the same two inputs, so the two figures are the same number by construction rather than by
     * agreement — and the alternative, threading the value into the plan, would mean a caller could hand
     * the stepper an elapsed time it had not measured. The conversion is a divide and a min.
     *
     * <h2>Why the heading comes from the state and the position from the sample</h2>
     * The position has to be the entity's real one: a vehicle that was pushed, stopped by a wall or
     * relocated by somebody else must be told where it actually is, which is what the sample read at the
     * top of this step carries. The heading, by contrast, is this layer's own — it is the value the solver
     * is about to integrate, and steering against the entity's yaw instead would aim at a bearing the
     * renderer has already been handed something else for.
     *
     * <h2>Containment, and why there is no detach</h2>
     * A pilot that throws costs this step's intent and nothing else: the failure is reported and the
     * vehicle coasts, which is a vehicle rolling to a stop rather than a control loop that died.
     * Deliberately <em>not</em> the detach policy a {@link VehicleController} gets — a detached controller
     * costs the behaviour it was adding, while a detached pilot would leave an autonomous vehicle parked
     * in the world for ever with nothing in the log to connect it to.
     *
     * <h2>The one allocation a piloted step makes</h2>
     * The context, and it is deliberate. The rest of this class reuses buffers because a control step
     * must not allocate — but a buffer handed to consumer code is a buffer a consumer can retain and read
     * a later step out of, and the whole argument for a pilot being safe off its own thread is that what
     * it receives is an immutable value. One small record per step for a vehicle that actually has a
     * pilot is the price of that, and it is paid by no other vehicle.
     *
     * @param current      the configuration generation this whole step is being decided against
     * @param elapsedNanos nanoseconds since the previous step
     * @return what the pilot asked for, or {@link RiderIntent#NONE} if it asked for nothing or threw
     */
    private @NotNull RiderIntent pilotIntent(
            @NotNull VehicleSettings current,
            long elapsedNanos
    ) {
        final float ticks = VehicleStepper.elapsedTicks(elapsedNanos, current.maxStepTicks());
        try {
            return this.pilot.steer(new PilotContext(this, this.sample.x(), this.sample.y(),
                    this.sample.z(), this.state, this.profile, ticks));
        } catch (final RuntimeException thrown) {
            this.logger.error("The pilot of vehicle " + this.uniqueIdentifier + " on "
                    + this.host.describe() + " failed to steer; it will coast this step", thrown);
            return RiderIntent.NONE;
        }
    }

    /**
     * Tells the pilot the vehicle is finished, once.
     *
     * <p>Contained for the reason every other consumer-facing hook on the teardown is: this runs on
     * whichever thread got to the teardown first, including the platform's retired callback, and a hook
     * that threw there would abandon the rest of the release — the carriers, the riders, the visual.
     * Skipped entirely when there is no pilot.
     */
    private void closePilot() {
        if (!this.piloted) {
            return;
        }
        try {
            this.pilot.onClosed(this);
        } catch (final RuntimeException thrown) {
            this.logger.error("The pilot of vehicle " + this.uniqueIdentifier + " on "
                    + this.host.describe() + " failed to close", thrown);
        }
    }

    /**
     * Resolves the consumer's listener for each seat, once, when the seats are resolved.
     *
     * <p>Owning thread only, from {@link #start()}. An address the rig has no seat for is logged rather
     * than refused; see {@link VehicleControls#unmatched}.
     */
    private void resolveSeatListeners() {
        final SeatPlan plan = this.seats.plan();
        final VehicleControls controls = this.setup.controls();
        final SeatInputListener[] resolved = new SeatInputListener[plan.size()];
        for (final VehicleSeat seat : plan.seats()) {
            resolved[seat.index()] = controls.listenerFor(seat);
        }
        this.seatListeners = resolved;

        final List<String> unmatched = controls.unmatched(plan);
        if (!unmatched.isEmpty()) {
            // Through the chart sink rather than the simulation logger, which only reports a throwable:
            // this is the same kind of finding the seat chart carries — a vehicle's seats not being what
            // somebody believed — and it belongs beside the chart in the same console, at the same
            // severity, as a component.
            this.chartSink.warn(Component.text("Vehicle " + this.uniqueIdentifier + " on "
                    + this.host.describe() + " has seat input listeners for seats the rig '"
                    + this.rig.getName() + "' does not declare: " + String.join(", ", unmatched)
                    + ". They will never fire: either the rig is missing those seats, or the controls"
                    + " address the wrong ones."));
        }
    }

    /**
     * Hands one occupied seat's input to the consumer's listener, then to the consumer's controller.
     *
     * <p>The body of {@link #inputVisitor}. One {@link SeatInput} is built and both are given it, which
     * is what makes the controller an adaptation of the per-seat mechanism rather than a second copy of
     * it: the two cannot disagree about what a rider did, and a consumer that supplies both pays one
     * allocation. Nothing is built at all for a seat with no listener and no controller, so a vehicle
     * whose consumer wired one of four seats still allocates one value per step rather than four.
     *
     * <p>The listener first and the controller second, which is specific before general — the order
     * {@link VehicleControls#listenerFor} already composes a seat's own bindings in, extended one step
     * outwards. See {@link VehicleController#onPlayerInput}, which promises it.
     *
     * <h2>Two containments, and why they are not the same one</h2>
     * Anything either of them throws is contained. It has to be: the platform swallows whatever escapes
     * a scheduled body and keeps the task alive, so an exception let through here would silently stop
     * the rest of this step — including the seats after this one — for as long as the consumer's bug
     * lasts.
     *
     * <p>They are contained <em>differently</em>, on purpose. A {@link SeatInputListener} is a function
     * with no state of its own, so one that throws costs that seat's reaction and nothing else, and its
     * own documentation promises a log line every time — loud, because a listener nobody notices has
     * stopped working is worse. A {@link VehicleController} holds the kind's invariants in fields, so one
     * that keeps throwing has state nothing should be built on: it is throttled and eventually detached
     * by {@link VehicleControllerDispatch}, which is the policy the input layer applies to a bound
     * handler for the same reason.
     */
    private void dispatchSeatInput(
            @NotNull VehicleSeat seat,
            @NotNull Player rider,
            @NotNull InputSnapshot input,
            @NotNull InputState previous
    ) {
        final SeatInputListener listener = listenerFor(seat);
        final boolean controllerActive = this.controller.isActive();
        if (listener == null && !controllerActive) {
            return;
        }
        final SeatInput value = new SeatInput(this, seat, rider, input, previous, seat.isDriver());
        if (listener != null) {
            try {
                listener.onInput(value);
            } catch (final RuntimeException thrown) {
                this.logger.error("A seat input listener for seat #" + seat.index() + " '" + seat.name()
                        + "' of vehicle " + this.uniqueIdentifier + " on " + this.host.describe()
                        + " failed", thrown);
            }
        }
        if (controllerActive) {
            this.controller.playerInput(value);
        }
    }

    /**
     * This seat's composed listener, or {@code null} when the consumer wired nothing for it.
     *
     * <p>An array read rather than a resolution: {@link #resolveSeatListeners()} composed them once at
     * start, because composing allocates and a control step must not. The bounds check covers a seat
     * index outside the plan the listeners were resolved against, which a vehicle whose start was
     * unwound can be asked about.
     */
    private @Nullable SeatInputListener listenerFor(@NotNull VehicleSeat seat) {
        final SeatInputListener[] listeners = this.seatListeners;
        final int index = seat.index();
        if (index < 0 || index >= listeners.length) {
            return null;
        }
        return listeners[index];
    }

    /**
     * {@code rider} has just been seated in {@code seat}.
     *
     * <p>From {@link SeatEvents}, on the owning thread, inside the mount that caused it. The message is
     * about which seat they got, because that is the thing they cannot see: a seat is an invisible
     * carrier on a locator, so nothing on screen distinguishes driving from being carried.
     *
     * <p>The consumer's controller is told afterwards, so a controller that throws cannot cost the rider
     * the one message about this that they can see. The seat is already theirs and the occupancy has
     * already been published by this point, which is what lets the hook's documentation promise that
     * {@link #getSeating()} agrees with what it is told.
     */
    private void seated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        send(rider, seat.isDriver() ? VehicleNotice.NOW_DRIVER : VehicleNotice.NOW_PASSENGER, seat);
        this.controller.seated(this, seat, rider);
    }

    /**
     * {@code rider} is no longer in {@code seat}.
     *
     * <p>From {@link SeatEvents}, so it covers every way a seat empties that this layer observes: asked
     * for, held sneak, disconnected, carrier removed, model gone. Not the shutdown path —
     * {@link VehicleSeats#release()} reports nothing, on purpose.
     *
     * <p>When it was the <strong>driver's</strong> seat the steering goes neutral immediately rather
     * than waiting for the next step to recompute it, so a reader that looks between the two sees a
     * vehicle with no driver and no steering rather than the last thing the departed rider asked for.
     * Nobody is promoted; see the class note.
     *
     * <p>The consumer's controller is told last, for the reason {@link #seated} tells it last. Note that
     * {@link VehicleSeats} publishes the new occupancy <em>after</em> the pass this is called from, so
     * the hook's own documentation tells a controller to read its arguments rather than
     * {@link #getSeating()} — a departure is not symmetrical with an arrival here, and saying so is
     * cheaper than making one pass publish per seat.
     */
    private void vacated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        if (seat.isDriver()) {
            this.steering = VehicleSteering.NEUTRAL;
        }
        send(rider, VehicleNotice.DISMOUNTED, seat);
        this.controller.vacated(this, seat, rider);
    }

    /** Tells {@code rider} why they are not aboard, if the outcome is worth a message. */
    private void notify(@NotNull Player rider, @NotNull BoardingResult result) {
        final VehicleNotice notice = result.outcome().notice();
        if (notice != null) {
            send(rider, notice, result.seat());
        }
    }

    /**
     * Renders one notice and sends it.
     *
     * <p>Sending a message is region-independent — it is a packet to one connection and touches no
     * entity — so this is safe from the owning thread, which is where every caller already is. Anything
     * the consumer's renderer throws is reported rather than allowed to abort a mount: a message is the
     * least important thing happening at that moment.
     */
    private void send(
            @NotNull Player rider,
            @NotNull VehicleNotice notice,
            @Nullable VehicleSeat seat
    ) {
        try {
            final VehicleNoticeContext context = VehicleNoticeContext.of(
                    rider, this.rig.getName(), this.seats.occupancy(), seat);
            final Optional<Component> message = this.setup.messages().render(notice, context);
            message.ifPresent(rider::sendMessage);
        } catch (final RuntimeException thrown) {
            this.logger.error("Rendering the " + notice.key() + " message of vehicle "
                    + this.uniqueIdentifier + " on " + this.host.describe() + " failed", thrown);
        }
    }

    /**
     * Writes one plan to the world.
     *
     * <p>Heading first, then the displacement, matching the order the solver integrated them in: a
     * vehicle that turns while accelerating travels along the heading it ends the step with, so applying
     * the translation first would put it a half-step behind its own controls.
     *
     * <p>This performs the operation {@link VehicleStepper.Plan#kind()} names and makes no choice of its
     * own. It used to make one — a {@link MoveKind#LOCAL} became a relocation here when the host had no
     * in-place move — and that was the half of the decision the plan could not see, which is why a climb
     * it had granted was then silently discarded. The capability now goes into the plan, so the kind that
     * arrives is the kind that can happen.
     *
     * <p>{@link MoveKind#NONE} with a changed heading is a real case rather than a leftover: a vehicle
     * turning on the spot has no displacement at all and still has to be written, which is why the
     * heading is applied on its own branch instead of alongside the move.
     */
    private void apply(@NotNull VehicleStepper.Plan plan, @NotNull VehicleState previous) {
        final VehicleMove move = plan.move();

        if (plan.kind() == MoveKind.TELEPORT) {
            // The relocation carries the heading with it, so there is nothing to write separately.
            relocate(move);
            return;
        }

        if (move.yawDegrees() != previous.yawDegrees()) {
            this.host.setYaw(move.yawDegrees());
        }
        if (plan.kind() == MoveKind.LOCAL) {
            // Partly applied is a normal outcome, not a failure: the entity ends up where the blocks let
            // it go, which is what gives a vehicle its wall stop and its slab climb. The state keeps the
            // commanded speed either way, because the next step reads the real position back and the
            // displacement it computes is measured from there.
            this.host.moveLocal(move.deltaX(), move.deltaY(), move.deltaZ());
        }
    }

    /**
     * Sends the vehicle to an absolute destination through the platform's asynchronous teleport.
     *
     * <p>The destination is the position just read plus the decided displacement, never a position this
     * layer remembered: a vehicle that was pushed, blocked or relocated by somebody else has to carry on
     * from where it actually is.
     *
     * <p>Marks the vehicle as relocating before starting, so the loop stops deciding until the platform
     * reports back. The callback does nothing but clear that flag, because it runs on whichever thread
     * finished the relocation — which is the destination region's, not ours.
     *
     * <h2>The sample is left describing the destination</h2>
     * Because the rest of the step still has to seat people, and the one position that is both known and
     * useful at this point is the one the relocation was just given. The entity itself cannot be read: it
     * is transiently out of the world, and reading it anyway answers either the old position or nothing.
     *
     * <p>So the seat follow that runs after this puts every rider where the vehicle is <em>going</em>,
     * and each carrier gets there by the mechanism it already uses for a root that moved — its own
     * in-place write, or its own relocation past the carrier step ceiling. The alternative, which this
     * replaces, was to skip the follow entirely: the carriers are not passengers of the root, so the
     * platform's teleport does not bring them, and on a host with no in-place move — where every
     * displaced step is a relocation — the riders were therefore never moved at all while the vehicle
     * was driving.
     *
     * <p>A relocation that fails leaves the carriers one step ahead of a root that did not move, and the
     * next step reads the real position and brings them back. That is the smaller error: a failed
     * relocation is a root that is being removed, and the step after it tears the vehicle down.
     */
    private void relocate(@NotNull VehicleMove move) {
        this.relocating = true;
        // A climb armed against the terrain the vehicle is leaving is a rise applied against terrain
        // nobody has looked at, so it is forgotten here — on this thread, synchronously, rather than in
        // the completion callback, which runs on the destination region's. The angles survive, because
        // they are about how the model is sitting and not about where it is; snapping them flat across
        // a relocation would be visible and wrong.
        this.attitude = this.attitude.withoutClimb();

        final double toX = this.sample.x() + move.deltaX();
        // The vertical is dropped rather than carried. A relocation applies an absolute position with no
        // interpolation, so a rise inside one snaps however carefully it was spread — which is the whole
        // point of spreading it. The stepper refuses to produce one for any step whose write it knows
        // will be a relocation, which now includes every step of a host with no in-place move at all; so
        // this is the belt to that braces rather than the only guard.
        final double toY = this.sample.y();
        final double toZ = this.sample.z() + move.deltaZ();

        this.host.teleport(toX, toY, toZ, move.yawDegrees(), succeeded -> this.relocating = false);

        // After the call rather than before it, so that nothing can read a destination this layer has
        // not actually asked for. See the class note above for what reads it.
        this.sample.set(toX, toY, toZ, move.yawDegrees());
    }

    /**
     * Re-dispatches the control loop if configuration moved its period.
     *
     * <p>Explicit rather than detected per step, for the reason
     * {@link pandastic.studio.models.internal.binding.RiggedModelImpl#reload()} is: a repeating task's
     * rate belongs to the platform, so the only way to change it is to cancel and dispatch again, which
     * a task cannot do to itself from inside its own body.
     *
     * <p>Cancels before dispatching, never the other way round: two live loops would both difference the
     * same clock field and each would see half the elapsed time, so the vehicle would travel at half
     * speed while apparently being decided twice as often.
     *
     * @return whether the loop was re-dispatched
     */
    public synchronized boolean reload() {
        if (!this.alive) {
            return false;
        }
        final long periodTicks = this.settings.current().controlPeriodTicks();
        if (periodTicks == this.scheduledPeriodTicks) {
            return false;
        }

        final VehicleTask previous = this.controlTask;
        this.controlTask = null;
        if (previous != null) {
            previous.cancel();
        }

        final VehicleTask scheduled = this.host.repeat(this::step, this::onRootRetired, periodTicks);
        if (scheduled == null) {
            // The root retired while we were re-dispatching, and we have just cancelled the only task
            // that would have told us. Nothing else will, so stop here.
            stop(false);
            return false;
        }
        this.controlTask = scheduled;
        this.scheduledPeriodTicks = periodTicks;
        return true;
    }

    /** The root entity, and the only thing allowed to touch it. */
    public @NotNull VehicleHost host() {
        return this.host;
    }

    /** The model riding on this vehicle. */
    public @NotNull VehicleVisual visual() {
        return this.visual;
    }

    /**
     * This vehicle's seats: who is in them, and the carriers they are riding.
     *
     * <p>Exposed for the same reason {@link #host()} and {@link #visual()} are — the layers above this
     * one are assembled from internal types — and used by nothing in production. A test drives it
     * directly, because the seating rules are worth asserting without a control loop around them.
     */
    public @NotNull VehicleSeats seats() {
        return this.seats;
    }

    /**
     * The consumer's controller for this vehicle, wrapped in its containment.
     *
     * <p>Exposed for the same reason {@link #seats()} is — a test drives it directly — and for a
     * diagnostic: {@link VehicleControllerDispatch#isDetached()} is the only way to find out that a
     * vehicle has given up on its controller without reading the log.
     */
    public @NotNull VehicleControllerDispatch controllerDispatch() {
        return this.controller;
    }

    /**
     * What steers this vehicle while the driver's seat is empty.
     *
     * <p>{@link VehiclePilot#NONE} when there is nothing. Exposed for the same reason
     * {@link #controllerDispatch()} is — a test drives it directly, and a diagnostic has no other way to
     * find out whether a vehicle that is not moving is one nothing is piloting or one whose pilot is
     * asking for nothing.
     */
    public @NotNull VehiclePilot pilot() {
        return this.pilot;
    }

    /** Whether this handle has torn down and can no longer start. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    /** The period the live control loop was dispatched at, or the last one if it is not running. */
    public synchronized long scheduledPeriodTicks() {
        return this.scheduledPeriodTicks;
    }

    /** Whether a relocation is in flight, during which the loop decides nothing new. */
    public boolean isRelocating() {
        return this.relocating;
    }

    /**
     * How the model is currently sitting on the terrain, and any climb in flight.
     *
     * <p>One read of one immutable value published whole by the control step, so it is safe from any
     * thread — the same contract {@link #getState()} has. Deliberately not on the published api: it is
     * internal state of a reaction, and a consumer that wants to know how a vehicle is leaning is asking
     * for something the renderer is already showing them.
     */
    public @NotNull TerrainAttitude getAttitude() {
        return this.attitude;
    }

    private void requireOwningThread(@NotNull String verb) {
        if (this.host.isOwnedByCurrentThread()) {
            return;
        }
        // Guarded here because the platform will not guard it: a position write or a mount from a thread
        // that does not own the entity is undefined rather than merely racy, and it fails silently.
        throw new IllegalStateException("Vehicle " + this.uniqueIdentifier + " on "
                + this.host.describe() + " cannot be " + verb + " from this thread: the operation reads"
                + " and writes the root entity, which is only defined on the thread that owns it."
                + " Schedule it on that thread.");
    }

    @Override
    public @NotNull String toString() {
        return "Vehicle[" + this.rig.getName() + " on " + this.host.describe()
                + ", alive=" + this.alive + "]";
    }
}
