package pandastic.studio.models.api.locomotion;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The calibrated look a {@link LocomotionSolver} scales: every angle, fraction, rate and guard a walk
 * cycle is evaluated from, plus the three defaults a {@link GaitProfile} is seeded with.
 *
 * <h2>Why all of it is configuration</h2>
 * A {@link GaitProfile} is deliberately dimensionless — {@code legSwing: 1} means "the calibrated leg
 * swing", so a rig file stays portable across solver revisions. That portability has a price: the
 * calibration those scales multiply has to live <em>somewhere</em>, and as constants in the solver it is
 * one number per build, chosen once, for one body shape.
 *
 * <p>Most of these were chosen rather than measured and have never been seen on a real model, which is
 * the strongest argument for making them reachable: the first person to look at a walking model will want
 * to move half of them, and a rebuild per iteration is how a gait stays wrong.
 *
 * <h2>Three kinds of number, and they fail differently</h2>
 * <ul>
 *   <li><strong>Amplitudes</strong> — the {@code *Radians} values. Wrong, they look wrong. Harmless.</li>
 *   <li><strong>Structure</strong> — {@link #counterPhaseCycles()}, {@link #torsoBobHarmonic()},
 *       {@link #strideToFootReach()}. These fall out of the geometry of walking; moving them produces a
 *       limp or a model whose arms no longer oppose its legs, which reads as a bug rather than a style.</li>
 *   <li><strong>Guards</strong> — {@link #maxGaitSpeedBlocksPerTick()},
 *       {@link #maxPhaseAdvancePerStep()}, {@link #maxStepSeconds()}. These exist to stop arithmetic
 *       overflow reaching the client, where a NaN rotation deletes the model outright. They are the ones
 *       to leave alone.</li>
 * </ul>
 *
 * <p>Server ticks per second is <em>not</em> here: a driver's speeds are per tick and a delta is in
 * seconds, so the conversion between them is a unit, not a preference.
 *
 * @param counterPhaseCycles             offset between limbs in opposition, in cycles — left leg against
 *                                       right, arms against legs. One value rather than two because it is
 *                                       one fact: contralateral limbs are half a stride apart
 * @param restingLegExtension            how far a resting kneed leg extends, as a fraction of
 *                                       {@code thigh + shin}. Short of {@code 1} on purpose: at full
 *                                       extension the segments are collinear and the knee's direction is
 *                                       undefined, so a leg that straightens fully jitters
 * @param movementDeadbandBlocksPerTick  horizontal speed below which the gait settles instead of
 *                                       advancing. The most important number here — without it,
 *                                       floating-point noise in a sampled position delta walks the phase
 *                                       forward forever and a standing model shuffles its feet
 * @param sneakTorsoPitchRadians         torso pitch at full crouch
 * @param fullStrideSpeedBlocksPerTick   speed at which the stride is fully expressed. Divides into the
 *                                       amplitude ramp, so it must be positive
 * @param sprintSpeedBlocksPerTick       speed at which the sprint amplitudes are fully expressed. The
 *                                       <em>difference</em> from {@code fullStrideSpeedBlocksPerTick}
 *                                       divides into the sprint blend, so it must sit strictly above it
 * @param strideToFootReach              stride length to a foot's fore-aft reach amplitude. Derived: a
 *                                       foot travels one stride relative to the ground per cycle, so half
 *                                       a stride end to end relative to the hip — an amplitude of a quarter
 * @param footLiftFraction               peak foot lift during the swing half, as a fraction of leg reach
 * @param footToeOffRadians              peak plantarflexion — toes down — as the foot leaves the ground.
 *                                       Only reached when a foot is bound; a rig with no foot bone has
 *                                       nothing to pitch
 * @param footHeelStrikeRadians          peak dorsiflexion — toes up — as the foot comes back down to land
 * @param maxIkReachFraction             largest inverse-kinematics target radius, as a fraction of leg
 *                                       reach. The guard that keeps the knee off its singularity
 * @param minIkDownFraction              smallest distance the target may sit below the hip, as a fraction
 *                                       of leg reach. Keeps a lifted foot from rising to the hip, where
 *                                       the limb folds flat
 * @param legSwingRadians                peak swing of a one-piece leg at walking pace. Only used when no
 *                                       shin is bound
 * @param sprintLegSwingRadians          peak swing of a one-piece leg at {@code sprintSpeedBlocksPerTick},
 *                                       interpolated toward from {@code legSwingRadians}. Two amplitudes
 *                                       rather than one scaled, because a sprint is a longer-limbed gait
 *                                       and not a louder walk
 * @param armSwingRadians                peak arm swing
 * @param forearmCurlRadians             peak elbow curl
 * @param torsoLeanRadians               forward lean held while walking. Constant across the cycle — it is
 *                                       posture, not oscillation, so a negative value leans back rather
 *                                       than being meaningless
 * @param torsoBobRadians                amplitude of the walk bob on torso pitch
 * @param torsoBobHarmonic               cycles of bob per cycle of stride. Derived: the body rises and
 *                                       falls once per step and there are two steps in a cycle. Getting
 *                                       this wrong is the classic tell of a hand-made walk — a bob at
 *                                       stride frequency makes the model limp
 * @param torsoSwayRadians               amplitude of the weight-shift roll
 * @param torsoTwistRadians              amplitude of the shoulder twist against the hips
 * @param breathRateHz                   breathing frequency. Divides into {@link #elapsedWrapSeconds()},
 *                                       so it must be positive
 * @param breathRadians                  breathing amplitude on torso pitch. Its whole job is to stop a
 *                                       standing model looking like a statue, so anything large enough to
 *                                       notice as motion is too large
 * @param amplitudeBlendSeconds          seconds for the stride amplitude to ramp across its full range
 * @param airborneBlendSeconds           seconds for the airborne blend to cross. Blending the cycle out
 *                                       rather than freezing it is the requirement: a frozen cycle leaves
 *                                       the legs mid-stride in the air
 * @param sneakBlendSeconds              seconds for the crouch blend to cross
 * @param maxStepSeconds                 longest delta honoured. Because the phase advances with distance,
 *                                       a ten-second step would advance it by an arbitrary number of
 *                                       cycles — and an arbitrary number of cycles, modulo one, is an
 *                                       arbitrary phase
 * @param maxGaitSpeedBlocksPerTick      ceiling on the speed the gait responds to. A guard: a caller that
 *                                       forgot to filter teleports can hand over a finite but enormous
 *                                       speed, whose phase advance overflows to infinity — and infinity's
 *                                       fractional part is NaN
 * @param maxPhaseAdvancePerStep         ceiling on cycles advanced in one step. Unreachable with the two
 *                                       clamps above; it exists so they cannot be weakened independently
 *                                       and reintroduce the overflow
 * @param elapsedWrapBreathCycles        breathing cycles before the elapsed-time odometer wraps. Wrapping
 *                                       at a whole number of cycles is what makes the wrap continuous, so
 *                                       the breathing does not jump
 * @param settledAmplitude               amplitude below which the walk cycle counts as no longer
 *                                       contributing
 * @param defaultStrideLength            stride a {@link GaitProfile} is seeded with, in blocks. The number
 *                                       a rig author is most likely to want to change first
 * @param defaultHeadStabilise           head stabilisation a {@link GaitProfile} is seeded with. Below
 *                                       {@code 1} on purpose: a head held perfectly level while the body
 *                                       bobs reads as detached rather than as poised
 * @param defaultHandStabilise           hand stabilisation a {@link GaitProfile} is seeded with. Far below
 *                                       the head's, because a hand has no gaze to keep steady: it is held
 *                                       level only enough to stop the palm swivelling with the forearm
 */
public record GaitTuning(
        float counterPhaseCycles,
        float restingLegExtension,
        float movementDeadbandBlocksPerTick,
        float sneakTorsoPitchRadians,
        float fullStrideSpeedBlocksPerTick,
        float sprintSpeedBlocksPerTick,
        float strideToFootReach,
        float footLiftFraction,
        float footToeOffRadians,
        float footHeelStrikeRadians,
        float maxIkReachFraction,
        float minIkDownFraction,
        float legSwingRadians,
        float sprintLegSwingRadians,
        float armSwingRadians,
        float forearmCurlRadians,
        float attackDurationSeconds,
        float attackUpperArmRadians,
        float attackForearmRadians,
        float torsoLeanRadians,
        float torsoBobRadians,
        float torsoBobHarmonic,
        float torsoSwayRadians,
        float torsoTwistRadians,
        float breathRateHz,
        float breathRadians,
        float amplitudeBlendSeconds,
        float airborneBlendSeconds,
        float sneakBlendSeconds,
        float maxStepSeconds,
        float maxGaitSpeedBlocksPerTick,
        float maxPhaseAdvancePerStep,
        int elapsedWrapBreathCycles,
        float settledAmplitude,
        float defaultStrideLength,
        float defaultHeadStabilise,
        float defaultHandStabilise
) {

    public GaitTuning {
        requireFraction(counterPhaseCycles, "counterPhaseCycles");
        // Zero would collapse a resting leg onto its own hip; past one the target is out of reach and
        // the leg straightens into the singularity it is being kept away from.
        requirePositiveFraction(restingLegExtension, "restingLegExtension");
        requireNonNegative(movementDeadbandBlocksPerTick, "movementDeadbandBlocksPerTick");
        // An angle, and its sign selects a direction. Any finite value is a style.
        requireFinite(sneakTorsoPitchRadians, "sneakTorsoPitchRadians");
        requirePositive(fullStrideSpeedBlocksPerTick, "fullStrideSpeedBlocksPerTick");
        requirePositive(sprintSpeedBlocksPerTick, "sprintSpeedBlocksPerTick");
        // The sprint blend is (speed - walk) / (sprint - walk). Equal speeds divide by zero and an
        // inverted pair runs the blend backwards, so the sprint amplitudes would be expressed while
        // standing still and the walk amplitudes while sprinting.
        if (sprintSpeedBlocksPerTick <= fullStrideSpeedBlocksPerTick) {
            throw new IllegalArgumentException(
                    "sprintSpeedBlocksPerTick=" + sprintSpeedBlocksPerTick + " must sit strictly above"
                            + " fullStrideSpeedBlocksPerTick=" + fullStrideSpeedBlocksPerTick
                            + ": the sprint blend divides by the difference between them");
        }
        requireNonNegative(strideToFootReach, "strideToFootReach");
        requireNonNegative(footLiftFraction, "footLiftFraction");
        // Angles whose sign selects which way the ankle rolls, so finiteness is the only arithmetic
        // constraint: a rig modelled with its foot bone pointing backward wants them negated.
        requireFinite(footToeOffRadians, "footToeOffRadians");
        requireFinite(footHeelStrikeRadians, "footHeelStrikeRadians");
        requirePositiveFraction(maxIkReachFraction, "maxIkReachFraction");
        requireFraction(minIkDownFraction, "minIkDownFraction");
        if (minIkDownFraction > maxIkReachFraction) {
            throw new IllegalArgumentException(
                    "Inverted range: minIkDownFraction=" + minIkDownFraction
                            + " > maxIkReachFraction=" + maxIkReachFraction
                            + ", so no foot target would satisfy both clamps");
        }
        requireFinite(legSwingRadians, "legSwingRadians");
        requireFinite(sprintLegSwingRadians, "sprintLegSwingRadians");
        requireFinite(armSwingRadians, "armSwingRadians");
        requireFinite(forearmCurlRadians, "forearmCurlRadians");
        requirePositive(attackDurationSeconds, "attackDurationSeconds");
        requireFinite(attackUpperArmRadians, "attackUpperArmRadians");
        requireFinite(attackForearmRadians, "attackForearmRadians");
        requireFinite(torsoLeanRadians, "torsoLeanRadians");
        requireFinite(torsoBobRadians, "torsoBobRadians");
        requireNonNegative(torsoBobHarmonic, "torsoBobHarmonic");
        requireFinite(torsoSwayRadians, "torsoSwayRadians");
        requireFinite(torsoTwistRadians, "torsoTwistRadians");
        requirePositive(breathRateHz, "breathRateHz");
        requireFinite(breathRadians, "breathRadians");
        // A blend of zero seconds is a division by zero, not a snap. Snapping is what a blend exists to
        // avoid, so there is no legitimate caller for it.
        requirePositive(amplitudeBlendSeconds, "amplitudeBlendSeconds");
        requirePositive(airborneBlendSeconds, "airborneBlendSeconds");
        requirePositive(sneakBlendSeconds, "sneakBlendSeconds");
        requirePositive(maxStepSeconds, "maxStepSeconds");
        requirePositive(maxGaitSpeedBlocksPerTick, "maxGaitSpeedBlocksPerTick");
        requirePositive(maxPhaseAdvancePerStep, "maxPhaseAdvancePerStep");
        if (elapsedWrapBreathCycles < 1) {
            throw new IllegalArgumentException(
                    "elapsedWrapBreathCycles must be at least 1 but was " + elapsedWrapBreathCycles);
        }
        requirePositiveFraction(settledAmplitude, "settledAmplitude");
        requirePositive(defaultStrideLength, "defaultStrideLength");
        requireFraction(defaultHeadStabilise, "defaultHeadStabilise");
        requireFraction(defaultHandStabilise, "defaultHandStabilise");
    }

    private static void requireFinite(float value, @NotNull String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    private static void requirePositive(float value, @NotNull String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireNonNegative(float value, @NotNull String name) {
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(name + " must be finite and at least 0 but was " + value);
        }
    }

    /** A fraction is {@code [0, 1]} by definition; that is arithmetic rather than a preference. */
    private static void requireFraction(float value, @NotNull String name) {
        if (!Float.isFinite(value) || value < 0F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " is a fraction and must be within [0, 1] but was " + value);
        }
    }

    private static void requirePositiveFraction(float value, @NotNull String name) {
        if (!Float.isFinite(value) || value <= 0F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " is a fraction and must be within (0, 1] but was " + value);
        }
    }

    /** A builder in which every value must be supplied; there are no defaults to fall back on. */
    @Contract(value = "-> new", pure = true)
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Seconds before the elapsed-time odometer wraps.
     *
     * <p>Derived rather than configured: {@code elapsedWrapBreathCycles / breathRateHz}, so the wrap
     * lands on a breathing zero crossing however the rate is set. Configuring it separately would let the
     * two disagree, and a wrap off a zero crossing makes every standing model on the server twitch in
     * unison.
     */
    public float elapsedWrapSeconds() {
        return this.elapsedWrapBreathCycles / this.breathRateHz;
    }

    /**
     * Width of the band between walking pace and sprinting pace, in blocks per tick.
     *
     * <p>Derived rather than configured, and guaranteed positive by the constructor, so the sprint blend
     * is a plain division with no guard at the call site. Configuring it as well as the two speeds would
     * be a third place to say the same thing, and the three could disagree.
     */
    public float sprintSpeedBandBlocksPerTick() {
        return this.sprintSpeedBlocksPerTick - this.fullStrideSpeedBlocksPerTick;
    }

    /**
     * Mutable builder for a {@link GaitTuning}.
     *
     * <p>Thirty-four same-typed numeric components cannot be passed positionally by a human being, so
     * this is the supported way to assemble one. It carries <strong>no defaults</strong> on purpose: a
     * default here would be exactly the hardcoded calibration this type exists to remove, and a builder
     * that quietly substituted zero would turn a missing configuration key into a model that stands
     * still — the hardest kind of defect to attribute. {@link #build()} names every value that was never
     * supplied instead.
     */
    public static final class Builder {

        private final @NotNull Set<String> unset = new LinkedHashSet<>(List.of(
                "counterPhaseCycles", "restingLegExtension", "movementDeadbandBlocksPerTick",
                "sneakTorsoPitchRadians", "fullStrideSpeedBlocksPerTick", "sprintSpeedBlocksPerTick",
                "strideToFootReach", "footLiftFraction", "footToeOffRadians", "footHeelStrikeRadians",
                "maxIkReachFraction", "minIkDownFraction", "legSwingRadians", "sprintLegSwingRadians",
                "armSwingRadians", "forearmCurlRadians", "attackDurationSeconds",
                "attackUpperArmRadians", "attackForearmRadians", "torsoLeanRadians", "torsoBobRadians",
                "torsoBobHarmonic", "torsoSwayRadians", "torsoTwistRadians", "breathRateHz",
                "breathRadians", "amplitudeBlendSeconds", "airborneBlendSeconds", "sneakBlendSeconds",
                "maxStepSeconds", "maxGaitSpeedBlocksPerTick", "maxPhaseAdvancePerStep",
                "elapsedWrapBreathCycles", "settledAmplitude", "defaultStrideLength",
                "defaultHeadStabilise", "defaultHandStabilise"));

        private float counterPhaseCycles;
        private float restingLegExtension;
        private float movementDeadbandBlocksPerTick;
        private float sneakTorsoPitchRadians;
        private float fullStrideSpeedBlocksPerTick;
        private float sprintSpeedBlocksPerTick;
        private float strideToFootReach;
        private float footLiftFraction;
        private float footToeOffRadians;
        private float footHeelStrikeRadians;
        private float maxIkReachFraction;
        private float minIkDownFraction;
        private float legSwingRadians;
        private float sprintLegSwingRadians;
        private float armSwingRadians;
        private float forearmCurlRadians;
        private float attackDurationSeconds;
        private float attackUpperArmRadians;
        private float attackForearmRadians;
        private float torsoLeanRadians;
        private float torsoBobRadians;
        private float torsoBobHarmonic;
        private float torsoSwayRadians;
        private float torsoTwistRadians;
        private float breathRateHz;
        private float breathRadians;
        private float amplitudeBlendSeconds;
        private float airborneBlendSeconds;
        private float sneakBlendSeconds;
        private float maxStepSeconds;
        private float maxGaitSpeedBlocksPerTick;
        private float maxPhaseAdvancePerStep;
        private int elapsedWrapBreathCycles;
        private float settledAmplitude;
        private float defaultStrideLength;
        private float defaultHeadStabilise;
        private float defaultHandStabilise;

        private Builder() {
        }

        public @NotNull Builder counterPhaseCycles(float counterPhaseCycles) {
            this.counterPhaseCycles = counterPhaseCycles;
            this.unset.remove("counterPhaseCycles");
            return this;
        }

        public @NotNull Builder restingLegExtension(float restingLegExtension) {
            this.restingLegExtension = restingLegExtension;
            this.unset.remove("restingLegExtension");
            return this;
        }

        public @NotNull Builder movementDeadbandBlocksPerTick(float movementDeadbandBlocksPerTick) {
            this.movementDeadbandBlocksPerTick = movementDeadbandBlocksPerTick;
            this.unset.remove("movementDeadbandBlocksPerTick");
            return this;
        }

        public @NotNull Builder sneakTorsoPitchRadians(float sneakTorsoPitchRadians) {
            this.sneakTorsoPitchRadians = sneakTorsoPitchRadians;
            this.unset.remove("sneakTorsoPitchRadians");
            return this;
        }

        public @NotNull Builder fullStrideSpeedBlocksPerTick(float fullStrideSpeedBlocksPerTick) {
            this.fullStrideSpeedBlocksPerTick = fullStrideSpeedBlocksPerTick;
            this.unset.remove("fullStrideSpeedBlocksPerTick");
            return this;
        }

        public @NotNull Builder sprintSpeedBlocksPerTick(float sprintSpeedBlocksPerTick) {
            this.sprintSpeedBlocksPerTick = sprintSpeedBlocksPerTick;
            this.unset.remove("sprintSpeedBlocksPerTick");
            return this;
        }

        public @NotNull Builder strideToFootReach(float strideToFootReach) {
            this.strideToFootReach = strideToFootReach;
            this.unset.remove("strideToFootReach");
            return this;
        }

        public @NotNull Builder footLiftFraction(float footLiftFraction) {
            this.footLiftFraction = footLiftFraction;
            this.unset.remove("footLiftFraction");
            return this;
        }

        public @NotNull Builder footToeOffRadians(float footToeOffRadians) {
            this.footToeOffRadians = footToeOffRadians;
            this.unset.remove("footToeOffRadians");
            return this;
        }

        public @NotNull Builder footHeelStrikeRadians(float footHeelStrikeRadians) {
            this.footHeelStrikeRadians = footHeelStrikeRadians;
            this.unset.remove("footHeelStrikeRadians");
            return this;
        }

        public @NotNull Builder maxIkReachFraction(float maxIkReachFraction) {
            this.maxIkReachFraction = maxIkReachFraction;
            this.unset.remove("maxIkReachFraction");
            return this;
        }

        public @NotNull Builder minIkDownFraction(float minIkDownFraction) {
            this.minIkDownFraction = minIkDownFraction;
            this.unset.remove("minIkDownFraction");
            return this;
        }

        public @NotNull Builder legSwingRadians(float legSwingRadians) {
            this.legSwingRadians = legSwingRadians;
            this.unset.remove("legSwingRadians");
            return this;
        }

        public @NotNull Builder sprintLegSwingRadians(float sprintLegSwingRadians) {
            this.sprintLegSwingRadians = sprintLegSwingRadians;
            this.unset.remove("sprintLegSwingRadians");
            return this;
        }

        public @NotNull Builder armSwingRadians(float armSwingRadians) {
            this.armSwingRadians = armSwingRadians;
            this.unset.remove("armSwingRadians");
            return this;
        }

        public @NotNull Builder forearmCurlRadians(float forearmCurlRadians) {
            this.forearmCurlRadians = forearmCurlRadians;
            this.unset.remove("forearmCurlRadians");
            return this;
        }

        public @NotNull Builder attackDurationSeconds(float attackDurationSeconds) {
            this.attackDurationSeconds = attackDurationSeconds;
            this.unset.remove("attackDurationSeconds");
            return this;
        }

        public @NotNull Builder attackUpperArmRadians(float attackUpperArmRadians) {
            this.attackUpperArmRadians = attackUpperArmRadians;
            this.unset.remove("attackUpperArmRadians");
            return this;
        }

        public @NotNull Builder attackForearmRadians(float attackForearmRadians) {
            this.attackForearmRadians = attackForearmRadians;
            this.unset.remove("attackForearmRadians");
            return this;
        }

        public @NotNull Builder torsoLeanRadians(float torsoLeanRadians) {
            this.torsoLeanRadians = torsoLeanRadians;
            this.unset.remove("torsoLeanRadians");
            return this;
        }

        public @NotNull Builder torsoBobRadians(float torsoBobRadians) {
            this.torsoBobRadians = torsoBobRadians;
            this.unset.remove("torsoBobRadians");
            return this;
        }

        public @NotNull Builder torsoBobHarmonic(float torsoBobHarmonic) {
            this.torsoBobHarmonic = torsoBobHarmonic;
            this.unset.remove("torsoBobHarmonic");
            return this;
        }

        public @NotNull Builder torsoSwayRadians(float torsoSwayRadians) {
            this.torsoSwayRadians = torsoSwayRadians;
            this.unset.remove("torsoSwayRadians");
            return this;
        }

        public @NotNull Builder torsoTwistRadians(float torsoTwistRadians) {
            this.torsoTwistRadians = torsoTwistRadians;
            this.unset.remove("torsoTwistRadians");
            return this;
        }

        public @NotNull Builder breathRateHz(float breathRateHz) {
            this.breathRateHz = breathRateHz;
            this.unset.remove("breathRateHz");
            return this;
        }

        public @NotNull Builder breathRadians(float breathRadians) {
            this.breathRadians = breathRadians;
            this.unset.remove("breathRadians");
            return this;
        }

        public @NotNull Builder amplitudeBlendSeconds(float amplitudeBlendSeconds) {
            this.amplitudeBlendSeconds = amplitudeBlendSeconds;
            this.unset.remove("amplitudeBlendSeconds");
            return this;
        }

        public @NotNull Builder airborneBlendSeconds(float airborneBlendSeconds) {
            this.airborneBlendSeconds = airborneBlendSeconds;
            this.unset.remove("airborneBlendSeconds");
            return this;
        }

        public @NotNull Builder sneakBlendSeconds(float sneakBlendSeconds) {
            this.sneakBlendSeconds = sneakBlendSeconds;
            this.unset.remove("sneakBlendSeconds");
            return this;
        }

        public @NotNull Builder maxStepSeconds(float maxStepSeconds) {
            this.maxStepSeconds = maxStepSeconds;
            this.unset.remove("maxStepSeconds");
            return this;
        }

        public @NotNull Builder maxGaitSpeedBlocksPerTick(float maxGaitSpeedBlocksPerTick) {
            this.maxGaitSpeedBlocksPerTick = maxGaitSpeedBlocksPerTick;
            this.unset.remove("maxGaitSpeedBlocksPerTick");
            return this;
        }

        public @NotNull Builder maxPhaseAdvancePerStep(float maxPhaseAdvancePerStep) {
            this.maxPhaseAdvancePerStep = maxPhaseAdvancePerStep;
            this.unset.remove("maxPhaseAdvancePerStep");
            return this;
        }

        public @NotNull Builder elapsedWrapBreathCycles(int elapsedWrapBreathCycles) {
            this.elapsedWrapBreathCycles = elapsedWrapBreathCycles;
            this.unset.remove("elapsedWrapBreathCycles");
            return this;
        }

        public @NotNull Builder settledAmplitude(float settledAmplitude) {
            this.settledAmplitude = settledAmplitude;
            this.unset.remove("settledAmplitude");
            return this;
        }

        public @NotNull Builder defaultStrideLength(float defaultStrideLength) {
            this.defaultStrideLength = defaultStrideLength;
            this.unset.remove("defaultStrideLength");
            return this;
        }

        public @NotNull Builder defaultHeadStabilise(float defaultHeadStabilise) {
            this.defaultHeadStabilise = defaultHeadStabilise;
            this.unset.remove("defaultHeadStabilise");
            return this;
        }

        public @NotNull Builder defaultHandStabilise(float defaultHandStabilise) {
            this.defaultHandStabilise = defaultHandStabilise;
            this.unset.remove("defaultHandStabilise");
            return this;
        }

        /**
         * Validates and freezes the calibration.
         *
         * @throws IllegalStateException    if any value was never supplied
         * @throws IllegalArgumentException if one of them is outside the range its term can survive
         */
        public @NotNull GaitTuning build() {
            if (!this.unset.isEmpty()) {
                throw new IllegalStateException(
                        "GaitTuning has no defaults — every value must come from configuration."
                                + " Never supplied: " + this.unset);
            }
            return new GaitTuning(
                    this.counterPhaseCycles, this.restingLegExtension,
                    this.movementDeadbandBlocksPerTick, this.sneakTorsoPitchRadians,
                    this.fullStrideSpeedBlocksPerTick, this.sprintSpeedBlocksPerTick,
                    this.strideToFootReach, this.footLiftFraction, this.footToeOffRadians,
                    this.footHeelStrikeRadians, this.maxIkReachFraction, this.minIkDownFraction,
                    this.legSwingRadians, this.sprintLegSwingRadians, this.armSwingRadians,
                    this.forearmCurlRadians, this.attackDurationSeconds, this.attackUpperArmRadians,
                    this.attackForearmRadians, this.torsoLeanRadians, this.torsoBobRadians,
                    this.torsoBobHarmonic, this.torsoSwayRadians, this.torsoTwistRadians,
                    this.breathRateHz, this.breathRadians, this.amplitudeBlendSeconds,
                    this.airborneBlendSeconds, this.sneakBlendSeconds, this.maxStepSeconds,
                    this.maxGaitSpeedBlocksPerTick, this.maxPhaseAdvancePerStep,
                    this.elapsedWrapBreathCycles, this.settledAmplitude, this.defaultStrideLength,
                    this.defaultHeadStabilise, this.defaultHandStabilise);
        }
    }
}
