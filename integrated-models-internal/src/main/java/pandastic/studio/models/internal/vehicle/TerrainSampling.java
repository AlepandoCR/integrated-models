package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How often a vehicle is allowed to read the blocks under it, as one immutable value.
 *
 * <h2>Why this exists, and why it is not an optimisation</h2>
 * A terrain reading is the only thing in this plugin that reads <strong>blocks</strong>, and a block read
 * cannot be moved off the thread that owns the chunk — not on a regionised server, not ever. So the one
 * cost that cannot be pushed off the tick is the one this record bounds. Every other per-step cost of a
 * vehicle is a position write or arithmetic; this one is a walk down a column of the world, five reads of
 * it at the shipped reach, per vehicle, per control step. At one step a tick that is a hundred block
 * reads a second per vehicle, and the figure that worried the owner is that times the vehicle count.
 *
 * <p>The answer is not asynchrony, because none is available. It is <em>rarity</em>: the terrain under a
 * vehicle does not change while the vehicle has not moved, so a reading taken once is good for as long as
 * neither the vehicle nor the world has moved under it. These five values are where an operator says how
 * long that is.
 *
 * <h2>The four mechanisms, and the one that is already free</h2>
 * <ol>
 *   <li><strong>A movement deadband.</strong> A vehicle that has not moved far enough to be over
 *       different blocks re-reads nothing. This is what makes a parked vehicle — the common state of
 *       every vehicle on a server most of the time — cost nothing at all.</li>
 *   <li><strong>A minimum interval</strong>, which decouples the sample rate from the control rate. The
 *       control loop decides where the vehicle is every tick because a rider's input changes that often;
 *       the ground does not.</li>
 *   <li><strong>A maximum age</strong>, which is the other half of the deadband and the reason it is
 *       safe: a player can build a ramp under a parked vehicle, and nothing about the vehicle's own
 *       motion would ever notice. So a reading expires.</li>
 *   <li><strong>A viewer gate.</strong> The terrain reaction is a lean, a pitch and a step-up on a model;
 *       for a vehicle no viewer holds, the right number of block reads is zero. See
 *       {@link #onlyWhenSeen()} for what that costs, because it does cost something.</li>
 * </ol>
 *
 * <p>There is deliberately <strong>no interpolation mechanism</strong> beside these, and that is not an
 * omission. {@link pandastic.studio.models.api.vehicle.TerrainResponse#angleBlendPerTick()} already moves
 * every angle a fraction of the remaining difference each tick, measured in real elapsed ticks — so
 * between two readings the model keeps travelling toward the target the older one implies, smoothly, at
 * the rate the profile asked for. The blend's own time constant is several ticks at every shipped
 * default, which is longer than the interval below: the step in the <em>target</em> a new reading
 * introduces is already smoothed by the mechanism that was there before this record existed. A second
 * interpolator would be two filters in series and a model that answers the ground late twice.
 *
 * <h2>Not to be confused with its namesakes</h2>
 * This is a consumer-side value, like {@link SeatSettings}: it is carried inside {@link VehicleSettings}
 * so that <em>one</em> snapshot read per control step covers the loop, the seats and the sampling alike.
 * The keys, comments and defaults behind it are the {@code terrain.sample*} group of
 * {@link pandastic.studio.models.internal.config.VehicleSettings}.
 *
 * @param moveDeadbandBlocks  how far the vehicle must have moved since the last reading before another
 *                            is worth taking, in blocks, measured in three dimensions. Zero means every
 *                            step may read. The vertical component is part of the distance on purpose: a
 *                            climb moves the vehicle vertically and almost not at all horizontally, and
 *                            it is exactly the step on which the ground underneath has changed
 * @param turnDeadbandDegrees how far the vehicle must have turned since the last reading before another
 *                            is worth taking. Its own term rather than part of the distance because the
 *                            <em>column ahead</em> is chosen by the heading — a vehicle turning on the
 *                            spot has not moved an inch and is looking somewhere else
 * @param minIntervalTicks    fewest ticks between two readings, whatever the vehicle has done in between.
 *                            Zero imposes no minimum. This is the term that bounds a vehicle at full
 *                            speed, where the deadband is crossed every step and suppresses nothing
 * @param maxAgeTicks         after how many ticks a reading is taken again regardless of the deadband,
 *                            because the world may have changed rather than the vehicle. Must not be
 *                            below {@link #minIntervalTicks()}, or it would force a reading at every
 *                            interval and the deadband would suppress nothing
 * @param onlyWhenSeen        whether a vehicle no viewer holds reads any blocks at all. What it buys is
 *                            everything: a server's unseen vehicles are most of its vehicles, and their
 *                            right cost is zero. What it costs is that an unseen vehicle does not
 *                            <em>climb</em> — the terrain reaction carries the step-up as well as the
 *                            lean — so an unobserved vehicle driving at a kerb stops at it. That repairs
 *                            itself on observation: the first step on which any viewer holds the model
 *                            takes a reading and arms the climb, so what a player arriving finds is a
 *                            vehicle that drives on, not one that is stuck
 */
public record TerrainSampling(
        float moveDeadbandBlocks,
        float turnDeadbandDegrees,
        int minIntervalTicks,
        int maxAgeTicks,
        boolean onlyWhenSeen
) {

    /**
     * A reading on every control step, which is what every vehicle did before this record existed.
     *
     * <p><strong>The identity value, not a tuning.</strong> All three thresholds are zero, so each
     * comparison in {@link TerrainSampleGate} passes trivially and nothing is ever suppressed; the viewer
     * gate is off, so nothing is skipped for being unseen. It is what the compatibility constructors of
     * {@link VehicleSettings} supply and what every test that is not about sampling gets, so a vehicle
     * built against those settings behaves exactly as it did before — a shared constant, so two bundles
     * built that way carry the same sampling by identity.
     */
    public static final @NotNull TerrainSampling EVERY_STEP =
            new TerrainSampling(0F, 0F, 0, 0, false);

    public TerrainSampling {
        if (!Float.isFinite(moveDeadbandBlocks) || moveDeadbandBlocks < 0F) {
            // Zero is legal and means "read on every step the interval allows"; negative names no
            // distance at all.
            throw new IllegalArgumentException("moveDeadbandBlocks must be finite and not negative but"
                    + " was " + moveDeadbandBlocks);
        }
        if (moveDeadbandBlocks > TerrainSample.PROBE_RUN_BLOCKS) {
            // Structural rather than a matter of taste. The probe looks one column ahead, so a vehicle
            // that has moved a whole column is standing where the reading said "ahead" was — a deadband
            // above that describes a reading that can never be stale enough to replace, and
            // TerrainSampleGate overrides it anyway. A key that cannot take effect is worse than no key.
            throw new IllegalArgumentException("moveDeadbandBlocks must not exceed the probe's own run of "
                    + TerrainSample.PROBE_RUN_BLOCKS + " block(s) but was " + moveDeadbandBlocks
                    + "; past one column the vehicle has driven into the column the reading described");
        }
        if (!Float.isFinite(turnDeadbandDegrees) || turnDeadbandDegrees < 0F) {
            throw new IllegalArgumentException("turnDeadbandDegrees must be finite and not negative but"
                    + " was " + turnDeadbandDegrees);
        }
        if (minIntervalTicks < 0) {
            throw new IllegalArgumentException(
                    "minIntervalTicks must not be negative but was " + minIntervalTicks);
        }
        if (maxAgeTicks < 0) {
            throw new IllegalArgumentException(
                    "maxAgeTicks must not be negative but was " + maxAgeTicks);
        }
        if (maxAgeTicks < minIntervalTicks) {
            // Not a tidiness check: the age override is read before the interval, so an age below the
            // interval fires on the first step past it and the deadband never suppresses anything. The
            // configuration would then describe a saving it does not make.
            throw new IllegalArgumentException("maxAgeTicks (" + maxAgeTicks + ") must not be below"
                    + " minIntervalTicks (" + minIntervalTicks + "), or a reading expires before the"
                    + " interval it was waiting for and the deadband suppresses nothing");
        }
    }

    /**
     * {@link #moveDeadbandBlocks()} squared, in the precision the comparison is made in.
     *
     * <p>Squared because the alternative is a square root per vehicle per step for a comparison whose
     * answer is unchanged by it, and {@code double} because a vehicle's position is.
     *
     * @return the deadband, squared
     */
    @Contract(pure = true)
    public double moveDeadbandSquared() {
        final double blocks = this.moveDeadbandBlocks;
        return blocks * blocks;
    }

    /**
     * {@link #minIntervalTicks()} in nanoseconds, which is the unit the gate compares in.
     *
     * <p>Converted from ticks rather than configured in nanoseconds because an operator thinks in ticks
     * and a monotonic clock does not, and from {@link VehicleStepper#NANOS_PER_TICK} rather than a figure
     * of its own so that the two cannot disagree about how long a tick is.
     *
     * @return the interval, in nanoseconds
     */
    @Contract(pure = true)
    public long minIntervalNanos() {
        return (long) this.minIntervalTicks * VehicleStepper.NANOS_PER_TICK;
    }

    /**
     * {@link #maxAgeTicks()} in nanoseconds. See {@link #minIntervalNanos()}.
     *
     * @return the age ceiling, in nanoseconds
     */
    @Contract(pure = true)
    public long maxAgeNanos() {
        return (long) this.maxAgeTicks * VehicleStepper.NANOS_PER_TICK;
    }
}
