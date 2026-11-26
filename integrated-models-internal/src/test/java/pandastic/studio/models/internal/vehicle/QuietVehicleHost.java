package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.vehicle.RiderIntent;

import java.util.UUID;

/**
 * A root entity that records nothing, for measuring what a control step allocates.
 *
 * <h2>Why {@link FakeVehicleHost} cannot be used for this</h2>
 * Because it journals. Every world-touching call appends a line, several of them built by concatenating a
 * float — {@code "probe:" + reachBlocks} — so a measured step reports the fixture's {@code StringBuilder}
 * and its growing list rather than the step. Measured against the journalling fake, one control step
 * appeared to allocate between 192 and 431 bytes; against this one the same step allocates what the step
 * allocates. An ordering fake and a measurement fake want opposite things, and conflating them produces a
 * figure that is wrong by an order of magnitude in the direction that hides a regression.
 *
 * <p>Everything here is therefore a field write or a counter. It models the two platform behaviours a
 * step's cost depends on — whether an in-place move is available, and whether a terrain probe finds a
 * surface — and nothing else.
 *
 * <p>Single-threaded, like the real thing: a host belongs to one region thread.
 */
final class QuietVehicleHost implements VehicleHost {

    private final @NotNull UUID rootId = UUID.randomUUID();

    private double x;
    private double y = 64D;
    private double z;
    private float yaw;

    private boolean localMove = true;
    private boolean terrainKnown;
    private double surfaceY;
    private double surfaceYAhead;

    private @NotNull RiderIntent intent = RiderIntent.NONE;
    private @Nullable Runnable body;
    private int probes;
    private int teleports;
    private int moves;

    /** Gives this host a surface to report, which turns the terrain path on. */
    @NotNull QuietVehicleHost terrain(double under, double ahead) {
        this.terrainKnown = true;
        this.surfaceY = under;
        this.surfaceYAhead = ahead;
        return this;
    }

    /** Whether an in-place move is available, i.e. whether the movement adapter resolved. */
    @NotNull QuietVehicleHost localMove(boolean available) {
        this.localMove = available;
        return this;
    }

    /** What the driver is holding, for a host whose intent comes from the movement fallback. */
    @NotNull QuietVehicleHost holding(@NotNull RiderIntent held) {
        this.intent = held;
        return this;
    }

    /** Runs the scheduled control step once, as the entity scheduler would. */
    void runOnce() {
        if (this.body != null) {
            this.body.run();
        }
    }

    int probes() {
        return this.probes;
    }

    int teleports() {
        return this.teleports;
    }

    int moves() {
        return this.moves;
    }

    void clearCounts() {
        this.probes = 0;
        this.teleports = 0;
        this.moves = 0;
    }

    // ---------------------------------------------------------------- the seam

    @Override
    public @NotNull UUID rootId() {
        return this.rootId;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return true;
    }

    @Override
    public boolean readInto(@NotNull VehicleSample sample) {
        sample.set(this.x, this.y, this.z, this.yaw);
        return true;
    }

    @Override
    public boolean probeTerrain(
            @NotNull TerrainSample sample,
            float reachBlocks,
            float headingDegrees
    ) {
        this.probes++;
        if (!this.terrainKnown) {
            sample.unavailable();
            return false;
        }
        sample.set(this.y, this.surfaceY, true, this.surfaceYAhead);
        return true;
    }

    @Override
    public @Nullable VehicleTask repeat(
            @NotNull Runnable scheduled,
            @NotNull Runnable retired,
            long periodTicks
    ) {
        this.body = scheduled;
        return new QuietTask();
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        work.run();
        return true;
    }

    @Override
    public boolean supportsLocalMove() {
        return this.localMove;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ) {
        this.moves++;
        this.x += deltaX;
        this.y += deltaY;
        this.z += deltaZ;
        return true;
    }

    @Override
    public void setYaw(float yawDegrees) {
        this.yaw = yawDegrees;
    }

    @Override
    public void teleport(
            double toX,
            double toY,
            double toZ,
            float yawDegrees,
            @NotNull BooleanSink whenDone
    ) {
        this.teleports++;
        this.x = toX;
        this.y = toY;
        this.z = toZ;
        this.yaw = yawDegrees;
        // Completed inline, which is what the platform does for a destination the current region already
        // owns. A pending completion would make every measured step the "a relocation is in flight" one.
        whenDone.accept(true);
    }

    @Override
    public boolean dismount() {
        return false;
    }

    @Override
    public boolean isClientSteered(@NotNull Player rider) {
        return false;
    }

    @Override
    public @NotNull RiderIntent intentOf(@NotNull Player rider) {
        return this.intent;
    }

    @Override
    public @NotNull String describe() {
        return "quiet-root";
    }

    /** A handle that does nothing: the measurement drives the body by hand. */
    private static final class QuietTask implements VehicleTask {

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
