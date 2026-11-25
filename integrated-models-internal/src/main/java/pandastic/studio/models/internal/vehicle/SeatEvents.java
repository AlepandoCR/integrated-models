package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.VehicleNotice;
import pandastic.studio.models.api.vehicle.VehicleSeat;

/**
 * Told when a seat fills or empties.
 *
 * <h2>Why the seating layer does not send the messages itself</h2>
 * Because a seat empties for five different reasons and only one of them is somebody asking. A rider
 * holds sneak and the server puts them down; their carrier is removed; the model stops being rendered; a
 * relocation drops them; the plugin is shutting down. {@link VehicleSeats} is the only thing that
 * notices all five, and {@link VehicleImpl} is the only thing that knows what this vehicle says and who
 * it says it to. One seam, so the first does not grow a {@code VehicleMessages}-shaped dependency and
 * the second does not have to re-derive occupancy changes by diffing snapshots.
 *
 * <p>It is also what makes the departure of a <em>driver</em> a single event rather than a condition
 * somebody has to poll for, which is what the "nobody is promoted" rule needs in order to be stated in
 * one place.
 *
 * <h2>Called on the thread that owns the root entity, inside the operation that caused it</h2>
 * So an implementation may touch the vehicle and the rider, and must not block. Shutdown is the one
 * exception and it is handled by not calling this at all: {@link VehicleSeats#release()} runs from
 * wherever a teardown was triggered — including a retired callback for an entity that no longer exists —
 * and deliberately reports nothing. A player being told "you are no longer riding" while the server is
 * stopping is noise at best, and at worst it is a message sent to somebody whose connection is already
 * going away.
 */
public interface SeatEvents {

    /** Reports nothing. For a vehicle with no messages and for tests about seating alone. */
    @NotNull SeatEvents NONE = new SeatEvents() {

        @Override
        public void onSeated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        }

        @Override
        public void onVacated(@NotNull VehicleSeat seat, @NotNull Player rider) {
        }
    };

    /**
     * {@code rider} has just been seated in {@code seat}.
     *
     * <p>Only ever after the mount succeeded, so the carrier exists and the rider is on it. Note that
     * the authority refusal is asked <em>after</em> this, by {@link VehicleImpl}, because the server's
     * own answer depends on who is aboard — so a seated event can be followed by the same rider being
     * put straight back down.
     *
     * @param seat  the seat they are in, with its index
     * @param rider who sat down
     */
    void onSeated(@NotNull VehicleSeat seat, @NotNull Player rider);

    /**
     * {@code rider} is no longer in {@code seat}.
     *
     * <p>Whatever the reason, and the reason is deliberately not passed: every one of them is the same
     * fact from the player's point of view, which is {@link VehicleNotice#DISMOUNTED}. What differs is
     * whether the vehicle keeps a driver, and that is answered by {@link VehicleSeat#isDriver()} on the
     * seat handed over here.
     *
     * @param seat  the seat they left
     * @param rider who got up, or was put down
     */
    void onVacated(@NotNull VehicleSeat seat, @NotNull Player rider);
}
