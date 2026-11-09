package pandastic.studio.models.api.locomotion;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every assertion here is about a failure that is silent at runtime. A bone index one past the end of
 * the rig, two roles on one bone, a shin with no thigh: none of them throw where they are configured,
 * all of them produce a model that looks subtly wrong on a server. Validating at build time is the only
 * place the mistake is still attributable.
 */
class LocomotionBindingTest {

    private static final float THIGH = 0.6F;
    private static final float SHIN = 0.6F;

    /** Deliberately unlike the removed {@code 1.0E-3}/{@code 16} segment constants. */
    private static final BindingBounds BOUNDS = GaitFixtures.bounds();

    private static final RigBounds RIG_BOUNDS = new RigBounds(32);
    private static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    /**
     * A humanoid with a decorative bone the gait has no business touching.
     *
     * <p>The four extremities are declared <em>after</em> the cape rather than beside their limbs, so
     * that the bone indices the assertions below pin stayed where they were when feet and hands arrived.
     * Nothing in a binding cares about the declaration order beyond a parent preceding its children.
     */
    private static Rig humanoid() {
        return Rig.builder("humanoid", "model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("root")
                .bone("torso", "root", null)
                .bone("head", "torso", null)
                .bone("left_upper_arm", "torso", null)
                .bone("left_forearm", "left_upper_arm", null)
                .bone("right_upper_arm", "torso", null)
                .bone("right_forearm", "right_upper_arm", null)
                .bone("left_thigh", "root", null)
                .bone("left_shin", "left_thigh", null)
                .bone("right_thigh", "root", null)
                .bone("right_shin", "right_thigh", null)
                .bone("cape", "torso", null)
                .bone("left_foot", "left_shin", null)
                .bone("right_foot", "right_shin", null)
                .bone("left_hand", "left_forearm", null)
                .bone("right_hand", "right_forearm", null)
                .build();
    }

    private static LocomotionBinding.Builder fullBuilder(Rig rig) {
        return LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .bone(GaitRole.HEAD, "head")
                .bone(GaitRole.LEFT_UPPER_ARM, "left_upper_arm")
                .bone(GaitRole.LEFT_FOREARM, "left_forearm")
                .bone(GaitRole.RIGHT_UPPER_ARM, "right_upper_arm")
                .bone(GaitRole.RIGHT_FOREARM, "right_forearm")
                .bone(GaitRole.LEFT_THIGH, "left_thigh")
                .bone(GaitRole.LEFT_SHIN, "left_shin")
                .bone(GaitRole.RIGHT_THIGH, "right_thigh")
                .bone(GaitRole.RIGHT_SHIN, "right_shin")
                .legSegments(THIGH, SHIN);
    }

    @Test
    void refusesABoneIndexOutsideTheRig() {
        final Rig rig = humanoid();
        final int past = rig.boneCount();

        final IllegalArgumentException high = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS).boneIndex(GaitRole.TORSO, past));
        final IllegalArgumentException low = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS).boneIndex(GaitRole.TORSO, -1));

        assertAll(
                () -> assertTrue(high.getMessage().contains("0.." + (past - 1)),
                        "it must state the legal range, got: " + high.getMessage()),
                () -> assertTrue(high.getMessage().contains("humanoid"), "and name the rig"),
                () -> assertTrue(high.getMessage().contains("TORSO"), "and the role"),
                () -> assertTrue(low.getMessage().contains("-1")),
                // The sentinel is not a legal index either: binding to UNBOUND would read as bound-to--1
                // everywhere else in the type.
                () -> assertThrows(IllegalArgumentException.class, () -> LocomotionBinding.builder(rig, BOUNDS)
                        .boneIndex(GaitRole.TORSO, LocomotionBinding.UNBOUND)));
    }

    @Test
    void refusesABoneNameTheRigDoesNotHave() {
        final Rig rig = humanoid();
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS).bone(GaitRole.TORSO, "thorax"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("thorax")),
                () -> assertTrue(thrown.getMessage().contains("humanoid")));
    }

    @Test
    void resolvesBoneNamesTheWayTheRigMatchesThem() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "TORSO")
                .build();
        assertEquals(rig.indexOf("torso").orElseThrow(), binding.boneOf(GaitRole.TORSO));
    }

    @Test
    void refusesTheSameRoleBoundTwice() {
        final Rig rig = humanoid();
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.TORSO, "torso")
                        .bone(GaitRole.TORSO, "head"));
        assertTrue(thrown.getMessage().contains("already bound"));
    }

    @Test
    void refusesTwoRolesPointingAtOneBone() {
        // Not a blend: the solver assigns absolute rotations, so one role would simply erase the other
        // and which one wins depends on an ordering the caller cannot see.
        final Rig rig = humanoid();
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.TORSO, "torso")
                        .bone(GaitRole.HEAD, "torso")
                        .build());
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("two gait roles")),
                () -> assertTrue(thrown.getMessage().contains("torso")));
    }

    @Test
    void refusesALowerSegmentWithoutItsUpperSegment() {
        final Rig rig = humanoid();
        final IllegalArgumentException shin = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.LEFT_SHIN, "left_shin")
                        .legSegments(THIGH, SHIN)
                        .build());
        final IllegalArgumentException forearm = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.RIGHT_FOREARM, "right_forearm")
                        .build());

        assertAll(
                () -> assertTrue(shin.getMessage().contains("LEFT_THIGH"),
                        "it must name the missing upper segment, got: " + shin.getMessage()),
                () -> assertTrue(forearm.getMessage().contains("RIGHT_UPPER_ARM")));
    }

    @Test
    void refusesAKneedLegWithNoSegmentLengths() {
        final Rig rig = humanoid();
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.LEFT_THIGH, "left_thigh")
                        .bone(GaitRole.LEFT_SHIN, "left_shin")
                        .build());
        assertTrue(thrown.getMessage().contains("legSegments"),
                "it must say how to fix it, got: " + thrown.getMessage());
    }

    @Test
    void aOnePieceLegNeedsNoSegmentLengths() {
        final Rig rig = humanoid();
        assertDoesNotThrow(() -> LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.LEFT_THIGH, "left_thigh")
                .bone(GaitRole.RIGHT_THIGH, "right_thigh")
                .build());
    }

    @Test
    void aFootMayHangFromAThighWithNoShinAtAll() {
        // The rule that is easy to get wrong in the obvious direction: requiring a foot's shin would
        // reject the perfectly ordinary rig that models a thigh and a boot and nothing between. A foot
        // is independent of the knee, and it needs no segment lengths either, because it adds no length
        // to the reach the inverse kinematics solves against.
        final Rig rig = humanoid();
        final LocomotionBinding binding = assertDoesNotThrow(() -> LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.LEFT_THIGH, "left_thigh")
                .bone(GaitRole.LEFT_FOOT, "left_foot")
                .build());

        assertAll(
                () -> assertTrue(binding.hasLegs()),
                () -> assertFalse(binding.isKneed(GaitRole.LEFT_THIGH),
                        "no shin, so the leg is one piece however many feet it has"),
                () -> assertTrue(binding.isBound(GaitRole.LEFT_FOOT)),
                () -> assertEquals(2, binding.boundRoleCount()));
    }

    @Test
    void refusesAnExtremityWithNoLimbAtAll() {
        // A foot with no leg is a boot floating where a hip should be, and a hand with no arm the same.
        // Both are silent at runtime: the solver simply never writes the bone.
        final Rig rig = humanoid();
        final IllegalArgumentException foot = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.LEFT_FOOT, "left_foot")
                        .build());
        final IllegalArgumentException footWithOnlyAShin = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.RIGHT_SHIN, "right_shin")
                        .bone(GaitRole.RIGHT_FOOT, "right_foot")
                        .legSegments(THIGH, SHIN)
                        .build());
        final IllegalArgumentException hand = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS)
                        .bone(GaitRole.RIGHT_HAND, "right_hand")
                        .build());

        assertAll(
                () -> assertTrue(foot.getMessage().contains("LEFT_THIGH"),
                        "it must name the thigh rather than the shin, got: " + foot.getMessage()),
                () -> assertTrue(footWithOnlyAShin.getMessage().contains("RIGHT_THIGH"),
                        "got: " + footWithOnlyAShin.getMessage()),
                () -> assertTrue(hand.getMessage().contains("LEFT_UPPER_ARM")
                                || hand.getMessage().contains("RIGHT_UPPER_ARM"),
                        "got: " + hand.getMessage()));
    }

    @Test
    void aFullLimbIncludingItsExtremityIsLegal() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = assertDoesNotThrow(() -> fullBuilder(rig)
                .bone(GaitRole.LEFT_FOOT, "left_foot")
                .bone(GaitRole.RIGHT_FOOT, "right_foot")
                .bone(GaitRole.LEFT_HAND, "left_hand")
                .bone(GaitRole.RIGHT_HAND, "right_hand")
                .build());

        assertAll(
                () -> assertEquals(14, binding.boundRoleCount(), "every role in the enum"),
                () -> assertEquals(GaitRole.COUNT, binding.boundRoleCount()),
                () -> assertTrue(binding.isKneed(GaitRole.LEFT_THIGH)),
                () -> assertTrue(binding.ownsBone(binding.boneOf(GaitRole.RIGHT_HAND))));
    }

    @Test
    void theAuthoredFacingRotatesADriverIntoTheRigsOwnFrame() {
        // The correction has to be a rotation of the input pair rather than a sign applied to the
        // output, which is what these two properties pin: the magnitude survives, and a half turn is
        // exactly travelling the other way.
        final Rig rig = humanoid();
        final LocomotionBinding ahead = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .build();
        final LocomotionBinding reversed = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .facing(180F)
                .build();
        final LocomotionBinding sideways = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .facing(90F)
                .build();

        final PhysicsDriver forward = new PhysicsDriver(0.2F, 0F, 0F, 0F);
        final PhysicsDriver backward = new PhysicsDriver(-0.2F, 0F, 0F, 0F);

        assertAll(
                () -> assertEquals(0F, ahead.facingDegrees(),
                        "the unstated facing is the identity rotation"),
                () -> assertEquals(0.2F, ahead.forwardSpeedInRigFrame(forward), 1.0E-6F),
                () -> assertEquals(0F, ahead.sideSpeedInRigFrame(forward), 1.0E-6F),
                // Half a turn: walking one way through a reversed rig is walking the other way through
                // an unreversed one, which is the whole claim the facing offset makes.
                () -> assertEquals(ahead.forwardSpeedInRigFrame(backward),
                        reversed.forwardSpeedInRigFrame(forward), 1.0E-6F),
                () -> assertEquals(-0.2F, reversed.forwardSpeedInRigFrame(forward), 1.0E-6F),
                // A quarter turn clockwise puts the owner's forward along the rig's left.
                () -> assertEquals(0F, sideways.forwardSpeedInRigFrame(forward), 1.0E-6F),
                () -> assertEquals(0.2F, sideways.sideSpeedInRigFrame(forward), 1.0E-6F),
                // A rotation preserves length, so no facing can change how fast a model walks.
                () -> assertEquals(0.2F, magnitudeIn(sideways, forward), 1.0E-6F),
                () -> assertEquals(0.2F, magnitudeIn(reversed, forward), 1.0E-6F),
                () -> assertEquals(90F, sideways.facingDegrees()),
                () -> assertTrue(sideways.toString().contains("facing=90")));
    }

    @Test
    void aFacingWrapsRatherThanBeingRangeChecked() {
        // A bearing is periodic, so every finite value names a direction and 450 is just 90 written by
        // somebody adding turns. What is refused is a value that names no direction at all.
        final Rig rig = humanoid();
        final PhysicsDriver forward = new PhysicsDriver(0.2F, 0F, 0F, 0F);

        assertAll(
                () -> assertEquals(
                        facedBinding(rig, 90F).forwardSpeedInRigFrame(forward),
                        facedBinding(rig, 450F).forwardSpeedInRigFrame(forward), 1.0E-5F),
                () -> assertEquals(
                        facedBinding(rig, -90F).sideSpeedInRigFrame(forward),
                        facedBinding(rig, 270F).sideSpeedInRigFrame(forward), 1.0E-5F),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).facing(Float.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).facing(Float.POSITIVE_INFINITY)));
    }

    private static LocomotionBinding facedBinding(Rig rig, float degrees) {
        return LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .facing(degrees)
                .build();
    }

    private static float magnitudeIn(LocomotionBinding binding, PhysicsDriver driver) {
        final float forward = binding.forwardSpeedInRigFrame(driver);
        final float side = binding.sideSpeedInRigFrame(driver);
        return (float) Math.sqrt(forward * forward + side * side);
    }

    @Test
    void refusesSegmentLengthsOutsideTheConfiguredRange() {
        final Rig rig = humanoid();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).legSegments(0F, SHIN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).legSegments(THIGH, -1F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS)
                                .legSegments(GaitFixtures.MAX_SEGMENT + 1F, SHIN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).legSegments(Float.NaN, SHIN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS)
                                .legSegments(Float.POSITIVE_INFINITY, SHIN)),
                () -> assertDoesNotThrow(() -> LocomotionBinding.builder(rig, BOUNDS)
                        .legSegments(GaitFixtures.MIN_SEGMENT, GaitFixtures.MAX_SEGMENT)));
    }

    @Test
    void segmentRangeComesFromTheSuppliedBoundsAndNothingElse() {
        // The property that catches a leftover fallback: these bounds disagree with the constants this
        // type used to carry in both directions, so honouring either of the old values fails here.
        final Rig rig = humanoid();
        assertAll(
                // 1.0E-3 was the old floor and is below this configuration's.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).legSegments(1.0E-3F, SHIN)),
                // 16 was the old ceiling and is above this configuration's.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).legSegments(16F, SHIN)),
                // And a second, differently shaped configuration is honoured just as literally.
                () -> assertDoesNotThrow(() -> LocomotionBinding
                        .builder(rig, new BindingBounds(4F, 9F, 0.5F, 1F, 1F))
                        .legSegments(4F, 9F)),
                () -> assertThrows(IllegalArgumentException.class, () -> LocomotionBinding
                        .builder(rig, new BindingBounds(4F, 9F, 0.5F, 1F, 1F))
                        .legSegments(THIGH, SHIN)));
    }

    @Test
    void refusesABindingThatDrivesNothing() {
        final Rig rig = humanoid();
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LocomotionBinding.builder(rig, BOUNDS).build());
        assertTrue(thrown.getMessage().contains("no bones at all"));
    }

    @Test
    void refusesABendSignThatIsNotAPureDirection() {
        final Rig rig = humanoid();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).kneeBendSign(0F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).kneeBendSign(0.5F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> LocomotionBinding.builder(rig, BOUNDS).kneeBendSign(Float.NaN)),
                () -> assertDoesNotThrow(() -> LocomotionBinding.builder(rig, BOUNDS).kneeBendSign(1F)),
                () -> assertDoesNotThrow(() -> LocomotionBinding.builder(rig, BOUNDS).kneeBendSign(-1F)));
    }

    @Test
    void aBindingWithNoLegsIsLegalBecauseADecorationStillWantsIdleMotion() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso")
                .build();

        assertAll(
                () -> assertFalse(binding.hasLegs()),
                () -> assertEquals(1, binding.boundRoleCount()),
                () -> assertTrue(binding.isBound(GaitRole.TORSO)),
                () -> assertFalse(binding.isBound(GaitRole.HEAD)),
                () -> assertEquals(LocomotionBinding.UNBOUND, binding.boneOf(GaitRole.LEFT_THIGH)),
                () -> assertEquals(OptionalInt.empty(), binding.findBone(GaitRole.LEFT_THIGH)));
    }

    @Test
    void reportsItsOwnedBonesAscendingAndDistinct() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = fullBuilder(rig).build();

        final int cape = rig.indexOf("cape").orElseThrow();
        final int root = rig.indexOf("root").orElseThrow();
        final int[] owned = binding.ownedBoneIndices();

        assertAll(
                () -> assertEquals(10, owned.length),
                () -> assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, owned,
                        "owned bones must come back sorted"),
                () -> assertFalse(binding.ownsBone(cape), "the cape belongs to physics, not to the gait"),
                () -> assertFalse(binding.ownsBone(root)),
                () -> assertTrue(binding.ownsBone(binding.boneOf(GaitRole.LEFT_SHIN))),
                // A fresh array every call: a caller holding onto it must not be able to corrupt us.
                () -> assertArrayEquals(owned, binding.ownedBoneIndices()));
    }

    @Test
    void ownedBoneArraysAreCopies() {
        final LocomotionBinding binding = fullBuilder(humanoid()).build();
        final int[] stolen = binding.ownedBoneIndices();
        stolen[0] = 999;
        assertEquals(1, binding.ownedBoneIndices()[0]);
    }

    @Test
    void reportsWhichLegsAreKneed() {
        final Rig rig = humanoid();
        final LocomotionBinding mixed = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.LEFT_THIGH, "left_thigh")
                .bone(GaitRole.LEFT_SHIN, "left_shin")
                .bone(GaitRole.RIGHT_THIGH, "right_thigh")
                .legSegments(THIGH, SHIN)
                .build();

        assertAll(
                () -> assertTrue(mixed.hasLegs()),
                () -> assertTrue(mixed.isKneed(GaitRole.LEFT_THIGH)),
                () -> assertFalse(mixed.isKneed(GaitRole.RIGHT_THIGH)),
                () -> assertThrows(IllegalArgumentException.class, () -> mixed.isKneed(GaitRole.HEAD)));
    }

    @Test
    void anElbowFoldsOppositeToAKnee() {
        final Rig rig = humanoid();
        final LocomotionBinding right = fullBuilder(rig).build();
        final LocomotionBinding left = fullBuilder(rig).kneeBendSign(-1F).build();

        assertAll(
                () -> assertEquals(1F, right.kneeBendSign()),
                () -> assertEquals(-1F, right.elbowBendSign()),
                () -> assertEquals(-1F, left.kneeBendSign()),
                () -> assertEquals(1F, left.elbowBendSign()));
    }

    @Test
    void carriesTheGeometryTheIkNeedsAndTheRigFactsTheSolverChecks() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = fullBuilder(rig).build();

        assertAll(
                () -> assertEquals(THIGH, binding.thighLength()),
                () -> assertEquals(SHIN, binding.shinLength()),
                () -> assertEquals(THIGH + SHIN, binding.legReach(), 1.0E-6F),
                () -> assertEquals(rig.boneCount(), binding.boneCount()),
                () -> assertEquals("humanoid", binding.getRigName()),
                () -> assertTrue(binding.toString().contains("humanoid")));
    }

    @Test
    void bindingByIndexAndByNameAgree() {
        final Rig rig = humanoid();
        final int torso = rig.indexOf("torso").orElseThrow();

        final LocomotionBinding named = LocomotionBinding.builder(rig, BOUNDS)
                .bone(GaitRole.TORSO, "torso").build();
        final LocomotionBinding indexed = LocomotionBinding.builder(rig, BOUNDS)
                .boneIndex(GaitRole.TORSO, torso).build();

        assertAll(
                () -> assertEquals(torso, named.boneOf(GaitRole.TORSO)),
                () -> assertEquals(named.boneOf(GaitRole.TORSO), indexed.boneOf(GaitRole.TORSO)),
                () -> assertEquals(OptionalInt.of(torso), named.findBone(GaitRole.TORSO)));
    }

    @Test
    void everyLowerSegmentKnowsItsUpperSegment() {
        // The orphan check is driven off this mapping, so an unmapped lower segment would be accepted
        // silently. Pinning it means adding a limb pair cannot forget the rule.
        assertAll(
                () -> assertEquals(GaitRole.LEFT_THIGH, GaitRole.LEFT_SHIN.upperSegment()),
                () -> assertEquals(GaitRole.RIGHT_THIGH, GaitRole.RIGHT_SHIN.upperSegment()),
                () -> assertEquals(GaitRole.LEFT_UPPER_ARM, GaitRole.LEFT_FOREARM.upperSegment()),
                () -> assertEquals(GaitRole.RIGHT_UPPER_ARM, GaitRole.RIGHT_FOREARM.upperSegment()),
                // An extremity reports the limb's upper segment, not the one it hangs from. That is the
                // difference between "a foot needs a leg" and "a foot needs a knee".
                () -> assertEquals(GaitRole.LEFT_THIGH, GaitRole.LEFT_FOOT.upperSegment()),
                () -> assertEquals(GaitRole.RIGHT_THIGH, GaitRole.RIGHT_FOOT.upperSegment()),
                () -> assertEquals(GaitRole.LEFT_UPPER_ARM, GaitRole.LEFT_HAND.upperSegment()),
                () -> assertEquals(GaitRole.RIGHT_UPPER_ARM, GaitRole.RIGHT_HAND.upperSegment()),
                () -> assertNullUpper(GaitRole.TORSO),
                () -> assertNullUpper(GaitRole.HEAD),
                () -> assertNullUpper(GaitRole.LEFT_THIGH),
                () -> assertNullUpper(GaitRole.RIGHT_UPPER_ARM),
                () -> assertEquals(GaitRole.values().length, GaitRole.COUNT),
                () -> assertEquals(GaitRole.TORSO, GaitRole.at(GaitRole.TORSO.ordinal())),
                () -> assertEquals(GaitRole.RIGHT_HAND, GaitRole.at(GaitRole.RIGHT_HAND.ordinal())));
    }

    @Test
    void anExtremityKnowsTheSegmentItHangsFromWhenThereIsOne() {
        // The solver cancels the whole chain above a foot, so it needs to know that the shin is in that
        // chain — and that nothing else ever is.
        assertAll(
                () -> assertEquals(GaitRole.LEFT_SHIN, GaitRole.LEFT_FOOT.intermediateSegment()),
                () -> assertEquals(GaitRole.RIGHT_SHIN, GaitRole.RIGHT_FOOT.intermediateSegment()),
                () -> assertEquals(GaitRole.LEFT_FOREARM, GaitRole.LEFT_HAND.intermediateSegment()),
                () -> assertEquals(GaitRole.RIGHT_FOREARM, GaitRole.RIGHT_HAND.intermediateSegment()),
                () -> assertNull(GaitRole.LEFT_SHIN.intermediateSegment(),
                        "a middle segment is not an extremity"),
                () -> assertNull(GaitRole.LEFT_THIGH.intermediateSegment()),
                () -> assertNull(GaitRole.TORSO.intermediateSegment()),
                // And the two predicates the binding's rules are written in terms of.
                () -> assertTrue(GaitRole.LEFT_FOOT.isExtremity()),
                () -> assertTrue(GaitRole.RIGHT_HAND.isExtremity()),
                () -> assertFalse(GaitRole.LEFT_SHIN.isExtremity()),
                () -> assertFalse(GaitRole.TORSO.isExtremity()),
                // A foot is deliberately not a leg segment: it adds no length to the IK's reach, which
                // is exactly why a boot on a one-piece leg needs no segment lengths.
                () -> assertTrue(GaitRole.LEFT_THIGH.isLegSegment()),
                () -> assertTrue(GaitRole.RIGHT_SHIN.isLegSegment()),
                () -> assertFalse(GaitRole.LEFT_FOOT.isLegSegment()),
                () -> assertFalse(GaitRole.LEFT_HAND.isLegSegment()));
    }

    private static void assertNullUpper(GaitRole role) {
        assertTrue(role.upperSegment() == null, role + " is not a lower segment");
    }
}
