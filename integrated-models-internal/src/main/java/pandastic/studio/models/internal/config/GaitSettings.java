package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code gait.yml}: the closed-form walk cycle's calibration, plus the bounds a
 * locomotion binding and a gait profile are validated against.
 *
 * <p>Almost every value here was <em>chosen</em> rather than measured — the walk cycle's cosmetic
 * constants have never been seen on a running server. That is the reason this file exists at all: a
 * guess that lives in a config file can be corrected by whoever first looks at the model, while a
 * guess compiled into a jar needs a release.
 *
 * <p>The keys split into three groups, in file order: timing and geometry of the step, amplitudes of
 * the decorative motion, and the guards that keep the arithmetic finite. The guards look redundant
 * next to the clamps they duplicate; they are not. Each one removes an overflow path that ends in a
 * NaN rotation, and a NaN rotation deletes the model on the client rather than looking wrong.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "GaitConfig", format = ConfigFormat.YAML)
public interface GaitSettings {

    /**
     * Offset between limbs in opposition, in cycles — left leg against right, and arms against legs.
     *
     * <p>One value rather than two because it is one fact: contralateral limbs are half a stride
     * apart. Giving arms a separate offset would invite a rig whose arms are in counter-phase with
     * nothing.
     *
     * @return the phase offset, in cycles
     */
    @ConfigValue(comment = "Phase offset in cycles between limbs in opposition - left leg against right, arms against legs. Half a cycle is the anatomical fact; other values look like a limp.")
    default float counterPhaseCycles() {
        return 0.5F;
    }

    /**
     * How far a resting kneed leg extends, as a fraction of {@code thigh + shin}.
     *
     * <p>Short of 1 on purpose. With equal segments this leaves about 0.28 rad of knee bend at rest,
     * which is enough that the solved angles respond smoothly to the foot target instead of snapping
     * as the limb crosses its fully straight singularity.
     *
     * @return the resting extension, as a fraction of total leg reach
     */
    @ConfigValue(comment = "How far a resting kneed leg extends, as a fraction of thigh plus shin. Deliberately short of 1 so the knee never sits on its straight-leg singularity.")
    default float restingLegExtension() {
        return 0.95F;
    }

    /**
     * Horizontal speed, in blocks per server tick, below which the gait settles.
     *
     * <p>Roughly a seventieth of walking pace: below it an entity is either standing or being nudged
     * by collision resolution, and neither should produce a step.
     *
     * @return the deadband, in blocks per tick
     */
    @ConfigValue(comment = "Horizontal speed in blocks per tick below which the gait settles. Below it an entity is standing or being nudged by collision, and neither should produce a step.")
    default float movementDeadbandBlocksPerTick() {
        return 0.003F;
    }

    /**
     * Torso pitch at full crouch, in radians (about 20 degrees).
     *
     * <p>Eased in over {@link #sneakBlendSeconds()} rather than switched on, because a crouch that
     * snaps reads as a glitch while the same angle blended reads as the model ducking.
     *
     * @return the crouch pitch, in radians
     */
    @ConfigValue(comment = "Torso pitch at full crouch, in radians. Eased in over sneakBlendSeconds rather than switched on, because a crouch that snaps reads as a glitch.")
    default float sneakTorsoPitchRadians() {
        return 0.35F;
    }

    /**
     * Speed at which the stride is fully expressed, in blocks per tick.
     *
     * <p>Vanilla walking pace, taken as given rather than tuned: it is the speed the overwhelming
     * majority of entities actually move at, so it is the speed the gait should look correct at.
     * Sprinting saturates the amplitude, which is right — a sprint is a different gait, not a louder
     * walk.
     *
     * @return the reference speed, in blocks per tick
     */
    @ConfigValue(comment = "Speed in blocks per tick at which the stride is fully expressed. Vanilla walking pace: sprinting saturates the amplitude rather than exaggerating it.")
    default float fullStrideSpeedBlocksPerTick() {
        return 0.215F;
    }

    /**
     * Speed at which the sprint amplitudes are fully expressed, in blocks per tick.
     *
     * <p>Vanilla sprinting pace, which is walking pace times the 1.3 sprint multiplier — taken as
     * given rather than tuned, for the same reason {@link #fullStrideSpeedBlocksPerTick()} is. The
     * <em>difference</em> between the two divides into the sprint blend, so this must sit strictly
     * above it; equal values would divide by zero and an inverted pair would express the sprint
     * amplitudes while standing still.
     *
     * @return the sprint reference speed, in blocks per tick
     */
    @ConfigValue(comment = "Speed in blocks per tick at which the sprint amplitudes are fully expressed. Vanilla sprinting pace, which is walking pace times 1.3. Must sit strictly above fullStrideSpeedBlocksPerTick: the sprint blend divides by the gap between them.")
    default float sprintSpeedBlocksPerTick() {
        return 0.28F;
    }

    /**
     * Stride length to a foot's fore-aft reach amplitude.
     *
     * <p>Derived, not chosen: a full cycle covers one stride, each foot travels that stride relative
     * to the ground, so relative to the hip it swings half a stride end to end — an amplitude of a
     * quarter. Editing it desynchronises the foot from the ground it is supposed to be standing on.
     *
     * @return the conversion factor from stride length to foot reach amplitude
     */
    @ConfigValue(comment = "Converts stride length into a foot's fore-aft swing amplitude. Derived from the geometry, not chosen: a quarter is what keeps the foot planted relative to the ground.")
    default float strideToFootReach() {
        return 0.25F;
    }

    /**
     * Peak foot lift during the swing half, as a fraction of leg reach.
     *
     * <p>Large enough to read as a step rather than a shuffle, small enough that a leg at
     * {@link #restingLegExtension()} never has to fold past its comfortable range.
     *
     * @return the peak lift, as a fraction of leg reach
     */
    @ConfigValue(comment = "Peak foot lift during the swing half, as a fraction of leg reach. Too small reads as a shuffle; too large folds the knee past its comfortable range.")
    default float footLiftFraction() {
        return 0.18F;
    }

    /**
     * Peak plantarflexion — toes down — as the foot leaves the ground, in radians (about 26 degrees).
     *
     * <p>Only reached by a rig that binds a foot bone; without one the inverse kinematics places the
     * ankle and there is nothing below it to pitch. Chosen rather than measured, and never seen on a
     * model: a push-off is the most pronounced thing an ankle does, so it is the larger of the two
     * ankle angles by some margin.
     *
     * <p>Its sign selects which way the ankle rolls. A rig whose foot bone was modelled pointing
     * backward wants both this and {@link #footHeelStrikeRadians()} negated, which is why neither is
     * constrained to be positive.
     *
     * @return the peak toe-off angle, in radians
     */
    @ConfigValue(comment = "Peak plantarflexion - toes down - as the foot pushes off, in radians. Only used by a rig that binds a foot bone. Negate it along with footHeelStrikeRadians for a rig whose foot bone points backward.")
    default float footToeOffRadians() {
        return 0.45F;
    }

    /**
     * Peak dorsiflexion — toes up — as the foot comes back down to land, in radians (about 10
     * degrees).
     *
     * <p>Smaller than the toe-off on purpose: landing heel first needs only enough lift to present the
     * heel, whereas a push-off goes up on the toes. Chosen, not measured.
     *
     * <p>Note what neither ankle angle does: contribute during stance. Both are shaped by the same
     * window as the foot's lift, so a planted foot is held exactly flat rather than nearly flat — a
     * planted foot that rolls is a foot sliding through the floor.
     *
     * @return the peak heel-strike angle, in radians
     */
    @ConfigValue(comment = "Peak dorsiflexion - toes up - as the foot comes down to land, in radians. Smaller than the toe-off: landing needs only enough lift to present a heel. Zero through the whole stance half, so a planted foot never rolls.")
    default float footHeelStrikeRadians() {
        return 0.18F;
    }

    /**
     * Largest inverse-kinematics target radius, as a fraction of leg reach.
     *
     * <p>The guard that keeps the knee off its singularity. The two-bone solver straightens rather
     * than inverting when a target is out of reach, so an unclamped target is not unsafe — it simply
     * produces a fully straight leg, which is the configuration whose solved angles are noisiest.
     *
     * @return the reach ceiling, as a fraction of leg reach
     */
    @ConfigValue(comment = "Largest IK target radius as a fraction of leg reach. Keeps the knee off its singularity, where the solved angles are noisiest. Must stay above minIkDownFraction.")
    default float maxIkReachFraction() {
        return 0.99F;
    }

    /**
     * Smallest distance the inverse-kinematics target may sit below the hip, as a fraction of leg
     * reach.
     *
     * <p>Keeps a lifted foot from rising to the hip, where the limb folds flat and the knee's plane
     * stops being well determined.
     *
     * @return the minimum downward offset, as a fraction of leg reach
     */
    @ConfigValue(comment = "Smallest distance an IK target may sit below the hip, as a fraction of leg reach. Stops a lifted foot reaching the hip, where the knee's plane is undefined.")
    default float minIkDownFraction() {
        return 0.35F;
    }

    /**
     * Peak swing of a one-piece leg at walking pace, in radians (about 40 degrees).
     *
     * <p>Only used when no shin is bound; a kneed leg's thigh angle comes out of the IK instead.
     *
     * @return the peak leg swing at walking pace, in radians
     */
    @ConfigValue(comment = "Peak swing of a one-piece leg at walking pace, in radians. Only used when a rig binds no shin; a kneed leg's thigh angle comes out of the IK instead.")
    default float legSwingRadians() {
        return 0.70F;
    }

    /**
     * Peak swing of a one-piece leg at {@link #sprintSpeedBlocksPerTick()}, in radians (about 60
     * degrees).
     *
     * <p>Two amplitudes interpolated by speed rather than one amplitude scaled, because a sprint is
     * not a louder walk: the limb reaches further and spends less of the cycle planted. Below walking
     * pace this value has no effect at all, so it changes nothing a model was already doing.
     *
     * <p>Half again as large as the walking swing. Chosen, not measured, and never seen on a model.
     *
     * @return the peak leg swing at sprinting pace, in radians
     */
    @ConfigValue(comment = "Peak swing of a one-piece leg at sprintSpeedBlocksPerTick, in radians. Interpolated toward from legSwingRadians, so it has no effect at or below walking pace. A sprint reaches further than a walk rather than being a louder one.")
    default float sprintLegSwingRadians() {
        return 1.05F;
    }

    /**
     * Peak arm swing, in radians (about 32 degrees).
     *
     * @return the peak arm swing, in radians
     */
    @ConfigValue(comment = "Peak arm swing, in radians. Runs in counter-phase with the legs.")
    default float armSwingRadians() {
        return 0.55F;
    }

    /**
     * Peak elbow curl, in radians (about 20 degrees).
     *
     * <p>Rectified so it is never negative: an elbow folds one way.
     *
     * @return the peak elbow curl, in radians
     */
    @ConfigValue(comment = "Peak elbow curl, in radians. Rectified before use, because an elbow folds one way.")
    default float forearmCurlRadians() {
        return 0.35F;
    }

    /** Duration of the generic right-hand attack swing, in seconds. */
    @ConfigValue(comment = "Duration of the generic right-hand attack swing, in seconds.")
    default float attackDurationSeconds() {
        return 0.35F;
    }

    /** Peak upper-arm pitch of the generic right-hand attack swing, in radians. */
    @ConfigValue(comment = "Peak upper-arm pitch of the generic right-hand attack swing, in radians.")
    default float attackUpperArmRadians() {
        return -1.35F;
    }

    /** Peak forearm pitch of the generic right-hand attack swing, in radians. */
    @ConfigValue(comment = "Peak forearm pitch of the generic right-hand attack swing, in radians.")
    default float attackForearmRadians() {
        return 0.55F;
    }

    /**
     * Forward lean held while walking, in radians (about 6 degrees).
     *
     * <p>Constant across the cycle — it is posture, not oscillation.
     *
     * @return the walking lean, in radians
     */
    @ConfigValue(comment = "Forward lean held while walking, in radians. Constant across the cycle: this is posture, not oscillation.")
    default float torsoLeanRadians() {
        return 0.10F;
    }

    /**
     * Amplitude of the walk bob on torso pitch, in radians (about 2 degrees).
     *
     * @return the bob amplitude, in radians
     */
    @ConfigValue(comment = "Amplitude of the walk bob on torso pitch, in radians.")
    default float torsoBobRadians() {
        return 0.035F;
    }

    /**
     * Cycles of bob per cycle of stride.
     *
     * <p>Derived: the body rises and falls once per step and there are two steps in a cycle, so the
     * bob runs at twice the stride frequency. Getting this wrong is the classic tell of a hand-made
     * walk — a bob at stride frequency makes the model limp.
     *
     * @return the bob harmonic, in cycles per stride cycle
     */
    @ConfigValue(comment = "Cycles of torso bob per cycle of stride. Two is derived: the body rises once per step and there are two steps per cycle. One makes the model limp.")
    default float torsoBobHarmonic() {
        return 2F;
    }

    /**
     * Amplitude of the weight-shift roll, in radians (about 3.5 degrees).
     *
     * @return the sway amplitude, in radians
     */
    @ConfigValue(comment = "Amplitude of the weight-shift roll on the torso, in radians.")
    default float torsoSwayRadians() {
        return 0.06F;
    }

    /**
     * Amplitude of the shoulder twist against the hips, in radians (about 4.5 degrees).
     *
     * <p>Opposed to the roll's sign so the torso counter-rotates against the legs, which is what the
     * arm counter-phase is there to express.
     *
     * @return the twist amplitude, in radians
     */
    @ConfigValue(comment = "Amplitude of the shoulder twist against the hips, in radians. Signed against the roll so the torso counter-rotates with the arms.")
    default float torsoTwistRadians() {
        return 0.08F;
    }

    /**
     * Breathing frequency, in hertz — one breath every four seconds at the default.
     *
     * <p>The elapsed-time odometer wraps at a whole number of these cycles, so this value and
     * {@link #elapsedWrapBreathCycles()} together decide where the wrap lands.
     *
     * @return the breathing rate, in hertz
     */
    @ConfigValue(comment = "Breathing frequency in hertz; the default is one breath every four seconds. Together with elapsedWrapBreathCycles it decides where the elapsed-time odometer wraps.")
    default float breathRateHz() {
        return 0.25F;
    }

    /**
     * Breathing amplitude on torso pitch, in radians (about 1.1 degrees).
     *
     * <p>Deliberately near the threshold of visibility: its entire job is to stop a standing model
     * from looking like a statue, and anything large enough to notice as motion is too large.
     *
     * @return the breathing amplitude, in radians
     */
    @ConfigValue(comment = "Breathing amplitude on torso pitch, in radians. Deliberately near the threshold of visibility: anything you can notice as motion is too much.")
    default float breathRadians() {
        return 0.02F;
    }

    /**
     * Seconds for the stride amplitude to ramp across its full range.
     *
     * <p>Shorter than the two stance blends because starting to walk should feel immediate.
     *
     * @return the amplitude blend time, in seconds
     */
    @ConfigValue(comment = "Seconds for the stride amplitude to ramp across its full range. Shorter than the stance blends, because starting to walk should feel immediate.")
    default float amplitudeBlendSeconds() {
        return 0.15F;
    }

    /**
     * Seconds for the airborne blend to cross.
     *
     * <p>Blending the cycle out rather than freezing it is the requirement; this is how long it takes.
     * A frozen cycle leaves the legs mid-stride in the air, which looks like the animation crashed.
     *
     * @return the airborne blend time, in seconds
     */
    @ConfigValue(comment = "Seconds for the airborne blend to cross. The cycle blends out rather than freezing: frozen legs mid-stride in the air look like the animation crashed.")
    default float airborneBlendSeconds() {
        return 0.20F;
    }

    /**
     * Seconds for the crouch blend to cross.
     *
     * @return the crouch blend time, in seconds
     */
    @ConfigValue(comment = "Seconds for the crouch blend to cross.")
    default float sneakBlendSeconds() {
        return 0.18F;
    }

    /**
     * Longest delta honoured in one step, in seconds.
     *
     * <p>Because the phase advances with distance travelled, a ten-second step would advance it by an
     * arbitrary number of cycles — and an arbitrary number of cycles, modulo one, is an arbitrary
     * phase. Clamping costs a little travelled ground after a stall; not clamping snaps the legs to a
     * random point in the stride.
     *
     * @return the step ceiling, in seconds
     */
    @ConfigValue(comment = "Longest delta honoured in one step, in seconds. Without it a stalled server resumes with the legs at a random point in the stride.")
    default float maxStepSeconds() {
        return 0.25F;
    }

    /**
     * Ceiling on the speed the gait responds to, in blocks per tick.
     *
     * <p>A guard, not a tuning value. Amplitude saturates at a fifth of this, so it changes nothing an
     * entity can actually do; what it removes is the arithmetic cliff. A caller that forgot to filter
     * teleports can legally hand over a finite but enormous speed, and the phase advance then overflows
     * to infinity — whose fractional part is NaN, and a NaN rotation deletes the model on the client.
     *
     * @return the speed ceiling, in blocks per tick
     */
    @ConfigValue(comment = "Ceiling on the speed the gait responds to, in blocks per tick. A guard: an unfiltered teleport would otherwise overflow the phase advance to NaN and delete the model.")
    default float maxGaitSpeedBlocksPerTick() {
        return 4F;
    }

    /**
     * Ceiling on cycles advanced in one step.
     *
     * <p>Unreachable with a clamped speed and a clamped delta; it exists so those two clamps cannot be
     * weakened independently and reintroduce the overflow.
     *
     * @return the phase advance ceiling, in cycles per step
     */
    @ConfigValue(comment = "Ceiling on cycles advanced in one step. Unreachable while the speed and delta clamps hold; it exists so they cannot be weakened independently.")
    default float maxPhaseAdvancePerStep() {
        return 4F;
    }

    /**
     * Breathing cycles before the elapsed-time odometer wraps.
     *
     * <p>Wrapping at a whole number of cycles is the part that matters: it makes the wrap continuous,
     * so the breathing does not jump. Wrapping at all matters because a float seconds counter left to
     * grow loses the resolution the oscillation needs after a few hours of uptime, and a decorative
     * model's uptime is the server's uptime.
     *
     * <p>A whole count rather than a duration, and declared as {@code int} for that reason: the file
     * cannot express a fractional number of cycles, so the wrap cannot land anywhere but on a zero
     * crossing.
     *
     * @return the wrap period, in breathing cycles
     */
    @ConfigValue(comment = "Breathing cycles before the elapsed-time odometer wraps. A whole count, so the wrap always lands on a zero crossing and the breathing never jumps; wrapping at all preserves float resolution over long uptime.")
    default int elapsedWrapBreathCycles() {
        return 256;
    }

    /**
     * Amplitude below which the walk cycle counts as no longer contributing.
     *
     * <p>Half of the "at rest" test: a model is only silent when the gait <em>and</em> the secondary
     * motion have both settled.
     *
     * @return the settled amplitude threshold
     */
    @ConfigValue(comment = "Amplitude below which the walk cycle counts as settled. A model only stops emitting when the gait and the physics have both settled.")
    default float settledAmplitude() {
        return 1.0E-3F;
    }

    /**
     * Default stride, in blocks, for a gait profile that does not state one.
     *
     * <p>A humanoid of player proportions covers roughly this much ground between successive left-foot
     * plants. Chosen, not measured — it is the number a rig author is most likely to want to change
     * first, which is the argument for it being in a file.
     *
     * @return the default stride length, in blocks
     */
    @ConfigValue(comment = "Default stride in blocks for a gait profile that does not state one. Roughly what a player-proportioned humanoid covers between left-foot plants.")
    default float defaultStrideLength() {
        return 1.3F;
    }

    /**
     * Default head stabilisation for a gait profile that does not state one.
     *
     * <p>Most of the torso's motion is cancelled at the neck, but not all of it: a head held perfectly
     * level while the body bobs reads as detached rather than as poised.
     *
     * @return the default head stabilisation, from 0 (head follows the torso) to 1 (head held level)
     */
    @ConfigValue(comment = "Default head stabilisation, from 0 (head follows the torso) to 1 (head held perfectly level). A perfectly level head reads as detached, not as poised.")
    default float defaultHeadStabilise() {
        return 0.65F;
    }

    /**
     * Default hand stabilisation for a gait profile that does not state one.
     *
     * <p>Far below the head's, and for a reason rather than by taste: a head is stabilised because the
     * model is looking somewhere, and there is no equivalent thing a hand is doing. What this value
     * buys is only that the palm does not swivel through the full range of the elbow curl — enough to
     * stop a hand group reading as the end of a stick, not enough to look like it is carrying a tray.
     * A rig whose hand holds an item wants it much higher, which is exactly why it is per-profile.
     *
     * <p>Chosen, not measured, and never seen on a model.
     *
     * @return the default hand stabilisation, from 0 (hand follows its parent) to 1 (hand held level)
     */
    @ConfigValue(comment = "Default hand stabilisation, from 0 (hand follows the forearm) to 1 (hand held perfectly level). Much lower than the head's: a hand has no gaze to keep steady, so this only stops the palm swivelling with the elbow curl. A rig holding an item wants it higher, per profile.")
    default float defaultHandStabilise() {
        return 0.35F;
    }

    /**
     * Shortest limb segment a locomotion binding accepts, in blocks.
     *
     * <p>Below this the two-bone solver's law-of-cosines denominator approaches zero and the solved
     * angle stops meaning anything.
     *
     * @return the minimum segment length, in blocks
     */
    @ConfigValue(comment = "Shortest limb segment a binding accepts, in blocks. Below it the two-bone solver's denominator approaches zero and the solved angle stops meaning anything.")
    default float minSegmentLength() {
        return 1.0E-3F;
    }

    /**
     * Longest limb segment a locomotion binding accepts, in blocks.
     *
     * <p>A sanity ceiling, not a geometric limit.
     *
     * @return the maximum segment length, in blocks
     */
    @ConfigValue(comment = "Longest limb segment a binding accepts, in blocks. A sanity ceiling rather than a geometric limit.")
    default float maxSegmentLength() {
        return 16F;
    }

    /**
     * Shortest stride a gait profile accepts, in blocks.
     *
     * <p>The phase advance divides by the stride, so a stride approaching zero turns any movement at
     * all into an unbounded number of cycles per step.
     *
     * @return the minimum stride length, in blocks
     */
    @ConfigValue(comment = "Shortest stride a gait profile accepts, in blocks. The phase advance divides by it, so a near-zero stride turns any movement into unbounded cycles per step.")
    default float minStrideLength() {
        return 0.1F;
    }

    /**
     * Longest stride a gait profile accepts, in blocks.
     *
     * @return the maximum stride length, in blocks
     */
    @ConfigValue(comment = "Longest stride a gait profile accepts, in blocks. A sanity ceiling.")
    default float maxStrideLength() {
        return 8F;
    }

    /**
     * Ceiling on every dimensionless scale a gait profile may declare.
     *
     * <p>Double the calibrated look is stylised; more is broken.
     *
     * @return the scale ceiling
     */
    @ConfigValue(comment = "Ceiling on every dimensionless scale a gait profile may declare. Double the calibrated look is stylised; more is broken.")
    default float maxScale() {
        return 2F;
    }
}
