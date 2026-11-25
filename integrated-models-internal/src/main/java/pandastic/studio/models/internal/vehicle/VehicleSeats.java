package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.BoardingOutcome;
import pandastic.studio.models.api.vehicle.BoardingResult;
import pandastic.studio.models.api.vehicle.SeatOccupancy;
import pandastic.studio.models.api.vehicle.SeatOccupant;
import pandastic.studio.models.api.vehicle.SeatPlan;
import pandastic.studio.models.api.vehicle.VehicleSeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every seat of one vehicle: its index, who is in it, the carrier they are riding, and the locator it
 * tracks.
 *
 * <h2>What a seat is, and what it is not</h2>
 * A seat is a <strong>locator</strong> — the empty marker a rigger places in Blockbench — and never a
 * bone. The distinction is not pedantry: a bone is geometry the animation pass writes, so a rider seated
 * on one would be moved by whichever clip happens to be playing, while a locator exists for exactly this
 * and nothing else. A model that carries a bone called {@code mount} therefore has no seat, and a seat
 * naming it is reported as {@link SeatLocators.Kind#BONE} and left unusable; see {@link SeatLocators}.
 *
 * <h2>Seats are indexed, and the index comes from {@link SeatPlan}</h2>
 * The driver's seat is index {@link SeatPlan#DRIVER_INDEX} and the rest follow in the rig file's own
 * order. Boarding takes the <strong>lowest free index</strong>, where free means usable and empty — so
 * the first player to board drives, the second takes index one, and so on until every usable seat is
 * taken. That is one rule rather than a rule plus a driver special case, which is the whole reason the
 * plan puts the driver first.
 *
 * <p>An index is stable for the life of the loaded rig, including across a seat that failed to resolve:
 * an unusable seat keeps its number instead of being skipped, because renumbering around it would move
 * every seat behind it whenever a rigger renamed a locator, and an index that moves is not an address.
 *
 * <p><strong>Nobody is ever promoted.</strong> When the driver leaves, the driver's seat empties and the
 * vehicle carries its passengers undriven; the next person to <em>board</em> gets it, because it is now
 * the lowest free index. The reasoning is in
 * {@link pandastic.studio.models.api.vehicle.Vehicle#board(Player)} and it is a decision rather than an
 * omission.
 *
 * <h2>How a rider is actually seated</h2>
 * Nobody can ride a point, so each <em>occupied</em> seat gets an invisible {@link SeatCarrier} the player
 * rides and this class keeps on top of the locator. One per occupied seat, created on mount and removed
 * the moment the seat empties — a vehicle with four declared seats and one rider owns one carrier.
 *
 * <h2>Input is read once per occupied seat per step</h2>
 * {@link #readInputs} is called at the top of a control step, before anything is decided, and stores one
 * snapshot per rider. The steering reads the driver's from {@link #driverInput()} and the per-seat
 * listeners read all of them from {@link #forEachOccupied}, so the two cannot disagree about what a
 * rider did within one step. Reading the source twice is the straddling hazard the whole project avoids
 * the same way.
 *
 * <h2>Thread confinement, and the one exception</h2>
 * Everything here runs on the thread that owns the vehicle's root entity: resolution, mounting,
 * following, vacating, reading input. That is the vehicle's control loop, dispatched from the root's own
 * entity scheduler, so it follows the root across region and world changes — which is what makes it
 * correct to write a carrier's position from it, since a carrier sits within a block of the model and
 * Folia owns nearby chunks together. It is still checked per carrier per step rather than assumed,
 * because a cross-region write on this platform is silently discarded rather than refused.
 *
 * <p>The exception is {@link #release()}, which must work from a teardown triggered anywhere — including
 * the platform's retired callback for a root entity that no longer exists, where the root's scheduler is
 * guaranteed to be dead. Each carrier therefore releases itself through its own entity scheduler, and
 * this class only has to ask.
 *
 * <p>{@link #driver()}, {@link #riders()}, {@link #occupancy()} and {@link #chart()} are readable from
 * any thread: each is one read of one immutable value published whole by the owning thread.
 */
public final class VehicleSeats {

    /**
     * Degrees to radians.
     *
     * <p>A mathematical fact rather than a tunable: a seat offset is authored in the model's own frame and
     * has to be turned by the vehicle's heading, which the platform states in degrees.
     */
    private static final double DEGREES_TO_RADIANS = Math.PI / 180D;

    /**
     * Asking for the lowest free seat rather than a particular one.
     *
     * <p>The same {@code -1} {@link VehicleSeat#NO_SEAT} is, and deliberately the same value: "no
     * particular seat" and "no seat at all" are the two readings of an index that addresses nothing, and
     * a second sentinel would let the two drift apart.
     */
    public static final int ANY_SEAT = VehicleSeat.NO_SEAT;

    private final @NotNull Rig rig;
    private final @NotNull VehicleHost host;
    private final @NotNull SeatEvents events;

    /** The declared seats, indexed, driver first. Fixed for this vehicle's life. */
    private final @NotNull SeatPlan plan;

    /** One per declared seat, in index order, which is the order riders are offered them. */
    private final @NotNull List<Slot> slots = new ArrayList<>();

    /** Reused by the follow step: the locator's offset, then the carrier's position. */
    private final @NotNull SeatSample locatorSample = new SeatSample();
    private final @NotNull SeatSample carrierSample = new SeatSample();

    /** Published whole by the owning thread; read from anywhere. */
    private volatile @NotNull SeatChart chart = SeatChart.EMPTY;

    /** Published whole by the owning thread; read from anywhere. */
    private volatile @NotNull SeatOccupancy occupancy = SeatOccupancy.EMPTY;

    /** Published by the owning thread; read from anywhere. The rider whose input steers, if any. */
    private volatile @Nullable Player driver;

    private boolean resolved;

    /**
     * @param rig  the rig whose {@code seats:} section this seats riders from
     * @param host the root entity, and the thing that spawns carriers in its world
     */
    public VehicleSeats(@NotNull Rig rig, @NotNull VehicleHost host) {
        this(rig, host, SeatEvents.NONE);
    }

    /**
     * @param rig    the rig whose {@code seats:} section this seats riders from
     * @param host   the root entity, and the thing that spawns carriers in its world
     * @param events told when a seat fills or empties, which is where a vehicle's messages come from
     */
    public VehicleSeats(@NotNull Rig rig, @NotNull VehicleHost host, @NotNull SeatEvents events) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.host = Objects.requireNonNull(host, "host");
        this.events = Objects.requireNonNull(events, "events");
        this.plan = SeatPlan.of(rig);
    }

    /** The declared seats, indexed, driver first. Fixed for this vehicle's life; safe from anywhere. */
    public @NotNull SeatPlan plan() {
        return this.plan;
    }

    /**
     * Resolves every declared seat against the live model, once.
     *
     * <p>Called by {@link VehicleImpl#start()} after the visual has spawned, because until then there is
     * no model to resolve against. A seat that resolves to a bone or to nothing at all is kept in the
     * chart, keeps its index, and is left unusable: a vehicle binds with the seats it has, and the ones
     * it has not are the finding rather than a failure.
     *
     * @param locators the live model's locators, or {@link SeatLocators#NONE} for a vehicle with no model
     * @return the chart, which is what gets logged
     */
    public @NotNull SeatChart resolve(@NotNull SeatLocators locators) {
        Objects.requireNonNull(locators, "locators");
        if (this.resolved) {
            return this.chart;
        }
        this.resolved = true;

        final List<SeatChart.Entry> entries = new ArrayList<>(this.plan.size());
        for (final VehicleSeat seat : this.plan.seats()) {
            final SeatLocators.Resolution resolution = locators.resolve(seat.locator());
            this.slots.add(new Slot(seat, resolution));
            entries.add(new SeatChart.Entry(seat, resolution.kind()));
        }
        final SeatChart built = new SeatChart(entries);
        this.chart = built;
        publish();
        return built;
    }

    /** Every declared seat and what it turned out to be. Empty until {@link #resolve} has run. */
    public @NotNull SeatChart chart() {
        return this.chart;
    }

    /**
     * Who is in which seat, as one value from one moment.
     *
     * <p>Published whole by the thread that seats people, so a reader that sees a new occupancy sees
     * every seat of it from the same instant. Empty until {@link #resolve} has run.
     */
    public @NotNull SeatOccupancy occupancy() {
        return this.occupancy;
    }

    /** The rider whose input steers, or {@code null} if the driver's seat is empty or does not exist. */
    public @Nullable Player driver() {
        return this.driver;
    }

    /** Everybody aboard, in seat index order. Allocates; {@link #occupancy()} is the cheap form. */
    public @NotNull List<Player> riders() {
        return this.occupancy.riders();
    }

    /** How many seats are occupied. */
    public int occupied() {
        return this.occupancy.occupied();
    }

    /** How many seats a rider could be offered, occupied or not. */
    public int usableSeats() {
        return this.chart.usableCount();
    }

    /**
     * Seats {@code rider} in the lowest free seat, or in the seat at {@code seatIndex}.
     *
     * <p>Lowest free index, where free means usable and empty — so the driver's seat first, because the
     * plan puts it at index zero. A request for a particular index never falls back to another seat: an
     * occupied one answers {@link BoardingOutcome#SEAT_TAKEN} and an index no seat has answers
     * {@link BoardingOutcome#NO_SUCH_SEAT}, so a caller that meant seat two is told rather than quietly
     * given seat three.
     *
     * <p>Owning thread only. The client-authority question is <strong>not</strong> asked here: it is
     * asked by {@link VehicleImpl}, about the driver's seat only, because a passenger whose client thinks
     * it is steering a carrier steers nothing that matters while a driver's does.
     *
     * @param rider     who to seat
     * @param seatIndex the seat to put them in, or {@link #ANY_SEAT} for the lowest free one
     * @param root      the root entity's position and heading, as this step read them
     * @param settings  where the carrier type and its vertical trim come from
     * @return what happened, including which seat when it worked
     */
    public @NotNull BoardingResult mount(
            @NotNull Player rider,
            int seatIndex,
            @NotNull VehicleSample root,
            @NotNull SeatSettings settings
    ) {
        Objects.requireNonNull(rider, "rider");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(settings, "settings");

        for (final Slot slot : this.slots) {
            if (rider.equals(slot.rider)) {
                return BoardingResult.of(BoardingOutcome.ALREADY_ABOARD, slot.seat);
            }
        }

        final Slot chosen = choose(seatIndex);
        if (chosen == null) {
            return refusal(seatIndex);
        }

        // The carrier is spawned where the rider is going to sit rather than at the vehicle's feet: a
        // carrier that appeared at the root and was moved into place on the next step would mount the
        // rider a block low for a tick, which is a visible drop every time anybody sits down.
        if (!chosen.resolution.requireAnchor().readInto(this.locatorSample)) {
            return BoardingResult.of(BoardingOutcome.MODEL_GONE, chosen.seat);
        }
        final double x = root.x() + this.locatorSample.x()
                + offsetWorldX(root.yawDegrees(), chosen.offsetX(), chosen.offsetZ());
        final double y = root.y() + this.locatorSample.y()
                + chosen.offsetY() + settings.carrierOffsetYBlocks();
        final double z = root.z() + this.locatorSample.z()
                + offsetWorldZ(root.yawDegrees(), chosen.offsetX(), chosen.offsetZ());

        final SeatCarrier carrier =
                this.host.spawnCarrier(settings.carrierEntityType(), x, y, z, root.yawDegrees());
        if (carrier == null) {
            return BoardingResult.of(BoardingOutcome.CARRIER_UNAVAILABLE, chosen.seat);
        }
        if (!carrier.mount(rider)) {
            // A cross-region mount, which the platform refuses. The carrier would otherwise be an
            // invisible entity nobody rides and nothing owns, so it goes back immediately.
            carrier.release();
            return BoardingResult.of(BoardingOutcome.CARRIER_UNAVAILABLE, chosen.seat);
        }

        chosen.rider = rider;
        chosen.carrier = carrier;
        chosen.yawWritten = root.yawDegrees();
        chosen.input = null;
        chosen.previousState = InputState.NONE;
        publish();
        this.events.onSeated(chosen.seat, rider);
        return BoardingResult.of(BoardingOutcome.SEATED, chosen.seat);
    }

    /**
     * Why nobody could be seated, which is four different answers and not one.
     *
     * <p>"Full" and "this vehicle has no seat anybody can sit on" are different facts and the player gets
     * a different message for each. The second is the <em>expected</em> one today: no model in this
     * repository carries a locator element, so every seat a rig declares resolves to nothing until a
     * rigger adds one.
     */
    private @NotNull BoardingResult refusal(int seatIndex) {
        if (seatIndex == ANY_SEAT) {
            return usableSeats() == 0 ? BoardingResult.NO_SEAT_RESOLVED : BoardingResult.VEHICLE_FULL;
        }
        if (!this.plan.hasIndex(seatIndex)) {
            return BoardingResult.NO_SUCH_SEAT;
        }
        final Slot addressed = slotAt(seatIndex);
        if (addressed == null) {
            // The plan has the index and resolve() has not run, so there is no slot to put anybody in.
            // A vehicle resolves its seats as it starts, so this is a caller racing the start.
            return BoardingResult.NO_SEAT_RESOLVED;
        }
        return addressed.isUsable()
                ? BoardingResult.of(BoardingOutcome.SEAT_TAKEN, addressed.seat)
                : BoardingResult.of(BoardingOutcome.SEAT_UNUSABLE, addressed.seat);
    }

    /**
     * Whether the server would let {@code rider}'s client simulate the carrier they are riding.
     *
     * <p>Asked by {@link VehicleImpl} about the driver only, immediately after seating them, because the
     * server's own answer depends on who is aboard what — so before the mount there is nothing to ask.
     * A rider who is not in any of these seats answers {@code false}: there is no carrier to be
     * authoritative about.
     *
     * <p>Owning thread only, because the authoritative answer reads the carrier's passenger list.
     */
    public boolean isClientSteered(@NotNull Player rider) {
        Objects.requireNonNull(rider, "rider");
        for (final Slot slot : this.slots) {
            if (rider.equals(slot.rider) && slot.carrier != null) {
                return slot.carrier.isClientSteered(rider);
            }
        }
        return false;
    }

    /**
     * Puts {@code rider} down, if they are in one of these seats.
     *
     * <p>Owning thread only.
     *
     * @return whether they were aboard
     */
    public boolean dismount(@NotNull Player rider) {
        Objects.requireNonNull(rider, "rider");
        for (final Slot slot : this.slots) {
            if (rider.equals(slot.rider)) {
                vacate(slot, true);
                publish();
                return true;
            }
        }
        return false;
    }

    /**
     * Puts everybody down and removes every carrier.
     *
     * <p>Owning thread only, and the ordinary teardown: see {@link #release()} for the one that has to
     * work from anywhere.
     *
     * @return how many riders were put down
     */
    public int dismountAll() {
        int removed = 0;
        for (final Slot slot : this.slots) {
            if (slot.carrier != null || slot.rider != null) {
                vacate(slot, true);
                removed++;
            }
        }
        publish();
        return removed;
    }

    /**
     * Releases every carrier from wherever this is called.
     *
     * <p>The teardown of last resort, and the one that matters: a leaked carrier is an invisible entity
     * carrying a player that nothing will ever remove. Each carrier releases itself through its own
     * entity scheduler, so this works when the root entity is gone — which is exactly when it is needed,
     * because that is the one case where the thread the rest of this class runs on no longer exists.
     *
     * <p>Reports nothing to {@link SeatEvents}, deliberately: this is the shutdown path, it can run from
     * a thread that may touch almost nothing, and a player told "you are no longer riding" as the server
     * stops is noise at best.
     *
     * <p>Idempotent and safe from any thread.
     *
     * @return how many carriers were asked to release
     */
    public int release() {
        int released = 0;
        for (final Slot slot : this.slots) {
            final SeatCarrier carrier = slot.carrier;
            slot.carrier = null;
            slot.rider = null;
            slot.relocating = false;
            slot.recoverRider = false;
            slot.input = null;
            slot.previousState = InputState.NONE;
            if (carrier != null) {
                carrier.release();
                released++;
            }
        }
        this.driver = null;
        this.occupancy = vacantOccupancy();
        return released;
    }

    /**
     * Reads one input snapshot per occupied seat, once.
     *
     * <p>Called at the top of a control step, before anything is decided. Both consumers — the steering
     * through {@link #driverInput()} and the per-seat listeners through {@link #forEachOccupied} — then
     * read the stored values, so one step is one read per rider. Owning thread only.
     *
     * @param inputs where a rider's input comes from
     */
    public void readInputs(@NotNull RiderInputSource inputs) {
        Objects.requireNonNull(inputs, "inputs");
        for (final Slot slot : this.slots) {
            final Player rider = slot.rider;
            if (rider == null) {
                slot.input = null;
                slot.previousState = InputState.NONE;
                continue;
            }
            final InputSnapshot previous = slot.input;
            slot.previousState = previous == null ? InputState.NONE : previous.state();
            slot.input = inputs.snapshot(rider);
        }
    }

    /**
     * What the driver is asking for, as this step read it.
     *
     * <p>{@link InputSnapshot#IDLE} when the driver's seat is empty, when the rig declares none, and on
     * the step a driver sat down — their first snapshot is read by the next {@link #readInputs}, which
     * costs one step of latency and avoids a read from the middle of a mount.
     */
    public @NotNull InputSnapshot driverInput() {
        for (final Slot slot : this.slots) {
            if (slot.seat.isDriver() && slot.rider != null) {
                final InputSnapshot input = slot.input;
                return input == null ? InputSnapshot.IDLE : input;
            }
        }
        return InputSnapshot.IDLE;
    }

    /**
     * Visits every occupied seat whose input has been read this step.
     *
     * <p>In index order, so the driver first. Owning thread only, and the visitor is the consumer's
     * {@code SeatInputListener} one layer up — see {@link VehicleImpl}, which catches anything it throws.
     *
     * @param visitor what to do with each seat's input
     */
    public void forEachOccupied(@NotNull SeatVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        for (final Slot slot : this.slots) {
            final Player rider = slot.rider;
            final InputSnapshot input = slot.input;
            if (rider == null || input == null) {
                continue;
            }
            visitor.visit(slot.seat, rider, input, slot.previousState);
        }
    }

    /**
     * Keeps every occupied seat on its locator, and notices every seat that has emptied.
     *
     * <p>Called once per control step, <strong>whether or not the vehicle moved</strong>: a parked vehicle
     * still animates, and an animated locator moves its seat. Owning thread only.
     *
     * <p>Five things can have happened to a seat since the last step, and each is handled rather than
     * assumed away: the rider got up, the carrier was removed, the model was released, the carrier ended
     * up in another region, or the platform put the rider down while relocating the carrier. The last one
     * is the only one a rider is given back their seat for — see {@link Slot#recoverRider} — because it is
     * the only one this layer caused.
     *
     * @param root     the root entity's position and heading, as this step read them
     * @param settings the thresholds this step is decided against
     */
    public void follow(@NotNull VehicleSample root, @NotNull SeatSettings settings) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(settings, "settings");
        boolean changed = false;
        for (final Slot slot : this.slots) {
            changed |= followOne(slot, root, settings);
        }
        if (changed) {
            publish();
        }
    }

    /**
     * @return whether this seat's occupancy changed, i.e. whether the published values need rebuilding
     */
    private boolean followOne(
            @NotNull Slot slot,
            @NotNull VehicleSample root,
            @NotNull SeatSettings settings
    ) {
        final SeatCarrier carrier = slot.carrier;
        if (carrier == null) {
            return false;
        }
        if (slot.relocating) {
            // A relocation is a transaction: the carrier is transiently out of the world, so there is
            // nothing to read and nothing to write until the platform reports back.
            return false;
        }
        if (!carrier.isAlive()) {
            vacate(slot, true);
            return true;
        }
        if (!carrier.isOwnedByCurrentThread()) {
            // Not an error and not a leak: the carrier is briefly in another region's hands, so this step
            // declines to touch it and the next one will. Writing anyway is the silent-corruption case
            // this whole layer is arranged to avoid.
            return false;
        }

        final Player expected = slot.rider;
        final Player aboard = carrier.rider();
        if (aboard == null) {
            if (slot.recoverRider && expected != null && carrier.mount(expected)) {
                // The platform dropped the rider during a relocation we asked for, and putting them back
                // is restoring the state rather than forcing one.
                slot.recoverRider = false;
            } else {
                vacate(slot, true);
                return true;
            }
        } else if (!aboard.equals(expected)) {
            // Somebody who never went through mount() is in the seat, so nothing has checked whether
            // their client would steer the carrier. The seat is given up rather than driven for them.
            vacate(slot, true);
            return true;
        } else {
            slot.recoverRider = false;
        }

        if (!slot.resolution.requireAnchor().readInto(this.locatorSample)) {
            // The model is gone. A seat on a model that is not being rendered is a position nobody can
            // compute, so the rider goes back on the ground rather than to wherever the carrier last was.
            vacate(slot, true);
            return true;
        }
        if (!carrier.readInto(this.carrierSample)) {
            vacate(slot, true);
            return true;
        }

        final float yaw = root.yawDegrees();
        final double targetX = root.x() + this.locatorSample.x()
                + offsetWorldX(yaw, slot.offsetX(), slot.offsetZ());
        final double targetY = root.y() + this.locatorSample.y()
                + slot.offsetY() + settings.carrierOffsetYBlocks();
        final double targetZ = root.z() + this.locatorSample.z()
                + offsetWorldZ(yaw, slot.offsetX(), slot.offsetZ());

        final double deltaX = targetX - this.carrierSample.x();
        final double deltaY = targetY - this.carrierSample.y();
        final double deltaZ = targetZ - this.carrierSample.z();
        final double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;

        final double deadband = settings.followDeadbandBlocks();
        if (distanceSquared <= deadband * deadband && yaw == slot.yawWritten) {
            return false;
        }

        final double ceiling = settings.maxCarrierStepBlocks();
        if (distanceSquared > ceiling * ceiling || !carrier.supportsLocalMove()) {
            relocate(slot, carrier, targetX, targetY, targetZ, yaw);
            return false;
        }
        carrier.moveLocal(deltaX, deltaY, deltaZ, yaw);
        slot.yawWritten = yaw;
        return false;
    }

    /**
     * Sends one carrier to an absolute destination, and arms the rider recovery.
     *
     * <p>Marks the seat as relocating first, so the loop stops writing to a carrier that is transiently
     * out of the world. The completion callback does nothing but publish two flags, because it runs on
     * whichever thread finished the relocation — the destination region's, not ours.
     */
    private void relocate(
            @NotNull Slot slot,
            @NotNull SeatCarrier carrier,
            double x,
            double y,
            double z,
            float yaw
    ) {
        slot.relocating = true;
        slot.yawWritten = yaw;
        carrier.teleport(x, y, z, yaw, succeeded -> {
            slot.relocating = false;
            // Armed whether or not the relocation succeeded: a failed one can still have dismounted the
            // rider, and the next step checks the seat before it acts on this.
            slot.recoverRider = true;
        });
    }

    /**
     * The seat {@code seatIndex} addresses, or the lowest free usable one; {@code null} if there is none.
     *
     * <p>{@link #ANY_SEAT} means the lowest free index and nothing else — not "the first that works". A
     * request for a particular index that is taken or unusable answers {@code null}, and
     * {@link #refusal(int)} turns that into the right message rather than this method quietly moving the
     * rider somewhere they did not ask for.
     */
    private @Nullable Slot choose(int seatIndex) {
        if (seatIndex == ANY_SEAT) {
            for (final Slot slot : this.slots) {
                if (slot.isFree() && slot.isUsable()) {
                    return slot;
                }
            }
            return null;
        }
        final Slot addressed = slotAt(seatIndex);
        if (addressed == null || !addressed.isFree() || !addressed.isUsable()) {
            return null;
        }
        return addressed;
    }

    /**
     * The slot at {@code seatIndex}.
     *
     * <p>Indexed rather than scanned, and the two are the same thing by construction: {@link #resolve}
     * adds one slot per planned seat in index order, so position and index agree. Bounds-checked anyway,
     * because the list is empty until resolve has run.
     */
    private @Nullable Slot slotAt(int seatIndex) {
        if (seatIndex < 0 || seatIndex >= this.slots.size()) {
            return null;
        }
        return this.slots.get(seatIndex);
    }

    /**
     * Empties one seat and removes its carrier.
     *
     * @param report whether to tell {@link SeatEvents}. False on the shutdown path only; see
     *               {@link #release()}
     */
    private void vacate(@NotNull Slot slot, boolean report) {
        final SeatCarrier carrier = slot.carrier;
        final Player leaving = slot.rider;
        slot.carrier = null;
        slot.rider = null;
        slot.relocating = false;
        slot.recoverRider = false;
        slot.input = null;
        slot.previousState = InputState.NONE;
        if (carrier != null) {
            carrier.release();
        }
        if (report && leaving != null) {
            this.events.onVacated(slot.seat, leaving);
        }
    }

    /**
     * Rebuilds the two values other threads read.
     *
     * <p>One store of a complete value rather than a mutable collection, for the reason
     * {@link VehicleImpl} publishes its state that way: a reader that sees the new occupancy sees every
     * seat in it, from one moment.
     */
    private void publish() {
        final List<SeatOccupant> occupants = new ArrayList<>(this.slots.size());
        Player driving = null;
        for (final Slot slot : this.slots) {
            occupants.add(new SeatOccupant(slot.seat, slot.rider, slot.isUsable()));
            if (slot.seat.isDriver() && slot.rider != null) {
                driving = slot.rider;
            }
        }
        this.occupancy = new SeatOccupancy(occupants);
        this.driver = driving;
    }

    /** The occupancy of these seats with nobody in them, keeping each seat's resolved usability. */
    private @NotNull SeatOccupancy vacantOccupancy() {
        final List<SeatOccupant> occupants = new ArrayList<>(this.slots.size());
        for (final Slot slot : this.slots) {
            occupants.add(new SeatOccupant(slot.seat, null, slot.isUsable()));
        }
        return new SeatOccupancy(occupants);
    }

    /**
     * The world x component of a seat offset authored in the model's own frame.
     *
     * <p>Pure, and public because it is the one piece of arithmetic in this class a test can pin down
     * exactly. The convention is the model's: at a heading of zero the model's x axis runs along the
     * world's x axis, which is what makes a rig's offsets mean the same thing in Blockbench and in the
     * world.
     *
     * @param yawDegrees the vehicle's heading
     * @param offsetX    the seat's x offset, in blocks
     * @param offsetZ    the seat's z offset, in blocks
     */
    @Contract(pure = true)
    public static double offsetWorldX(float yawDegrees, float offsetX, float offsetZ) {
        final double radians = yawDegrees * DEGREES_TO_RADIANS;
        return offsetX * Math.cos(radians) - offsetZ * Math.sin(radians);
    }

    /** The world z component of a seat offset; see {@link #offsetWorldX}. */
    @Contract(pure = true)
    public static double offsetWorldZ(float yawDegrees, float offsetX, float offsetZ) {
        final double radians = yawDegrees * DEGREES_TO_RADIANS;
        return offsetX * Math.sin(radians) + offsetZ * Math.cos(radians);
    }

    @Override
    public @NotNull String toString() {
        return "VehicleSeats[" + this.rig.getName() + ", " + occupied() + "/" + usableSeats()
                + " occupied, " + this.chart.declared() + " declared]";
    }

    /**
     * Where {@link #forEachOccupied} hands each occupied seat over.
     *
     * <p>An interface rather than a {@code BiConsumer} because it carries four values, and because a
     * caller can implement it on a long-lived object: a control step must allocate nothing, and a lambda
     * built per step would.
     */
    @FunctionalInterface
    public interface SeatVisitor {

        /**
         * @param seat     the seat, with its index
         * @param rider    who is in it
         * @param input    what they are asking for, read once at the top of this step
         * @param previous what they were asking for as of the previous step, or
         *                 {@link InputState#NONE} on the first step after they sat down
         */
        void visit(
                @NotNull VehicleSeat seat,
                @NotNull Player rider,
                @NotNull InputSnapshot input,
                @NotNull InputState previous
        );
    }

    /**
     * One declared seat's live state.
     *
     * <p>A mutable class rather than a record because most of its fields change over a seat's life and
     * all of them are confined to one thread. The two that are not — the rider and the carrier — are read
     * only by that same thread; what leaves this object for other threads is
     * {@link VehicleSeats#occupancy()} and {@link VehicleSeats#driver()}, both published whole.
     */
    private static final class Slot {

        private final @NotNull VehicleSeat seat;
        private final @NotNull SeatLocators.Resolution resolution;

        private @Nullable Player rider;
        private @Nullable SeatCarrier carrier;

        /** What the rider in this seat asked for this step, or {@code null} when it has not been read. */
        private @Nullable InputSnapshot input;

        /**
         * What they asked for on the previous step.
         *
         * <p>Kept per seat rather than taken from {@link InputSnapshot#previous()}, which is the previous
         * <em>publication</em> and on a source with no history is the same state. A seat's own previous
         * step is what makes an edge answerable whatever the input source can do; it costs one step of
         * resolution, which is the resolution the vehicle moves at anyway.
         */
        private @NotNull InputState previousState = InputState.NONE;

        /** The heading last written to the carrier, so an unchanged one is not written again. */
        private float yawWritten;

        /** Whether a relocation of this carrier is in flight, during which it is not touched. */
        private volatile boolean relocating;

        /**
         * Whether the rider may have been put down by a relocation we asked for.
         *
         * <p>The one case a rider is put back in their seat. A rider who got up by holding sneak, logged
         * out or died must <em>not</em> be remounted — that would trap a player in a vehicle — so a seat
         * that finds itself empty without this flag set is simply vacated.
         *
         * <p>Volatile because it is set by the platform's completion callback, which runs on the thread
         * that finished the relocation rather than on ours.
         */
        private volatile boolean recoverRider;

        private Slot(@NotNull VehicleSeat seat, @NotNull SeatLocators.Resolution resolution) {
            this.seat = seat;
            this.resolution = resolution;
        }

        private boolean isFree() {
            return this.rider == null && this.carrier == null;
        }

        private boolean isUsable() {
            return this.resolution.kind().isUsable();
        }

        private float offsetX() {
            return this.seat.definition().offsetX();
        }

        private float offsetY() {
            return this.seat.definition().offsetY();
        }

        private float offsetZ() {
            return this.seat.definition().offsetZ();
        }
    }
}
