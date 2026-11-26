package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;

import java.util.Objects;

/**
 * One seat's rider and everything they are asking for, as of one control step.
 *
 * <h2>Read once, handed over once</h2>
 * The vehicle reads one {@link InputSnapshot} per occupied seat per step, before it decides anything,
 * and hands it to that seat's listener afterwards. A listener therefore sees exactly the input the
 * steering saw in the same step — the two cannot disagree about what the rider did, which is the whole
 * reason the read is not left to the listener.
 *
 * <p>It also means a listener must not go and ask the controller again. Two reads inside one step can
 * straddle a publication, which is the hazard {@code PlayerInputController}'s own notes open with.
 *
 * <p>One instance per occupied seat per step, and both of the consumer's mechanisms get <em>that</em>
 * instance: the seat's {@link SeatInputListener} first, then {@link VehicleController#onPlayerInput}.
 * So they cannot disagree about what the rider did either, and a consumer that supplies both pays one
 * allocation rather than two.
 *
 * <h2>Which thread this arrives on, and what that permits</h2>
 * The thread that owns the vehicle's root entity, inside the vehicle's own scheduled step. So a listener
 * may read and write the vehicle, the rider and anything else in that region — and must not block,
 * because every other vehicle in the region is waiting behind it. It must not touch an entity somewhere
 * else; the platform will not say so, it will simply be wrong.
 *
 * <h2>Edges work even where the capture layer has no history</h2>
 * {@link #previous()} is the state <em>this seat</em> saw on the previous step rather than the previous
 * publication, so {@link #justPressed(InputKey)} is answerable whatever the input source can do. It
 * costs one step of resolution: a key tapped and released between two steps is not seen at all, which is
 * the same resolution the steering has and therefore the same resolution the vehicle moves at.
 *
 * @param vehicle  the vehicle being ridden
 * @param seat     the seat this rider is in, with its index
 * @param rider    who is in it
 * @param input    what they are asking for, read once this step
 * @param previous what they were asking for as of the previous step, or {@link InputState#NONE} on the
 *                 first step after they sat down
 * @param steering whether this seat's input is also steering the vehicle, i.e. whether this is the
 *                 driver's seat. Published so that a listener shared between seats can tell
 */
public record SeatInput(
        @NotNull Vehicle vehicle,
        @NotNull VehicleSeat seat,
        @NotNull Player rider,
        @NotNull InputSnapshot input,
        @NotNull InputState previous,
        boolean steering
) {

    public SeatInput {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(seat, "seat");
        Objects.requireNonNull(rider, "rider");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(previous, "previous");
    }

    /** The flags alone. */
    @Contract(pure = true)
    public @NotNull InputState state() {
        return this.input.state();
    }

    /** Whether {@code key} is down as of this step. */
    @Contract(pure = true)
    public boolean isDown(@NotNull InputKey key) {
        return this.input.state().isDown(key);
    }

    /** Whether {@code key} went down between the previous step and this one. */
    @Contract(pure = true)
    public boolean justPressed(@NotNull InputKey key) {
        return this.input.state().isDown(key) && !this.previous.isDown(key);
    }

    /** Whether {@code key} came up between the previous step and this one. */
    @Contract(pure = true)
    public boolean justReleased(@NotNull InputKey key) {
        return !this.input.state().isDown(key) && this.previous.isDown(key);
    }

    /** Whether anything at all is held. Not the same question as whether the vehicle is at rest. */
    @Contract(pure = true)
    public boolean isIdle() {
        return this.input.state().isIdle();
    }

    /** This seat's index, which is what addresses it. */
    @Contract(pure = true)
    public int seatIndex() {
        return this.seat.index();
    }

    /**
     * What the steering made of these keys, as a movement intent.
     *
     * <p>The same seven held keys the driver's seat is steered by, for a listener that wants to react to
     * the vehicle's own notion of the input rather than to the raw bits. Identical to what the control
     * step used this step when {@link #steering()} is true.
     */
    @Contract(pure = true)
    public @NotNull RiderIntent intent() {
        return RiderIntent.ofBits(this.input.state().movementBits());
    }

    @Override
    public @NotNull String toString() {
        return "SeatInput[#" + this.seat.index() + " " + this.rider.getName() + " "
                + this.input.state().describe() + (this.steering ? ", steering" : "") + "]";
    }
}
