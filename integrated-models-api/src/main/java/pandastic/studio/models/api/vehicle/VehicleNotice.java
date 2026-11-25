package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Everything a vehicle ever says to a player.
 *
 * <h2>Seven, and why these seven</h2>
 * Each one is a moment a player is left guessing without it. Four of them are refusals — the vehicle is
 * full, that seat is taken, nobody can board this at all, your client would be the one steering — and a
 * refusal with no message is a vehicle that looks broken. The other three are confirmations of a state
 * change the player cannot otherwise see, because a seat is an invisible carrier on a locator: there is
 * no saddle to look at and nothing on screen that distinguishes driving from being carried.
 *
 * <p>Nothing here is a log line. A diagnostic about a seat that named a bone goes to the operator
 * through the seat chart; this enum is the player's half, and the two deliberately do not share a key.
 *
 * @see VehicleNotifications
 * @see VehicleMessages
 */
public enum VehicleNotice {

    /**
     * Every usable seat is taken.
     *
     * <p>Placeholders: {@code vehicle}, {@code player}, {@code seats}, {@code occupied}, {@code driver}.
     */
    VEHICLE_FULL,

    /**
     * The seat this player asked for is occupied, or does not exist.
     *
     * <p>Only ever reached by an addressed request — boarding without naming a seat takes the lowest
     * free index and so never meets an occupied one.
     *
     * <p>Placeholders: everything, including {@code seat}, {@code seat_index} and {@code seat_number}.
     */
    SEAT_TAKEN,

    /**
     * They are now driving.
     *
     * <p>Sent on being seated in the driver's seat, which is the seat the first player to board takes.
     *
     * <p>Placeholders: everything.
     */
    NOW_DRIVER,

    /**
     * They are now a passenger.
     *
     * <p>Worth distinguishing from {@link #NOW_DRIVER} because the vehicle will not respond to their
     * keys and a player who is not told that concludes the vehicle is broken.
     *
     * <p>Placeholders: everything.
     */
    NOW_PASSENGER,

    /**
     * They are no longer aboard.
     *
     * <p>Sent when a seat empties for any reason this layer observes: they got up, they were put down,
     * the carrier was removed, the model stopped being rendered. Deliberately <em>not</em> sent when the
     * whole vehicle is torn down at plugin shutdown — see {@link Vehicle#despawn()}.
     *
     * <p>Placeholders: everything, with {@code seat} naming the seat they left.
     */
    DISMOUNTED,

    /**
     * They cannot board, and not because it is full.
     *
     * <p>The authority refusal is the one that matters: the server would make this player's client the
     * authority over what they are riding, so the vehicle would rubber-band for everybody. Also covers
     * a carrier that could not be spawned and a vehicle that is no longer running.
     *
     * <p>Placeholders: everything.
     */
    CANNOT_BOARD,

    /**
     * This vehicle has no seat anybody can sit on.
     *
     * <p>Not the same as full, and the distinction is the whole reason both exist: a seat is a
     * <strong>locator</strong>, and a rig whose seats name bones or name nothing has no seats at all. It
     * is the expected answer for every model in this repository today, because none of them carries a
     * locator element yet. The player is told they cannot board; the operator is told why by the seat
     * chart.
     *
     * <p>Placeholders: {@code vehicle}, {@code player}, {@code seats}.
     */
    NO_SEAT_RESOLVED;

    /** This notice's own name, lower-cased with underscores, for a diagnostic. */
    public @NotNull String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
