package pandastic.studio.models.api.locomotion;

/**
 * Shared {@link GaitTuning} and {@link BindingBounds} fixtures for the locomotion tests.
 *
 * <p>Every value here is deliberately <strong>unlike</strong> the constants this layer used to carry:
 * the default stride is not 1.3, the default head stabilisation is not 0.65, the scale ceiling is not 2.
 * That is the point. A fixture made of plausible numbers cannot distinguish "the configured value was
 * used" from "a leftover hardcoded fallback happened to agree with it", so these are chosen to disagree
 * with every removed constant.
 */
final class GaitFixtures {

    /** Not 1.3. */
    static final float DEFAULT_STRIDE = 0.73F;

    /** Not 0.65. */
    static final float DEFAULT_HEAD_STABILISE = 0.31F;

    /** Not 0.35, and on the far side of it: a hand held far more level than the shipped default asks. */
    static final float DEFAULT_HAND_STABILISE = 0.84F;

    /** Not 0.1. */
    static final float MIN_STRIDE = 0.41F;

    /** Not 8. */
    static final float MAX_STRIDE = 2.9F;

    /** Not 2. */
    static final float MAX_SCALE = 1.25F;

    /** Not 1.0E-3. */
    static final float MIN_SEGMENT = 0.077F;

    /** Not 16. */
    static final float MAX_SEGMENT = 3.5F;

    private GaitFixtures() {
    }

    /** A builder with every value supplied, so a test may override exactly one. */
    static GaitTuning.Builder tuningBuilder() {
        return GaitTuning.builder()
                .counterPhaseCycles(0.5F)
                .restingLegExtension(0.91F)
                .movementDeadbandBlocksPerTick(0.007F)
                .sneakTorsoPitchRadians(0.41F)
                .fullStrideSpeedBlocksPerTick(0.183F)
                // Not 0.28, and nowhere near it: a fixture sprint speed that happened to agree with the
                // shipped one could not tell a read value from an ignored one.
                .sprintSpeedBlocksPerTick(0.41F)
                .strideToFootReach(0.31F)
                .footLiftFraction(0.23F)
                // Not 0.45 and not 0.18 — and the heel angle is deliberately the smaller of the pair in
                // the fixture as well as in the shipped file, so a swapped pair still shows up.
                .footToeOffRadians(0.61F)
                .footHeelStrikeRadians(0.07F)
                .maxIkReachFraction(0.97F)
                .minIkDownFraction(0.29F)
                .legSwingRadians(0.61F)
                // Not 1.05, and below rather than above the walk amplitude it is blended from, so a
                // solver that ignored the blend direction cannot pass by accident.
                .sprintLegSwingRadians(0.83F)
                .armSwingRadians(0.47F)
                .forearmCurlRadians(0.29F)
                .attackDurationSeconds(0.37F)
                .attackUpperArmRadians(-1.11F)
                .attackForearmRadians(0.44F)
                .torsoLeanRadians(0.13F)
                .torsoBobRadians(0.041F)
                .torsoBobHarmonic(2F)
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
                .settledAmplitude(2.5E-3F)
                .defaultStrideLength(DEFAULT_STRIDE)
                .defaultHeadStabilise(DEFAULT_HEAD_STABILISE)
                .defaultHandStabilise(DEFAULT_HAND_STABILISE);
    }

    static GaitTuning tuning() {
        return tuningBuilder().build();
    }

    static BindingBounds bounds() {
        return new BindingBounds(MIN_SEGMENT, MAX_SEGMENT, MIN_STRIDE, MAX_STRIDE, MAX_SCALE);
    }
}
