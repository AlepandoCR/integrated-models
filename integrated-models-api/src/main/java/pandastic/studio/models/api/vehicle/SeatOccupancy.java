package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every seat of one vehicle and who is in it, as one value read from one moment.
 *
 * <h2>Why a snapshot and not a live view</h2>
 * Because the thing it describes is mutated by the vehicle's own control step, on the thread that owns
 * the root entity, and a consumer reading it is usually somewhere else. A live view would let a caller
 * ask "is it full" and "where is the free seat" and get answers from two different steps — which is the
 * same straddling hazard the configuration snapshot and the driver sample exist to prevent, in a place
 * where it reads as a boarding bug rather than as a race.
 *
 * <p>So the vehicle publishes one of these whole, and every question below is answered from the same
 * instant. Hold it, ask it everything, drop it.
 *
 * <h2>The boarding rule, stated once</h2>
 * {@link #lowestFreeIndex()} is the rule: the lowest index that is usable and empty. Since the driver's
 * seat is {@link SeatPlan#DRIVER_INDEX}, the first player to board drives, the second takes the next
 * index, and so on until {@link #isFull()}. Nothing about that order depends on who boarded first or in
 * what order riders left — an index that frees up is filled before any higher one, so a vehicle whose
 * driver got off and whose passengers stayed hands the wheel to the <em>next person who boards</em>
 * rather than to somebody already aboard. See {@link Vehicle#board(Player)} for why.
 *
 * @param seats one per declared seat, in index order
 */
public record SeatOccupancy(@NotNull List<SeatOccupant> seats) {

    /** The occupancy of a vehicle whose rig declares no seats. */
    public static final @NotNull SeatOccupancy EMPTY = new SeatOccupancy(List.of());

    public SeatOccupancy {
        seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
        for (int index = 0; index < seats.size(); index++) {
            final SeatOccupant occupant = seats.get(index);
            if (occupant.index() != index) {
                // Stated as an invariant rather than trusted, because every accessor below indexes this
                // list by a seat index. A list whose positions and indices disagree would answer every
                // question about the wrong seat, silently.
                throw new IllegalArgumentException("Seat at position " + index + " carries index "
                        + occupant.index() + "; an occupancy is in index order and has no gaps");
            }
        }
    }

    /** How many seats the rig declared, usable or not. */
    @Contract(pure = true)
    public int size() {
        return this.seats.size();
    }

    /** How many riders are aboard. */
    @Contract(pure = true)
    public int occupied() {
        int count = 0;
        for (final SeatOccupant occupant : this.seats) {
            if (occupant.isOccupied()) {
                count++;
            }
        }
        return count;
    }

    /** How many seats resolved to a locator and could therefore be sat on. */
    @Contract(pure = true)
    public int usableCount() {
        int count = 0;
        for (final SeatOccupant occupant : this.seats) {
            if (occupant.usable()) {
                count++;
            }
        }
        return count;
    }

    /** How many seats are usable and empty. */
    @Contract(pure = true)
    public int availableCount() {
        int count = 0;
        for (final SeatOccupant occupant : this.seats) {
            if (occupant.isAvailable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether this vehicle has usable seats and every one of them is taken.
     *
     * <p>Deliberately {@code false} for a vehicle with <em>no</em> usable seat: that is not a full
     * vehicle, it is a vehicle nobody can board, and the two get different messages. Ask
     * {@link #hasUsableSeat()} first, which is what the boarding path does.
     */
    @Contract(pure = true)
    public boolean isFull() {
        return hasUsableSeat() && availableCount() == 0;
    }

    /** Whether any seat resolved to a locator. {@code false} is the expected state of a model with none. */
    @Contract(pure = true)
    public boolean hasUsableSeat() {
        return usableCount() > 0;
    }

    /**
     * The index the next rider to board will take.
     *
     * @return the lowest usable, empty index, or {@link VehicleSeat#NO_SEAT} when there is none —
     *         whether because the vehicle is full or because no seat resolved
     */
    @Contract(pure = true)
    public int lowestFreeIndex() {
        for (final SeatOccupant occupant : this.seats) {
            if (occupant.isAvailable()) {
                return occupant.index();
            }
        }
        return VehicleSeat.NO_SEAT;
    }

    /**
     * The seat at {@code index}.
     *
     * @param index the seat's address
     * @return the seat, or empty when nothing has that index
     */
    @Contract(pure = true)
    public @NotNull Optional<SeatOccupant> at(int index) {
        return index >= 0 && index < this.seats.size()
                ? Optional.of(this.seats.get(index))
                : Optional.empty();
    }

    /** Who is in the seat at {@code index}, or empty. */
    @Contract(pure = true)
    public @NotNull Optional<Player> riderAt(int index) {
        return at(index).flatMap(SeatOccupant::occupant);
    }

    /**
     * Who is driving.
     *
     * <p>Empty when the driver's seat is free, when the rig declares none, and when it did not resolve.
     * A vehicle can be full of passengers and have no driver, which is the right answer for a question
     * whose subject is who is steering.
     */
    @Contract(pure = true)
    public @NotNull Optional<Player> driver() {
        for (final SeatOccupant occupant : this.seats) {
            if (occupant.seat().isDriver()) {
                return occupant.occupant();
            }
        }
        return Optional.empty();
    }

    /**
     * The seat {@code rider} is in.
     *
     * @param rider whose seat to find
     * @return their seat, or empty when they are not aboard
     */
    @Contract(pure = true)
    public @NotNull Optional<SeatOccupant> seatOf(@NotNull Player rider) {
        Objects.requireNonNull(rider, "rider");
        for (final SeatOccupant occupant : this.seats) {
            if (rider.equals(occupant.rider())) {
                return Optional.of(occupant);
            }
        }
        return Optional.empty();
    }

    /** Everybody aboard, in index order — so the driver first, when there is one. */
    @Contract(pure = true)
    public @NotNull List<Player> riders() {
        final List<Player> aboard = new ArrayList<>(this.seats.size());
        for (final SeatOccupant occupant : this.seats) {
            final Player rider = occupant.rider();
            if (rider != null) {
                aboard.add(rider);
            }
        }
        return List.copyOf(aboard);
    }

    /**
     * The occupancy of a plan nobody is sitting in and whose seats all resolved.
     *
     * <p>For a consumer reasoning about a plan before a vehicle exists, and for tests. Production
     * occupancy comes from a live vehicle, where {@code usable} is whatever the model said.
     *
     * @param plan the seats
     * @return the occupancy
     */
    @Contract(pure = true)
    public static @NotNull SeatOccupancy vacant(@NotNull SeatPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final List<SeatOccupant> occupants = new ArrayList<>(plan.size());
        for (final VehicleSeat seat : plan.seats()) {
            occupants.add(new SeatOccupant(seat, null, true));
        }
        return new SeatOccupancy(occupants);
    }

    @Override
    public @NotNull String toString() {
        return "SeatOccupancy[" + occupied() + "/" + usableCount() + " of " + size() + " declared]";
    }

    /** The rider in the seat at {@code index}, or {@code null}. The allocation-free form. */
    @Contract(pure = true)
    public @Nullable Player occupantAt(int index) {
        return index >= 0 && index < this.seats.size() ? this.seats.get(index).rider() : null;
    }
}
