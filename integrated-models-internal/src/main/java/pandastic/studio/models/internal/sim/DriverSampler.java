package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsDriver;

import java.util.Objects;

/**
 * Turns successive positions and yaws into a {@link PhysicsDriver}.
 *
 * <p>Takes six doubles and two floats rather than an entity or a location, so the differencing is
 * testable on its own and so nothing in this package has to name a Bukkit type. The layer that owns the
 * entity reads {@code getX/getY/getZ/getYaw} on the owning region thread and feeds the numbers in; that
 * layer is out of this package's scope by design.
 *
 * <h2>Why the teleport filter lives here</h2>
 * A position delta is <strong>indistinguishable from a teleport</strong> unless whoever differenced it
 * says so. The solver cannot tell: eighty blocks in one tick is arithmetically just a very fast owner,
 * and feeding it to the wind term launches every bone in the rig at once — a chain that snaps to its
 * limits and takes a second to crawl back, on every portal, every {@code /tp}, and every respawn. Only
 * this method knows the delta came from two samples rather than from real motion, so
 * {@link PhysicsDriver#withoutTeleports(DriverBounds)} is applied here and nowhere else.
 *
 * <h2>Yaw is wrapped, not subtracted</h2>
 * Minecraft yaw is unbounded and crosses the 180-degree seam freely, so a two-degree turn from 179 to
 * −179 subtracts to −358. Unwrapped, that one frame hands the drag term a 6 rad/tick spin, which looks
 * exactly like a teleport and is just as wrong.
 *
 * <h2>The thresholds are configuration, read once per sample</h2>
 * What counts as a teleport depends on what the server does to entities, not on arithmetic — elytra,
 * riptide, vehicles and plugins that set positions directly all produce deltas a vanilla server never
 * would. So the two thresholds arrive in a {@link DriverBounds} from a
 * {@link SimulationSettingsSource}, and {@link #sample} takes one snapshot and uses it for the whole
 * sample. Too low and real movement is silently discarded, so the model stops reacting to the thing it
 * is attached to with nothing in the log; too high and a teleport reaches the wind term.
 *
 * <h2>One sample per tick</h2>
 * {@link PhysicsDriver} speeds are blocks per <em>server tick</em>, which is what a raw delta between
 * two consecutive ticks already is — so there is no scaling here. A caller sampling at some other rate
 * has to scale, and a caller sampling irregularly cannot use this at all; the design assumption is one
 * sample per tick on the entity's own scheduler.
 *
 * <p>Instances are not thread-safe. One sampler belongs to one model and is only ever touched from that
 * model's owning region thread — the same thread that will {@link DriverHolder#publish} the result.
 */
public final class DriverSampler {

    private final @NotNull SimulationSettingsSource settings;

    private double previousX;
    private double previousY;
    private double previousZ;
    private float previousYawDegrees;
    private boolean seeded;

    /**
     * @param settings where the teleport thresholds are read from, once per sample
     */
    public DriverSampler(@NotNull SimulationSettingsSource settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * The owner's motion since the previous sample, with teleports filtered out.
     *
     * <p>The first call after construction or {@link #reset()} returns {@link PhysicsDriver#STILL}:
     * there is no previous position to difference against, and inventing one would make a model's first
     * tick of physics depend on where it happened to spawn.
     *
     * @param x           current world x, in blocks
     * @param y           current world y
     * @param z           current world z
     * @param yawDegrees  current body yaw, in degrees, unwrapped as Minecraft reports it
     */
    public @NotNull PhysicsDriver sample(double x, double y, double z, float yawDegrees) {
        if (!this.seeded) {
            this.seeded = true;
            store(x, y, z, yawDegrees);
            return PhysicsDriver.STILL;
        }

        // One read for the whole sample: the horizontal and vertical thresholds are applied together
        // and must come from the same snapshot.
        final DriverBounds bounds = this.settings.current().driverBounds();
        final PhysicsDriver driver =
                difference(this.previousX, this.previousY, this.previousZ, this.previousYawDegrees,
                        x, y, z, yawDegrees, bounds);
        store(x, y, z, yawDegrees);
        return driver;
    }

    /** Samples and publishes in one step, which is all a per-tick region task needs. */
    public void sampleInto(
            @NotNull DriverHolder holder,
            double x,
            double y,
            double z,
            float yawDegrees
    ) {
        holder.publish(sample(x, y, z, yawDegrees));
    }

    /**
     * Drops the remembered sample, so the next one is treated as a first.
     *
     * <p>Call this when the model is rebound to another entity or teleported deliberately: the delta
     * across a rebind describes nothing physical.
     */
    public void reset() {
        this.seeded = false;
    }

    /** Whether a position has been remembered yet. */
    public boolean isSeeded() {
        return this.seeded;
    }

    /**
     * The driver described by moving from one position and yaw to another, teleports removed.
     *
     * <p>Stateless, so it is the form worth testing and the form worth calling when the caller already
     * tracks the previous sample itself.
     *
     * <p>Translation is projected into the owner's own frame using the <em>current</em> yaw — forward
     * along the facing, side to the left — so that a model turning on the spot reports a yaw rate and no
     * translation, which is what {@link PhysicsDriver} documents. A non-finite input yields
     * {@link PhysicsDriver#STILL}: there is no sensible driver for a NaN coordinate, and letting the
     * record's own finiteness check throw here would propagate a server-side bug into the region thread
     * that merely read the position.
     *
     * @param bounds the teleport thresholds to filter against, passed explicitly so this form has no
     *               hidden configuration of its own
     */
    public static @NotNull PhysicsDriver difference(
            double previousX,
            double previousY,
            double previousZ,
            float previousYawDegrees,
            double x,
            double y,
            double z,
            float yawDegrees,
            @NotNull DriverBounds bounds
    ) {
        final double deltaX = x - previousX;
        final double deltaY = y - previousY;
        final double deltaZ = z - previousZ;
        final float yawDelta = wrapDegrees(yawDegrees - previousYawDegrees);
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || !Float.isFinite(yawDelta)) {
            return PhysicsDriver.STILL;
        }

        // Minecraft yaw grows clockwise from +Z, so facing = (-sin, 0, cos) and the owner's left —
        // which is what sideSpeed is measured along — is (cos, 0, sin).
        final double heading = Math.toRadians(yawDegrees);
        final double sin = Math.sin(heading);
        final double cos = Math.cos(heading);

        final float forward = (float) (-deltaX * sin + deltaZ * cos);
        final float side = (float) (deltaX * cos + deltaZ * sin);
        final float vertical = (float) deltaY;
        final float yawRate = (float) Math.toRadians(yawDelta);

        return new PhysicsDriver(forward, side, vertical, yawRate).withoutTeleports(bounds);
    }

    /** {@code degrees} folded into {@code -180..180}, i.e. the shorter of the two ways round. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360F;
        if (wrapped >= 180F) {
            wrapped -= 360F;
        } else if (wrapped < -180F) {
            wrapped += 360F;
        }
        return wrapped;
    }

    private void store(double x, double y, double z, float yawDegrees) {
        this.previousX = x;
        this.previousY = y;
        this.previousZ = z;
        this.previousYawDegrees = yawDegrees;
    }
}
