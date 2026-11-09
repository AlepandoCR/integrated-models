package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitRole;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.locomotion.LocomotionSolver;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam between the gait and the secondary motion.
 *
 * <p>Both layers were built independently and each is covered on its own; what is only observable once
 * they are composed is the ordering, the shared driver sample, and the at-rest conjunction. All three
 * are silent failures — a reversed order loses the secondary motion, a split sample desynchronises the
 * two by one step, and an at-rest check that only asks the physics stops the packets while the legs are
 * still swinging. None of them throws.
 */
class LocomotionStageTest {

    /** What the fake gait writes to bone 0's pitch. Absolute, as locomotion is defined to be. */
    private static final float GAIT_PITCH = 1F;

    private static Rig rig() {
        return SimFixtures.rig("walker", "model")
                .root("torso")
                .bone("hair", "torso", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, SimFixtures.PROFILE_BOUNDS,
                                SimFixtures.PROFILE_DEFAULTS)
                        .build())
                .build();
    }

    private static LocomotionBinding binding(Rig rig) {
        return LocomotionBinding.builder(rig, SimFixtures.BINDING_BOUNDS).bone(GaitRole.TORSO, "torso").build();
    }

    @Test
    void locomotionRunsBeforePhysicsSoSecondaryMotionSurvives() {
        final Rig rig = rig();
        final ScriptedSolver physics = new ScriptedSolver();
        final ScriptedGait gait = new ScriptedGait();
        final RecordingPoseSink sink = new RecordingPoseSink();

        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(physics), sink, new DriverHolder(),
                new LocomotionStage(gait, binding(rig), SimFixtures.GAIT_PROFILE));

        simulation.step(0L);
        simulation.step(16_666_667L);

        // The gait SETS the pitch, the physics ADDS to it. Seeing the sum proves the gait ran first:
        // the other order would have the gait's absolute write erase the physics contribution, leaving
        // exactly GAIT_PITCH and no trace that anything was lost.
        assertEquals(GAIT_PITCH + ScriptedSolver.OFFSET_PER_SOLVE,
                sink.last().pose().pitch(0), 1.0E-6F);
    }

    @Test
    void bothStagesSeeTheSameDriverSample() {
        final Rig rig = rig();
        final ScriptedSolver physics = new ScriptedSolver();
        final ScriptedGait gait = new ScriptedGait();
        final DriverHolder holder = new DriverHolder();
        holder.publish(new PhysicsDriver(0.2F, 0F, 0F, 0F));

        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(physics), PoseSink.DISCARDING, holder,
                new LocomotionStage(gait, binding(rig), SimFixtures.GAIT_PROFILE));

        simulation.step(0L);
        simulation.step(16_666_667L);

        assertAll(
                () -> assertEquals(2, gait.drivers().size()),
                () -> assertEquals(2, physics.drivers().size()),
                // Identity, not equality: the point is that one read was shared, not that two reads
                // happened to agree.
                () -> assertSame(gait.drivers().get(0), physics.drivers().get(0)),
                () -> assertSame(gait.drivers().get(1), physics.drivers().get(1)));
    }

    @Test
    void anUnsettledGaitKeepsEmittingEvenWithRestingPhysics() {
        final Rig rig = rig();
        final ScriptedSolver physics = new ScriptedSolver();
        physics.restAfter(0);
        final ScriptedGait gait = new ScriptedGait();
        gait.settled(false);
        final RecordingPoseSink sink = new RecordingPoseSink();

        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(physics), sink, new DriverHolder(),
                new LocomotionStage(gait, binding(rig), SimFixtures.GAIT_PROFILE));

        // A model walking at constant speed is exactly this shape: nothing perturbs the hair, so the
        // physics settles, while the legs keep swinging.
        for (int step = 0; step < 12; step++) {
            simulation.step(step * 16_666_667L);
        }

        assertAll(
                () -> assertFalse(simulation.isAtRest(), "a mid-stride model is not at rest"),
                () -> assertEquals(4, sink.count(), "every emission step must still emit"));
    }

    @Test
    void restRequiresBothLayersToSettle() {
        final Rig rig = rig();
        final ScriptedSolver physics = new ScriptedSolver();
        physics.restAfter(0);
        final ScriptedGait gait = new ScriptedGait();
        gait.settled(false);
        final RecordingPoseSink sink = new RecordingPoseSink();

        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(physics), sink, new DriverHolder(),
                new LocomotionStage(gait, binding(rig), SimFixtures.GAIT_PROFILE));

        for (int step = 0; step < 6; step++) {
            simulation.step(step * 16_666_667L);
        }
        final int whileWalking = sink.count();
        assertFalse(simulation.isAtRest());

        gait.settled(true);
        for (int step = 6; step < 24; step++) {
            simulation.step(step * 16_666_667L);
        }

        assertAll(
                () -> assertTrue(simulation.isAtRest(), "both layers settled"),
                // Exactly one settling update, then silence.
                () -> assertEquals(whileWalking + 1, sink.count(),
                        "a settled model emits its final pose once and then goes quiet"));
    }

    @Test
    void resetAlsoReturnsTheGaitToRest() {
        final Rig rig = rig();
        final ScriptedGait gait = new ScriptedGait();
        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(new ScriptedSolver()), PoseSink.DISCARDING, new DriverHolder(),
                new LocomotionStage(gait, binding(rig), SimFixtures.GAIT_PROFILE));

        simulation.step(0L);
        assertEquals(0, gait.resetCount());

        simulation.reset();
        assertEquals(1, gait.resetCount());
    }

    @Test
    void aSimulationWithoutLocomotionStillWorksAndReportsNone() {
        final Rig rig = rig();
        final ScriptedSolver physics = new ScriptedSolver();
        final RecordingPoseSink sink = new RecordingPoseSink();
        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(physics), sink);

        simulation.step(0L);
        simulation.step(16_666_667L);

        assertAll(
                () -> assertNull(simulation.locomotion(), "a decoration has no gait"),
                // Only the physics contribution, no gait write.
                () -> assertEquals(ScriptedSolver.OFFSET_PER_SOLVE,
                        sink.last().pose().pitch(0), 1.0E-6F));
    }

    @Test
    void theStageExposesItsGaitStateSoStanceFlagsCanBeSet() {
        final Rig rig = rig();
        final LocomotionStage stage =
                new LocomotionStage(new ScriptedGait(), binding(rig), SimFixtures.GAIT_PROFILE);
        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(new ScriptedSolver()), PoseSink.DISCARDING, new DriverHolder(),
                stage);

        final LocomotionStage exposed = simulation.locomotion();
        assertNotNull(exposed);

        exposed.state().setSneaking(true);
        exposed.state().setAirborne(true);

        assertAll(
                () -> assertSame(stage, exposed),
                () -> assertTrue(exposed.state().isSneaking()),
                () -> assertTrue(exposed.state().isAirborne()),
                () -> assertEquals("walker", stage.binding().getRigName()),
                () -> assertSame(SimFixtures.GAIT_PROFILE, stage.profile()));
    }

    /** A gait that writes one absolute value and lets the test decide when it has settled. */
    private static final class ScriptedGait implements LocomotionSolver {

        private final @NotNull List<PhysicsDriver> drivers = new ArrayList<>();
        private final @NotNull ScriptedGaitState state = new ScriptedGaitState();

        @Override
        public @NotNull GaitState createState(@NotNull LocomotionBinding binding) {
            return this.state;
        }

        @Override
        public float movementDeadband() {
            return 0.003F;
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
            this.drivers.add(driver);
            // Absolute, as the contract requires of locomotion.
            pose.set(0, GAIT_PITCH, 0F, 0F);
        }

        @NotNull List<PhysicsDriver> drivers() {
            return this.drivers;
        }

        void settled(boolean settled) {
            this.state.settled = settled;
        }

        int resetCount() {
            return this.state.resets;
        }
    }

    private static final class ScriptedGaitState implements GaitState {

        private boolean settled = true;
        private boolean airborne;
        private boolean sneaking;
        private int resets;

        @Override
        public void reset() {
            this.resets++;
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
            return this.airborne;
        }

        @Override
        public void setAirborne(boolean airborne) {
            this.airborne = airborne;
        }

        @Override
        public boolean isSneaking() {
            return this.sneaking;
        }

        @Override
        public void setSneaking(boolean sneaking) {
            this.sneaking = sneaking;
        }

        @Override
        public boolean isSettled() {
            return this.settled;
        }
    }
}
