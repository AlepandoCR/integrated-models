package pandastic.studio.models.api.decoration;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Where a decoration stands: a position and an orientation, with no world and no entity in it.
 *
 * <h2>Why the world is not a component</h2>
 * A decoration cannot change world. Moving one across a world boundary means a different renderer
 * registry and a different set of viewers, which is a new tracker rather than a new position — so the
 * world belongs to the handle, fixed for its whole life, and is reported by
 * {@link Decoration#getWorldId()}. Carrying it here would make {@link Decoration#moveTo} look as though
 * it could do something it cannot, and would put a platform object inside a value type that the
 * simulation reads off the server tick.
 *
 * <p>Which is the second reason: this is read by the thread that computes a decoration's ambient drive,
 * and that thread may not touch a world. Five numbers can cross that boundary; a {@code World} cannot.
 *
 * <h2>Immutable, and that is what makes it publishable</h2>
 * A move replaces the whole value rather than editing one of five fields, so a reader either sees the
 * old placement or the new one and never a position from one and a yaw from the other. That is the same
 * argument {@link pandastic.studio.models.api.physics.PhysicsDriver} makes, for the same hand-off.
 *
 * @param x            world x, in blocks
 * @param y            world y, in blocks
 * @param z            world z, in blocks
 * @param yawDegrees   rotation about the vertical axis, in degrees, as the platform measures yaw —
 *                     zero facing {@code +Z} and growing clockwise
 * @param pitchDegrees rotation about the horizontal axis, in degrees. Honoured by the renderer, so a
 *                     hanging sign can be tilted; the physics ignores it, because secondary motion is
 *                     expressed in the decoration's own frame and that frame is what pitch rotates
 */
public record DecorationPlacement(
        double x,
        double y,
        double z,
        float yawDegrees,
        float pitchDegrees
) {

    public DecorationPlacement {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException(
                    "A placement must be finite but was [" + x + ", " + y + ", " + z + "]");
        }
        if (!Float.isFinite(yawDegrees) || !Float.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException(
                    "A placement's rotation must be finite but was yaw " + yawDegrees + ", pitch "
                            + pitchDegrees);
        }
    }

    /**
     * A placement with no pitch, which is what almost every decoration wants.
     *
     * @param x          world x, in blocks
     * @param y          world y, in blocks
     * @param z          world z, in blocks
     * @param yawDegrees rotation about the vertical axis, in degrees
     * @return the placement, upright
     */
    @Contract(value = "_, _, _, _ -> new", pure = true)
    public static @NotNull DecorationPlacement of(double x, double y, double z, float yawDegrees) {
        return new DecorationPlacement(x, y, z, yawDegrees, 0F);
    }

    /**
     * This placement moved to another position, keeping its orientation.
     *
     * @param newX world x to move to, in blocks
     * @param newY world y to move to, in blocks
     * @param newZ world z to move to, in blocks
     * @return the moved placement
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public @NotNull DecorationPlacement at(double newX, double newY, double newZ) {
        return new DecorationPlacement(newX, newY, newZ, this.yawDegrees, this.pitchDegrees);
    }

    /**
     * This placement turned to another yaw, keeping its position and pitch.
     *
     * @param newYawDegrees the yaw to turn to, in degrees
     * @return the turned placement
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull DecorationPlacement withYaw(float newYawDegrees) {
        return new DecorationPlacement(this.x, this.y, this.z, newYawDegrees, this.pitchDegrees);
    }

    /**
     * Yaw in radians, which is the unit every projection into the decoration's own frame needs.
     *
     * <p>Derived rather than stored: the platform reports degrees and an operator writes degrees, while
     * every trigonometric use of it wants radians. Converting at the use site would put the same
     * conversion in three places.
     *
     * @return the yaw, in radians
     */
    public double yawRadians() {
        return Math.toRadians(this.yawDegrees);
    }
}
