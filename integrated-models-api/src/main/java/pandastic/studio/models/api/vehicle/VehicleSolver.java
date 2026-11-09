package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The whole of a vehicle's motion, as one pure function of state, steering, profile and elapsed time.
 *
 * <h2>Why a vehicle gets a closed form and not an integrator</h2>
 * The same reason {@code WalkCycleSolver} does. There is no second-order system here — nothing
 * oscillates, nothing overshoots, there is no spring to go unstable — so there is nothing for a
 * substepped integrator to stabilise and nothing for a stability bound to protect. A vehicle's speed
 * approaches what the rider asked for at a declared rate and its heading turns at a declared rate, and
 * both of those are expressible exactly. That matters beyond elegance: a closed form is
 * <strong>step-size independent</strong>, so a control loop that missed three ticks produces the same
 * answer as three loops that did not, and a server under load gets a vehicle that is slow to respond
 * rather than one that travels a different distance.
 *
 * <h2>Turn first, then translate</h2>
 * The heading is integrated before the displacement is projected, so a vehicle turning while accelerating
 * travels along the heading it <em>ends</em> the step with. The other order is a half-step of lag that
 * shows up as a vehicle cutting the inside of every corner, which reads as the controls being late.
 *
 * <h2>Friction compounds, acceleration does not</h2>
 * Coasting multiplies the speed by {@code (1 - friction)} once per tick, so over a fractional or
 * multiple tick count the exponent is the tick count — which is what makes one step of two ticks agree
 * with two steps of one. Acceleration, by contrast, is a rate and is multiplied by the tick count. Using
 * the wrong one of the two for either is the classic way to get a vehicle whose stopping distance
 * depends on the server's load.
 *
 * <h2>Nothing here is tunable</h2>
 * Every coefficient arrives in the {@link VehicleProfile}, every limit in the {@link VehicleBounds} that
 * validated it, and the elapsed time from a measured clock. The only literals are {@code 0} and
 * {@code 1}, as the additive and multiplicative identities.
 */
public final class VehicleSolver {

    private VehicleSolver() {
    }

    /**
     * The vehicle's motion over {@code ticks} of elapsed time.
     *
     * <p>{@code ticks} is a real measured figure, not the constant the loop was dispatched at: a region
     * thread's period is a request rather than a guarantee, and a loop that assumes its own nominal
     * period makes every vehicle on a loaded server travel a distance that depends on the load. The
     * caller is responsible for clamping it, because what counts as "too long a gap to integrate across"
     * is configuration and this function has none.
     *
     * @param state    where the vehicle is in its own motion
     * @param steering what the rider is asking for
     * @param profile  how this vehicle drives
     * @param ticks    elapsed server ticks; zero yields a move that goes nowhere
     * @return the displacement, the new heading and the state to carry forward
     * @throws IllegalArgumentException if {@code ticks} is negative or not finite
     */
    @Contract(value = "_, _, _, _ -> new", pure = true)
    public static @NotNull VehicleMove advance(
            @NotNull VehicleState state,
            @NotNull VehicleSteering steering,
            @NotNull VehicleProfile profile,
            float ticks
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(steering, "steering");
        Objects.requireNonNull(profile, "profile");
        if (!Float.isFinite(ticks) || ticks < 0F) {
            throw new IllegalArgumentException(
                    "ticks must be finite and not negative but was " + ticks);
        }
        if (ticks == 0F) {
            // No elapsed time is not the same as no input: the rider may be holding a key, but nothing
            // has happened yet and inventing a displacement for it would make the vehicle's speed
            // depend on how often it was asked rather than on how long it drove.
            return VehicleMove.still(state);
        }

        final float yawDegrees = VehicleState.normaliseDegrees(
                state.yawDegrees() + steering.turn() * profile.turnRateDegreesPerTick() * ticks);

        final float boost = steering.boosting() ? profile.boostMultiplier() : 1F;
        // Boost applies ahead only. A vehicle that reverses or slides faster when the rider holds
        // sprint is a vehicle whose boost key is a speed key, which is a different feature.
        final float forwardTarget = steering.throttle() >= 0F
                ? steering.throttle() * profile.maxForwardSpeedBlocksPerTick() * boost
                : steering.throttle() * profile.maxReverseSpeedBlocksPerTick();
        final float strafeTarget = steering.strafe() * profile.maxStrafeSpeedBlocksPerTick();

        // The clamps are the profile's absolute envelope rather than this step's target, so releasing
        // the boost lets the speed decay at the braking rate instead of being snapped down to the
        // unboosted ceiling the instant the key comes up.
        final float forwardSpeed = approach(state.forwardSpeedBlocksPerTick(), forwardTarget,
                profile, ticks, profile.boostedForwardSpeedBlocksPerTick(),
                profile.maxReverseSpeedBlocksPerTick());
        final float strafeSpeed = approach(state.strafeSpeedBlocksPerTick(), strafeTarget,
                profile, ticks, profile.maxStrafeSpeedBlocksPerTick(),
                profile.maxStrafeSpeedBlocksPerTick());

        // Minecraft yaw grows clockwise from +Z, so the facing is (-sin, cos) and the vehicle's own
        // left — the axis strafe is measured along — is (cos, sin). The same convention the pose
        // pipeline's driver sampler projects into, so a vehicle and the model on it agree about which
        // way sideways is.
        final double heading = Math.toRadians(yawDegrees);
        final double sin = Math.sin(heading);
        final double cos = Math.cos(heading);
        final double forwardDistance = (double) forwardSpeed * ticks;
        final double strafeDistance = (double) strafeSpeed * ticks;

        return new VehicleMove(
                -forwardDistance * sin + strafeDistance * cos,
                // Vertical motion belongs to the entity's own physics; see VehicleMove.
                0.0D,
                forwardDistance * cos + strafeDistance * sin,
                yawDegrees,
                new VehicleState(forwardSpeed, strafeSpeed, yawDegrees));
    }

    /**
     * {@code speed} moved toward {@code target} over {@code ticks}, clamped to the profile's limits.
     *
     * <p>Three regimes, and which one applies is decided by what the change does to the speed's
     * magnitude rather than by the sign of the input. That is what makes a rider who slams from full
     * ahead to full astern get the braking rate until the vehicle has stopped and the acceleration rate
     * afterwards, without the caller having to notice the crossing.
     *
     * @param speed        current speed along this axis
     * @param target       the speed the rider is asking for
     * @param profile      the rates and the friction
     * @param ticks        elapsed ticks
     * @param positiveLimit the profile's whole envelope in the positive direction, boost included
     * @param negativeLimit the profile's whole envelope in the negative direction, as a positive figure
     * @return the new speed
     */
    private static float approach(
            float speed,
            float target,
            @NotNull VehicleProfile profile,
            float ticks,
            float positiveLimit,
            float negativeLimit
    ) {
        final float moved;
        if (target == 0F) {
            // Coasting. Geometric in the tick count, so the result is step-size independent; see the
            // class note. A friction of exactly 1 stops the vehicle dead, which Math.pow gives for
            // free as 0 for any positive exponent.
            moved = speed * (float) Math.pow(1.0D - profile.frictionPerTick(), ticks);
        } else {
            // Positive when the change pushes the speed further from zero, so zero itself counts as
            // accelerating — which is right: a stationary vehicle being asked to move is not braking.
            final boolean gaining = speed * (target - speed) >= 0F;
            final float rate = gaining
                    ? profile.accelerationBlocksPerTickSquared()
                    : profile.brakingBlocksPerTickSquared();
            final float step = rate * ticks;
            moved = target > speed
                    ? Math.min(target, speed + step)
                    : Math.max(target, speed - step);
        }
        // A rail rather than a shaper: the envelope is what this profile can ever reach, so the only
        // thing it catches is a speed carried over from a profile that has since been narrowed.
        return Math.clamp(moved, -negativeLimit, positiveLimit);
    }
}
