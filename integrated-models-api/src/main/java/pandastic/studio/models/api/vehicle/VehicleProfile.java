package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How one vehicle drives: nine numbers and an enum, immutable and validated.
 *
 * <h2>Per vehicle, not per server</h2>
 * The same split {@link pandastic.studio.models.api.rig.PhysicsProfile} makes. A profile is a statement
 * about a <em>thing</em> — this cart is slow and turns tightly, that hovercraft is fast and slides — so
 * it belongs to the plugin that shipped the thing. What the operator owns is the
 * {@link VehicleBounds} every profile is checked against, because that is a statement about the server.
 *
 * <h2>Speeds are blocks per tick</h2>
 * The same unit {@link pandastic.studio.models.api.physics.PhysicsDriver} uses, and for the same reason:
 * a position delta between two consecutive ticks already is a blocks-per-tick figure, so a vehicle whose
 * speeds are expressed this way can be compared against the thresholds the rest of the pipeline already
 * carries without a conversion nobody would remember to keep in step. Accelerations are therefore blocks
 * per tick squared, and the turn rate is degrees per tick.
 *
 * <h2>Friction is a fraction, and it is per tick</h2>
 * {@code frictionPerTick} is the share of the current speed a coasting vehicle loses each tick, so
 * {@code 0} coasts forever and {@code 1} stops dead the moment the key is released. It is applied
 * geometrically over the elapsed tick count rather than linearly, which is what makes a step of two
 * ticks identical to two steps of one — a linear application would make a vehicle's stopping distance
 * depend on how often the control loop happened to run.
 *
 * @param maxForwardSpeedBlocksPerTick top speed ahead, before any boost
 * @param maxReverseSpeedBlocksPerTick top speed astern, as a positive figure. Usually well below the
 *                                     forward speed, because reversing fast is what makes a vehicle feel
 *                                     wrong rather than fast
 * @param maxStrafeSpeedBlocksPerTick  top speed sideways, as a positive figure. Only reachable under
 *                                     {@link ControlScheme#STRAFING}, but declared regardless so a
 *                                     profile can be switched between schemes without being rebuilt
 * @param accelerationBlocksPerTickSquared how fast speed rises toward what the rider asked for
 * @param brakingBlocksPerTickSquared  how fast speed falls when the rider asks for the other direction.
 *                                     Separate from the acceleration because a vehicle that brakes as
 *                                     slowly as it accelerates is a vehicle that cannot be parked
 * @param frictionPerTick              share of speed lost per tick while coasting, {@code 0} to {@code 1}
 * @param turnRateDegreesPerTick       how fast the vehicle yaws at full turn input
 * @param boostMultiplier              multiple of the forward top speed the sprint key unlocks; {@code 1}
 *                                     for a vehicle with no boost
 * @param controlScheme                what the rider's left and right keys do
 */
public record VehicleProfile(
        float maxForwardSpeedBlocksPerTick,
        float maxReverseSpeedBlocksPerTick,
        float maxStrafeSpeedBlocksPerTick,
        float accelerationBlocksPerTickSquared,
        float brakingBlocksPerTickSquared,
        float frictionPerTick,
        float turnRateDegreesPerTick,
        float boostMultiplier,
        @NotNull ControlScheme controlScheme
) {

    public VehicleProfile {
        Objects.requireNonNull(controlScheme, "controlScheme");
        requirePositive(maxForwardSpeedBlocksPerTick, "maxForwardSpeedBlocksPerTick");
        // Zero is legal for these two and means "this vehicle does not do that at all": a one-way
        // conveyor cannot reverse, and a turning vehicle never strafes.
        requireNonNegative(maxReverseSpeedBlocksPerTick, "maxReverseSpeedBlocksPerTick");
        requireNonNegative(maxStrafeSpeedBlocksPerTick, "maxStrafeSpeedBlocksPerTick");
        requirePositive(accelerationBlocksPerTickSquared, "accelerationBlocksPerTickSquared");
        requirePositive(brakingBlocksPerTickSquared, "brakingBlocksPerTickSquared");
        requireFraction(frictionPerTick, "frictionPerTick");
        // Zero is legal and means a vehicle that cannot be steered, which is what a rail-bound one is.
        requireNonNegative(turnRateDegreesPerTick, "turnRateDegreesPerTick");
        if (!Float.isFinite(boostMultiplier) || boostMultiplier < 1F) {
            throw new IllegalArgumentException(
                    "boostMultiplier must be finite and at least 1, the neutral multiplier, but was "
                            + boostMultiplier);
        }
    }

    /**
     * A builder seeded with {@code bounds}, which {@link Builder#build()} validates against.
     *
     * <p>A builder rather than a nine-argument constructor for the reason
     * {@link pandastic.studio.models.api.locomotion.GaitTuning} has one: a row of same-typed floats
     * accepts two transposed arguments without complaint, and the result is a vehicle that drives
     * slightly wrong in a way no exception points at.
     *
     * <p>The bounds are supplied here rather than at {@code build()} so that a caller cannot assemble a
     * profile and then forget to check it. Everything the builder does not set keeps a neutral value: no
     * reverse, no strafe, no boost, no friction, and {@link ControlScheme#TURNING}, which is what a
     * vanilla mount does.
     *
     * @param bounds the operator's ranges
     * @return a builder
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull Builder builder(@NotNull VehicleBounds bounds) {
        return new Builder(bounds);
    }

    /**
     * This profile, or a complaint naming the component that is out of range.
     *
     * <p>Separate from the constructor because the constructor enforces what is <em>arithmetically</em>
     * required — finite, non-negative, a fraction where a fraction is meant — while this enforces what
     * the operator permits. A profile can be perfectly well formed and still be refused by a server that
     * does not want a vehicle that fast.
     *
     * @param bounds the operator's ranges
     * @return this profile
     * @throws IllegalArgumentException if any component exceeds its bound, naming both
     */
    @Contract(value = "_ -> this", pure = true)
    public @NotNull VehicleProfile requireWithin(@NotNull VehicleBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        requireAtMost(this.maxForwardSpeedBlocksPerTick, "maxForwardSpeedBlocksPerTick",
                bounds.maxSpeedBlocksPerTick(), "maxSpeedBlocksPerTick");
        requireAtMost(this.maxReverseSpeedBlocksPerTick, "maxReverseSpeedBlocksPerTick",
                bounds.maxSpeedBlocksPerTick(), "maxSpeedBlocksPerTick");
        requireAtMost(this.maxStrafeSpeedBlocksPerTick, "maxStrafeSpeedBlocksPerTick",
                bounds.maxSpeedBlocksPerTick(), "maxSpeedBlocksPerTick");
        requireAtMost(this.accelerationBlocksPerTickSquared, "accelerationBlocksPerTickSquared",
                bounds.maxAccelerationBlocksPerTickSquared(), "maxAccelerationBlocksPerTickSquared");
        requireAtMost(this.brakingBlocksPerTickSquared, "brakingBlocksPerTickSquared",
                bounds.maxAccelerationBlocksPerTickSquared(), "maxAccelerationBlocksPerTickSquared");
        requireAtMost(this.turnRateDegreesPerTick, "turnRateDegreesPerTick",
                bounds.maxTurnRateDegreesPerTick(), "maxTurnRateDegreesPerTick");
        requireAtMost(this.boostMultiplier, "boostMultiplier",
                bounds.maxBoostMultiplier(), "maxBoostMultiplier");
        return this;
    }

    /**
     * The top forward speed this profile reaches with the boost applied.
     *
     * <p>On the profile rather than computed at each use, because it is the figure that has to be
     * compared against the server's own movement thresholds: a vehicle whose <em>boosted</em> top speed
     * crosses the local-move threshold teleports under boost and not otherwise, which is a bug report
     * about one key rather than about speed.
     *
     * @return blocks per tick
     */
    @Contract(pure = true)
    public float boostedForwardSpeedBlocksPerTick() {
        return this.maxForwardSpeedBlocksPerTick * this.boostMultiplier;
    }

    /** Fluent assembler for a {@link VehicleProfile}; see {@link VehicleProfile#builder}. */
    public static final class Builder {

        private final @NotNull VehicleBounds bounds;

        private float maxForwardSpeedBlocksPerTick;
        private float maxReverseSpeedBlocksPerTick;
        private float maxStrafeSpeedBlocksPerTick;
        private float accelerationBlocksPerTickSquared;
        private float brakingBlocksPerTickSquared;
        private float frictionPerTick;
        private float turnRateDegreesPerTick;

        /** The neutral multiplier: a profile that says nothing about boost does not boost. */
        private float boostMultiplier = 1F;

        private @NotNull ControlScheme controlScheme = ControlScheme.TURNING;

        private Builder(@NotNull VehicleBounds bounds) {
            this.bounds = Objects.requireNonNull(bounds, "bounds");
        }

        /** Top speed ahead, in blocks per tick, before any boost. */
        public @NotNull Builder forwardSpeed(float blocksPerTick) {
            this.maxForwardSpeedBlocksPerTick = blocksPerTick;
            return this;
        }

        /** Top speed astern, in blocks per tick, as a positive figure. */
        public @NotNull Builder reverseSpeed(float blocksPerTick) {
            this.maxReverseSpeedBlocksPerTick = blocksPerTick;
            return this;
        }

        /** Top speed sideways, in blocks per tick, as a positive figure. */
        public @NotNull Builder strafeSpeed(float blocksPerTick) {
            this.maxStrafeSpeedBlocksPerTick = blocksPerTick;
            return this;
        }

        /** How fast speed rises toward what the rider asked for, in blocks per tick squared. */
        public @NotNull Builder acceleration(float blocksPerTickSquared) {
            this.accelerationBlocksPerTickSquared = blocksPerTickSquared;
            return this;
        }

        /** How fast speed falls when the rider asks for the other direction. */
        public @NotNull Builder braking(float blocksPerTickSquared) {
            this.brakingBlocksPerTickSquared = blocksPerTickSquared;
            return this;
        }

        /** Share of speed lost per tick while coasting, {@code 0} to {@code 1}. */
        public @NotNull Builder friction(float perTick) {
            this.frictionPerTick = perTick;
            return this;
        }

        /** How fast the vehicle yaws at full turn input, in degrees per tick. */
        public @NotNull Builder turnRate(float degreesPerTick) {
            this.turnRateDegreesPerTick = degreesPerTick;
            return this;
        }

        /** Multiple of the forward top speed the sprint key unlocks. */
        public @NotNull Builder boost(float multiplier) {
            this.boostMultiplier = multiplier;
            return this;
        }

        /** What the rider's left and right keys do. */
        public @NotNull Builder controls(@NotNull ControlScheme scheme) {
            this.controlScheme = Objects.requireNonNull(scheme, "scheme");
            return this;
        }

        /**
         * The profile, checked against the bounds this builder was given.
         *
         * @return the profile
         * @throws IllegalArgumentException if the profile is malformed or outside the bounds
         */
        @Contract(value = "-> new", pure = true)
        public @NotNull VehicleProfile build() {
            return new VehicleProfile(
                    this.maxForwardSpeedBlocksPerTick,
                    this.maxReverseSpeedBlocksPerTick,
                    this.maxStrafeSpeedBlocksPerTick,
                    this.accelerationBlocksPerTickSquared,
                    this.brakingBlocksPerTickSquared,
                    this.frictionPerTick,
                    this.turnRateDegreesPerTick,
                    this.boostMultiplier,
                    this.controlScheme)
                    .requireWithin(this.bounds);
        }
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(
                    name + " must be finite and not negative but was " + value);
        }
    }

    private static void requireFraction(float value, String name) {
        if (!Float.isFinite(value) || value < 0F || value > 1F) {
            throw new IllegalArgumentException(
                    name + " must be a finite fraction between 0 and 1 but was " + value);
        }
    }

    private static void requireAtMost(float value, String name, float bound, String boundName) {
        if (value > bound) {
            throw new IllegalArgumentException(name + " must not exceed " + boundName + ", which is "
                    + bound + ", but is " + value);
        }
    }
}
