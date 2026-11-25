package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What happened when somebody tried to sit down.
 *
 * <h2>Why not a boolean</h2>
 * Because nine different things stop a rider being seated and they call for three different answers.
 * "Every seat is taken" is a normal outcome and the player should be told. "This rig declares no usable
 * seat" is a rigging problem the operator has to see, and is the <em>expected</em> state today — no
 * model in this repository carries a locator element yet. "The platform refused the mount" is a race
 * nobody could have avoided. A boolean collapses all of them into one silence, which is why
 * {@link Vehicle#mount(Player)} keeps answering one and {@link Vehicle#board(Player)} answers this.
 *
 * <p>Each outcome maps to at most one {@link VehicleNotice}, and the mapping is
 * {@link #notice()} — in one place, so a consumer that wants to say something else about a particular
 * outcome overrides the text rather than reimplementing the decision.
 */
public enum BoardingOutcome {

    /** Seated. Their carrier is following the locator from the next control step. */
    SEATED(null),

    /** They were already in a seat of this vehicle and were not moved. */
    ALREADY_ABOARD(null),

    /**
     * Every usable seat is taken.
     *
     * <p>Full means full: the rider gets {@link VehicleNotice#VEHICLE_FULL} and nothing is thrown. A
     * vehicle that threw here would make an ordinary moment — a third player walking up to a two-seater
     * — into a stack trace in the console of whoever wired the interaction.
     */
    VEHICLE_FULL(VehicleNotice.VEHICLE_FULL),

    /**
     * A particular seat was asked for and somebody is in it.
     *
     * <p>Only ever the answer to an addressed request. An unaddressed one takes the lowest free index
     * and therefore never meets an occupied seat.
     */
    SEAT_TAKEN(VehicleNotice.SEAT_TAKEN),

    /** A seat was asked for by index or by name and this rig has no such seat. */
    NO_SUCH_SEAT(VehicleNotice.SEAT_TAKEN),

    /**
     * The rig declares seats and not one of them resolved to a locator on the live model.
     *
     * <p>The rigger pointed at bones, or at locators nobody has added yet. The expected answer today,
     * and the seat chart logged when the vehicle bound is where the reason is written down.
     */
    NO_SEAT_RESOLVED(VehicleNotice.NO_SEAT_RESOLVED),

    /** The seat a particular request named exists, and its locator did not resolve. */
    SEAT_UNUSABLE(VehicleNotice.NO_SEAT_RESOLVED),

    /**
     * Seating them in the driver's seat would hand the vehicle's movement to their client.
     *
     * <p>The authority refusal, and the load-bearing one: a driver whose client believes it is steering
     * turns every position the server writes into a correction the client has already drawn past, which
     * is permanent rubber-banding rather than an error. Applied to the driver's seat alone — a
     * passenger's client steering an invisible carrier steers nothing anybody can see.
     */
    CANNOT_BOARD(VehicleNotice.CANNOT_BOARD),

    /**
     * The seat is usable and no carrier could be spawned for it.
     *
     * <p>In practice an operator's configured carrier entity type the platform does not recognise, or a
     * cross-region mount the platform refused.
     */
    CARRIER_UNAVAILABLE(VehicleNotice.CANNOT_BOARD),

    /** The model the seat is on is no longer being rendered, so the seat has no position. */
    MODEL_GONE(VehicleNotice.CANNOT_BOARD),

    /** This vehicle is not running: it was never started, or it has already been despawned. */
    NOT_ALIVE(VehicleNotice.CANNOT_BOARD);

    private final @Nullable VehicleNotice notice;

    BoardingOutcome(@Nullable VehicleNotice notice) {
        this.notice = notice;
    }

    /** Whether the rider ended up aboard, which includes having already been there. */
    public boolean isAboard() {
        return this == SEATED || this == ALREADY_ABOARD;
    }

    /**
     * The message this outcome is worth telling the player, if any.
     *
     * <p>Empty for the two that succeeded, because what a seated rider is told is about the seat they
     * got — {@link VehicleNotice#NOW_DRIVER} or {@link VehicleNotice#NOW_PASSENGER} — and that is not a
     * function of the outcome alone.
     *
     * @return the notice, or {@code null}
     */
    public @Nullable VehicleNotice notice() {
        return this.notice;
    }

    /** This outcome in one word, for a log line. */
    public @NotNull String describe() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
