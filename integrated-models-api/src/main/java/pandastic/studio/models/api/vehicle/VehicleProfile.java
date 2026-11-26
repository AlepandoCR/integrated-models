package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How one vehicle drives: eight numbers, two enums and a terrain response, immutable and validated.
 *
 * <h2>Why the kind and the terrain response live here and not beside this type</h2>
 * Because "how this vehicle drives" is exactly this type's contract, and {@link VehicleKind} and
 * {@link TerrainResponse} are statements about precisely that. Split across two types they would be two
 * things a caller can pass separately, which means two things it can pass <em>inconsistently</em> — a
 * boat's terrain response handed to a flyer's kind — and the combination is the thing worth validating.
 * One record, one constructor, one {@link #requireWithin(VehicleBounds)} call, and the contradiction is
 * reported at the point the vehicle is described rather than discovered on the first hill.
 *
 * <h2>Error or ignored: which contradictions are refused</h2>
 * {@link VehicleKind} promises that a term its kind does not honour is "ignored rather than rejected", so
 * that one {@link TerrainResponse} can be shared across kinds without a caller having to blank the fields
 * that do not apply. This record keeps that promise, and the rule it follows is:
 *
 * <ul>
 *   <li><strong>Ignored</strong> — a term the kind does not honour. It is unused, not broken:
 *       {@link TerrainResponse#stepHeightBlocks()} on anything but {@link VehicleKind#GROUND},
 *       {@link TerrainResponse#slopePitchDegrees()} on a kind that does not
 *       {@link VehicleKind#followsSlope()}, and {@link TerrainResponse#accelerationPitchDegrees()} on
 *       one that does not {@link VehicleKind#pitchesFromAcceleration()}. Refusing these would make
 *       sharing a response impossible and would buy nothing: the vehicle drives correctly, it simply
 *       does not use that number.</li>
 *   <li><strong>Refused</strong> — a term that <em>is</em> honoured and that nothing can drive. Every
 *       angle in a response is a ceiling scaled by the fraction of a full-rate input achieved, so a term
 *       whose full rate is zero is a zero-over-zero: see {@link #requireBankIsDrivable}. That is not an
 *       unused number, it is an undefined one, and an undefined roll is a model that renders wrong rather
 *       than a model that renders flat.</li>
 * </ul>
 *
 * <p>The three remaining scalings cannot fall into that trap and so need no rule: the acceleration pitch
 * divides by {@link #accelerationBlocksPerTickSquared()} and {@link #brakingBlocksPerTickSquared()},
 * which this constructor already requires to be positive, and the slope pitch is scaled by the terrain
 * rather than by any term of this profile.
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
 * @param kind                         what medium the vehicle moves through, and therefore which terms
 *                                     of {@code terrain} are honoured
 * @param terrain                      how far the model leans, pitches and climbs in answer to what it
 *                                     is driving over. {@link TerrainResponse#NONE} for a vehicle that
 *                                     does not react to terrain at all
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
        @NotNull ControlScheme controlScheme,
        @NotNull VehicleKind kind,
        @NotNull TerrainResponse terrain
) {

    public VehicleProfile {
        Objects.requireNonNull(controlScheme, "controlScheme");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(terrain, "terrain");
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
        requireBankIsDrivable(terrain, controlScheme, turnRateDegreesPerTick,
                maxStrafeSpeedBlocksPerTick);
    }

    /**
     * A profile in the shape this record had before 1.1.0: the same nine arguments, in the same order.
     *
     * <p>Supplies {@link VehicleKind#GROUND} and {@link TerrainResponse#NONE}, which together are
     * exactly what a profile written before those two existed described — a vehicle on solid ground that
     * does not lean, does not pitch and does not climb. So a consumer that moves to 1.1.0 and changes
     * {@code new VehicleProfile(...)} to this call keeps the behaviour it already had, bit for bit, and
     * the compiler points at every site it has to look at.
     *
     * <p>{@link TerrainResponse#NONE} is a constant rather than a fresh record, so every profile built
     * this way shares one identity-stable response; a consumer comparing responses by identity across
     * two profiles gets the answer it expects.
     *
     * @param maxForwardSpeedBlocksPerTick top speed ahead, before any boost
     * @param maxReverseSpeedBlocksPerTick top speed astern, as a positive figure
     * @param maxStrafeSpeedBlocksPerTick  top speed sideways, as a positive figure
     * @param accelerationBlocksPerTickSquared how fast speed rises toward what the rider asked for
     * @param brakingBlocksPerTickSquared  how fast speed falls when the rider asks for the other way
     * @param frictionPerTick              share of speed lost per tick while coasting, {@code 0} to 1
     * @param turnRateDegreesPerTick       how fast the vehicle yaws at full turn input
     * @param boostMultiplier              multiple of the forward top speed the sprint key unlocks
     * @param controlScheme                what the rider's left and right keys do
     * @return the profile, on the ground and inert to terrain
     * @throws IllegalArgumentException if any component is malformed, exactly as before
     */
    @Contract(value = "_, _, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull VehicleProfile withoutTerrain(
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
        return new VehicleProfile(
                maxForwardSpeedBlocksPerTick,
                maxReverseSpeedBlocksPerTick,
                maxStrafeSpeedBlocksPerTick,
                accelerationBlocksPerTickSquared,
                brakingBlocksPerTickSquared,
                frictionPerTick,
                turnRateDegreesPerTick,
                boostMultiplier,
                controlScheme,
                VehicleKind.GROUND,
                TerrainResponse.NONE);
    }

    /**
     * A builder seeded with {@code bounds}, which {@link Builder#build()} validates against.
     *
     * <p>A builder rather than an eleven-argument constructor for the reason
     * {@link pandastic.studio.models.api.locomotion.GaitTuning} has one: a row of same-typed floats
     * accepts two transposed arguments without complaint, and the result is a vehicle that drives
     * slightly wrong in a way no exception points at.
     *
     * <p>The bounds are supplied here rather than at {@code build()} so that a caller cannot assemble a
     * profile and then forget to check it. Everything the builder does not set keeps a neutral value: no
     * reverse, no strafe, no boost, no friction, {@link ControlScheme#TURNING},
     * {@link VehicleKind#GROUND} and {@link TerrainResponse#NONE} — which together are what a vanilla
     * mount does.
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
     * <p>Checks the {@link #terrain()} response as well, against {@link VehicleBounds#terrain()}, so one
     * call validates the whole profile. A caller that had to remember a second call for the terrain
     * half would eventually not, and the half it forgot is the half that moves the model.
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
        // Last, so that a profile breaking both a speed ceiling and an angle ceiling complains about the
        // speed first: the speed is what decides whether the server can move the vehicle at all, and the
        // angle is cosmetic on top of it.
        this.terrain.requireWithin(bounds.terrain());
        return this;
    }

    /**
     * Whether this vehicle climbs steps, which needs both a kind that climbs and a height to climb.
     *
     * <p>Here rather than on either half because it is the one question that needs both, and a caller
     * asking it with the two apart is a caller that can ask it of a mismatched pair.
     *
     * @return whether a step up is something this profile does
     */
    @Contract(pure = true)
    public boolean climbs() {
        return this.terrain.climbs(this.kind);
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

        /** What a vanilla mount is, and what every profile written before the kind existed described. */
        private @NotNull VehicleKind kind = VehicleKind.GROUND;

        /** The identity: a vehicle that says nothing about terrain does not react to it. */
        private @NotNull TerrainResponse terrain = TerrainResponse.NONE;

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

        /** What medium the vehicle moves through, which selects the terrain terms that are honoured. */
        public @NotNull Builder kind(@NotNull VehicleKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        /**
         * How far the model leans, pitches and climbs in answer to the terrain.
         *
         * <p>The response may be shared with other profiles, including profiles of another
         * {@link VehicleKind}: it is immutable, and terms the kind does not honour are ignored.
         */
        public @NotNull Builder terrain(@NotNull TerrainResponse terrain) {
            this.terrain = Objects.requireNonNull(terrain, "terrain");
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
                    this.controlScheme,
                    this.kind,
                    this.terrain)
                    .requireWithin(this.bounds);
        }
    }

    /**
     * Refuses a bank angle that nothing in this profile can drive.
     *
     * <p>{@link TerrainResponse#bankDegrees()} is a <em>ceiling</em> reached at a full-rate turn, and the
     * solver scales it by the fraction of that full rate the vehicle is currently achieving. Which
     * input the fraction is taken from is the {@link ControlScheme}'s to say: a turning vehicle leans
     * because it is yawing, so the denominator is {@code turnRateDegreesPerTick}; a strafing vehicle
     * never yaws, so it leans because it is sliding, and the denominator is
     * {@code maxStrafeSpeedBlocksPerTick}.
     *
     * <p>A zero denominator is therefore not a vehicle that leans less — it is a fraction of the form
     * {@code 0 / 0}. Left to the solver that is a NaN roll, and a NaN rotation is a model the renderer
     * draws wrong rather than a model it draws level, which is why this one is refused where the three
     * kind-applicability contradictions are ignored. A zero bank needs no driver and is always legal, so
     * {@link TerrainResponse#NONE} passes under either scheme and no profile written before the terrain
     * layer existed can be refused by this check.
     */
    private static void requireBankIsDrivable(
            @NotNull TerrainResponse terrain,
            @NotNull ControlScheme controlScheme,
            float turnRateDegreesPerTick,
            float maxStrafeSpeedBlocksPerTick
    ) {
        if (terrain.bankDegrees() <= 0F) {
            return;
        }
        final boolean turning = controlScheme == ControlScheme.TURNING;
        final float driver = turning ? turnRateDegreesPerTick : maxStrafeSpeedBlocksPerTick;
        if (driver > 0F) {
            return;
        }
        final String driverName =
                turning ? "turnRateDegreesPerTick" : "maxStrafeSpeedBlocksPerTick";
        throw new IllegalArgumentException("bankDegrees is " + terrain.bankDegrees()
                + ", which is the lean at a full-rate input, but " + driverName + " is "
                + driver + " under ControlScheme." + controlScheme
                + ", so the fraction of full rate the lean is scaled by is undefined; either give the"
                + " vehicle something to lean from or set bankDegrees to 0");
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
