package pandastic.studio.models.api.vehicle;

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
 */
public record VehicleBounds(
        float maxSpeedBlocksPerTick,
        float maxAccelerationBlocksPerTickSquared,
        float maxTurnRateDegreesPerTick,
        float maxBoostMultiplier
) {

    public VehicleBounds {
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

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }
}
