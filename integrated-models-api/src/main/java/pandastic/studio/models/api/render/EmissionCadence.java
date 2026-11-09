package pandastic.studio.models.api.render;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How often bone transforms go on the wire, and what interpolation window to send with them.
 *
 * <h2>Why this type exists at all</h2>
 * The intuition that smoother motion needs faster packets is wrong here, and expensively so. A display
 * entity consumes its pending-update flags <strong>exactly once per client tick</strong>: a second
 * packet inside the same tick does not add an interpolation step, it only replaces the target the one
 * step will aim at. So twenty updates a second and sixty produce the same number of visible state
 * changes — sixty just discards two thirds of them, and pays full bandwidth to do it.
 *
 * <p>The smoothness above the tick rate is real, and it is free. The client re-evaluates its
 * interpolation progress on every rendered frame using the partial tick, so a single update spread over
 * a window yields as many distinct intermediate poses as the viewer's monitor can draw. A player at
 * 144 fps sees 144 poses a second out of 20 packets.
 *
 * <p>Which inverts the tuning: emit <em>slower</em> with a matched window, not faster. Ten updates a
 * second with a two-tick window looks better than sixty with none, at a sixth of the bandwidth.
 *
 * <h2>The window must cover the gap</h2>
 * {@link #interpolationTicks()} is at least the emission interval. If the window is shorter than the
 * gap between updates, the tween finishes early and the bone sits still until the next packet — motion
 * that stutters in exactly the rhythm of the emission rate. Matching them is optimal; one tick over
 * buys tolerance for a late packet at the cost of a tick of lag, which is why
 * {@link #withJitterTolerance()} exists and why it is not the default.
 *
 * <p>Overshooting is safe in a way undershooting is not: a late or dropped update does not snap,
 * because the client re-anchors a new window at the pose it is currently rendering rather than at the
 * one it was aiming for.
 */
public final class EmissionCadence {

    /**
     * Client ticks per second. The ceiling on distinct interpolation restarts.
     *
     * <p><strong>Deliberately not configurable, and this is the one number in the library where that
     * matters most.</strong> It is a protocol fact, not a preference: a display entity consumes its
     * pending-update flags exactly once per client tick, so twenty is the number of distinct visible
     * state changes a second the client is capable of, whatever the server sends. Exposed as a setting
     * an operator would eventually set it to 60 — the intuition that smoother motion needs faster
     * packets is near-universal — and pay triple the bandwidth for zero visual change. That is the
     * precise mistake this whole type exists to prevent, so it is not a knob.
     */
    public static final int CLIENT_TICK_HZ = 20;

    private final int emissionHz;
    private final int stepsPerEmission;
    private final int interpolationTicks;
    private final @NotNull CadenceBounds bounds;

    private EmissionCadence(
            int emissionHz,
            int stepsPerEmission,
            int interpolationTicks,
            @NotNull CadenceBounds bounds
    ) {
        this.emissionHz = emissionHz;
        this.stepsPerEmission = stepsPerEmission;
        this.interpolationTicks = interpolationTicks;
        this.bounds = bounds;
    }

    /**
     * A cadence for a simulation stepping at {@code simulationHz} that emits at {@code emissionHz}.
     *
     * <p>The two rates are independent on purpose. Simulation rate is a stability requirement set by
     * the stiffest spring in the rig; emission rate is a bandwidth decision. Conflating them is what
     * leads to paying for sixty packets a second to keep a spring from diverging.
     *
     * @param simulationHz steps per second of the physics loop; must be a multiple of {@code emissionHz}
     * @param emissionHz   updates per second to put on the wire, at most {@link #CLIENT_TICK_HZ}
     * @param bounds       the window the client may be asked to interpolate over, which is a trade
     *                     against this server's own jitter and therefore configuration
     * @throws IllegalArgumentException if the rates are not a clean multiple, which would make the
     *                                 emission interval — and therefore the interpolation window —
     *                                 vary from one update to the next
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull EmissionCadence of(
            int simulationHz,
            int emissionHz,
            @NotNull CadenceBounds bounds
    ) {
        if (emissionHz < 1 || emissionHz > CLIENT_TICK_HZ) {
            throw new IllegalArgumentException(
                    "Emission rate must be within 1.." + CLIENT_TICK_HZ + " Hz but was " + emissionHz
                            + ". Above the client tick rate the extra packets are discarded unseen.");
        }
        if (simulationHz < emissionHz) {
            throw new IllegalArgumentException(
                    "Simulation rate " + simulationHz + " Hz is below the emission rate " + emissionHz
                            + " Hz, which would emit poses that were never computed");
        }
        if (simulationHz % emissionHz != 0) {
            throw new IllegalArgumentException(
                    "Simulation rate " + simulationHz + " Hz is not a multiple of the emission rate "
                            + emissionHz + " Hz; the interpolation window would differ between updates");
        }

        final int ticks = Math.clamp(CLIENT_TICK_HZ / emissionHz,
                bounds.minInterpolationTicks(), bounds.maxInterpolationTicks());
        return new EmissionCadence(emissionHz, simulationHz / emissionHz, ticks, bounds);
    }

    /** The window limits this cadence was built against, which {@link #withJitterTolerance()} respects. */
    public @NotNull CadenceBounds bounds() {
        return this.bounds;
    }

    /** Updates per second on the wire. */
    public int emissionHz() {
        return this.emissionHz;
    }

    /** Simulation steps between two emissions. */
    public int stepsPerEmission() {
        return this.stepsPerEmission;
    }

    /** The interpolation window to send, in client ticks. Always covers the emission interval. */
    public int interpolationTicks() {
        return this.interpolationTicks;
    }

    /** Whether the step numbered {@code step} is an emission step. */
    public boolean shouldEmit(long step) {
        return step % this.stepsPerEmission == 0;
    }

    /**
     * This cadence with one extra tick of window, so a late packet still arrives inside the tween.
     *
     * <p>Costs a tick of visual lag. Worth it on a server where region threads stall, not worth it
     * otherwise.
     */
    @Contract(value = " -> new", pure = true)
    public @NotNull EmissionCadence withJitterTolerance() {
        return new EmissionCadence(
                this.emissionHz,
                this.stepsPerEmission,
                Math.min(this.interpolationTicks + 1, this.bounds.maxInterpolationTicks()),
                this.bounds);
    }

    @Override
    public @NotNull String toString() {
        return "EmissionCadence[" + this.emissionHz + " Hz, every " + this.stepsPerEmission
                + " steps, " + this.interpolationTicks + "-tick window]";
    }
}
