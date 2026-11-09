package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.sim.SimulationLoop;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bandwidth claim, end to end, with the real solver and the real loops: <strong>a decoration that is
 * not moving costs zero packets per second.</strong>
 *
 * <p>This is the property the whole decoration layer exists for, and it is the one that cannot be
 * verified by reading any single class: it needs the driver loop publishing stillness, the solver
 * settling under it, and the simulation's emission gate noticing — three components, each of which is
 * individually correct in ways that would not compose if any of the three changed its mind about what
 * "at rest" means.
 *
 * <p>Everything here is real except the renderer and the two schedulers. The solver is the production
 * {@code AngularPhysicsSolver} at the shipped calibration, the simulation is a real
 * {@code ModelSimulation} registered with a real {@code SimulationLoop}, and the driver comes from a
 * real {@link DecorationDriverLoop}. The schedulers are stepped by hand so a test can cover ten seconds
 * of server time in a millisecond, and the renderer is a fake that counts what would have gone on the
 * wire.
 */
class DecorationSilenceTest {

    /** One simulation step at the fixture's sixty hertz, in nanoseconds. */
    private static final long STEP_NANOS = 16_666_666L;

    /** Simulation steps per driver pass at sixty hertz against the fixture's twenty hertz passes. */
    private static final int STEPS_PER_PASS = 3;

    /** Ten seconds of server time. Long enough that "it has not settled yet" is not the explanation. */
    private static final int TEN_SECONDS = 600;

    private final SteppableClock clock = new SteppableClock();
    private final FakeOffTickScheduler simulationScheduler = new FakeOffTickScheduler();
    private final FakeOffTickScheduler driverScheduler = new FakeOffTickScheduler();
    private final SimulationLoop loop = new SimulationLoop(
            this.simulationScheduler, this.clock, new StrictLogger(),
            DecorationFixtures.simulationSource());

    private final DecorationFixtures.Journal journal = new DecorationFixtures.Journal();
    private final FakeDecorationAnchor anchor = new FakeDecorationAnchor(this.journal);

    private DecorationDriverLoop drivers(@NotNull DecorationSettings field) {
        return new DecorationDriverLoop(this.driverScheduler, this.clock,
                DecorationSettingsSource.fixed(field), DecorationFixtures.simulationSource());
    }

    private DecorationImpl decoration(
            @NotNull DecorationDriverLoop drivers,
            @NotNull AmbientMotion ambient
    ) {
        return decoration(drivers, this.anchor, DecorationFixtures.PLACEMENT, ambient);
    }

    private DecorationImpl decoration(
            @NotNull DecorationDriverLoop drivers,
            @NotNull FakeDecorationAnchor anchor,
            @NotNull DecorationPlacement placement,
            @NotNull AmbientMotion ambient
    ) {
        return new DecorationImpl(DecorationFixtures.rig(), anchor, this.loop, drivers,
                DecorationFixtures.simulationSource(), placement, ambient, ignored -> {
        });
    }

    /**
     * Runs {@code steps} simulation steps, with a driver pass every third one.
     *
     * <p>The ratio is the configured one rather than a convenience: sixty steps a second against twenty
     * driver passes is exactly the shipped relationship between the two loops, and a test that drove
     * them at the same rate would hide the case where a movement published once is read by three
     * consecutive steps.
     */
    private void advance(int steps) {
        for (int step = 0; step < steps; step++) {
            this.clock.advanceNanos(STEP_NANOS);
            this.simulationScheduler.runOnce();
            if (step % STEPS_PER_PASS == STEPS_PER_PASS - 1) {
                this.driverScheduler.runOnce();
            }
        }
    }

    private void start(@NotNull DecorationDriverLoop drivers) {
        this.loop.start();
        drivers.start();
    }

    // ------------------------------------------------------------------ the claim

    @Test
    void aShelteredDecorationEmitsTheSettlingPoseOnceAndThenNothingForever() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.decorationSettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.NONE);
        start(drivers);
        decoration.spawn();

        advance(TEN_SECONDS);

        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        assertAll(
                // Exactly one. The step that discovers rest has to emit — the solver reaches rest by
                // settling, so going silent one update early would leave every bone a fraction short of
                // where the physics ended, permanently. Every step after it is free.
                () -> assertEquals(1, rendering.emissions(),
                        "a sheltered decoration must cost one packet and then nothing"),
                // The window covers the emission interval: twenty updates a second is one client tick
                // apart, so anything shorter would finish early and the bone would sit still.
                () -> assertEquals(1, rendering.lastInterpolationTicks()),
                () -> assertTrue(decoration.simulation().isAtRest()),
                () -> assertEquals(0, this.simulationScheduler.escaped().size()),
                () -> assertEquals(0, this.driverScheduler.escaped().size()));
    }

    @Test
    void threeHundredShelteredDecorationsCostThreeHundredPacketsInTotal() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.decorationSettings());
        final List<FakeDecorationAnchor> anchors = new ArrayList<>();
        start(drivers);
        for (int index = 0; index < 300; index++) {
            final FakeDecorationAnchor own = new FakeDecorationAnchor(this.journal);
            anchors.add(own);
            // Spread across the world, so each one sits in a different part of the travelling gust —
            // which is exactly the case where a field that was not position-dependent would hide a bug.
            decoration(drivers, own, DecorationPlacement.of(index * 7.5D, 70D, index * -3.5D, 0F),
                    AmbientMotion.NONE).spawn();
        }

        advance(TEN_SECONDS);

        int emissions = 0;
        for (final FakeDecorationAnchor own : anchors) {
            final FakeDecorationRendering rendering = own.rendering();
            assertNotNull(rendering);
            emissions += rendering.emissions();
        }
        // The case this design exists to survive: three hundred decorations, ten seconds, three hundred
        // packets — one settling update each and nothing after it.
        assertEquals(300, emissions);
    }

    @Test
    void despawningStopsTheEmissionsEntirely() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.decorationSettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.EXPOSED);
        start(drivers);
        decoration.spawn();
        advance(60);
        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        final int whileAlive = rendering.emissions();

        decoration.despawn();
        advance(TEN_SECONDS);

        assertAll(
                () -> assertTrue(whileAlive > 1, "an exposed decoration must have been emitting"),
                () -> assertEquals(whileAlive, rendering.emissions()),
                // Nothing reached the sink after the detach, which is what covers an emission that was
                // already in flight when the loop was left.
                () -> assertEquals(0, rendering.emissionsWhileDetached()));
    }

    // ------------------------------------------------------------------ re-arming

    @Test
    void movingADecorationReArmsEmissionAndItThenSettlesAgain() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.calmSettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.NONE);
        start(drivers);
        decoration.spawn();
        advance(TEN_SECONDS);
        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        final int settled = rendering.emissions();

        decoration.moveTo(DecorationFixtures.PLACEMENT.at(11.5D, 64.25D, -7.4D));
        advance(30);
        final int afterTheMove = rendering.emissions();

        advance(TEN_SECONDS);
        final int afterSettlingAgain = rendering.emissions();
        advance(TEN_SECONDS);

        assertAll(
                () -> assertEquals(1, settled),
                // The move became a velocity on the next driver pass, the bones felt it, and the
                // emission gate re-armed. Without the loop differencing the placement this would still
                // be one.
                () -> assertTrue(afterTheMove > settled,
                        () -> "a move must re-arm emission, got " + afterTheMove),
                // And then it settles again and goes quiet: a move is an impulse, not a permanent
                // velocity, because the next pass publishes stillness.
                () -> assertEquals(afterSettlingAgain, rendering.emissions(),
                        "a moved decoration must settle again and stop emitting"));
    }

    // ------------------------------------------------------------------ the field

    @Test
    void aSteadyWindCostsBoundedBandwidthBecauseAConstantForceSettles() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.steadySettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.EXPOSED);
        start(drivers);
        decoration.spawn();

        advance(TEN_SECONDS);
        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        final int afterSettling = rendering.emissions();
        advance(TEN_SECONDS);

        assertAll(
                // It moved: a steady wind deflects the bone to a new equilibrium.
                () -> assertTrue(afterSettling > 1,
                        () -> "a steady wind must deflect the rig, got " + afterSettling),
                // And then it stopped, which is the documented claim that the steady half of the look
                // is free. An operator who wants wind without bandwidth sets the base and zeroes the
                // gust.
                () -> assertEquals(afterSettling, rendering.emissions()),
                () -> assertTrue(decoration.simulation().isAtRest()));
    }

    @Test
    void aGustingFieldKeepsEmittingWhichIsWhatItCosts() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.decorationSettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.EXPOSED);
        start(drivers);
        decoration.spawn();

        advance(TEN_SECONDS);

        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        // Ten seconds at twenty emissions a second is two hundred updates if it never rests. The
        // assertion is deliberately loose about the exact figure — a gust has turning points where the
        // per-substep change dips below the rest threshold and the gate saves a packet — and
        // deliberately tight about the order of magnitude: this is what exposure costs, and it is two
        // orders of magnitude more than being sheltered.
        assertAll(
                () -> assertTrue(rendering.emissions() > 100,
                        () -> "an exposed decoration must keep emitting, got " + rendering.emissions()),
                () -> assertTrue(rendering.emissions() <= TEN_SECONDS / STEPS_PER_PASS,
                        () -> "it must not emit more often than the cadence, got "
                                + rendering.emissions()));
    }

    @Test
    void shelteringAGustingDecorationLetsItGoSilent() {
        final DecorationDriverLoop drivers = drivers(DecorationFixtures.decorationSettings());
        final DecorationImpl decoration = decoration(drivers, AmbientMotion.EXPOSED);
        start(drivers);
        decoration.spawn();
        advance(120);
        final FakeDecorationRendering rendering = this.anchor.rendering();
        assertNotNull(rendering);
        final int whileExposed = rendering.emissions();

        decoration.setAmbientMotion(AmbientMotion.NONE);
        advance(TEN_SECONDS);
        final int afterCalming = rendering.emissions();
        advance(TEN_SECONDS);

        assertAll(
                () -> assertTrue(whileExposed > 10, "it must have been emitting while exposed"),
                // Calming it is a store the next pass reads, after which the driver is the still
                // constant again and the solver settles.
                () -> assertEquals(afterCalming, rendering.emissions()),
                () -> assertTrue(decoration.simulation().isAtRest()));
    }

    /** A logger that fails the test if the loop reports anything. */
    private static final class StrictLogger implements SimulationLogger {

        @Override
        public void error(String message, Throwable thrown) {
            throw new AssertionError(message, thrown);
        }
    }
}
