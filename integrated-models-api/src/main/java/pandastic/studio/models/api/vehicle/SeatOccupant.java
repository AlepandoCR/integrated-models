package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * One seat as it stands right now: who is in it, and whether anybody could be.
 *
 * <h2>Three states, not two</h2>
 * A seat is occupied, free, or <strong>unusable</strong>, and collapsing the last two would lose the
 * answer an operator needs. A seat is unusable when the locator it names did not resolve on the live
 * model — the rigger pointed at a bone, or at a locator nobody has added yet — and today that is the
 * state of every seat of every model in this repository, because none of them contains a locator
 * element. "Free" and "nobody can ever sit here" are different facts and they get different messages:
 * {@link VehicleNotice#VEHICLE_FULL} against {@link VehicleNotice#NO_SEAT_RESOLVED}.
 *
 * @param seat   the seat and its index
 * @param rider  who is in it, or {@code null}
 * @param usable whether its locator resolved on the live model. A seat can be unusable and occupied for
 *               the length of one control step — the step that notices the model is gone is the step
 *               that puts its rider down
 */
public record SeatOccupant(@NotNull VehicleSeat seat, @Nullable Player rider, boolean usable) {

    public SeatOccupant {
        Objects.requireNonNull(seat, "seat");
    }

    /** Whether somebody is in this seat. */
    @Contract(pure = true)
    public boolean isOccupied() {
        return this.rider != null;
    }

    /**
     * Whether a rider could be seated here right now.
     *
     * <p>The boarding rule's own predicate: usable and empty. An unusable seat is never available,
     * however empty it is.
     */
    @Contract(pure = true)
    public boolean isAvailable() {
        return this.usable && this.rider == null;
    }

    /** Who is in this seat. */
    @Contract(pure = true)
    public @NotNull Optional<Player> occupant() {
        return Optional.ofNullable(this.rider);
    }

    /** This seat's index, which is what addresses it. */
    @Contract(pure = true)
    public int index() {
        return this.seat.index();
    }

    @Override
    public @NotNull String toString() {
        final Player seated = this.rider;
        return "SeatOccupant[#" + this.seat.index() + " " + this.seat.name() + ", "
                + (this.usable ? "usable" : "unusable") + ", "
                + (seated == null ? "empty" : seated.getName()) + "]";
    }
}
