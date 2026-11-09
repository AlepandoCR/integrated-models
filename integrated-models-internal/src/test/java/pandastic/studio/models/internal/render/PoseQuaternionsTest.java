package pandastic.studio.models.internal.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.pose.Pose;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Euler order. Two orders agree whenever only one axis is non-zero — which is exactly the case
 * anybody checks by hand — so the only test that means anything is one with all three axes set.
 */
class PoseQuaternionsTest {

    private static final float TOLERANCE = 1.0E-6F;

    @Test
    void aZeroTripleIsTheIdentityRotation() {
        final Quaternionf rotation = PoseQuaternions.fromEuler(0F, 0F, 0F, new Quaternionf());
        assertTrue(rotation.equals(new Quaternionf(), TOLERANCE), "got " + rotation);
    }

    @Test
    void composesZThenYThenXLikeTheRenderer() {
        // The renderer's MathUtil#toQuaternion is identity().rotateZYX(roll, yaw, pitch), which is the
        // product Rz · Ry · Rx. This is the whole contract of the class.
        final float pitch = 0.37F;
        final float yaw = -0.82F;
        final float roll = 1.15F;

        final Quaternionf expected = new Quaternionf().rotateZ(roll).rotateY(yaw).rotateX(pitch);
        final Quaternionf actual = PoseQuaternions.fromEuler(pitch, yaw, roll, new Quaternionf());

        assertTrue(actual.equals(expected, TOLERANCE), expected + " vs " + actual);
    }

    @Test
    void disagreesWithTheOppositeEulerOrderOnACombinedRotation() {
        final float pitch = 0.5F;
        final float yaw = 0.6F;
        final float roll = 0.7F;

        final Quaternionf zyx = PoseQuaternions.fromEuler(pitch, yaw, roll, new Quaternionf());
        final Quaternionf xyz = new Quaternionf().rotateXYZ(pitch, yaw, roll);

        assertFalse(zyx.equals(xyz, 1.0E-3F),
                "if these agreed the order would be untestable, which would make the convention"
                        + " unverifiable: " + zyx + " vs " + xyz);
    }

    @Test
    void roundTripsThroughTheRenderersEulerOrder() {
        final float pitch = 0.3F;
        final float yaw = 0.4F;
        final float roll = 0.5F;

        final Vector3f recovered = PoseQuaternions
                .fromEuler(pitch, yaw, roll, new Quaternionf())
                .getEulerAnglesZYX(new Vector3f());

        assertAll(
                () -> assertEquals(pitch, recovered.x, 1.0E-5F),
                () -> assertEquals(yaw, recovered.y, 1.0E-5F),
                () -> assertEquals(roll, recovered.z, 1.0E-5F));
    }

    @Test
    void aSingleAxisTripleIsThatBareAxisRotation() {
        assertAll(
                () -> assertTrue(PoseQuaternions.fromEuler(0.9F, 0F, 0F, new Quaternionf())
                        .equals(new Quaternionf().rotateX(0.9F), TOLERANCE), "pitch is about X"),
                () -> assertTrue(PoseQuaternions.fromEuler(0F, 0.9F, 0F, new Quaternionf())
                        .equals(new Quaternionf().rotateY(0.9F), TOLERANCE), "yaw is about Y"),
                () -> assertTrue(PoseQuaternions.fromEuler(0F, 0F, 0.9F, new Quaternionf())
                        .equals(new Quaternionf().rotateZ(0.9F), TOLERANCE), "roll is about Z"));
    }

    @Test
    void reusesTheDestinationAndDiscardsWhateverWasInIt() {
        // The emission path hands the same instance in every frame; a leftover accumulation would turn
        // a steady pose into an ever-growing rotation.
        final Quaternionf destination = new Quaternionf().rotateY(2.5F);
        final Quaternionf returned = PoseQuaternions.fromEuler(0F, 0F, 0F, destination);

        assertAll(
                () -> assertSame(destination, returned, "it must write in place, not allocate"),
                () -> assertTrue(returned.equals(new Quaternionf(), TOLERANCE),
                        "the prior contents must be discarded, got " + returned));
    }

    @Test
    void readsAPoseInRotationAxisOrder() {
        final Pose pose = new Pose(2);
        pose.set(1, 0.11F, 0.22F, 0.33F);

        final Quaternionf fromPose = PoseQuaternions.fromPose(pose, 1, new Quaternionf());
        final Quaternionf fromTriple = PoseQuaternions.fromEuler(0.11F, 0.22F, 0.33F, new Quaternionf());

        assertTrue(fromPose.equals(fromTriple, TOLERANCE),
                "the pose's slot layout and this conversion must agree on which float is which axis");
    }
}
