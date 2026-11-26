package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.VehicleState;

import java.util.Objects;

/**
 * Whether a vehicle's terrain reading is worth taking again, and where the last one was taken.
 *
 * <h2>The whole of the saving is this class, and it reads nothing</h2>
 * One of these per vehicle, asked once per control step, from the thread that owns the root entity. It
 * holds the position, the heading and the clock reading of the last reading taken and compares them
 * against the step's own — all of which the step has already read for other reasons, so the decision
 * costs no entity access, no world access and no allocation. What it decides is whether
 * {@link VehicleHost#probeTerrain} is called at all, and that call is the only thing in this plugin that
 * reads blocks.
 *
 * <h2>The order of the tests is the design</h2>
 * {@link #readingDue} answers five questions in a fixed order, and three of them are overrides that a
 * configured threshold cannot switch off:
 *
 * <ol>
 *   <li><strong>Nothing has been read yet.</strong> A vehicle's first step has no reading to reuse, so
 *       there is nothing to decide.</li>
 *   <li><strong>The vehicle has crossed a whole column.</strong> The probe looks exactly
 *       {@link TerrainSample#PROBE_RUN_BLOCKS} ahead, so a vehicle that has moved that far is standing in
 *       the column the reading called "ahead" — the reading is not stale, it is about somewhere else.
 *       This override is what keeps the look-ahead honest at any sample rate: it is the answer to "a
 *       probe that was safe at one block of look-ahead may not be at four", and it is checked before the
 *       interval so that no configuration can make a vehicle at speed act on a column it has already
 *       driven through. It also covers the relocation case for free, where the vehicle's position jumps
 *       by more than the threshold decided it could.</li>
 *   <li><strong>The reading has expired.</strong> Independent of the vehicle's own motion, because the
 *       thing that changed may be the world: somebody builds a ramp under a parked cart, and no amount of
 *       the cart not moving will ever notice.</li>
 *   <li><strong>The interval has not elapsed.</strong> Below it nothing is read, whatever the vehicle has
 *       done. This is the term that bounds a vehicle at full speed.</li>
 *   <li><strong>The vehicle has moved, or turned, enough.</strong> Two thresholds rather than one,
 *       because the column <em>ahead</em> is chosen by the heading: a vehicle turning on the spot has not
 *       moved and is looking at different ground.</li>
 * </ol>
 *
 * <h2>Why deciding and recording are one call</h2>
 * Because they cannot be allowed to disagree. A caller that asked and then forgot to record would read
 * blocks on every step for ever, silently and with nothing to point at — the same class of defect as a
 * settings source whose identity is unstable. So the recording is not the caller's to remember: an answer
 * of {@code true} <em>is</em> the record of a reading being taken.
 *
 * <p>The consequence is that a probe which then declines — a host whose root has gone, a column another
 * region owns — is still recorded as a reading taken. That is deliberate: the decision this class makes
 * is "is it worth asking", and a refusal is an answer. The column under the vehicle is never refused, so
 * a declined probe has still established what the vehicle is standing on.
 *
 * <p>Region-confined, like the {@link TerrainSample} it gates: written and read only by the control step,
 * on the thread that owns the root entity. Nothing here is volatile and nothing needs to be.
 */
public final class TerrainSampleGate {

    /**
     * One column of the world, squared, in the precision the comparison is made in.
     *
     * <p><strong>Structure.</strong> {@link TerrainSample#PROBE_RUN_BLOCKS} is the width of a column and
     * is the run the probe looks along; this is the same fact squared so that a step does not pay for a
     * square root. Derived from that field rather than written as a number, so the two cannot drift.
     */
    private static final double COLUMN_SQUARED =
            (double) TerrainSample.PROBE_RUN_BLOCKS * (double) TerrainSample.PROBE_RUN_BLOCKS;

    private boolean seeded;
    private double x;
    private double y;
    private double z;
    private float yawDegrees;
    private long nanos;

    /**
     * Whether to take a reading now, recording that one was taken when the answer is yes.
     *
     * @param settings    how often this server permits a reading, read once for the whole step
     * @param x           the vehicle's world x this step, from the sample the step has already read
     * @param y           its world y
     * @param z           its world z
     * @param yawDegrees  the heading the probe would look along, as the server measures yaw
     * @param nowNanos    this step's monotonic clock reading, the same one the step integrates with
     * @return whether {@link VehicleHost#probeTerrain} should be called on this step
     */
    public boolean readingDue(
            @NotNull TerrainSampling settings,
            double x,
            double y,
            double z,
            float yawDegrees,
            long nowNanos
    ) {
        Objects.requireNonNull(settings, "settings");
        if (decide(settings, x, y, z, yawDegrees, nowNanos)) {
            this.seeded = true;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yawDegrees = yawDegrees;
            this.nanos = nowNanos;
            return true;
        }
        return false;
    }

    /** The decision, with nothing recorded. See the class note for the order. */
    private boolean decide(
            @NotNull TerrainSampling settings,
            double x,
            double y,
            double z,
            float yawDegrees,
            long nowNanos
    ) {
        if (!this.seeded) {
            return true;
        }

        final double movedSquared = distanceSquaredFromLastReading(x, y, z);
        if (movedSquared >= COLUMN_SQUARED) {
            return true;
        }

        // Subtracted rather than compared, because a monotonic clock's origin is arbitrary and may be
        // negative: the difference is meaningful where the readings themselves are not.
        final long age = nowNanos - this.nanos;
        if (age >= settings.maxAgeNanos()) {
            return true;
        }
        if (age < settings.minIntervalNanos()) {
            return false;
        }

        if (movedSquared >= settings.moveDeadbandSquared()) {
            return true;
        }
        // Normalised, because a vehicle that crossed the +/-180 seam since the last reading has turned a
        // few degrees and not three hundred and sixty of them.
        final float turned = Math.abs(
                VehicleState.normaliseDegrees(yawDegrees - this.yawDegrees));
        return turned >= settings.turnDeadbandDegrees();
    }

    /** How far the vehicle is from where the last reading was taken, squared, in three dimensions. */
    private double distanceSquaredFromLastReading(double x, double y, double z) {
        final double deltaX = x - this.x;
        final double deltaY = y - this.y;
        final double deltaZ = z - this.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    @Override
    public @NotNull String toString() {
        return this.seeded
                ? "TerrainSampleGate[last=" + this.x + "," + this.y + "," + this.z
                        + " yaw=" + this.yawDegrees + "]"
                : "TerrainSampleGate[nothing read yet]";
    }
}
