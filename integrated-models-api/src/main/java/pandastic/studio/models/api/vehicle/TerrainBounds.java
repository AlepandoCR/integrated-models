package pandastic.studio.models.api.vehicle;

/**
 * The ranges a {@link TerrainResponse} is accepted within.
 *
 * <h2>Whose statement this is</h2>
 * The operator's, exactly like {@link VehicleBounds}. A response is a claim about <em>one vehicle</em> —
 * this tank is heavy and leans slowly — and belongs to the plugin that ships the vehicle. These bounds
 * are a claim about <em>the server</em>: how far any vehicle on it may lean before it looks broken, and
 * how long a climb may take before it reads as lag. A plugin cannot know the second, so it does not get
 * to decide it.
 *
 * @param maxBankDegrees             the furthest any vehicle may roll into a turn
 * @param maxSlopePitchDegrees       the furthest any vehicle may pitch to follow terrain
 * @param maxAccelerationPitchDegrees the furthest any vehicle may pitch under its own acceleration
 * @param maxStepHeightBlocks        the highest step any vehicle may climb, in blocks
 * @param maxStepDurationTicks       the longest a climb may be spread over, in ticks
 * @param minAngleBlendPerTick       the slowest any vehicle may approach a new angle
 */
public record TerrainBounds(
        float maxBankDegrees,
        float maxSlopePitchDegrees,
        float maxAccelerationPitchDegrees,
        float maxStepHeightBlocks,
        int maxStepDurationTicks,
        float minAngleBlendPerTick
) {

    public TerrainBounds {
        requirePositive(maxBankDegrees, "maxBankDegrees");
        requirePositive(maxSlopePitchDegrees, "maxSlopePitchDegrees");
        requirePositive(maxAccelerationPitchDegrees, "maxAccelerationPitchDegrees");
        requirePositive(maxStepHeightBlocks, "maxStepHeightBlocks");

        if (maxStepDurationTicks < 1) {
            throw new IllegalArgumentException(
                    "maxStepDurationTicks must be at least 1, but was " + maxStepDurationTicks);
        }
        if (!Float.isFinite(minAngleBlendPerTick)
                || minAngleBlendPerTick <= 0F || minAngleBlendPerTick > 1F) {
            throw new IllegalArgumentException(
                    "minAngleBlendPerTick must be in (0, 1] but was " + minAngleBlendPerTick);
        }
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(
                    name + " must be finite and not negative, but was " + value);
        }
    }
}
