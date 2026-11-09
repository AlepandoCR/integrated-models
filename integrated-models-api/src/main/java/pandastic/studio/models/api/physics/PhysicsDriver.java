package pandastic.studio.models.api.physics;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The owner's movement for one simulation step — everything the solver needs to know about the world
 * outside the rig.
 *
 * <p>Deliberately a handful of scalars rather than a reference to an entity. The solver runs off the
 * server tick, where reading an entity's position is not merely racy but undefined: on a regionised
 * server an entity may only be touched from the thread that owns it. Sampling these four numbers on
 * the owning thread and handing them over as a value is the whole safety argument, so this type is a
 * record with no object references in it.
 *
 * <p>Speeds are in <strong>blocks per server tick</strong>, matching how entity deltas are naturally
 * differenced; the solver converts. Forward and side are in the owner's own frame, so a model that
 * turns on the spot sees yaw rate but no translation.
 *
 * @param forwardSpeed    blocks/tick along the owner's facing
 * @param sideSpeed       blocks/tick to the owner's left
 * @param verticalSpeed   blocks/tick upward
 * @param yawRateRadians  radians of body yaw gained this tick, already wrapped to {@code -pi..pi}
 */
public record PhysicsDriver(
        float forwardSpeed,
        float sideSpeed,
        float verticalSpeed,
        float yawRateRadians
) {

    /**
     * A motionless owner. Physics still settles toward rest; it simply is not being driven.
     *
     * <p>Not a tunable: every component is the additive identity, so this is "no movement" rather than
     * "a little movement somebody picked".
     */
    public static final @NotNull PhysicsDriver STILL = new PhysicsDriver(0F, 0F, 0F, 0F);

    public PhysicsDriver {
        if (!Float.isFinite(forwardSpeed) || !Float.isFinite(sideSpeed)
                || !Float.isFinite(verticalSpeed) || !Float.isFinite(yawRateRadians)) {
            throw new IllegalArgumentException("A physics driver must be finite");
        }
    }

    /**
     * This driver with teleport-sized deltas discarded.
     *
     * <p>Call it on whatever differenced an entity's position, not in the solver: a position delta is
     * indistinguishable from a teleport without knowing it came from one, and the solver has no way to
     * find out.
     *
     * @param bounds how large a delta may be before it is a teleport. Configured rather than fixed
     *               because what an entity can legitimately do in one tick depends on what else is
     *               installed on the server
     */
    @Contract(value = "_ -> _", pure = true)
    public @NotNull PhysicsDriver withoutTeleports(@NotNull DriverBounds bounds) {
        final float maxStep = bounds.maxStepBlocks();
        final boolean horizontalJump = Math.abs(this.forwardSpeed) > maxStep
                || Math.abs(this.sideSpeed) > maxStep;
        final boolean verticalJump = Math.abs(this.verticalSpeed) > bounds.maxVerticalStepBlocks();
        if (!horizontalJump && !verticalJump) {
            return this;
        }
        return new PhysicsDriver(
                horizontalJump ? 0F : this.forwardSpeed,
                horizontalJump ? 0F : this.sideSpeed,
                verticalJump ? 0F : this.verticalSpeed,
                this.yawRateRadians);
    }

    /** Whether the owner is translating or turning at all. */
    public boolean isMoving() {
        return this.forwardSpeed != 0F || this.sideSpeed != 0F
                || this.verticalSpeed != 0F || this.yawRateRadians != 0F;
    }
}
