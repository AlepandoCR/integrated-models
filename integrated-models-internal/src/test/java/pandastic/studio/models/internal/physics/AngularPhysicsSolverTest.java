package pandastic.studio.models.internal.physics;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.AxisLimits;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lives in the solver's own package so the tests can read {@link AngularPhysicsState}'s internals —
 * the accumulator in particular, whose backlog-discard rule is the whole defence against a lag spike
 * exploding a chain and is invisible from the public surface.
 *
 * <h2>Nothing here is a default</h2>
 * Every calibration figure is written out in {@link #CALIBRATED} rather than imported from the solver,
 * which is deliberate: a suite that reads its expectations from the same place the code reads them
 * cannot tell "the solver honours its configuration" from "the solver ignores its configuration and
 * happens to agree with it". The tests in the <em>injected configuration</em> section exist to make
 * exactly that distinction, by varying one factor at a time to a value no default would ever hold and
 * asserting the observable behaviour moves with it.
 */
class AngularPhysicsSolverTest {

    private static final float TICK_SECONDS = 1F / 20F;
    private static final int BODY = 0;
    private static final int HAIR = 1;

    /**
     * The shipped calibration, spelled out so the expected numbers below sit next to the figures they
     * were derived from.
     */
    private static final PhysicsTuning CALIBRATED = PhysicsTuning.builder()
            .stiffnessToRate(260F)
            .dampingToRate(120F)
            .gravityToRate(40F)
            .windToRadians(60F)
            .turnToSpeed(2F)
            .substepRateHz(60)
            .maxSubsteps(4)
            .restEpsilonRadians(1.0E-5F)
            .build();

    /** The shipped author-facing ranges, which bound a profile rather than calibrate the solver. */
    private static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    /**
     * The shipped seeds. Spelled out for the same reason as {@link #CALIBRATED}: these tests are about
     * the solver, so they want the figures a real server runs on, written where they can be read.
     */
    private static final ProfileDefaults PROFILE_DEFAULTS = new ProfileDefaults(
            0.08F, 0.015F, 0.6F, 1F, 1F,
            0.25F, 0F, 0.25F, 0.25F,
            0F, -1F, 0F);

    private static final RigBounds RIG_BOUNDS = new RigBounds(256);

    private final AngularPhysicsSolver solver = new AngularPhysicsSolver(CALIBRATED);

    /** The shipped calibration with one factor about to be replaced. */
    private static PhysicsTuning.Builder varying() {
        return PhysicsTuning.builder()
                .stiffnessToRate(CALIBRATED.stiffnessToRate())
                .dampingToRate(CALIBRATED.dampingToRate())
                .gravityToRate(CALIBRATED.gravityToRate())
                .windToRadians(CALIBRATED.windToRadians())
                .turnToSpeed(CALIBRATED.turnToSpeed())
                .substepRateHz(CALIBRATED.substepRateHz())
                .maxSubsteps(CALIBRATED.maxSubsteps())
                .restEpsilonRadians(CALIBRATED.restEpsilonRadians());
    }

    /** A pendulum damped enough to settle inside a short test but well inside the stability bound. */
    private static PhysicsProfile settling() {
        return PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(0.1F)
                .mass(1F)
                .build();
    }

    private static Rig hairRig(PhysicsProfile profile) {
        return Rig.builder("doll", "model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .bone("hair", "body", profile)
                .build();
    }

    /**
     * One simulation step. Rewrites the base pose first, because secondary motion is additive and the
     * caller owns clearing it — feeding the pose back uncleared is how offsets compound.
     */
    private static void stepWith(
            AngularPhysicsSolver solver,
            Rig rig,
            PhysicsState state,
            Pose pose,
            float bodyYaw,
            float deltaSeconds
    ) {
        pose.reset();
        pose.set(BODY, 0F, bodyYaw, 0F);
        solver.solve(rig, state, pose, deltaSeconds, PhysicsDriver.STILL);
    }

    private void step(Rig rig, PhysicsState state, Pose pose, float bodyYaw, float deltaSeconds) {
        stepWith(this.solver, rig, state, pose, bodyYaw, deltaSeconds);
    }

    // ───────────────────────────── Integration ─────────────────────────────

    @Test
    void carriesABoneWhenItsParentTurnsThenSettlesBack() {
        final Rig rig = hairRig(settling());
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        step(rig, state, pose, 0.3F, TICK_SECONDS);

        final float afterKick = pose.yaw(HAIR);
        assertAll(
                // The bone is left behind by roughly the angle its parent moved: that is the inertia.
                () -> assertTrue(Math.abs(afterKick) > 0.1F,
                        "expected the bone to lag behind, got " + afterKick),
                () -> assertTrue(afterKick < 0F, "it should lag opposite to the parent's turn"),
                () -> assertFalse(state.isAtRest(), "it is visibly moving"));

        for (int tick = 0; tick < 500; tick++) {
            step(rig, state, pose, 0.3F, TICK_SECONDS);
        }

        assertAll(
                () -> assertTrue(Math.abs(pose.yaw(HAIR)) < 0.01F,
                        "expected it to settle, got " + pose.yaw(HAIR)),
                () -> assertTrue(state.isAtRest(), "a settled bone must report at rest"),
                () -> assertTrue(pose.isFinite()));
    }

    @Test
    void aBoneWhoseParentNeverMovesNeverMoves() {
        final Rig rig = hairRig(settling());
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        for (int tick = 0; tick < 50; tick++) {
            step(rig, state, pose, 0F, TICK_SECONDS);
        }

        assertAll(
                () -> assertEquals(0F, pose.yaw(HAIR), 1.0E-6F),
                () -> assertEquals(0F, pose.pitch(HAIR), 1.0E-6F),
                () -> assertTrue(state.isAtRest()));
    }

    @Test
    void neverExceedsItsAxisLimits() {
        final PhysicsProfile tight = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(0.1F)
                .limits(new AxisLimits(-0.05F, 0.05F, -0.05F, 0.05F, -0.05F, 0.05F))
                .build();
        final Rig rig = hairRig(tight);
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        for (int tick = 0; tick < 100; tick++) {
            // Shake the parent hard enough that an unclamped bone would swing an order of magnitude
            // past the limit.
            final float yaw = tick % 2 == 0 ? 1.5F : -1.5F;
            step(rig, state, pose, yaw, TICK_SECONDS);

            assertTrue(Math.abs(pose.yaw(HAIR)) <= 0.05F + 1.0E-6F,
                    "tick " + tick + " escaped the limit: " + pose.yaw(HAIR));
            assertTrue(Math.abs(pose.pitch(HAIR)) <= 0.05F + 1.0E-6F,
                    "tick " + tick + " escaped the pitch limit: " + pose.pitch(HAIR));
        }
    }

    @Test
    void discardsBacklogBeyondTheSubstepBudgetInsteadOfCarryingIt() {
        final Rig rig = hairRig(settling());
        final AngularPhysicsState state = (AngularPhysicsState) this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        // A ten-second stall. Carrying this would hand the integrator a step it cannot survive.
        step(rig, state, pose, 0.3F, 10F);

        assertAll(
                () -> assertEquals(0F, state.accumulator,
                        "the backlog must be dropped once the substep budget is spent"),
                () -> assertTrue(pose.isFinite(), "a stall must not produce NaN"));
    }

    @Test
    void survivesAStormOfIrregularTimesteps() {
        final Rig rig = hairRig(settling());
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        final float[] deltas = {0.001F, 0.05F, 0.5F, 0.016F, 2F, 0F, -1F, 0.033F, 30F};
        for (int round = 0; round < 20; round++) {
            for (final float delta : deltas) {
                step(rig, state, pose, round % 2 == 0 ? 0.4F : -0.4F, delta);
                assertTrue(pose.isFinite(), "diverged at delta " + delta);
            }
        }
    }

    @Test
    void keepsLeftoverTimeWhenTheBudgetIsNotExhausted() {
        final Rig rig = hairRig(settling());
        final AngularPhysicsState state = (AngularPhysicsState) this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        // A third of a substep: too little to integrate, so it must be banked rather than dropped.
        step(rig, state, pose, 0F, 1F / 180F);
        assertTrue(state.accumulator > 0F, "sub-substep time must accumulate");
    }

    @Test
    void dragDecaysMonotonicallyAndNeverOvershoots() {
        final PhysicsProfile drag =
                PhysicsProfile.builder(PhysicsKind.DRAG, PROFILE_BOUNDS, PROFILE_DEFAULTS).lag(0.5F).build();
        final Rig rig = hairRig(drag);
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        step(rig, state, pose, 0.3F, TICK_SECONDS);
        float previous = pose.yaw(HAIR);
        assertTrue(previous < 0F, "it should lag behind the turn");

        for (int tick = 0; tick < 40; tick++) {
            step(rig, state, pose, 0.3F, TICK_SECONDS);
            final float current = pose.yaw(HAIR);
            assertTrue(current <= 0F, "drag must not overshoot past rest at tick " + tick);
            assertTrue(Math.abs(current) <= Math.abs(previous) + 1.0E-7F,
                    "drag must decay monotonically, " + previous + " -> " + current);
            previous = current;
        }
        assertTrue(state.isAtRest());
    }

    @Test
    void propagatesThroughAChainWithEnergyAbsorbedOnTheWayDown() {
        final Rig rig = Rig.builder("chain", "model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .bone("upper", "body", settling())
                .bone("lower", "upper", settling())
                .build();
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        step(rig, state, pose, 0.3F, TICK_SECONDS);

        final float upper = pose.yaw(1);
        final float lower = pose.yaw(2);

        assertAll(
                () -> assertTrue(Math.abs(upper) > 0.1F, "the first link takes the hit: " + upper),
                () -> assertNotEquals(0F, lower, "the second link must feel its parent's physics"),
                // The upper link absorbed most of the parent's turn, so the lower one — which inherits
                // the upper link's rotation — sees far less net movement. A chain that did not read its
                // parent's live angle would swing the whole way instead.
                () -> assertTrue(Math.abs(lower) < Math.abs(upper),
                        "expected the chain to absorb energy, got " + lower + " vs " + upper));

        for (int tick = 0; tick < 500; tick++) {
            step(rig, state, pose, 0.3F, TICK_SECONDS);
        }
        assertAll(
                () -> assertTrue(state.isAtRest()),
                () -> assertTrue(Math.abs(pose.yaw(1)) < 0.01F),
                () -> assertTrue(Math.abs(pose.yaw(2)) < 0.01F));
    }

    @Test
    void windDragDeflectsAMovingOwner() {
        final PhysicsProfile windy = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(0.1F)
                .wind(1F)
                .build();
        final Rig rig = hairRig(windy);
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        final PhysicsDriver running = new PhysicsDriver(0.21F, 0F, 0F, 0F);
        for (int tick = 0; tick < 200; tick++) {
            pose.reset();
            this.solver.solve(rig, state, pose, TICK_SECONDS, running);
        }

        assertAll(
                () -> assertNotEquals(0F, pose.pitch(HAIR), "running should trail the bone backward"),
                () -> assertTrue(pose.isFinite()));
    }

    // ───────────────────────────── Stability ─────────────────────────────

    @Test
    void refusesTuningThatWouldDivergeThroughOverDamping() {
        // damping 1 against mass 0.05 gives a per-step bleed factor of 40: the velocity flips sign and
        // grows every step. The permitted ranges allow this, which is why the check is not theoretical.
        final PhysicsProfile unstable = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(1F)
                .mass(0.05F)
                .build();
        final Rig rig = hairRig(unstable);

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> this.solver.createState(rig));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("over-damped")),
                () -> assertTrue(thrown.getMessage().contains("Keep damping below mass")),
                () -> assertTrue(thrown.getMessage().contains("hair"), "it must name the bone"),
                () -> assertTrue(thrown.getMessage().contains("chain") || thrown.getMessage().contains("doll"),
                        "and the rig"));
    }

    @Test
    void acceptsTuningJustInsideTheDampingBound() {
        assertAll(
                () -> assertDoesNotThrow(() -> this.solver.createState(hairRig(
                        PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                                .damping(0.99F).mass(1F).build()))),
                () -> assertThrows(IllegalArgumentException.class, () -> this.solver.createState(hairRig(
                        PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                                .damping(1F).mass(1F).build()))));
    }

    @Test
    void reportsTheDampingCeilingSoALoaderCanClampInsteadOfRefusing() {
        // dampingToRate * (1/60) is exactly 2 at the shipped calibration, so the bound reduces there to
        // "damping below mass".
        assertAll(
                () -> assertEquals(1F, this.solver.maxStableDamping(1F, 60), 1.0E-6F),
                () -> assertEquals(0.05F, this.solver.maxStableDamping(0.05F, 60), 1.0E-6F),
                () -> assertEquals(0.333333F, this.solver.maxStableDamping(1F, 20), 1.0E-5F));
    }

    @Test
    void dragIsNeverRefusedBecauseItCannotDiverge() {
        final PhysicsProfile drag = PhysicsProfile.builder(PhysicsKind.DRAG, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(1F)
                .mass(PROFILE_BOUNDS.minMass())
                .lag(1F)
                .build();
        assertDoesNotThrow(() -> this.solver.createState(hairRig(drag)));
    }

    @Test
    void theRestoringBoundCannotTriggerAtSixtyHertzButDoesAtTwenty() {
        // Worth pinning: within the permitted ranges the stiffness bound peaks at omega*h = 1.22 at
        // 60 Hz, so only the damping bound can fire there. Lower the rate and the same tuning diverges,
        // which is the entire reason the substep rate is not the tick rate.
        final PhysicsProfile loosest = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .stiffness(1F)
                .damping(0F)
                .gravity(PROFILE_BOUNDS.maxGravity())
                .mass(PROFILE_BOUNDS.minMass())
                .build();
        final float omega = AngularPhysicsSolver.naturalFrequency(CALIBRATED, loosest);

        assertAll(
                () -> assertTrue(omega / 60F < PhysicsTuning.STABILITY_LIMIT,
                        "60 Hz must survive the loosest legal tuning, got " + omega / 60F),
                () -> assertTrue(omega / 20F > PhysicsTuning.STABILITY_LIMIT,
                        "20 Hz must not, got " + omega / 20F),
                () -> assertDoesNotThrow(() -> this.solver.createState(hairRig(loosest))));
    }

    // ───────────────────────────── Seam checks ─────────────────────────────

    @Test
    void aRigWithNoSimulatedBonesIsANoOp() {
        final Rig rig = Rig.builder("static", "model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .bone("head", "body", null)
                .build();
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());
        pose.set(1, 1F, 2F, 3F);

        this.solver.solve(rig, state, pose, TICK_SECONDS, PhysicsDriver.STILL);

        assertAll(
                () -> assertEquals(1F, pose.pitch(1), "an unsimulated pose must be untouched"),
                () -> assertTrue(state.isAtRest()));
    }

    @Test
    void ignoresANonPositiveOrNonFiniteDelta() {
        final Rig rig = hairRig(settling());
        final PhysicsState state = this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        for (final float delta : new float[]{0F, -1F, Float.NaN, Float.POSITIVE_INFINITY}) {
            pose.reset();
            pose.set(BODY, 0F, 0.3F, 0F);
            this.solver.solve(rig, state, pose, delta, PhysicsDriver.STILL);
            assertEquals(0F, pose.yaw(HAIR), "delta " + delta + " must not advance the solver");
        }
    }

    @Test
    void refusesAStateItDidNotCreate() {
        final Rig rig = hairRig(settling());
        final Pose pose = new Pose(rig.boneCount());
        final PhysicsState foreign = new PhysicsState() {
            @Override
            public void reset() {
            }

            @Override
            public boolean isAtRest() {
                return true;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> this.solver.solve(rig, foreign, pose, TICK_SECONDS, PhysicsDriver.STILL));
    }

    @Test
    void refusesAPoseOfTheWrongWidth() {
        final Rig rig = hairRig(settling());
        final PhysicsState state = this.solver.createState(rig);
        final Pose narrow = new Pose(1);

        assertThrows(IllegalArgumentException.class,
                () -> this.solver.solve(rig, state, narrow, TICK_SECONDS, PhysicsDriver.STILL));
    }

    @Test
    void resetClearsEverythingIncludingTheBacklog() {
        final Rig rig = hairRig(settling());
        final AngularPhysicsState state = (AngularPhysicsState) this.solver.createState(rig);
        final Pose pose = new Pose(rig.boneCount());

        step(rig, state, pose, 0.3F, TICK_SECONDS);
        assertFalse(state.isAtRest());

        state.reset();

        assertAll(
                () -> assertEquals(0F, state.accumulator),
                () -> assertTrue(state.isAtRest()),
                () -> assertEquals(0F, state.angle[0]),
                () -> assertEquals(0F, state.velocity[0]),
                () -> assertEquals(0F, state.parentPrevious[0]));
    }

    @Test
    void oneSolverInstanceServesManyIndependentRigs() {
        final Rig rig = hairRig(settling());
        final PhysicsState first = this.solver.createState(rig);
        final PhysicsState second = this.solver.createState(rig);
        final Pose firstPose = new Pose(rig.boneCount());
        final Pose secondPose = new Pose(rig.boneCount());

        step(rig, first, firstPose, 0.3F, TICK_SECONDS);
        // The second rig is never driven, so it must remain untouched by the first one's motion.
        step(rig, second, secondPose, 0F, TICK_SECONDS);

        assertAll(
                () -> assertTrue(Math.abs(firstPose.yaw(HAIR)) > 0.1F),
                () -> assertEquals(0F, secondPose.yaw(HAIR), 1.0E-6F),
                () -> assertFalse(first.isAtRest()),
                () -> assertTrue(second.isAtRest()));
    }

    // ───────────────────────── Injected configuration ─────────────────────────
    // Each of these varies exactly one component of the tuning to a value no default would hold, and
    // asserts the behaviour it controls moves with it. A solver that kept a leftover constant would
    // still pass every test above.

    @Test
    void reportsTheTuningsStepRatherThanADefault() {
        final AngularPhysicsSolver unusual =
                new AngularPhysicsSolver(varying().substepRateHz(30).maxSubsteps(9).build());

        assertAll(
                () -> assertEquals(60, this.solver.substepRateHz()),
                () -> assertEquals(4, this.solver.maxSubsteps()),
                () -> assertEquals(30, unusual.substepRateHz(),
                        "the reported rate must be the configured one"),
                () -> assertEquals(9, unusual.maxSubsteps()));
    }

    @Test
    void halvingTheSubstepRateRunsHalfAsManySubstepsForTheSameElapsedTime() {
        // A DRAG bone multiplies its offset by `lag` once per substep and has no velocity term, so the
        // remaining offset is exactly lag^substeps — which makes the substep count readable from the
        // pose. 0.105 s is 6 substeps at 60 Hz and 3 at 30 Hz, both clear of a boundary and both inside
        // the budget, so the only thing that differs is the step size.
        final PhysicsProfile drag =
                PhysicsProfile.builder(PhysicsKind.DRAG, PROFILE_BOUNDS, PROFILE_DEFAULTS).lag(0.5F).build();
        final Rig rig = hairRig(drag);
        final AngularPhysicsSolver fast =
                new AngularPhysicsSolver(varying().substepRateHz(60).maxSubsteps(8).build());
        final AngularPhysicsSolver slow =
                new AngularPhysicsSolver(varying().substepRateHz(30).maxSubsteps(8).build());

        final Pose fastPose = new Pose(rig.boneCount());
        final Pose slowPose = new Pose(rig.boneCount());
        stepWith(fast, rig, fast.createState(rig), fastPose, 0.3F, 0.105F);
        stepWith(slow, rig, slow.createState(rig), slowPose, 0.3F, 0.105F);

        final float sixSubsteps = -0.3F * 0.5F * 0.5F * 0.5F * 0.5F * 0.5F * 0.5F;
        final float threeSubsteps = -0.3F * 0.5F * 0.5F * 0.5F;

        assertAll(
                () -> assertEquals(sixSubsteps, fastPose.yaw(HAIR), 1.0E-7F,
                        "60 Hz must take six substeps across 0.105 s"),
                () -> assertEquals(threeSubsteps, slowPose.yaw(HAIR), 1.0E-7F,
                        "30 Hz must take three — half as many for the same elapsed time"),
                () -> assertEquals(8F, slowPose.yaw(HAIR) / fastPose.yaw(HAIR), 1.0E-3F,
                        "three decays against six is a factor of lag cubed"));
    }

    @Test
    void halvingTheSubstepRateMakesTheDampingBoundTwiceAsStrict() {
        // The bleed factor per step is damping * dampingToRate * h / mass, so halving the rate doubles
        // it. This tuning is comfortably legal at 60 Hz and must be refused at 30 — which is only true
        // if requireStable reads the rate out of the tuning rather than assuming one.
        final PhysicsProfile legalAtSixty =
                PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                        .damping(0.4F)
                        .mass(0.5F)
                        .build();
        final AngularPhysicsSolver slow =
                new AngularPhysicsSolver(varying().substepRateHz(30).build());

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> slow.createState(hairRig(legalAtSixty)));

        assertAll(
                () -> assertDoesNotThrow(() -> this.solver.createState(hairRig(legalAtSixty)),
                        "damping 0.4 against mass 0.5 is legal at 60 Hz"),
                () -> assertTrue(thrown.getMessage().contains("over-damped")),
                () -> assertTrue(thrown.getMessage().contains("30 Hz"),
                        "the diagnostic must quote the configured rate, got: " + thrown.getMessage()),
                () -> assertEquals(0.5F, this.solver.maxStableDamping(0.5F, 60), 1.0E-6F),
                () -> assertEquals(0.25F, slow.maxStableDamping(0.5F, 30), 1.0E-6F,
                        "and the ceiling a loader clamps against must halve with it"));
    }

    @Test
    void aDoubledStiffnessToRateReallyDoublesTheRestoringForce() {
        final PhysicsProfile spring = PhysicsProfile.builder(PhysicsKind.SPRING, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .stiffness(0.08F)
                .damping(0.1F)
                .mass(1F)
                .build();
        final Rig rig = hairRig(spring);
        final PhysicsTuning doubled = varying().stiffnessToRate(520F).build();
        final AngularPhysicsSolver stiffer = new AngularPhysicsSolver(doubled);

        final Pose softPose = new Pose(rig.boneCount());
        final Pose stiffPose = new Pose(rig.boneCount());
        step(rig, this.solver.createState(rig), softPose, 0.3F, TICK_SECONDS);
        stepWith(stiffer, rig, stiffer.createState(rig), stiffPose, 0.3F, TICK_SECONDS);

        // The kick leaves the bone at -0.3; how far back toward rest it has travelled after one tick is
        // what the restoring force decides.
        final float softRecovery = 0.3F + softPose.yaw(HAIR);
        final float stiffRecovery = 0.3F + stiffPose.yaw(HAIR);

        assertAll(
                () -> assertTrue(softRecovery > 0F, "the spring must pull back at all"),
                () -> assertTrue(stiffRecovery > 1.8F * softRecovery,
                        "twice the stiffness factor must pull back about twice as far, got "
                                + stiffRecovery + " against " + softRecovery),
                // Gravity is zero for a SPRING, so omega is sqrt(stiffness * stiffnessToRate / mass) and
                // doubling the factor raises it by exactly root two.
                () -> assertEquals(
                        (float) Math.sqrt(2D)
                                * AngularPhysicsSolver.naturalFrequency(CALIBRATED, spring),
                        AngularPhysicsSolver.naturalFrequency(doubled, spring),
                        1.0E-3F));
    }

    @Test
    void theDampingFactorIsReadFromTheTuningByBothTheIntegratorAndTheBound() {
        // Doubling the bleed factor takes this spring from underdamped to overdamped, so the shipped
        // calibration overshoots past rest and the doubled one never crosses it.
        final PhysicsProfile ringing = PhysicsProfile.builder(PhysicsKind.SPRING, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .stiffness(0.08F)
                .damping(0.05F)
                .mass(1F)
                .build();
        final AngularPhysicsSolver bleedy =
                new AngularPhysicsSolver(varying().dampingToRate(240F).build());
        final Rig ringingRig = hairRig(ringing);

        final PhysicsState shipped = this.solver.createState(ringingRig);
        final PhysicsState damped = bleedy.createState(ringingRig);
        final Pose shippedPose = new Pose(ringingRig.boneCount());
        final Pose dampedPose = new Pose(ringingRig.boneCount());
        float shippedOvershoot = 0F;
        float dampedOvershoot = 0F;
        for (int tick = 0; tick < 60; tick++) {
            step(ringingRig, shipped, shippedPose, 0.3F, TICK_SECONDS);
            stepWith(bleedy, ringingRig, damped, dampedPose, 0.3F, TICK_SECONDS);
            shippedOvershoot = Math.max(shippedOvershoot, shippedPose.yaw(HAIR));
            dampedOvershoot = Math.max(dampedOvershoot, dampedPose.yaw(HAIR));
        }
        final float ringingPeak = shippedOvershoot;
        final float dampedPeak = dampedOvershoot;

        // The same factor divides into both stability checks, so the bound and the ceiling a loader
        // clamps against have to move with it too.
        final PhysicsProfile heavilyDamped =
                PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                        .damping(0.6F)
                        .mass(1F)
                        .build();

        assertAll(
                () -> assertTrue(ringingPeak > 0.005F,
                        "the shipped bleed leaves this spring ringing, got " + ringingPeak),
                () -> assertEquals(0F, dampedPeak, 1.0E-6F,
                        "twice the bleed makes it overdamped, so it must never pass rest"),
                () -> assertDoesNotThrow(() -> this.solver.createState(hairRig(heavilyDamped)),
                        "damping 0.6 is legal against the shipped factor"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bleedy.createState(hairRig(heavilyDamped)),
                        "and refused against twice it"),
                () -> assertEquals(1F, this.solver.maxStableDamping(1F, 60), 1.0E-6F),
                () -> assertEquals(0.5F, bleedy.maxStableDamping(1F, 60), 1.0E-6F));
    }

    @Test
    void theGravityFactorIsReadFromTheTuningAndFeedsTheRestoringBound() {
        final PhysicsProfile pendulum = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .stiffness(0F)
                .damping(0F)
                .gravity(1F)
                .mass(1F)
                .build();
        final Rig rig = hairRig(pendulum);
        // Six hertz is absurd as a step rate, which is the point: it puts the restoring bound within
        // reach of a legal profile, so the two factors can be told apart by whether the rig loads.
        final AngularPhysicsSolver coarse =
                new AngularPhysicsSolver(varying().substepRateHz(6).build());
        final AngularPhysicsSolver coarseAndHeavy = new AngularPhysicsSolver(
                varying().substepRateHz(6).gravityToRate(160F).build());

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> coarseAndHeavy.createState(rig));

        assertAll(
                // With no stiffness the whole natural frequency is the gravity term, so it is
                // sqrt(gravity * gravityToRate) and nothing else.
                () -> assertEquals((float) Math.sqrt(40D),
                        AngularPhysicsSolver.naturalFrequency(CALIBRATED, pendulum), 1.0E-4F),
                () -> assertEquals((float) Math.sqrt(160D),
                        AngularPhysicsSolver.naturalFrequency(
                                varying().gravityToRate(160F).build(), pendulum),
                        1.0E-4F),
                () -> assertDoesNotThrow(() -> coarse.createState(rig),
                        "sqrt(40) / 6 is 1.05, inside the bound"),
                () -> assertTrue(thrown.getMessage().contains("restoring-force"),
                        "quadrupling the factor takes the same profile past it: " + thrown.getMessage()));
    }

    @Test
    void theWindFactorScalesTheSettledDragDeflection() {
        // At equilibrium the deflection is wind / (restoring + gravityPull * mass), which is linear in
        // windToRadians — so doubling the factor must double the angle the bone settles at.
        final PhysicsProfile windy = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(0.1F)
                .wind(0.25F)
                .build();
        final Rig rig = hairRig(windy);
        final AngularPhysicsSolver gusty =
                new AngularPhysicsSolver(varying().windToRadians(120F).build());

        final PhysicsDriver running = new PhysicsDriver(0.21F, 0F, 0F, 0F);
        final Pose calmPose = new Pose(rig.boneCount());
        final Pose gustyPose = new Pose(rig.boneCount());
        final PhysicsState calmState = this.solver.createState(rig);
        final PhysicsState gustyState = gusty.createState(rig);
        for (int tick = 0; tick < 400; tick++) {
            calmPose.reset();
            this.solver.solve(rig, calmState, calmPose, TICK_SECONDS, running);
            gustyPose.reset();
            gusty.solve(rig, gustyState, gustyPose, TICK_SECONDS, running);
        }

        assertAll(
                () -> assertTrue(Math.abs(calmPose.pitch(HAIR)) > 0.05F,
                        "the baseline must deflect visibly, got " + calmPose.pitch(HAIR)),
                () -> assertEquals(2F * calmPose.pitch(HAIR), gustyPose.pitch(HAIR), 1.0E-3F,
                        "twice the wind factor must settle at twice the angle"));
    }

    @Test
    void theTurnLeverArmIsReadFromTheTuning() {
        // A pure yaw turn drives a bone only through turnToSpeed: the lever arm that converts body yaw
        // rate into a tangential speed. Set it to zero and turning on the spot must stop moving the bone
        // at all, while the straight-line wind path is untouched.
        final PhysicsProfile windy = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .damping(0.1F)
                .wind(0.25F)
                .reach(0F, -1F, 1F)
                .build();
        final Rig rig = hairRig(windy);
        final AngularPhysicsSolver rigid = new AngularPhysicsSolver(varying().turnToSpeed(0F).build());

        final PhysicsDriver turning = new PhysicsDriver(0F, 0F, 0F, 0.4F);
        final Pose leveredPose = new Pose(rig.boneCount());
        final Pose rigidPose = new Pose(rig.boneCount());
        final PhysicsState leveredState = this.solver.createState(rig);
        final PhysicsState rigidState = rigid.createState(rig);
        for (int tick = 0; tick < 200; tick++) {
            leveredPose.reset();
            this.solver.solve(rig, leveredState, leveredPose, TICK_SECONDS, turning);
            rigidPose.reset();
            rigid.solve(rig, rigidState, rigidPose, TICK_SECONDS, turning);
        }

        assertAll(
                () -> assertTrue(Math.abs(leveredPose.yaw(HAIR)) > 0.01F,
                        "a lever arm of 2 blocks must swing the bone, got " + leveredPose.yaw(HAIR)),
                () -> assertEquals(0F, rigidPose.yaw(HAIR), 1.0E-6F,
                        "a lever arm of zero leaves a turning owner with nothing to drag with"),
                () -> assertEquals(0F, rigidPose.roll(HAIR), 1.0E-6F));
    }

    @Test
    void theRestEpsilonIsReadFromTheTuning() {
        // The epsilon decides when the emission layer may stop sending, so a leftover constant here
        // would be a bandwidth setting that does nothing.
        final Rig rig = hairRig(settling());
        final AngularPhysicsSolver generous =
                new AngularPhysicsSolver(varying().restEpsilonRadians(1F).build());
        final AngularPhysicsSolver exacting =
                new AngularPhysicsSolver(varying().restEpsilonRadians(1.0E-30F).build());

        final PhysicsState shipped = this.solver.createState(rig);
        final PhysicsState loose = generous.createState(rig);
        final PhysicsState tight = exacting.createState(rig);
        final Pose shippedPose = new Pose(rig.boneCount());
        final Pose loosePose = new Pose(rig.boneCount());
        final Pose tightPose = new Pose(rig.boneCount());

        step(rig, shipped, shippedPose, 0.3F, TICK_SECONDS);
        stepWith(generous, rig, loose, loosePose, 0.3F, TICK_SECONDS);
        stepWith(exacting, rig, tight, tightPose, 0.3F, TICK_SECONDS);

        assertAll(
                () -> assertFalse(shipped.isAtRest(), "a fresh kick is motion at the shipped epsilon"),
                () -> assertTrue(loose.isAtRest(),
                        "an epsilon of a whole radian calls the same kick settled"),
                () -> assertFalse(tight.isAtRest()));

        // Seven and a half seconds, by which the pendulum's per-substep change is a couple of parts in a
        // billion: comfortably below the shipped epsilon, and nowhere near one of 1e-30.
        for (int tick = 0; tick < 150; tick++) {
            step(rig, shipped, shippedPose, 0.3F, TICK_SECONDS);
            stepWith(exacting, rig, tight, tightPose, 0.3F, TICK_SECONDS);
        }

        assertAll(
                () -> assertTrue(shipped.isAtRest(), "and a settled bone does reach rest"),
                () -> assertFalse(tight.isAtRest(),
                        "while an epsilon near zero refuses to call the same bone settled"));
    }
}
