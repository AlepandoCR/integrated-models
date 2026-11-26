package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How many times a pass reads one model's position, per viewer.
 *
 * <h2>Why count this rather than time it</h2>
 * Because the cost of the culling pass is a product — models times viewers — and a timing is the one
 * thing that cannot be asserted in a unit test without being flaky. A <em>count</em> is exact,
 * reproducible and the thing that actually regressed: the pass measured each pair's distance, and then
 * {@code shouldBeShown} measured it again from the same inputs and threw the first one away. One world
 * comparison, three subtractions and three multiplies per pair, discarded, at four hundred models
 * against forty players ten times a second.
 *
 * <p>{@link CullableModel#cullX()} is read exactly once by each of the two position-reading operations a
 * decision can perform — the distance and, for a pair outside the near zone, the view-cone projection —
 * so counting calls to it counts those operations with no instrumentation inside the thing under test.
 *
 * <p>These figures are therefore ceilings in the same sense the allocation ones are, but they are exact
 * rather than approximate: the JIT cannot change how many times an interface method is called.
 */
class ViewPassWorkTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());
    private static final UUID ELSEWHERE = UUID.nameUUIDFromBytes("elsewhere".getBytes());

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

    /** One viewer at the origin looking along +Z, which is Minecraft's yaw 0. */
    private static Map<UUID, ViewerSnapshot> oneViewer() {
        final UUID id = UUID.nameUUIDFromBytes("viewer".getBytes());
        final Map<UUID, ViewerSnapshot> viewers = new HashMap<>();
        viewers.put(id, new ViewerSnapshot(id, WORLD, 0D, 0D, 0D, 0D, 0D, 1D, 32, 0L));
        return viewers;
    }

    /**
     * A model in another world costs one distance measurement and nothing else.
     *
     * <p>The world mismatch answers infinity, so the range test rejects it without any geometry. Two
     * before the distance was hoisted, because the pass measured the infinity and then the decision
     * measured it again.
     */
    @Test
    void aModelInAnotherWorldCostsOneDistanceMeasurement() {
        final CountingModel model = new CountingModel(ELSEWHERE, 0D, 0D, 5D);

        new ViewportCuller().pass(List.of(model), oneViewer(), settings());

        assertEquals(1, model.positionReads,
                "the pass and the decision must share one distance measurement");
    }

    /**
     * A model beyond the range costs one, too: the range test is the first thing the distance decides.
     */
    @Test
    void aModelBeyondTheRangeCostsOneDistanceMeasurement() {
        final CountingModel model = new CountingModel(WORLD, 0D, 0D, 400D);

        new ViewportCuller().pass(List.of(model), oneViewer(), settings());

        assertEquals(1, model.positionReads);
    }

    /**
     * And a model inside the near radius costs one: direction is not considered at all in there.
     *
     * <p>Which is the point of the near zone rather than an accident of it — see {@link ViewSettings}.
     */
    @Test
    void aModelInsideTheNearRadiusCostsOneDistanceMeasurement() {
        final CountingModel model = new CountingModel(WORLD, 0D, 0D, 3D);

        new ViewportCuller().pass(List.of(model), oneViewer(), settings());

        assertEquals(1, model.positionReads);
    }

    /**
     * A model in the cone band costs two: the distance, then the projection onto the look axis.
     *
     * <p>The most expensive pair there is, and three before the hoist. Nothing in the decision reads a
     * position a third time, so this is the ceiling for the whole pass.
     */
    @Test
    void aModelInTheConeBandCostsTheDistanceAndOneProjection() {
        final CountingModel model = new CountingModel(WORLD, 0D, 0D, 20D);

        new ViewportCuller().pass(List.of(model), oneViewer(), settings());

        assertEquals(2, model.positionReads,
                "a pair outside the near zone and inside the range costs the distance plus the"
                        + " view-cone projection, and nothing may read the position a third time");
    }

    /** And the figure does not change on the pass that keeps a model already shown. */
    @Test
    void theSecondPassOverAShownModelCostsTheSame() {
        final CountingModel model = new CountingModel(WORLD, 0D, 0D, 20D);
        final ViewportCuller culler = new ViewportCuller();
        final Map<UUID, ViewerSnapshot> viewers = oneViewer();
        final ViewSettings settings = settings();

        culler.pass(List.of(model), viewers, settings);
        model.positionReads = 0;
        culler.pass(List.of(model), viewers, settings);

        assertEquals(2, model.positionReads,
                "the hysteresis side changes which margin is compared, not how many times the position"
                        + " is read");
    }

    /** A model that counts how many times a pass read its position. */
    private static final class CountingModel implements CullableModel {

        private final @NotNull UUID id = UUID.randomUUID();
        private final @NotNull UUID world;
        private final @NotNull ModelViewport viewport = new ModelViewport();
        private final double x;
        private final double y;
        private final double z;

        private int positionReads;

        private CountingModel(@NotNull UUID world, double x, double y, double z) {
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

        /** The counted one: every operation that reads a position reads this exactly once. */
        @Override
        public double cullX() {
            this.positionReads++;
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
