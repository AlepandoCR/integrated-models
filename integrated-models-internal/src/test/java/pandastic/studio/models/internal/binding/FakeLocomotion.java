package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitRole;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.locomotion.LocomotionSolver;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.LocomotionStage;

/**
 * A gait that records what it was told instead of walking.
 *
 * <p>The real solver would answer the same questions, but through twenty-nine calibration values and a
 * closed-form cycle — so a test asserting "the sneak flag reached the gait" would be asserting it
 * through an inverse-kinematics pass. What the binding layer owes the locomotion layer is narrow: one
 * stance pair per sample, written on the region thread. This fake is exactly that contract and nothing
 * else.
 */
final class FakeLocomotion implements LocomotionSolver {

    private @NotNull RecordingGaitState last = new RecordingGaitState();

    /** A stage over {@code rig}'s first bone, which is all a stance flag needs to land somewhere. */
    static @NotNull LocomotionStage stageFor(@NotNull Rig rig, @NotNull FakeLocomotion solver) {
        final LocomotionBinding binding = LocomotionBinding.builder(rig, BindingFixtures.BINDING_BOUNDS)
                .bone(GaitRole.TORSO, rig.bone(0).name())
                .build();
        return new LocomotionStage(
                solver, binding, GaitProfile.defaults(BindingFixtures.GAIT_TUNING));
    }

    @Override
    public @NotNull GaitState createState(@NotNull LocomotionBinding binding) {
        this.last = new RecordingGaitState();
        return this.last;
    }

    @Override
    public void solve(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull GaitState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    ) {
        if (state instanceof RecordingGaitState recording) {
            recording.solves++;
            recording.lastDriver = driver;
        }
    }

    @Override
    public float movementDeadband() {
        return 0F;
    }

    /** The state created for the most recent model. */
    @NotNull RecordingGaitState state() {
        return this.last;
    }

    /** Remembers the stance it was handed, which is the one thing the binding layer writes here. */
    static final class RecordingGaitState implements GaitState {

        private boolean airborne;
        private boolean sneaking;
        private int solves;
        private int resets;
        private @NotNull PhysicsDriver lastDriver = PhysicsDriver.STILL;

        @Override
        public void reset() {
            this.resets++;
            this.airborne = false;
            this.sneaking = false;
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
            return this.airborne ? 1F : 0F;
        }

        @Override
        public float sneakBlend() {
            return this.sneaking ? 1F : 0F;
        }

        @Override
        public float elapsedSeconds() {
            return 0F;
        }

        @Override
        public boolean isAirborne() {
            return this.airborne;
        }

        @Override
        public void setAirborne(boolean nowAirborne) {
            this.airborne = nowAirborne;
        }

        @Override
        public boolean isSneaking() {
            return this.sneaking;
        }

        @Override
        public void setSneaking(boolean nowSneaking) {
            this.sneaking = nowSneaking;
        }

        @Override
        public boolean isSettled() {
            return true;
        }

        int solves() {
            return this.solves;
        }

        @NotNull PhysicsDriver lastDriver() {
            return this.lastDriver;
        }
    }
}
