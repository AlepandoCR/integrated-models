package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.internal.sim.DriverHolder;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one task that drives every decoration on the server.
 *
 * <p>Two of its properties are the whole reason the decoration layer is worth having, and both are
 * asserted here rather than reasoned about: a decoration nobody moves and no wind reaches is published
 * the shared {@code STILL} constant, by identity, so it allocates nothing and settles; and a move
 * becomes a velocity for exactly one pass, so an impulse ends instead of leaving a decoration
 * permanently deflected in a wind that stopped.
 */
class DecorationDriverLoopTest {

    private static final float TOLERANCE = 1.0E-5F;

    /** At the spatial origin, so the gust's phase is the clock alone and expectations stay exact. */
    private static final DecorationPlacement ORIGIN = new DecorationPlacement(0D, 70D, 0D, 0F, 0F);

    private final SteppableClock clock = new SteppableClock();
    private final FakeOffTickScheduler scheduler = new FakeOffTickScheduler();
    private final DecorationFixtures.MutableDecorationSource fields =
            new DecorationFixtures.MutableDecorationSource(DecorationFixtures.calmSettings());
    private final SimulationSettingsSource simulation = DecorationFixtures.simulationSource();

    private DecorationDriverLoop loop() {
        return new DecorationDriverLoop(this.scheduler, this.clock, this.fields, this.simulation);
    }

    // ------------------------------------------------------------------ stillness

    @Test
    void aStillDecorationIsPublishedTheStillConstantItself() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);

        loop.runPass();
        loop.runPass();

        // Identity, not equality. This is the single most important assertion in the decoration layer:
        // the published driver is the shared constant, so three hundred untouched decorations allocate
        // nothing per pass and every one of them lets the solver settle and go silent.
        assertSame(PhysicsDriver.STILL, decoration.driver().current());
    }

    @Test
    void theFirstPassNeverPublishesAMovement() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);
        decoration.moveTo(ORIGIN.at(0D, 70D, 40D));

        loop.runPass();

        // There is no previous placement to difference against, and inventing one would make a
        // decoration's first moment of physics depend on where it was placed.
        assertSame(PhysicsDriver.STILL, decoration.driver().current());
    }

    // ------------------------------------------------------------------ movement

    @Test
    void aMoveBetweenPassesBecomesAVelocity() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);

        loop.runPass();
        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        loop.runPass();

        final PhysicsDriver moved = decoration.driver().current();
        assertAll(
                // Facing +Z and moved along +Z, so the whole delta is forward speed.
                () -> assertEquals(0.1F, moved.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, moved.sideSpeed(), TOLERANCE),
                () -> assertEquals(0F, moved.verticalSpeed(), TOLERANCE));
    }

    @Test
    void aMovementLastsOnePassAndThenEnds() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);
        loop.runPass();
        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        loop.runPass();

        loop.runPass();

        // The reason the loop keeps publishing for a decoration nothing drives. Without this pass a
        // decoration moved once would keep that velocity for ever, so its bones would settle at a
        // permanent tilt — a banner leaning in a wind that stopped minutes ago.
        assertSame(PhysicsDriver.STILL, decoration.driver().current());
    }

    @Test
    void movementIsScaledToBlocksPerTickFromTheConfiguredPassRate() {
        // Five passes a second: one pass covers four ticks, so the same delta is a quarter of the speed.
        this.fields.publish(new DecorationSettings(
                5, 0F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);

        loop.runPass();
        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        loop.runPass();

        // A driver's speeds are per server tick. Publishing the raw delta would make a move's apparent
        // speed depend on a pass rate that is supposed to be invisible.
        assertEquals(0.4F, decoration.driver().current().forwardSpeed(), TOLERANCE);
    }

    @Test
    void aTeleportSizedMoveIsFilteredAgainstWhatOnePassCouldCover() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);
        loop.runPass();
        decoration.moveTo(ORIGIN.at(0D, 70D, 50D));

        loop.runPass();

        // Fifty blocks is a teleport, and a teleport fed to the wind term launches every bone at once.
        assertEquals(0F, decoration.driver().current().forwardSpeed(), TOLERANCE);
    }

    @Test
    void theTeleportThresholdIsMeasuredPerPassRatherThanPerDelta() {
        final DecorationPlacement moved = ORIGIN.at(0D, 70D, 0.5D);

        final DecorationDriverLoop atTickRate = loop();
        final FakeDriven perTick = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        atTickRate.register(perTick);
        atTickRate.runPass();
        perTick.moveTo(moved);
        atTickRate.runPass();

        // The same move, seen by a loop whose passes are four ticks apart: half a block per pass is two
        // blocks per tick, which is past the configured threshold of one and a half.
        this.fields.publish(new DecorationSettings(
                5, 0F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
        final DecorationDriverLoop atFiveHertz = loop();
        final FakeDriven perFourTicks = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        atFiveHertz.register(perFourTicks);
        atFiveHertz.runPass();
        perFourTicks.moveTo(moved);
        atFiveHertz.runPass();

        assertAll(
                () -> assertEquals(0.5F, perTick.driver().current().forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, perFourTicks.driver().current().forwardSpeed(), TOLERANCE));
    }

    // ------------------------------------------------------------------ the field

    @Test
    void windAndMovementAddBecauseBothAreVelocitiesRelativeToTheSameAir() {
        // A steady wind blowing toward +Z at a quarter block a tick, and nothing else moving.
        this.fields.publish(new DecorationSettings(
                20, 0.25F, 0F, 0.5F, 3F, 0F, 17F, 0F, 0F, 0F, 0F));
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.EXPOSED);
        loop.register(decoration);
        loop.runPass();

        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        loop.runPass();

        // Moving downwind at 0.1 in a wind that gives -0.25 leaves -0.15: the decoration's velocity
        // relative to the air, which is the only quantity the solver's drag term is written against.
        assertEquals(-0.15F, decoration.driver().current().forwardSpeed(), TOLERANCE);
    }

    @Test
    void theFieldsPhaseIsMeasuredFromTheFirstPassRatherThanFromTheClocksOrigin() {
        // A gust on top of a steady wind. At the field's own origin the gust is exactly zero, so the
        // first pass must publish the steady component alone — which only holds if the elapsed time of
        // the first pass is zero rather than a difference against an arbitrary clock reading.
        this.fields.publish(new DecorationSettings(
                20, 0.25F, 0.4F, 0.5F, 3F, 0F, 17F, 0F, 0F, 0F, 0.3F));
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.EXPOSED);
        loop.register(decoration);

        this.clock.advanceSeconds(3.25D);
        loop.runPass();

        assertAll(
                () -> assertEquals(-0.25F, decoration.driver().current().forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, decoration.driver().current().verticalSpeed(), TOLERANCE));
    }

    @Test
    void theFieldAdvancesWithTheClockBetweenPasses() {
        this.fields.publish(new DecorationSettings(
                20, 0F, 0.4F, 0.5F, 3F, 0F, 17F, 0F, 0F, 0F, 0.3F));
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.EXPOSED);
        loop.register(decoration);

        loop.runPass();
        // A quarter cycle at half a hertz, where the primary sine is exactly one.
        this.clock.advanceSeconds(0.5D);
        loop.runPass();

        assertEquals(-0.4F, decoration.driver().current().forwardSpeed(), TOLERANCE);
    }

    @Test
    void anExposedDecorationIsNotPublishedTheStillConstant() {
        this.fields.publish(DecorationFixtures.decorationSettings());
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.EXPOSED);
        loop.register(decoration);

        loop.runPass();
        this.clock.advanceSeconds(0.4D);
        loop.runPass();

        assertAll(
                () -> assertFalse(decoration.driver().current() == PhysicsDriver.STILL,
                        "a decoration in a gust must be driven"),
                () -> assertTrue(decoration.driver().current().isMoving()));
    }

    @Test
    void shelteringADecorationStopsDrivingItWithoutUnregisteringIt() {
        this.fields.publish(DecorationFixtures.decorationSettings());
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.EXPOSED);
        loop.register(decoration);
        loop.runPass();
        this.clock.advanceSeconds(0.4D);
        loop.runPass();

        decoration.setAmbientMotion(AmbientMotion.NONE);
        this.clock.advanceSeconds(0.4D);
        loop.runPass();

        // The exposure is read once per pass per decoration, so calming one takes effect on the next
        // pass and lets it settle — without the loop having to add or remove anything.
        assertAll(
                () -> assertSame(PhysicsDriver.STILL, decoration.driver().current()),
                () -> assertEquals(1, loop.size()));
    }

    @Test
    void theFieldIsReadOncePerPassRatherThanOncePerDecoration() {
        final DecorationDriverLoop loop = loop();
        loop.register(new FakeDriven(ORIGIN, AmbientMotion.EXPOSED));
        loop.register(new FakeDriven(ORIGIN.at(5D, 70D, 5D), AmbientMotion.EXPOSED));
        loop.register(new FakeDriven(ORIGIN.at(9D, 70D, 9D), AmbientMotion.EXPOSED));
        this.fields.resetReads();

        loop.runPass();

        // A read per decoration could straddle a reload, and because the gusts are a function of
        // position the symptom would be a seam in the weather between two neighbouring banners rather
        // than anything that looked like a configuration change.
        assertEquals(1, this.fields.reads());
    }

    // ------------------------------------------------------------------ membership

    @Test
    void registeringTheSameDecorationTwiceIsRefused() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);

        // Two registrations would publish twice per pass, and the second would difference against the
        // placement the first had just remembered — a velocity of zero for a decoration that moved.
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> loop.register(decoration)),
                () -> assertEquals(1, loop.size()));
    }

    @Test
    void unregisteringStopsTheDriving() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        final DecorationDriverLoop.Registration registration = loop.register(decoration);
        loop.runPass();

        registration.unregister();
        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        loop.runPass();

        assertAll(
                () -> assertFalse(registration.isRegistered()),
                () -> assertEquals(0, loop.size()),
                () -> assertSame(PhysicsDriver.STILL, decoration.driver().current()));
    }

    @Test
    void unregisteringIsIdempotentAndByIdentity() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        final DecorationDriverLoop.Registration registration = loop.register(decoration);

        registration.unregister();
        registration.unregister();

        assertAll(
                () -> assertEquals(0, loop.size()),
                () -> assertFalse(loop.unregister(decoration)),
                () -> assertSame(decoration, registration.decoration()));
    }

    // ------------------------------------------------------------------ scheduling

    @Test
    void startDispatchesAtTheConfiguredPeriodInNanoseconds() {
        final DecorationDriverLoop loop = loop();

        loop.start();

        assertAll(
                () -> assertTrue(loop.isRunning()),
                () -> assertEquals(50_000_000L, loop.scheduledPeriodNanos()),
                () -> assertEquals(50_000_000L, this.scheduler.period()),
                // The first pass has a full period behind it, so it does not difference a placement
                // against itself before anything could have moved.
                () -> assertEquals(50_000_000L, this.scheduler.initialDelay()),
                () -> assertEquals(TimeUnit.NANOSECONDS, this.scheduler.unit()));
    }

    @Test
    void startingTwiceIsRefused() {
        final DecorationDriverLoop loop = loop();
        loop.start();

        // Two live passes would both difference against the same remembered placement.
        assertThrows(IllegalStateException.class, loop::start);
    }

    @Test
    void stopCancelsTheTaskAndIsIdempotent() {
        final DecorationDriverLoop loop = loop();
        loop.start();

        loop.stop();
        loop.stop();

        assertAll(
                () -> assertFalse(loop.isRunning()),
                () -> assertFalse(this.scheduler.isAlive()));
    }

    @Test
    void theScheduledBodyIsThePass() {
        final DecorationDriverLoop loop = loop();
        final FakeDriven decoration = new FakeDriven(ORIGIN, AmbientMotion.NONE);
        loop.register(decoration);
        loop.start();

        this.scheduler.runOnce();
        decoration.moveTo(ORIGIN.at(0D, 70D, 0.1D));
        this.scheduler.runOnce();

        assertAll(
                () -> assertEquals(0.1F, decoration.driver().current().forwardSpeed(), TOLERANCE),
                () -> assertEquals(0, this.scheduler.escaped().size(),
                        "nothing may escape the pass"));
    }

    @Test
    void reloadRedispatchesOnlyWhenThePeriodMoved() {
        final DecorationDriverLoop loop = loop();
        loop.start();

        final boolean unchanged = loop.reload();
        this.fields.publish(new DecorationSettings(
                7, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));
        final boolean moved = loop.reload();

        assertAll(
                () -> assertFalse(unchanged, "an unchanged rate must not cancel a healthy task"),
                () -> assertTrue(moved),
                () -> assertEquals(2, this.scheduler.scheduleCount()),
                () -> assertEquals(142_857_142L, loop.scheduledPeriodNanos()),
                () -> assertTrue(this.scheduler.isAlive()));
    }

    @Test
    void reloadOnAStoppedLoopDoesNothing() {
        final DecorationDriverLoop loop = loop();
        this.fields.publish(new DecorationSettings(
                7, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F));

        assertAll(
                () -> assertFalse(loop.reload()),
                () -> assertEquals(0, this.scheduler.scheduleCount()),
                // Every coefficient of the field is read fresh at the top of each pass, so the only
                // thing a reload has to act on is the rate the platform owns.
                () -> assertEquals(142_857_142L, loop.periodNanos()));
    }

    /**
     * A decoration reduced to the three things the loop reads.
     *
     * <p>Volatile fields, because the real implementation has to publish all three to a thread with no
     * affinity and a fake that used plain fields would make the loop look safer than it is.
     */
    private static final class FakeDriven implements DrivenDecoration {

        private final @NotNull DriverHolder driver = new DriverHolder();

        private volatile @NotNull DecorationPlacement placement;
        private volatile @NotNull AmbientMotion motion;

        FakeDriven(@NotNull DecorationPlacement placement, @NotNull AmbientMotion motion) {
            this.placement = placement;
            this.motion = motion;
        }

        @Override
        public @NotNull DecorationPlacement placement() {
            return this.placement;
        }

        @Override
        public @NotNull AmbientMotion ambientMotion() {
            return this.motion;
        }

        @Override
        public @NotNull DriverHolder driver() {
            return this.driver;
        }

        void moveTo(@NotNull DecorationPlacement to) {
            this.placement = to;
        }

        void setAmbientMotion(@NotNull AmbientMotion to) {
            this.motion = to;
        }
    }
}
