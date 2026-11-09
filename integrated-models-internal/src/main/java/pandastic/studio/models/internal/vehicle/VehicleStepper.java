package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleMove;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSolver;
import pandastic.studio.models.api.vehicle.VehicleState;
import pandastic.studio.models.api.vehicle.VehicleSteering;

import java.util.Objects;

/**
 * One control step, decided and not applied: the whole of a vehicle's per-tick thinking as a pure
 * function.
 *
 * <h2>Why deciding and applying are separated</h2>
 * Because applying touches the world and deciding does not, and the half that contains every judgement
 * is the half that does not. Everything a vehicle can get wrong lives here — how much elapsed time to
 * trust, whether the rider's keys mean turn or slide, how fast the result may be, whether the resulting
 * displacement is small enough to write in place, whether the vehicle has stopped — and all of it is a
 * function from four values to one record. {@link VehicleImpl} is then a dispatcher: it reads a clock,
 * calls this, and performs the one operation the answer names.
 *
 * <p>That split is what makes the riskiest feature in the project testable without a server. A test
 * drives this with a scripted elapsed time and a scripted bitfield and asserts on the plan, including
 * the teleport decision, which is the thing most expensive to get wrong and least visible when it is.
 *
 * <h2>Elapsed time is measured, never assumed</h2>
 * A repeating task's period is a request. The scheduler drops missed iterations rather than replaying
 * them, so after any stall one step sees the whole gap — and a step that multiplies its nominal period by
 * one instead of asking the clock makes every vehicle on a loaded server travel a distance that depends
 * on the load. So the elapsed nanoseconds come in from outside and are converted here, with the gap
 * clamped by configuration rather than trusted: integrating a five-second gap faithfully puts a vehicle
 * through whatever was in the way, and losing the distance is the lesser failure.
 *
 * <h2>Resting is a fixed point, deliberately</h2>
 * Friction is geometric, so a coasting vehicle approaches zero speed and never arrives. Left alone it
 * writes a position every tick forever for a displacement no client could resolve. So a coasting vehicle
 * whose speed has fallen below the configured rest threshold is snapped to a stop, which makes
 * {@link Plan#isIdle()} stable rather than almost-true — and only while the rider is asking for nothing,
 * because snapping a vehicle that is being driven would stop it from ever starting if its acceleration
 * were below the threshold.
 */
public final class VehicleStepper {

    /**
     * Nanoseconds in one server tick.
     *
     * <p><strong>A protocol fact, derived rather than restated.</strong> The server and the client run
     * the same tick rate — that is what makes a display entity consume its pending-update flags once per
     * server tick's worth of time — and {@link EmissionCadence#CLIENT_TICK_HZ} is where this project
     * already declares it. Deriving from it means the two cannot drift apart, and an operator cannot be
     * offered a key whose only effect would be to make every speed in {@code vehicle.yml} mean something
     * other than what it says.
     */
    static final long NANOS_PER_TICK = 1_000_000_000L / EmissionCadence.CLIENT_TICK_HZ;

    private VehicleStepper() {
    }

    /**
     * What this step should do.
     *
     * @param state        where the vehicle is in its own motion
     * @param intent       the keys the rider is holding; {@link RiderIntent#NONE} when there is no rider
     * @param profile      how this vehicle drives
     * @param settings     the clamps and thresholds in force, read once for the whole step
     * @param elapsedNanos nanoseconds since the previous step, from a monotonic clock. Zero or negative
     *                     yields a plan that does nothing, which is the honest answer to "no time has
     *                     passed" and the right one for the first step after a vehicle is created
     * @return the resolved steering, the decided move, and how it has to be applied
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    public static @NotNull Plan plan(
            @NotNull VehicleState state,
            @NotNull RiderIntent intent,
            @NotNull VehicleProfile profile,
            @NotNull VehicleSettings settings,
            long elapsedNanos
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(settings, "settings");

        final VehicleSteering steering = VehicleSteering.of(intent, profile.controlScheme());
        final float ticks = elapsedTicks(elapsedNanos, settings.maxStepTicks());
        final VehicleMove advanced = VehicleSolver.advance(state, steering, profile, ticks);
        final VehicleMove move = settle(advanced, steering, settings.restSpeedBlocksPerTick());
        return new Plan(steering, move, move.kind(settings.maxLocalStepBlocks()), ticks);
    }

    /**
     * {@code elapsedNanos} as a tick count, clamped.
     *
     * @param elapsedNanos nanoseconds since the previous step
     * @param maxStepTicks the configured ceiling
     * @return the elapsed ticks, never negative and never above the ceiling
     */
    static float elapsedTicks(long elapsedNanos, float maxStepTicks) {
        if (elapsedNanos <= 0L) {
            // A monotonic clock should not go backwards, and the first step of a vehicle's life has
            // nothing to difference against. Both answer "no time has passed", which the solver turns
            // into a move that goes nowhere rather than into a guess.
            return 0F;
        }
        // Divided as a double before narrowing: a float nanosecond count above a few million has
        // consecutive values hundreds of nanoseconds apart, which at a 50 ms tick would quantise the
        // jitter this measurement exists to capture.
        final float ticks = (float) ((double) elapsedNanos / (double) NANOS_PER_TICK);
        return Math.min(ticks, maxStepTicks);
    }

    /**
     * {@code advanced}, with a coasting vehicle below the rest threshold snapped to a dead stop.
     *
     * <p>The heading is kept. A vehicle that has stopped has not forgotten which way it is pointing, and
     * zeroing the heading here would spin every parked vehicle on the server to due south.
     *
     * @param advanced the solver's answer
     * @param steering what the rider is asking for
     * @param restSpeedBlocksPerTick the threshold below which a coasting vehicle has stopped
     * @return the move to apply
     */
    private static @NotNull VehicleMove settle(
            @NotNull VehicleMove advanced,
            @NotNull VehicleSteering steering,
            float restSpeedBlocksPerTick
    ) {
        if (!steering.isNeutral() || !advanced.next().isAtRest(restSpeedBlocksPerTick)) {
            return advanced;
        }
        return VehicleMove.still(
                new VehicleState(0F, 0F, advanced.next().yawDegrees()));
    }

    /**
     * What one step decided.
     *
     * @param steering what the rider's keys resolved to, kept so a plugin can read the jump key this
     *                 layer deliberately does not act on
     * @param move     the displacement, the heading and the state to carry forward
     * @param kind     how the displacement has to reach the world
     * @param ticks    the elapsed time this plan was computed over, after clamping. Exposed because a
     *                 diagnostic about a vehicle that is running slow is a question about this number
     */
    public record Plan(
            @NotNull VehicleSteering steering,
            @NotNull VehicleMove move,
            @NotNull MoveKind kind,
            float ticks
    ) {

        public Plan {
            Objects.requireNonNull(steering, "steering");
            Objects.requireNonNull(move, "move");
            Objects.requireNonNull(kind, "kind");
        }

        /**
         * Whether this step has nothing at all to apply.
         *
         * <p>Both halves have to be settled, which is the same rule the pose pipeline's emission gate
         * follows: a vehicle being steered is not idle however small the displacement, because the next
         * step's displacement will be larger, and a vehicle with a displacement is not idle however
         * neutral the keys. Only the conjunction is a fixed point.
         *
         * @return whether the step can be skipped entirely
         */
        public boolean isIdle() {
            return this.steering.isNeutral() && this.kind == MoveKind.NONE;
        }
    }
}
