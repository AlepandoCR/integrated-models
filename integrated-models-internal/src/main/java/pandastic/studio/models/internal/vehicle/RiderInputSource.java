package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.input.PlayerInputController;
import pandastic.studio.models.api.vehicle.RiderIntent;

import java.util.Objects;

/**
 * Where a vehicle reads a rider's input.
 *
 * <h2>Why the vehicle layer does not call the controller directly</h2>
 * Because there are two sources with the same shape and the choice between them is not the vehicle's.
 * {@link PlayerInputController} is the real one and gives all twelve mapped inputs with timing;
 * {@link #fromHost(VehicleHost)} is the fallback over the retained movement flags, which is what a
 * server with no input capture — or a vehicle created before the input layer is wired — has. Narrowing
 * the dependency to one method also keeps the control loop testable without a controller, which is the
 * same reason {@link VehicleHost} exists.
 *
 * <h2>Read once per occupied seat per step</h2>
 * The contract the controller states and this seam inherits: a step reads one snapshot per rider, at the
 * top, before it decides anything, and uses that value for the whole step — the steering and the
 * per-seat listeners alike. Two reads inside one step can straddle a publication, which would let the
 * gait of the decision and the listener that reacts to it disagree about what the rider did. It is the
 * same hand-over the pose pipeline's driver sample makes, for the same reason.
 *
 * <p>Implementations must be safe to call from the thread that owns the vehicle's root entity, which in
 * the ridden case is also the region that owns the rider — the platform force-resyncs a passenger that
 * drifts more than a chunk from its vehicle, so the two cannot be apart.
 *
 * <h2>The movement bits are the same seven bits</h2>
 * {@link InputKey#FORWARD} through {@link InputKey#SPRINT} occupy bits 0 to 6 in the client's own order,
 * which is bit-for-bit the encoding {@link RiderIntent#ofBits(int)} reads. So
 * {@code RiderIntent.ofBits(state.movementBits())} is a reinterpretation rather than a translation, and
 * {@link #intentOf(InputState)} is where that is said once instead of at every call site.
 */
@FunctionalInterface
public interface RiderInputSource {

    /** A source that sees nothing. A vehicle driven by it never moves, which is the honest answer. */
    @NotNull RiderInputSource NONE = rider -> InputSnapshot.IDLE;

    /**
     * What {@code rider} is asking for right now.
     *
     * @param rider whose input to read
     * @return the published snapshot, or {@link InputSnapshot#IDLE} when nothing is known. Never
     *         {@code null}: a player whose client disconnected mid-key never sends the release, and the
     *         controller's own answer past its staleness window is {@code IDLE} rather than a key held
     *         forever
     */
    @NotNull InputSnapshot snapshot(@NotNull Player rider);

    /**
     * The controller, which is the production source.
     *
     * <p>Its snapshots are published immutable values handed over by reference, so reading one per
     * occupied seat per step allocates nothing.
     *
     * @param controller the input layer
     * @return the source
     */
    static @NotNull RiderInputSource of(@NotNull PlayerInputController controller) {
        Objects.requireNonNull(controller, "controller");
        return controller::snapshot;
    }

    /**
     * The fallback: the seven movement flags the host retains, dressed as a snapshot.
     *
     * <p>For a server whose input layer is not wired, or a vehicle built before it was. It is honest
     * about its limits rather than silently reduced: there are no action inputs in it at all, no hold
     * durations and no press history, so a consumer binding a listener to
     * {@link InputKey#USE} sees nothing and a {@code hold} never matches. What does work is the
     * steering, which is the half a vehicle cannot do without.
     *
     * <p>Allocates a timing-free snapshot per call, which is why it is the fallback and not the default
     * on a server that has a controller. The arrays are two twelve-element longs; at one per occupied
     * seat per control step that is small and measurable, and the right fix is to wire the controller
     * rather than to cache here — a cache would hand a step a value from the previous one.
     *
     * @param host the root entity's seam, whose {@code intentOf} retains the flags
     * @return the source
     */
    static @NotNull RiderInputSource fromHost(@NotNull VehicleHost host) {
        Objects.requireNonNull(host, "host");
        return rider -> {
            final InputState state = InputState.ofBits(host.intentOf(rider).toBits());
            // Previous equal to current, so nothing reads as an edge. A source with no history must not
            // be able to claim one; the seat layer keeps its own previous state, which is what makes
            // SeatInput.justPressed answerable anyway.
            return InputSnapshot.ofFlags(state, state, 0L);
        };
    }

    /**
     * The seven held movement keys of {@code state}, as the steering reads them.
     *
     * @param state the input state
     * @return the intent
     */
    static @NotNull RiderIntent intentOf(@NotNull InputState state) {
        return RiderIntent.ofBits(Objects.requireNonNull(state, "state").movementBits());
    }
}
