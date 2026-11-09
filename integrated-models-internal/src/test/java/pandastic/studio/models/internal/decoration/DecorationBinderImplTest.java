package pandastic.studio.models.internal.decoration;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.RigBindValidator;
import pandastic.studio.models.internal.render.RenderSettings;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationScheduler;
import pandastic.studio.models.internal.sim.SimulationTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the binder validates, what it remembers, and what it tears down.
 *
 * <p>The shutdown path is the reason this class holds a set at all: a decoration nobody closed is a
 * model left standing in the world with a frame handler holding this plugin's classes, and the platform
 * does not sweep either for a plugin on disable.
 */
class DecorationBinderImplTest {

    private static final RenderSettingsSource RENDER =
            RenderSettingsSource.fixed(new RenderSettings(1.0E-4F, 8));

    private final DecorationFixtures.Journal journal = new DecorationFixtures.Journal();
    private final FakeDecorationAnchor anchor = new FakeDecorationAnchor(this.journal);
    private final SimulationLoop loop = new SimulationLoop(
            new UnusedScheduler(), new StrictLogger(), DecorationFixtures.simulationSource());
    private final DecorationDriverLoop drivers = new DecorationDriverLoop(
            new FakeOffTickScheduler(),
            new SteppableClock(),
            DecorationSettingsSource.fixed(DecorationFixtures.calmSettings()),
            DecorationFixtures.simulationSource());

    private DecorationBinderImpl binder() {
        return binder(RigBindValidator.over(DecorationFixtures.modelBoneNames(), RENDER));
    }

    private DecorationBinderImpl binder(RigBindValidator validator) {
        return new DecorationBinderImpl(
                world -> this.anchor, this.loop, this.drivers,
                DecorationFixtures.simulationSource(), validator);
    }

    // ------------------------------------------------------------------ validation

    @Test
    void bindValidatesTheRigAgainstItsModelBeforeHandingOutAHandle() {
        final Rig broken = Rig.builder("broken", "test-model",
                        DecorationFixtures.RIG_BOUNDS, DecorationFixtures.PROFILE_BOUNDS)
                .root("body")
                .bone("tassel", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, DecorationFixtures.PROFILE_BOUNDS,
                                DecorationFixtures.PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .build())
                .build();
        final DecorationBinderImpl binder = binder();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> binder.bind(broken, this.anchor, DecorationFixtures.PLACEMENT,
                        AmbientMotion.NONE));

        assertAll(
                // A rig whose bone names went stale is a content error, and the person who can fix it
                // is reading the log — so it fails before anything exists, naming the bone.
                () -> assertTrue(thrown.getMessage().contains("tassel"),
                        () -> "must name the bone, got: " + thrown.getMessage()),
                () -> assertEquals(0, binder.size()),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void aLocationWithNoWorldIsRefused() {
        final DecorationBinderImpl binder = binder();

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(DecorationFixtures.rig(), new Location(null, 11.5D, 64.25D, -7.5D)));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("world"),
                        () -> "must say what is missing, got: " + thrown.getMessage()),
                () -> assertEquals(0, binder.size()));
    }

    // ------------------------------------------------------------------ membership

    @Test
    void aBoundDecorationIsRememberedAndIsShelteredByDefault() {
        final DecorationBinderImpl binder = binder();

        final DecorationImpl decoration = binder.bind(
                DecorationFixtures.rig(), this.anchor, DecorationFixtures.PLACEMENT,
                AmbientMotion.NONE);

        assertAll(
                () -> assertEquals(1, binder.size()),
                () -> assertEquals(List.of(decoration), List.copyOf(binder.boundDecorations())),
                // Sheltered by default: motion costs packets continuously, and a server whose three
                // hundred decorations all breathe by default pays for weather nobody asked for.
                () -> assertEquals(AmbientMotion.NONE, decoration.getAmbientMotion()),
                () -> assertFalse(decoration.isAlive(), "binding does not spawn"),
                () -> assertEquals(List.of(), this.journal.entries()));
    }

    @Test
    void aDecorationRemovesItselfWhenItStops() {
        final DecorationBinderImpl binder = binder();
        final DecorationImpl decoration = binder.bind(
                DecorationFixtures.rig(), this.anchor, DecorationFixtures.PLACEMENT,
                AmbientMotion.NONE);
        decoration.spawn();

        decoration.despawn();

        // Membership is exactly "bound and not yet stopped", which is also why a handle is single use:
        // one that could spawn again would have left the set with no way back in.
        assertEquals(0, binder.size());
    }

    @Test
    void despawnAllTearsDownEverythingIncludingWhatNeverSpawned() {
        final DecorationBinderImpl binder = binder();
        final DecorationImpl spawned = binder.bind(
                DecorationFixtures.rig(), this.anchor, DecorationFixtures.PLACEMENT,
                AmbientMotion.EXPOSED);
        final DecorationImpl neverSpawned = binder.bind(
                DecorationFixtures.rig(), this.anchor, DecorationFixtures.PLACEMENT,
                AmbientMotion.NONE);
        spawned.spawn();

        final int despawned = binder.despawnAll();

        assertAll(
                () -> assertEquals(2, despawned),
                () -> assertEquals(0, binder.size()),
                () -> assertTrue(spawned.isStopped()),
                () -> assertTrue(neverSpawned.isStopped()),
                () -> assertEquals(0, this.loop.size()),
                () -> assertEquals(0, this.drivers.size()));
    }

    @Test
    void despawnAllIsIdempotent() {
        final DecorationBinderImpl binder = binder();
        binder.bind(DecorationFixtures.rig(), this.anchor, DecorationFixtures.PLACEMENT,
                AmbientMotion.NONE).spawn();

        assertAll(
                () -> assertEquals(1, binder.despawnAll()),
                () -> assertEquals(0, binder.despawnAll()));
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
