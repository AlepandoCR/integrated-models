package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsDriver;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverSamplerTest {

    private static final float TOLERANCE = 1.0E-5F;

    private static final DriverBounds BOUNDS = SimFixtures.DRIVER_BOUNDS;

    private final DriverSampler sampler =
            new DriverSampler(SimulationSettingsSource.fixed(SimFixtures.settings(new ScriptedSolver())));

    /** Moving from the origin to {@code (x, y, z)} while facing {@code yawDegrees}. */
    private static PhysicsDriver from(double x, double y, double z, float yawDegrees) {
        return DriverSampler.difference(0D, 0D, 0D, yawDegrees, x, y, z, yawDegrees, BOUNDS);
    }

    @Test
    void translationIsProjectedIntoTheOwnersOwnFrame() {
        assertAll(
                // Yaw zero faces +Z, and the owner's left is +X.
                () -> assertEquals(0.2F, from(0D, 0D, 0.2D, 0F).forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, from(0D, 0D, 0.2D, 0F).sideSpeed(), TOLERANCE),
                () -> assertEquals(0.2F, from(0.2D, 0D, 0D, 0F).sideSpeed(), TOLERANCE),
                () -> assertEquals(0F, from(0.2D, 0D, 0D, 0F).forwardSpeed(), TOLERANCE),
                // Yaw -90 faces +X, so the same world delta is now forward.
                () -> assertEquals(0.2F, from(0.2D, 0D, 0D, -90F).forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, from(0.2D, 0D, 0D, -90F).sideSpeed(), TOLERANCE),
                // Yaw 180 faces -Z.
                () -> assertEquals(0.2F, from(0D, 0D, -0.2D, 180F).forwardSpeed(), TOLERANCE),
                () -> assertEquals(0.1F, from(0D, 0.1D, 0D, 0F).verticalSpeed(), TOLERANCE));
    }

    @Test
    void turningOnTheSpotReportsAYawRateAndNoTranslation() {
        final PhysicsDriver turning =
                DriverSampler.difference(0D, 0D, 0D, 0F, 0D, 0D, 0D, 30F, BOUNDS);

        assertAll(
                () -> assertEquals((float) Math.toRadians(30D), turning.yawRateRadians(), TOLERANCE),
                () -> assertEquals(0F, turning.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, turning.sideSpeed(), TOLERANCE),
                () -> assertTrue(turning.isMoving()));
    }

    @Test
    void yawIsWrappedToTheShorterTurnRatherThanSubtracted() {
        // 179 to -179 is a two-degree turn. Subtracted it is -358 degrees, which hands the drag term a
        // 6 rad/tick spin for one frame and launches every bone in the rig.
        final PhysicsDriver forwards = DriverSampler.difference(0D, 0D, 0D, 179F, 0D, 0D, 0D, -179F, BOUNDS);
        final PhysicsDriver backwards = DriverSampler.difference(0D, 0D, 0D, -179F, 0D, 0D, 0D, 179F, BOUNDS);

        assertAll(
                () -> assertEquals((float) Math.toRadians(2D), forwards.yawRateRadians(), TOLERANCE),
                () -> assertEquals((float) Math.toRadians(-2D), backwards.yawRateRadians(), TOLERANCE),
                // Minecraft yaw is unbounded, so neither end of a difference is necessarily in -180..180.
                () -> assertEquals((float) Math.toRadians(5D),
                        DriverSampler.difference(0D, 0D, 0D, 765F, 0D, 0D, 0D, 770F, BOUNDS).yawRateRadians(),
                        TOLERANCE),
                () -> assertEquals(0F, DriverSampler.wrapDegrees(720F), TOLERANCE),
                () -> assertEquals(-90F, DriverSampler.wrapDegrees(270F), TOLERANCE),
                () -> assertEquals(179F, DriverSampler.wrapDegrees(-181F), TOLERANCE));
    }

    @Test
    void discardsATeleportSizedHorizontalDeltaButKeepsTheRest() {
        // A hundred blocks in a tick is a portal, not a sprint, and there is no way to tell from the
        // delta alone — which is exactly why the filter belongs here rather than in the solver.
        final PhysicsDriver teleported =
                DriverSampler.difference(0D, 64D, 0D, 10F, 100D, 66D, 0D, 20F, BOUNDS);

        assertAll(
                () -> assertEquals(0F, teleported.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0F, teleported.sideSpeed(), TOLERANCE),
                () -> assertEquals(2F, teleported.verticalSpeed(), TOLERANCE,
                        "two blocks up is a legitimate climb and must survive"),
                () -> assertEquals((float) Math.toRadians(10D), teleported.yawRateRadians(), TOLERANCE,
                        "a teleport does not invalidate the turn"));
    }

    @Test
    void keepsALongFallButDiscardsAnImpossibleOne() {
        assertAll(
                // Falling legitimately covers more ground per tick than walking does, which is why the
                // vertical ceiling is its own, looser number.
                () -> assertEquals(-3F, from(0D, -3D, 0D, 0F).verticalSpeed(), TOLERANCE),
                () -> assertEquals(0F, from(0D, -10D, 0D, 0F).verticalSpeed(), TOLERANCE),
                () -> assertEquals(6F, BOUNDS.maxVerticalStepBlocks(), TOLERANCE));
    }

    @Test
    void realMovementSurvivesTheFilter() {
        // A sprinting player covers about 0.28 blocks a tick. If the filter ate that, every model on
        // the server would be windless and the whole wind term would be dead code.
        final PhysicsDriver sprinting = from(0D, 0D, 0.28D, 0F);

        assertAll(
                () -> assertEquals(0.28F, sprinting.forwardSpeed(), TOLERANCE),
                () -> assertTrue(sprinting.isMoving()),
                () -> assertTrue(BOUNDS.maxStepBlocks() > 0.28F));
    }

    @Test
    void theFirstSampleIsStillBecauseThereIsNothingToDifference() {
        assertFalse(this.sampler.isSeeded());

        final PhysicsDriver first = this.sampler.sample(12D, 64D, -30D, 45F);

        assertAll(
                // Inventing a previous position would make a model's first tick of physics depend on
                // where it happened to spawn.
                () -> assertSame(PhysicsDriver.STILL, first),
                () -> assertTrue(this.sampler.isSeeded()));
    }

    @Test
    void successiveSamplesDifferenceAgainstEachOther() {
        this.sampler.sample(0D, 64D, 0D, 0F);
        final PhysicsDriver moved = this.sampler.sample(0D, 64D, 0.2D, 0F);
        final PhysicsDriver movedAgain = this.sampler.sample(0D, 64D, 0.5D, 0F);
        final PhysicsDriver stopped = this.sampler.sample(0D, 64D, 0.5D, 0F);

        assertAll(
                () -> assertEquals(0.2F, moved.forwardSpeed(), TOLERANCE),
                () -> assertEquals(0.3F, movedAgain.forwardSpeed(), TOLERANCE,
                        "each sample differences against the previous one, not against the first"),
                () -> assertFalse(stopped.isMoving()));
    }

    @Test
    void resetMakesTheNextSampleAFirstAgain() {
        this.sampler.sample(0D, 64D, 0D, 0F);
        this.sampler.reset();
        assertFalse(this.sampler.isSeeded());

        // The delta across a rebind or a deliberate teleport describes nothing physical.
        assertSame(PhysicsDriver.STILL, this.sampler.sample(500D, 64D, 500D, 0F));
        assertEquals(0.2F,
                this.sampler.sample(500D, 64D, 500.2D, 0F).forwardSpeed(), TOLERANCE);
    }

    @Test
    void sampleIntoPublishesStraightAcrossTheSeam() {
        final DriverHolder holder = new DriverHolder();

        this.sampler.sampleInto(holder, 0D, 64D, 0D, 0F);
        assertSame(PhysicsDriver.STILL, holder.current());

        this.sampler.sampleInto(holder, 0D, 64D, 0.2D, 0F);
        assertEquals(0.2F, holder.current().forwardSpeed(), TOLERANCE);
    }

    // ------------------------------------------------------------------------------------------
    // Injected thresholds are honoured, not shadowed by a leftover default
    // ------------------------------------------------------------------------------------------

    @Test
    void filtersAgainstTheConfiguredThresholdsRatherThanStockOnes() {
        // A server with movement plugins routinely sees deltas vanilla never produces, so the
        // thresholds move. Both of these are on the opposite side of the stock pair from where the
        // configured pair puts them, which is the only arrangement that can tell them apart.
        final DriverBounds generous = new DriverBounds(200F, 200F);
        final DriverBounds mean = new DriverBounds(0.05F, 0.05F);

        assertAll(
                () -> assertEquals(100F, DriverSampler.difference(
                        0D, 0D, 0D, 0F, 0D, 0D, 100D, 0F, generous).forwardSpeed(), TOLERANCE,
                        "a hundred blocks is movement if the operator says it is"),
                () -> assertEquals(50F, DriverSampler.difference(
                        0D, 0D, 0D, 0F, 0D, 50D, 0D, 0F, generous).verticalSpeed(), TOLERANCE),
                () -> assertEquals(0F, DriverSampler.difference(
                        0D, 0D, 0D, 0F, 0D, 0D, 0.28D, 0F, mean).forwardSpeed(), TOLERANCE,
                        "and a sprint is a teleport if they say that instead"),
                () -> assertEquals(0F, DriverSampler.difference(
                        0D, 0D, 0D, 0F, 0D, 0.28D, 0D, 0F, mean).verticalSpeed(), TOLERANCE));
    }

    @Test
    void theHorizontalAndVerticalThresholdsAreIndependentlyConfigured() {
        // Two separate numbers, and a sampler reading one for both would pass whichever assertion
        // happened to match and fail the other.
        final DriverBounds horizontalOnly = new DriverBounds(10F, 0.1F);
        final PhysicsDriver driver =
                DriverSampler.difference(0D, 0D, 0D, 0F, 0D, 5D, 5D, 0F, horizontalOnly);

        assertAll(
                () -> assertEquals(5F, driver.forwardSpeed(), TOLERANCE,
                        "five blocks is inside a ten-block horizontal threshold"),
                () -> assertEquals(0F, driver.verticalSpeed(), TOLERANCE,
                        "and far outside a tenth-of-a-block vertical one"));
    }

    @Test
    void anInstanceSamplerReadsItsThresholdsFromTheSourceEverySample() {
        // The thresholds are hot-reloadable, so a sampler that latched them at construction would keep
        // filtering against the configuration the server booted with.
        final SimFixtures.MutableSource source =
                new SimFixtures.MutableSource(SimFixtures.settings(new ScriptedSolver()));
        final DriverSampler sampler = new DriverSampler(source);

        sampler.sample(0D, 64D, 0D, 0F);
        final PhysicsDriver underStock = sampler.sample(0D, 64D, 3D, 0F);

        source.publish(new SimulationSettings(
                new ScriptedSolver(),
                SimFixtures.SIMULATION_HZ,
                SimFixtures.cadence(SimFixtures.SIMULATION_HZ, SimFixtures.EMISSION_HZ),
                new DriverBounds(50F, 50F),
                SimFixtures.ERROR_LOG_INTERVAL_NANOS));
        final PhysicsDriver underReload = sampler.sample(0D, 64D, 6D, 0F);

        assertAll(
                () -> assertEquals(0F, underStock.forwardSpeed(), TOLERANCE,
                        "three blocks a tick is a teleport at the stock threshold"),
                () -> assertEquals(3F, underReload.forwardSpeed(), TOLERANCE,
                        "and real movement once the operator raises it"));
    }

    @Test
    void theThresholdsCannotBeConfiguredToZero() {
        // Zero would classify every movement as a teleport and zero the driver permanently, so the
        // physics would never be driven at all — which presents as "the physics is broken".
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new DriverBounds(0F, 6F)),
                () -> assertThrows(IllegalArgumentException.class, () -> new DriverBounds(1.5F, 0F)),
                () -> assertThrows(IllegalArgumentException.class, () -> new DriverBounds(-1F, 6F)));
    }

    @Test
    void aNonFiniteCoordinateYieldsStillRatherThanThrowing() {
        assertAll(
                // The record refuses a non-finite driver. Letting it throw from here would propagate a
                // server-side bug into the region thread that merely read the position.
                () -> assertSame(PhysicsDriver.STILL, from(Double.NaN, 0D, 0D, 0F)),
                () -> assertSame(PhysicsDriver.STILL, from(0D, Double.POSITIVE_INFINITY, 0D, 0F)),
                () -> assertSame(PhysicsDriver.STILL,
                        DriverSampler.difference(0D, 0D, 0D, Float.NaN, 0D, 0D, 0D, 0F, BOUNDS)));
    }
}
