package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigSeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Every seat of one vehicle rig, indexed, with the driver's first.
 *
 * <h2>What a rig says and what this derives</h2>
 * A rig file says two things about seating and this type turns them into one ordered list:
 * <ul>
 *   <li><strong>which locators are seats</strong> — every entry of the file's {@code seats:} section is
 *       a seat, and each names a locator. There is no second flag to switch one off and no other way a
 *       locator becomes mountable: a locator nobody listed is just a point on the model;</li>
 *   <li><strong>which single one drives</strong> — {@link RigSeat#driver()}, which {@code Rig.Builder}
 *       already refuses to accept twice.</li>
 * </ul>
 * From those: <strong>the driver's seat is index {@link #DRIVER_INDEX}</strong> and every other seat
 * follows in the order the file declared it. Nothing else is reordered, so a rigger reading their own
 * file can count the passenger seats off it.
 *
 * <h2>Why the driver is moved to the front rather than left where it was</h2>
 * Because the boarding rule is "the lowest free index", and the owner's rule is "the first player to
 * board drives". Those two are the same sentence only if the driver's seat is the lowest index. The
 * alternative — keep file order and special-case the driver in the boarding code — makes the rule
 * depend on where in the file the rigger happened to put the driver, which is exactly the kind of fact
 * that is invisible in the api and surprising in the world.
 *
 * <p>A rig that declares <em>no</em> driver's seat has no special index at all: {@link #driverIndex()}
 * answers {@link VehicleSeat#NO_SEAT}, index zero is the first passenger seat, and the vehicle can be
 * boarded but never steered. That is a legal rig — a float, a trailer, a platform — rather than a
 * broken one.
 *
 * <h2>Resolution is not this type's business</h2>
 * A seat here is a <em>declaration</em>, exactly as a {@code RigBone} is. Whether its locator exists on
 * the live model is answered when the vehicle binds, is reported in the seat chart, and shows up on
 * {@link SeatOccupant#usable()}. An unusable seat keeps its index: renumbering around it would move
 * every seat behind it whenever a rigger renamed a locator, which would make an index worthless as an
 * address.
 */
public final class SeatPlan {

    /**
     * The index the driver's seat takes, when the rig declares one.
     *
     * <p>Structure rather than a tunable: it is zero because boarding fills the lowest free index and
     * the first rider must be the driver. A configurable driver index would be a way to make the first
     * rider a passenger, which no file should be able to say.
     */
    public static final int DRIVER_INDEX = 0;

    /** The plan of a rig that declares no seats, which is every rig that is not a vehicle. */
    public static final @NotNull SeatPlan EMPTY = new SeatPlan(List.of());

    private final @NotNull List<VehicleSeat> seats;
    private final int driverIndex;

    private SeatPlan(@NotNull List<VehicleSeat> seats) {
        this.seats = List.copyOf(seats);
        int driver = VehicleSeat.NO_SEAT;
        for (final VehicleSeat seat : this.seats) {
            if (seat.isDriver()) {
                driver = seat.index();
                break;
            }
        }
        this.driverIndex = driver;
    }

    /**
     * The indexed seats of {@code rig}: the driver's first, then the rest in the file's own order.
     *
     * @param rig the rig whose {@code seats:} section to index
     * @return the plan, {@link #EMPTY} for a rig that declares no seats
     */
    @Contract(pure = true)
    public static @NotNull SeatPlan of(@NotNull Rig rig) {
        Objects.requireNonNull(rig, "rig");
        return of(rig.seats());
    }

    /**
     * The same thing from a bare list, for a caller that has seats but no rig.
     *
     * @param declared the seats, in the order they were declared
     * @return the plan
     */
    @Contract(pure = true)
    public static @NotNull SeatPlan of(@NotNull List<RigSeat> declared) {
        Objects.requireNonNull(declared, "declared");
        if (declared.isEmpty()) {
            return EMPTY;
        }
        final List<VehicleSeat> ordered = new ArrayList<>(declared.size());
        // Two passes rather than a sort: a comparator over a boolean is a stable sort in disguise, and
        // saying "the driver, then everybody else in order" in two loops is the thing the documentation
        // claims. Rig.Builder has already refused a second driver, so the first pass adds at most one.
        for (final RigSeat seat : declared) {
            if (Objects.requireNonNull(seat, "seat").driver()) {
                ordered.add(new VehicleSeat(ordered.size(), seat));
            }
        }
        for (final RigSeat seat : declared) {
            if (!seat.driver()) {
                ordered.add(new VehicleSeat(ordered.size(), seat));
            }
        }
        return new SeatPlan(ordered);
    }

    /** Every seat, in index order. Unmodifiable. */
    @Contract(pure = true)
    public @NotNull List<VehicleSeat> seats() {
        return this.seats;
    }

    /** How many seats the rig declared, usable or not. */
    @Contract(pure = true)
    public int size() {
        return this.seats.size();
    }

    /** Whether the rig declared no seats at all. */
    @Contract(pure = true)
    public boolean isEmpty() {
        return this.seats.isEmpty();
    }

    /**
     * The index the vehicle is steered from, or {@link VehicleSeat#NO_SEAT}.
     *
     * <p>Always {@link #DRIVER_INDEX} when it is not the sentinel, by construction. Worth asking anyway
     * rather than assuming, because the sentinel is the real answer for a rig with no driver's seat and
     * a caller that assumed zero would steer a passenger.
     */
    @Contract(pure = true)
    public int driverIndex() {
        return this.driverIndex;
    }

    /** Whether any seat of this rig steers. */
    @Contract(pure = true)
    public boolean hasDriverSeat() {
        return this.driverIndex != VehicleSeat.NO_SEAT;
    }

    /** The driver's seat, or empty when the rig declares none. */
    @Contract(pure = true)
    public @NotNull Optional<VehicleSeat> driverSeat() {
        return this.driverIndex == VehicleSeat.NO_SEAT
                ? Optional.empty()
                : Optional.of(this.seats.get(this.driverIndex));
    }

    /**
     * Whether {@code index} addresses a seat of this plan.
     *
     * @param index the index to test, which may be any integer — including
     *              {@link VehicleSeat#NO_SEAT}
     */
    @Contract(pure = true)
    public boolean hasIndex(int index) {
        return index >= 0 && index < this.seats.size();
    }

    /**
     * The seat at {@code index}.
     *
     * @param index the seat's address
     * @return the seat
     * @throws IndexOutOfBoundsException if no seat has that index. An exception rather than an
     *                                   {@link Optional} because this is the accessor a caller uses
     *                                   with an index it got from here; {@link #hasIndex(int)} is the
     *                                   question, and {@link #findSeat(int)} the lenient form
     */
    @Contract(pure = true)
    public @NotNull VehicleSeat seat(int index) {
        return this.seats.get(index);
    }

    /** The seat at {@code index}, or empty — for a caller holding a number somebody typed. */
    @Contract(pure = true)
    public @NotNull Optional<VehicleSeat> findSeat(int index) {
        return hasIndex(index) ? Optional.of(this.seats.get(index)) : Optional.empty();
    }

    /**
     * The seat called {@code seatName}, matched case-insensitively.
     *
     * <p>On the seat's own id, which is ours to interpret — unlike its locator's name, which is the
     * renderer's. A name is the stable way for a consumer to address a seat whose index could move if
     * the rigger reordered the file; an index is the stable way to address it within one load.
     *
     * @param seatName the seat's id
     * @return the seat, or empty
     */
    @Contract(pure = true)
    public @NotNull Optional<VehicleSeat> findSeat(@NotNull String seatName) {
        final String key = Objects.requireNonNull(seatName, "seatName").trim().toLowerCase(Locale.ROOT);
        for (final VehicleSeat seat : this.seats) {
            if (seat.name().equals(key)) {
                return Optional.of(seat);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SeatPlan plan && this.seats.equals(plan.seats);
    }

    @Override
    public int hashCode() {
        return this.seats.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return "SeatPlan" + this.seats;
    }
}
