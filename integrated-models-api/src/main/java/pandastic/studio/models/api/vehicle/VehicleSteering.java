package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Seven held keys, resolved into the three axes a vehicle can actually be driven along.
 *
 * <h2>Why this exists between the keys and the solver</h2>
 * Because the translation is where every scheme decision lives, and it is the one part of a vehicle that
 * is pure arithmetic over booleans. Keeping it in its own type means the whole of it —
 * opposing keys cancelling, left meaning "turn" on one vehicle and "slide" on another, sprint being a
 * multiplier rather than a speed — is a function from a record to a record, testable by enumeration. A
 * solver that took {@link RiderIntent} directly would have the scheme baked into the integrator.
 *
 * <h2>Opposing keys cancel</h2>
 * Holding forward and backward together yields zero throttle, not "the last one pressed" and not
 * "forward wins". Zero is the only answer that does not require remembering which key arrived first,
 * which matters because the retained input carries no ordering — both flags simply are down. It is also
 * what the vanilla client does with its own movement vector, so a rider who rolls a finger across two
 * keys gets the same nothing they would get on foot.
 *
 * <h2>Sneak is deliberately not translated</h2>
 * It looks like an obvious brake and it is unusable as one: a rider holding sneak while mounted is
 * dismounted by the server on the same tick, so a brake bound to it would be observable for one step and
 * then stop existing. {@link RiderIntent#sneak()} stays readable for a caller that cancels the dismount
 * and wants to bind it anyway; this layer does not pretend to own it.
 *
 * @param throttle  along the vehicle's facing, {@code -1} full reverse to {@code +1} full ahead
 * @param strafe    along the vehicle's left, {@code -1} full right to {@code +1} full left. Always zero
 *                  under {@link ControlScheme#TURNING}
 * @param turn      {@code -1} full left to {@code +1} full right, measured the way Minecraft yaw grows.
 *                  Always zero under {@link ControlScheme#STRAFING}
 * @param boosting  the sprint key is held, so the profile's boost multiplier applies
 * @param jumping   the jump key is held. Carried rather than consumed: vertical motion belongs to the
 *                  root entity's own physics, so what a jump does is the owning plugin's decision
 */
public record VehicleSteering(
        float throttle,
        float strafe,
        float turn,
        boolean boosting,
        boolean jumping
) {

    /** Nothing asked for. What a vehicle with no rider is steered by, and what it coasts under. */
    public static final @NotNull VehicleSteering NEUTRAL =
            new VehicleSteering(0F, 0F, 0F, false, false);

    public VehicleSteering {
        requireUnit(throttle, "throttle");
        requireUnit(strafe, "strafe");
        requireUnit(turn, "turn");
        throttle = canonicalZero(throttle);
        strafe = canonicalZero(strafe);
        turn = canonicalZero(turn);
    }

    /**
     * The steering {@code intent} asks for under {@code scheme}.
     *
     * <p>The whole of the translation, and the only place the scheme is consulted.
     *
     * @param intent what the rider is holding
     * @param scheme what this vehicle's left and right keys mean
     * @return the resolved steering
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull VehicleSteering of(
            @NotNull RiderIntent intent,
            @NotNull ControlScheme scheme
    ) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(scheme, "scheme");

        final float throttle = axis(intent.forward(), intent.backward());
        // Left is positive on both axes, because left is the positive side of the body frame this
        // project measures side speed along. Turning is the exception: Minecraft yaw grows clockwise,
        // so turning left is a *decrease* and the sign has to flip.
        final float sideways = axis(intent.left(), intent.right());

        return new VehicleSteering(
                throttle,
                scheme == ControlScheme.STRAFING ? sideways : 0F,
                scheme == ControlScheme.TURNING ? -sideways : 0F,
                intent.sprint(),
                intent.jump());
    }

    /** Whether nothing is being asked for, so the vehicle is coasting rather than being driven. */
    @Contract(pure = true)
    public boolean isNeutral() {
        return this.throttle == 0F && this.strafe == 0F && this.turn == 0F;
    }

    /**
     * {@code +1} for the positive key alone, {@code -1} for the negative key alone, {@code 0} for both
     * or neither.
     */
    private static float axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0F;
        }
        return positive ? 1F : -1F;
    }

    /**
     * {@code axis} with a negative zero folded to zero.
     *
     * <p>Not pedantry. Negating a zero yields negative zero, and the turn axis is a negation of the
     * sideways axis — so a rider holding nothing produces a steering whose turn is {@code -0.0}. A
     * record's generated equality compares floats bitwise, which makes that value <em>unequal</em> to
     * {@link #NEUTRAL} while being indistinguishable from it in every arithmetic sense. Anything that
     * compares a steering against neutral, including a test, would then be wrong for the one input that
     * matters most.
     */
    private static float canonicalZero(float axis) {
        return axis == 0F ? 0F : axis;
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < -1F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " must be a finite fraction between -1 and 1 but was " + value);
        }
    }
}
