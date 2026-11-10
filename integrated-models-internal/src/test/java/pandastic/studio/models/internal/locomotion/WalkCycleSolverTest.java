package pandastic.studio.models.internal.locomotion;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitRole;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.math.IkTolerance;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.rig.RotationAxis;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lives in the solver's own package so the tests can set {@link WalkCycleState}'s phase and amplitude
 * directly. That is what makes the gait's central property testable: because the pose is <em>evaluated</em>
 * from a phase rather than arrived at by stepping, a test can ask what the model looks like at any point
 * in the stride and compare two points exactly. An integrator could only be sampled where it happened to
 * land.
 *
 * <h2>Nothing here is a default</h2>
 * {@link #CALIBRATED} spells out every figure the shipped calibration carries rather than importing it,
 * because a suite that reads its expectations from the same place the solver reads them cannot tell "the
 * gait honours its configuration" from "the gait ignores its configuration and happens to agree with it".
 * The tests in the <em>injected configuration</em> section make that distinction, each by moving one
 * value to somewhere no default would ever sit.
 */
class WalkCycleSolverTest {

    private static final float TICK = 1F / 20F;

    /**
     * A delta small enough that the blends barely move, so a pose can be evaluated at a chosen phase.
     * Not zero, because a zero delta is a documented no-op.
     */
    private static final float EVAL_DELTA = 1.0E-6F;

    /** Blocks per tick. Vanilla walking pace, i.e. the speed that saturates the stride amplitude. */
    private static final float WALK = 0.215F;

    /** Blocks per tick. Vanilla sprinting pace, i.e. the speed that saturates the sprint blend. */
    private static final float SPRINT = 0.28F;

    private static final int ROOT = 0;
    private static final int TORSO = 1;
    private static final int HEAD = 2;
    private static final int LEFT_ARM = 3;
    private static final int LEFT_FOREARM = 4;
    private static final int RIGHT_ARM = 5;
    private static final int RIGHT_FOREARM = 6;
    private static final int LEFT_THIGH = 7;
    private static final int LEFT_SHIN = 8;
    private static final int RIGHT_THIGH = 9;
    private static final int RIGHT_SHIN = 10;
    private static final int CAPE = 11;

    // The four extremities are declared after the cape rather than beside their limbs, so that every
    // index the suite already pinned stayed where it was when feet and hands arrived.
    private static final int LEFT_FOOT = 12;
    private static final int RIGHT_FOOT = 13;
    private static final int LEFT_HAND = 14;
    private static final int RIGHT_HAND = 15;

    private static final float THIGH_LENGTH = 0.6F;
    private static final float SHIN_LENGTH = 0.6F;

    /** The shipped gait calibration, written out so the expectations below sit next to their source. */
    private static final GaitTuning CALIBRATED = calibrating().build();

    /** The shipped degenerate-target guard of the two-bone solver. */
    private static final IkTolerance IK_TOLERANCE = new IkTolerance(1.0E-4F);

    private static final BindingBounds BINDING_BOUNDS =
            new BindingBounds(1.0E-3F, 16F, 0.1F, 8F, 2F);

    private static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    private static final RigBounds RIG_BOUNDS = new RigBounds(256);

    private final WalkCycleSolver solver = new WalkCycleSolver(CALIBRATED, IK_TOLERANCE);

    /**
     * The shipped calibration, as a builder so a test can replace exactly one value.
     *
     * <p>{@link GaitTuning.Builder} deliberately carries no defaults, so every one of the thirty-four is
     * stated here — which is also what makes the overrides below readable as deviations.
     */
    private static GaitTuning.Builder calibrating() {
        return GaitTuning.builder()
                .counterPhaseCycles(0.5F)
                .restingLegExtension(0.95F)
                .movementDeadbandBlocksPerTick(0.003F)
                .sneakTorsoPitchRadians(0.35F)
                .fullStrideSpeedBlocksPerTick(0.215F)
                .sprintSpeedBlocksPerTick(0.28F)
                .strideToFootReach(0.25F)
                .footLiftFraction(0.18F)
                .footToeOffRadians(0.45F)
                .footHeelStrikeRadians(0.18F)
                .maxIkReachFraction(0.99F)
                .minIkDownFraction(0.35F)
                .legSwingRadians(0.70F)
                .sprintLegSwingRadians(1.05F)
                .armSwingRadians(0.55F)
                .forearmCurlRadians(0.35F)
                .attackDurationSeconds(0.35F)
                .attackUpperArmRadians(-1.35F)
                .attackForearmRadians(0.55F)
                .torsoLeanRadians(0.10F)
                .torsoBobRadians(0.035F)
                .torsoBobHarmonic(2F)
                .torsoSwayRadians(0.06F)
                .torsoTwistRadians(0.08F)
                .breathRateHz(0.25F)
                .breathRadians(0.02F)
                .amplitudeBlendSeconds(0.15F)
                .airborneBlendSeconds(0.20F)
                .sneakBlendSeconds(0.18F)
                .maxStepSeconds(0.25F)
                .maxGaitSpeedBlocksPerTick(4F)
                .maxPhaseAdvancePerStep(4F)
                .elapsedWrapBreathCycles(256)
                .settledAmplitude(1.0E-3F)
                .defaultStrideLength(1.3F)
                .defaultHeadStabilise(0.65F)
                .defaultHandStabilise(0.35F);
    }

    /** The calibrated look at the tuning's default proportions. */
    private static GaitProfile profile() {
        return GaitProfile.defaults(CALIBRATED);
    }

    /**
     * A humanoid plus a cape, which belongs to the physics layer and must never be written here.
     *
     * <p>The extremities sit after the cape in declaration order for the reason the index constants
     * give. A rig only requires a parent to precede its children, which this still satisfies.
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

    /** Torso, head, two kneed legs and two elbowed arms. No extremities: those have their own fixtures. */
    private static LocomotionBinding.Builder kneedBuilder(
            Rig rig,
            float thigh,
            float shin,
            float bendSign
    ) {
        return LocomotionBinding.builder(rig, BINDING_BOUNDS)
                .boneIndex(GaitRole.TORSO, TORSO)
                .boneIndex(GaitRole.HEAD, HEAD)
                .boneIndex(GaitRole.LEFT_UPPER_ARM, LEFT_ARM)
                .boneIndex(GaitRole.LEFT_FOREARM, LEFT_FOREARM)
                .boneIndex(GaitRole.RIGHT_UPPER_ARM, RIGHT_ARM)
                .boneIndex(GaitRole.RIGHT_FOREARM, RIGHT_FOREARM)
                .boneIndex(GaitRole.LEFT_THIGH, LEFT_THIGH)
                .boneIndex(GaitRole.LEFT_SHIN, LEFT_SHIN)
                .boneIndex(GaitRole.RIGHT_THIGH, RIGHT_THIGH)
                .boneIndex(GaitRole.RIGHT_SHIN, RIGHT_SHIN)
                .legSegments(thigh, shin)
                .kneeBendSign(bendSign);
    }

    private static LocomotionBinding kneed(Rig rig, float thigh, float shin, float bendSign) {
        return kneedBuilder(rig, thigh, shin, bendSign).build();
    }

    private static LocomotionBinding kneed(Rig rig) {
        return kneed(rig, THIGH_LENGTH, SHIN_LENGTH, 1F);
    }

    /** The same humanoid with both feet bound, which is what the ankle assertions need. */
    private static LocomotionBinding kneedWithFeet(Rig rig) {
        return kneedBuilder(rig, THIGH_LENGTH, SHIN_LENGTH, 1F)
                .boneIndex(GaitRole.LEFT_FOOT, LEFT_FOOT)
                .boneIndex(GaitRole.RIGHT_FOOT, RIGHT_FOOT)
                .build();
    }

    /** The same humanoid with both hands bound. */
    private static LocomotionBinding kneedWithHands(Rig rig) {
        return kneedBuilder(rig, THIGH_LENGTH, SHIN_LENGTH, 1F)
                .boneIndex(GaitRole.LEFT_HAND, LEFT_HAND)
                .boneIndex(GaitRole.RIGHT_HAND, RIGHT_HAND)
                .build();
    }

    @Test
    void attackSwingUsesOnlyTheBoundRightArmRolesAndSettles() {
        final LocomotionBinding binding = kneedWithHands(humanoid());
        final WalkCycleState state = state(binding);
        final Pose pose = new Pose(binding.boneCount());

        state.triggerAttack();
        step(binding, state, pose, 0F, CALIBRATED.attackDurationSeconds() * 0.5F);

        assertEquals(CALIBRATED.attackUpperArmRadians(), pose.pitch(RIGHT_ARM), 1.0E-5F);
        assertEquals(binding.elbowBendSign() * CALIBRATED.attackForearmRadians(),
                pose.pitch(RIGHT_FOREARM), 1.0E-5F);
        assertFalse(state.isSettled());

        step(binding, state, pose, 0F, CALIBRATED.attackDurationSeconds());
        assertTrue(state.isSettled());
    }

    /** A kneed humanoid whose mesh was modelled facing {@code degrees} away from where it walks. */
    private static LocomotionBinding kneedFacing(Rig rig, float degrees) {
        return kneedBuilder(rig, THIGH_LENGTH, SHIN_LENGTH, 1F).facing(degrees).build();
    }

    /** Legs with no shins, so a thigh angle is a pure sine and counter-phase can be measured exactly. */
    private static LocomotionBinding onePiece(Rig rig) {
        return LocomotionBinding.builder(rig, BINDING_BOUNDS)
                .boneIndex(GaitRole.TORSO, TORSO)
                .boneIndex(GaitRole.LEFT_UPPER_ARM, LEFT_ARM)
                .boneIndex(GaitRole.RIGHT_UPPER_ARM, RIGHT_ARM)
                .boneIndex(GaitRole.LEFT_THIGH, LEFT_THIGH)
                .boneIndex(GaitRole.RIGHT_THIGH, RIGHT_THIGH)
                .build();
    }

    /**
     * A one-piece left leg with a boot on the end and no shin between.
     *
     * <p>The combination the obvious implementation of a foot would have refused, so it gets a fixture
     * of its own: the foot bone is a child of the shin in the rig's hierarchy and the gait never
     * mentions the shin, which is exactly the rig a modeller produces when the knee is not a group.
     */
    private static LocomotionBinding onePieceWithFeet(Rig rig) {
        return LocomotionBinding.builder(rig, BINDING_BOUNDS)
                .boneIndex(GaitRole.TORSO, TORSO)
                .boneIndex(GaitRole.LEFT_THIGH, LEFT_THIGH)
                .boneIndex(GaitRole.LEFT_FOOT, LEFT_FOOT)
                .boneIndex(GaitRole.RIGHT_THIGH, RIGHT_THIGH)
                .boneIndex(GaitRole.RIGHT_FOOT, RIGHT_FOOT)
                .build();
    }

    /** A torso-only binding, i.e. a floating decoration with no legs to drive. */
    private static LocomotionBinding torsoOnly(Rig rig) {
        return LocomotionBinding.builder(rig, BINDING_BOUNDS)
                .boneIndex(GaitRole.TORSO, TORSO)
                .build();
    }

    private WalkCycleState state(LocomotionBinding binding) {
        return (WalkCycleState) this.solver.createState(binding);
    }

    private void step(LocomotionBinding binding, GaitState state, Pose pose, float speed, float delta) {
        stepWith(this.solver, binding, profile(), state, pose, speed, delta);
    }

    private static void stepWith(
            WalkCycleSolver solver,
            LocomotionBinding binding,
            GaitProfile profile,
            GaitState state,
            Pose pose,
            float speed,
            float delta
    ) {
        solver.solve(binding, profile, state, pose, delta, new PhysicsDriver(speed, 0F, 0F, 0F));
    }

    private void walk(LocomotionBinding binding, GaitState state, Pose pose, float speed, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            step(binding, state, pose, speed, TICK);
        }
    }

    /** Evaluates the pose at a chosen point in the stride, holding the amplitude. */
    private void evaluate(
            LocomotionBinding binding,
            GaitProfile profile,
            WalkCycleState state,
            Pose pose,
            float phase,
            float amplitude
    ) {
        evaluateWith(this.solver, binding, profile, state, pose, phase, amplitude);
    }

    private static void evaluateWith(
            WalkCycleSolver solver,
            LocomotionBinding binding,
            GaitProfile profile,
            WalkCycleState state,
            Pose pose,
            float phase,
            float amplitude
    ) {
        state.phase = phase;
        state.amplitude = amplitude;
        solver.solve(binding, profile, state, pose, EVAL_DELTA, PhysicsDriver.STILL);
    }

    /**
     * Evaluates the pose at a chosen point in the stride with a chosen sprint blend held as well.
     *
     * <p>The blend is a function of speed, and the evaluation delta is a millionth of a second against a
     * standing driver, so what the solver's own ramp can take off it in one step is six parts in a
     * million — far below the tolerances the sprint assertions use. Setting it directly is what makes
     * the two configured amplitudes separately observable at all.
     */
    private void evaluateSprinting(
            LocomotionBinding binding,
            GaitProfile profile,
            WalkCycleState state,
            Pose pose,
            float phase,
            float sprintBlend
    ) {
        state.sprintBlend = sprintBlend;
        evaluate(binding, profile, state, pose, phase, 1F);
    }

    /**
     * Evaluates the pose at a chosen point in the stride while still being driven.
     *
     * <p>{@link #evaluate} hands over a standing driver, which is what makes it an evaluation rather
     * than a step — but a standing driver is below the deadband, so the stride bias falls back to the
     * identity and the direction of travel becomes unobservable. This variant keeps the real driver and
     * relies on the evaluation delta instead: a millionth of a second advances the phase by three parts
     * in a million and moves no blend further than that.
     */
    private void evaluateDriven(
            LocomotionBinding binding,
            GaitProfile profile,
            WalkCycleState state,
            Pose pose,
            float phase,
            PhysicsDriver driver
    ) {
        state.phase = phase;
        state.amplitude = 1F;
        this.solver.solve(binding, profile, state, pose, EVAL_DELTA, driver);
    }

    private static void assertSamePose(Pose expected, Pose actual, String what) {
        assertEquals(expected.boneCount(), actual.boneCount(), what + ": pose widths differ");
        for (int bone = 0; bone < expected.boneCount(); bone++) {
            for (int axis = 0; axis < RotationAxis.COUNT; axis++) {
                final RotationAxis rotation = RotationAxis.at(axis);
                assertEquals(
                        Float.floatToRawIntBits(expected.get(bone, rotation)),
                        Float.floatToRawIntBits(actual.get(bone, rotation)),
                        what + ": bone " + bone + " " + rotation + " differs ("
                                + expected.get(bone, rotation) + " vs " + actual.get(bone, rotation) + ")");
            }
        }
    }

    // ───────────────────────────── Phase and the deadband ─────────────────────────────

    @Test
    void reportsItsDeadbandAsPartOfItsContract() {
        assertEquals(0.003F, this.solver.movementDeadband(),
                "callers smooth their own speed sampling against this");
    }

    @Test
    void phaseAdvancesInProportionToSpeed() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState slow = state(binding);
        final WalkCycleState fast = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        step(binding, slow, pose, 0.05F, TICK);
        step(binding, fast, pose, 0.10F, TICK);

        assertAll(
                () -> assertTrue(slow.phase() > 0F, "it must advance at all, got " + slow.phase()),
                () -> assertEquals(2F * slow.phase(), fast.phase(), 1.0E-6F,
                        "twice the speed must be twice the phase"),
                // Pinned against the arithmetic: 0.05 blocks/tick for 1/20 s is 0.05 blocks, which is
                // 0.05/1.3 of the default stride.
                () -> assertEquals(0.05F / CALIBRATED.defaultStrideLength(), slow.phase(), 1.0E-6F));
    }

    @Test
    void phaseAdvancesWithDistanceTravelledRatherThanWithTime() {
        // The one property that keeps feet from sliding. The same ground covered must land on the same
        // point in the stride however it was split across steps.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState oneFastStep = state(binding);
        step(binding, oneFastStep, pose, 0.10F, TICK);

        final WalkCycleState twoSlowSteps = state(binding);
        walk(binding, twoSlowSteps, pose, 0.05F, 2);

        final WalkCycleState oneLongStep = state(binding);
        step(binding, oneLongStep, pose, 0.05F, 2F * TICK);

        assertAll(
                () -> assertEquals(oneFastStep.phase(), twoSlowSteps.phase(), 1.0E-6F),
                () -> assertEquals(oneFastStep.phase(), oneLongStep.phase(), 1.0E-6F));
    }

    @Test
    void theDeadbandStopsThePhaseFromCreeping() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState below = state(binding);
        walk(binding, below, pose, 0.0029F, 2000);

        final WalkCycleState above = state(binding);
        walk(binding, above, pose, 0.0031F, 50);

        assertAll(
                // Exactly zero, not nearly zero. A phase that creeps by an epsilon per tick is a model
                // that shuffles on the spot, and it is the most visible defect this layer can have.
                () -> assertEquals(0, Float.floatToRawIntBits(below.phase()),
                        "a hundred seconds below the deadband must not move the phase at all"),
                () -> assertEquals(0F, below.amplitude()),
                () -> assertTrue(below.isSettled()),
                () -> assertTrue(above.phase() > 0.1F,
                        "just above the deadband it must advance, got " + above.phase()),
                () -> assertTrue(above.amplitude() > 0F));
    }

    @Test
    void theGaitSettlesIntoAHeldStandWhenMovementStops() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        walk(binding, gait, pose, WALK, 60);
        assertEquals(1F, gait.amplitude(), 1.0E-6F, "walking pace must saturate the amplitude");
        assertFalse(gait.isSettled());

        final float phaseWhenItStopped = gait.phase();
        walk(binding, gait, pose, 0F, 20);

        final Pose settled = new Pose(rig.boneCount());
        settled.copyFrom(pose);
        walk(binding, gait, pose, 0F, 20);

        assertAll(
                () -> assertEquals(0, Float.floatToRawIntBits(gait.amplitude()),
                        "the amplitude must reach exactly zero, not approach it"),
                () -> assertTrue(gait.isSettled()),
                () -> assertEquals(Float.floatToRawIntBits(phaseWhenItStopped),
                        Float.floatToRawIntBits(gait.phase()), "a settled gait must not advance"),
                // Legs converge on the rest pose and hold it, whichever phase the cycle stopped at.
                // Only the torso keeps moving, because it keeps breathing.
                () -> assertEquals(Float.floatToRawIntBits(settled.pitch(LEFT_THIGH)),
                        Float.floatToRawIntBits(pose.pitch(LEFT_THIGH))),
                () -> assertEquals(Float.floatToRawIntBits(settled.pitch(LEFT_SHIN)),
                        Float.floatToRawIntBits(pose.pitch(LEFT_SHIN))),
                () -> assertEquals(Float.floatToRawIntBits(settled.pitch(RIGHT_THIGH)),
                        Float.floatToRawIntBits(pose.pitch(RIGHT_THIGH))),
                () -> assertNotEquals(settled.pitch(TORSO), pose.pitch(TORSO),
                        "a settled gait still breathes"));
    }

    @Test
    void clampsAnEnormousDeltaInsteadOfJumpingToAnArbitraryPhase() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState stalled = state(binding);
        step(binding, stalled, pose, WALK, 10F);

        final WalkCycleState clamped = state(binding);
        step(binding, clamped, pose, WALK, CALIBRATED.maxStepSeconds());

        assertAll(
                () -> assertEquals(Float.floatToRawIntBits(clamped.phase()),
                        Float.floatToRawIntBits(stalled.phase()),
                        "a ten-second stall must be treated as the longest honoured step"),
                () -> assertTrue(pose.isFinite()));
    }

    // ───────────────────────────── Counter-phase ─────────────────────────────

    @Test
    void armsSwingInExactCounterPhaseToLegs() {
        // One-piece legs, so both the thigh and the arm are pure sines and the half-cycle offset shows up
        // as an exact ratio rather than as a sign test.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        final float expectedRatio =
                -CALIBRATED.armSwingRadians() / CALIBRATED.legSwingRadians();

        for (int sample = 1; sample < 20; sample++) {
            final float phase = sample / 20F;
            if (Math.abs(Math.sin(2 * Math.PI * phase)) < 1.0E-3) {
                continue;
            }
            evaluate(binding, profile(), gait, pose, phase, 1F);

            final float leftThigh = pose.pitch(LEFT_THIGH);
            final float leftArm = pose.pitch(LEFT_ARM);
            final float rightThigh = pose.pitch(RIGHT_THIGH);
            final float rightArm = pose.pitch(RIGHT_ARM);

            assertEquals(expectedRatio, leftArm / leftThigh, 1.0E-4F,
                    "phase " + phase + ": the left arm must oppose the left leg");
            assertTrue(leftThigh * leftArm < 0F, "phase " + phase + ": they must have opposite signs");
            assertEquals(expectedRatio, rightArm / rightThigh, 1.0E-4F,
                    "phase " + phase + ": and the right arm the right leg");
            assertTrue(leftThigh * rightThigh < 0F,
                    "phase " + phase + ": the two legs must also be in counter-phase");
            assertTrue(leftArm * rightArm < 0F, "phase " + phase + ": and so must the two arms");
            // Contralateral limbs travel together: left arm with right leg.
            assertTrue(leftArm * rightThigh > 0F,
                    "phase " + phase + ": the left arm must travel with the right leg");
        }
    }

    @Test
    void theHalfCycleOffsetIsTheSameFactForLegsAsForArms() {
        // A kneed left leg at phase p must match the right leg half a cycle later, bone for bone. If the
        // two offsets ever drift apart, this is what catches it.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose atPhase = new Pose(rig.boneCount());
        final Pose atOpposite = new Pose(rig.boneCount());

        for (int sample = 0; sample < 16; sample++) {
            final float phase = sample / 16F;
            final float opposite = (phase + CALIBRATED.counterPhaseCycles()) % 1F;

            evaluate(binding, profile(), gait, atPhase, phase, 1F);
            evaluate(binding, profile(), gait, atOpposite, opposite, 1F);

            assertEquals(atPhase.pitch(LEFT_THIGH), atOpposite.pitch(RIGHT_THIGH), 1.0E-5F,
                    "phase " + phase + ": the right thigh must lag the left by half a cycle");
            assertEquals(atPhase.pitch(LEFT_SHIN), atOpposite.pitch(RIGHT_SHIN), 1.0E-5F,
                    "phase " + phase + ": and so must the right shin");
            assertEquals(atPhase.pitch(LEFT_ARM), atOpposite.pitch(RIGHT_ARM), 1.0E-5F,
                    "phase " + phase + ": and the right arm the left arm");
        }
    }

    @Test
    void aOnePieceLegSwingsWithinItsCalibratedAmplitude() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        float peak = 0F;
        for (int sample = 0; sample < 128; sample++) {
            evaluate(binding, profile(), gait, pose, sample / 128F, 1F);
            peak = Math.max(peak, Math.abs(pose.pitch(LEFT_THIGH)));
            assertTrue(Math.abs(pose.pitch(LEFT_THIGH)) <= CALIBRATED.legSwingRadians() + 1.0E-5F,
                    "a one-piece leg must stay inside its calibrated swing");
        }
        assertEquals(CALIBRATED.legSwingRadians(), peak, 1.0E-3F,
                "and must actually reach it at full amplitude");
    }

    @Test
    void theElbowCurlNeverFoldsBackward() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        for (int sample = 0; sample < 128; sample++) {
            evaluate(binding, profile(), gait, pose, sample / 128F, 1F);
            final float curl = pose.pitch(LEFT_FOREARM);
            assertTrue(curl * binding.elbowBendSign() >= -1.0E-6F,
                    "an elbow folds one way; got " + curl + " against sign "
                            + binding.elbowBendSign());
            assertTrue(Math.abs(curl) <= CALIBRATED.forearmCurlRadians() + 1.0E-5F);
        }
    }

    // ───────────────────────────── Inverse kinematics ─────────────────────────────

    @Test
    void aKneedFootStaysWithinReachAndTheKneeNeverInverts() {
        // The reach clamp's whole purpose: TwoBoneIk straightens rather than inverting when a target is
        // out of range, and a straight leg is the configuration where the knee's direction is undefined
        // and the solved angles are noisiest. Clamping the target radius bounds the bend away from zero.
        final Rig rig = humanoid();
        final float[][] segments = {{0.6F, 0.6F}, {0.8F, 0.4F}, {0.2F, 1.0F}, {0.05F, 0.05F}};
        final float[] strides = {
                BINDING_BOUNDS.minStrideLength(), CALIBRATED.defaultStrideLength(),
                BINDING_BOUNDS.maxStrideLength()};
        final float[] amplitudes = {0F, 0.37F, 1F};
        final float[] signs = {1F, -1F};

        for (final float[] pair : segments) {
            for (final float sign : signs) {
                final LocomotionBinding binding = kneed(rig, pair[0], pair[1], sign);
                final WalkCycleState gait = state(binding);
                final Pose pose = new Pose(rig.boneCount());
                for (final float stride : strides) {
                    final GaitProfile profile = profile().withStrideLength(stride);
                    for (final float amplitude : amplitudes) {
                        for (int sample = 0; sample < 64; sample++) {
                            evaluate(binding, profile, gait, pose, sample / 64F, amplitude);
                            final float knee = pose.pitch(LEFT_SHIN);
                            final String where = "segments " + pair[0] + "/" + pair[1] + " sign " + sign
                                    + " stride " + stride + " amplitude " + amplitude
                                    + " phase " + sample / 64F;

                            assertTrue(pose.isFinite(), where + ": non-finite pose");
                            assertTrue(knee * sign > 0F,
                                    where + ": the knee inverted, got " + knee);
                            assertTrue(Math.abs(knee) >= 0.25F,
                                    where + ": the knee straightened into its singularity, got " + knee);
                            assertTrue(Math.abs(knee) <= (float) Math.PI + 1.0E-5F,
                                    where + ": the knee folded past itself, got " + knee);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aRestingKneedLegStopsShortOfStraight() {
        // restingLegExtension is 0.95 rather than 1 on purpose. With equal segments that leaves a knee
        // bend of acos-derived 0.64 rad at rest, which is what keeps the solved angles responsive instead
        // of jittering at the singularity.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        evaluate(binding, profile(), gait, pose, 0F, 0F);

        final double reach = binding.legReach();
        final double restRadius = CALIBRATED.restingLegExtension() * reach;
        final double a = binding.thighLength();
        final double b = binding.shinLength();
        final double expectedBend = Math.PI
                - Math.acos((a * a + b * b - restRadius * restRadius) / (2 * a * b));

        assertAll(
                () -> assertEquals(0.95F, CALIBRATED.restingLegExtension()),
                () -> assertEquals((float) expectedBend, pose.pitch(LEFT_SHIN), 1.0E-4F,
                        "the resting knee must sit exactly where 0.95 extension puts it"),
                () -> assertTrue(pose.pitch(LEFT_SHIN) > 0.5F,
                        "and that is a visible bend, not a rounding artefact"),
                () -> assertEquals(pose.pitch(LEFT_SHIN), pose.pitch(RIGHT_SHIN), 1.0E-6F,
                        "both legs rest identically regardless of their phase offset"));
    }

    @Test
    void aKneedThighLeadsTheFootForwardAndBackwardAroundItsRestAngle() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        evaluate(binding, profile(), gait, pose, 0F, 0F);
        final float rest = pose.pitch(LEFT_THIGH);

        evaluate(binding, profile(), gait, pose, 0.25F, 1F);
        final float forward = pose.pitch(LEFT_THIGH);

        evaluate(binding, profile(), gait, pose, 0.75F, 1F);
        final float backward = pose.pitch(LEFT_THIGH);

        assertAll(
                () -> assertTrue(forward > rest, "at a quarter cycle the foot leads: " + forward),
                () -> assertTrue(backward < rest, "at three quarters it trails: " + backward),
                () -> assertTrue(forward - backward > 0.3F,
                        "and the excursion must be visible, got " + (forward - backward)));
    }

    @Test
    void theFootLiftsDuringTheSwingHalfAndStaysDownDuringTheStance() {
        // The lift peaks a quarter cycle from either extreme of the swing, where the foot is travelling
        // fastest. A lift in phase with the fore-aft offset instead would lift the planted foot.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        // Phase 0: cosine peaks, so the knee is folded most. Phase 0.5: cosine is negative, so the
        // rectified lift is zero and the leg sits at its resting extension.
        evaluate(binding, profile(), gait, pose, 0F, 1F);
        final float lifted = pose.pitch(LEFT_SHIN);

        evaluate(binding, profile(), gait, pose, 0.5F, 1F);
        final float planted = pose.pitch(LEFT_SHIN);

        evaluate(binding, profile(), gait, pose, 0.5F, 0F);
        final float resting = pose.pitch(LEFT_SHIN);

        assertAll(
                () -> assertTrue(lifted > planted,
                        "a lifted foot folds the knee further: " + lifted + " vs " + planted),
                () -> assertEquals(resting, planted, 1.0E-5F,
                        "mid-stance the leg must sit at exactly its resting extension"));
    }

    // ───────────────────────────── Stance blends ─────────────────────────────

    @Test
    void sneakBlendsInAndOutSmoothlyRatherThanSnapping() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        gait.setSneaking(true);
        step(binding, gait, pose, 0F, TICK);
        final float afterOneTick = gait.sneakBlend();

        float previous = afterOneTick;
        int ticksToCross = 1;
        while (gait.sneakBlend() < 1F && ticksToCross < 100) {
            step(binding, gait, pose, 0F, TICK);
            assertTrue(gait.sneakBlend() >= previous, "the blend must be monotone going in");
            previous = gait.sneakBlend();
            ticksToCross++;
        }
        final int inTicks = ticksToCross;
        final float crouched = pose.pitch(TORSO);

        gait.setSneaking(false);
        step(binding, gait, pose, 0F, TICK);
        previous = gait.sneakBlend();
        int outTicks = 1;
        while (gait.sneakBlend() > 0F && outTicks < 100) {
            step(binding, gait, pose, 0F, TICK);
            assertTrue(gait.sneakBlend() <= previous, "and monotone coming out");
            previous = gait.sneakBlend();
            outTicks++;
        }
        final int finalOutTicks = outTicks;

        assertAll(
                () -> assertTrue(afterOneTick > 0F && afterOneTick < 1F,
                        "one tick must move it part of the way, not all of it: " + afterOneTick),
                () -> assertTrue(inTicks > 1, "a blend that resolves in one tick is a snap"),
                () -> assertEquals(
                        (int) Math.ceil(CALIBRATED.sneakBlendSeconds() / TICK), inTicks,
                        "it must cross in exactly the configured time"),
                () -> assertEquals(inTicks, finalOutTicks, "in and out must take the same time"),
                // Full crouch is the calibrated pitch, give or take the breathing that rides on top.
                () -> assertEquals(CALIBRATED.sneakTorsoPitchRadians(), crouched,
                        CALIBRATED.breathRadians() + 1.0E-5F,
                        "full crouch must reach the calibrated pitch, got " + crouched),
                () -> assertEquals(0, Float.floatToRawIntBits(gait.sneakBlend()),
                        "standing up must reach exactly zero"),
                () -> assertTrue(gait.isSettled()));
    }

    @Test
    void sneakDepthZeroRemovesTheCrouchEntirely() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());
        final GaitProfile upright =
                GaitProfile.builder(CALIBRATED).sneakDepth(0F).breathDepth(0F).build();

        gait.setSneaking(true);
        for (int tick = 0; tick < 20; tick++) {
            this.solver.solve(binding, upright, gait, pose, TICK, PhysicsDriver.STILL);
        }

        assertAll(
                () -> assertEquals(1F, gait.sneakBlend(), "the blend still resolves"),
                () -> assertEquals(0F, pose.pitch(TORSO), 1.0E-6F, "but it pitches nothing"),
                () -> assertEquals(0F, pose.pitch(HEAD), 1.0E-6F));
    }

    @Test
    void crouchTiltsTheUpperBodyButKeepsTheLegRootsUpright() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());
        final GaitProfile noBreath = GaitProfile.builder(CALIBRATED).breathDepth(0F).build();
        final WalkCycleState uprightGait = state(binding);
        final Pose uprightPose = new Pose(rig.boneCount());

        gait.setSneaking(true);
        for (int tick = 0; tick < 20; tick++) {
            this.solver.solve(binding, noBreath, gait, pose, TICK, PhysicsDriver.STILL);
            this.solver.solve(binding, noBreath, uprightGait, uprightPose, TICK, PhysicsDriver.STILL);
        }

        assertAll(
                () -> assertEquals(CALIBRATED.sneakTorsoPitchRadians(), pose.pitch(TORSO), 1.0E-6F),
                () -> assertEquals(0F, pose.pitch(HEAD), 1.0E-6F,
                        "the head inherits the upper-body crouch"),
                () -> assertEquals(uprightPose.pitch(LEFT_THIGH) - CALIBRATED.sneakTorsoPitchRadians(),
                        pose.pitch(LEFT_THIGH), 1.0E-6F,
                        "the left leg root cancels the torso crouch"),
                () -> assertEquals(uprightPose.pitch(RIGHT_THIGH) - CALIBRATED.sneakTorsoPitchRadians(),
                        pose.pitch(RIGHT_THIGH), 1.0E-6F,
                        "the right leg root cancels the torso crouch"));
    }

    @Test
    void airborneBlendsTheGaitOutWithoutFreezingThePhase() {
        // Blending out rather than freezing is the requirement, and the phase continuing underneath is
        // what makes landing resume the stride where the ground would have put it.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final Pose pose = new Pose(rig.boneCount());
        final Pose groundPose = new Pose(rig.boneCount());

        final WalkCycleState jumper = state(binding);
        final WalkCycleState grounded = state(binding);
        walk(binding, jumper, pose, WALK, 60);
        walk(binding, grounded, groundPose, WALK, 60);
        assertSamePose(groundPose, pose, "two identically driven gaits must agree before the jump");

        jumper.setAirborne(true);
        float previous = 0F;
        int ticksToCross = 0;
        while (jumper.airborneBlend() < 1F && ticksToCross < 100) {
            step(binding, jumper, pose, WALK, TICK);
            step(binding, grounded, groundPose, WALK, TICK);
            assertTrue(jumper.airborneBlend() >= previous, "the blend must be monotone");
            previous = jumper.airborneBlend();
            ticksToCross++;
        }
        final int inTicks = ticksToCross;

        final Pose rest = new Pose(rig.boneCount());
        final WalkCycleState standing = state(binding);
        evaluate(binding, profile(), standing, rest, jumper.phase(), 0F);

        assertAll(
                () -> assertTrue(inTicks > 1, "an airborne blend that resolves in one tick is a snap"),
                () -> assertEquals(
                        (int) Math.ceil(CALIBRATED.airborneBlendSeconds() / TICK), inTicks),
                () -> assertEquals(Float.floatToRawIntBits(grounded.phase()),
                        Float.floatToRawIntBits(jumper.phase()),
                        "the phase must keep advancing while airborne, not freeze"),
                // Fully airborne means the walk cycle contributes nothing, so the limbs sit exactly where
                // zero amplitude would put them.
                () -> assertEquals(rest.pitch(LEFT_THIGH), pose.pitch(LEFT_THIGH), 1.0E-6F),
                () -> assertEquals(rest.pitch(LEFT_SHIN), pose.pitch(LEFT_SHIN), 1.0E-6F),
                () -> assertEquals(rest.pitch(LEFT_ARM), pose.pitch(LEFT_ARM), 1.0E-6F),
                () -> assertEquals(0F, pose.roll(TORSO), 1.0E-6F));

        jumper.setAirborne(false);
        walk(binding, jumper, pose, WALK, 20);
        walk(binding, grounded, groundPose, WALK, 20);

        assertAll(
                () -> assertEquals(0, Float.floatToRawIntBits(jumper.airborneBlend())),
                // Landing resumes exactly the stride the ground dictated, which is the whole payoff of
                // advancing the phase while airborne.
                () -> assertSamePose(groundPose, pose, "after landing"));
    }

    // ───────────────────────────── Ownership and idle motion ─────────────────────────────

    @Test
    void leavesEveryBoneItDoesNotOwnExactlyAsItFoundIt() {
        // The contract that lets the additive physics layer run afterwards on the same pose. The cape is
        // physics' bone and the root is nobody's.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        pose.set(CAPE, 0.125F, -0.25F, 0.5F);
        pose.set(ROOT, 1.5F, 2.5F, -3.5F);

        for (int tick = 0; tick < 200; tick++) {
            gait.setSneaking(tick % 37 < 18);
            gait.setAirborne(tick % 53 < 11);
            step(binding, gait, pose, tick % 19 == 0 ? 0F : WALK, TICK);
        }

        assertAll(
                () -> assertEquals(0.125F, pose.pitch(CAPE)),
                () -> assertEquals(-0.25F, pose.yaw(CAPE)),
                () -> assertEquals(0.5F, pose.roll(CAPE)),
                () -> assertEquals(1.5F, pose.pitch(ROOT)),
                () -> assertEquals(2.5F, pose.yaw(ROOT)),
                () -> assertEquals(-3.5F, pose.roll(ROOT)),
                () -> assertFalse(binding.ownsBone(CAPE)),
                () -> assertFalse(binding.ownsBone(ROOT)),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void aBindingWithNoLegsStillProducesIdleMotion() {
        // A floating decoration has no legs and still must not read as a prop.
        final Rig rig = humanoid();
        final LocomotionBinding legless = torsoOnly(rig);
        final WalkCycleState gait = state(legless);
        final Pose pose = new Pose(rig.boneCount());

        assertFalse(legless.hasLegs());

        // Five seconds, which is more than one breath at 0.25 Hz.
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;
        int distinct = 0;
        float previous = Float.NaN;
        for (int tick = 0; tick < 100; tick++) {
            step(legless, gait, pose, 0F, TICK);
            final float pitch = pose.pitch(TORSO);
            lowest = Math.min(lowest, pitch);
            highest = Math.max(highest, pitch);
            if (pitch != previous) {
                distinct++;
                previous = pitch;
            }
        }
        final float min = lowest;
        final float max = highest;
        final int changes = distinct;

        assertAll(
                () -> assertTrue(changes > 90, "a breathing model changes every tick, got " + changes),
                () -> assertTrue(max > 0.8F * CALIBRATED.breathRadians(),
                        "a full breath must reach near its peak, got " + max),
                () -> assertTrue(min < -0.8F * CALIBRATED.breathRadians(),
                        "and its trough, got " + min),
                () -> assertTrue(max <= CALIBRATED.breathRadians() + 1.0E-6F,
                        "but never exceed it: idle motion is meant to be barely visible"),
                () -> assertEquals(0, Float.floatToRawIntBits(gait.phase()),
                        "a legless model standing still has no walk cycle to advance"),
                () -> assertTrue(gait.isSettled()),
                () -> assertEquals(0F, pose.pitch(HEAD), "an unbound head stays unwritten"),
                () -> assertEquals(0F, pose.pitch(LEFT_THIGH)));
    }

    @Test
    void breathDepthZeroMakesAStatue() {
        final Rig rig = humanoid();
        final LocomotionBinding legless = torsoOnly(rig);
        final WalkCycleState gait = state(legless);
        final Pose pose = new Pose(rig.boneCount());
        final GaitProfile statue = GaitProfile.builder(CALIBRATED).breathDepth(0F).build();

        for (int tick = 0; tick < 100; tick++) {
            this.solver.solve(legless, statue, gait, pose, TICK, PhysicsDriver.STILL);
            assertEquals(0, Float.floatToRawIntBits(pose.pitch(TORSO)),
                    "zero breath depth must be exactly still");
        }
    }

    @Test
    void breathingIsContinuousAcrossTheElapsedTimeWrap() {
        // The wrap interval is a whole number of breaths precisely so that the wrap lands on a zero
        // crossing and the oscillation does not jump. Wrapping at all is what keeps a float seconds
        // counter from losing the resolution the oscillation needs after hours of uptime.
        final Rig rig = humanoid();
        final LocomotionBinding legless = torsoOnly(rig);
        final WalkCycleState gait = state(legless);
        final Pose pose = new Pose(rig.boneCount());

        final float wrap = CALIBRATED.elapsedWrapBreathCycles() / CALIBRATED.breathRateHz();
        final float cyclesPerWrap = wrap * CALIBRATED.breathRateHz();

        // Two ticks before the wrap, so one step lands just short of it and the next crosses it.
        gait.elapsedSeconds = wrap - 2F * TICK;

        step(legless, gait, pose, 0F, TICK);
        final float elapsedBefore = gait.elapsedSeconds();
        final float before = pose.pitch(TORSO);

        step(legless, gait, pose, 0F, TICK);
        final float elapsedAfter = gait.elapsedSeconds();
        final float after = pose.pitch(TORSO);

        // Twenty-one minutes of simulated uptime, which is more than one whole wrap interval.
        float lowestElapsed = Float.MAX_VALUE;
        float highestElapsed = -Float.MAX_VALUE;
        for (int tick = 0; tick < 25_000; tick++) {
            step(legless, gait, pose, 0F, TICK);
            lowestElapsed = Math.min(lowestElapsed, gait.elapsedSeconds());
            highestElapsed = Math.max(highestElapsed, gait.elapsedSeconds());
        }
        final float lowest = lowestElapsed;
        final float highest = highestElapsed;

        assertAll(
                () -> assertTrue(lowest >= 0F, "elapsed time went negative: " + lowest),
                () -> assertTrue(highest < wrap,
                        "elapsed time escaped its wrap: " + highest + " against " + wrap),
                () -> assertTrue(highest > wrap - 1F,
                        "the run must actually reach the wrap, got " + highest),
                // The invariant that makes the wrap continuous rather than merely bounded.
                () -> assertEquals((float) CALIBRATED.elapsedWrapBreathCycles(), cyclesPerWrap,
                        "the wrap interval must be a whole number of breath cycles"),
                () -> assertEquals(0F, cyclesPerWrap - Math.round(cyclesPerWrap)),
                () -> assertTrue(elapsedBefore > wrap - 2F * TICK,
                        "the first step must land just short of the wrap, got " + elapsedBefore),
                () -> assertTrue(elapsedAfter < TICK,
                        "and the second must cross it, got " + elapsedAfter),
                // A wrap landing anywhere but on a zero crossing would jump by up to twice the
                // breathing amplitude. Landing on one keeps the step the size of an ordinary step.
                () -> assertTrue(Math.abs(after - before) < 0.25F * CALIBRATED.breathRadians(),
                        "the wrap must not make the breathing jump: " + before + " -> " + after),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void theTorsoBobRunsAtTwiceTheStrideFrequencyWhileTheSwayRunsAtOnce() {
        // The classic tell of a hand-made walk: a bob at stride frequency makes the model limp, because
        // the body rises once per step and there are two steps in a cycle.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose first = new Pose(rig.boneCount());
        final Pose second = new Pose(rig.boneCount());
        final GaitProfile noBreath = GaitProfile.builder(CALIBRATED).breathDepth(0F).build();

        evaluate(binding, noBreath, gait, first, 0.13F, 1F);
        evaluate(binding, noBreath, gait, second, 0.63F, 1F);

        assertAll(
                () -> assertEquals(first.pitch(TORSO), second.pitch(TORSO), 1.0E-5F,
                        "the bob repeats twice per cycle, so half a cycle leaves pitch unchanged"),
                () -> assertEquals(-first.roll(TORSO), second.roll(TORSO), 1.0E-5F,
                        "the weight shift runs once per cycle, so half a cycle flips it"),
                () -> assertEquals(-first.yaw(TORSO), second.yaw(TORSO), 1.0E-5F,
                        "and so does the shoulder twist"),
                () -> assertTrue(Math.abs(first.roll(TORSO)) > 1.0E-3F, "and both are actually present"),
                () -> assertTrue(Math.abs(first.yaw(TORSO)) > 1.0E-3F));
    }

    // ───────────────────────────── Robustness ─────────────────────────────

    @Test
    void isDeterministic() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final float[] deltas = {TICK, 0.001F, 1F / 60F, 0.25F, 0.1234F};
        final float[] speeds = {0F, 0.002F, 0.07F, WALK, 0.6F};

        final Pose firstPose = new Pose(rig.boneCount());
        final Pose secondPose = new Pose(rig.boneCount());
        final WalkCycleState first = state(binding);
        final WalkCycleState second = state(binding);

        for (int round = 0; round < 200; round++) {
            final float delta = deltas[round % deltas.length];
            final float speed = speeds[(round * 7) % speeds.length];
            final boolean sneaking = round % 11 < 5;
            final boolean airborne = round % 17 < 4;

            first.setSneaking(sneaking);
            first.setAirborne(airborne);
            second.setSneaking(sneaking);
            second.setAirborne(airborne);
            step(binding, first, firstPose, speed, delta);
            step(binding, second, secondPose, speed, delta);
        }

        assertAll(
                () -> assertSamePose(firstPose, secondPose, "identical inputs"),
                () -> assertEquals(Float.floatToRawIntBits(first.phase()),
                        Float.floatToRawIntBits(second.phase())),
                () -> assertEquals(Float.floatToRawIntBits(first.amplitude()),
                        Float.floatToRawIntBits(second.amplitude())),
                () -> assertEquals(Float.floatToRawIntBits(first.elapsedSeconds()),
                        Float.floatToRawIntBits(second.elapsedSeconds())),
                () -> assertEquals(Float.floatToRawIntBits(first.sneakBlend()),
                        Float.floatToRawIntBits(second.sneakBlend())),
                () -> assertEquals(Float.floatToRawIntBits(first.airborneBlend()),
                        Float.floatToRawIntBits(second.airborneBlend())));
    }

    @Test
    void survivesAStormOfIrregularDeltasAndAbsurdSpeeds() {
        final Rig rig = humanoid();
        final float[] strides = {BINDING_BOUNDS.minStrideLength(), BINDING_BOUNDS.maxStrideLength()};
        final float[] deltas = {
                0.001F, TICK, 0.5F, 2F, 0F, -1F, 30F, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MIN_VALUE, 1.0E30F};
        final float[] speeds = {
                0F, 1.0E-12F, WALK, 1.5F, -0.3F, 1.0E30F, Float.MAX_VALUE, -Float.MAX_VALUE,
                CALIBRATED.movementDeadbandBlocksPerTick()};

        for (final float stride : strides) {
            final LocomotionBinding binding = kneed(rig, 0.05F, 1.0F, 1F);
            final GaitProfile profile = profile().withStrideLength(stride);
            final WalkCycleState gait = state(binding);
            final Pose pose = new Pose(rig.boneCount());

            int round = 0;
            for (final float delta : deltas) {
                for (final float speed : speeds) {
                    gait.setSneaking(round % 3 == 0);
                    gait.setAirborne(round % 5 == 0);
                    round++;
                    this.solver.solve(binding, profile, gait, pose, delta,
                            new PhysicsDriver(speed, -speed, 11F, 7F));

                    final String where = "stride " + stride + " delta " + delta + " speed " + speed;
                    assertTrue(pose.isFinite(), where + ": produced a non-finite pose");
                    assertTrue(gait.phase() >= 0F && gait.phase() < 1F,
                            where + ": phase escaped [0,1), got " + gait.phase());
                    assertTrue(gait.amplitude() >= 0F && gait.amplitude() <= 1F,
                            where + ": amplitude escaped [0,1], got " + gait.amplitude());
                    assertTrue(gait.sneakBlend() >= 0F && gait.sneakBlend() <= 1F, where);
                    assertTrue(gait.airborneBlend() >= 0F && gait.airborneBlend() <= 1F, where);
                    assertTrue(Float.isFinite(gait.elapsedSeconds()), where);
                }
            }
        }
    }

    @Test
    void anAbsurdSpeedIsClampedRatherThanOverflowingThePhase() {
        // Without the speed ceiling, speed * h * 20 / strideLength overflows to infinity, whose
        // fractional part is NaN — and a NaN rotation deletes the model on the client.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        final GaitProfile shortStride =
                profile().withStrideLength(BINDING_BOUNDS.minStrideLength());
        for (int tick = 0; tick < 10; tick++) {
            this.solver.solve(binding, shortStride, gait, pose, TICK,
                    new PhysicsDriver(Float.MAX_VALUE, Float.MAX_VALUE, 0F, 0F));
            assertTrue(pose.isFinite(), "tick " + tick + " produced a non-finite pose");
        }

        assertAll(
                () -> assertTrue(Float.isFinite(gait.phase())),
                () -> assertTrue(gait.phase() >= 0F && gait.phase() < 1F,
                        "phase escaped [0,1), got " + gait.phase()),
                () -> assertEquals(1F, gait.amplitude(),
                        "an absurd speed still saturates the amplitude normally"),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void ignoresANonPositiveOrNonFiniteDelta() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        walk(binding, gait, pose, WALK, 10);
        final Pose untouched = new Pose(rig.boneCount());
        untouched.copyFrom(pose);
        final int phaseBits = Float.floatToRawIntBits(gait.phase());
        final int amplitudeBits = Float.floatToRawIntBits(gait.amplitude());
        final int elapsedBits = Float.floatToRawIntBits(gait.elapsedSeconds());

        for (final float delta : new float[]{
                0F, -1F, -0F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            step(binding, gait, pose, WALK, delta);
            assertAll(
                    () -> assertEquals(phaseBits, Float.floatToRawIntBits(gait.phase()),
                            "delta " + delta + " must not advance the phase"),
                    () -> assertEquals(amplitudeBits, Float.floatToRawIntBits(gait.amplitude())),
                    () -> assertEquals(elapsedBits, Float.floatToRawIntBits(gait.elapsedSeconds())),
                    () -> assertSamePose(untouched, pose, "delta " + delta));
        }
    }

    @Test
    void resetReturnsTheGaitToAStand() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        gait.setSneaking(true);
        gait.setAirborne(true);
        walk(binding, gait, pose, WALK, 40);
        assertFalse(gait.isSettled());

        gait.reset();

        assertAll(
                () -> assertEquals(0F, gait.phase()),
                () -> assertEquals(0F, gait.amplitude()),
                () -> assertEquals(0F, gait.airborneBlend()),
                () -> assertEquals(0F, gait.sneakBlend()),
                () -> assertEquals(0F, gait.elapsedSeconds()),
                () -> assertFalse(gait.isAirborne()),
                () -> assertFalse(gait.isSneaking()),
                () -> assertTrue(gait.isSettled()),
                () -> assertTrue(gait.toString().contains("phase")));
    }

    @Test
    void oneSolverInstanceServesManyIndependentModels() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState walker = state(binding);
        final WalkCycleState stander = state(binding);
        final Pose walking = new Pose(rig.boneCount());
        final Pose standing = new Pose(rig.boneCount());

        walk(binding, walker, walking, WALK, 30);
        walk(binding, stander, standing, 0F, 30);

        assertAll(
                () -> assertTrue(walker.phase() > 0F),
                () -> assertEquals(0F, stander.phase(), "the second model was never driven"),
                () -> assertFalse(walker.isSettled()),
                () -> assertTrue(stander.isSettled()),
                () -> assertNotEquals(walking.pitch(LEFT_THIGH), standing.pitch(LEFT_THIGH)));
    }

    // ───────────────────────────── Seam checks ─────────────────────────────

    @Test
    void refusesAStateItDidNotCreate() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final Pose pose = new Pose(rig.boneCount());
        final GaitState foreign = new GaitState() {
            @Override
            public void reset() {
            }

            @Override
            public float phase() {
                return 0F;
            }

            @Override
            public float amplitude() {
                return 0F;
            }

            @Override
            public float airborneBlend() {
                return 0F;
            }

            @Override
            public float sneakBlend() {
                return 0F;
            }

            @Override
            public float elapsedSeconds() {
                return 0F;
            }

            @Override
            public boolean isAirborne() {
                return false;
            }

            @Override
            public void setAirborne(boolean airborne) {
            }

            @Override
            public boolean isSneaking() {
                return false;
            }

            @Override
            public void setSneaking(boolean sneaking) {
            }

            @Override
            public boolean isSettled() {
                return true;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> this.solver.solve(binding, profile(), foreign, pose, TICK, PhysicsDriver.STILL));
    }

    @Test
    void refusesAPoseOfTheWrongWidth() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose narrow = new Pose(2);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> this.solver.solve(binding, profile(), gait, narrow, TICK, PhysicsDriver.STILL));
        assertTrue(thrown.getMessage().contains("humanoid"));
    }

    @Test
    void refusesAStateCreatedForADifferentBinding() {
        // A state stepped against a binding for another rig would write rotations into whatever bone
        // happened to sit at that index, which is far harder to diagnose than an exception.
        final Rig rig = humanoid();
        final Rig stub = Rig.builder("stub", "model", RIG_BOUNDS, PROFILE_BOUNDS).root("torso").build();
        final LocomotionBinding small =
                LocomotionBinding.builder(stub, BINDING_BOUNDS).boneIndex(GaitRole.TORSO, 0).build();
        final LocomotionBinding big = kneed(rig);
        final GaitState mismatched = this.solver.createState(small);
        final Pose pose = new Pose(rig.boneCount());

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> this.solver.solve(big, profile(), mismatched, pose, TICK, PhysicsDriver.STILL));
        assertTrue(thrown.getMessage().contains("belongs to one model"));
    }

    @Test
    void aTinyRigWithJustATorsoWorksEndToEnd() {
        final Rig stub = Rig.builder("stub", "model", RIG_BOUNDS, PROFILE_BOUNDS).root("torso").build();
        final LocomotionBinding binding =
                LocomotionBinding.builder(stub, BINDING_BOUNDS).boneIndex(GaitRole.TORSO, 0).build();
        final GaitState gait = this.solver.createState(binding);
        final Pose pose = new Pose(stub.boneCount());

        for (int tick = 0; tick < 40; tick++) {
            this.solver.solve(binding, profile(), gait, pose, TICK,
                    new PhysicsDriver(WALK, 0F, 0F, 0F));
        }

        assertAll(
                () -> assertTrue(pose.isFinite()),
                () -> assertTrue(gait.phase() > 0F),
                () -> assertNotEquals(0F, pose.pitch(0), "the torso must lean into the walk"));
    }

    // ───────────────────────── Injected configuration ─────────────────────────
    // Each of these moves exactly one value to somewhere no default would sit, and asserts the thing it
    // controls moves with it. A solver that kept a leftover constant would still pass every test above.

    @Test
    void theDeadbandIsReadFromTheTuningAndZeroLetsTheTiniestDriftAdvanceThePhase() {
        // The deadband is the one number in this layer whose absence is immediately visible, so a stale
        // constant here is the defect this whole exercise is meant to make impossible.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver jumpy =
                new WalkCycleSolver(calibrating().movementDeadbandBlocksPerTick(0F).build(),
                        IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState shipped = state(binding);
        step(binding, shipped, pose, 1.0E-9F, TICK);

        final WalkCycleState sensitive = (WalkCycleState) jumpy.createState(binding);
        stepWith(jumpy, binding, profile(), sensitive, pose, 1.0E-9F, TICK);

        assertAll(
                () -> assertEquals(0.003F, this.solver.movementDeadband()),
                () -> assertEquals(0F, jumpy.movementDeadband(),
                        "the reported deadband must be the configured one"),
                () -> assertEquals(0, Float.floatToRawIntBits(shipped.phase()),
                        "a nanoblock of drift is below the shipped deadband"),
                () -> assertTrue(sensitive.phase() > 0F,
                        "and above a deadband of zero, which is exactly the shuffle the default"
                                + " prevents, got " + sensitive.phase()));
    }

    @Test
    void theCounterPhaseOffsetIsReadFromTheTuning() {
        // A quarter-cycle offset is biomechanically wrong and that is the point: the right leg must sit
        // where the configured offset puts it, not half a cycle away because 0.5 was left in the code.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleSolver quartered =
                new WalkCycleSolver(calibrating().counterPhaseCycles(0.25F).build(), IK_TOLERANCE);
        final WalkCycleState gait = (WalkCycleState) quartered.createState(binding);
        final Pose atPhase = new Pose(rig.boneCount());
        final Pose aQuarterLater = new Pose(rig.boneCount());
        final Pose aHalfLater = new Pose(rig.boneCount());

        final float phase = 0.1F;
        evaluateWith(quartered, binding, profile(), gait, atPhase, phase, 1F);
        evaluateWith(quartered, binding, profile(), gait, aQuarterLater, phase + 0.25F, 1F);
        evaluateWith(quartered, binding, profile(), gait, aHalfLater, phase + 0.5F, 1F);

        assertAll(
                () -> assertEquals(aQuarterLater.pitch(LEFT_THIGH), atPhase.pitch(RIGHT_THIGH), 1.0E-5F,
                        "the right leg must sit a quarter cycle ahead, as configured"),
                () -> assertNotEquals(aHalfLater.pitch(LEFT_THIGH), atPhase.pitch(RIGHT_THIGH),
                        "and emphatically not half a cycle ahead"),
                // The arms share the one offset, in the opposite direction, so they move with it rather
                // than staying at a half.
                () -> assertEquals(atPhase.pitch(LEFT_ARM), aQuarterLater.pitch(RIGHT_ARM), 1.0E-5F),
                () -> assertNotEquals(atPhase.pitch(LEFT_ARM), aHalfLater.pitch(RIGHT_ARM)));
    }

    @Test
    void everyCalibratedLimbAngleIsScaledByTheTuning() {
        // Doubling the three limb amplitudes must double the three limb outputs and nothing else, which
        // is only true if each is read from the tuning at evaluation time.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleSolver loud = new WalkCycleSolver(calibrating()
                .legSwingRadians(1.40F)
                .armSwingRadians(1.10F)
                .forearmCurlRadians(0.70F)
                .build(), IK_TOLERANCE);
        final LocomotionBinding armed = kneed(rig);

        final Pose quiet = new Pose(rig.boneCount());
        final Pose doubled = new Pose(rig.boneCount());
        evaluate(armed, profile(), state(armed), quiet, 0.1F, 1F);
        evaluateWith(loud, armed, profile(), (WalkCycleState) loud.createState(armed), doubled, 0.1F, 1F);

        final Pose quietLeg = new Pose(rig.boneCount());
        final Pose doubledLeg = new Pose(rig.boneCount());
        evaluate(binding, profile(), state(binding), quietLeg, 0.1F, 1F);
        evaluateWith(loud, binding, profile(), (WalkCycleState) loud.createState(binding), doubledLeg,
                0.1F, 1F);

        assertAll(
                () -> assertTrue(Math.abs(quietLeg.pitch(LEFT_THIGH)) > 0.01F,
                        "the baseline must swing visibly"),
                () -> assertEquals(2F * quietLeg.pitch(LEFT_THIGH), doubledLeg.pitch(LEFT_THIGH),
                        1.0E-5F, "a one-piece leg swings by the configured amplitude"),
                () -> assertEquals(2F * quiet.pitch(LEFT_ARM), doubled.pitch(LEFT_ARM), 1.0E-5F,
                        "and so does an arm"),
                () -> assertEquals(2F * quiet.pitch(LEFT_FOREARM), doubled.pitch(LEFT_FOREARM), 1.0E-5F,
                        "and an elbow curl"));
    }

    @Test
    void everyCalibratedTorsoAngleIsScaledByTheTuning() {
        // The torso's pitch is four configured terms summed — crouch, lean, bob and breath — so doubling
        // all four must double the sum. Any one of them left as a constant breaks the ratio.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver loud = new WalkCycleSolver(calibrating()
                .sneakTorsoPitchRadians(0.70F)
                .torsoLeanRadians(0.20F)
                .torsoBobRadians(0.070F)
                .torsoSwayRadians(0.12F)
                .torsoTwistRadians(0.16F)
                .breathRadians(0.04F)
                .build(), IK_TOLERANCE);

        final Pose quiet = new Pose(rig.boneCount());
        final Pose doubled = new Pose(rig.boneCount());
        final WalkCycleState quietGait = state(binding);
        final WalkCycleState loudGait = (WalkCycleState) loud.createState(binding);

        // A quarter of a breath in, so the breathing term is at its peak rather than at a zero crossing,
        // and fully crouched, so the crouch term is in play. Both blends are configured identically in
        // the two tunings, so they ramp by the same amount under the evaluation delta.
        for (final WalkCycleState gait : new WalkCycleState[]{quietGait, loudGait}) {
            gait.elapsedSeconds = 1F;
            gait.sneakBlend = 1F;
            gait.setSneaking(true);
        }
        evaluate(binding, profile(), quietGait, quiet, 0.1F, 1F);
        evaluateWith(loud, binding, profile(), loudGait, doubled, 0.1F, 1F);

        assertAll(
                () -> assertTrue(Math.abs(quiet.pitch(TORSO)) > 0.1F,
                        "the baseline torso must pitch visibly, got " + quiet.pitch(TORSO)),
                () -> assertEquals(2F * quiet.pitch(TORSO), doubled.pitch(TORSO), 1.0E-5F,
                        "crouch, lean, bob and breath are all configured"),
                () -> assertEquals(2F * quiet.roll(TORSO), doubled.roll(TORSO), 1.0E-5F,
                        "and the weight-shift roll"),
                () -> assertEquals(2F * quiet.yaw(TORSO), doubled.yaw(TORSO), 1.0E-5F,
                        "and the shoulder twist"),
                // The head cancels a fraction of the torso, so it must double with it.
                () -> assertEquals(2F * quiet.pitch(HEAD), doubled.pitch(HEAD), 1.0E-5F));
    }

    @Test
    void theBobHarmonicIsReadFromTheTuning() {
        // At the configured 2 the bob repeats twice per cycle, so half a cycle leaves the pitch
        // unchanged — the property the suite above pins. At 1 the same half cycle must flip it instead,
        // which is the limp the derived value exists to avoid and the only way to tell the two apart.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver limping =
                new WalkCycleSolver(calibrating().torsoBobHarmonic(1F).build(), IK_TOLERANCE);
        final GaitProfile noBreath = GaitProfile.builder(CALIBRATED)
                .breathDepth(0F)
                .sneakDepth(0F)
                .build();
        final WalkCycleState gait = (WalkCycleState) limping.createState(binding);
        final Pose first = new Pose(rig.boneCount());
        final Pose second = new Pose(rig.boneCount());

        evaluateWith(limping, binding, noBreath, gait, first, 0.13F, 1F);
        evaluateWith(limping, binding, noBreath, gait, second, 0.63F, 1F);

        // The lean is a constant across the cycle, so the oscillating part is what has to flip sign.
        final float lean = CALIBRATED.torsoLeanRadians();
        assertAll(
                () -> assertEquals(-(first.pitch(TORSO) - lean), second.pitch(TORSO) - lean, 1.0E-5F,
                        "a bob at stride frequency flips across half a cycle"),
                () -> assertTrue(Math.abs(first.pitch(TORSO) - lean) > 1.0E-3F,
                        "and the bob must actually be present"));
    }

    @Test
    void theAmplitudeSaturationSpeedIsReadFromTheTuning() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        // An Olympic sprinter's pace: walking is then less than a quarter of a full stride.
        // The sprint reference has to move with it: it is required to sit above walking pace, and a
        // reference below the speed that saturates the stride describes no band at all.
        final WalkCycleSolver sprinting = new WalkCycleSolver(
                calibrating()
                        .fullStrideSpeedBlocksPerTick(1F)
                        .sprintSpeedBlocksPerTick(1.3F)
                        .build(), IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState shipped = state(binding);
        walk(binding, shipped, pose, WALK, 20);

        final WalkCycleState lazy = (WalkCycleState) sprinting.createState(binding);
        for (int tick = 0; tick < 20; tick++) {
            stepWith(sprinting, binding, profile(), lazy, pose, WALK, TICK);
        }

        assertAll(
                () -> assertEquals(1F, shipped.amplitude(), 1.0E-6F,
                        "walking pace saturates the shipped stride"),
                () -> assertEquals(WALK, lazy.amplitude(), 1.0E-5F,
                        "and is 0.215 of one that saturates at a block per tick"));
    }

    @Test
    void theBlendDurationsAreReadFromTheTuning() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver sluggish = new WalkCycleSolver(calibrating()
                .sneakBlendSeconds(0.5F)
                .airborneBlendSeconds(0.75F)
                .build(), IK_TOLERANCE);

        assertAll(
                // Four ticks at the shipped 0.18 s and 0.20 s; ten and fifteen at half a second and
                // three quarters. A blend that still crossed in four would be reading a constant.
                () -> assertEquals(4, ticksToCross(this.solver, binding, true)),
                () -> assertEquals(4, ticksToCross(this.solver, binding, false)),
                () -> assertEquals(10, ticksToCross(sluggish, binding, true),
                        "the crouch must cross in the configured half second"),
                () -> assertEquals(15, ticksToCross(sluggish, binding, false),
                        "and the airborne blend in its own configured three quarters"));
    }

    /** Ticks a stance blend takes to cross from standing to fully blended under {@code solver}. */
    private static int ticksToCross(
            WalkCycleSolver solver,
            LocomotionBinding binding,
            boolean sneak
    ) {
        final Pose pose = new Pose(binding.boneCount());
        final WalkCycleState gait = (WalkCycleState) solver.createState(binding);
        if (sneak) {
            gait.setSneaking(true);
        } else {
            gait.setAirborne(true);
        }

        int ticks = 0;
        while ((sneak ? gait.sneakBlend() : gait.airborneBlend()) < 1F && ticks < 500) {
            stepWith(solver, binding, profile(), gait, pose, 0F, TICK);
            ticks++;
        }
        return ticks;
    }

    @Test
    void theStepAndSpeedGuardsAreReadFromTheTuning() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver guarded = new WalkCycleSolver(calibrating()
                .maxStepSeconds(0.05F)
                .maxGaitSpeedBlocksPerTick(0.1F)
                .build(), IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());

        // A one-second step at walking pace, against a tuning that honours a twentieth of a second and
        // a tenth of a block per tick. The phase must advance by 0.1 * 0.05 * 20 / 1.3 of a cycle.
        final WalkCycleState clamped = (WalkCycleState) guarded.createState(binding);
        stepWith(guarded, binding, profile(), clamped, pose, WALK, 1F);

        final WalkCycleState shipped = state(binding);
        step(binding, shipped, pose, WALK, 1F);

        // The third guard is unreachable at the shipped clamps, by design — it exists so the other two
        // cannot be weakened independently and reintroduce the overflow. Configure it low enough to bite
        // and it has to be the thing that decides the phase.
        final WalkCycleSolver hobbled = new WalkCycleSolver(
                calibrating().maxPhaseAdvancePerStep(0.01F).build(), IK_TOLERANCE);
        final WalkCycleState capped = (WalkCycleState) hobbled.createState(binding);
        stepWith(hobbled, binding, profile(), capped, pose, WALK, 1F);

        assertAll(
                () -> assertEquals(0.1F * 0.05F * 20F / CALIBRATED.defaultStrideLength(),
                        clamped.phase(), 1.0E-6F,
                        "both configured guards must bite, got " + clamped.phase()),
                // The shipped guards clamp the same step to 0.25 s at full walking pace.
                () -> assertEquals(WALK * 0.25F * 20F / CALIBRATED.defaultStrideLength(),
                        shipped.phase(), 1.0E-6F),
                () -> assertEquals(0.01F, capped.phase(), 1.0E-7F,
                        "a hundredth of a cycle is all the configured advance ceiling permits"));
    }

    @Test
    void theRestingLegExtensionIsReadFromTheTuning() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver crouched =
                new WalkCycleSolver(calibrating().restingLegExtension(0.6F).build(), IK_TOLERANCE);
        final Pose shippedPose = new Pose(rig.boneCount());
        final Pose crouchedPose = new Pose(rig.boneCount());

        evaluate(binding, profile(), state(binding), shippedPose, 0F, 0F);
        evaluateWith(crouched, binding, profile(), (WalkCycleState) crouched.createState(binding),
                crouchedPose, 0F, 0F);

        final double reach = binding.legReach();
        final double a = binding.thighLength();
        final double b = binding.shinLength();
        final double radius = 0.6D * reach;
        final double expectedBend =
                Math.PI - Math.acos((a * a + b * b - radius * radius) / (2 * a * b));

        assertAll(
                () -> assertEquals((float) expectedBend, crouchedPose.pitch(LEFT_SHIN), 1.0E-4F,
                        "a 0.6 extension must put the knee where the law of cosines says"),
                () -> assertTrue(crouchedPose.pitch(LEFT_SHIN) > shippedPose.pitch(LEFT_SHIN) + 0.5F,
                        "which is a far deeper bend than 0.95 gives"));
    }

    @Test
    void theFootTargetGeometryIsReadFromTheTuning() {
        // Four values place the IK target, and each is checked by configuring it to the setting that
        // removes its own contribution — the one outcome a leftover constant could never produce.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final float reach = binding.legReach();

        // strideToFootReach of zero leaves no fore-aft excursion, so the two mid-swing phases — where
        // the lift is zero — have to agree exactly. They differ under the shipped quarter.
        final GaitTuning noReach = calibrating().strideToFootReach(0F).build();
        final float flatLead = evaluateUnder(noReach, binding, 0.25F, 1F).pitch(LEFT_THIGH);
        final float flatTrail = evaluateUnder(noReach, binding, 0.75F, 1F).pitch(LEFT_THIGH);

        // footLiftFraction of zero leaves the planted leg's knee at its resting bend at every phase.
        final GaitTuning noLift = calibrating().footLiftFraction(0F).build();
        final float unlifted = evaluateUnder(noLift, binding, 0F, 1F).pitch(LEFT_SHIN);
        final float unliftedRest = evaluateUnder(noLift, binding, 0F, 0F).pitch(LEFT_SHIN);

        // maxIkReachFraction of a half pulls the resting target in to 0.6 blocks, where equal segments
        // give a cosine of a half and therefore a 120-degree bend.
        final GaitTuning shortReach = calibrating().maxIkReachFraction(0.5F).build();
        final float clamped = evaluateUnder(shortReach, binding, 0F, 0F).pitch(LEFT_SHIN);

        // minIkDownFraction raised to the resting extension floors the lift away entirely, so a swinging
        // foot sits exactly where a standing one does.
        final GaitTuning highFloor = calibrating().minIkDownFraction(0.95F).build();
        final float floored = evaluateUnder(highFloor, binding, 0F, 1F).pitch(LEFT_SHIN);
        final float shippedLift = evaluateUnder(CALIBRATED, binding, 0F, 1F).pitch(LEFT_SHIN);
        final float shippedRest = evaluateUnder(CALIBRATED, binding, 0F, 0F).pitch(LEFT_SHIN);

        assertAll(
                () -> assertEquals(flatLead, flatTrail, 1.0E-6F,
                        "with no stride-to-reach the foot cannot lead or trail"),
                () -> assertNotEquals(
                        evaluateUnder(CALIBRATED, binding, 0.25F, 1F).pitch(LEFT_THIGH),
                        evaluateUnder(CALIBRATED, binding, 0.75F, 1F).pitch(LEFT_THIGH),
                        "whereas the shipped quarter very much does"),
                () -> assertEquals(unliftedRest, unlifted, 1.0E-6F,
                        "with no lift the knee holds its resting bend through the swing"),
                () -> assertEquals((float) (2 * Math.PI / 3), clamped, 1.0E-4F,
                        "a reach ceiling of a half puts the resting knee at 120 degrees, got " + clamped),
                () -> assertEquals(shippedRest, floored, 1.0E-6F,
                        "a floor at the resting extension removes the lift"),
                () -> assertNotEquals(shippedRest, shippedLift,
                        "while the shipped floor lets it through"));
    }

    @Test
    void theBreathRateAndAmplitudeRampAreReadFromTheTuning() {
        final Rig rig = humanoid();
        final LocomotionBinding legless = torsoOnly(rig);
        final LocomotionBinding binding = kneed(rig);

        // One second in, the shipped quarter hertz is at the peak of its breath and a half hertz is at a
        // zero crossing. Nothing but the configured rate distinguishes the two.
        final WalkCycleSolver slower =
                new WalkCycleSolver(calibrating().breathRateHz(0.5F).build(), IK_TOLERANCE);
        final Pose peaked = new Pose(rig.boneCount());
        final Pose crossing = new Pose(rig.boneCount());
        final WalkCycleState shippedBreath = state(legless);
        final WalkCycleState halfHertz = (WalkCycleState) slower.createState(legless);
        shippedBreath.elapsedSeconds = 1F;
        halfHertz.elapsedSeconds = 1F;
        evaluate(legless, profile(), shippedBreath, peaked, 0F, 0F);
        evaluateWith(slower, legless, profile(), halfHertz, crossing, 0F, 0F);

        // And the amplitude ramp crosses in the configured time, not in the shipped 0.15 s.
        final WalkCycleSolver gradual =
                new WalkCycleSolver(calibrating().amplitudeBlendSeconds(0.5F).build(), IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());
        final WalkCycleState brisk = state(binding);
        final WalkCycleState lazy = (WalkCycleState) gradual.createState(binding);
        step(binding, brisk, pose, WALK, TICK);
        stepWith(gradual, binding, profile(), lazy, pose, WALK, TICK);

        assertAll(
                () -> assertEquals(CALIBRATED.breathRadians(), peaked.pitch(TORSO), 1.0E-5F,
                        "a quarter hertz peaks exactly one second in"),
                () -> assertEquals(0F, crossing.pitch(TORSO), 1.0E-5F,
                        "and a half hertz has come back to zero by then"),
                () -> assertEquals(TICK / 0.15F, brisk.amplitude(), 1.0E-5F,
                        "one tick of the shipped ramp"),
                () -> assertEquals(TICK / 0.5F, lazy.amplitude(), 1.0E-6F,
                        "against one tick of a configured half second"));
    }

    /** The pose one tuning produces at a chosen phase and amplitude, on a solver of its own. */
    private static Pose evaluateUnder(
            GaitTuning tuning,
            LocomotionBinding binding,
            float phase,
            float amplitude
    ) {
        final WalkCycleSolver solver = new WalkCycleSolver(tuning, IK_TOLERANCE);
        final Pose pose = new Pose(binding.boneCount());
        evaluateWith(solver, binding, profile(), (WalkCycleState) solver.createState(binding), pose,
                phase, amplitude);
        return pose;
    }

    @Test
    void theIkToleranceIsHandedToTheTwoBoneSolver() {
        // The solver holds the tolerance only to pass it on, so the one way to show it is not dropped is
        // to make the tolerance decide the answer. A resting extension of half the leg's reach puts the
        // foot target 0.6 blocks from the hip: a normal solve under the shipped guard, and a fully
        // folded limb under one wide enough to swallow it.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final GaitTuning halfExtended = calibrating()
                .restingLegExtension(0.5F)
                .minIkDownFraction(0F)
                .build();
        final WalkCycleSolver precise = new WalkCycleSolver(halfExtended, IK_TOLERANCE);
        final WalkCycleSolver blunt = new WalkCycleSolver(halfExtended, new IkTolerance(1F));

        final Pose precisePose = new Pose(rig.boneCount());
        final Pose bluntPose = new Pose(rig.boneCount());
        evaluateWith(precise, binding, profile(), (WalkCycleState) precise.createState(binding),
                precisePose, 0F, 0F);
        evaluateWith(blunt, binding, profile(), (WalkCycleState) blunt.createState(binding),
                bluntPose, 0F, 0F);

        assertAll(
                // Equal segments with the target at half the reach: cos of the joint is 0.5, so the bend
                // is 120 degrees and the thigh leans back by 60.
                () -> assertEquals((float) (2 * Math.PI / 3), precisePose.pitch(LEFT_SHIN), 1.0E-4F,
                        "the shipped tolerance must leave this an ordinary solve"),
                () -> assertEquals((float) (-Math.PI / 3), precisePose.pitch(LEFT_THIGH), 1.0E-4F),
                () -> assertEquals((float) Math.PI, bluntPose.pitch(LEFT_SHIN), 1.0E-4F,
                        "a tolerance of a whole block folds the limb flat instead"),
                () -> assertEquals(0F, bluntPose.pitch(LEFT_THIGH), 1.0E-6F));
    }

    @Test
    void theElapsedWrapIsDerivedFromTheConfiguredBreathRateAndCycleCount() {
        // Four breaths at one hertz wraps after four seconds instead of the shipped seventeen minutes,
        // which makes the wrap reachable inside a test — and proves the interval is derived rather than
        // fixed, since neither input alone would produce it.
        final Rig rig = humanoid();
        final LocomotionBinding legless = torsoOnly(rig);
        final WalkCycleSolver brisk = new WalkCycleSolver(
                calibrating().breathRateHz(1F).elapsedWrapBreathCycles(4).build(), IK_TOLERANCE);
        final WalkCycleState gait = (WalkCycleState) brisk.createState(legless);
        final Pose pose = new Pose(rig.boneCount());

        float highest = 0F;
        for (int tick = 0; tick < 400; tick++) {
            stepWith(brisk, legless, profile(), gait, pose, 0F, TICK);
            highest = Math.max(highest, gait.elapsedSeconds());
        }
        final float peak = highest;

        assertAll(
                () -> assertTrue(peak < 4F, "elapsed time must wrap at four seconds, got " + peak),
                () -> assertTrue(peak > 4F - 2F * TICK,
                        "and must actually reach it, got " + peak),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void theSettledAmplitudeIsReadFromTheTuning() {
        // The state carries its own copy of this one, so a stale constant there would be a configured
        // threshold that the emission layer never sees.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleSolver forgiving =
                new WalkCycleSolver(calibrating().settledAmplitude(1F).build(), IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());

        // Half walking pace, so the amplitude saturates below one and a threshold of one is above it.
        final WalkCycleState shipped = state(binding);
        walk(binding, shipped, pose, 0.1F, 20);

        final WalkCycleState lenient = (WalkCycleState) forgiving.createState(binding);
        for (int tick = 0; tick < 20; tick++) {
            stepWith(forgiving, binding, profile(), lenient, pose, 0.1F, TICK);
        }

        assertAll(
                () -> assertTrue(shipped.amplitude() > 0.4F,
                        "the stride must be expressed, got " + shipped.amplitude()),
                () -> assertFalse(shipped.isSettled(),
                        "a stride this strong is not settled at the shipped threshold"),
                () -> assertEquals(shipped.amplitude(), lenient.amplitude(), 1.0E-6F,
                        "the same stride, so the same amplitude"),
                () -> assertTrue(lenient.isSettled(),
                        "but a threshold of one calls everything below full amplitude settled"));
    }

    // ───────────────────────────── Feet ─────────────────────────────

    /**
     * The world pitch of a bone chain, which is the only thing that proves a counter-rotation works.
     *
     * <p>A pose is parent-relative, so each bone's own angle says nothing about where it is pointing.
     * Asserting on the foot's own value would pass for a foot that happens to be zero on a leg that is
     * folded double; asserting on the sum is asserting about the sole.
     */
    private static float chainPitch(Pose pose, int... bones) {
        float sum = 0F;
        for (final int bone : bones) {
            sum += pose.pitch(bone);
        }
        return sum;
    }

    @Test
    void aFootOnAKneedLegStaysFlatAgainstTheGroundThroughTheWholeStance() {
        // The defect this removes: without a foot bone the IK places the ankle and the foot inherits the
        // shin, so it drives into the ground at stance. Exactly flat matters rather than nearly flat — a
        // planted foot that rolls is a foot sliding through the floor.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedWithFeet(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        // Stance is the half of the cycle in which the lift's rectified cosine is zero: phase 0.25 to
        // 0.75 for the left leg, which travels at `cycle`.
        float largestChain = 0F;
        for (int sample = 0; sample <= 32; sample++) {
            final float phase = 0.25F + 0.5F * sample / 32F;
            evaluate(binding, profile(), gait, pose, phase, 1F);

            final float sole = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);
            assertEquals(0F, sole, 1.0E-6F,
                    "phase " + phase + ": the sole must be flat through stance, got " + sole);
            largestChain =
                    Math.max(largestChain, Math.abs(chainPitch(pose, LEFT_THIGH, LEFT_SHIN)));
        }

        // The chain being cancelled swings across the stance and passes through level somewhere in the
        // middle of it, so the claim is about its excursion rather than about every sample: were it zero
        // throughout, the flatness above would be a leg hanging straight rather than a cancellation.
        assertTrue(largestChain > 0.2F,
                "the ankle's inherited rotation must be substantial somewhere in the stance, got "
                        + largestChain);
    }

    @Test
    void aRestingModelStandsWithAFlatFoot() {
        // The reason the counter-rotation is not scaled by the gait's weight. A resting kneed leg stands
        // at its resting extension, so its chain sums to half the resting knee bend even at zero
        // amplitude; fading the cancellation out with the cycle would pitch a standing model's soles
        // into the floor, which is the very thing being fixed.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedWithFeet(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        evaluate(binding, profile(), gait, pose, 0F, 0F);
        final float ankleChain = chainPitch(pose, LEFT_THIGH, LEFT_SHIN);

        assertAll(
                () -> assertTrue(ankleChain > 0.1F,
                        "a resting kneed leg's chain is not flat to begin with, got " + ankleChain),
                () -> assertEquals(-ankleChain, pose.pitch(LEFT_FOOT), 1.0E-6F,
                        "so the foot has to cancel all of it"),
                () -> assertEquals(0F, chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT), 1.0E-6F),
                () -> assertEquals(0F, chainPitch(pose, RIGHT_THIGH, RIGHT_SHIN, RIGHT_FOOT), 1.0E-6F,
                        "both feet, whichever phase each leg is at"));
    }

    @Test
    void theFootRollsToToeOffAndBackThroughHeelStrikeOverTheSwing() {
        // The lobe peaks a quarter of the way into each half of the swing, where the product of the
        // rectified cosine and sine is a half — which the solver's normaliser doubles, so the configured
        // angle is the angle actually reached.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedWithFeet(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        evaluate(binding, profile(), gait, pose, 0.875F, 1F);
        final float toeOff = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);

        evaluate(binding, profile(), gait, pose, 0.125F, 1F);
        final float heelStrike = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);

        evaluate(binding, profile(), gait, pose, 0F, 1F);
        final float midSwing = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);

        assertAll(
                () -> assertEquals(CALIBRATED.footToeOffRadians(), toeOff, 1.0E-5F,
                        "the push-off must reach exactly the configured plantarflexion"),
                () -> assertEquals(-CALIBRATED.footHeelStrikeRadians(), heelStrike, 1.0E-5F,
                        "and the landing exactly the configured dorsiflexion, the other way"),
                () -> assertTrue(toeOff > 0F && heelStrike < 0F, "the two must oppose each other"),
                () -> assertEquals(0F, midSwing, 1.0E-6F,
                        "and the foot passes back through flat between them"));
    }

    @Test
    void aFootOnAOnePieceLegCounterRotatesAgainstTheThighAlone() {
        // The leg has no shin at all, so the chain the foot cancels is one bone long. A foot that
        // required a shin would have refused this binding; one that assumed a shin would cancel a stale
        // zero and leave the sole pitched by the whole thigh swing.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePieceWithFeet(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        assertFalse(binding.isKneed(GaitRole.LEFT_THIGH), "this fixture must really have no shin");

        // A quarter cycle in, where a one-piece thigh is at its peak swing — the worst case for a foot
        // that inherited it.
        evaluate(binding, profile(), gait, pose, 0.25F, 1F);
        final float thigh = pose.pitch(LEFT_THIGH);
        final float foot = pose.pitch(LEFT_FOOT);

        float worstStance = 0F;
        for (int sample = 0; sample <= 16; sample++) {
            final float phase = 0.25F + 0.5F * sample / 16F;
            evaluate(binding, profile(), gait, pose, phase, 1F);
            worstStance = Math.max(worstStance,
                    Math.abs(chainPitch(pose, LEFT_THIGH, LEFT_FOOT)));
        }
        final float stance = worstStance;

        assertAll(
                () -> assertEquals(CALIBRATED.legSwingRadians(), thigh, 1.0E-5F,
                        "the thigh must be at its full swing here"),
                () -> assertEquals(-thigh, foot, 1.0E-6F, "and the foot must cancel exactly that"),
                () -> assertEquals(0F, stance, 1.0E-6F, "flat through stance with no shin either"),
                // The shin bone is a real bone in this rig; the gait does not drive it, so it must be
                // left exactly as it was found.
                () -> assertEquals(0F, pose.pitch(LEFT_SHIN),
                        "an unbound shin stays unwritten even though a foot hangs below it"));
    }

    @Test
    void anUnboundFootIsLeftEntirelyAlone() {
        final Rig rig = humanoid();
        final LocomotionBinding footless = kneed(rig);
        final WalkCycleState gait = state(footless);
        final Pose pose = new Pose(rig.boneCount());

        pose.set(LEFT_FOOT, 0.375F, -0.5F, 0.625F);
        walk(footless, gait, pose, WALK, 40);

        assertAll(
                () -> assertFalse(footless.ownsBone(LEFT_FOOT)),
                () -> assertEquals(0.375F, pose.pitch(LEFT_FOOT)),
                () -> assertEquals(-0.5F, pose.yaw(LEFT_FOOT)),
                () -> assertEquals(0.625F, pose.roll(LEFT_FOOT)),
                () -> assertEquals(0F, pose.pitch(RIGHT_FOOT)));
    }

    @Test
    void theAnkleAnglesAreReadFromTheTuning() {
        // Two deliberately wrong-looking angles, one far above the shipped value and one far below, and
        // the heel angle made the larger of the pair — which no default would ever be.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedWithFeet(rig);
        final GaitTuning odd = calibrating()
                .footToeOffRadians(0.09F)
                .footHeelStrikeRadians(1.21F)
                .build();
        final WalkCycleSolver other = new WalkCycleSolver(odd, IK_TOLERANCE);
        final Pose pose = new Pose(rig.boneCount());
        final WalkCycleState gait = (WalkCycleState) other.createState(binding);

        evaluateWith(other, binding, profile(), gait, pose, 0.875F, 1F);
        final float toeOff = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);

        evaluateWith(other, binding, profile(), gait, pose, 0.125F, 1F);
        final float heelStrike = chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT);

        // And zeroing both leaves a foot that is flat for the entire cycle rather than only at stance.
        final WalkCycleSolver flat =
                new WalkCycleSolver(calibrating()
                        .footToeOffRadians(0F)
                        .footHeelStrikeRadians(0F)
                        .build(), IK_TOLERANCE);
        final WalkCycleState flatGait = (WalkCycleState) flat.createState(binding);
        float worst = 0F;
        for (int sample = 0; sample < 64; sample++) {
            evaluateWith(flat, binding, profile(), flatGait, pose, sample / 64F, 1F);
            worst = Math.max(worst, Math.abs(chainPitch(pose, LEFT_THIGH, LEFT_SHIN, LEFT_FOOT)));
        }
        final float worstFlat = worst;

        assertAll(
                () -> assertEquals(0.09F, toeOff, 1.0E-5F),
                () -> assertEquals(-1.21F, heelStrike, 1.0E-5F),
                () -> assertEquals(0F, worstFlat, 1.0E-6F,
                        "zero ankle angles mean a sole that is level all cycle, got " + worstFlat));
    }

    // ───────────────────────────── Hands ─────────────────────────────

    @Test
    void aHandFollowsItsParentAtZeroStabilisationAndIsHeldLevelAtOne() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedWithHands(rig);
        final WalkCycleState gait = state(binding);
        final Pose loose = new Pose(rig.boneCount());
        final Pose held = new Pose(rig.boneCount());
        final Pose between = new Pose(rig.boneCount());

        final GaitProfile relaxed = GaitProfile.builder(CALIBRATED).handStabilise(0F).build();
        final GaitProfile carrying = GaitProfile.builder(CALIBRATED).handStabilise(1F).build();
        final GaitProfile halfway = GaitProfile.builder(CALIBRATED).handStabilise(0.5F).build();

        // A tenth of a cycle in, so the arm swing and the elbow curl are both away from zero and the
        // chain the hand is cancelling is genuinely non-zero.
        evaluate(binding, relaxed, gait, loose, 0.1F, 1F);
        evaluate(binding, carrying, gait, held, 0.1F, 1F);
        evaluate(binding, halfway, gait, between, 0.1F, 1F);

        final float armChain = chainPitch(loose, LEFT_ARM, LEFT_FOREARM);

        assertAll(
                () -> assertTrue(Math.abs(armChain) > 0.05F,
                        "the arm above the hand must be doing something, got " + armChain),
                () -> assertEquals(0, Float.floatToRawIntBits(loose.pitch(LEFT_HAND)),
                        "zero stabilisation is exactly the pose's own zero: the hand follows its parent"),
                () -> assertEquals(0F, chainPitch(held, LEFT_ARM, LEFT_FOREARM, LEFT_HAND), 1.0E-6F,
                        "full stabilisation holds the hand level in the world"),
                () -> assertEquals(-armChain, held.pitch(LEFT_HAND), 1.0E-6F),
                () -> assertEquals(-0.5F * armChain, between.pitch(LEFT_HAND), 1.0E-6F,
                        "and a half lands exactly halfway between the two"),
                () -> assertTrue(Math.abs(between.pitch(LEFT_HAND)) < Math.abs(held.pitch(LEFT_HAND))),
                // The right hand is on the opposing arm, so it must cancel that arm's chain instead.
                () -> assertEquals(-chainPitch(held, RIGHT_ARM, RIGHT_FOREARM), held.pitch(RIGHT_HAND),
                        1.0E-6F));
    }

    @Test
    void aHandOnAOnePieceArmCancelsTheUpperArmAlone() {
        // No forearm bound at all, so the chain is one bone long — the arm equivalent of a boot on a
        // one-piece leg, and the reason nothing here may assume a hand has an elbow above it.
        final Rig rig = humanoid();
        final LocomotionBinding binding = LocomotionBinding.builder(rig, BINDING_BOUNDS)
                .boneIndex(GaitRole.LEFT_UPPER_ARM, LEFT_ARM)
                .boneIndex(GaitRole.LEFT_HAND, LEFT_HAND)
                .build();
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());
        final GaitProfile carrying = GaitProfile.builder(CALIBRATED).handStabilise(1F).build();

        evaluate(binding, carrying, gait, pose, 0.25F, 1F);

        assertAll(
                () -> assertEquals(CALIBRATED.armSwingRadians(), Math.abs(pose.pitch(LEFT_ARM)), 1.0E-5F,
                        "the arm must be at its full swing here"),
                () -> assertEquals(-pose.pitch(LEFT_ARM), pose.pitch(LEFT_HAND), 1.0E-6F),
                () -> assertEquals(0F, pose.pitch(LEFT_FOREARM),
                        "an unbound forearm stays unwritten even with a hand below it"));
    }

    @Test
    void anUnboundHandIsLeftEntirelyAlone() {
        final Rig rig = humanoid();
        final LocomotionBinding handless = kneed(rig);
        final WalkCycleState gait = state(handless);
        final Pose pose = new Pose(rig.boneCount());

        pose.set(RIGHT_HAND, -0.75F, 0.25F, 0.125F);
        walk(handless, gait, pose, WALK, 40);

        assertAll(
                () -> assertFalse(handless.ownsBone(RIGHT_HAND)),
                () -> assertEquals(-0.75F, pose.pitch(RIGHT_HAND)),
                () -> assertEquals(0.25F, pose.yaw(RIGHT_HAND)),
                () -> assertEquals(0.125F, pose.roll(RIGHT_HAND)));
    }

    // ───────────────────────────── The authored facing ─────────────────────────────

    @Test
    void aHalfTurnOfFacingIsTheSameAsWalkingTheOtherWay() {
        // The property the facing offset exists for, stated as the only thing that can be observed: a
        // rig modelled backwards, walking forwards, must pose exactly as a rig modelled forwards walking
        // backwards. Bit-for-bit, because both are the same closed-form evaluation of the same phase.
        final Rig rig = humanoid();
        final LocomotionBinding reversedRig = kneedFacing(rig, 180F);
        final LocomotionBinding plainRig = kneedFacing(rig, 0F);

        final Pose reversedPose = new Pose(rig.boneCount());
        final Pose backwardPose = new Pose(rig.boneCount());
        final Pose forwardPose = new Pose(rig.boneCount());

        final WalkCycleState reversed = state(reversedRig);
        final WalkCycleState backward = state(plainRig);
        final WalkCycleState forward = state(plainRig);

        walk(reversedRig, reversed, reversedPose, WALK, 30);
        walk(plainRig, backward, backwardPose, -WALK, 30);
        walk(plainRig, forward, forwardPose, WALK, 30);

        assertAll(
                () -> assertSamePose(backwardPose, reversedPose,
                        "a reversed rig walking forward is a plain rig walking backward"),
                () -> assertEquals(Float.floatToRawIntBits(backward.phase()),
                        Float.floatToRawIntBits(reversed.phase()),
                        "the phase advances by distance, so it cannot depend on the facing either"),
                () -> assertEquals(Float.floatToRawIntBits(forward.phase()),
                        Float.floatToRawIntBits(reversed.phase()),
                        "nor on the direction of travel"),
                () -> assertEquals(-1F, reversed.strideBias, 1.0E-6F),
                () -> assertEquals(1F, forward.strideBias, 1.0E-6F),
                // Non-vacuous: the two really do look different, so the equality above is a claim.
                () -> assertNotEquals(forwardPose.pitch(LEFT_THIGH), reversedPose.pitch(LEFT_THIGH),
                        "otherwise the facing offset would be changing nothing at all"));
    }

    @Test
    void aQuarterTurnOfFacingRemovesTheForeAftExcursionRatherThanSlidingTheFeet() {
        // There is no strafing gait in this layer, so a model travelling straight across its own forward
        // axis must step in place: the lift still happens, the fore-aft excursion does not. Striding
        // forward while moving sideways is a foot sliding, which is the one thing a gait may not do.
        final Rig rig = humanoid();
        final LocomotionBinding sideways = kneedFacing(rig, 90F);
        final LocomotionBinding ahead = kneedFacing(rig, 0F);
        final Pose sidewaysPose = new Pose(rig.boneCount());
        final Pose aheadPose = new Pose(rig.boneCount());

        final WalkCycleState strafing = state(sideways);
        final WalkCycleState walking = state(ahead);
        final PhysicsDriver forward = new PhysicsDriver(WALK, 0F, 0F, 0F);
        walk(sideways, strafing, sidewaysPose, WALK, 30);
        walk(ahead, walking, aheadPose, WALK, 30);

        // An eighth of a cycle in, where the left leg's lift and the left arm's swing are both away
        // from zero — and driven rather than evaluated, because a standing driver has no direction for
        // the facing to rotate.
        evaluateDriven(sideways, profile(), strafing, sidewaysPose, 0.125F, forward);
        evaluateDriven(ahead, profile(), walking, aheadPose, 0.125F, forward);

        assertAll(
                () -> assertEquals(0F, strafing.strideBias, 1.0E-6F,
                        "travel is perpendicular to the rig's forward axis"),
                () -> assertEquals(1F, walking.amplitude(), 1.0E-6F,
                        "yet the amplitude is unchanged: a rotation does not alter a magnitude"),
                () -> assertEquals(walking.amplitude(), strafing.amplitude(), 1.0E-6F),
                () -> assertEquals(0F, sidewaysPose.pitch(LEFT_ARM), 1.0E-6F,
                        "no fore-aft swing on an arm"),
                () -> assertNotEquals(0F, aheadPose.pitch(LEFT_ARM)),
                // The lift is driven by the cosine, which the bias does not touch, so the legs still
                // step rather than freezing.
                () -> assertNotEquals(sidewaysPose.pitch(LEFT_SHIN), sidewaysPose.pitch(RIGHT_SHIN),
                        "the two legs are still half a cycle apart in their lift"),
                () -> assertTrue(sidewaysPose.isFinite()));
    }

    @Test
    void theFacingLeavesAStandingModelExactlyWhereItWas() {
        // The bias falls back to the identity below the deadband, so the rest pose a model settles into
        // cannot depend on how its rig was authored. Were it to fall back to zero instead, a standing
        // model would hold a different pose from one that had just stopped walking.
        final Rig rig = humanoid();
        final LocomotionBinding reversedRig = kneedFacing(rig, 180F);
        final LocomotionBinding plainRig = kneedFacing(rig, 0F);
        final Pose reversedPose = new Pose(rig.boneCount());
        final Pose plainPose = new Pose(rig.boneCount());

        walk(reversedRig, state(reversedRig), reversedPose, 0F, 40);
        walk(plainRig, state(plainRig), plainPose, 0F, 40);

        assertSamePose(plainPose, reversedPose, "two standing models, differently authored");
    }

    // ───────────────────────── Walk and sprint amplitudes ─────────────────────────

    @Test
    void aOnePieceLegSwingsBetweenTheTwoConfiguredAmplitudes() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        // A quarter cycle in, where the sine is one, so the thigh's pitch *is* the amplitude.
        evaluateSprinting(binding, profile(), gait, pose, 0.25F, 0F);
        final float walking = pose.pitch(LEFT_THIGH);

        evaluateSprinting(binding, profile(), gait, pose, 0.25F, 1F);
        final float sprinting = pose.pitch(LEFT_THIGH);

        evaluateSprinting(binding, profile(), gait, pose, 0.25F, 0.5F);
        final float between = pose.pitch(LEFT_THIGH);

        assertAll(
                () -> assertEquals(CALIBRATED.legSwingRadians(), walking, 1.0E-4F,
                        "at a sprint blend of zero the walk amplitude is used, unchanged"),
                () -> assertEquals(CALIBRATED.sprintLegSwingRadians(), sprinting, 1.0E-4F,
                        "and at one the sprint amplitude, which is a different number"),
                () -> assertEquals(
                        0.5F * (CALIBRATED.legSwingRadians() + CALIBRATED.sprintLegSwingRadians()),
                        between, 1.0E-4F, "halfway blends halfway: it is an interpolation, not a step"),
                () -> assertTrue(sprinting > walking, "the shipped sprint reaches further than the walk"));
    }

    @Test
    void walkingPaceLeavesTheSprintBlendAtExactlyZero() {
        // The requirement that the second amplitude changes nothing below the speed it belongs to. Bits,
        // not a tolerance: a blend that crept up by an epsilon per tick would widen a walking model's
        // stride over a minute of walking and nothing would point at why.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final Pose pose = new Pose(rig.boneCount());

        final WalkCycleState walker = state(binding);
        walk(binding, walker, pose, WALK, 200);
        final float walkingSwing = pose.pitch(LEFT_THIGH);

        final WalkCycleState sprinter = state(binding);
        walk(binding, sprinter, pose, SPRINT, 200);

        final WalkCycleState dawdler = state(binding);
        walk(binding, dawdler, pose, 0.5F * WALK, 200);

        assertAll(
                () -> assertEquals(0, Float.floatToRawIntBits(walker.sprintBlend),
                        "walking pace is the floor of the sprint band, so the blend must be a hard zero"),
                () -> assertEquals(0, Float.floatToRawIntBits(dawdler.sprintBlend),
                        "and below it, more so"),
                () -> assertEquals(1F, sprinter.sprintBlend,
                        "sprinting pace saturates it"),
                // Pinned against the amplitude the layer shipped with, so a regression in the walk case
                // is visible as a number rather than as a relation.
                () -> assertEquals(CALIBRATED.legSwingRadians() * (float) Math.sin(
                                2 * Math.PI * walker.phase()), walkingSwing, 1.0E-5F,
                        "a walking one-piece leg swings by exactly legSwingRadians, as it always did"));
    }

    @Test
    void theSprintBlendIsProportionalBetweenTheTwoConfiguredSpeeds() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final Pose pose = new Pose(rig.boneCount());

        // Halfway between the two reference speeds.
        final float midway = 0.5F * (WALK + SPRINT);
        final WalkCycleState trotting = state(binding);
        walk(binding, trotting, pose, midway, 200);

        // And a solver whose sprint reference is somewhere no default would put it: at that reference,
        // vanilla sprinting pace is only a fifth of the way into the band.
        final WalkCycleSolver distant =
                new WalkCycleSolver(calibrating().sprintSpeedBlocksPerTick(0.54F).build(), IK_TOLERANCE);
        final WalkCycleState lazy = (WalkCycleState) distant.createState(binding);
        for (int tick = 0; tick < 200; tick++) {
            stepWith(distant, binding, profile(), lazy, pose, SPRINT, TICK);
        }

        assertAll(
                () -> assertEquals(0.5F, trotting.sprintBlend, 1.0E-5F,
                        "halfway between the reference speeds is halfway through the blend"),
                () -> assertEquals((SPRINT - WALK) / (0.54F - WALK), lazy.sprintBlend, 1.0E-5F,
                        "and the band's width is the configured one, got " + lazy.sprintBlend));
    }

    @Test
    void theSprintAmplitudeDoesNotReachAKneedLeg() {
        // A kneed leg's thigh angle is a result rather than an amplitude: it comes out of the IK placing
        // a foot whose excursion is pinned to the ground that passed under it. Scaling that would
        // desynchronise the foot from the ground, which is the one thing strideToFootReach may not do.
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneed(rig);
        final WalkCycleState gait = state(binding);
        final Pose walkPose = new Pose(rig.boneCount());
        final Pose sprintPose = new Pose(rig.boneCount());

        evaluateSprinting(binding, profile(), gait, walkPose, 0.25F, 0F);
        evaluateSprinting(binding, profile(), gait, sprintPose, 0.25F, 1F);

        assertAll(
                () -> assertEquals(walkPose.pitch(LEFT_THIGH), sprintPose.pitch(LEFT_THIGH), 1.0E-6F),
                () -> assertEquals(walkPose.pitch(LEFT_SHIN), sprintPose.pitch(LEFT_SHIN), 1.0E-6F),
                () -> assertNotEquals(0F, walkPose.pitch(LEFT_THIGH),
                        "and the leg really is being driven"));
    }

    @Test
    void theSprintBlendCrossesOverTheConfiguredAmplitudeDurationAndSettlesBack() {
        // It rides the amplitude's own blend duration, because it is an amplitude. A snap would widen a
        // model's stride within one frame of it crossing walking pace.
        final Rig rig = humanoid();
        final LocomotionBinding binding = onePiece(rig);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        int ticksIn = 0;
        float previous = 0F;
        while (gait.sprintBlend < 1F && ticksIn < 100) {
            step(binding, gait, pose, SPRINT, TICK);
            assertTrue(gait.sprintBlend >= previous, "the blend must be monotone going in");
            // Both ramps start at zero, chase one, and are driven by the same configured duration, so
            // they have to agree bit for bit. A separate blend key would show up here immediately.
            assertEquals(Float.floatToRawIntBits(gait.amplitude()),
                    Float.floatToRawIntBits(gait.sprintBlend),
                    "the sprint blend must ride the amplitude's own ramp, not one of its own");
            previous = gait.sprintBlend;
            ticksIn++;
        }
        final int crossing = ticksIn;

        int ticksOut = 0;
        while (gait.sprintBlend > 0F && ticksOut < 100) {
            step(binding, gait, pose, WALK, TICK);
            ticksOut++;
        }
        final int settling = ticksOut;

        // Three times the configured duration. Checked as a duration rather than as a tick count: the
        // ramp's per-tick increment is a float quotient, so the final step is a snap that may fall
        // either side of a tick boundary, and one tick is the resolution this can be asserted to.
        final WalkCycleSolver sluggish =
                new WalkCycleSolver(calibrating().amplitudeBlendSeconds(0.45F).build(), IK_TOLERANCE);
        final WalkCycleState slowGait = (WalkCycleState) sluggish.createState(binding);
        int slowTicks = 0;
        while (slowGait.sprintBlend < 1F && slowTicks < 500) {
            stepWith(sluggish, binding, profile(), slowGait, pose, SPRINT, TICK);
            slowTicks++;
        }
        final int slowCrossing = slowTicks;

        assertAll(
                () -> assertTrue(crossing > 1, "a blend that resolves in one tick is a snap"),
                () -> assertEquals(crossing, settling, "and it comes back in the same time"),
                () -> assertEquals(0, Float.floatToRawIntBits(gait.sprintBlend),
                        "dropping back to walking pace must reach exactly zero"),
                () -> assertEquals(CALIBRATED.amplitudeBlendSeconds(), crossing * TICK, TICK,
                        "it must cross in the shipped blend duration, got " + crossing + " ticks"),
                () -> assertEquals(0.45F, slowCrossing * TICK, TICK,
                        "and in a configured 0.45 s when that is what is configured, got "
                                + slowCrossing + " ticks"),
                () -> assertTrue(slowCrossing > crossing,
                        "a longer configured blend must take longer: " + crossing + " against "
                                + slowCrossing));
    }

    @Test
    void resetClearsTheSprintBlendAndRestoresTheIdentityBias() {
        final Rig rig = humanoid();
        final LocomotionBinding binding = kneedFacing(rig, 180F);
        final WalkCycleState gait = state(binding);
        final Pose pose = new Pose(rig.boneCount());

        walk(binding, gait, pose, SPRINT, 40);
        assertAll(
                () -> assertTrue(gait.sprintBlend > 0F),
                () -> assertEquals(-1F, gait.strideBias, 1.0E-6F));

        gait.reset();

        assertAll(
                () -> assertEquals(0F, gait.sprintBlend),
                () -> assertEquals(1F, gait.strideBias,
                        "the identity, not zero: zero is a real direction rather than a stand"));
    }
}
