package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsProfileTest {

    private static final ProfileBounds BOUNDS = RigFixtures.wideProfileBounds();
    private static final ProfileDefaults DEFAULTS = RigFixtures.stockProfileDefaults();

    @Test
    void defaultsHangDownwardWithRoomToSwing() {
        // Against the stock seeds, which is what the shipped physics.yml declares.
        final PhysicsProfile profile =
                PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS).build();
        assertAll(
                () -> assertEquals(PhysicsKind.PENDULUM, profile.kind()),
                () -> assertEquals(0F, profile.reachX()),
                () -> assertEquals(-1F, profile.reachY()),
                () -> assertEquals(0F, profile.reachZ()),
                () -> assertEquals(AxisLimits.symmetricDegrees(BOUNDS.defaultLimitDegrees()),
                        profile.limits()),
                () -> assertTrue(profile.gravity() > 0F, "a pendulum needs gravity to hang"));
    }

    @Test
    void theOmittedLimitComesFromTheSuppliedBoundsAndNothingElse() {
        // The removed constant was a 45-degree half-width. A configuration of 12.5 degrees is honoured
        // literally, which no fallback to 45 could produce.
        final ProfileBounds tight = new ProfileBounds(0.01F, 100F, 50F, 50F, 20F, 1.7F, 12.5F);
        final PhysicsProfile profile = PhysicsProfile.builder(PhysicsKind.PENDULUM, tight, DEFAULTS).build();

        assertAll(
                () -> assertEquals((float) Math.toRadians(12.5F), profile.limits().maxPitch(), 1.0E-6F),
                () -> assertEquals((float) Math.toRadians(-12.5F), profile.limits().minYaw(), 1.0E-6F),
                () -> assertEquals((float) Math.toRadians(12.5F), profile.limits().maxRoll(), 1.0E-6F),
                // An explicit limit still wins over the default.
                () -> assertEquals((float) Math.toRadians(70F),
                        PhysicsProfile.builder(PhysicsKind.PENDULUM, tight, DEFAULTS)
                                .limits(AxisLimits.symmetricDegrees(70F))
                                .build().limits().maxPitch(), 1.0E-6F));
    }

    @Test
    void aSpringDefaultsToNoGravitySoItReturnsToCentre() {
        // The zero is the configured spring seed rather than a rule inside the builder - see
        // theGravitySeedIsChosenPerKindFromConfiguration, which moves it and watches it move.
        final PhysicsProfile spring =
                PhysicsProfile.builder(PhysicsKind.SPRING, BOUNDS, DEFAULTS).build();
        assertEquals(0F, spring.gravity());
    }

    @Test
    void everyUnsetCoefficientComesFromTheSuppliedDefaultsAndNothingElse() {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        final PhysicsProfile profile =
                PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, odd).build();
        // The reach is normalised on the way in, so what has to survive is the direction.
        final float length = (float) Math.sqrt(2F * 2F + 0.5F * 0.5F + 1F * 1F);

        assertAll(
                () -> assertEquals(0.92F, profile.stiffness(), "above the removed 0.08"),
                () -> assertEquals(0.004F, profile.damping(), "below the removed 0.015"),
                () -> assertEquals(0.11F, profile.lag(), "below the removed 0.6"),
                () -> assertEquals(3.75F, profile.mass(), "above the removed 1"),
                () -> assertEquals(0.3F, profile.wind(), "below the removed 1"),
                () -> assertEquals(1.9F, profile.gravity(), "above the removed 0.25"),
                () -> assertEquals(2F / length, profile.reachX(), 1.0E-6F),
                () -> assertEquals(0.5F / length, profile.reachY(), 1.0E-6F,
                        "upward, which the removed hangs-straight-down default could not produce"),
                () -> assertEquals(-1F / length, profile.reachZ(), 1.0E-6F),
                // Not seeded, and deliberately: zero is the absence of a collision cone rather than a
                // small one, so there is nothing for configuration to say about it.
                () -> assertEquals(0F, profile.collisionRadius()));
    }

    @Test
    void theGravitySeedIsChosenPerKindFromConfiguration() {
        // Four distinct configured values, so a seed delivered to the wrong kind is visible - and the
        // spring's is non-zero, which is the case a builder that still hardcoded "springs get nothing"
        // would fail.
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        assertAll(
                () -> assertEquals(1.9F, seededGravity(PhysicsKind.PENDULUM, odd)),
                () -> assertEquals(0.6F, seededGravity(PhysicsKind.SPRING, odd)),
                () -> assertEquals(0.05F, seededGravity(PhysicsKind.CLOTH_STRIP, odd)),
                () -> assertEquals(2.5F, seededGravity(PhysicsKind.DRAG, odd)),
                // And an explicit value in a profile still wins over whichever seed its kind selects.
                () -> assertEquals(7F, PhysicsProfile.builder(PhysicsKind.SPRING, BOUNDS, odd)
                        .gravity(7F).build().gravity()));
    }

    private static float seededGravity(PhysicsKind kind, ProfileDefaults defaults) {
        return PhysicsProfile.builder(kind, BOUNDS, defaults).build().gravity();
    }

    @Test
    void normalisesTheReachVector() {
        final PhysicsProfile profile = PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS)
                .reach(0F, -4F, 0F)
                .build();
        assertAll(
                () -> assertEquals(0F, profile.reachX()),
                () -> assertEquals(-1F, profile.reachY(), 1.0E-6F),
                () -> assertEquals(0F, profile.reachZ()));

        final PhysicsProfile diagonal = PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS)
                .reach(3F, 4F, 0F)
                .build();
        final float length = (float) Math.sqrt(
                diagonal.reachX() * diagonal.reachX()
                        + diagonal.reachY() * diagonal.reachY()
                        + diagonal.reachZ() * diagonal.reachZ());
        assertEquals(1F, length, 1.0E-6F);
    }

    @Test
    void refusesADirectionlessReach() {
        assertThrows(IllegalArgumentException.class,
                () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS).reach(0F, 0F, 0F).build());
    }

    @Test
    void enforcesTheFractionsWithoutNeedingBounds() {
        // Stiffness, damping and lag are fractions, so their range is what the word means rather than
        // an operator's choice, and the canonical constructor owns it.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.PENDULUM, 1.5F, 0F, 0F, 0F, 1F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.PENDULUM, -0.1F, 0F, 0F, 0F, 1F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.PENDULUM, 0F, 2F, 0F, 0F, 1F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.DRAG, 0F, 0F, 1.2F, 0F, 1F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)),
                // And a mass of zero is a division by zero rather than a narrow range.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.PENDULUM, 0F, 0F, 0F, 0F, 0F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PhysicsProfile(PhysicsKind.PENDULUM, 0F, 0F, 0F, -1F, 1F, 0F, 0F,
                                0F, -1F, 0F, AxisLimits.UNBOUNDED)));
    }

    @Test
    void enforcesEveryConfiguredCoefficientRange() {
        final ProfileBounds narrow = new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS)
                                .gravity(5F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS).wind(5F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.CLOTH_STRIP, narrow, DEFAULTS)
                                .collisionRadius(3F).build()),
                () -> assertDoesNotThrow(() -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS)
                        .gravity(4F).wind(4F).build()));
    }

    @Test
    void enforcesTheConfiguredMassFloorBecauseALightBoneIsAnUnstableBone() {
        final ProfileBounds narrow = new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS).mass(0.01F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS).mass(0F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS).mass(20F).build()),
                () -> assertDoesNotThrow(
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, narrow, DEFAULTS).mass(0.05F).build()));
    }

    @Test
    void theMassRangeComesFromTheSuppliedBoundsAndNothingElse() {
        // The removed constants were 0.05 and 10. This configuration brackets both of them out, so a
        // surviving fallback shows up as an accepted mass that should have been refused.
        final ProfileBounds odd = new ProfileBounds(0.4F, 0.6F, 4F, 4F, 2F, 1.7F, 45F);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).mass(0.05F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).mass(10F).build()),
                // The mass seeded from the defaults is outside this range too, and is not exempt:
                // build() validates the final values, whether the caller chose them or inherited them.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).build()),
                () -> assertDoesNotThrow(
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS).mass(0.5F).build()));
    }

    @Test
    void theCeilingsComeFromTheSuppliedBoundsAndNothingElse() {
        // The removed ceilings were 4, 4 and 2.
        final ProfileBounds odd = new ProfileBounds(0.05F, 10F, 0.125F, 0.25F, 0.05F, 1.7F, 45F);
        assertAll(
                () -> assertRefusedNaming(
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS)
                                .gravity(4F).wind(0.1F).build(),
                        "gravity"),
                () -> assertRefusedNaming(
                        () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, odd, DEFAULTS)
                                .gravity(0.1F).wind(4F).build(),
                        "wind"),
                () -> assertRefusedNaming(
                        () -> PhysicsProfile.builder(PhysicsKind.CLOTH_STRIP, odd, DEFAULTS)
                                .collisionRadius(2F).gravity(0.1F).wind(0.1F).build(),
                        "collisionRadius"),
                () -> assertDoesNotThrow(() -> PhysicsProfile.builder(PhysicsKind.CLOTH_STRIP, odd, DEFAULTS)
                        .gravity(0.125F).wind(0.25F).collisionRadius(0.05F).build()));
    }

    private static void assertRefusedNaming(Runnable build, String expectedInMessage) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, build::run);
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must name the coefficient that was out of range, got: " + thrown.getMessage());
    }

    @Test
    void requireWithinReturnsTheSameInstanceSoItChains() {
        final PhysicsProfile profile = PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS).build();
        assertSame(profile, profile.requireWithin(BOUNDS));
    }

    @Test
    void rejectsNaN() {
        assertThrows(IllegalArgumentException.class,
                () -> PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS).stiffness(Float.NaN).build());
    }

    @Test
    void onlyDragIsNotSpringLike() {
        assertAll(
                () -> assertTrue(PhysicsProfile.builder(PhysicsKind.PENDULUM, BOUNDS, DEFAULTS).build()
                        .isSpringLike()),
                () -> assertTrue(PhysicsProfile.builder(PhysicsKind.SPRING, BOUNDS, DEFAULTS).build()
                        .isSpringLike()),
                () -> assertTrue(PhysicsProfile.builder(PhysicsKind.CLOTH_STRIP, BOUNDS, DEFAULTS).build()
                        .isSpringLike()),
                () -> assertFalse(PhysicsProfile.builder(PhysicsKind.DRAG, BOUNDS, DEFAULTS).build()
                        .isSpringLike()));
    }

    @Test
    void axisLimitsConvertDegreesOnceAndRejectInversion() {
        final AxisLimits limits = AxisLimits.ofDegrees(-90F, 60F, -45F, 45F, 0F, 0F);
        assertAll(
                () -> assertEquals((float) Math.toRadians(-90F), limits.minPitch(), 1.0E-6F),
                () -> assertEquals((float) Math.toRadians(60F), limits.maxPitch(), 1.0E-6F),
                () -> assertEquals(limits.minPitch(), limits.min(RotationAxis.PITCH)),
                () -> assertEquals(limits.maxYaw(), limits.max(RotationAxis.YAW)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AxisLimits(1F, -1F, 0F, 0F, 0F, 0F)));
    }

    @Test
    void symmetricDegreesIsTheFactoryThatReplacedTheDefaultConstant() {
        // Any half-width, honoured literally. A constant could only ever be right for one model scale.
        assertAll(
                () -> assertEquals((float) Math.toRadians(3F),
                        AxisLimits.symmetricDegrees(3F).maxPitch(), 1.0E-6F),
                () -> assertEquals((float) Math.toRadians(-3F),
                        AxisLimits.symmetricDegrees(3F).minRoll(), 1.0E-6F),
                () -> assertEquals(0F, AxisLimits.symmetricDegrees(0F).maxYaw(),
                        "a fully pinned bone is a legal configuration"),
                // UNBOUNDED stays a constant because infinity is a clamp's identity, not a choice.
                () -> assertEquals(Float.POSITIVE_INFINITY, AxisLimits.UNBOUNDED.maxPitch()),
                () -> assertEquals(Float.NEGATIVE_INFINITY, AxisLimits.UNBOUNDED.minRoll()));
    }

    @Test
    void pitchCeilingOnlyEverTightens() {
        final AxisLimits wide = AxisLimits.symmetricDegrees(80F);
        final AxisLimits tightened = wide.withPitchCeiling(0.1F);
        final AxisLimits unchanged = wide.withPitchCeiling(99F);

        assertAll(
                () -> assertEquals(0.1F, tightened.maxPitch(), 1.0E-6F),
                () -> assertEquals(wide.maxPitch(), unchanged.maxPitch(), 1.0E-6F),
                () -> assertTrue(tightened.minPitch() <= tightened.maxPitch(),
                        "tightening past the floor must not invert the range"));
    }

    @Test
    void aCeilingBelowTheFloorPullsTheFloorDownRatherThanInverting() {
        // Folding a very tight collision cone into limits whose floor is above the new ceiling must
        // still produce a valid range, or the record's own validation would reject it.
        final AxisLimits offset = AxisLimits.ofDegrees(30F, 80F, 0F, 0F, 0F, 0F);
        final AxisLimits folded = offset.withPitchCeiling(0.1F);
        assertTrue(folded.minPitch() <= folded.maxPitch());
    }
}
