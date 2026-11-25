package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.ClipRole;

/**
 * Chooses which locomotion state a model is in from how fast its owner is moving — with two independent
 * guards against choosing a different one every sample.
 *
 * <h2>Two guards, because one is not enough</h2>
 * <strong>Hysteresis</strong> widens every threshold in the direction of the state currently held: it
 * takes {@code hysteresis} more speed to enter a faster state than to stay in it, and {@code hysteresis}
 * less to drop out of one. That covers the ordinary case, an owner moving at almost exactly walking pace,
 * where a bare threshold is crossed several times a second and a clip restarted several times a second
 * reads as a model twitching rather than walking.
 *
 * <p>It does <em>not</em> cover a speed that oscillates by more than the band — a player stutter-stepping,
 * a mob pathfinding around a corner, an owner being knocked back. So a <strong>minimum hold</strong> runs
 * alongside it: a state just entered is kept for at least {@code minimumHoldSeconds} regardless of what
 * the speed does. The cost is that a genuine transition can be late by that much, which is why it is
 * configuration — the right figure is a function of how long the authored clips are, and nothing here can
 * know that.
 *
 * <h2>Pure, and therefore the part worth testing</h2>
 * No rig, no renderer, no clip names: it answers in {@link ClipRole} and leaves the mapping to
 * {@link ClipStage}. Which is what makes a threshold's behaviour at its own boundary assertable without a
 * server.
 *
 * <p>Not thread-safe, and does not need to be: one selector belongs to one model and is only ever touched
 * from that model's owning region thread — the same thread that sampled the speed it is handed.
 */
public final class ClipSelector {

    private @NotNull ClipRole role = ClipRole.IDLE;
    private float heldSeconds;

    /**
     * The state this model should be in, given its owner's current horizontal speed.
     *
     * <p>Takes the settings as an argument rather than reading a source, so that the caller's
     * once-per-decision read is the only read there is.
     *
     * @param speedBlocksPerTick horizontal speed of the owner, in blocks per server tick. Non-finite or
     *                           negative input is treated as standing still rather than throwing: it
     *                           arrives from a differenced position and the right failure mode for a
     *                           garbage sample is a model that stops rather than one that crashes a
     *                           region thread
     * @param deltaSeconds       seconds since the previous call, for the hold timer. A non-positive value
     *                           contributes nothing
     * @param settings           the one snapshot this decision is made against
     * @return the state in force after this call, which is {@link #role()}
     */
    public @NotNull ClipRole select(
            float speedBlocksPerTick,
            float deltaSeconds,
            @NotNull ClipSettings settings
    ) {
        if (deltaSeconds > 0F && Float.isFinite(deltaSeconds)) {
            this.heldSeconds += deltaSeconds;
        }

        final float speed = Float.isFinite(speedBlocksPerTick) && speedBlocksPerTick > 0F
                ? speedBlocksPerTick
                : 0F;
        final ClipRole target = targetFor(speed, settings);
        if (target == this.role) {
            return this.role;
        }
        if (this.heldSeconds < settings.minimumHoldSeconds()) {
            return this.role;
        }
        this.role = target;
        this.heldSeconds = 0F;
        return this.role;
    }

    /** The state currently held. */
    public @NotNull ClipRole role() {
        return this.role;
    }

    /** How long the current state has been held, in seconds. A diagnostic, and what a test asserts on. */
    public float heldSeconds() {
        return this.heldSeconds;
    }

    /**
     * Returns to standing, with the hold timer satisfied.
     *
     * <p>The timer is cleared to a value that lets the <em>next</em> sample move immediately, because a
     * reset happens when a model is rebound or stopped and making it wait out a hold it was not part of
     * would show the model standing still for a quarter second after it appeared.
     */
    public void reset() {
        this.role = ClipRole.IDLE;
        this.heldSeconds = Float.MAX_VALUE;
    }

    /**
     * Which state {@code speed} names, with the thresholds widened around the state currently held.
     *
     * <p>Note the asymmetry is deliberate and is the whole of the hysteresis: entering a faster state
     * needs {@code threshold + h}, leaving one needs {@code threshold - h}, so the band between them is
     * {@code 2h} wide and no single speed sits in both.
     */
    private @NotNull ClipRole targetFor(float speed, @NotNull ClipSettings settings) {
        final float walk = settings.walkSpeedBlocksPerTick();
        final float run = settings.runSpeedBlocksPerTick();
        final float margin = settings.speedHysteresisBlocksPerTick();
        return switch (this.role) {
            case IDLE -> {
                if (speed >= run + margin) {
                    yield ClipRole.RUN;
                }
                yield speed >= walk + margin ? ClipRole.WALK : ClipRole.IDLE;
            }
            case WALK -> {
                if (speed >= run + margin) {
                    yield ClipRole.RUN;
                }
                yield speed < walk - margin ? ClipRole.IDLE : ClipRole.WALK;
            }
            case RUN -> {
                if (speed < walk - margin) {
                    yield ClipRole.IDLE;
                }
                yield speed < run - margin ? ClipRole.WALK : ClipRole.RUN;
            }
        };
    }
}
