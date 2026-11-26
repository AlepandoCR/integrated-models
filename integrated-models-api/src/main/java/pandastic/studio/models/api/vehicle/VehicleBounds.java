package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The ranges a {@link VehicleProfile} is accepted within: the operator's half of what a plugin may ask
 * for.
 *
 * <p>Every number here multiplies something the server pays for. A top speed is how far a vehicle can
 * travel in one tick, which is what decides whether it can be moved inside its own region at all; a turn
 * rate is how far the model it carries rotates between two pose emissions; a boost is both of those
 * again. None of them is wrong in the abstract and all of them are wrong at some value, and the person
 * who knows which is which is the operator rather than the plugin that wrote the profile.
 *
 * <p>Refused rather than clamped, like every other programmatic bound in this project: a plugin passing
 * a coefficient out of range has a bug, and a clamp would leave it driving a vehicle it did not describe.
 * A rig <em>file</em> clamps, because a file is written by a human.
 *
 * <h2>The terrain ceilings are nested rather than flattened</h2>
 * This record <em>carries</em> a {@link TerrainBounds} instead of repeating its six components, because
 * {@link TerrainBounds} is already the operator's complete statement about how far a vehicle may lean and
 * how long a climb may take, and a flattened copy of it would be a second place to keep the same six
 * ceilings in step. The nesting mirrors the profile side, where a {@link VehicleProfile} carries a
 * {@link TerrainResponse}: one bounds object is checked against one profile object, component for
 * component, and {@link VehicleProfile#requireWithin(VehicleBounds)} therefore validates a whole profile
 * in one call rather than leaving the terrain half to a second call a caller can forget.
 *
 * @param maxSpeedBlocksPerTick    fastest a profile may travel, in blocks per tick, before its boost.
 *                                 The useful ceiling is well under the local-move threshold, because a
 *                                 vehicle whose ordinary tick exceeds it pays a scheduled teleport per
 *                                 tick
 * @param maxAccelerationBlocksPerTickSquared largest change of speed a profile may ask for per tick,
 *                                 applying to both acceleration and braking. A figure at or above the
 *                                 speed ceiling means instant top speed, which is legal and is what a
 *                                 lift wants
 * @param maxTurnRateDegreesPerTick fastest a profile may rotate, in degrees per tick. Above roughly a
 *                                 quarter turn a tick the model's own interpolation cannot keep up and
 *                                 the visual lags the hitbox
 * @param maxBoostMultiplier       largest multiple of top speed the sprint key may unlock. At {@code 1}
 *                                 the boost key does nothing, which is the neutral value rather than a
 *                                 disabled one
 * @param terrain                  how far any vehicle on this server may lean, pitch and climb.
 *                                 {@link #NO_TERRAIN} for a server that permits no terrain reaction at
 *                                 all
 */
public record VehicleBounds(
        float maxSpeedBlocksPerTick,
        float maxAccelerationBlocksPerTickSquared,
        float maxTurnRateDegreesPerTick,
        float maxBoostMultiplier,
        @NotNull TerrainBounds terrain
) {

    /**
     * The terrain ceilings that admit exactly {@link TerrainResponse#NONE} and nothing else.
     *
     * <p>Every angle ceiling is zero, the step ceiling is zero, the climb may last the one tick a climb
     * at minimum lasts, and the blend floor is the snap that an inert response already is — so the one
     * response it accepts is the inert one, by construction rather than by coincidence.
     *
     * <p>The identity value of this half of the bounds, in the sense
     * {@link pandastic.studio.models.api.rig.AxisLimits#UNBOUNDED} is one, and the right ceiling for a
     * server whose configuration says nothing about terrain: refusing a lean nobody asked for is loud,
     * where silently permitting any lean would make the first terrain-bearing profile a surprise. It
     * lives here rather than on {@link TerrainBounds} because it exists for this record's
     * {@link #withoutTerrain} factory.
     */
    public static final @NotNull TerrainBounds NO_TERRAIN =
            new TerrainBounds(0F, 0F, 0F, 0F, 1, 1F);

    public VehicleBounds {
        Objects.requireNonNull(terrain, "terrain");
        requirePositive(maxSpeedBlocksPerTick, "maxSpeedBlocksPerTick");
        requirePositive(maxAccelerationBlocksPerTickSquared,
                "maxAccelerationBlocksPerTickSquared");
        requirePositive(maxTurnRateDegreesPerTick, "maxTurnRateDegreesPerTick");
        // One, not zero: a boost multiplier is a multiplier, so the identity is the floor. A ceiling
        // below it would refuse every profile including one that declares no boost at all.
        if (!Float.isFinite(maxBoostMultiplier) || maxBoostMultiplier < 1F) {
            throw new IllegalArgumentException(
                    "maxBoostMultiplier must be finite and at least 1, the neutral multiplier, but was "
                            + maxBoostMultiplier);
        }
    }

    /**
     * Bounds in the shape this record had before 1.1.0: the same four arguments, in the same order,
     * with {@link #NO_TERRAIN} for the terrain half.
     *
     * <p>For an operator's configuration that declares no terrain ceilings yet. The result admits every
     * profile that was legal before the terrain layer existed — all of them carry
     * {@link TerrainResponse#NONE} — and refuses, by name and with the ceiling it broke, the first
     * profile that asks for a lean the configuration has not permitted.
     *
     * @param maxSpeedBlocksPerTick fastest a profile may travel, in blocks per tick, before its boost
     * @param maxAccelerationBlocksPerTickSquared largest change of speed a profile may ask for per tick
     * @param maxTurnRateDegreesPerTick fastest a profile may rotate, in degrees per tick
     * @param maxBoostMultiplier largest multiple of top speed the sprint key may unlock
     * @return the bounds, permitting no terrain reaction
     * @throws IllegalArgumentException if any ceiling is malformed, exactly as before
     */
    @Contract(value = "_, _, _, _ -> new", pure = true)
    public static @NotNull VehicleBounds withoutTerrain(
            float maxSpeedBlocksPerTick,
            float maxAccelerationBlocksPerTickSquared,
            float maxTurnRateDegreesPerTick,
            float maxBoostMultiplier
    ) {
        return new VehicleBounds(
                maxSpeedBlocksPerTick,
                maxAccelerationBlocksPerTickSquared,
                maxTurnRateDegreesPerTick,
                maxBoostMultiplier,
                NO_TERRAIN);
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }
}
