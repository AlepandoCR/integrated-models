package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportCullerTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());
    private static final UUID OTHER_WORLD = UUID.nameUUIDFromBytes("other".getBytes());

    /**
     * Deliberately odd values that disagree with anything a plausible default would be, so a leftover
     * hardcoded fallback fails rather than coincides.
     */
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
                4,      // sampleIntervalTicks
                false,  // honourClientViewDistance
                2);     // spawnBudgetPerViewer
    }

    /** A viewer at the origin looking along +Z, which is Minecraft's yaw 0. */
    private static ViewerSnapshot viewerFacingPositiveZ() {
        return new ViewerSnapshot(
                UUID.nameUUIDFromBytes("viewer".getBytes()), WORLD,
                0D, 0D, 0D, 0D, 0D, 1D, 32, 0L);
    }

    private static Map<UUID, ViewerSnapshot> viewers(ViewerSnapshot... snapshots) {
        final Map<UUID, ViewerSnapshot> map = new HashMap<>();
        for (final ViewerSnapshot snapshot : snapshots) {
            map.put(snapshot.playerId(), snapshot);
        }
        return map;
    }

    // =============================================================================================
    // The two zones
    // =============================================================================================

    @Test
    void aModelBehindTheViewerButCloseIsStillShown() {
        // The whole point of the near zone: direction is not considered, so a model at the viewer's
        // back is shown anyway. Without this, turning on the spot would re-send every bone.
        final FakeModel behind = new FakeModel(WORLD, 0D, 0D, -5D);
        assertTrue(ViewportCuller.shouldBeShown(behind, viewerFacingPositiveZ(), settings(), false));
    }

    @Test
    void aModelToTheSideAndCloseIsStillShown() {
        final FakeModel beside = new FakeModel(WORLD, 6D, 0D, 0D);
        assertTrue(ViewportCuller.shouldBeShown(beside, viewerFacingPositiveZ(), settings(), false));
    }

    @Test
    void aModelToTheSideAndFarIsCulled() {
        // Perpendicular to the look axis, well outside the near zone: no camera mode can see it.
        final FakeModel beside = new FakeModel(WORLD, 25D, 0D, 0D);
        assertFalse(ViewportCuller.shouldBeShown(beside, viewerFacingPositiveZ(), settings(), false));
    }

    @Test
    void aModelAheadAndFarIsShown() {
        final FakeModel ahead = new FakeModel(WORLD, 0D, 0D, 30D);
        assertTrue(ViewportCuller.shouldBeShown(ahead, viewerFacingPositiveZ(), settings(), false));
    }

    // =============================================================================================
    // The bowtie — the camera-mode correctness property
    // =============================================================================================

    @Test
    void aModelDirectlyBehindAndFarIsShownBecauseAFrontCameraWouldSeeIt() {
        // This is the test that distinguishes a bowtie from a cone. In third-person front view the
        // camera sits ahead of the player looking back, so what it renders is what lies behind them.
        // A one-sided forward cone would cull exactly what that player is looking at, and the server
        // cannot know which camera is in use — nothing in the protocol carries it.
        final FakeModel behind = new FakeModel(WORLD, 0D, 0D, -30D);
        assertTrue(ViewportCuller.shouldBeShown(behind, viewerFacingPositiveZ(), settings(), false),
                "a far model directly behind must stay visible for a front-facing camera");
    }

    @Test
    void theBowtieIsSymmetricAboutTheLookAxis() {
        final ViewSettings settings = settings();
        final ViewerSnapshot viewer = viewerFacingPositiveZ();

        // Mirrored pairs at the same angle off the axis must agree, forward and backward.
        for (final double offset : new double[]{5D, 12D, 20D}) {
            final boolean ahead = ViewportCuller.shouldBeShown(
                    new FakeModel(WORLD, offset, 0D, 25D), viewer, settings, false);
            final boolean behind = ViewportCuller.shouldBeShown(
                    new FakeModel(WORLD, offset, 0D, -25D), viewer, settings, false);
            assertEquals(ahead, behind,
                    "offset " + offset + " must be decided the same way in front and behind");
        }
    }

    @Test
    void onlyThePerpendicularBandIsEverCulledDirectionally() {
        // At a fixed far distance, sweep the full circle and assert the culled set is a band around
        // perpendicular rather than a half-plane. A one-sided cone would cull roughly half of these.
        final ViewSettings settings = settings();
        final ViewerSnapshot viewer = viewerFacingPositiveZ();
        final double distance = 25D;

        int visible = 0;
        for (int degrees = 0; degrees < 360; degrees += 5) {
            final double radians = Math.toRadians(degrees);
            final FakeModel model = new FakeModel(
                    WORLD, distance * Math.sin(radians), 0D, distance * Math.cos(radians));
            if (ViewportCuller.shouldBeShown(model, viewer, settings, false)) {
                visible++;
            }
        }
        final int total = 360 / 5;
        assertTrue(visible > total / 2,
                "a bowtie must admit more than half of all directions, admitted " + visible
                        + " of " + total);
        assertTrue(visible < total,
                "but not all of them, or the angular test is doing nothing");
    }

    // =============================================================================================
    // Hysteresis
    // =============================================================================================

    @Test
    void aShownModelSurvivesSlightlyPastTheRangeItEnteredAt() {
        final ViewSettings settings = settings();
        final ViewerSnapshot viewer = viewerFacingPositiveZ();
        // Range is 40 + 4 of camera offset = 44; exit adds 6 more.
        final FakeModel justOutside = new FakeModel(WORLD, 0D, 0D, 47D);

        assertAll(
                () -> assertFalse(
                        ViewportCuller.shouldBeShown(justOutside, viewer, settings, false),
                        "a hidden model at 47 must stay hidden"),
                () -> assertTrue(
                        ViewportCuller.shouldBeShown(justOutside, viewer, settings, true),
                        "a shown model at 47 must be kept — that is the hysteresis"));
    }

    @Test
    void aShownModelSurvivesSlightlyPastTheAngleItEnteredAt() {
        final ViewSettings settings = settings();
        final ViewerSnapshot viewer = viewerFacingPositiveZ();
        // 57 degrees off axis: past the 50-degree enter angle, inside the 65-degree exit angle.
        final double radians = Math.toRadians(57D);
        final double distance = 25D;
        final FakeModel edge = new FakeModel(
                WORLD, distance * Math.sin(radians), 0D, distance * Math.cos(radians));

        assertAll(
                () -> assertFalse(ViewportCuller.shouldBeShown(edge, viewer, settings, false)),
                () -> assertTrue(ViewportCuller.shouldBeShown(edge, viewer, settings, true)));
    }

    @Test
    void aBigModelIsNotCulledWhilePartOfItIsStillOnScreen() {
        final ViewSettings settings = settings();
        final ViewerSnapshot viewer = viewerFacingPositiveZ();
        final double radians = Math.toRadians(57D);
        final double distance = 25D;
        final double x = distance * Math.sin(radians);
        final double z = distance * Math.cos(radians);

        // Same centre, outside the enter angle as a point, but a large enough sphere reaches in.
        assertAll(
                () -> assertFalse(ViewportCuller.shouldBeShown(
                        new FakeModel(WORLD, x, 0D, z, 1.5D), viewer, settings, false)),
                () -> assertTrue(ViewportCuller.shouldBeShown(
                        new FakeModel(WORLD, x, 0D, z, 12D), viewer, settings, false),
                        "a model whose bounding sphere reaches into the view must be shown"));
    }

    // =============================================================================================
    // World, eligibility, range
    // =============================================================================================

    @Test
    void aModelInAnotherWorldIsNeverShown() {
        final FakeModel elsewhere = new FakeModel(OTHER_WORLD, 0D, 0D, 1D);
        assertAll(
                () -> assertFalse(
                        ViewportCuller.shouldBeShown(elsewhere, viewerFacingPositiveZ(), settings(), false)),
                () -> assertFalse(
                        ViewportCuller.shouldBeShown(elsewhere, viewerFacingPositiveZ(), settings(), true),
                        "not even one that was shown before the viewer changed world"));
    }

    @Test
    void anIneligibleModelIsNeverShownHoweverCloseItIs() {
        final FakeModel binding = new FakeModel(WORLD, 0D, 0D, 1D);
        binding.cullable = false;
        assertFalse(ViewportCuller.shouldBeShown(binding, viewerFacingPositiveZ(), settings(), false));
    }

    @Test
    void theClientsOwnRenderDistanceCapsTheRangeWhenHonoured() {
        final ViewSettings honouring = new ViewSettings(
                40D, 7D, 6D, 50D, 65D, 4D, 1.5D, 0.8D, 4, true, 2);
        // Two chunks of render distance is 32 blocks, which is less than the configured 44.
        final ViewerSnapshot shortSighted = new ViewerSnapshot(
                UUID.nameUUIDFromBytes("short".getBytes()), WORLD,
                0D, 0D, 0D, 0D, 0D, 1D, 2, 0L);
        final FakeModel ahead = new FakeModel(WORLD, 0D, 0D, 35D);

        assertAll(
                () -> assertFalse(ViewportCuller.shouldBeShown(ahead, shortSighted, honouring, false),
                        "a client that cannot draw that far must not be sent it"),
                () -> assertTrue(ViewportCuller.shouldBeShown(
                        ahead, shortSighted, settings(), false),
                        "and must be sent it when the server is configured not to care"));
    }

    // =============================================================================================
    // The pass
    // =============================================================================================

    @Test
    void aPassShowsAndHidesThroughTheSink() {
        final ViewportCuller culler = new ViewportCuller();
        final FakeModel near = new FakeModel(WORLD, 0D, 0D, 3D);
        final ViewerSnapshot viewer = viewerFacingPositiveZ();

        final ViewportCuller.Result first = culler.pass(
                List.of(near), viewers(viewer), settings());
        assertAll(
                () -> assertEquals(1, first.shown()),
                () -> assertEquals(List.of("show:" + viewer.playerId()), near.sink.calls),
                () -> assertTrue(near.viewport().isShownTo(viewer.playerId())));

        near.z = 200D;
        final ViewportCuller.Result second = culler.pass(
                List.of(near), viewers(viewer), settings());
        assertAll(
                () -> assertEquals(1, second.hidden()),
                () -> assertEquals("hide:" + viewer.playerId(), near.sink.calls.get(1)),
                () -> assertFalse(near.viewport().isShownTo(viewer.playerId())));
    }

    @Test
    void anUnchangedPassSendsNothing() {
        final ViewportCuller culler = new ViewportCuller();
        final FakeModel near = new FakeModel(WORLD, 0D, 0D, 3D);
        final ViewerSnapshot viewer = viewerFacingPositiveZ();

        culler.pass(List.of(near), viewers(viewer), settings());
        near.sink.calls.clear();

        final ViewportCuller.Result idle = culler.pass(List.of(near), viewers(viewer), settings());
        assertAll(
                () -> assertTrue(idle.isIdle()),
                () -> assertTrue(near.sink.calls.isEmpty(),
                        "a model already shown must not be re-sent, or culling would cost more than"
                                + " it saves"));
    }

    @Test
    void theBudgetServesTheNearestFirstAndDefersTheRest() {
        final ViewportCuller culler = new ViewportCuller();
        final FakeModel nearest = new FakeModel(WORLD, 0D, 0D, 2D);
        final FakeModel middle = new FakeModel(WORLD, 0D, 0D, 4D);
        final FakeModel furthest = new FakeModel(WORLD, 0D, 0D, 6D);
        final ViewerSnapshot viewer = viewerFacingPositiveZ();

        // Budget is 2, and all three are inside the near zone, so all three want showing.
        final ViewportCuller.Result first = culler.pass(
                List.of(furthest, middle, nearest), viewers(viewer), settings());

        assertAll(
                () -> assertEquals(2, first.shown()),
                () -> assertEquals(1, first.deferred()),
                () -> assertTrue(nearest.viewport().isShownTo(viewer.playerId()), "nearest first"),
                () -> assertTrue(middle.viewport().isShownTo(viewer.playerId())),
                () -> assertFalse(furthest.viewport().isShownTo(viewer.playerId()),
                        "the furthest waits for the next pass"));

        final ViewportCuller.Result second = culler.pass(
                List.of(furthest, middle, nearest), viewers(viewer), settings());
        assertAll(
                () -> assertEquals(1, second.shown()),
                () -> assertEquals(0, second.deferred()),
                () -> assertTrue(furthest.viewport().isShownTo(viewer.playerId()),
                        "a deferred show is picked up, not lost"));
    }

    @Test
    void hidesAreNeverBudgeted() {
        // Hides only save bandwidth, so metering them would be backwards: a pass must be able to drop
        // everything at once.
        final ViewportCuller culler = new ViewportCuller();
        final List<FakeModel> models = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            models.add(new FakeModel(WORLD, 0D, 0D, 2D));
        }
        final ViewerSnapshot viewer = viewerFacingPositiveZ();
        for (final FakeModel model : models) {
            model.viewport().markShown(viewer.playerId());
        }

        for (final FakeModel model : models) {
            model.z = 500D;
        }
        final ViewportCuller.Result result = culler.pass(models, viewers(viewer), settings());

        assertAll(
                () -> assertEquals(10, result.hidden()),
                () -> assertEquals(0, result.shown()));
    }

    @Test
    void aViewerWhoLeftIsForgottenWithoutAPacket() {
        final ViewportCuller culler = new ViewportCuller();
        final FakeModel model = new FakeModel(WORLD, 0D, 0D, 3D);
        final ViewerSnapshot viewer = viewerFacingPositiveZ();

        culler.pass(List.of(model), viewers(viewer), settings());
        model.sink.calls.clear();

        // The player is gone from the snapshot entirely.
        final ViewportCuller.Result result = culler.pass(List.of(model), Map.of(), settings());

        assertAll(
                () -> assertEquals(1, result.forgotten()),
                () -> assertEquals(0, result.hidden()),
                () -> assertTrue(model.sink.calls.isEmpty(),
                        "despawning for an absent client is a send to nobody"),
                () -> assertFalse(model.viewport().isShownTo(viewer.playerId()),
                        "and leaving them recorded would never repair itself"));
    }

    @Test
    void twoViewersAreDecidedIndependently() {
        final ViewportCuller culler = new ViewportCuller();
        final FakeModel model = new FakeModel(WORLD, 0D, 0D, 30D);
        final ViewerSnapshot looking = viewerFacingPositiveZ();
        // Looking along +X, so a model 30 blocks along +Z is perpendicular and culled.
        final ViewerSnapshot lookingAway = new ViewerSnapshot(
                UUID.nameUUIDFromBytes("away".getBytes()), WORLD,
                0D, 0D, 0D, 1D, 0D, 0D, 32, 0L);

        culler.pass(List.of(model), viewers(looking, lookingAway), settings());

        assertAll(
                () -> assertTrue(model.viewport().isShownTo(looking.playerId())),
                () -> assertFalse(model.viewport().isShownTo(lookingAway.playerId())),
                () -> assertEquals(1, model.viewport().shownCount()));
    }

    // =============================================================================================
    // Settings validation
    // =============================================================================================

    @Test
    void refusesAMarginAViewerCouldCrossBetweenSamples() {
        // The anti-flap guarantee. 0.8 blocks/tick over 8 ticks is 6.4 blocks, so a 6-block margin
        // gives no hysteresis at all and every flip costs a full respawn.
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new ViewSettings(40D, 7D, 6D, 50D, 65D, 4D, 1.5D, 0.8D, 8, false, 2));
        assertTrue(thrown.getMessage().contains("crossed between samples"));
    }

    @Test
    void refusesAnExitAngleThatDoesNotExceedTheEnterAngle() {
        assertThrows(IllegalArgumentException.class,
                () -> new ViewSettings(40D, 7D, 6D, 50D, 50D, 4D, 1.5D, 0.8D, 4, false, 2));
    }

    @Test
    void refusesAHalfAngleAtOrPastNinetyDegrees() {
        // At 90 the two halves of the bowtie meet and every direction is admitted, which is the same
        // as having no angular test — better refused than silently inert.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ViewSettings(40D, 7D, 6D, 50D, 90D, 4D, 1.5D, 0.8D, 4, false, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ViewSettings(40D, 7D, 6D, 95D, 100D, 4D, 1.5D, 0.8D, 4, false, 2)));
    }

    @Test
    void refusesANearRadiusThatSwallowsTheWholeRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ViewSettings(10D, 10D, 6D, 50D, 65D, 4D, 1.5D, 0.8D, 4, false, 2));
    }

    @Test
    void refusesANonUnitLookVector() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerSnapshot(
                UUID.randomUUID(), WORLD, 0D, 0D, 0D, 0D, 0D, 2D, 32, 0L));
    }

    @Test
    void buildsAUnitLookVectorFromYawAndPitch() {
        // Yaw 0 faces +Z in Minecraft's convention; the constructor would reject a non-unit result.
        final ViewerSnapshot facing = ViewerSnapshot.of(
                UUID.randomUUID(), WORLD, 0D, 0D, 0D, 0F, 0F, 32, 0L);
        assertAll(
                () -> assertEquals(0D, facing.lookX(), 1.0E-9D),
                () -> assertEquals(0D, facing.lookY(), 1.0E-9D),
                () -> assertEquals(1D, facing.lookZ(), 1.0E-9D));

        final ViewerSnapshot down = ViewerSnapshot.of(
                UUID.randomUUID(), WORLD, 0D, 0D, 0D, 0F, 90F, 32, 0L);
        assertEquals(-1D, down.lookY(), 1.0E-9D, "a positive pitch looks down");
    }

    // =============================================================================================
    // Fakes
    // =============================================================================================

    private static final class RecordingSink implements ModelViewportSink {

        private final @NotNull List<String> calls = new ArrayList<>();

        @Override
        public void show(@NotNull UUID viewer) {
            this.calls.add("show:" + viewer);
        }

        @Override
        public void hide(@NotNull UUID viewer) {
            this.calls.add("hide:" + viewer);
        }
    }

    private static final class FakeModel implements CullableModel {

        private final @NotNull UUID id = UUID.randomUUID();
        private final @NotNull UUID world;
        private final @NotNull ModelViewport viewport = new ModelViewport();
        private final @NotNull RecordingSink sink = new RecordingSink();
        private final double radius;
        private double x;
        private double y;
        private double z;
        private boolean cullable = true;

        private FakeModel(@NotNull UUID world, double x, double y, double z) {
            this(world, x, y, z, 0D);
        }

        private FakeModel(@NotNull UUID world, double x, double y, double z, double radius) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
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
        public double cullRadius() {
            return this.radius;
        }

        @Override
        public @NotNull ModelViewport viewport() {
            return this.viewport;
        }

        @Override
        public @NotNull ModelViewportSink viewportSink() {
            return this.sink;
        }

        @Override
        public boolean isCullable() {
            return this.cullable;
        }
    }
}
