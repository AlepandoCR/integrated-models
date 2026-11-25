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
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.SeatInput;
import pandastic.studio.models.api.vehicle.SeatInputListener;
import pandastic.studio.models.api.vehicle.SeatOccupancy;
import pandastic.studio.models.api.vehicle.SeatPlan;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleControls;
import pandastic.studio.models.api.vehicle.VehicleMessages;
import pandastic.studio.models.api.vehicle.VehicleMove;
import pandastic.studio.models.api.vehicle.VehicleNotice;
import pandastic.studio.models.api.vehicle.VehicleNoticeContext;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSeat;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.api.vehicle.VehicleState;
import pandastic.studio.models.api.vehicle.VehicleSteering;
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
 * The rule is <em>stop deciding, then release what was being decided about</em>: cancel the loop, forget
 * the rider, then put the rider down and release the visual. Three things can decide a vehicle is
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

    /** Published by the control step, read from anywhere. One store per step, of a complete value. */
    private volatile @NotNull VehicleState state = VehicleState.STOPPED;

    /** Published by the control step, read from anywhere. */
    private volatile @NotNull VehicleSteering steering = VehicleSteering.NEUTRAL;

    /** The control step's own gate. A body that lands after the teardown finds false and does nothing. */
    private volatile boolean alive;

    /**
     * Whether a relocation is in flight.
     *
     * <p>Set on the region thread and cleared on whichever thread finishes the relocation, which is why
     * it is volatile. While it is set the control loop decides nothing and writes nothing: a
     * cross-region teleport removes the entity and its whole passenger tree from the world before
     * re-adding it, so for part of that window there is no entity to move and moving it anyway fails
     * confusingly later rather than immediately.
     */
    private volatile boolean relocating;

    /** Region-confined: the clock reading the previous step took. */
    private long previousNanos;

    /** Region-confined: whether {@link #previousNanos} means anything yet. */
    private boolean clockSeeded;

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
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
        this.seats = new VehicleSeats(rig, host, this.seatEvents);
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
        this.relocating = false;

        // 3. and 4. Put the rider down, release the visual.
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

        // One input read per occupied seat, before anything is decided. The driver's becomes the intent
        // the solver sees and every seat's is handed to its listener after the move — so the steering and
        // a consumer's listener cannot disagree about what a rider did within one step. Reading the
        // source twice is the straddling hazard the snapshot read above avoids the same way.
        this.seats.readInputs(this.inputs);

        // Only the driver's input steers. A seat that emptied — a rider who held sneak, disconnected,
        // died or was removed — is noticed by the follow pass below, which reports it; by the time the
        // next step reads this there is no driver and the vehicle coasts. Nobody is promoted into the
        // empty seat.
        final RiderIntent intent = RiderInputSource.intentOf(this.seats.driverInput().state());

        final VehicleState previous = this.state;
        final VehicleStepper.Plan plan =
                VehicleStepper.plan(previous, intent, this.profile, current, elapsedNanos);

        this.steering = plan.steering();
        this.state = plan.move().next();
        if (!plan.isIdle()) {
            apply(plan, previous);
            if (this.relocating) {
                // The root is transiently out of the world, so there is no position to seat anybody
                // relative to. The seats catch up on the step after the relocation completes, through
                // their own teleports, which is what the carrier step ceiling is for.
                return;
            }
            // Re-read, because the write that just happened may have been partly applied: a vehicle
            // stopped by a wall is somewhere other than where it was asked to go, and a seat computed
            // from the commanded position would put its rider inside the wall.
            if (!this.host.readInto(this.sample)) {
                stop(false);
                return;
            }
        }

        // Then, and unconditionally. A parked vehicle still animates, and an animated locator moves the
        // seat hanging off it — gating this on the vehicle having moved would leave a rider behind
        // whenever an idle clip breathed.
        this.seats.follow(this.sample, current.seats());

        // Last, after the follow pass, so a listener never sees a rider whose seat this step has just
        // given up. Skipped entirely when the consumer wired nothing, which is the common case and
        // costs one reference comparison.
        if (!this.setup.controls().isEmpty()) {
            this.seats.forEachOccupied(this.inputVisitor);
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
     * Hands one occupied seat's input to the consumer's listener.
     *
     * <p>The body of {@link #inputVisitor}. Allocates a {@link SeatInput} only for a seat that has a
     * listener, so a vehicle whose consumer wired one of four seats allocates one value per step rather
     * than four.
     *
     * <p>Anything the listener throws is reported and swallowed. It has to be: the platform swallows
     * whatever escapes a scheduled body and keeps the task alive, so an exception let through here would
     * silently stop the rest of this step — including the seats after this one — for as long as the
     * consumer's bug lasts.
     */
    private void dispatchSeatInput(
            @NotNull VehicleSeat seat,
            @NotNull Player rider,
            @NotNull InputSnapshot input,
            @NotNull InputState previous
    ) {
        final SeatInputListener[] listeners = this.seatListeners;
        final int index = seat.index();
        if (index < 0 || index >= listeners.length) {
            return;
        }
        final SeatInputListener listener = listeners[index];
        if (listener == null) {
            return;
        }
        try {
            listener.onInput(new SeatInput(this, seat, rider, input, previous, seat.isDriver()));
        } catch (final RuntimeException thrown) {
            this.logger.error("A seat input listener for seat #" + index + " '" + seat.name()
                    + "' of vehicle " + this.uniqueIdentifier + " on " + this.host.describe()
                    + " failed", thrown);
        }
    }

    /**
     * {@code rider} has just been seated in {@code seat}.
     *
     * <p>From {@link SeatEvents}, on the owning thread, inside the mount that caused it. The message is
     * about which seat they got, because that is the thing they cannot see: a seat is an invisible
     * carrier on a locator, so nothing on screen distinguishes driving from being carried.
     */
    private void seated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        send(rider, seat.isDriver() ? VehicleNotice.NOW_DRIVER : VehicleNotice.NOW_PASSENGER, seat);
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
     */
    private void vacated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        if (seat.isDriver()) {
            this.steering = VehicleSteering.NEUTRAL;
        }
        send(rider, VehicleNotice.DISMOUNTED, seat);
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
     * <p>A {@link MoveKind#LOCAL} displacement becomes a relocation when the host has no in-place move —
     * which is what a server running with no Mojang-mapped adapter looks like. That is expensive rather
     * than wrong: every step becomes a scheduled transaction, and the vehicle still goes where the server
     * said.
     *
     * <p>{@link MoveKind#NONE} with a changed heading is a real case rather than a leftover: a vehicle
     * turning on the spot has no displacement at all and still has to be written, which is why the
     * heading is applied on its own branch instead of alongside the move.
     */
    private void apply(@NotNull VehicleStepper.Plan plan, @NotNull VehicleState previous) {
        final VehicleMove move = plan.move();

        if (plan.kind() == MoveKind.TELEPORT
                || (plan.kind() == MoveKind.LOCAL && !this.host.supportsLocalMove())) {
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
     */
    private void relocate(@NotNull VehicleMove move) {
        this.relocating = true;
        this.host.teleport(
                this.sample.x() + move.deltaX(),
                this.sample.y() + move.deltaY(),
                this.sample.z() + move.deltaZ(),
                move.yawDegrees(),
                succeeded -> this.relocating = false);
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

    /** Whether this handle has torn down and can no longer start. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    /** The period the live control loop was dispatched at, or the last one if it is not running. */
    public synchronized long scheduledPeriodTicks() {
        return this.scheduledPeriodTicks;
    }

    /** Whether a relocation is in flight, during which the loop decides nothing. */
    public boolean isRelocating() {
        return this.relocating;
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
