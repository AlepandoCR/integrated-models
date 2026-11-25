package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * What happened when somebody tried to board, and which seat it happened to.
 *
 * <p>The seat is present whenever the outcome is about a particular one — which includes every failure
 * that named a seat, because "seat 2 is taken" is a more useful thing to be told than "that did not
 * work". It is absent for the outcomes that are about the vehicle as a whole: full, no seat resolved,
 * not alive.
 *
 * @param outcome what happened
 * @param seat    the seat it happened to, or {@code null}
 */
public record BoardingResult(@NotNull BoardingOutcome outcome, @Nullable VehicleSeat seat) {

    /** Every usable seat is taken. */
    public static final @NotNull BoardingResult VEHICLE_FULL =
            new BoardingResult(BoardingOutcome.VEHICLE_FULL, null);

    /** The rig declares seats and none of them resolved to a locator on the live model. */
    public static final @NotNull BoardingResult NO_SEAT_RESOLVED =
            new BoardingResult(BoardingOutcome.NO_SEAT_RESOLVED, null);

    /** A seat was asked for by index or by name and this rig has no such seat. */
    public static final @NotNull BoardingResult NO_SUCH_SEAT =
            new BoardingResult(BoardingOutcome.NO_SUCH_SEAT, null);

    /** The vehicle was never started, or has already been despawned. */
    public static final @NotNull BoardingResult NOT_ALIVE =
            new BoardingResult(BoardingOutcome.NOT_ALIVE, null);

    public BoardingResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * A result about one seat.
     *
     * @param outcome what happened
     * @param seat    the seat it happened to
     * @return the result
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull BoardingResult of(
            @NotNull BoardingOutcome outcome,
            @NotNull VehicleSeat seat
    ) {
        return new BoardingResult(outcome, Objects.requireNonNull(seat, "seat"));
    }

    /** Whether the rider ended up aboard, which includes having already been there. */
    @Contract(pure = true)
    public boolean isAboard() {
        return this.outcome.isAboard();
    }

    /** Whether the rider is in the driver's seat as a result of this. */
    @Contract(pure = true)
    public boolean isDriving() {
        final VehicleSeat seated = this.seat;
        return isAboard() && seated != null && seated.isDriver();
    }

    /** The seat, when the outcome is about one. */
    @Contract(pure = true)
    public @NotNull Optional<VehicleSeat> seatTaken() {
        return Optional.ofNullable(this.seat);
    }

    /** The seat's index, or {@link VehicleSeat#NO_SEAT}. */
    @Contract(pure = true)
    public int seatIndex() {
        final VehicleSeat seated = this.seat;
        return seated == null ? VehicleSeat.NO_SEAT : seated.index();
    }

    @Override
    public @NotNull String toString() {
        final VehicleSeat seated = this.seat;
        return "BoardingResult[" + this.outcome
                + (seated == null ? "" : ", seat #" + seated.index() + " " + seated.name()) + "]";
    }
}
