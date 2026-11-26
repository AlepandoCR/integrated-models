package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Everything the server remembers about how a vehicle is sitting on the terrain: two angles and a climb
 * in progress.
 *
 * <h2>Why a value and not fields on the vehicle</h2>
 * The argument {@link pandastic.studio.models.api.vehicle.VehicleState} makes, and it is the same
 * argument. The angles are blended from the previous step's, so they are state rather than output; a
 * state that can be mutated in place has no single moment at which it is true, and a reader on another
 * thread can then see a lean from this step against a pitch from the last. An immutable record published
 * whole makes that combination unrepresentable.
 *
 * <h2>The angles here are the blended ones, not the targets</h2>
 * {@link pandastic.studio.models.api.vehicle.TerrainResponse} states each angle as a <em>ceiling</em>
 * reached at a full-rate input; the target is that ceiling scaled by the input actually achieved, and
 * what is stored here is how far toward the target the model has got. Which is why this is the thing
 * carried between steps: without it, {@code angleBlendPerTick} has nothing to blend from and every
 * terrain change snaps.
 *
 * <h2>Why the climb is two numbers</h2>
 * {@link #climbRemainingBlocks()} is how much of the step is left to apply and
 * {@link #climbPerTickBlocks()} is the rate it is applied at, fixed when the climb was armed. Keeping the
 * rate rather than re-deriving it is what makes a climb finish in the duration it started with: a rate
 * recomputed from the remaining distance each step would spread the same rise over the same duration
 * <em>again</em> every step, so the vehicle would approach the top of the block and never reach it —
 * Zeno's staircase, with no symptom other than a vehicle that never quite climbs.
 *
 * @param bankDegrees           how far the model is currently rolled, positive with its right side down
 * @param pitchDegrees          how far the model is currently pitched, in the sign the server measures
 *                              pitch in: negative is nose up
 * @param climbRemainingBlocks  how much of an armed step is still to be applied, in blocks; zero when no
 *                              climb is in flight
 * @param climbPerTickBlocks    how much of it is applied per tick, fixed when the climb was armed
 */
public record TerrainAttitude(
        float bankDegrees,
        float pitchDegrees,
        float climbRemainingBlocks,
        float climbPerTickBlocks
) {

    /**
     * A vehicle sitting flat with no climb in flight.
     *
     * <p>Not a tunable: every component is the additive identity. It is what a vehicle starts at and what
     * one whose profile says nothing about terrain stays at for its whole life.
     */
    public static final @NotNull TerrainAttitude LEVEL = new TerrainAttitude(0F, 0F, 0F, 0F);

    public TerrainAttitude {
        if (!Float.isFinite(bankDegrees) || !Float.isFinite(pitchDegrees)
                || !Float.isFinite(climbRemainingBlocks) || !Float.isFinite(climbPerTickBlocks)) {
            throw new IllegalArgumentException("A terrain attitude must be finite");
        }
        if (climbRemainingBlocks < 0F || climbPerTickBlocks < 0F) {
            throw new IllegalArgumentException("A climb is a distance still to be covered and a rate to"
                    + " cover it at, so neither can be negative, but they were "
                    + climbRemainingBlocks + " and " + climbPerTickBlocks
                    + "; a vehicle going downhill falls under its own gravity rather than being lowered");
        }
        // Negative zero folded for the reason VehicleState folds it: a record's generated equality
        // compares floats bitwise, so an angle that blended to -0.0 would be unequal to LEVEL while
        // being indistinguishable from it arithmetically.
        bankDegrees = bankDegrees == 0F ? 0F : bankDegrees;
        pitchDegrees = pitchDegrees == 0F ? 0F : pitchDegrees;
        climbRemainingBlocks = climbRemainingBlocks == 0F ? 0F : climbRemainingBlocks;
        climbPerTickBlocks = climbPerTickBlocks == 0F ? 0F : climbPerTickBlocks;
    }

    /** Whether a step is part-way applied, during which no new one is armed. */
    @Contract(pure = true)
    public boolean isClimbing() {
        return this.climbRemainingBlocks > 0F;
    }

    /** Whether the model is flat and still, which is what a vehicle on level ground settles to. */
    @Contract(pure = true)
    public boolean isLevel() {
        return this.bankDegrees == 0F && this.pitchDegrees == 0F && !isClimbing();
    }

    /**
     * This attitude with both angles kept and any climb forgotten.
     *
     * <p>For a vehicle whose root has just been relocated: a cross-region teleport puts the entity
     * somewhere this layer has not probed, so a climb armed against the terrain it left is a rise applied
     * against terrain nobody has looked at. The angles survive because they are about how the model is
     * sitting rather than about where it is, and blending them from where they were is what stops the
     * model snapping flat across the relocation.
     *
     * @return the same angles with no climb in flight
     */
    @Contract(value = "-> new", pure = true)
    public @NotNull TerrainAttitude withoutClimb() {
        return new TerrainAttitude(this.bankDegrees, this.pitchDegrees, 0F, 0F);
    }
}
