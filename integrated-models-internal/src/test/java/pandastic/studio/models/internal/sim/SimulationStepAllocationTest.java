package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.perf.AllocationProbe;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one simulation step allocates, and what it costs as the rig grows.
 *
 * <h2>Why "nothing" is the figure worth defending</h2>
 * The step is the one piece of this plugin that runs at the substep rate — three times a tick at the
 * shipped 60 Hz — for every bound model on the server, from a single off-tick task. One object per step
 * per model is therefore the most-multiplied allocation the design could contain, and the whole layer was
 * built to avoid it: the pose is a flat {@code float[]}, the solver's state is six parallel arrays and a
 * scratch triple, the driver arrives as a value a region thread already built, and the emission borrows
 * the pose rather than copying it.
 *
 * <p>All of that is true today and nothing in the suite noticed if it stopped being: a {@code Vector3f}
 * per bone, a {@code List} of dirty bones, a settings source that stopped memoising, an
 * {@code Optional} in the at-rest test — each is a correct-looking change whose only symptom is
 * garbage-collector pressure proportional to the model count. Hence a measured ceiling rather than a
 * comment.
 *
 * <h2>And why the figure is held at four rig sizes</h2>
 * Because the failure mode that matters is not a constant object per step but a <em>per-bone</em> one,
 * and a measurement at one bone count cannot tell the two apart. Measured at 1, 8, 20 and 32 simulated
 * bones — the shipped {@code krampus} rig declares 20 — the figure is flat zero, so the ceiling is
 * asserted against the widest rig as well as the narrowest.
 */
@EnabledIf(value = "allocationCountingAvailable",
        disabledReason = "this JVM exposes no per-thread allocation counter")
class SimulationStepAllocationTest {

    /** Enough iterations that one object every few hundred steps would still show in the average. */
    private static final int ITERATIONS = 200_000;

    /** Enough executions that the measured window is JIT-compiled steady state. See the probe. */
    private static final int WARMUP = 50_000;

    private static final long PERIOD_NANOS = 1_000_000_000L / SimFixtures.SIMULATION_HZ;

    /**
     * Less than one object.
     *
     * <p>Not zero, because the probe reports an average over a window and a single unlucky
     * deoptimisation inside it would put a few bytes on a path that allocates nothing. Sixteen bytes is
     * below the smallest object this JVM can produce, so anything that allocates even one object every
     * step — or every other step — is over it, which is the regression being guarded.
     */
    private static final double LESS_THAN_ONE_OBJECT = 16D;

    static boolean allocationCountingAvailable() {
        return AllocationProbe.isSupported();
    }

    @Test
    void aSteppingSimulationAllocatesNothingAtOneBone() {
        assertFlat(1);
    }

    @Test
    void aSteppingSimulationAllocatesNothingAtEightBones() {
        assertFlat(8);
    }

    @Test
    void aSteppingSimulationAllocatesNothingAtTheSizeOfAShippedRig() {
        // krampus declares twenty simulated bones, which is the largest thing this plugin ships.
        assertFlat(20);
    }

    /**
     * The cost must not grow with the bone count.
     *
     * <p>The one assertion a single-size measurement cannot make. A per-bone allocation — the obvious
     * regression, since every intermediate in the solver is a candidate for being wrapped in an object —
     * would show here as a figure that scales and nowhere else.
     */
    @Test
    void theCostDoesNotGrowWithTheBoneCount() {
        final double narrow = measure(1);
        final double wide = measure(32);
        assertTrue(wide < LESS_THAN_ONE_OBJECT,
                "a 32-bone rig allocated " + wide + " B/step; something in the solver is allocating"
                        + " per bone");
        assertTrue(wide - narrow < LESS_THAN_ONE_OBJECT,
                "stepping 32 bones allocated " + (wide - narrow) + " B/step more than stepping one,"
                        + " so the cost is per bone rather than per step");
    }

    /**
     * And the emission borrows the pose rather than copying it.
     *
     * <p>Stated as an identity assertion beside the byte count, because it is the one claim that holds
     * whatever the JIT decides: a sink that received two different poses would be receiving copies, which
     * is a {@code boneCount * 3} float array per emission per model.
     */
    @Test
    void everyEmissionBorrowsTheSamePoseBuffer() {
        final BorrowedPoseSink sink = new BorrowedPoseSink();
        final ModelSimulation simulation = new ModelSimulation(
                chain(8), SimFixtures.source(SimFixtures.realSolver()), sink);
        simulation.driver().publish(new PhysicsDriver(0.2F, 0.05F, 0F, 0.03F));

        long now = 0L;
        for (int step = 0; step < 60; step++) {
            now += PERIOD_NANOS;
            simulation.step(now);
        }

        assertTrue(sink.accepted > 1, "the model should have emitted more than once");
        assertEquals(1, sink.distinct.size(),
                "every emission must borrow one reused pose, not a copy per emission");
    }

    private static void assertFlat(int bones) {
        final double bytes = measure(bones);
        assertTrue(bytes < LESS_THAN_ONE_OBJECT,
                "a simulation step over " + bones + " simulated bones allocated " + bytes
                        + " B; the off-tick step is contractually allocation-free");
    }

    /** Bytes one step allocates for a rig with {@code bones} simulated bones hanging off a root. */
    private static double measure(int bones) {
        final Rig rig = chain(bones);
        final ModelSimulation simulation = new ModelSimulation(
                rig, SimFixtures.source(SimFixtures.realSolver()), new CountingSink());
        // A moving owner, so nothing settles and the whole path — the wind term, every substep, the
        // at-rest test and the emission — runs on every measured step. A resting model would measure the
        // early return instead.
        simulation.driver().publish(new PhysicsDriver(0.2F, 0.05F, 0F, 0.03F));

        final long[] now = {0L};
        final Runnable step = () -> {
            now[0] += PERIOD_NANOS;
            simulation.step(now[0]);
        };
        return AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS, step);
    }

    /**
     * A root with {@code springs} simulated bones below it, alternating the two physics kinds.
     *
     * <p>A chain rather than a star, because a chain is what makes the solver's ancestor walk do work:
     * bone <em>n</em> inherits every rotation above it, so the per-axis driver sum is the deepest it can
     * be and nothing in the loop is skipped.
     */
    private static Rig chain(int springs) {
        final Rig.Builder builder = SimFixtures.rig("measured", "model").root("body");
        String parent = "body";
        for (int bone = 0; bone < springs; bone++) {
            final String name = "bone" + bone;
            builder.bone(name, parent, PhysicsProfile
                    .builder(bone % 2 == 0 ? PhysicsKind.SPRING : PhysicsKind.DRAG,
                            SimFixtures.PROFILE_BOUNDS, SimFixtures.PROFILE_DEFAULTS)
                    .mass(1F)
                    .damping(0.1F)
                    .wind(0.5F)
                    .reach(0F, -1F, 0F)
                    .build());
            parent = name;
        }
        return builder.build();
    }

    /** Counts emissions and allocates nothing, so the figure is the step's and not the sink's. */
    private static final class CountingSink implements PoseSink {

        private long accepted;

        @Override
        public void accept(Rig rig, Pose pose, int interpolationTicks) {
            this.accepted++;
        }
    }

    /** Remembers which pose instances it was handed, by identity. */
    private static final class BorrowedPoseSink implements PoseSink {

        private final Set<Pose> distinct =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private long accepted;

        @Override
        public void accept(Rig rig, Pose pose, int interpolationTicks) {
            this.accepted++;
            this.distinct.add(pose);
        }
    }
}
