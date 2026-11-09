package pandastic.studio.models.internal.decoration;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoration handle: its lifecycle, its ordering, its viewers and the three things it refuses.
 *
 * <p>Every collaborator with a platform type behind it is a fake, which is the point of the anchor
 * seam. What is left is exactly the decisions this class makes — the order it builds and tears down in,
 * what it does when a thread is wrong, and whether its viewer registry tells the truth.
 */
class DecorationImplTest {

    private static final double TOLERANCE = 1.0E-9D;

    private final Rig rig = DecorationFixtures.rig();
    private final DecorationFixtures.Journal journal = new DecorationFixtures.Journal();
    private final FakeDecorationAnchor anchor = new FakeDecorationAnchor(this.journal);
    private final SimulationLoop loop = new SimulationLoop(
            new UnusedScheduler(), new StrictLogger(), DecorationFixtures.simulationSource());
    private final FakeOffTickScheduler driverScheduler = new FakeOffTickScheduler();
    private final DecorationDriverLoop drivers = new DecorationDriverLoop(
            this.driverScheduler,
            new SteppableClock(),
            DecorationSettingsSource.fixed(DecorationFixtures.calmSettings()),
            DecorationFixtures.simulationSource());
    private final AtomicInteger stopNotifications = new AtomicInteger();

    DecorationImplTest() {
        // Every journal entry then carries how many simulations and how many drivers were registered
        // when it was written, which is how an ordering claim about two loops that cannot record
        // anything becomes an assertion about recorded text.
        this.journal.observing(this.loop::size, this.drivers::size);
    }

    private DecorationImpl decoration() {
        return decoration(DecorationFixtures.simulationSource(), AmbientMotion.NONE);
    }

    private DecorationImpl decoration(AmbientMotion ambient) {
        return decoration(DecorationFixtures.simulationSource(), ambient);
    }

    private DecorationImpl decoration(SimulationSettingsSource settings, AmbientMotion ambient) {
        return new DecorationImpl(this.rig, this.anchor, this.loop, this.drivers, settings,
                DecorationFixtures.PLACEMENT, ambient,
                ignored -> this.stopNotifications.incrementAndGet());
    }

    // ------------------------------------------------------------------ spawn

    @Test
    void spawnOpensTheRenderingAndRegistersWithBothLoops() {
        final DecorationImpl decoration = decoration();

        assertTrue(decoration.spawn());

        assertAll(
                () -> assertTrue(decoration.isAlive()),
                () -> assertEquals(1, this.loop.size(), "the simulation must be registered"),
                () -> assertEquals(1, this.drivers.size(), "the driver must be registered"),
                () -> assertNotNull(this.anchor.rendering(), "the rendering must be opened"),
                // The rendering is opened before either registration: it is the step most likely to
                // fail and the cheapest to unwind, since nothing is registered yet.
                () -> assertEquals(List.of("open(stepping=0,driving=0)"), this.journal.entries()));
    }

    @Test
    void spawnOpensTheRenderingAtThePlacementTheHandleHolds() {
        final DecorationImpl decoration = decoration();

        decoration.spawn();

        assertAll(
                () -> assertEquals(DecorationFixtures.PLACEMENT, this.anchor.openedAt()),
                // The ownership question is asked about that same placement, because on a regionised
                // server the owning thread is a property of the chunk the position falls in.
                () -> assertEquals(List.of(DecorationFixtures.PLACEMENT),
                        this.anchor.ownershipQuestions()));
    }

    @Test
    void aDecorationDoesNotWalk() {
        final DecorationImpl decoration = decoration();

        decoration.spawn();

        // No locomotion stage, which is what makes its at-rest test the physics alone — and therefore
        // what makes "settled" mean "silent" without a gait to consult.
        assertAll(
                () -> assertNotNull(decoration.simulation()),
                () -> assertNull(decoration.simulation().locomotion()));
    }

    @Test
    void spawnRefusesToRunOffTheThreadThatOwnsThePlacement() {
        final DecorationImpl decoration = decoration();
        this.anchor.owned(false);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, decoration::spawn);

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("owns that position's region"),
                        () -> "must say why, got: " + thrown.getMessage()),
                () -> assertFalse(decoration.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(0, this.drivers.size()),
                // Nothing was created, so there is nothing to unwind — the guard comes before the work.
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void spawningTwiceIsANoOpRatherThanASecondTracker() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        this.journal.clear();

        assertAll(
                () -> assertFalse(decoration.spawn()),
                () -> assertEquals(1, this.loop.size()),
                () -> assertEquals(1, this.drivers.size()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void aSpentHandleRefusesToSpawnAgain() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        decoration.despawn();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, decoration::spawn);

        assertTrue(thrown.getMessage().contains("single"),
                () -> "must explain that a handle is single use, got: " + thrown.getMessage());
    }

    @Test
    void aFailedRegistrationUnwindsTheRenderingRatherThanLeavingItStanding() {
        // A cadence built for a thirty hertz loop, handed to a sixty hertz one: the registration that
        // refuses it is the realistic late failure, and what matters is what is left behind.
        final DecorationImpl decoration = decoration(
                SimulationSettingsSource.fixed(DecorationFixtures.simulationSettings(
                        30, 10, DecorationFixtures.DRIVER_BOUNDS)),
                AmbientMotion.NONE);

        assertThrows(IllegalArgumentException.class, decoration::spawn);

        final FakeDecorationRendering opened = this.anchor.rendering();
        assertAll(
                () -> assertFalse(decoration.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(0, this.drivers.size()),
                () -> assertNotNull(opened),
                // A tracker left open by a spawn that threw is a model standing in the world that
                // nothing holds a reference to, so nothing would ever close it.
                () -> assertTrue(opened.isDetached()),
                () -> assertTrue(opened.isClosed()),
                () -> assertEquals(
                        List.of("open(stepping=0,driving=0)", "detach(stepping=0,driving=0)",
                                "close(stepping=0,driving=0)"),
                        this.journal.entries()));
    }

    @Test
    void aFailedOpenLeavesNothingRegistered() {
        final DecorationImpl decoration = decoration();
        this.anchor.failOpenWith(new IllegalStateException("bone 'cloth' did not resolve"));

        assertThrows(IllegalStateException.class, decoration::spawn);

        assertAll(
                () -> assertFalse(decoration.isAlive()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(0, this.drivers.size()),
                () -> assertEquals(0, this.stopNotifications.get(),
                        "a failed spawn is not a teardown"));
    }

    // ------------------------------------------------------------------ teardown

    @Test
    void despawnLeavesBothLoopsBeforeItReleasesAnything() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        this.journal.clear();

        decoration.despawn();

        assertAll(
                () -> assertFalse(decoration.isAlive()),
                () -> assertTrue(decoration.isStopped()),
                // Both observations are zero by the time anything is released, which is the ordering:
                // stop everything that can drive the model, then release what it was driving.
                () -> assertEquals(
                        List.of("detach(stepping=0,driving=0)", "close(stepping=0,driving=0)"),
                        this.journal.entries()),
                () -> assertEquals(1, this.stopNotifications.get()));
    }

    @Test
    void despawnIsIdempotentAndReleasesTheTrackerOnce() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        this.journal.clear();

        decoration.despawn();
        decoration.despawn();
        decoration.close();

        assertAll(
                () -> assertEquals(1, this.stopNotifications.get()),
                // One detach and one close however many callers reach the teardown, which is what the
                // single latch is for: the platform and a plugin shutting down commonly arrive at once.
                () -> assertEquals(
                        List.of("detach(stepping=0,driving=0)", "close(stepping=0,driving=0)"),
                        this.journal.entries()));
    }

    @Test
    void despawnBeforeSpawnIsSafe() {
        final DecorationImpl decoration = decoration();

        decoration.despawn();

        assertAll(
                () -> assertTrue(decoration.isStopped()),
                () -> assertEquals(1, this.stopNotifications.get()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void aDespawnFromAnotherRegionDefersTheRelease() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        this.journal.clear();
        this.anchor.owned(false);

        decoration.despawn();

        assertAll(
                // Detached immediately — that is thread-free — and the release handed to the thread
                // that may fire the renderer's close event.
                () -> assertTrue(opened.isDetached()),
                () -> assertFalse(opened.isClosed()),
                () -> assertEquals(1, this.anchor.pendingSubmissions()),
                () -> assertEquals(List.of("detach(stepping=0,driving=0)", "submit(stepping=0,driving=0)"),
                        this.journal.entries()));
    }

    @Test
    void aDeferredReleaseClosesTheTrackerWhenItRuns() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        this.anchor.owned(false);
        decoration.despawn();

        assertEquals(1, this.anchor.runSubmitted());

        assertTrue(opened.isClosed());
    }

    @Test
    void aRefusedDeferralLeavesTheTrackerToTheWorldThatIsGone() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        this.anchor.owned(false);
        this.anchor.refuseSubmission();

        decoration.despawn();

        assertAll(
                // An unloaded world has no viewers and no displays left, so declining costs nothing —
                // and the teardown must still complete rather than throwing on the way out.
                () -> assertTrue(decoration.isStopped()),
                () -> assertTrue(opened.isDetached()),
                () -> assertFalse(opened.isClosed()),
                () -> assertEquals(1, this.stopNotifications.get()));
    }

    // ------------------------------------------------------------------ moving

    @Test
    void moveToUpdatesThePlacementAndTellsTheRenderer() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        final DecorationPlacement to = DecorationFixtures.PLACEMENT.at(31.5D, 70.25D, -4.5D);

        decoration.moveTo(to);

        assertAll(
                () -> assertEquals(to, decoration.getPlacement()),
                () -> assertEquals(to, decoration.placement()),
                () -> assertEquals(List.of(to), opened.moves()));
    }

    @Test
    void moveToPublishesNoDriverItselfAndLetsTheLoopDoIt() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        this.drivers.runPass();

        decoration.moveTo(DecorationFixtures.PLACEMENT.at(11.5D, 64.25D, -7.4D));

        // Publishing its own impulse would have it overwritten by the next pass before the simulation
        // had necessarily read it — a dropped impulse that happens only when the timing lines up.
        assertSame(PhysicsDriver.STILL, decoration.driver().current());

        this.drivers.runPass();

        assertEquals(0.1F, decoration.driver().current().forwardSpeed(), 1.0E-5F);
    }

    @Test
    void aMoveBeforeSpawnDecidesWhereTheTrackerIsOpened() {
        final DecorationImpl decoration = decoration();
        final DecorationPlacement to = DecorationFixtures.PLACEMENT.at(-80.5D, 12.5D, 240.5D);

        decoration.moveTo(to);
        decoration.spawn();

        assertAll(
                () -> assertEquals(to, this.anchor.openedAt()),
                () -> assertEquals(List.of(to), this.anchor.ownershipQuestions()));
    }

    @Test
    void aMoveOnASpentHandleIsIgnored() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        decoration.despawn();

        decoration.moveTo(DecorationFixtures.PLACEMENT.at(0D, 0D, 0D));

        assertAll(
                // Not a lifecycle operation: a plugin moving a decoration the platform already tore
                // down has nothing to fix, so this is deliberately not an exception.
                () -> assertEquals(DecorationFixtures.PLACEMENT, decoration.getPlacement()),
                () -> assertEquals(List.of(), opened.moves()));
    }

    // ------------------------------------------------------------------ visibility

    @Test
    void aDecorationIsVisibleToNobodyUntilItIsShown() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();

        assertAll(
                () -> assertEquals(0, decoration.shownCount()),
                () -> assertFalse(decoration.isShownTo(UUID.randomUUID())));
    }

    @Test
    void showBeforeSpawnChangesNothing() {
        final DecorationImpl decoration = decoration();
        final UUID viewer = UUID.randomUUID();

        assertAll(
                // There are no displays to send. Not an exception, because "place, show, spawn" is a
                // reasonable order for a caller to try.
                () -> assertFalse(decoration.show(viewer)),
                () -> assertFalse(decoration.isShownTo(viewer)),
                () -> assertEquals(0, decoration.shownCount()));
    }

    @Test
    void showAndHideArePerViewerAndReportWhetherTheyChangedAnything() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        final UUID shown = UUID.randomUUID();
        final UUID other = UUID.randomUUID();

        assertAll(
                () -> assertTrue(decoration.show(shown)),
                () -> assertFalse(decoration.show(shown), "a second show is not a second spawn"),
                () -> assertTrue(decoration.isShownTo(shown)),
                () -> assertFalse(decoration.isShownTo(other)),
                () -> assertEquals(1, decoration.shownCount()),
                () -> assertEquals(Set.of(shown), opened.spawnedFor()),
                () -> assertTrue(decoration.hide(shown)),
                () -> assertFalse(decoration.hide(shown)),
                () -> assertFalse(decoration.isShownTo(shown)),
                () -> assertEquals(Set.of(), opened.spawnedFor()));
    }

    @Test
    void aShowThatCouldNotBeSentDoesNotClaimTheViewer() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        opened.refuseShow();
        final UUID viewer = UUID.randomUUID();

        assertAll(
                () -> assertFalse(decoration.show(viewer)),
                // A viewer recorded as holding a model they do not get no updates and no repair, which
                // is the one state that never fixes itself.
                () -> assertFalse(decoration.isShownTo(viewer)),
                () -> assertEquals(0, decoration.shownCount()));
    }

    @Test
    void despawnForgetsEveryViewer() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final UUID viewer = UUID.randomUUID();
        decoration.show(viewer);

        decoration.despawn();

        assertAll(
                () -> assertEquals(0, decoration.shownCount()),
                () -> assertFalse(decoration.isShownTo(viewer)));
    }

    @Test
    void theCullersSinkSendsWithoutTouchingTheViewerList() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final FakeDecorationRendering opened = this.anchor.rendering();
        assertNotNull(opened);
        final UUID viewer = UUID.randomUUID();

        decoration.viewportSink().show(viewer);

        assertAll(
                // The culler marks the viewport itself and then calls the sink, so the sink is
                // transport only — marking here would double-count and skip the send.
                () -> assertEquals(Set.of(viewer), opened.spawnedFor()),
                () -> assertFalse(decoration.isShownTo(viewer)));
    }

    @Test
    void theCullersSinkIsSafeOnADecorationThatIsNotSpawned() {
        final DecorationImpl decoration = decoration();

        decoration.viewportSink().show(UUID.randomUUID());
        decoration.viewportSink().hide(UUID.randomUUID());

        assertEquals(List.of(), this.journal.entries());
    }

    // ------------------------------------------------------------------ culling and the field

    @Test
    void theCullingPositionFollowsThePlacement() {
        final DecorationImpl decoration = decoration();
        decoration.moveTo(DecorationFixtures.PLACEMENT.at(13.5D, 71.25D, -2.5D));

        assertAll(
                () -> assertEquals(13.5D, decoration.cullX(), TOLERANCE),
                () -> assertEquals(71.25D, decoration.cullY(), TOLERANCE),
                () -> assertEquals(-2.5D, decoration.cullZ(), TOLERANCE),
                () -> assertEquals(this.anchor.worldId(), decoration.worldId()),
                () -> assertEquals(this.anchor.worldId(), decoration.getWorldId()),
                () -> assertSame(decoration.getUniqueIdentifier(), decoration.modelId()));
    }

    @Test
    void onlyALiveDecorationIsACullingDecision() {
        final DecorationImpl decoration = decoration();

        final boolean beforeSpawn = decoration.isCullable();
        decoration.spawn();
        final boolean whileAlive = decoration.isCullable();
        decoration.despawn();

        assertAll(
                () -> assertFalse(beforeSpawn, "nothing to show yet"),
                () -> assertTrue(whileAlive),
                () -> assertFalse(decoration.isCullable(), "everything has already been taken away"));
    }

    @Test
    void theExposureIsReadableByTheDriverLoopUnderBothNames() {
        final DecorationImpl decoration = decoration(AmbientMotion.EXPOSED);

        assertAll(
                () -> assertEquals(AmbientMotion.EXPOSED, decoration.getAmbientMotion()),
                () -> assertEquals(AmbientMotion.EXPOSED, decoration.ambientMotion()));

        decoration.setAmbientMotion(AmbientMotion.of(0.25F));

        assertAll(
                () -> assertEquals(0.25F, decoration.getAmbientMotion().strength()),
                () -> assertEquals(0.25F, decoration.ambientMotion().strength()));
    }

    @Test
    void theDriverTheLoopPublishesToIsTheOneTheSimulationReads() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();

        // Two ends of one seam rather than two objects that happen to agree: the simulation is built
        // around this holder instead of allocating its own.
        assertSame(decoration.driver(), decoration.simulation().driver());
    }

    // ------------------------------------------------- the published interface's own defaults

    @Test
    void movingToALocationInAnotherWorldIsRefused() {
        final DecorationImpl decoration = decoration();
        final World elsewhere = world(UUID.randomUUID());

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> decoration.moveTo(new Location(elsewhere, 1.5D, 2.5D, 3.5D)));

        assertAll(
                // A decoration whose coordinates moved and whose renderer did not would stand in the
                // old world at the new world's position, which is not a state anything downstream
                // could detect.
                () -> assertTrue(thrown.getMessage().contains("change world"),
                        () -> "must say why, got: " + thrown.getMessage()),
                () -> assertEquals(DecorationFixtures.PLACEMENT, decoration.getPlacement()));
    }

    @Test
    void movingToALocationWithNoWorldIsRefused() {
        final DecorationImpl decoration = decoration();

        assertThrows(IllegalArgumentException.class,
                () -> decoration.moveTo(new Location(null, 1.5D, 2.5D, 3.5D)));
    }

    @Test
    void aLocationInTheDecorationsOwnWorldBecomesAPlacement() {
        final DecorationImpl decoration = decoration();
        final World own = world(this.anchor.worldId());

        decoration.moveTo(new Location(own, 1.5D, 2.5D, 3.5D, 90F, -10F));

        // Including the pitch: a hanging sign can be tilted, and the renderer honours it even though
        // the physics works in the decoration's own frame.
        assertEquals(new DecorationPlacement(1.5D, 2.5D, 3.5D, 90F, -10F), decoration.getPlacement());
    }

    @Test
    void showAndHideAcceptAPlayerDirectly() {
        final DecorationImpl decoration = decoration();
        decoration.spawn();
        final UUID identity = UUID.randomUUID();
        final Player viewer = player(identity);

        assertAll(
                () -> assertTrue(decoration.show(viewer)),
                () -> assertTrue(decoration.isShownTo(identity)),
                () -> assertTrue(decoration.hide(viewer)),
                () -> assertFalse(decoration.isShownTo(identity)));
    }

    /**
     * A world that knows its own identity and nothing else.
     *
     * <p>A proxy rather than a stub class because {@code World} is a few hundred methods and the handle
     * reads exactly one of them. Anything else being called is a change of behaviour worth failing on,
     * which is what the default branch is for.
     */
    private static @NotNull World world(@NotNull UUID identity) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> identity;
                    case "getName" -> "test-world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test-world";
                    default -> throw new UnsupportedOperationException(
                            "The handle must not call World." + method.getName());
                });
    }

    /** A player that knows its own identity and nothing else; see {@link #world(UUID)}. */
    private static @NotNull Player player(@NotNull UUID identity) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> identity;
                    case "getName" -> "test-player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test-player";
                    default -> throw new UnsupportedOperationException(
                            "The handle must not call Player." + method.getName());
                });
    }

    /** A scheduler that fails the test if the loop under test ever dispatches anything. */
    private static final class UnusedScheduler implements SimulationScheduler {

        @Override
        public SimulationTask runAtFixedRate(
                Runnable body,
                long initialDelay,
                long period,
                TimeUnit unit
        ) {
            throw new AssertionError("These tests drive the loop by hand and must not schedule");
        }
    }

    /** A logger that fails the test if the loop reports anything. */
    private static final class StrictLogger implements SimulationLogger {

        @Override
        public void error(String message, Throwable thrown) {
            throw new AssertionError(message, thrown);
        }
    }
}
