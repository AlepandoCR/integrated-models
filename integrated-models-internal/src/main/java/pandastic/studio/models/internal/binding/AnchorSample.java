package pandastic.studio.models.internal.binding;

/**
 * One reading of where a model's anchor is and what stance it is in: the scratch buffer the region
 * thread fills before publishing.
 *
 * <h2>Mutable, and that is the point</h2>
 * A record would be the obvious shape and is the wrong one. This is filled once per tick for every
 * bound model on the server, and the whole value of it is consumed inside the same method that filled
 * it — the numbers leave this object as an immutable
 * {@link pandastic.studio.models.api.physics.PhysicsDriver} and two booleans, and nothing retains it. A
 * record would allocate one object per model per tick for no benefit, which on a server with a thousand
 * bound models is twenty thousand short-lived objects a second on the region threads that can least
 * afford the garbage.
 *
 * <p>So this is deliberately a reusable buffer, owned by one {@link RiggedModelImpl} and written only on
 * that model's owning region thread. It is not thread-safe and does not need to be: it is never read
 * from anywhere else, and the hand-off to the simulation thread happens through the immutable value
 * published into a {@link pandastic.studio.models.internal.sim.DriverHolder}, not through this.
 *
 * <h2>Position and stance in one object</h2>
 * Both halves come from the same entity read, on the same thread, in the same tick — and they have to
 * stay that way. A gait blended from this tick's stance against last tick's speed is the same
 * disagreement the one-driver-sample-per-step rule exists to prevent, one layer earlier.
 */
public final class AnchorSample {

    private double x;
    private double y;
    private double z;
    private float yawDegrees;
    private boolean sneaking;
    private boolean airborne;

    /** Overwrites every field. Called from the owning region thread and nowhere else. */
    public void set(
            double x,
            double y,
            double z,
            float yawDegrees,
            boolean sneaking,
            boolean airborne
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDegrees = yawDegrees;
        this.sneaking = sneaking;
        this.airborne = airborne;
    }

    /** World x, in blocks. */
    public double x() {
        return this.x;
    }

    /** World y, in blocks. */
    public double y() {
        return this.y;
    }

    /** World z, in blocks. */
    public double z() {
        return this.z;
    }

    /**
     * Body yaw, in degrees, exactly as the platform reports it.
     *
     * <p>Unwrapped on purpose: Minecraft yaw grows without bound and crosses the 180-degree seam freely,
     * and folding it here would destroy the information
     * {@link pandastic.studio.models.internal.sim.DriverSampler} needs to tell a two-degree turn from a
     * 358-degree one.
     */
    public float yawDegrees() {
        return this.yawDegrees;
    }

    /** Whether the anchor is crouched. A gait input, blended rather than snapped. */
    public boolean sneaking() {
        return this.sneaking;
    }

    /** Whether the anchor is off the ground. At full blend the walk cycle contributes nothing. */
    public boolean airborne() {
        return this.airborne;
    }

    @Override
    public String toString() {
        return "AnchorSample[" + this.x + ", " + this.y + ", " + this.z + ", yaw=" + this.yawDegrees
                + ", sneaking=" + this.sneaking + ", airborne=" + this.airborne + "]";
    }
}
