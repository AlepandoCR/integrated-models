package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Per-axis angular bounds for a physics bone, in <strong>radians</strong>.
 *
 * <p>Limits are the whole reason this library simulates in angle space. In angle space a limit is a
 * clamp; in world space it is a constraint that has to be projected back onto the chain, and competing
 * projections are the usual source of jitter in world-space chains.
 *
 * <p>Authors write degrees; this type stores radians so the solver never converts inside its loop.
 * Use {@link #ofDegrees} at load time.
 */
public record AxisLimits(
        float minPitch, float maxPitch,
        float minYaw, float maxYaw,
        float minRoll, float maxRoll
) {

    /**
     * Unbounded on every axis. Only sane for {@link PhysicsKind#DRAG}, which cannot diverge.
     *
     * <p>Not a tunable: infinity is the identity of a clamp, so this is "no limit" rather than "a very
     * wide limit somebody chose". There is no finite value that means the same thing.
     */
    public static final @NotNull AxisLimits UNBOUNDED = new AxisLimits(
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY
    );

    public AxisLimits {
        requireOrdered(minPitch, maxPitch, "pitch");
        requireOrdered(minYaw, maxYaw, "yaw");
        requireOrdered(minRoll, maxRoll, "roll");
    }

    private static void requireOrdered(float min, float max, @NotNull String axis) {
        if (Float.isNaN(min) || Float.isNaN(max)) {
            throw new IllegalArgumentException("NaN limit on the " + axis + " axis");
        }
        if (min > max) {
            throw new IllegalArgumentException(
                    "Inverted limits on the " + axis + " axis: min=" + min + " > max=" + max);
        }
    }

    /**
     * Symmetric bounds of {@code degrees} either way on all three axes.
     *
     * <p>This is also the factory that replaced the former {@code DEFAULT} constant: the bound applied
     * when a rig omits one is {@code symmetricDegrees(bounds.defaultLimitDegrees())}. A constant could
     * only ever be right for one model scale, and it was the first thing anyone rigging at a different
     * scale had to work around.
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull AxisLimits symmetricDegrees(float degrees) {
        final float radians = (float) Math.toRadians(degrees);
        return new AxisLimits(-radians, radians, -radians, radians, -radians, radians);
    }

    /** Bounds given per axis in degrees, converted once. */
    @Contract(value = "_, _, _, _, _, _ -> new", pure = true)
    public static @NotNull AxisLimits ofDegrees(
            float minPitch, float maxPitch,
            float minYaw, float maxYaw,
            float minRoll, float maxRoll
    ) {
        return new AxisLimits(
                (float) Math.toRadians(minPitch), (float) Math.toRadians(maxPitch),
                (float) Math.toRadians(minYaw), (float) Math.toRadians(maxYaw),
                (float) Math.toRadians(minRoll), (float) Math.toRadians(maxRoll)
        );
    }

    /**
     * This instance with the pitch ceiling lowered to {@code ceiling} if that is tighter.
     *
     * <p>Used to fold a {@link PhysicsKind#CLOTH_STRIP}'s collision cone into a plain clamp at rig
     * compile time, so the solver never runs a collision test.
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull AxisLimits withPitchCeiling(float ceiling) {
        final float tightened = Math.min(this.maxPitch, ceiling);
        return new AxisLimits(
                Math.min(this.minPitch, tightened), tightened,
                this.minYaw, this.maxYaw,
                this.minRoll, this.maxRoll
        );
    }

    /** The lower bound on {@code axis}. */
    public float min(@NotNull RotationAxis axis) {
        return switch (axis) {
            case PITCH -> this.minPitch;
            case YAW -> this.minYaw;
            case ROLL -> this.minRoll;
        };
    }

    /** The upper bound on {@code axis}. */
    public float max(@NotNull RotationAxis axis) {
        return switch (axis) {
            case PITCH -> this.maxPitch;
            case YAW -> this.maxYaw;
            case ROLL -> this.maxRoll;
        };
    }
}
