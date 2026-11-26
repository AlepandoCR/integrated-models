package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehiclePilot;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.internal.perf.AllocationProbe;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one vehicle control step costs on the thread that owns the root entity: the two kinds of work
 * that reach the platform, and the bytes.
 *
 * <h2>Why this thread is the one that matters</h2>
 * A control step runs on the root entity's {@code EntityScheduler}. On a regionised server that spreads
 * across region threads; on an ordinary Paper server the entity scheduler <strong>is</strong> the main
 * thread, so every vehicle's step is work the tick pays for. Fifty vehicles at the shipped control period
 * is fifty bodies a tick on one thread.
 *
 * <h2>The two work counts, and why they are the figures worth defending</h2>
 * <ul>
 *   <li><strong>Block reads.</strong> The terrain probe is the only thing in the whole plugin that reads
 *       blocks, and a block read cannot be moved off the thread that owns the chunk — on a regionised
 *       server there is no "do it asynchronously" available and never will be. So the only lever is doing
 *       it <em>less</em>, which is what {@code vehicle.yml}'s sampling keys are, and a count per twenty
 *       steps is the only way to tell a gate that works from one that is wired up and inert.</li>
 *   <li><strong>Teleport transactions.</strong> Before the movement adapter landed, every displaced step
 *       of every vehicle took the relocation path, because {@code MovementBridge}'s declining default
 *       meant {@code supportsLocalMove()} was false on every server that existed. That is an asynchronous
 *       teleport per vehicle per step. The adapter is what made the in-place write reachable, and nothing
 *       in the suite proved the step takes it — so this does.</li>
 * </ul>
 *
 * <h2>And the bytes</h2>
 * Ceilings, not figures, and deliberately generous: the exact count depends on the JVM's object layout
 * and on how much the JIT scalar-replaced inside the measured window. What they guard is the regression
 * that puts a new per-step {@code new} into the path — a record per seat, a lambda that started
 * capturing, a settings source that stopped memoising — each of which is a multiple of the slack rather
 * than a few bytes inside it.
 *
 * <p>Measured against {@link QuietVehicleHost} and {@link VehicleVisual#NONE} rather than the ordering
 * fakes, which journal: three of {@link FakeVehicleHost}'s lines concatenate a float, so a step measured
 * against it reports the fixture's {@code StringBuilder}. See {@link QuietVehicleHost}.
 */
@EnabledIf(value = "allocationCountingAvailable",
        disabledReason = "this JVM exposes no per-thread allocation counter")
class VehicleStepCostTest {

    private static final int ITERATIONS = 100_000;
    private static final int WARMUP = 20_000;

    /** Nanoseconds in a server tick, which is what the control loop is dispatched at. */
    private static final long TICK_NANOS = 50_000_000L;

    static boolean allocationCountingAvailable() {
        return AllocationProbe.isSupported();
    }

    // ------------------------------------------------------------- work counts

    /**
     * A vehicle whose profile says nothing about terrain reads no blocks, ever.
     *
     * <p>Decided from a field computed at construction, so it is the one case that costs nothing per
     * step. This is every vehicle that existed before the terrain layer, and the default.
     */
    @Test
    void aVehicleWithNoTerrainResponseNeverReadsABlock() {
        final Harness harness = new Harness(true, false, true);
        harness.step(20);

        assertEquals(0, harness.host.probes(),
                "a profile inert to terrain must not probe: the gate is a field, not a decision");
    }

    /**
     * A terrain-reacting vehicle probes at most once per step, and in practice far less.
     *
     * <p>The ceiling is the invariant: one reading per step, taken before anything is decided, so the
     * lean and the climb cannot answer different hillsides. It is the ceiling and not the saving — the
     * fixture profile travels a block a tick, which clears the sampling deadband on every step, so this
     * vehicle genuinely does read the ground twenty times in twenty steps. What the keys save at
     * realistic speeds is measured by {@code VehicleTerrainSamplingTest}, which is about the gate; this
     * is about the step never asking twice.
     */
    @Test
    void aTerrainReactingVehicleProbesAtMostOncePerStep() {
        final Harness harness = new Harness(true, true, true);
        harness.step(20);

        assertAll(
                () -> assertTrue(harness.host.probes() > 0,
                        "a moving terrain-reacting vehicle has to read the ground at least once"),
                () -> assertTrue(harness.host.probes() <= 20,
                        "it probed " + harness.host.probes() + " times in 20 steps; at most one reading"
                                + " per step is the invariant the whole terrain layer is decided from")
        );
    }

    /**
     * With the movement adapter present, a driven vehicle performs no teleport transactions at all.
     *
     * <p>The line context item 4 is about. Every displaced step writes in place; a relocation is what the
     * seam always documented it as, a displacement past the configured threshold, rather than the only
     * path available.
     */
    @Test
    void aDrivenVehicleWithAnAdapterWritesInPlaceAndNeverTeleports() {
        final Harness harness = new Harness(true, false, true);
        harness.step(20);

        assertAll(
                () -> assertTrue(harness.host.moves() > 0, "it should have written a position"),
                () -> assertEquals(0, harness.host.teleports(),
                        "with an in-place move available, no step may cost a teleport transaction")
        );
    }

    /** And without one, every displaced step is a relocation — which is what the adapter removed. */
    @Test
    void withoutAnAdapterEveryDisplacedStepIsATeleport() {
        final Harness harness = new Harness(true, false, false);
        harness.step(20);

        assertAll(
                () -> assertEquals(0, harness.host.moves(),
                        "there is no in-place write to make"),
                () -> assertTrue(harness.host.teleports() > 0,
                        "so the displacement has to go through the platform's teleport")
        );
    }

    // -------------------------------------------------------------- allocation

    /**
     * A parked vehicle with no terrain response.
     *
     * <p>Measured at 296 B/step when this was written, which is <strong>more</strong> than a driven one
     * costs — see {@link #aDrivenVehicleStaysWithinItsPublishedValues}. The cause is the rest snap: a
     * settled vehicle's plan is built twice, once by the solver and once by the stepper's own
     * {@code settle}, and the first pair of values is discarded. The ceiling is set above the figure
     * rather than below it because closing that gap changes a signed zero in a discarded delta, which is
     * a judgement about the api's values rather than about its cost.
     */
    @Test
    void aParkedVehicleStaysWithinItsPublishedValues() {
        final double bytes = measure(false, false, true);
        assertTrue(bytes < 420D,
                "a parked step allocated " + bytes + " B; the step publishes a handful of small"
                        + " immutable values and should not be building more than that");
    }

    /** A vehicle being steered on flat ground, which is the ordinary driving case. */
    @Test
    void aDrivenVehicleStaysWithinItsPublishedValues() {
        final double bytes = measure(true, false, true);
        assertTrue(bytes < 296D,
                "a driven step allocated " + bytes + " B; measured at 200 when written, so this is a"
                        + " new allocation site rather than a different object layout");
    }

    /**
     * The same vehicle with a terrain response, where the probe fills a reused buffer.
     *
     * <p>Held at the same ceiling as the flat-ground case on purpose: the reading goes into
     * {@code VehicleImpl}'s own {@link TerrainSample} field, so adding terrain must not add an object per
     * step. A probe that handed out a fresh sample would show here and nowhere else.
     */
    @Test
    void addingATerrainResponseAddsNoPerStepObject() {
        final double bytes = measure(true, true, true);
        assertTrue(bytes < 296D,
                "a terrain-reacting step allocated " + bytes + " B; the probe must fill the reused"
                        + " buffer rather than hand out a sample per step");
    }

    /** And the relocation path is not the expensive one in bytes either. */
    @Test
    void aRelocatingVehicleIsNoMoreExpensiveInBytesThanADrivenOne() {
        final double bytes = measure(true, false, false);
        assertTrue(bytes < 336D,
                "a relocating step allocated " + bytes + " B; the teleport path decides less than an"
                        + " in-place one and must not allocate more");
    }

    private static double measure(boolean driven, boolean terrain, boolean localMove) {
        final Harness harness = new Harness(driven, terrain, localMove);
        return AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS, harness::stepOnce);
    }

    /** One started vehicle on a quiet host, ready to be stepped. */
    private static final class Harness {

        private final QuietVehicleHost host;
        private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();

        Harness(boolean driven, boolean terrain, boolean localMove) {
            this.host = new QuietVehicleHost().localMove(localMove);
            if (terrain) {
                this.host.terrain(64D, 64D);
            }
            // A pilot holding one key, because a rider would need a carrier and a Proxy player — and a
            // dynamic proxy allocates an argument array per call, which would be the fixture's cost in
            // the figure again. The pilot returns a constant, so nothing it does is measured either.
            final RiderIntent held = new RiderIntent(true, false, false, true, false, false, false);
            final VehiclePilot pilot = driven ? context -> held : VehiclePilot.NONE;

            final VehicleImpl vehicle = new VehicleImpl(
                    VehicleFixtures.rig(),
                    terrain
                            ? VehicleFixtures.terrainProfile(VehicleKind.GROUND,
                                    VehicleFixtures.snapping(20F, 25F, 10F, 1F, 4))
                            : VehicleFixtures.profile(),
                    this.host,
                    // The identity visual, which is quiet for the reason QuietVehicleHost exists.
                    VehicleVisual.NONE,
                    VehicleSettingsSource.fixed(VehicleFixtures.settings()),
                    this.clock,
                    (message, thrown) -> {
                        throw new AssertionError(message, thrown);
                    },
                    SeatChartSink.NONE,
                    VehicleSetup.DEFAULT,
                    RiderInputSource.fromHost(this.host),
                    VehicleImpl.reportingThrough((message, thrown) -> {
                        throw new AssertionError(message, thrown);
                    }),
                    pilot,
                    stopped -> {
                    });
            assertTrue(vehicle.start(), "the quiet host should allow a start");
            // The first step of a vehicle's life has nothing to difference against, so it decides
            // nothing. Its counts are cleared with it.
            this.host.runOnce();
            this.host.clearCounts();
        }

        void stepOnce() {
            this.clock.advanceNanos(TICK_NANOS);
            this.host.runOnce();
        }

        void step(int times) {
            for (int step = 0; step < times; step++) {
                stepOnce();
            }
        }
    }
}
