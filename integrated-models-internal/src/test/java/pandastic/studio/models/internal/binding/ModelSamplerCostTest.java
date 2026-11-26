package pandastic.studio.models.internal.binding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.perf.AllocationProbe;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a bound model costs <strong>on the thread that owns its entity</strong>, per tick.
 *
 * <h2>Why this thread and not the simulation's</h2>
 * Because on a regionised server it is a region thread and on an ordinary Paper server it <em>is the main
 * thread</em>, and the entity scheduler is the only dispatcher a model's entity read is allowed to use.
 * So every bound model contributes one body to that thread every sampling period, and whatever those
 * bodies do is work the tick pays for. The simulation, the physics, the gait and the emission are all off
 * it by design — {@code SimulationLoop} on the async scheduler — and the whole value of that design is
 * destroyed by anything that creeps back onto this side.
 *
 * <p>Two things are therefore asserted here and nowhere else:
 *
 * <ol>
 *   <li><strong>How many entity reads a tick costs</strong>, which must be one. The sampler reads the
 *       anchor once into one buffer and derives both the movement and the stance from that reading; a
 *       second read would also be a correctness bug, because the two halves would then come from
 *       different ticks.</li>
 *   <li><strong>That a simulation step performs none at all.</strong> This is the invariant the whole
 *       {@code DriverHolder} seam exists to make true, and it is the one that silently fails: a step that
 *       reached an entity would work perfectly on a single-region server and corrupt a coordinate on
 *       Folia, with no exception and no log line.</li>
 * </ol>
 *
 * <h2>And how much it allocates</h2>
 * One immutable driver per <em>change</em> of movement, and nothing else. A parked model — a decoration,
 * an idling mob, anything stationary, which is most of them on a server with hundreds — publishes the
 * same value every tick, and since {@code DriverSampler} hands the previous instance back for an
 * unchanged value it allocates nothing at all in the steady state. A moving one pays for the one record
 * it publishes.
 */
@EnabledIf(value = "allocationCountingAvailable",
        disabledReason = "this JVM exposes no per-thread allocation counter")
class ModelSamplerCostTest {

    private static final int ITERATIONS = 200_000;
    private static final int WARMUP = 50_000;

    /**
     * Two objects' worth.
     *
     * <p>The contract is "one driver per changed sample", so the honest ceiling for a <em>moving</em>
     * model is one record — 32 bytes measured. Twice that is the band in which a regression is a new
     * allocation site rather than a different object layout, and leaves room for the temporary the
     * teleport filter builds on a JVM whose escape analysis does not remove it.
     */
    private static final double TWO_OBJECTS = 80D;

    /**
     * Less than one driver record, which measures 32 bytes.
     *
     * <p>The ceiling for a model that is not moving, where the contract is that nothing is built at all.
     * Stated as a band rather than as zero because the figure is an average over a measured window and a
     * deoptimisation inside one would put a few bytes on a path with no allocation site; anything that
     * actually allocates per tick lands at 32 or above and fails.
     */
    private static final double LESS_THAN_ONE_DRIVER = 24D;

    private final Rig rig = BindingFixtures.rig();
    private final BindingFixtures.Journal journal = new BindingFixtures.Journal();
    private final FakeAnchor anchor = new FakeAnchor(this.journal);
    private final SimulationLoop loop = new SimulationLoop(
            new UnusedScheduler(), new StrictLogger(), BindingFixtures.simulationSource());

    static boolean allocationCountingAvailable() {
        return AllocationProbe.isSupported();
    }

    private RiggedModelImpl spawned() {
        final RiggedModelImpl model = new RiggedModelImpl(this.rig, this.anchor, this.loop,
                BindingFixtures.simulationSource(),
                new BindingFixtures.MutableBindingSource(BindingFixtures.SAMPLE_PERIOD_TICKS),
                LocomotionStageSource.NONE, BindingOptions.defaults(), stopped -> {
        });
        this.anchor.moveTo(0D, 64D, 0D, 0F);
        assertTrue(model.spawn(), "the fake anchor should allow a spawn");
        // The first sample seeds the differencer; a delta measured across a bind describes nothing.
        this.anchor.tick();
        return model;
    }

    // --------------------------------------------------------------- work counts

    /**
     * One tick of the sampler reads the anchor exactly once.
     *
     * <p>The spawn reads it too — it seeds the culler's position from the owning thread, which is where a
     * spawn already is — so the count is taken from a cleared baseline.
     */
    @Test
    void oneSamplingPeriodCostsExactlyOneEntityRead() {
        final RiggedModelImpl model = spawned();
        this.anchor.clearReads();

        this.anchor.tick();

        assertAll(
                () -> assertEquals(1, this.anchor.reads(),
                        "the movement and the stance must come from one reading of the entity"),
                () -> assertTrue(model.isAlive())
        );
    }

    /**
     * Ten ticks cost ten reads: the cost is per period and not per anything else.
     *
     * <p>Worth separating from the single-tick count because a read cached across ticks would pass that
     * one and be a different bug — a model whose position the culler stopped following.
     */
    @Test
    void theEntityReadCostIsLinearInTheSamplingPeriod() {
        spawned();
        this.anchor.clearReads();

        for (int tick = 0; tick < 10; tick++) {
            this.anchor.tick();
        }

        assertEquals(10, this.anchor.reads());
    }

    /**
     * A simulation step reads no entity at all.
     *
     * <p>The load-bearing one. The step runs on the async scheduler, where reading an entity is undefined
     * rather than merely racy, and the only reason it is legal to run there is that it cannot reach one.
     * Asserted by driving the real loop over the real simulation and counting.
     */
    @Test
    void steppingTheSimulationPerformsNoEntityReads() {
        final RiggedModelImpl model = spawned();
        this.anchor.clearReads();

        // The model's own simulation, stepped directly: the loop's iteration is package-private to the
        // simulation package, and what is being asserted is a property of the step rather than of the
        // dispatcher around it.
        final var simulation = model.simulation();
        assertTrue(simulation != null, "a spawned model has a simulation");
        long now = 0L;
        for (int iteration = 0; iteration < 100; iteration++) {
            now += 16_666_667L;
            simulation.step(now);
        }

        assertAll(
                () -> assertEquals(1, this.loop.size(), "the model should be registered"),
                () -> assertEquals(0, this.anchor.reads(),
                        "the off-tick step must never touch the entity: on a regionised server that is"
                                + " a torn coordinate rather than an exception"),
                () -> assertTrue(model.isAlive())
        );
    }

    // -------------------------------------------------------------- allocation

    /**
     * A parked model's sampler allocates nothing per tick.
     *
     * <p>The common case, and the one that multiplies: every stationary bound model on the server runs
     * this body once per sampling period forever. Its driver is the same four zeroes every time, so there
     * is nothing to publish that was not published last tick.
     */
    @Test
    void aParkedModelAllocatesNothingPerTick() {
        spawned();
        final double bytes = AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS, this.anchor::tick);

        assertTrue(bytes < LESS_THAN_ONE_DRIVER,
                "a parked model's sampler allocated " + bytes + " B/tick; its driver does not change"
                        + " from one tick to the next, so there is nothing for it to build");
    }

    /** And a moving one pays for the one record it publishes, not more. */
    @Test
    void aMovingModelAllocatesOneDriverPerTick() {
        spawned();
        final double[] walked = {0D};
        final Runnable tick = () -> {
            walked[0] += 0.08D;
            this.anchor.moveTo(walked[0], 64D, walked[0], (float) (walked[0] * 3D));
            this.anchor.tick();
        };

        final double bytes = AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS, tick);

        assertTrue(bytes < TWO_OBJECTS,
                "a moving model's sampler allocated " + bytes + " B/tick; the contract is one immutable"
                        + " driver, so anything beyond that is a new allocation site");
    }

    /**
     * Two consecutive unchanged samples publish the <em>same</em> driver instance.
     *
     * <p>The deterministic half of the claim above. A byte count depends on what the JIT managed to
     * remove; this does not, so it is what actually holds the line if a later change drops the reuse.
     */
    @Test
    void anUnchangedSamplePublishesTheSameInstance() {
        final RiggedModelImpl model = spawned();

        this.anchor.tick();
        final var first = model.driver().current();
        this.anchor.tick();
        final var second = model.driver().current();

        assertAll(
                () -> assertEquals(first, second, "an unchanged anchor must publish an equal driver"),
                () -> assertTrue(first == second,
                        "and the same instance, or every stationary model on the server allocates one"
                                + " record a tick to say it has not moved")
        );
    }

    /** A loop whose task nothing drives: the test calls {@code runIteration} by hand. */
    private static final class UnusedScheduler implements SimulationScheduler {

        @Override
        public SimulationTask runAtFixedRate(
                Runnable body,
                long initialDelay,
                long period,
                TimeUnit unit
        ) {
            throw new AssertionError("this test never starts the loop");
        }
    }

    /** Fails the test rather than swallowing a simulation failure. */
    private static final class StrictLogger implements SimulationLogger {

        @Override
        public void error(String message, Throwable thrown) {
            throw new AssertionError(message, thrown);
        }
    }
}
