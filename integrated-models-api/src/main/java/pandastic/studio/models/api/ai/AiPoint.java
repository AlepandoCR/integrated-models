package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * A point in world space, with no world attached to it.
 *
 * <h2>Why not a Bukkit {@code Location}</h2>
 * Because of which thread is allowed to read one. A {@code Location} carries a world reference, and on
 * a regionised server the interesting things you can do with a world reference are only defined on the
 * thread that owns the region you are asking about. A plain triple of doubles carries no such
 * permission and needs none, which is what lets an {@link AiIntent} published by a behaviour on a
 * region thread be read by the simulation thread without the simulation having to be told the rules.
 *
 * <p>It is also the only value shape {@link AiMemory} admits that is not a boxed primitive or a
 * {@link String}, and for the same reason: a memory outlives the tick that wrote it, so a mutable
 * platform object stored in one is a reference that keeps being dereferenced after the region that
 * produced it has moved on.
 *
 * <h2>Nothing here is tunable</h2>
 * Three coordinates and the Euclidean metric. The distances a behaviour <em>compares</em> against are
 * configuration; the arithmetic that produces them is not.
 *
 * @param x the world x coordinate, in blocks
 * @param y the world y coordinate, in blocks
 * @param z the world z coordinate, in blocks
 */
public record AiPoint(double x, double y, double z) {

    /** The world origin. Useful as a neutral value; not useful as a destination. */
    public static final AiPoint ORIGIN = new AiPoint(0.0D, 0.0D, 0.0D);

    /**
     * Refuses a non-finite coordinate.
     *
     * <p>A NaN that reaches a navigation target makes the mob stand still with no error anywhere, and a
     * NaN that reaches a look target is sent to the client as a rotation. Both are far cheaper to refuse
     * here than to find later.
     */
    public AiPoint {
        require(x, "x");
        require(y, "y");
        require(z, "z");
    }

    private static void require(double coordinate, @NotNull String name) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException(name + " must be finite but was " + coordinate);
        }
    }

    /**
     * The squared distance to {@code other}.
     *
     * <p>Squared rather than plain, because every caller in this layer compares against a configured
     * radius and can square that once instead of taking a square root per comparison per tick.
     *
     * @param other the point to measure to
     * @return the squared distance, in blocks squared
     */
    @Contract(pure = true)
    public double distanceSquaredTo(@NotNull AiPoint other) {
        final double deltaX = this.x - other.x;
        final double deltaY = this.y - other.y;
        final double deltaZ = this.z - other.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    @Override
    public @NotNull String toString() {
        return "(" + this.x + ", " + this.y + ", " + this.z + ")";
    }
}
