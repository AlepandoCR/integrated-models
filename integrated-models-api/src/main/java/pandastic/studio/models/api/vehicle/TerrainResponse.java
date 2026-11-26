package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How much a vehicle's model leans, pitches and rises in answer to what it is driving over.
 *
 * <h2>Rotation only, plus one vertical offset</h2>
 * Everything here except {@link #stepHeightBlocks()} is an <strong>angle</strong>, and that is the same
 * invariant the rig layer holds: the renderer parents a bone to its pivot, so a model that is translated
 * detaches from itself. The step is the single exception and it is applied to the vehicle's
 * <em>position</em>, not to a bone — it moves the whole thing up, which is what climbing a block is.
 *
 * <h2>Why the step has a duration and not just a height</h2>
 * This is the part that decides whether climbing a block looks like a vehicle or like a teleport, and it
 * is a protocol fact rather than a preference. A display entity interpolates toward the transform it was
 * last given, over the window it was given. Raising the vehicle a whole block in one tick gives the
 * client one frame to cover that block, so it snaps however smooth the maths behind it was. Spreading
 * the same block over {@link #stepDurationTicks()} ticks hands the client a window it can draw across,
 * and the climb is then as smooth as the viewer's frame rate rather than as the server's tick rate.
 *
 * <p>So a step height with no duration is not "instant and smooth", it is "instant"; the duration is
 * what buys the smoothness, and it costs latency on the climb in exchange. Three to four ticks reads as
 * a suspension taking the step; one tick reads as the vehicle flickering upward.
 *
 * <h2>Every angle is a ceiling, not a constant</h2>
 * {@link #bankDegrees()} is how far the model may roll at a <em>full-rate</em> turn, and the solver
 * scales it by how hard the vehicle is actually turning. A tight turn therefore leans further than a
 * gentle one with nothing extra to configure, which is what makes one number enough. The same reading
 * applies to {@link #slopePitchDegrees()} and {@link #accelerationPitchDegrees()}: each is the angle at
 * the extreme of its own input.
 *
 * <h2>The blend is separate from the angles</h2>
 * {@link #angleBlendPerTick()} is the fraction of the remaining difference the model closes each tick,
 * and it is what stops a vehicle snapping to a new lean the instant the terrain changes. It belongs here
 * rather than being derived from the angles because it is a property of the vehicle's <em>mass</em>, not
 * of how far it leans: a laden truck and a motorbike can bank to the same angle and have no business
 * getting there at the same rate.
 *
 * @param bankDegrees             how far the model rolls into a full-rate turn, in degrees. {@code 0}
 *                                disables banking
 * @param slopePitchDegrees       how far the model pitches to follow the steepest slope it will take, in
 *                                degrees. Honoured only when {@link VehicleKind#followsSlope()}
 * @param accelerationPitchDegrees how far the model pitches under full acceleration — stern-down for a
 *                                boat, nose-down for a diving submarine. Honoured only when
 *                                {@link VehicleKind#pitchesFromAcceleration()}
 * @param stepHeightBlocks        how high a step the vehicle may climb, in blocks. Honoured only when
 *                                {@link VehicleKind#climbsSteps()}; {@code 0} disables climbing
 * @param stepDurationTicks       how many ticks a climb is spread over. See the note above: this is
 *                                what makes the climb smooth, and {@code 1} means it is not
 * @param angleBlendPerTick       fraction of the remaining angle difference closed each tick, {@code 0}
 *                                exclusive to {@code 1} inclusive. {@code 1} snaps
 */
public record TerrainResponse(
        float bankDegrees,
        float slopePitchDegrees,
        float accelerationPitchDegrees,
        float stepHeightBlocks,
        int stepDurationTicks,
        float angleBlendPerTick
) {

    /**
     * A vehicle that does not react to terrain at all: no lean, no pitch, no climb, and a blend that
     * snaps because there is nothing to blend.
     *
     * <p>The identity value, and the right default for a profile written before this type existed —
     * which is exactly what {@link VehicleProfile}'s shorter factory supplies, so an existing consumer
     * keeps the behaviour it already had.
     */
    public static final @NotNull TerrainResponse NONE =
            new TerrainResponse(0F, 0F, 0F, 0F, 1, 1F);

    public TerrainResponse {
        requireAngle(bankDegrees, "bankDegrees");
        requireAngle(slopePitchDegrees, "slopePitchDegrees");
        requireAngle(accelerationPitchDegrees, "accelerationPitchDegrees");

        if (!Float.isFinite(stepHeightBlocks) || stepHeightBlocks < 0F) {
            throw new IllegalArgumentException(
                    "stepHeightBlocks must be finite and not negative, but was " + stepHeightBlocks);
        }
        if (stepDurationTicks < 1) {
            throw new IllegalArgumentException(
                    "stepDurationTicks must be at least 1, but was " + stepDurationTicks
                            + "; a step takes at least the tick it is applied in");
        }
        if (!Float.isFinite(angleBlendPerTick) || angleBlendPerTick <= 0F || angleBlendPerTick > 1F) {
            throw new IllegalArgumentException(
                    "angleBlendPerTick must be in (0, 1] but was " + angleBlendPerTick
                            + "; zero would mean the model never reaches the angle it was asked for");
        }
    }

    /** A response that only banks into its turns, which is the whole of it for a flyer. */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull TerrainResponse banking(float bankDegrees, float angleBlendPerTick) {
        return new TerrainResponse(bankDegrees, 0F, 0F, 0F, 1, angleBlendPerTick);
    }

    /** Whether any term of this response can move the model. */
    public boolean isInert() {
        return this.bankDegrees == 0F
                && this.slopePitchDegrees == 0F
                && this.accelerationPitchDegrees == 0F
                && this.stepHeightBlocks == 0F;
    }

    /** Whether this response can climb, which needs both a height and a kind that climbs. */
    public boolean climbs(@NotNull VehicleKind kind) {
        return kind.climbsSteps() && this.stepHeightBlocks > 0F;
    }

    /**
     * Checks every term against the operator's ranges.
     *
     * <p>Throws rather than clamping, because a programmatic caller passing an out-of-range angle has a
     * bug. A <em>file</em> is written by a human and is clamped with a diagnostic instead — the same
     * split the rig layer makes, and for the same reason.
     *
     * @param bounds the ranges this server accepts
     * @throws IllegalArgumentException naming the term and the bound it broke
     */
    public void requireWithin(@NotNull TerrainBounds bounds) {
        requireAtMost(this.bankDegrees, bounds.maxBankDegrees(), "bankDegrees");
        requireAtMost(this.slopePitchDegrees, bounds.maxSlopePitchDegrees(), "slopePitchDegrees");
        requireAtMost(this.accelerationPitchDegrees, bounds.maxAccelerationPitchDegrees(),
                "accelerationPitchDegrees");
        requireAtMost(this.stepHeightBlocks, bounds.maxStepHeightBlocks(), "stepHeightBlocks");

        if (this.stepDurationTicks > bounds.maxStepDurationTicks()) {
            throw new IllegalArgumentException("stepDurationTicks is " + this.stepDurationTicks
                    + " but this server permits at most " + bounds.maxStepDurationTicks());
        }
        if (this.angleBlendPerTick < bounds.minAngleBlendPerTick()) {
            throw new IllegalArgumentException("angleBlendPerTick is " + this.angleBlendPerTick
                    + " but this server requires at least " + bounds.minAngleBlendPerTick()
                    + "; below that a model takes long enough to reach its angle that it reads as lag");
        }
    }

    private static void requireAngle(float degrees, @NotNull String name) {
        if (!Float.isFinite(degrees) || degrees < 0F) {
            throw new IllegalArgumentException(name + " must be finite and not negative, but was "
                    + degrees + "; it is a magnitude, and which way the model leans is the solver's");
        }
    }

    private static void requireAtMost(float value, float limit, @NotNull String name) {
        if (value > limit) {
            throw new IllegalArgumentException(name + " is " + value
                    + " but this server permits at most " + limit);
        }
    }
}
