package pandastic.studio.models.internal.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry half of the binding, tested without a renderer — which is the entire reason
 * {@link ModelBone} exists as a plain record rather than as a {@code RendererGroup}.
 */
class RigBindPoseTest {

    private static final float TOLERANCE = 1.0E-5F;
    private static final float QUARTER_TURN = (float) (Math.PI / 2D);

    private static ModelBone bone(String name, int parent, Vector3f position, Quaternionf rotation) {
        return new ModelBone(name, parent, position, rotation, new Vector3f(1F));
    }

    /** body at the origin, hair hanging four blocks below it, both unrotated. */
    private static List<ModelBone> doll() {
        return List.of(
                bone("body", ModelBone.NO_PARENT, new Vector3f(), new Quaternionf()),
                bone("hair", 0, new Vector3f(0F, -4F, 0F), new Quaternionf()));
    }

    private static Rig dollRig() {
        return RenderFixtures.rig("doll", "model").root("body").bone("hair", "body", null).build();
    }

    private static Vector3f positionOf(RigBindPose bindPose, int bone) {
        return bindPose.position(bone, new Vector3f());
    }

    private static Quaternionf rotationOf(RigBindPose bindPose, int bone) {
        return bindPose.rotation(bone, new Quaternionf());
    }

    @Test
    void aZeroPoseReproducesTheRestTransformExactly() {
        // Not approximately: binding a rig that nothing is driving must not nudge the model by a float.
        final List<ModelBone> model = List.of(
                bone("body", ModelBone.NO_PARENT, new Vector3f(1F, 2F, 3F),
                        new Quaternionf().rotateZ(0.3F)),
                bone("hair", 0, new Vector3f(1F, -2F, 3F), new Quaternionf().rotateY(0.5F)));
        final RigBindPose bindPose = RigBindPose.of(dollRig(), model, 1F, RenderFixtures.SETTINGS);

        bindPose.compose(new Pose(2));

        assertAll(
                () -> assertEquals(new Vector3f(1F, 2F, 3F), positionOf(bindPose, 0)),
                () -> assertEquals(new Vector3f(1F, -2F, 3F), positionOf(bindPose, 1)),
                () -> assertTrue(rotationOf(bindPose, 0).equals(new Quaternionf().rotateZ(0.3F), TOLERANCE)),
                () -> assertTrue(rotationOf(bindPose, 1).equals(new Quaternionf().rotateY(0.5F), TOLERANCE)));
    }

    @Test
    void reportsTheRestTransformBeforeAnythingIsComposed() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);

        assertAll(
                () -> assertEquals(new Vector3f(0F, -4F, 0F), positionOf(bindPose, 1),
                        "reading before the first compose must report where the model already is"),
                () -> assertTrue(rotationOf(bindPose, 1).equals(new Quaternionf(), TOLERANCE)));
    }

    @Test
    void aBonesOwnRotationDoesNotMoveItsOwnPivot() {
        // Pose's rotation-only contract: a bone that leaves its pivot detaches from its parent in the
        // renderer, which reads as a broken model rather than as physics.
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        pose.set(1, 0.4F, 0.5F, 0.6F);

        bindPose.compose(pose);

        assertEquals(new Vector3f(0F, -4F, 0F), positionOf(bindPose, 1));
    }

    @Test
    void rotatingAParentSwingsItsChildAroundTheParentsPivot() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        // A quarter turn of roll about Z takes (0, -4, 0) to (4, 0, 0).
        pose.set(0, 0F, 0F, QUARTER_TURN);

        bindPose.compose(pose);
        final Vector3f hair = positionOf(bindPose, 1);

        assertAll(
                () -> assertEquals(new Vector3f(), positionOf(bindPose, 0), "the parent's pivot is fixed"),
                () -> assertTrue(hair.equals(new Vector3f(4F, 0F, 0F), TOLERANCE), "got " + hair),
                () -> assertEquals(4F, hair.length(), TOLERANCE,
                        "the segment is rigid: a child may swing but may never stretch"));
    }

    @Test
    void aChildWithNoDeltaOfItsOwnInheritsItsParentsDeviation() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        pose.set(0, 0F, 0F, QUARTER_TURN);

        bindPose.compose(pose);

        assertTrue(rotationOf(bindPose, 1).equals(new Quaternionf().rotateZ(QUARTER_TURN), TOLERANCE),
                "an undriven child must turn with its parent, got " + rotationOf(bindPose, 1));
    }

    @Test
    void appliesTheDeltaInTheBonesOwnFrame() {
        // The solver already folds every ancestor's pose angle into its driver term, so its output is an
        // offset from the bone's own rest orientation — a local rotation, right-multiplied.
        final List<ModelBone> model = List.of(
                bone("body", ModelBone.NO_PARENT, new Vector3f(),
                        new Quaternionf().rotateY(QUARTER_TURN)));
        final Rig rig = RenderFixtures.rig("single", "model").root("body").build();
        final RigBindPose bindPose = RigBindPose.of(rig, model, 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(1);
        pose.set(0, 0.3F, 0F, 0F);

        bindPose.compose(pose);

        final Quaternionf local = new Quaternionf().rotateY(QUARTER_TURN).rotateX(0.3F);
        final Quaternionf global = new Quaternionf().rotateX(0.3F).rotateY(QUARTER_TURN);
        assertAll(
                () -> assertTrue(rotationOf(bindPose, 0).equals(local, TOLERANCE),
                        "expected rest · delta, got " + rotationOf(bindPose, 0)),
                () -> assertTrue(!rotationOf(bindPose, 0).equals(global, 1.0E-3F),
                        "and not delta · rest, or the two orders would be indistinguishable here"));
    }

    @Test
    void carriesTheGapBetweenTwoRigBonesRigidly() {
        // The model nests tip under mid under body; the rig names only body and tip. The intermediate
        // bone is not driven, so preserving the authored geometry across the gap is correct — the tip
        // has to swing through its full offset from the body, not from the bone the rig skipped.
        final List<ModelBone> model = List.of(
                bone("body", ModelBone.NO_PARENT, new Vector3f(), new Quaternionf()),
                bone("mid", 0, new Vector3f(0F, -2F, 0F), new Quaternionf()),
                bone("tip", 1, new Vector3f(0F, -5F, 0F), new Quaternionf()));
        final Rig rig = RenderFixtures.rig("skip", "model").root("body").bone("tip", "body", null).build();
        final RigBindPose bindPose = RigBindPose.of(rig, model, 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        pose.set(0, 0F, 0F, QUARTER_TURN);

        bindPose.compose(pose);
        final Vector3f tip = positionOf(bindPose, 1);

        assertAll(
                () -> assertEquals(2, bindPose.modelIndexOf(1), "tip is the third model bone"),
                () -> assertTrue(tip.equals(new Vector3f(5F, 0F, 0F), TOLERANCE), "got " + tip));
    }

    @Test
    void scalesEveryLengthByTheModelScale() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 2F, RenderFixtures.SETTINGS);
        bindPose.compose(new Pose(2));

        assertAll(
                () -> assertEquals(2F, bindPose.getModelScale()),
                () -> assertEquals(new Vector3f(0F, -8F, 0F), positionOf(bindPose, 1),
                        "a scaled model's bones sit further apart"),
                () -> assertEquals(new Vector3f(2F), bindPose.scale(1, new Vector3f())));
    }

    @Test
    void segmentsScaleWithTheModelToo() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 3F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        pose.set(0, 0F, 0F, QUARTER_TURN);

        bindPose.compose(pose);
        final Vector3f hair = positionOf(bindPose, 1);

        assertTrue(hair.equals(new Vector3f(12F, 0F, 0F), TOLERANCE),
                "a swung segment must keep the scaled length, got " + hair);
    }

    @Test
    void refusesARigNamingAbsentBonesAndListsThemAll() {
        final Rig rig = RenderFixtures.rig("doll", "model")
                .root("body")
                .bone("tail", "body", null)
                .bone("tail_tip", "tail", null)
                .build();

        final String message = assertThrows(IllegalStateException.class,
                () -> RigBindPose.of(rig, doll(), 1F, RenderFixtures.SETTINGS)).getMessage();

        assertAll(
                () -> assertTrue(message.contains("tail"), message),
                () -> assertTrue(message.contains("tail_tip"), "both, not just the first: " + message));
    }

    @Test
    void threadsTheDiagnosticLimitThroughToTheBindFailure() {
        // The limit is configuration two layers away from where it is used, so the thing worth pinning
        // is that it arrives: a bind failure must summarise at the operator's figure, not at one this
        // layer kept for itself.
        final Rig rig = RenderFixtures.rig("doll", "model").root("absent").build();

        final String message = assertThrows(IllegalStateException.class,
                () -> RigBindPose.of(rig, doll(), 1F, new RenderSettings(1.0E-4F, 1))).getMessage();

        assertAll(
                () -> assertTrue(message.contains("[body] (+1 more)"),
                        "a limit of 1 names one bone and counts the rest: " + message),
                () -> assertFalse(message.contains("hair"), message));
    }

    @Test
    void refusesAPoseOfTheWrongWidth() {
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);

        assertThrows(IllegalArgumentException.class, () -> bindPose.compose(new Pose(5)));
    }

    @Test
    void refusesAModelBoneListThatIsNotInHierarchyOrder() {
        // The composition resolves a chain in a single pass, so a parent that follows its child would
        // read a one-frame-stale ancestor.
        final List<ModelBone> reversed = List.of(
                bone("hair", 1, new Vector3f(0F, -4F, 0F), new Quaternionf()),
                bone("body", ModelBone.NO_PARENT, new Vector3f(), new Quaternionf()));

        assertThrows(IllegalArgumentException.class,
                () -> RigBindPose.of(dollRig(), reversed, 1F, RenderFixtures.SETTINGS));
    }

    @Test
    void refusesANonPositiveOrNonFiniteModelScale() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RigBindPose.of(dollRig(), doll(), 0F, RenderFixtures.SETTINGS)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RigBindPose.of(dollRig(), doll(), -1F, RenderFixtures.SETTINGS)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RigBindPose.of(dollRig(), doll(), Float.NaN, RenderFixtures.SETTINGS)));
    }

    @Test
    void aModelBoneCopiesTheVectorsItIsGiven() {
        // The renderer hands out its live internal vectors, so a record that aliased them would let a
        // later read of the model mutate a bind pose.
        final Vector3f position = new Vector3f(1F, 2F, 3F);
        final ModelBone modelBone = bone("body", ModelBone.NO_PARENT, position, new Quaternionf());

        position.set(9F, 9F, 9F);

        assertEquals(new Vector3f(1F, 2F, 3F), modelBone.restPosition());
    }

    @Test
    void composingTwiceWithTheSamePoseIsIdempotent() {
        // The deviation quaternions are scratch reused across emissions; one that accumulated instead of
        // being rewritten would turn a steady pose into a bone that spins forever.
        final RigBindPose bindPose = RigBindPose.of(dollRig(), doll(), 1F, RenderFixtures.SETTINGS);
        final Pose pose = new Pose(2);
        pose.set(0, 0.1F, 0.2F, 0.3F);
        pose.set(1, -0.4F, 0.5F, 0.6F);

        bindPose.compose(pose);
        final Vector3f firstPosition = positionOf(bindPose, 1);
        final Quaternionf firstRotation = rotationOf(bindPose, 1);

        for (int round = 0; round < 10; round++) {
            bindPose.compose(pose);
        }

        assertAll(
                () -> assertTrue(positionOf(bindPose, 1).equals(firstPosition, TOLERANCE)),
                () -> assertTrue(rotationOf(bindPose, 1).equals(firstRotation, TOLERANCE)));
    }
}
