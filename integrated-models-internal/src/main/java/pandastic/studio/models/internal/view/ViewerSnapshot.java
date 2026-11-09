package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Everything the culler needs to know about one viewer, as an immutable value.
 *
 * <h2>Why a value and not a Player</h2>
 * The culling decision runs off the server tick, and on a regionised server reading a player's
 * position from the wrong thread is not merely racy but <em>undefined</em> — the ownership check is the
 * only sanctioned way to ask, and it answers "no" from any thread that is not the owning region's. So a
 * player's position is sampled on their own scheduler and handed over as this record, which holds no
 * object references at all. That is the whole safety argument for this layer, and it is why nothing
 * here needs to be scheduled back onto a region thread to be read.
 *
 * <h2>The look vector is precomputed, deliberately</h2>
 * Yaw and pitch are turned into a unit vector <em>on the sampling thread</em>, once per viewer per
 * sample, rather than in the culler. The culler then runs over every (model, viewer) pair, so the two
 * trigonometric calls that would live there are the difference between a few hundred per second and a
 * few hundred thousand. The hot path ends up as one dot product and one square root.
 *
 * <h2>The eye, not the feet</h2>
 * The position is the eye: where the camera is anchored in first person, and the point every camera
 * mode is an offset from. Culling against the feet would be wrong by the better part of two blocks.
 *
 * @param playerId           who this is
 * @param worldId            which world they are in; a mismatch is an unconditional cull
 * @param x                  eye position
 * @param eyeY               eye position
 * @param z                  eye position
 * @param lookX              unit look vector, already normalised
 * @param lookY              unit look vector
 * @param lookZ              unit look vector
 * @param viewDistanceChunks the render distance the client reported, a hard ceiling on what it can
 *                           draw however much the server sends
 * @param sampledAtNanos     when this was taken, so a consumer can tell a stale snapshot from a fresh one
 */
public record ViewerSnapshot(
        @NotNull UUID playerId,
        @NotNull UUID worldId,
        double x,
        double eyeY,
        double z,
        double lookX,
        double lookY,
        double lookZ,
        int viewDistanceChunks,
        long sampledAtNanos
) {

    /**
     * How far a look vector's length may stray from 1 before it is treated as a mistake.
     *
     * <h2>Why this is not configuration</h2>
     * Every other epsilon in this project is a key in a file, because every other one is a threshold on
     * a <em>measurement</em> — how little a bone has to move before it stops being worth a packet, how
     * small a reach vector may be before it cannot be normalised. An operator can legitimately want a
     * different answer to those. This one is not that shape: it is a precondition on a vector built in
     * code, three lines above, out of a {@code cos} and a {@code sin}. There is no value an operator
     * could usefully put here, because the only two outcomes are "the sampler is correct" and "the
     * sampler has a bug", and a key would let a server configure the second one away.
     *
     * <h2>Where the figure comes from</h2>
     * {@code sin} and {@code cos} on this platform are accurate to within an ulp, and the product and
     * the square root that follow add a handful more, so the length of a vector built by {@link #of} is
     * wrong by a few parts in {@code 1e16}. A tolerance that merely allowed for that would be around
     * {@code 1e-15}; this one is twelve orders of magnitude looser, and deliberately so. It is not
     * sized to admit rounding error — anything correct clears it by an enormous margin — it is sized to
     * reject a vector that was never a unit vector at all: a difference between two positions handed
     * over as a direction, a zero-filled field, a vector scaled by a distance. Those are wrong by tens
     * of percent or more, so no threshold between {@code 1e-15} and about {@code 1e-2} would behave
     * differently, and picking the loose end of that range means the check can never fire on a
     * legitimate vector on some future platform whose trigonometry is a little worse.
     *
     * <p>Which is also the argument against moving it to the configuration mapper's input-sanity
     * floors, where the project's other underivable thresholds live. Those bound what a <em>file</em>
     * may say, and no file says anything about a look vector; this bounds what one thread may hand
     * another.
     */
    private static final double UNIT_TOLERANCE = 1.0E-3D;

    public ViewerSnapshot {
        if (!Double.isFinite(x) || !Double.isFinite(eyeY) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("A viewer snapshot must have a finite position");
        }
        final double length = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (!Double.isFinite(length) || Math.abs(length - 1D) > UNIT_TOLERANCE) {
            // Checked rather than normalised here: a non-unit vector means whoever sampled it got the
            // yaw/pitch conversion wrong, and silently fixing it would hide that while leaving every
            // angular comparison subtly scaled.
            throw new IllegalArgumentException(
                    "The look vector must be a unit vector, but its length is " + length);
        }
        if (viewDistanceChunks < 0) {
            throw new IllegalArgumentException("Negative client view distance: " + viewDistanceChunks);
        }
    }

    /**
     * A snapshot built from a player's yaw and pitch in degrees, which is the form the server has them
     * in.
     *
     * <p>Call this on the thread that owns the player. The conversion matches Minecraft's convention:
     * yaw 0 faces +Z, and pitch is positive looking down.
     */
    public static @NotNull ViewerSnapshot of(
            @NotNull UUID playerId,
            @NotNull UUID worldId,
            double x,
            double eyeY,
            double z,
            float yawDegrees,
            float pitchDegrees,
            int viewDistanceChunks,
            long sampledAtNanos
    ) {
        final double yaw = Math.toRadians(yawDegrees);
        final double pitch = Math.toRadians(pitchDegrees);
        final double horizontal = Math.cos(pitch);
        return new ViewerSnapshot(
                playerId, worldId, x, eyeY, z,
                -horizontal * Math.sin(yaw),
                -Math.sin(pitch),
                horizontal * Math.cos(yaw),
                viewDistanceChunks, sampledAtNanos);
    }

    /**
     * Squared distance from this viewer's eye to a point, or {@link Double#POSITIVE_INFINITY} if the
     * point is in another world.
     *
     * <p>Squared, because the comparison that uses it is against a squared range and a square root per
     * model per viewer per pass is a cost with no purpose.
     */
    public double distanceSquaredTo(@NotNull UUID world, double pointX, double pointY, double pointZ) {
        if (!this.worldId.equals(world)) {
            return Double.POSITIVE_INFINITY;
        }
        final double dx = pointX - this.x;
        final double dy = pointY - this.eyeY;
        final double dz = pointZ - this.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * How far along this viewer's look axis a point lies, signed.
     *
     * <p>Positive is in front, negative is behind, and the magnitude is what the angular test compares.
     * The sign is <em>not</em> used to decide visibility — see
     * {@link ViewSettings#isWithinViewCone(double, double, double, boolean)} for why both directions
     * count.
     */
    public double axisProjectionTo(double pointX, double pointY, double pointZ) {
        return (pointX - this.x) * this.lookX
                + (pointY - this.eyeY) * this.lookY
                + (pointZ - this.z) * this.lookZ;
    }
}
