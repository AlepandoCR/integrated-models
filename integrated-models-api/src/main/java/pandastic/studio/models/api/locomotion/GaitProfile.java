package pandastic.studio.models.api.locomotion;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How a particular model walks: one physical measurement and a handful of dimensionless scales.
 *
 * <p>The split is the same one {@link pandastic.studio.models.api.rig.PhysicsProfile} makes, and for the
 * same reason. {@link #strideLength()} is a real length in blocks, because the gait's whole correctness
 * condition is that the phase advance matches the distance travelled — get it wrong and the feet slide,
 * which no amount of tuning elsewhere hides. Everything else is a scale on a calibrated angle the solver
 * owns, so {@code 1} means "the calibrated look" and a rig file stays portable across solver revisions.
 *
 * <p>Scales reach {@link BindingBounds#maxScale()} rather than {@code 1} because exaggeration is a
 * legitimate style: a cartoon walk is a real walk with the swing amplitudes doubled. They are refused
 * beyond it because at that point the limb is passing through the body and the problem is not tuning.
 *
 * <h2>Two layers of validation, and why they are separate</h2>
 * The canonical constructor enforces only what is <strong>arithmetic</strong>: nothing is NaN or
 * infinite, the stride is positive because the phase advance divides by it, a scale is non-negative
 * because it is a magnitude, and a fraction is within {@code [0, 1]} because that is what the word means.
 * None of that is a preference and none of it is configurable.
 *
 * <p>The operator-facing ranges are checked by {@link #requireWithin(BindingBounds)} instead, because a
 * record cannot reach configuration from its own constructor and should not try. That split is the same
 * one the physics layer makes for the stability bound: a check needing context the value does not carry
 * lives in a named method the caller invokes where the context exists. {@link Builder#build()} does not
 * perform it — chain {@code requireWithin} on the result, which is what a rig loader does.
 *
 * @param strideLength   blocks of ground covered per full gait cycle — one step with each leg
 * @param legSwing       scale on the calibrated leg swing and foot reach, up to {@code maxScale}
 * @param armSwing       scale on the calibrated arm swing and elbow curl, up to {@code maxScale}
 * @param torsoSway      scale on every gait-driven torso motion — lean, bob, roll and twist — up to
 *                       {@code maxScale}
 * @param headStabilise  {@code 0..1} how much of the torso's motion the head cancels. {@code 0} welds
 *                       the head to the torso, {@code 1} holds it perfectly level
 * @param handStabilise  {@code 0..1} how much of the arm's rotation a bound hand cancels. {@code 0}
 *                       welds the hand to the forearm, {@code 1} holds it perfectly level. A fraction
 *                       rather than the foot's fixed counter-rotation because a hand has no ground to
 *                       stay parallel to: a held item wants a steady palm, a relaxed arm wants the hand
 *                       to follow the swing, and both are legitimate
 * @param sneakDepth     {@code 0..1} scale on the crouch pitch
 * @param breathDepth    scale on the idle breathing oscillation, up to {@code maxScale}. {@code 0} is a
 *                       statue
 */
public record GaitProfile(
        float strideLength,
        float legSwing,
        float armSwing,
        float torsoSway,
        float headStabilise,
        float handStabilise,
        float sneakDepth,
        float breathDepth
) {

    public GaitProfile {
        // The stride is a divisor, so a non-positive one is a division by zero or a reversed cycle,
        // neither of which any configuration can make meaningful.
        requirePositive(strideLength, "strideLength");
        requireNonNegative(legSwing, "legSwing");
        requireNonNegative(armSwing, "armSwing");
        requireNonNegative(torsoSway, "torsoSway");
        requireFraction(headStabilise, "headStabilise");
        requireFraction(handStabilise, "handStabilise");
        requireFraction(sneakDepth, "sneakDepth");
        requireNonNegative(breathDepth, "breathDepth");
    }

    private static void requirePositive(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireNonNegative(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(
                    name + " is a magnitude and must be finite and at least 0 but was " + value);
        }
    }

    private static void requireFraction(float value, @NotNull String name) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException(name + " is NaN");
        }
        if (value < 0F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " is a fraction and must be within [0, 1] but was " + value);
        }
    }

    /**
     * A builder seeded with the calibrated look: every scale at {@code 1}, and the three defaults
     * {@code tuning} carries.
     *
     * <p>The scales are {@code 1} rather than a configured value because {@code 1} is the multiplicative
     * identity — it means "whatever the calibration says", so the configured number it defers to is
     * {@code tuning}'s own. A configurable default scale would be a second place to express the same
     * thing, and the two would disagree.
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull Builder builder(@NotNull GaitTuning tuning) {
        return new Builder(tuning);
    }

    /**
     * The calibrated look at this tuning's default proportions — what the removed {@code DEFAULT}
     * constant used to be, now that both of its inputs arrive from configuration.
     *
     * <p>Needs no {@link BindingBounds} check: {@code tuning} has already validated that its default
     * stride is positive and that its default head and hand stabilisations are fractions, and the scales
     * are all the identity. A caller that wants the operator's ranges enforced as well can chain
     * {@link #requireWithin(BindingBounds)}.
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull GaitProfile defaults(@NotNull GaitTuning tuning) {
        return builder(tuning).build();
    }

    /**
     * Checks this profile against the operator's ranges and returns it, so it chains.
     *
     * @throws IllegalArgumentException if the stride is outside
     *                                 {@code [minStrideLength, maxStrideLength]} or any scale exceeds
     *                                 {@code maxScale}
     */
    @Contract(value = "_ -> this", pure = true)
    public @NotNull GaitProfile requireWithin(@NotNull BindingBounds bounds) {
        requireRange(this.strideLength, bounds.minStrideLength(), bounds.maxStrideLength(),
                "strideLength");
        requireRange(this.legSwing, 0F, bounds.maxScale(), "legSwing");
        requireRange(this.armSwing, 0F, bounds.maxScale(), "armSwing");
        requireRange(this.torsoSway, 0F, bounds.maxScale(), "torsoSway");
        requireRange(this.breathDepth, 0F, bounds.maxScale(), "breathDepth");
        return this;
    }

    private static void requireRange(float value, float min, float max, @NotNull String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be within [" + min + ", " + max + "] but was " + value);
        }
    }

    /** This profile with a different stride, which is the one value that depends on model scale. */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull GaitProfile withStrideLength(float strideLength) {
        return new GaitProfile(strideLength, this.legSwing, this.armSwing, this.torsoSway,
                this.headStabilise, this.handStabilise, this.sneakDepth, this.breathDepth);
    }

    /**
     * Mutable builder for a {@link GaitProfile}.
     *
     * <p>The five scales below are seeded with {@code 1} and that is <strong>not</strong> a hardcoded
     * tunable: {@code 1} is the multiplicative identity, so it means "whatever the calibration says" and
     * the configured number it defers to is {@link GaitTuning}'s own amplitude. A configurable default
     * scale would be a second place to express one thing: halving a hypothetical
     * {@code defaultLegSwing} and halving the existing {@code legSwingRadians} would be the same edit
     * made twice, and a rig file asking for {@code legSwing: 1} would no longer mean the calibrated look
     * it documents itself as meaning. The three values that are real defaults rather than identities —
     * the stride and the two stabilisations — do come from {@code tuning}.
     */
    public static final class Builder {

        /** The three real defaults, all supplied by {@code tuning} in the constructor below. */
        private float strideLength;
        private float headStabilise;
        private float handStabilise;

        /** Identity scales. See the class note: {@code 1} is "the calibrated look", not a figure. */
        private float legSwing = 1F;
        private float armSwing = 1F;
        private float torsoSway = 1F;
        private float sneakDepth = 1F;
        private float breathDepth = 1F;

        private Builder(@NotNull GaitTuning tuning) {
            this.strideLength = tuning.defaultStrideLength();
            this.headStabilise = tuning.defaultHeadStabilise();
            this.handStabilise = tuning.defaultHandStabilise();
        }

        public @NotNull Builder strideLength(float strideLength) {
            this.strideLength = strideLength;
            return this;
        }

        public @NotNull Builder legSwing(float legSwing) {
            this.legSwing = legSwing;
            return this;
        }

        public @NotNull Builder armSwing(float armSwing) {
            this.armSwing = armSwing;
            return this;
        }

        public @NotNull Builder torsoSway(float torsoSway) {
            this.torsoSway = torsoSway;
            return this;
        }

        public @NotNull Builder headStabilise(float headStabilise) {
            this.headStabilise = headStabilise;
            return this;
        }

        public @NotNull Builder handStabilise(float handStabilise) {
            this.handStabilise = handStabilise;
            return this;
        }

        public @NotNull Builder sneakDepth(float sneakDepth) {
            this.sneakDepth = sneakDepth;
            return this;
        }

        public @NotNull Builder breathDepth(float breathDepth) {
            this.breathDepth = breathDepth;
            return this;
        }

        /**
         * Builds the profile, enforcing only the arithmetic invariants.
         *
         * <p>Chain {@link GaitProfile#requireWithin(BindingBounds)} to enforce the operator's ranges as
         * well; this does not, because a builder has no way to reach them.
         */
        public @NotNull GaitProfile build() {
            return new GaitProfile(this.strideLength, this.legSwing, this.armSwing, this.torsoSway,
                    this.headStabilise, this.handStabilise, this.sneakDepth, this.breathDepth);
        }
    }
}
