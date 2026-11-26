package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.SeatInput;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleController;
import pandastic.studio.models.api.vehicle.VehicleSeat;
import pandastic.studio.models.internal.input.InputFaultSink;

import java.util.Objects;

/**
 * One vehicle's view of its consumer's {@link VehicleController}: the five hooks, contained.
 *
 * <h2>Why the controller is not called from the control step directly</h2>
 * Because every one of those calls is consumer code on a hot path and the handling is identical, so
 * there is exactly one place that gets it wrong or right. The step says "the controller is told about
 * this"; this class is the whole answer to "and what if it throws".
 *
 * <p>It is also what keeps the controller <em>adapted onto</em> the per-seat mechanism rather than
 * duplicating it. {@link VehicleImpl} resolves one {@link SeatInput} per occupied seat for the
 * consumer's {@link pandastic.studio.models.api.vehicle.SeatInputListener}s already; the controller is
 * handed that same value, after that seat's listener, from inside the same
 * {@link VehicleSeats#forEachOccupied} pass. So the two mechanisms cannot disagree about what a rider
 * did, and a consumer that supplies both pays for one read and one allocation rather than two.
 *
 * <h2>The fault policy is the input layer's, deliberately not a second one</h2>
 * {@link InputFaultSink} is the policy a bound {@link pandastic.studio.models.api.input.InputHandler}
 * already gets — contain, report at most once per configured interval per failing thing, and stop
 * calling it once it has failed as many times <em>in a row</em> as the configuration permits, with a
 * success resetting the count. A vehicle controller is consumer code reacting to rider input on a
 * scheduled body; it is the same situation and it gets the same answer, out of the same two keys. A
 * second pair of keys would be a second answer to one question, and the two would drift.
 *
 * <p>What is caught is {@link RuntimeException} and {@link LinkageError}, matching that layer exactly: a
 * consumer's hook can reference a class its own plugin has since unloaded, which is survivable, while an
 * {@link Error} the JVM raised about itself is not something to count and carry on from.
 *
 * <h2>What the counters are keyed by, and why not by vehicle</h2>
 * The controller's class, the rig, and the hook — so forty tanks sharing one controller share one
 * counter and therefore one report per interval, instead of forty. That is the whole point of a
 * throttle: the operator needs to know a controller is broken, not how many vehicles are running.
 * Keying by vehicle would also grow the sink's map by one entry per vehicle that ever failed.
 *
 * <p>The <em>detach</em> is per vehicle even though the count is shared, and that is not an
 * inconsistency. The count answers "is this code broken", which is a property of the code. The detach
 * answers "is this object's state still worth building on", which is a property of one vehicle's
 * controller state — so each vehicle detaches when a call of its own fails and the shared count has
 * already run out. One object holds one set of invariants, which is why the detach is the whole
 * controller rather than the one hook that threw.
 *
 * <h2>Threading</h2>
 * Built on whichever thread created the vehicle and used from the thread that owns its root entity,
 * except {@link #closed(Vehicle)} — which is reached from the teardown and therefore from anywhere, so
 * {@link #detached} is volatile. Nothing else here holds mutable state.
 *
 * <h2>Allocation</h2>
 * None, per step. The containment is written out at each hook rather than factored into a method taking
 * a {@code Runnable}, because that {@code Runnable} would be a capturing lambda allocated once per
 * rider per step — and the five descriptions are built once, at construction, for the same reason.
 */
public final class VehicleControllerDispatch {

    /**
     * A dispatch that does nothing, for a vehicle whose consumer supplied
     * {@link VehicleController#NONE}.
     *
     * <p>Shared, because it holds nothing that matters: {@link #isActive()} is false for it and every
     * hook checks {@code inert} first, so neither of its two flags can ever be set and there is no per
     * vehicle state to keep apart.
     */
    public static final @NotNull VehicleControllerDispatch INERT = new VehicleControllerDispatch(
            VehicleController.NONE, InputFaultSink.ignoring(), "");

    private final @NotNull VehicleController controller;
    private final @NotNull InputFaultSink faults;

    /** Whether there is anything to call at all. A constant for this dispatch's life. */
    private final boolean inert;

    /**
     * One description per hook, which is both the sink's key and what the report names.
     *
     * <p>Built once. The sink's report reads "An input handler bound to &lt;this&gt; threw", so each
     * reads as a noun phrase naming the controller, the rig and the hook.
     */
    private final @NotNull String playerInputKey;
    private final @NotNull String seatedKey;
    private final @NotNull String vacatedKey;
    private final @NotNull String stepKey;
    private final @NotNull String closedKey;

    /**
     * Whether this vehicle has given up on its controller.
     *
     * <p>Set from the owning thread and read from the teardown, which can be any thread.
     */
    private volatile boolean detached;

    /**
     * Whether {@link #closed(Vehicle)} has already run.
     *
     * <p>What makes "the last call a controller receives" true structurally rather than by inspection. A
     * controller is allowed to ask the vehicle to stop from inside a hook — {@code onStep}'s own
     * documentation offers "deciding to shut down" as a use — and the teardown that follows runs
     * <em>inside</em> that hook's frame. Without this flag the rest of the step would carry on calling a
     * controller that had already been told the vehicle was gone: the seats after the one being
     * dispatched, and the step hook itself.
     *
     * <p>Volatile for the reason {@link #detached} is: the teardown is reached from any thread.
     */
    private volatile boolean closed;

    private VehicleControllerDispatch(
            @NotNull VehicleController controller,
            @NotNull InputFaultSink faults,
            @NotNull String rigName
    ) {
        this.controller = controller;
        this.faults = faults;
        this.inert = controller == VehicleController.NONE;
        final String subject = "a vehicle controller (" + controller.getClass().getSimpleName()
                + ", rig '" + rigName + "')";
        this.playerInputKey = subject + " in onPlayerInput";
        this.seatedKey = subject + " in onSeated";
        this.vacatedKey = subject + " in onVacated";
        this.stepKey = subject + " in onStep";
        this.closedKey = subject + " in onClosed";
    }

    /**
     * The dispatch for one vehicle.
     *
     * <p>{@link VehicleController#NONE} answers {@link #INERT} rather than a fresh object: the common
     * case is a vehicle with no controller at all, and that case should cost nothing per vehicle.
     *
     * @param controller what the consumer supplied, through {@code VehicleSetup}
     * @param faults     where a hook's exception goes, and what decides whether to keep calling it
     * @param rigName    the rig this vehicle renders, which is what names the controller's kind in a
     *                   report. One counter per kind rather than per vehicle; see the class note
     * @return the dispatch
     */
    public static @NotNull VehicleControllerDispatch of(
            @NotNull VehicleController controller,
            @NotNull InputFaultSink faults,
            @NotNull String rigName
    ) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(faults, "faults");
        Objects.requireNonNull(rigName, "rigName");
        if (controller == VehicleController.NONE) {
            return INERT;
        }
        return new VehicleControllerDispatch(controller, faults, rigName);
    }

    /**
     * Whether any hook would actually reach the consumer.
     *
     * <p>What the control step gates its per-seat pass on, so a vehicle with no controller and no
     * per-seat listeners builds no {@link SeatInput} at all. False for a controller that has been
     * detached, which is what makes the detach free rather than a wrapped no-op per rider per step, and
     * false once the vehicle has closed — which is what makes {@link #closed(Vehicle)} the last call
     * even when the controller is itself what asked for the teardown.
     */
    public boolean isActive() {
        return !this.inert && !this.detached && !this.closed;
    }

    /** Whether this vehicle has stopped calling its controller because it kept throwing. */
    public boolean isDetached() {
        return this.detached;
    }

    /** What the consumer supplied, for a diagnostic and for a test. */
    public @NotNull VehicleController controller() {
        return this.controller;
    }

    /**
     * Offers one occupied seat's input, after that seat's own listener has had it.
     *
     * @param input the seat, the rider and what they are asking for, as this step read it
     */
    public void playerInput(@NotNull SeatInput input) {
        if (!isActive()) {
            return;
        }
        try {
            this.controller.onPlayerInput(input);
        } catch (final RuntimeException | LinkageError failure) {
            failed(this.playerInputKey, failure);
            return;
        }
        this.faults.succeeded(this.playerInputKey);
    }

    /**
     * Tells the controller a rider is aboard, after the seat is theirs and after they have been told.
     *
     * @param vehicle the vehicle they boarded
     * @param seat    where they ended up
     * @param rider   who boarded
     */
    public void seated(
            @NotNull Vehicle vehicle,
            @NotNull VehicleSeat seat,
            @NotNull Player rider
    ) {
        if (!isActive()) {
            return;
        }
        try {
            this.controller.onSeated(vehicle, seat, rider);
        } catch (final RuntimeException | LinkageError failure) {
            failed(this.seatedKey, failure);
            return;
        }
        this.faults.succeeded(this.seatedKey);
    }

    /**
     * Tells the controller a seat has emptied, however it emptied.
     *
     * @param vehicle the vehicle they left
     * @param seat    the seat that is now free
     * @param rider   who left
     */
    public void vacated(
            @NotNull Vehicle vehicle,
            @NotNull VehicleSeat seat,
            @NotNull Player rider
    ) {
        if (!isActive()) {
            return;
        }
        try {
            this.controller.onVacated(vehicle, seat, rider);
        } catch (final RuntimeException | LinkageError failure) {
            failed(this.vacatedKey, failure);
            return;
        }
        this.faults.succeeded(this.vacatedKey);
    }

    /**
     * Closes one control step, after every occupied seat's input has been offered.
     *
     * @param vehicle    the vehicle being stepped
     * @param deltaTicks the measured, clamped ticks this step covered — the figure the solver
     *                   integrated, not the configured period
     */
    public void step(@NotNull Vehicle vehicle, float deltaTicks) {
        if (!isActive()) {
            return;
        }
        try {
            this.controller.onStep(vehicle, deltaTicks);
        } catch (final RuntimeException | LinkageError failure) {
            failed(this.stepKey, failure);
            return;
        }
        this.faults.succeeded(this.stepKey);
    }

    /**
     * The last call, and the only one a detached controller still gets.
     *
     * <p>Gated on {@link #inert} alone rather than on {@link #isActive()}, which is the one asymmetry in
     * this class: the call is bounded — the teardown it comes from runs exactly once — and it is the
     * only hook whose purpose is releasing what the consumer opened. Nothing is reported as succeeding
     * either, because there is nothing left to reset a count for.
     *
     * @param vehicle the vehicle being torn down
     */
    public void closed(@NotNull Vehicle vehicle) {
        if (this.inert || this.closed) {
            return;
        }
        // Before the call rather than after it, so a controller that asks the vehicle to stop from
        // inside its own onClosed is not told twice. The vehicle's teardown is already latched; this is
        // the same latch read from the side that matters to the consumer.
        this.closed = true;
        try {
            this.controller.onClosed(vehicle);
        } catch (final RuntimeException | LinkageError failure) {
            failed(this.closedKey, failure);
        }
    }

    /**
     * Reports one failure and detaches if the policy says this controller has had enough chances.
     *
     * <p>One call rather than a report and a question, because the sink's count is changed by the act of
     * reporting; see {@link InputFaultSink}.
     */
    private void failed(@NotNull String described, @NotNull Throwable failure) {
        if (!this.faults.failed(described, failure)) {
            this.detached = true;
        }
    }

    /** Whether the vehicle has closed, after which no hook of this dispatch reaches the consumer. */
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public @NotNull String toString() {
        return "VehicleControllerDispatch[" + (this.inert ? "none" : this.stepKey)
                + (this.detached ? ", detached" : "") + (this.closed ? ", closed" : "") + ']';
    }
}
