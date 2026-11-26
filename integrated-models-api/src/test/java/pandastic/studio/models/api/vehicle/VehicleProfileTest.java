package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a profile accepts, what the operator's bounds then refuse, and which of the two says so.
 *
 * <p>The division is the thing worth testing. The constructor enforces what is arithmetically required —
 * finite, non-negative, a fraction where a fraction is meant — and the bounds enforce what the server
 * permits. A profile can be perfectly well formed and still be refused, and the two failures have to name
 * different things.
 */
class VehicleProfileTest {

    @Test
    void aBuilderThatSaysNothingProducesNeutralValues() {
        final VehicleProfile neutral = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .acceleration(0.5F)
                .braking(0.5F)
                .build();

        assertAll(
                // Zero means "this vehicle does not do that at all", which is what a one-way conveyor
                // and a turning vehicle respectively are.
                () -> assertEquals(0F, neutral.maxReverseSpeedBlocksPerTick()),
                () -> assertEquals(0F, neutral.maxStrafeSpeedBlocksPerTick()),
                () -> assertEquals(0F, neutral.frictionPerTick()),
                () -> assertEquals(0F, neutral.turnRateDegreesPerTick()),
                // The multiplicative identity, not zero: a profile that says nothing about boost does
                // not boost, and a zero multiplier would stop it moving when the rider sprints.
                () -> assertEquals(1F, neutral.boostMultiplier()),
                () -> assertSame(ControlScheme.TURNING, neutral.controlScheme(),
                        "the default scheme should be what every vanilla mount does"),
                () -> assertSame(VehicleKind.GROUND, neutral.kind(),
                        "the default kind should be what every vanilla mount is"),
                () -> assertSame(TerrainResponse.NONE, neutral.terrain(),
                        "a vehicle that says nothing about terrain does not react to it"),
                () -> assertFalse(neutral.climbs(),
                        "no step height means no climb, whatever the kind says")
        );
    }

    @Test
    void theNineArgumentFactoryIsThePreviousShapeAndIsInertToTerrain() {
        // The whole point of the factory: a consumer that only changes `new VehicleProfile(` to
        // `VehicleProfile.withoutTerrain(` keeps the behaviour it had before the terrain layer existed.
        final VehicleProfile ported = VehicleProfile.withoutTerrain(
                1F, 0.5F, 0F, 0.25F, 0.5F, 0.5F, 10F, 2F, ControlScheme.TURNING);

        assertAll(
                () -> assertSame(VehicleKind.GROUND, ported.kind()),
                () -> assertSame(TerrainResponse.NONE, ported.terrain(),
                        "the constant, not a fresh record: a consumer comparing responses by identity "
                                + "across two profiles gets the answer it expects"),
                () -> assertTrue(ported.terrain().isInert()),
                () -> assertFalse(ported.climbs(),
                        "a ground vehicle with no step height does not climb"),
                () -> assertEquals(
                        new VehicleProfile(1F, 0.5F, 0F, 0.25F, 0.5F, 0.5F, 10F, 2F,
                                ControlScheme.TURNING, VehicleKind.GROUND, TerrainResponse.NONE),
                        ported,
                        "the factory is the canonical form with two defaults, nothing else")
        );
    }

    @Test
    void aBankWithNothingToDriveItIsRefusedUnderEitherScheme() {
        // The one kind of contradiction that is refused rather than ignored, and the reason is
        // arithmetic: the bank is a ceiling scaled by the fraction of a full-rate input achieved, so a
        // full rate of zero makes the fraction 0/0. Which input it is depends on the scheme, which is
        // why both arms are asserted and why each has to name its own driver.
        final TerrainResponse leaning = TerrainResponse.banking(20F, 0.25F);

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleProfile.builder(VehicleFixtures.BOUNDS)
                                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F)
                                .terrain(leaning)
                                .build()).getMessage()
                        .contains("turnRateDegreesPerTick"),
                        "a turning vehicle leans because it yaws, so the turn rate is the denominator"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleProfile.builder(VehicleFixtures.BOUNDS)
                                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F)
                                .turnRate(10F)
                                .controls(ControlScheme.STRAFING)
                                .terrain(leaning)
                                .build()).getMessage()
                        .contains("maxStrafeSpeedBlocksPerTick"),
                        "a strafing vehicle never yaws, so its lean comes from sliding instead"),
                // And the mirror of both: each scheme accepts the driver the other one refuses, which is
                // what proves the scheme selects the denominator rather than the check being symmetric.
                () -> assertEquals(20F, VehicleProfile.builder(VehicleFixtures.BOUNDS)
                        .forwardSpeed(1F).acceleration(0.25F).braking(0.5F)
                        .turnRate(10F)
                        .terrain(leaning)
                        .build().terrain().bankDegrees()),
                () -> assertEquals(20F, VehicleProfile.builder(VehicleFixtures.BOUNDS)
                        .forwardSpeed(1F).acceleration(0.25F).braking(0.5F)
                        .strafeSpeed(0.5F)
                        .controls(ControlScheme.STRAFING)
                        .terrain(leaning)
                        .build().terrain().bankDegrees(),
                        "a strafing vehicle with no turn rate at all is legal: it has something to lean "
                                + "from")
        );
    }

    @Test
    void aZeroBankNeedsNoDriverSoNoProfileWrittenBeforeTheTerrainLayerCanBeRefused() {
        // The compatibility guarantee, stated as a test: TerrainResponse.NONE banks by zero, so the one
        // new refusal cannot fire for a profile that carries it — not even for the rail-bound vehicle
        // with no turn rate and no strafe speed, which is the case the check would otherwise catch.
        final VehicleProfile railBound = VehicleProfile.withoutTerrain(
                1F, 0F, 0F, 0.25F, 0.5F, 0F, 0F, 1F, ControlScheme.TURNING);

        assertEquals(0F, railBound.terrain().bankDegrees());
    }

    @Test
    void aTermTheKindDoesNotHonourIsIgnoredRatherThanRefused() {
        // VehicleKind promises this, so that one response can be shared across kinds without a caller
        // blanking the fields that do not apply. A flyer has no ground to take a slope from and no block
        // to climb, and a ground vehicle takes its pitch from the hill rather than from its own
        // acceleration — all three are unused numbers, not broken ones, and the vehicle drives.
        final TerrainResponse everything = new TerrainResponse(15F, 20F, 10F, 0.6F, 4, 0.25F);

        final VehicleProfile flyer = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F).turnRate(10F)
                .kind(VehicleKind.FLYER)
                .terrain(everything)
                .build();
        final VehicleProfile car = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F).turnRate(10F)
                .terrain(everything)
                .build();

        assertAll(
                () -> assertEquals(20F, flyer.terrain().slopePitchDegrees(),
                        "the value is kept, not zeroed: the kind decides whether it is read"),
                () -> assertFalse(flyer.climbs(),
                        "a step height on a kind that does not climb is ignored, and climbs() says so"),
                () -> assertTrue(car.climbs(),
                        "the same response on the one kind that climbs does climb"),
                () -> assertEquals(10F, car.terrain().accelerationPitchDegrees(),
                        "a ground vehicle keeps an acceleration pitch it will not use")
        );
    }

    @Test
    void oneTerrainResponseIsLegalAcrossTwoKinds() {
        // The sharing case VehicleKind's promise exists for. One immutable response, two kinds that
        // honour different halves of it, and the same instance on both profiles.
        final TerrainResponse shared = new TerrainResponse(12F, 18F, 8F, 0.5F, 3, 0.2F);

        final VehicleProfile cart = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F).turnRate(10F)
                .kind(VehicleKind.GROUND)
                .terrain(shared)
                .build();
        final VehicleProfile skiff = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F).turnRate(10F)
                .kind(VehicleKind.BOAT)
                .terrain(shared)
                .build();

        assertAll(
                () -> assertSame(shared, cart.terrain()),
                () -> assertSame(shared, skiff.terrain(),
                        "no copy and no clamp: sharing a response is the whole point of ignoring the "
                                + "terms a kind does not honour"),
                () -> assertTrue(cart.climbs(), "the cart reads the step height"),
                () -> assertFalse(skiff.climbs(), "the skiff ignores it, because a hull does not climb")
        );
    }

    @Test
    void oneRequireWithinCallChecksTheTerrainHalfToo() {
        // Two narrow terrain ceilings, one at a time, for the same reason the speed bounds are narrowed
        // one at a time above: a bounds narrowed twice would only ever report whichever is checked first.
        final VehicleBounds flat = new VehicleBounds(2F, 1F, 45F, 4F,
                new TerrainBounds(5F, 25F, 20F, 1F, 8, 0.05F));
        final VehicleBounds impatient = new VehicleBounds(2F, 1F, 45F, 4F,
                new TerrainBounds(30F, 25F, 20F, 1F, 2, 0.05F));
        final VehicleProfile leaner = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F).acceleration(0.25F).braking(0.5F).turnRate(10F)
                .terrain(new TerrainResponse(20F, 0F, 0F, 0.5F, 4, 0.25F))
                .build();

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> leaner.requireWithin(flat)).getMessage().contains("bankDegrees"),
                        "the terrain half is checked by the same call, and names its own term"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> leaner.requireWithin(impatient)).getMessage()
                        .contains("stepDurationTicks")),
                () -> assertSame(leaner, leaner.requireWithin(VehicleFixtures.BOUNDS),
                        "and still a check rather than a transformation"),
                // The speed is checked before the angle: a vehicle too fast for the server cannot be
                // moved at all, where an angle too steep is cosmetic on top of a vehicle that works.
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> leaner.requireWithin(new VehicleBounds(0.5F, 1F, 45F, 4F,
                                new TerrainBounds(5F, 25F, 20F, 1F, 8, 0.05F))))
                        .getMessage().contains("maxSpeedBlocksPerTick"))
        );
    }

    @Test
    void aMissingKindOrTerrainResponseIsRefusedRatherThanDefaulted() {
        // Defaulting a null would make the canonical constructor quietly disagree with the factory that
        // exists to supply those defaults.
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new VehicleProfile(1F, 0F, 0F, 0.25F, 0.5F, 0F, 10F, 1F,
                                ControlScheme.TURNING, null, TerrainResponse.NONE)),
                () -> assertThrows(NullPointerException.class,
                        () -> new VehicleProfile(1F, 0F, 0F, 0.25F, 0.5F, 0F, 10F, 1F,
                                ControlScheme.TURNING, VehicleKind.GROUND, null))
        );
    }

    @Test
    void theBoostedSpeedIsTheProductAndIsWhatTheThresholdsHaveToClear() {
        final VehicleProfile boosted = VehicleFixtures.boosted();

        assertEquals(2F, boosted.boostedForwardSpeedBlocksPerTick(),
                "the figure every movement threshold on the server has to be compared against");
    }

    @Test
    void theConstructorRefusesWhatIsArithmeticallyImpossible() {
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(0F, 0.1F, 0.1F, 0F, 1F)).getMessage()
                        .contains("maxForwardSpeedBlocksPerTick"),
                        "a vehicle with no top speed cannot move and is not a vehicle"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0F, 0.1F, 0F, 1F)).getMessage()
                        .contains("accelerationBlocksPerTickSquared"),
                        "a vehicle that cannot accelerate never reaches its own top speed"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0.1F, 0.1F, 1.5F, 1F)).getMessage()
                        .contains("frictionPerTick"),
                        "friction is the share of speed lost per tick, so above one it is not a share"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> profile(1F, 0.1F, 0.1F, 0F, 0.5F)).getMessage()
                        .contains("boostMultiplier"),
                        "a multiplier below its own identity would make sprinting a brake"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profile(Float.NaN, 0.1F, 0.1F, 0F, 1F))
        );
    }

    @Test
    void theBoundsRefuseWhatTheOperatorDoesNotPermitAndNameBoth() {
        // One narrow bound per case, each wide enough everywhere else: a bounds that narrowed two
        // ceilings at once would only ever report whichever is checked first, so the test would be
        // asserting the order of the checks rather than which key each one names.
        final VehicleBounds slow = VehicleBounds.withoutTerrain(0.5F, 1F, 45F, 4F);
        final VehicleBounds unboostable = VehicleBounds.withoutTerrain(2F, 1F, 45F, 1F);
        final VehicleBounds sluggish = VehicleBounds.withoutTerrain(2F, 0.1F, 45F, 4F);

        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.car().requireWithin(slow)).getMessage()
                        .contains("maxSpeedBlocksPerTick"),
                        "the complaint has to name the bound, because the fix might be in either"),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.boosted().requireWithin(unboostable)).getMessage()
                        .contains("maxBoostMultiplier")),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleFixtures.car().requireWithin(sluggish)).getMessage()
                        .contains("maxAccelerationBlocksPerTickSquared")),
                // The reverse and strafe speeds are held to the same single speed ceiling, which is the
                // point of there being one: an operator capping a server's vehicle speed should not have
                // to think about which direction it is in.
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> VehicleProfile.builder(slow)
                                .forwardSpeed(0.5F).reverseSpeed(0.9F)
                                .acceleration(0.1F).braking(0.1F).build()).getMessage()
                        .contains("maxReverseSpeedBlocksPerTick"))
        );
    }

    @Test
    void aProfileInsideTheBoundsComesBackUnchanged() {
        final VehicleProfile car = VehicleFixtures.car();

        assertSame(car, car.requireWithin(VehicleFixtures.BOUNDS),
                "requireWithin is a check, not a transformation");
    }

    @Test
    void theBuilderValidatesAgainstTheBoundsItWasGiven() {
        // The bounds are supplied to builder() rather than to build() so that a caller cannot assemble a
        // profile and then forget to check it.
        final VehicleBounds narrow = VehicleBounds.withoutTerrain(0.25F, 1F, 45F, 4F);

        assertThrows(IllegalArgumentException.class, () -> VehicleProfile.builder(narrow)
                .forwardSpeed(1F)
                .acceleration(0.25F)
                .braking(0.25F)
                .build());
    }

    private static VehicleProfile profile(
            float forward,
            float acceleration,
            float braking,
            float friction,
            float boost
    ) {
        return VehicleProfile.withoutTerrain(forward, 0F, 0F, acceleration, braking, friction, 0F,
                boost, ControlScheme.TURNING);
    }
}
