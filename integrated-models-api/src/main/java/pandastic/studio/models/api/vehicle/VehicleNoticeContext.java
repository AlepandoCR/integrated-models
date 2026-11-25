package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The values a vehicle's messages substitute into their placeholders.
 *
 * <h2>Strings and numbers, never live objects</h2>
 * Every field is already resolved to text or to an {@code int}, and that is the point. A context
 * carrying a {@link Player} would be a message template that could read a player's position, health or
 * world — from whichever thread happens to be rendering the message, which on a regionised server is
 * undefined. So the names are taken once, on the thread that already owns both parties, and what
 * crosses into the renderer is a value.
 *
 * <h2>Absent is a dash, not an empty string and not a left-over tag</h2>
 * A notice about the whole vehicle has no seat; one sent before anybody is driving has no driver. Those
 * resolve to {@link #ABSENT} rather than to nothing, because a message reading "seat  is taken" looks
 * like a bug and a message reading "seat &lt;seat&gt; is taken" looks like a worse one.
 *
 * @param player     the player the message is going to
 * @param vehicle    what to call the vehicle. The rig's name by default, which is the only name this
 *                   layer has — a consumer wanting "the royal carriage" supplies it
 * @param seat       the seat's own id, or {@link #ABSENT}
 * @param seatIndex  the seat's zero-based address, or {@link VehicleSeat#NO_SEAT}
 * @param seats      how many seats resolved to a locator, i.e. how many anybody can sit on
 * @param occupied   how many of those are taken
 * @param driver     who is driving, or {@link #ABSENT}
 */
public record VehicleNoticeContext(
        @NotNull String player,
        @NotNull String vehicle,
        @NotNull String seat,
        int seatIndex,
        int seats,
        int occupied,
        @NotNull String driver
) {

    /**
     * What a placeholder with no value resolves to.
     *
     * <p>Structure, in the sense a sentinel is: it is the one answer that cannot be mistaken for a
     * name, and it is the same dash the seat chart uses for a cell it has nothing to put in.
     */
    public static final @NotNull String ABSENT = "-";

    public VehicleNoticeContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(seat, "seat");
        Objects.requireNonNull(driver, "driver");
        if (seats < 0 || occupied < 0) {
            throw new IllegalArgumentException(
                    "seats and occupied are counts and cannot be negative, but were "
                            + seats + " and " + occupied);
        }
        if (seatIndex < VehicleSeat.NO_SEAT) {
            throw new IllegalArgumentException("seatIndex must be a seat address or "
                    + VehicleSeat.NO_SEAT + " for none, but was " + seatIndex);
        }
    }

    /**
     * The context for a notice about the vehicle as a whole.
     *
     * @param player    who the message is for
     * @param vehicle   what to call the vehicle
     * @param occupancy the seats as they stand
     * @return the context, with no seat and the current driver's name if there is one
     */
    @Contract(pure = true)
    public static @NotNull VehicleNoticeContext of(
            @NotNull Player player,
            @NotNull String vehicle,
            @NotNull SeatOccupancy occupancy
    ) {
        return of(player, vehicle, occupancy, null);
    }

    /**
     * The context for a notice about one seat.
     *
     * @param player    who the message is for
     * @param vehicle   what to call the vehicle
     * @param occupancy the seats as they stand
     * @param seat      the seat the message is about, or {@code null}
     * @return the context
     */
    @Contract(pure = true)
    public static @NotNull VehicleNoticeContext of(
            @NotNull Player player,
            @NotNull String vehicle,
            @NotNull SeatOccupancy occupancy,
            @Nullable VehicleSeat seat
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(occupancy, "occupancy");
        return new VehicleNoticeContext(
                player.getName(),
                vehicle,
                seat == null ? ABSENT : seat.name(),
                seat == null ? VehicleSeat.NO_SEAT : seat.index(),
                occupancy.usableCount(),
                occupancy.occupied(),
                occupancy.driver().map(Player::getName).orElse(ABSENT));
    }

    /** The seat counted from one, as a player should read it, or {@link #ABSENT}. */
    @Contract(pure = true)
    public @NotNull String seatNumber() {
        return this.seatIndex == VehicleSeat.NO_SEAT ? ABSENT : Integer.toString(this.seatIndex + 1);
    }

    /** The seat's address as text, or {@link #ABSENT}. */
    @Contract(pure = true)
    public @NotNull String seatIndexText() {
        return this.seatIndex == VehicleSeat.NO_SEAT ? ABSENT : Integer.toString(this.seatIndex);
    }

    /** How many usable seats are empty. */
    @Contract(pure = true)
    public int free() {
        return Math.max(0, this.seats - this.occupied);
    }
}
