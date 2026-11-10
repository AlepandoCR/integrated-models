package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.PhysicsDriver;

import java.util.Objects;

/**
 * The one place a region thread hands the owner's movement to the simulation thread.
 *
 * <h2>The safety argument this type exists to make</h2>
 * The simulation thread may <strong>never read an entity</strong>. On a regionised server an entity may
 * only be touched from the thread that owns its region, and reading a position from anywhere else is not
 * merely racy but undefined — the server offers no protection and the symptom is a torn coordinate, not
 * an exception. So the movement has to be <em>sampled on the owning region thread</em> and handed over
 * as a value. That is the whole design, and this class is the whole seam.
 *
 * <h2>Why a reference store and not a shared mutable object</h2>
 * {@link PhysicsDriver} is an immutable record of four floats, and publication is a single store of a
 * reference to a fully-built one. That store is what carries the happens-before edge: a reader that sees
 * the new reference sees all four fields, and sees them from the same sample.
 *
 * <p>The two obvious alternatives both break that. A mutable holder written field by field publishes a
 * mix of two samples — a forward speed from this tick against a yaw rate from the last — which reads as
 * a bone twitching for no reason anyone can reproduce. A concurrent collection mutated per field has
 * exactly the same flaw, with the thread-safe packaging making it look solved.
 *
 * <p>A plain {@code volatile} field rather than an {@code AtomicReference} because nothing here ever
 * reads and then writes: the region thread publishes, the simulation thread reads, and there is no
 * compare-and-set to perform. The field gives the identical memory semantics without the extra object
 * per model.
 *
 * <h2>Last writer wins, on purpose</h2>
 * A sample is a snapshot, not an event. If the region thread publishes twice between two simulation
 * steps, the older sample is of no interest — the solver wants the owner's current motion, and queueing
 * stale ones would only add latency. Equally, a simulation step that runs between two samples reads the
 * most recent one twice, which is the correct answer to "how is the owner moving".
 */
public final class DriverHolder {

    /**
     * Defaults to {@link PhysicsDriver#STILL} rather than to null, so a simulation that steps before
     * the first sample arrives — which it will, since the loop and the region sampler start
     * independently — simulates a motionless owner instead of needing a null check in the hot path.
     */
    private volatile @NotNull PhysicsDriver published = PhysicsDriver.STILL;
    private volatile float headYawRadians;
    private volatile float headPitchRadians;

    /** Publishes {@code driver}. Called from the owning region thread, once per tick. */
    public void publish(@NotNull PhysicsDriver driver) {
        this.published = Objects.requireNonNull(driver, "driver");
    }

    /** The most recent sample, or {@link PhysicsDriver#STILL} if there has not been one. */
    public @NotNull PhysicsDriver current() {
        return this.published;
    }

    public void publishHeadOrientation(float yawRadians, float pitchRadians) {
        this.headYawRadians = yawRadians;
        this.headPitchRadians = pitchRadians;
    }

    public float headYawRadians() {
        return this.headYawRadians;
    }

    public float headPitchRadians() {
        return this.headPitchRadians;
    }

    /**
     * Forgets the last sample.
     *
     * <p>For a model that has been unbound from its entity: the stale sample would otherwise keep
     * driving the wind term from motion that is no longer happening.
     */
    public void reset() {
        this.published = PhysicsDriver.STILL;
        this.headYawRadians = 0F;
        this.headPitchRadians = 0F;
    }
}
