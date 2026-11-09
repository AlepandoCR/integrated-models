package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * One step's worth of decided motion: where the vehicle goes, which way it ends up facing, and the state
 * it carries into the next step.
 *
 * <h2>A displacement, not a destination</h2>
 * Because the authoritative position is the entity's and this layer does not keep a copy — see
 * {@link VehicleState}. Handing the applier a delta lets it add that delta to wherever the entity
 * actually is, which is what makes a vehicle that was pushed by a piston, nudged by a block collision or
 * relocated by another plugin carry on driving from its real position instead of snapping back to one
 * this layer remembered.
 *
 * <h2>Vertical is the entity's business</h2>
 * {@code deltaY} is present and this layer never produces anything but zero in it. Gravity, buoyancy,
 * step-up and the collision response that makes a vehicle climb a slab are all things the server's own
 * entity physics does well and that a plugin reimplementing them does badly. So the vehicle writes
 * horizontal motion and a heading, and lets the entity fall. The component exists because the applier's
 * contract is "apply this displacement" and a displacement has three components; a two-component one
 * would have to grow a third the first time a vehicle flies.
 *
 * @param deltaX     world x displacement this step, in blocks
 * @param deltaY     world y displacement this step, in blocks
 * @param deltaZ     world z displacement this step, in blocks
 * @param yawDegrees heading after the step, normalised to {@code -180..180}
 * @param next       the state to carry into the next step
 */
public record VehicleMove(
        double deltaX,
        double deltaY,
        double deltaZ,
        float yawDegrees,
        @NotNull VehicleState next
) {

    public VehicleMove {
        Objects.requireNonNull(next, "next");
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || !Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("A vehicle move must be finite");
        }
    }

    /**
     * A step that goes nowhere and leaves the heading alone.
     *
     * @param state the state to carry forward unchanged
     * @return a move with a zero displacement
     */
    public static @NotNull VehicleMove still(@NotNull VehicleState state) {
        Objects.requireNonNull(state, "state");
        return new VehicleMove(0.0D, 0.0D, 0.0D, state.yawDegrees(), state);
    }

    /** Whether the displacement is exactly zero in all three components. */
    public boolean isStationary() {
        return this.deltaX == 0.0D && this.deltaY == 0.0D && this.deltaZ == 0.0D;
    }

    /**
     * Which kind of write this displacement needs.
     *
     * @param maxLocalStepBlocks furthest a single in-place write may reach
     * @return the kind of move
     * @see MoveKind#forDelta(double, double, double, float)
     */
    public @NotNull MoveKind kind(float maxLocalStepBlocks) {
        return MoveKind.forDelta(this.deltaX, this.deltaY, this.deltaZ, maxLocalStepBlocks);
    }
}
