package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A seat carrier the test drives by hand.
 *
 * <h2>What it models faithfully, because the real thing does</h2>
 * <ul>
 *   <li><strong>A rider can leave without anybody being told.</strong> {@link #seat(Player)} puts
 *       somebody in or takes them out behind the seating layer's back, which is what holding sneak,
 *       disconnecting and dying all look like from here.</li>
 *   <li><strong>A relocation completes later, on another thread, and may drop the rider.</strong> By
 *       default it is left pending; {@link #completeTeleport(boolean, boolean)} is the test standing in
 *       for the destination region, and the second flag is the dismount the platform is entitled to.</li>
 *   <li><strong>Ownership is a property of the calling thread.</strong> A flag here, because a test
 *       cannot own a region, but switchable at any moment so that a step can be asserted to decline to
 *       touch a carrier that is briefly somebody else's.</li>
 * </ul>
 *
 * <p>Every world-touching call appends to the host's journal, so an ordering assertion across the host,
 * the visual and the carriers reads one sequence.
 */
final class FakeSeatCarrier implements SeatCarrier {

    private final @NotNull List<String> journal;
    private final @NotNull String name;

    private boolean alive = true;
    private boolean owned = true;
    private boolean localMove = true;
    private boolean clientSteered;
    private boolean refuseMount;

    private double x;
    private double y;
    private double z;
    private float yaw;

    private @Nullable Player rider;
    private @Nullable VehicleHost.BooleanSink pendingTeleport;
    private int moves;
    private int teleports;
    private int releases;

    FakeSeatCarrier(
            @NotNull List<String> journal,
            @NotNull String name,
            double x,
            double y,
            double z,
            float yaw
    ) {
        this.journal = journal;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return this.owned;
    }

    @Override
    public boolean readInto(@NotNull SeatSample sample) {
        if (!this.alive) {
            return false;
        }
        sample.set(this.x, this.y, this.z);
        return true;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ, float yawDegrees) {
        this.x += deltaX;
        this.y += deltaY;
        this.z += deltaZ;
        this.yaw = yawDegrees;
        this.moves++;
        this.journal.add(this.name + ".moveLocal");
        return true;
    }

    @Override
    public boolean supportsLocalMove() {
        return this.localMove;
    }

    @Override
    public void teleport(
            double toX,
            double toY,
            double toZ,
            float yawDegrees,
            @NotNull VehicleHost.BooleanSink whenDone
    ) {
        this.x = toX;
        this.y = toY;
        this.z = toZ;
        this.yaw = yawDegrees;
        this.teleports++;
        this.pendingTeleport = whenDone;
        this.journal.add(this.name + ".teleport");
    }

    @Override
    public boolean mount(@NotNull Player newRider) {
        if (this.refuseMount) {
            return false;
        }
        this.rider = newRider;
        this.journal.add(this.name + ".mount");
        return true;
    }

    @Override
    public @Nullable Player rider() {
        return this.rider;
    }

    @Override
    public boolean isClientSteered(@NotNull Player candidate) {
        return this.clientSteered;
    }

    @Override
    public void release() {
        this.releases++;
        this.rider = null;
        this.alive = false;
        this.journal.add(this.name + ".release");
    }

    @Override
    public @NotNull String toString() {
        return "FakeSeatCarrier[" + this.name + "]";
    }

    // ------------------------------------------------------------------ test control

    /** Puts somebody in the seat, or takes them out, without the seating layer being told. */
    void seat(@Nullable Player newRider) {
        this.rider = newRider;
    }

    /** Completes a pending relocation, optionally having dropped the rider as the platform may. */
    void completeTeleport(boolean succeeded, boolean droppedTheRider) {
        final VehicleHost.BooleanSink sink = this.pendingTeleport;
        if (sink == null) {
            throw new IllegalStateException("No relocation is in flight for " + this.name);
        }
        this.pendingTeleport = null;
        if (droppedTheRider) {
            this.rider = null;
        }
        sink.accept(succeeded);
    }

    FakeSeatCarrier owned(boolean value) {
        this.owned = value;
        return this;
    }

    FakeSeatCarrier alive(boolean value) {
        this.alive = value;
        return this;
    }

    FakeSeatCarrier localMove(boolean value) {
        this.localMove = value;
        return this;
    }

    FakeSeatCarrier clientSteered(boolean value) {
        this.clientSteered = value;
        return this;
    }

    FakeSeatCarrier refuseMount(boolean value) {
        this.refuseMount = value;
        return this;
    }

    double x() {
        return this.x;
    }

    double y() {
        return this.y;
    }

    double z() {
        return this.z;
    }

    float yaw() {
        return this.yaw;
    }

    int moves() {
        return this.moves;
    }

    int teleports() {
        return this.teleports;
    }

    int releases() {
        return this.releases;
    }

    boolean hasPendingTeleport() {
        return this.pendingTeleport != null;
    }
}
