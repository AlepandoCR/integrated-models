package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Turns "where a clip has moved a bone to" into "how far it moved it from rest" — exactly, rather than by
 * subtracting Euler triples.
 *
 * <h2>Why a subtraction is not good enough</h2>
 * A renderer stores an animated bone's local orientation as one Euler triple that is the <em>sum</em> of
 * the authored rest triple and the clip's keyframe, and builds a single quaternion from the sum:
 * {@code Q(rest + clip)}. {@link RigBindPose}, by contrast, keeps the rest orientation and the overlay
 * apart, and composes {@code Q(rest) · Q(delta)}. Those two expressions are only equal when one of the
 * two rotations is trivial or both turn about the same axis, because Euler addition is not quaternion
 * composition.
 *
 * <p>The error is not academic. For a bone authored at {@code (12°, −30°, 5°)} carrying a 20° yaw
 * keyframe, {@code Q(rest) · Q(clip)} sits 4.2° away from {@code Q(rest + clip)} — enough, on a limb, to
 * read as the clip playing slightly wrong rather than as a rounding difference. So the delta this class
 * produces is the one that makes the two sides agree by construction:
 *
 * <pre>
 *   delta = Q(rest)⁻¹ · Q(live)
 * </pre>
 *
 * <p>decomposed back to the {@code (pitch, yaw, roll)} triple {@link AnimatedRotations} carries and
 * {@link PoseQuaternions} rebuilds. The round trip is exact: JOML's {@code getEulerAnglesZYX} inverts
 * {@code rotateZYX(roll, yaw, pitch)}, which is the convention {@link PoseQuaternions} documents and the
 * one the renderer's own conversion uses.
 *
 * <h2>{@link #isRest} is load-bearing, not an optimisation</h2>
 * It is tempting to assume the arithmetic handles the no-clip case on its own: {@code Q(rest)⁻¹ · Q(rest)}
 * is the identity, and the decomposition of the identity is exactly {@code (0, −0, 0)} — which
 * {@link AnimatedRotations#set} would record as "no delta", because {@code -0F != 0F} is false in Java.
 *
 * <p><strong>It does not.</strong> {@code Quaternionf#invert} divides by the squared length, and a unit
 * quaternion's squared length is only <em>nearly</em> one in {@code float}, so the product is only nearly
 * the identity. Measured over 200,000 random authored rotations the decomposition comes back as much as
 * {@code 1.5e-7} rad from zero — invisible as motion, and quite enough for {@code set} to record a delta,
 * which would make {@code RigBindPose} multiply in a near-identity rotation for a bone no clip is
 * touching. The bone would land a float away from where it used to, on every rig that declares an
 * animation-owned bone, with nothing to point at.
 *
 * <p>So the exact equality test comes <em>first</em> and the arithmetic is never reached for a bone at
 * its authored orientation. That is also the common case by a wide margin: a renderer <em>copies</em> the
 * default frame for a bone no keyframe touches, so the two are the same floats rather than merely close.
 * With it, {@link RigBindPose#compose(pandastic.studio.models.api.pose.Pose, AnimatedRotations)} evaluates
 * the same expression as the single-argument overload, bit for bit.
 *
 * <h2>Degrees in, radians out</h2>
 * Deliberately asymmetric, because the two sides of the seam are. Renderer bone rotations are authored
 * and stored in degrees; a {@link pandastic.studio.models.api.pose.Pose} and everything built on it are
 * radians. Converting here is what keeps the unit boundary in one place instead of at every call site.
 *
 * <h2>Nothing here is tunable, and nothing here names a renderer</h2>
 * Every number below is either a unit conversion or comes from the model file. There is no coefficient,
 * bound or epsilon to configure: this reproduces a composition the renderer owns, and a key that let an
 * operator change it would only let them disagree with the packets the renderer is sending.
 *
 * <p>It takes two {@code Vector3f}s rather than a bone, which is the whole reason it is on this side of
 * the backend seam: the arithmetic is the part worth testing and the lookup is the part that needs a
 * server.
 */
public final class AnimationDelta {

    /**
     * Radians per degree.
     *
     * <p><strong>A unit conversion, not a tunable.</strong> It is the SI relationship between the unit a
     * renderer authors bone rotations in and the unit a pose is expressed in.
     */
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

    private AnimationDelta() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether {@code liveDegrees} is the bone's authored orientation, i.e. whether no clip has moved it.
     *
     * <p><strong>Call this before {@link #of}, always.</strong> See the class note: the arithmetic is not
     * bit-exact at the identity, so this test is what makes the no-clip case free of behaviour change
     * rather than merely close to it.
     *
     * <p>Exact equality rather than an epsilon, and that is a structural fact about renderers rather than
     * a tolerance worth configuring: a bone with no keyframe has its animated frame copied from its
     * authored frame, so the two are the same floats. An epsilon here would be a tunable with no value an
     * operator could want other than zero.
     */
    public static boolean isRest(@NotNull Vector3f restDegrees, @NotNull Vector3f liveDegrees) {
        return restDegrees.x == liveDegrees.x
                && restDegrees.y == liveDegrees.y
                && restDegrees.z == liveDegrees.z;
    }

    /**
     * {@code Q(rest)⁻¹ · Q(live)} as a {@code (pitch, yaw, roll)} triple in radians, written into
     * {@code dest}.
     *
     * @param restInverse {@code Q(rest)⁻¹}, precomputed by {@link #restInverse} because a bone's authored
     *                    orientation never changes
     * @param liveDegrees the bone's current local orientation, in degrees, in the renderer's ZYX
     *                    convention
     * @param live        scratch for the live orientation as a quaternion, overwritten
     * @param delta       scratch for the composed delta, overwritten
     * @param dest        the triple to overwrite; the returned reference is {@code dest} itself
     */
    public static @NotNull Vector3f of(
            @NotNull Quaternionf restInverse,
            @NotNull Vector3f liveDegrees,
            @NotNull Quaternionf live,
            @NotNull Quaternionf delta,
            @NotNull Vector3f dest
    ) {
        toQuaternion(liveDegrees, live);
        restInverse.mul(live, delta);
        return delta.getEulerAnglesZYX(dest);
    }

    /**
     * {@code Q(degrees)⁻¹}, for a caller holding a bone's authored rest orientation.
     *
     * <p>Allocation-free in the sense that matters: it writes into {@code dest}, which a binding allocates
     * once per animated bone and then keeps for the binding's whole life.
     */
    public static @NotNull Quaternionf restInverse(
            @NotNull Vector3f degrees,
            @NotNull Quaternionf dest
    ) {
        return toQuaternion(degrees, dest).invert();
    }

    /**
     * {@code degrees} as a quaternion, in the renderer's convention.
     *
     * <p>{@code rotateZYX(z, y, x)} over a triple laid out {@code (x = pitch, y = yaw, z = roll)}, which
     * is what {@link PoseQuaternions#fromEuler} rebuilds with and what BetterModel's own
     * {@code MathUtil#toQuaternion} performs. Swapping the first and last argument is the single most
     * likely way to break this file, and it is invisible until a bone pitches and rolls at once.
     */
    public static @NotNull Quaternionf toQuaternion(
            @NotNull Vector3f degrees,
            @NotNull Quaternionf dest
    ) {
        return PoseQuaternions.fromEuler(
                degrees.x * DEGREES_TO_RADIANS,
                degrees.y * DEGREES_TO_RADIANS,
                degrees.z * DEGREES_TO_RADIANS,
                dest);
    }
}
