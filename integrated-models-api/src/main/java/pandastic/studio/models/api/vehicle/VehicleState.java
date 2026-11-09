package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Everything the server remembers about how a vehicle is moving: two speeds and a heading.
 *
 * <h2>Why a value and not a field on the vehicle</h2>
 * Because the vehicle's authority argument is that the server decides and the client is told. A state
 * that can be mutated in place has no single moment at which it is true, so a reader on another thread
 * can see a heading from this tick against a speed from the last — and the symptom of that is a vehicle
 * whose model points one way while it travels another, once in a while, for one frame. An immutable
 * record published whole makes the combination impossible, exactly as
 * {@link pandastic.studio.models.api.physics.PhysicsDriver} does for the pose pipeline.
 *
 * <h2>Position is not in here</h2>
 * Deliberately. The authoritative position is the entity's, held by the server and reachable only from
 * the thread that owns it; a copy kept here would be a second truth that drifts the first time a
 * teleport, a push or a block collision moves the entity without going through this layer. So the state
 * carries the things only this layer knows — how fast and which way — and asks the entity for where.
 *
 * @param forwardSpeedBlocksPerTick speed along the heading; negative when reversing
 * @param strafeSpeedBlocksPerTick  speed along the vehicle's left; negative to its right
 * @param yawDegrees                heading, in the degrees Minecraft measures yaw in, normalised to
 *                                  {@code -180..180}
 */
public record VehicleState(
        float forwardSpeedBlocksPerTick,
        float strafeSpeedBlocksPerTick,
        float yawDegrees
) {

    /**
     * A vehicle that has not moved and faces due south, which is yaw zero.
     *
     * <p>Not a tunable: every component is the additive identity and the heading is the origin of the
     * server's own yaw scale. A vehicle built from this is immediately overwritten with its entity's
     * real heading at mount time.
     */
    public static final @NotNull VehicleState STOPPED = new VehicleState(0F, 0F, 0F);

    public VehicleState {
        if (!Float.isFinite(forwardSpeedBlocksPerTick) || !Float.isFinite(strafeSpeedBlocksPerTick)
                || !Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("A vehicle state must be finite");
        }
        // Negative zero is folded to zero for the reason VehicleSteering does the same: a record's
        // generated equality compares floats bitwise, so a state that decayed to -0.0 would be unequal
        // to one that decayed to 0.0 while being indistinguishable from it arithmetically.
        forwardSpeedBlocksPerTick = forwardSpeedBlocksPerTick == 0F ? 0F : forwardSpeedBlocksPerTick;
        strafeSpeedBlocksPerTick = strafeSpeedBlocksPerTick == 0F ? 0F : strafeSpeedBlocksPerTick;
        final float normalised = normaliseDegrees(yawDegrees);
        yawDegrees = normalised == 0F ? 0F : normalised;
    }

    /**
     * This state with a different heading.
     *
     * @param yawDegrees the heading, normalised by the constructor
     * @return a copy facing {@code yawDegrees}
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleState withYaw(float yawDegrees) {
        return new VehicleState(this.forwardSpeedBlocksPerTick, this.strafeSpeedBlocksPerTick,
                yawDegrees);
    }

    /**
     * Whether both speeds are below {@code epsilonBlocksPerTick}.
     *
     * <p>The threshold is a parameter rather than a constant because what counts as stopped is a
     * statement about what the server is willing to pay for: below it, this layer stops writing positions
     * at all, and a figure chosen here would be a tunable hidden in the api.
     *
     * <p>Note what this does <em>not</em> consider: the heading. A vehicle turning in place has both
     * speeds at zero and is still being driven, which is why the step that uses this also asks whether
     * the heading moved.
     *
     * @param epsilonBlocksPerTick the speed below which motion is not worth a packet
     * @return whether the vehicle has stopped translating
     */
    @Contract(pure = true)
    public boolean isAtRest(float epsilonBlocksPerTick) {
        return Math.abs(this.forwardSpeedBlocksPerTick) < epsilonBlocksPerTick
                && Math.abs(this.strafeSpeedBlocksPerTick) < epsilonBlocksPerTick;
    }

    /**
     * {@code degrees} folded into {@code -180..180}.
     *
     * <p>Normalised rather than left unbounded, unlike the yaw the pose pipeline samples. There the
     * unwrapped value is the information — it distinguishes a two-degree turn from a 358-degree one —
     * whereas here the yaw is integrated every step for the life of the vehicle, so leaving it to grow
     * would spend its float precision on turns that happened minutes ago.
     *
     * @param degrees any finite angle
     * @return the same bearing within {@code -180..180}
     */
    @Contract(pure = true)
    public static float normaliseDegrees(float degrees) {
        float wrapped = degrees % 360F;
        if (wrapped >= 180F) {
            wrapped -= 360F;
        } else if (wrapped < -180F) {
            wrapped += 360F;
        }
        return wrapped;
    }
}
