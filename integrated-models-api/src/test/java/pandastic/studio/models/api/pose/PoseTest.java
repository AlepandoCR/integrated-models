package pandastic.studio.models.api.pose;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.RotationAxis;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseTest {

    @Test
    void startsAtRestPose() {
        final Pose pose = new Pose(4);
        assertAll(
                () -> assertEquals(4, pose.boneCount()),
                () -> assertEquals(0F, pose.pitch(0)),
                () -> assertEquals(0F, pose.yaw(3)),
                () -> assertEquals(0F, pose.roll(2)),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void keepsAxesIndependentAcrossBones() {
        final Pose pose = new Pose(2);
        pose.set(0, 0.1F, 0.2F, 0.3F);
        pose.set(1, -0.4F, -0.5F, -0.6F);

        assertAll(
                () -> assertEquals(0.1F, pose.pitch(0)),
                () -> assertEquals(0.2F, pose.yaw(0)),
                () -> assertEquals(0.3F, pose.roll(0)),
                () -> assertEquals(-0.4F, pose.pitch(1)),
                () -> assertEquals(-0.5F, pose.yaw(1)),
                () -> assertEquals(-0.6F, pose.roll(1)));
    }

    @Test
    void axisAccessorsAgreeWithTheNamedOnes() {
        final Pose pose = new Pose(1);
        pose.set(0, RotationAxis.PITCH, 1F);
        pose.set(0, RotationAxis.YAW, 2F);
        pose.set(0, RotationAxis.ROLL, 3F);

        assertAll(
                () -> assertEquals(pose.pitch(0), pose.get(0, RotationAxis.PITCH)),
                () -> assertEquals(pose.yaw(0), pose.get(0, RotationAxis.YAW)),
                () -> assertEquals(pose.roll(0), pose.get(0, RotationAxis.ROLL)));
    }

    @Test
    void addsRatherThanReplaces() {
        final Pose pose = new Pose(1);
        pose.set(0, 1F, 1F, 1F);
        pose.add(0, 0.5F, -0.5F, 0F);
        pose.add(0, RotationAxis.ROLL, 2F);

        assertAll(
                () -> assertEquals(1.5F, pose.pitch(0)),
                () -> assertEquals(0.5F, pose.yaw(0)),
                () -> assertEquals(3F, pose.roll(0)));
    }

    @Test
    void resetReturnsEveryBoneToZero() {
        final Pose pose = new Pose(3);
        pose.set(1, 1F, 2F, 3F);
        pose.reset();
        for (int bone = 0; bone < pose.boneCount(); bone++) {
            assertEquals(0F, pose.pitch(bone));
            assertEquals(0F, pose.yaw(bone));
            assertEquals(0F, pose.roll(bone));
        }
    }

    @Test
    void copiesFromAPoseOfTheSameWidth() {
        final Pose source = new Pose(2);
        source.set(0, 1F, 2F, 3F);
        source.set(1, 4F, 5F, 6F);

        final Pose target = new Pose(2);
        target.copyFrom(source);

        assertAll(
                () -> assertEquals(1F, target.pitch(0)),
                () -> assertEquals(6F, target.roll(1)));

        // A copy is a snapshot, not an alias.
        source.set(0, 9F, 9F, 9F);
        assertEquals(1F, target.pitch(0));
    }

    @Test
    void refusesACopyOfADifferentWidth() {
        final Pose narrow = new Pose(1);
        final Pose wide = new Pose(2);
        assertThrows(IllegalArgumentException.class, () -> narrow.copyFrom(wide));
    }

    @Test
    void refusesAnEmptyPose() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Pose(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Pose(-1)));
    }

    @Test
    void boundsCheckesEveryAccess() {
        final Pose pose = new Pose(2);
        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class, () -> pose.pitch(2)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> pose.yaw(-1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> pose.set(5, 0F, 0F, 0F)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> pose.add(9, 0F, 0F, 0F)));
    }

    @Test
    void detectsADivergedRotation() {
        final Pose pose = new Pose(2);
        assertTrue(pose.isFinite());
        pose.set(1, RotationAxis.YAW, Float.NaN);
        assertFalse(pose.isFinite());

        pose.reset();
        pose.set(0, RotationAxis.PITCH, Float.POSITIVE_INFINITY);
        assertFalse(pose.isFinite());
    }
}
