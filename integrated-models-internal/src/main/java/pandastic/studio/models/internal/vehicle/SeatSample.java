package pandastic.studio.models.internal.vehicle;

/**
 * One reading of where a seat's locator is in the world: the scratch buffer the owning region thread
 * fills before it decides where to put the carrier.
 *
 * <h2>An offset from the model's origin, in world axes</h2>
 * The three numbers are blocks along the world's own axes — the model's rotation has already been applied
 * — measured from where the model stands. They are deliberately <em>not</em> absolute, and the reason is
 * a tick of lag: a renderer caches the position of the model it is drawing and refreshes it on its own
 * schedule, so an absolute answer would be up to a tick stale and every seat would trail the vehicle it
 * belongs to. Adding this offset to the root entity's position as the control step has just read it costs
 * nothing and leaves the rider where the model is now. See {@link SeatLocators.Anchor}.
 *
 * <h2>Mutable, for the reason {@link VehicleSample} is</h2>
 * One of these is filled once per occupied seat per control step and consumed inside the method that
 * filled it. A record would allocate an object per rider per tick for no benefit. It is owned by one seat
 * and written only on the thread that owns the vehicle's root entity, so it is not thread-safe and has no
 * reason to be: nothing else ever reads it.
 *
 * <h2>No rotation, deliberately</h2>
 * A locator has a world rotation and this does not carry it. A carrier's heading is the <em>vehicle's</em>
 * heading, because the thing a rider's body must agree with is the machine they are sitting on rather
 * than the bone the rigger hung the seat off — and a rider's own view direction is theirs, not ours. The
 * day a seat wants to be posed by its locator's roll is the day a rotation belongs here, and until then a
 * field nothing consumes is a field that will quietly stop being filled.
 */
public final class SeatSample {

    private double x;
    private double y;
    private double z;

    /** Overwrites every field. Called from the thread that owns the model and nowhere else. */
    public void set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The locator's x offset from the model's origin, along the world's x axis, in blocks. */
    public double x() {
        return this.x;
    }

    /** The locator's y offset from the model's origin, in blocks. */
    public double y() {
        return this.y;
    }

    /** The locator's z offset from the model's origin, along the world's z axis, in blocks. */
    public double z() {
        return this.z;
    }

    @Override
    public String toString() {
        return "SeatSample[" + this.x + ", " + this.y + ", " + this.z + "]";
    }
}
