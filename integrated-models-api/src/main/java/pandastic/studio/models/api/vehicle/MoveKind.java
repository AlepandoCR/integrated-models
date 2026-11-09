package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How a decided displacement has to reach the world.
 *
 * <h2>The platform fact this enum exists for</h2>
 * On a regionised server a position write is legal only inside the chunks the ticking region already
 * owns, and <strong>a ticking region cannot expand its owned chunk set in the middle of a tick</strong>.
 * There is no trick and no fast path: a step that might leave the owned chunks has to go through the
 * asynchronous teleport, which is scheduled, completes on the destination region later, and removes the
 * entity and its whole passenger tree from the world before re-adding it.
 *
 * <p>So the two ways of moving are not two implementations of one operation. One is a cheap write with a
 * precondition; the other is a scheduled transaction with a visible cost and a window during which the
 * entity is in neither region. Naming them makes the choice reviewable, and makes it the kind of thing a
 * test can assert about rather than a branch buried in a method that applies movement.
 *
 * @see #forDelta(double, double, double, float)
 */
public enum MoveKind {

    /**
     * Nothing to write.
     *
     * <p>Not an optimisation for its own sake. A vehicle sitting still that keeps writing its own
     * position pays the full cost of a moving one, and — because a display entity consumes its
     * pending-update flags once per client tick whatever is in them — buys nothing visible with it.
     */
    NONE,

    /**
     * A write inside the owned chunks, applied in place.
     *
     * <p>The ordinary case, and the one a vehicle spends essentially all of its life in: a vehicle moving
     * at any speed a rider would enjoy covers a fraction of a block per tick.
     */
    LOCAL,

    /**
     * A displacement large enough that it may cross out of the owned chunks, so it goes through the
     * asynchronous teleport.
     *
     * <p>Reached by a vehicle that is genuinely very fast, by one whose control loop missed several ticks
     * and is catching up, and by a deliberate relocation. All three are the same problem from the
     * region's point of view.
     */
    TELEPORT;

    /**
     * Which kind a displacement of {@code (deltaX, deltaY, deltaZ)} blocks needs.
     *
     * <p>Measured as a true distance rather than per axis, because the constraint is about leaving a
     * region and a diagonal step leaves one sooner than either of its components suggests. Compared
     * squared, so the common case costs three multiplies and no square root.
     *
     * <p>{@link #NONE} is returned only for an exactly zero displacement. Anything smaller than
     * "worth a packet" is a judgement with a threshold attached, and that threshold is configuration
     * held by the layer that owns the control loop — this method's job is the platform rule.
     *
     * @param deltaX              world x displacement, in blocks
     * @param deltaY              world y displacement, in blocks
     * @param deltaZ              world z displacement, in blocks
     * @param maxLocalStepBlocks  furthest a single in-place write may reach. Configuration, because how
     *                            much of a region is safely owned depends on the server's chunk loading
     *                            rather than on anything derivable here
     * @return the kind of move needed; {@link #TELEPORT} for a non-finite displacement, since a position
     *         that cannot be written in place certainly cannot be written safely
     */
    @Contract(pure = true)
    public static @NotNull MoveKind forDelta(
            double deltaX,
            double deltaY,
            double deltaZ,
            float maxLocalStepBlocks
    ) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)) {
            // Refusing outright would leave the caller with nothing to do and a vehicle that is stuck;
            // routing it through the teleport path lets the platform refuse the destination instead,
            // which it does with a message naming the coordinate.
            return TELEPORT;
        }
        if (deltaX == 0.0D && deltaY == 0.0D && deltaZ == 0.0D) {
            return NONE;
        }
        final double squared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        final double limit = (double) maxLocalStepBlocks * maxLocalStepBlocks;
        return squared > limit ? TELEPORT : LOCAL;
    }
}
