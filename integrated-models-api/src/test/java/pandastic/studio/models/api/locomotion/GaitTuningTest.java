package pandastic.studio.models.api.locomotion;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A calibration with no defaults, which is the whole point: a builder that quietly substituted zero for
 * a missing configuration key would turn a typo in a YAML file into a model that stands still.
 */
class GaitTuningTest {

    @Test
    void refusesToBuildWithAnyValueUnsupplied() {
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GaitTuning.builder().breathRateHz(0.25F).build());
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no defaults"),
                        "it must say why, got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("counterPhaseCycles"),
                        "and name what is missing"),
                () -> assertTrue(!thrown.getMessage().contains("breathRateHz"),
                        "and not name what was supplied"));
    }

    @Test
    void namesEveryMissingValueSoOneReadOfTheErrorIsEnough() {
        // Two unrelated keys omitted: a builder that reported only the first would mean a round trip per
        // missing line, which is how a half-configured calibration ends up shipped.
        final GaitTuning.Builder partial = GaitTuning.builder()
                .counterPhaseCycles(0.5F)
                .restingLegExtension(0.91F)
                .movementDeadbandBlocksPerTick(0.007F)
                .sneakTorsoPitchRadians(0.41F)
                .fullStrideSpeedBlocksPerTick(0.183F)
                .sprintSpeedBlocksPerTick(0.41F)
                .strideToFootReach(0.31F)
                .footLiftFraction(0.23F)
                .footToeOffRadians(0.61F)
                .footHeelStrikeRadians(0.07F)
                .maxIkReachFraction(0.97F)
                .minIkDownFraction(0.29F)
                .legSwingRadians(0.61F)
                .sprintLegSwingRadians(0.83F)
                .armSwingRadians(0.47F)
                .forearmCurlRadians(0.29F)
                .torsoLeanRadians(0.13F)
                .torsoBobRadians(0.041F)
                .torsoSwayRadians(0.071F)
                .torsoTwistRadians(0.093F)
                .breathRateHz(0.4F)
                .breathRadians(0.017F)
                .amplitudeBlendSeconds(0.19F)
                .airborneBlendSeconds(0.27F)
                .sneakBlendSeconds(0.11F)
                .maxStepSeconds(0.33F)
                .maxGaitSpeedBlocksPerTick(3.25F)
                .maxPhaseAdvancePerStep(2.75F)
                .elapsedWrapBreathCycles(64)
                .defaultStrideLength(0.73F)
                .defaultHeadStabilise(0.31F)
                .defaultHandStabilise(0.84F);

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, partial::build);
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("torsoBobHarmonic"),
                        "got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("settledAmplitude"),
                        "got: " + thrown.getMessage()));
    }

    @Test
    void buildsOnceEveryValueIsSupplied() {
        assertDoesNotThrow(GaitFixtures::tuning);
    }

    @Test
    void refusesACoefficientWhoseTermCannotSurviveIt() {
        assertAll(
                // Divisors: the amplitude ramp divides by the full-stride speed and the elapsed wrap
                // divides by the breath rate.
                () -> assertRefused(builder -> builder.fullStrideSpeedBlocksPerTick(0F),
                        "fullStrideSpeedBlocksPerTick"),
                () -> assertRefused(builder -> builder.breathRateHz(0F), "breathRateHz"),
                () -> assertRefused(builder -> builder.breathRateHz(-0.25F), "breathRateHz"),
                // A blend of zero seconds is a division by zero, not a snap.
                () -> assertRefused(builder -> builder.amplitudeBlendSeconds(0F),
                        "amplitudeBlendSeconds"),
                () -> assertRefused(builder -> builder.airborneBlendSeconds(0F),
                        "airborneBlendSeconds"),
                () -> assertRefused(builder -> builder.sneakBlendSeconds(0F), "sneakBlendSeconds"),
                // Guards against overflow reaching the client as NaN.
                () -> assertRefused(builder -> builder.maxStepSeconds(0F), "maxStepSeconds"),
                () -> assertRefused(builder -> builder.maxGaitSpeedBlocksPerTick(0F),
                        "maxGaitSpeedBlocksPerTick"),
                () -> assertRefused(builder -> builder.maxPhaseAdvancePerStep(0F),
                        "maxPhaseAdvancePerStep"),
                () -> assertRefused(builder -> builder.elapsedWrapBreathCycles(0),
                        "elapsedWrapBreathCycles"),
                () -> assertRefused(builder -> builder.restingLegExtension(0F), "restingLegExtension"),
                () -> assertRefused(builder -> builder.settledAmplitude(0F), "settledAmplitude"),
                () -> assertRefused(builder -> builder.defaultStrideLength(0F), "defaultStrideLength"),
                () -> assertRefused(builder -> builder.sprintSpeedBlocksPerTick(0F),
                        "sprintSpeedBlocksPerTick"));
    }

    @Test
    void refusesASprintSpeedThatDoesNotSitAboveWalkingPace() {
        // The sprint blend divides by the gap between the two speeds. Equal speeds divide by zero, and
        // an inverted pair runs the blend backwards — so the sprint amplitudes would be expressed while
        // standing still and the walk amplitudes while sprinting.
        final IllegalArgumentException equal = assertThrows(IllegalArgumentException.class,
                () -> GaitFixtures.tuningBuilder()
                        .fullStrideSpeedBlocksPerTick(0.25F)
                        .sprintSpeedBlocksPerTick(0.25F)
                        .build());
        final IllegalArgumentException inverted = assertThrows(IllegalArgumentException.class,
                () -> GaitFixtures.tuningBuilder()
                        .fullStrideSpeedBlocksPerTick(0.5F)
                        .sprintSpeedBlocksPerTick(0.25F)
                        .build());

        assertAll(
                () -> assertTrue(equal.getMessage().contains("sprintSpeedBlocksPerTick"),
                        "got: " + equal.getMessage()),
                () -> assertTrue(equal.getMessage().contains("divides by the difference"),
                        "it must say why, got: " + equal.getMessage()),
                () -> assertTrue(inverted.getMessage().contains("fullStrideSpeedBlocksPerTick"),
                        "and name both sides, got: " + inverted.getMessage()),
                // And the band it feeds is the gap between them, derived rather than configured.
                () -> assertEquals(0.41F - 0.183F,
                        GaitFixtures.tuning().sprintSpeedBandBlocksPerTick(), 1.0E-6F));
    }

    @Test
    void refusesAFractionOutsideZeroToOne() {
        assertAll(
                () -> assertRefused(builder -> builder.counterPhaseCycles(1.5F), "counterPhaseCycles"),
                () -> assertRefused(builder -> builder.counterPhaseCycles(-0.5F), "counterPhaseCycles"),
                () -> assertRefused(builder -> builder.restingLegExtension(1.2F),
                        "restingLegExtension"),
                () -> assertRefused(builder -> builder.maxIkReachFraction(1.2F), "maxIkReachFraction"),
                () -> assertRefused(builder -> builder.minIkDownFraction(1.2F), "minIkDownFraction"),
                () -> assertRefused(builder -> builder.settledAmplitude(1.2F), "settledAmplitude"),
                () -> assertRefused(builder -> builder.defaultHeadStabilise(1.2F),
                        "defaultHeadStabilise"),
                () -> assertRefused(builder -> builder.defaultHeadStabilise(-0.1F),
                        "defaultHeadStabilise"),
                () -> assertRefused(builder -> builder.defaultHandStabilise(1.2F),
                        "defaultHandStabilise"),
                () -> assertRefused(builder -> builder.defaultHandStabilise(-0.1F),
                        "defaultHandStabilise"));
    }

    @Test
    void refusesANegativeMagnitude() {
        assertAll(
                () -> assertRefused(builder -> builder.movementDeadbandBlocksPerTick(-0.1F),
                        "movementDeadbandBlocksPerTick"),
                () -> assertRefused(builder -> builder.strideToFootReach(-0.1F), "strideToFootReach"),
                () -> assertRefused(builder -> builder.footLiftFraction(-0.1F), "footLiftFraction"),
                () -> assertRefused(builder -> builder.torsoBobHarmonic(-1F), "torsoBobHarmonic"));
    }

    @Test
    void refusesTheInvertedIkWindow() {
        // A down-fraction above the reach ceiling leaves no foot target satisfying both clamps, so the
        // leg would be clamped into a position that does not exist.
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GaitFixtures.tuningBuilder()
                        .minIkDownFraction(0.9F)
                        .maxIkReachFraction(0.5F)
                        .build());
        assertTrue(thrown.getMessage().contains("Inverted range"),
                "got: " + thrown.getMessage());
    }

    @Test
    void refusesNaNAndInfinityOnAnAngleThatIsOtherwiseUnconstrained() {
        // A lean may legitimately be negative — leaning back is a style — so finiteness is the only
        // check, and it has to actually be there.
        assertAll(
                () -> assertRefused(builder -> builder.torsoLeanRadians(Float.NaN),
                        "torsoLeanRadians"),
                () -> assertRefused(builder -> builder.torsoLeanRadians(Float.POSITIVE_INFINITY),
                        "torsoLeanRadians"),
                () -> assertRefused(builder -> builder.breathRadians(Float.NaN), "breathRadians"),
                () -> assertRefused(builder -> builder.sneakTorsoPitchRadians(Float.NaN),
                        "sneakTorsoPitchRadians"),
                () -> assertRefused(builder -> builder.footToeOffRadians(Float.NaN),
                        "footToeOffRadians"),
                () -> assertRefused(builder -> builder.footHeelStrikeRadians(
                        Float.NEGATIVE_INFINITY), "footHeelStrikeRadians"),
                () -> assertRefused(builder -> builder.sprintLegSwingRadians(Float.NaN),
                        "sprintLegSwingRadians"),
                () -> assertDoesNotThrow(() -> GaitFixtures.tuningBuilder()
                        .torsoLeanRadians(-0.2F)
                        .build()),
                // Both ankle angles are signed on purpose: a rig whose foot bone points backward wants
                // the pair negated, so refusing a negative one would refuse that rig.
                () -> assertDoesNotThrow(() -> GaitFixtures.tuningBuilder()
                        .footToeOffRadians(-0.45F)
                        .footHeelStrikeRadians(-0.18F)
                        .build()));
    }

    @Test
    void honoursADeliberatelyOddCalibrationRatherThanSubstitutingADefault() {
        // Every value below is unlike the constant it replaced, and all thirty-four are read back. A
        // surviving hardcoded fallback cannot pass this.
        final GaitTuning odd = GaitTuning.builder()
                .counterPhaseCycles(0.37F)
                .restingLegExtension(0.62F)
                .movementDeadbandBlocksPerTick(0.019F)
                .sneakTorsoPitchRadians(-0.77F)
                .fullStrideSpeedBlocksPerTick(0.903F)
                .sprintSpeedBlocksPerTick(2.19F)
                .strideToFootReach(0.71F)
                .footLiftFraction(0.46F)
                .footToeOffRadians(-0.88F)
                .footHeelStrikeRadians(1.37F)
                .maxIkReachFraction(0.53F)
                .minIkDownFraction(0.11F)
                .legSwingRadians(1.23F)
                .sprintLegSwingRadians(0.29F)
                .armSwingRadians(1.47F)
                .forearmCurlRadians(0.91F)
                .attackDurationSeconds(1.13F)
                .attackUpperArmRadians(-1.61F)
                .attackForearmRadians(0.73F)
                .torsoLeanRadians(-0.33F)
                .torsoBobRadians(0.219F)
                .torsoBobHarmonic(5F)
                .torsoSwayRadians(0.311F)
                .torsoTwistRadians(0.407F)
                .breathRateHz(1.6F)
                .breathRadians(0.133F)
                .amplitudeBlendSeconds(0.87F)
                .airborneBlendSeconds(0.93F)
                .sneakBlendSeconds(0.71F)
                .maxStepSeconds(1.9F)
                .maxGaitSpeedBlocksPerTick(11.5F)
                .maxPhaseAdvancePerStep(7.25F)
                .elapsedWrapBreathCycles(13)
                .settledAmplitude(0.07F)
                .defaultStrideLength(4.75F)
                .defaultHeadStabilise(0.02F)
                .defaultHandStabilise(0.96F)
                .build();

        assertAll(
                () -> assertEquals(0.37F, odd.counterPhaseCycles()),
                () -> assertEquals(0.62F, odd.restingLegExtension()),
                () -> assertEquals(0.019F, odd.movementDeadbandBlocksPerTick()),
                () -> assertEquals(-0.77F, odd.sneakTorsoPitchRadians()),
                () -> assertEquals(0.903F, odd.fullStrideSpeedBlocksPerTick()),
                () -> assertEquals(2.19F, odd.sprintSpeedBlocksPerTick()),
                () -> assertEquals(0.71F, odd.strideToFootReach()),
                () -> assertEquals(0.46F, odd.footLiftFraction()),
                () -> assertEquals(-0.88F, odd.footToeOffRadians()),
                () -> assertEquals(1.37F, odd.footHeelStrikeRadians()),
                () -> assertEquals(0.53F, odd.maxIkReachFraction()),
                () -> assertEquals(0.11F, odd.minIkDownFraction()),
                () -> assertEquals(1.23F, odd.legSwingRadians()),
                () -> assertEquals(0.29F, odd.sprintLegSwingRadians()),
                () -> assertEquals(1.47F, odd.armSwingRadians()),
                () -> assertEquals(0.91F, odd.forearmCurlRadians()),
                () -> assertEquals(-0.33F, odd.torsoLeanRadians()),
                () -> assertEquals(0.219F, odd.torsoBobRadians()),
                () -> assertEquals(5F, odd.torsoBobHarmonic()),
                () -> assertEquals(0.311F, odd.torsoSwayRadians()),
                () -> assertEquals(0.407F, odd.torsoTwistRadians()),
                () -> assertEquals(1.6F, odd.breathRateHz()),
                () -> assertEquals(0.133F, odd.breathRadians()),
                () -> assertEquals(0.87F, odd.amplitudeBlendSeconds()),
                () -> assertEquals(0.93F, odd.airborneBlendSeconds()),
                () -> assertEquals(0.71F, odd.sneakBlendSeconds()),
                () -> assertEquals(1.9F, odd.maxStepSeconds()),
                () -> assertEquals(11.5F, odd.maxGaitSpeedBlocksPerTick()),
                () -> assertEquals(7.25F, odd.maxPhaseAdvancePerStep()),
                () -> assertEquals(13, odd.elapsedWrapBreathCycles()),
                () -> assertEquals(0.07F, odd.settledAmplitude()),
                () -> assertEquals(4.75F, odd.defaultStrideLength()),
                () -> assertEquals(0.02F, odd.defaultHeadStabilise()),
                () -> assertEquals(0.96F, odd.defaultHandStabilise()),
                // And it reaches the profile that is seeded from it.
                () -> assertEquals(4.75F, GaitProfile.defaults(odd).strideLength()),
                () -> assertEquals(0.02F, GaitProfile.defaults(odd).headStabilise()),
                () -> assertEquals(0.96F, GaitProfile.defaults(odd).handStabilise()));
    }

    @Test
    void derivesTheElapsedWrapFromTheBreathRateSoTheWrapLandsOnAZeroCrossing() {
        // Configuring the wrap in seconds instead would let the two disagree, and a wrap off a zero
        // crossing makes every standing model on the server twitch in unison.
        final GaitTuning tuning = GaitFixtures.tuningBuilder()
                .breathRateHz(0.4F)
                .elapsedWrapBreathCycles(13)
                .build();
        assertEquals(13F / 0.4F, tuning.elapsedWrapSeconds(), 1.0E-5F);

        final GaitTuning faster = GaitFixtures.tuningBuilder()
                .breathRateHz(2F)
                .elapsedWrapBreathCycles(13)
                .build();
        assertEquals(6.5F, faster.elapsedWrapSeconds(), 1.0E-5F);
    }

    private static void assertRefused(
            UnaryOperator<GaitTuning.Builder> tweak,
            String expectedInMessage
    ) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tweak.apply(GaitFixtures.tuningBuilder()).build());
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must name the value, got: " + thrown.getMessage());
    }
}
