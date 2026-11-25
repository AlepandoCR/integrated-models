package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.RigSeat;

import java.util.Objects;

/**
 * One seat of one vehicle, with the <strong>index</strong> that addresses it.
 *
 * <h2>Why the index is not in the rig file</h2>
 * Because an index is a statement about the <em>set</em> of seats and a rig file declares them one at a
 * time. A number written per seat would be a second way to say the same thing as the order they appear
 * in, and two ways to say one thing is two ways to disagree: a file with seats numbered 1, 2, 2 would
 * have to be refused or silently renumbered, and a file with 1, 3, 7 would have holes a boarding rule
 * cannot fill. So the file declares seats in an order and says which one drives, and the index is
 * derived from those two facts by {@link SeatPlan} — deterministically, with no gaps, every time the rig
 * is loaded.
 *
 * <h2>The driver's seat is index {@link SeatPlan#DRIVER_INDEX}</h2>
 * Zero, when the rig declares a driver's seat at all. That is what makes "the first player to board
 * drives" a consequence of the boarding rule rather than a special case in it: boarding takes the
 * lowest free index, and the lowest index is the driver's.
 *
 * <h2>Zero-based for addressing, one-based for reading</h2>
 * {@link #index()} is zero-based because it indexes {@link SeatPlan#seats()} and is what every api method
 * taking a seat takes. {@link #number()} is {@code index + 1} and exists for messages and for a chart
 * line, because "seat 0" reads as a bug to the player seeing it. The second rider to board therefore
 * takes {@code index() == 1}, which is {@code number() == 2} — the same seat under both spellings.
 *
 * <h2>It is still a locator, and still never a bone</h2>
 * Everything {@link RigSeat} says about that holds here unchanged; this type adds an address and nothing
 * else. A seat whose locator name turned out to name a bone keeps its index, is reported, and is never
 * offered to a rider — see {@link SeatOccupant#usable()}.
 *
 * @param index      this seat's address, {@code 0 .. }{@link SeatPlan#size()}{@code  - 1}. Stable for
 *                   the life of the loaded rig and identical on two servers reading the same file
 * @param definition the seat exactly as the rig declared it: the locator, the driver flag and the
 *                   offsets
 */
public record VehicleSeat(int index, @NotNull RigSeat definition) {

    /**
     * The index of no seat at all.
     *
     * <p>Structure: a {@code -1} sentinel, the same one {@code RigBone.NO_PARENT} and
     * {@code InputState.UNKNOWN_HOTBAR_SLOT} are. It is what {@link SeatOccupancy#lowestFreeIndex()}
     * answers for a vehicle with nowhere to sit, and what a boarding result carries when the outcome is
     * not about a particular seat.
     */
    public static final int NO_SEAT = -1;

    public VehicleSeat {
        Objects.requireNonNull(definition, "definition");
        if (index < 0) {
            throw new IllegalArgumentException("A seat index addresses a position in the seat plan and"
                    + " must not be negative, but was " + index + "; " + NO_SEAT + " is the sentinel for"
                    + " no seat and is not an address");
        }
    }

    /** This seat's own id, as the rig named it: lower-cased, and matched case-insensitively. */
    @Contract(pure = true)
    public @NotNull String name() {
        return this.definition.name();
    }

    /** The locator the rider sits on. Never a bone; see {@link RigSeat}. */
    @Contract(pure = true)
    public @NotNull String locator() {
        return this.definition.locator();
    }

    /**
     * Whether a rider in this seat steers the vehicle.
     *
     * <p>The rig's statement, not the consumer's: at most one seat of a rig may drive, and which one is
     * a property of the vehicle rather than of a session. A consumer decides what a rider's inputs
     * <em>also</em> do — see {@link VehicleControls} — and cannot move the steering to another seat.
     */
    @Contract(pure = true)
    public boolean isDriver() {
        return this.definition.driver();
    }

    /**
     * This seat counted from one, for a message or a chart line.
     *
     * <p>Never used to address anything. See the class note on the two spellings.
     */
    @Contract(pure = true)
    public int number() {
        return this.index + 1;
    }

    @Override
    public @NotNull String toString() {
        return "VehicleSeat[#" + this.index + " " + this.definition.name()
                + (isDriver() ? " (driver)" : "") + " on " + this.definition.locator() + "]";
    }
}
