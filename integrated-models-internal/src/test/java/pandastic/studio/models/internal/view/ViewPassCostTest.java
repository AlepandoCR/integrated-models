package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import pandastic.studio.models.internal.perf.AllocationProbe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one culling pass costs, at the scale that makes it the widest loop in the plugin.
 *
 * <h2>Why this loop and not another</h2>
 * Every other hot path here is per model or per vehicle. This one is per <strong>(model, viewer)
 * pair</strong>: four hundred models and forty players is sixteen thousand decisions a pass, ten passes a
 * second at the shipped {@code sampleIntervalTicks}, so a hundred and sixty thousand decisions a second.
 * Nothing else in the plugin has that shape, which makes it both the place a wasted multiply actually
 * costs something and the only place worth asking a vectorisation question about.
 *
 * <p>It runs on the async scheduler, so none of it is on a region thread — but "off the tick" is not
 * "free": it is a thread the server also uses, and the pass is the one piece of work whose cost grows as
 * the product of two things an operator scales independently.
 *
 * <h2>What is asserted</h2>
 * Allocation per pass, as a ceiling expressed <em>per pair</em> so the figure does not have to be
 * restated when the fixture size changes, plus the decision count — a pass over a steady state must
 * change nothing, because every show and every hide is a packet.
 */
@EnabledIf(value = "allocationCountingAvailable",
        disabledReason = "this JVM exposes no per-thread allocation counter")
class ViewPassCostTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());

    /** Four hundred models is a decoration-heavy server; forty players is a busy one. */
    private static final int MODELS = 400;
    private static final int VIEWERS = 40;

    private static final int ITERATIONS = 200;
    private static final int WARMUP = 2_000;

    static boolean allocationCountingAvailable() {
        return AllocationProbe.isSupported();
    }

    private static ViewSettings settings() {
        return new ViewSettings(
                40D,    // viewRangeBlocks
                7D,     // nearRadiusBlocks
                6D,     // exitMarginBlocks
                50D,    // coneEnterDegrees
                65D,    // coneExitDegrees
                4D,     // cameraOffsetBlocks
                1.5D,   // defaultModelRadiusBlocks
                0.8D,   // maxViewerSpeedBlocksPerTick
                2,      // sampleIntervalTicks
                false,  // honourClientViewDistance
                4);     // spawnBudgetPerViewer
    }

    /**
     * A pass over a settled world allocates little per pair, and changes nothing.
     *
     * <p>Settled is the case that matters: a pass decides something on the few passes after a player
     * moves and decides nothing on all the others, so the steady state is what the cost is paid in. A
     * pass that keeps finding work to do here would be a hysteresis bug, which is why the decision count
     * is asserted beside the bytes rather than in a separate test.
     */
    @Test
    void aSettledPassIsCheapPerPairAndDecidesNothing() {
        final World world = spread();
        final ViewportCuller culler = new ViewportCuller();
        final ViewSettings settings = settings();

        // Two passes to settle: the first shows what the budget allows, and the budget is per viewer per
        // pass, so a crowded start takes several. Driven until it reports nothing left to do.
        for (int pass = 0; pass < 200; pass++) {
            if (culler.pass(world.models, world.viewers, settings).isIdle()) {
                break;
            }
        }
        final ViewportCuller.Result settled = culler.pass(world.models, world.viewers, settings);

        final double bytes = AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS,
                () -> culler.pass(world.models, world.viewers, settings));
        final double perPair = bytes / (MODELS * VIEWERS);

        assertAll(
                () -> assertTrue(settled.isIdle(),
                        "a settled pass must decide nothing: every show and hide is a packet, and a"
                                + " pass that keeps changing its mind is the flapping the hysteresis"
                                + " exists to prevent — got " + settled),
                () -> assertTrue(perPair < 4D,
                        "a settled pass allocated " + perPair + " B per (model, viewer) pair ("
                                + bytes + " B for " + MODELS + "x" + VIEWERS + "); the pass reads"
                                + " immutable snapshots and primitive positions, so the only thing it"
                                + " has to build is the candidate list of the shows it decided — and a"
                                + " settled pass decides none")
        );
    }

    /**
     * And the models no viewer holds — which on a culling server is most of them — cost nothing to sweep.
     *
     * <p>The stale-viewer sweep at the top of a pass exists for a player who disconnected, and it runs
     * over every model whether or not anybody holds it. A model held by nobody has nothing to sweep, and
     * the figure below says so: a pass over four hundred unheld models in a world with no viewers at all
     * must not allocate per model.
     */
    @Test
    void aPassOverModelsNobodyHoldsAllocatesAlmostNothing() {
        final World world = spread();
        final ViewportCuller culler = new ViewportCuller();
        final ViewSettings settings = settings();
        final Map<UUID, ViewerSnapshot> nobody = new HashMap<>();

        final double bytes = AllocationProbe.bytesPerIteration(WARMUP, ITERATIONS,
                () -> culler.pass(world.models, nobody, settings));
        final double perModel = bytes / MODELS;

        assertAll(
                () -> assertEquals(0, culler.pass(world.models, nobody, settings).hidden(),
                        "there is nobody to hide anything from"),
                () -> assertTrue(perModel < 4D,
                        "a pass over " + MODELS + " unheld models allocated " + perModel
                                + " B per model (" + bytes + " B total); a model no viewer holds has"
                                + " nothing to sweep, so the sweep must not build anything for it")
        );
    }

    /** Models and viewers scattered so that a realistic fraction of the pairs are visible. */
    private static World spread() {
        final List<CullableModel> models = new ArrayList<>(MODELS);
        for (int model = 0; model < MODELS; model++) {
            // A grid four blocks apart, eighty blocks on a side, so every band of the decision is
            // exercised: inside the near radius, inside the cone, outside the range, behind the viewer.
            final double x = (model % 20) * 4D;
            final double z = (model / 20) * 4D;
            models.add(new QuietModel(WORLD, x, 64D, z));
        }

        final Map<UUID, ViewerSnapshot> viewers = new HashMap<>();
        for (int viewer = 0; viewer < VIEWERS; viewer++) {
            final UUID id = UUID.nameUUIDFromBytes(("viewer" + viewer).getBytes());
            final double x = (viewer % 8) * 10D;
            final double z = (viewer / 8) * 16D;
            viewers.put(id, new ViewerSnapshot(
                    id, WORLD, x, 65D, z, 0D, 0D, 1D, 32, 0L));
        }
        return new World(models, viewers);
    }

    private record World(
            @NotNull List<CullableModel> models,
            @NotNull Map<UUID, ViewerSnapshot> viewers
    ) {
    }

    /** A cullable model that records nothing and sends nothing, so the figure is the pass's own. */
    private static final class QuietModel implements CullableModel {

        private final @NotNull UUID id = UUID.randomUUID();
        private final @NotNull UUID world;
        private final @NotNull ModelViewport viewport = new ModelViewport();
        private final double x;
        private final double y;
        private final double z;

        private QuietModel(@NotNull UUID world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public @NotNull UUID modelId() {
            return this.id;
        }

        @Override
        public @NotNull UUID worldId() {
            return this.world;
        }

        @Override
        public double cullX() {
            return this.x;
        }

        @Override
        public double cullY() {
            return this.y;
        }

        @Override
        public double cullZ() {
            return this.z;
        }

        @Override
        public @NotNull ModelViewport viewport() {
            return this.viewport;
        }

        @Override
        public @NotNull ModelViewportSink viewportSink() {
            return ModelViewportSink.NONE;
        }

        @Override
        public boolean isCullable() {
            return true;
        }
    }
}
