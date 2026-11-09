package pandastic.studio.models.internal.vehicle;

/**
 * One reading of where a vehicle's root entity is: the scratch buffer the owning region thread fills
 * before deciding anything.
 *
 * <h2>What it deliberately does not carry</h2>
 * Whether the root is standing on something. It is tempting and nothing reads it: this layer never
 * touches the vertical axis, so there is no decision in it that a ground state could change. The day a
 * profile wants to refuse steering in mid-air is the day it belongs here, and until then a field nothing
 * consumes is a field that will quietly stop being filled.
 *
 * <h2>Mutable, and that is the point</h2>
 * The same argument {@link pandastic.studio.models.internal.binding.AnchorSample} makes. This is filled
 * once per control step for every vehicle on the server and consumed inside the method that filled it; a
 * record would allocate one object per vehicle per tick for no benefit. So it is a reusable buffer owned
 * by one vehicle and written only on that vehicle's owning region thread. It is not thread-safe and does
 * not need to be: nothing else ever reads it, and what leaves the step is an immutable
 * {@link pandastic.studio.models.api.vehicle.VehicleState} published whole.
 *
 * <h2>Why the position is read at all, when the solver works in displacements</h2>
 * Because a teleport needs an absolute destination, and the only correct base for that destination is
 * where the entity <em>actually is</em> — not where this layer last decided it should be. A vehicle that
 * was pushed by a piston, stopped by a wall or relocated by another plugin has moved without this layer's
 * involvement, and adding a displacement to a remembered position would snap it back. So the position is
 * read fresh every step and the state carries only the things the entity cannot be asked for.
 */
public final class VehicleSample {

    private double x;
    private double y;
    private double z;
    private float yawDegrees;

    /** Overwrites every field. Called from the owning region thread and nowhere else. */
    public void set(double x, double y, double z, float yawDegrees) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDegrees = yawDegrees;
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
     * The root entity's heading, in degrees, exactly as the platform reports it.
     *
     * <p>Read so that a vehicle can be <em>seeded</em> from its entity at mount time rather than starting
     * at an arbitrary heading. It is deliberately not read afterwards: this layer is authoritative about
     * the heading, so treating the entity's value as input every step would let any other plugin's
     * rotation become a steering command.
     */
    public float yawDegrees() {
        return this.yawDegrees;
    }

    @Override
    public String toString() {
        return "VehicleSample[" + this.x + ", " + this.y + ", " + this.z + ", yaw=" + this.yawDegrees
                + "]";
    }
}
