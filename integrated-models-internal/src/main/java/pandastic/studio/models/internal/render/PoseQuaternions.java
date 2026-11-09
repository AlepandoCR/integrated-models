package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.RotationAxis;

/**
 * Converts a {@link Pose}'s Euler triple into the quaternion a display entity's transform carries.
 *
 * <h2>The order is ZYX, and it is not a preference</h2>
 * Blockbench authors a bone rotation as three degrees and the renderer turns it into a quaternion in
 * exactly one way. BetterModel 3.6.0's {@code MathUtil.toQuaternion(Vector3f, Quaternionf)} is
 *
 * <pre>
 *   dest.identity().rotateZYX(v.z · DEG2RAD, v.y · DEG2RAD, v.x · DEG2RAD)
 * </pre>
 *
 * over a vector laid out {@code (x = pitch, y = yaw, z = roll)} — the same composition
 * {@code Float3#toQuaternionZYX} performs, and the one every rest rotation and every animation keyframe
 * in the renderer already went through. So the product is {@code Rz(roll) · Ry(yaw) · Rx(pitch)}:
 * pitch is applied first, in the bone's own frame, and roll last.
 *
 * <p>Getting this wrong is not a small error. Two Euler orders agree whenever only one axis is
 * non-zero, which is precisely the case a developer tests by hand; they diverge as soon as a bone pitches
 * <em>and</em> rolls, and the result is a bone that twists the wrong way only in combined motion. That is
 * why the order is pinned by a test against the explicit {@code rotateZ·rotateY·rotateX} product rather
 * than left to a comment.
 *
 * <p>A {@link Pose} is already radians, so unlike the renderer's own conversion there is no degree
 * factor here — {@link RotationAxis} fixes the triple's layout and this class fixes its meaning.
 */
public final class PoseQuaternions {

    private PoseQuaternions() {
    }

    /**
     * Writes the rotation described by one bone's Euler triple into {@code dest}.
     *
     * <p>{@code dest} is reset first, so a caller may hand the same instance in every frame and never
     * allocate. The returned reference is {@code dest} itself.
     *
     * @param pitch rotation about X, in radians
     * @param yaw   rotation about Y, in radians
     * @param roll  rotation about Z, in radians
     * @param dest  the quaternion to overwrite
     */
    public static @NotNull Quaternionf fromEuler(
            float pitch,
            float yaw,
            float roll,
            @NotNull Quaternionf dest
    ) {
        // rotateZYX takes its arguments in the order it applies them to the accumulator — Z, then Y,
        // then X — which is the reverse of the pitch/yaw/roll order a Pose stores them in. Swapping
        // these two arguments is the single most likely way to break this file.
        return dest.identity().rotateZYX(roll, yaw, pitch);
    }

    /** {@link #fromEuler} for bone {@code bone} of {@code pose}. */
    public static @NotNull Quaternionf fromPose(
            @NotNull Pose pose,
            int bone,
            @NotNull Quaternionf dest
    ) {
        return fromEuler(pose.pitch(bone), pose.yaw(bone), pose.roll(bone), dest);
    }
}
