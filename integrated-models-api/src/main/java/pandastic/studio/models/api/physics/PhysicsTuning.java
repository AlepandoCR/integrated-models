package pandastic.studio.models.api.physics;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The calibration a {@link PhysicsSolver} runs on: the coefficients that turn a
 * {@link pandastic.studio.models.api.rig.PhysicsProfile}'s dimensionless authoring values into angular
 * rates, and the fixed step the integrator takes.
 *
 * <h2>Why this is a value and not a constant block</h2>
 * A rig file carries {@code stiffness: 0.3}; the solver needs rad·s⁻². The conversion between them is a
 * calibration, and a calibration is the single thing in this library an operator is most likely to have
 * to move: it depends on model scale, on how the renderer interprets bone rotations, and on taste. Baked
 * into the solver as constants it is unreachable without a rebuild, and every rig on the server is
 * silently calibrated against whatever the last build happened to contain.
 *
 * <p>Holding it as an immutable value instead means a reload swaps the whole calibration atomically —
 * no rig ever sees half of one calibration and half of another, which would read as some bones
 * stiffening before others.
 *
 * <h2>The step rate is a stability requirement, not a smoothness one</h2>
 * Semi-implicit Euler survives only while {@code ω·h < }{@link #STABILITY_LIMIT}, and {@code ω} rises as
 * mass falls. {@link #substepRateHz()} is therefore constrained from below by the stiffest tuning the
 * bounds permit — see {@link #maxStableAngularFrequency()} — and is unrelated to how smooth the result
 * looks, which comes from the client interpolating between the transforms it receives.
 *
 * <p>Raising it buys nothing visual and costs CPU linearly; lowering it below what the permitted
 * stiffness needs produces NaN rotations, which delete the model on the client. Both failure modes are
 * worth a glance at {@link #maxStableAngularFrequency()} before touching the number.
 *
 * @param stiffnessToRate     author-facing stiffness {@code 0..1} to rad·s⁻²·rad⁻¹
 * @param dampingToRate       author-facing damping {@code 0..1} to rad·s⁻¹. Note the derived constraint
 *                            {@code damping · dampingToRate · substepSeconds() < mass}: the velocity
 *                            update is explicit, so past that the velocity flips sign and grows
 * @param gravityToRate       author-facing gravity to the pendulum's {@code g/L}, applied outside the
 *                            mass division
 * @param windToRadians       blocks per tick of owner movement to drag force
 * @param turnToSpeed         effective lever arm, in blocks, turning body yaw rate into tangential speed
 * @param substepRateHz       fixed internal step rate of the integrator, in hertz
 * @param maxSubsteps         most substeps one {@code solve} call will run, bounding the cost of a stall
 * @param restEpsilonRadians  per-substep angular change below which a bone counts as having stopped.
 *                            This is the bandwidth lever: too small and nothing ever reaches rest, so
 *                            the emission layer never stops sending
 */
public record PhysicsTuning(
        float stiffnessToRate,
        float dampingToRate,
        float gravityToRate,
        float windToRadians,
        float turnToSpeed,
        int substepRateHz,
        int maxSubsteps,
        float restEpsilonRadians
) {

    /**
     * Largest {@code ω·h} semi-implicit Euler survives.
     *
     * <p><strong>Deliberately not configurable.</strong> It is a property of the integrator rather than
     * a preference: the method's amplification factor leaves the unit disc at exactly {@code ω·h = 2},
     * so beyond it the spring gains energy every step and diverges. Exposing it as a setting would be
     * exposing a switch labelled "permit NaN", and a NaN bone rotation deletes the model on the client
     * rather than looking wrong. Changing the integrator is what changes this number.
     */
    public static final float STABILITY_LIMIT = 2F;

    public PhysicsTuning {
        // A conversion coefficient of zero silently deletes the term it scales, which presents as "the
        // setting does nothing" rather than as an error. Negative inverts it, which is worse.
        requirePositive(stiffnessToRate, "stiffnessToRate");
        requirePositive(dampingToRate, "dampingToRate");
        // Zero gravity is a legitimate world; negative would hang a pendulum upward, which is not a
        // style but a contradiction of its rest pose.
        requireNonNegative(gravityToRate, "gravityToRate");
        requireNonNegative(windToRadians, "windToRadians");
        requireNonNegative(turnToSpeed, "turnToSpeed");
        requireAtLeast(substepRateHz, 1, "substepRateHz");
        requireAtLeast(maxSubsteps, 1, "maxSubsteps");
        requirePositive(restEpsilonRadians, "restEpsilonRadians");
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

    private static void requireAtLeast(int value, int floor, @NotNull String name) {
        if (value < floor) {
            throw new IllegalArgumentException(name + " must be at least " + floor + " but was " + value);
        }
    }

    /** A builder in which every coefficient must be supplied; there are no defaults to fall back on. */
    @Contract(value = "-> new", pure = true)
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** The integrator's step, in seconds. Derived from {@link #substepRateHz()}, never configured separately. */
    public float substepSeconds() {
        return 1F / this.substepRateHz;
    }

    /**
     * The stiffest spring this step size can integrate without diverging, in rad·s⁻¹.
     *
     * <p>{@code STABILITY_LIMIT / substepSeconds()}. A solver compares a bone's {@code ω} against this
     * at rig compile time so a divergent tuning is refused by the author who can fix it, rather than
     * producing NaN on a live server.
     */
    public float maxStableAngularFrequency() {
        return STABILITY_LIMIT * this.substepRateHz;
    }

    /**
     * Mutable builder for a {@link PhysicsTuning}.
     *
     * <p>Exists because eight same-typed numeric coefficients in a row are read positionally exactly
     * once, correctly, and then transposed by whoever edits the call next. It carries <strong>no
     * defaults</strong> on purpose: a default here would be the hardcoded calibration this type exists
     * to remove, and a builder that silently substituted zero would hide a missing configuration key
     * behind a bone that simply never moves.
     */
    public static final class Builder {

        private final @NotNull Set<String> unset = new LinkedHashSet<>(List.of(
                "stiffnessToRate", "dampingToRate", "gravityToRate", "windToRadians", "turnToSpeed",
                "substepRateHz", "maxSubsteps", "restEpsilonRadians"));

        private float stiffnessToRate;
        private float dampingToRate;
        private float gravityToRate;
        private float windToRadians;
        private float turnToSpeed;
        private int substepRateHz;
        private int maxSubsteps;
        private float restEpsilonRadians;

        private Builder() {
        }

        public @NotNull Builder stiffnessToRate(float stiffnessToRate) {
            this.stiffnessToRate = stiffnessToRate;
            this.unset.remove("stiffnessToRate");
            return this;
        }

        public @NotNull Builder dampingToRate(float dampingToRate) {
            this.dampingToRate = dampingToRate;
            this.unset.remove("dampingToRate");
            return this;
        }

        public @NotNull Builder gravityToRate(float gravityToRate) {
            this.gravityToRate = gravityToRate;
            this.unset.remove("gravityToRate");
            return this;
        }

        public @NotNull Builder windToRadians(float windToRadians) {
            this.windToRadians = windToRadians;
            this.unset.remove("windToRadians");
            return this;
        }

        public @NotNull Builder turnToSpeed(float turnToSpeed) {
            this.turnToSpeed = turnToSpeed;
            this.unset.remove("turnToSpeed");
            return this;
        }

        public @NotNull Builder substepRateHz(int substepRateHz) {
            this.substepRateHz = substepRateHz;
            this.unset.remove("substepRateHz");
            return this;
        }

        public @NotNull Builder maxSubsteps(int maxSubsteps) {
            this.maxSubsteps = maxSubsteps;
            this.unset.remove("maxSubsteps");
            return this;
        }

        public @NotNull Builder restEpsilonRadians(float restEpsilonRadians) {
            this.restEpsilonRadians = restEpsilonRadians;
            this.unset.remove("restEpsilonRadians");
            return this;
        }

        /**
         * Validates and freezes the calibration.
         *
         * @throws IllegalStateException    if any coefficient was never supplied
         * @throws IllegalArgumentException if one of them is outside the range its term can survive
         */
        public @NotNull PhysicsTuning build() {
            if (!this.unset.isEmpty()) {
                throw new IllegalStateException(
                        "PhysicsTuning has no defaults — every coefficient must come from configuration."
                                + " Never supplied: " + this.unset);
            }
            return new PhysicsTuning(
                    this.stiffnessToRate, this.dampingToRate, this.gravityToRate, this.windToRadians,
                    this.turnToSpeed, this.substepRateHz, this.maxSubsteps, this.restEpsilonRadians);
        }
    }
}
