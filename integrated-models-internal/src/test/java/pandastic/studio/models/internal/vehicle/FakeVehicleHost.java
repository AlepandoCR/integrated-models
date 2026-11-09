package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.vehicle.RiderIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A root entity the test drives by hand.
 *
 * <h2>What it models faithfully, because the real thing does</h2>
 * <ul>
 *   <li><strong>Scheduling can fail.</strong> {@link #repeat} answers null once the root is gone, which
 *       is the platform's documented behaviour and the one branch where nothing will ever tear a vehicle
 *       down unless its creator unwinds by hand.</li>
 *   <li><strong>Ownership is a property of the calling thread, not of the object.</strong> It is a flag
 *       here, because a test cannot own a region — but it is readable and writable at any moment, so a
 *       test can assert that an entry point refuses before it does anything.</li>
 *   <li><strong>A teleport completes later, on another thread.</strong> By default it is left pending,
 *       which is the state a vehicle must survive. {@link #completeTeleport(boolean)} is the test
 *       standing in for the destination region.</li>
 *   <li><strong>Ejecting answers whether there was anybody.</strong> Which is how the teardown can be
 *       asserted to have put a rider down rather than merely to have tried.</li>
 * </ul>
 *
 * <h2>The journal</h2>
 * Every world-touching call appends a line. Ordering is the thing most of this layer's correctness is
 * about — heading before translation, cancel before release, dismount before the visual — and an
 * ordering assertion needs a sequence rather than a set of counters.
 */
final class FakeVehicleHost implements VehicleHost {

    private final @NotNull UUID rootId = UUID.randomUUID();
    private final @NotNull List<String> journal = new ArrayList<>();

    private boolean owned = true;
    private boolean alive = true;
    private boolean localMove = true;
    private boolean clientSteered;
    private boolean refuseMount;
    private boolean refuseSubmit;
    private boolean refuseSchedule;

    private double x;
    private double y;
    private double z;
    private float yaw;

    private @Nullable Player rider;
    private @NotNull RiderIntent intent = RiderIntent.NONE;

    private @Nullable Runnable body;
    private @Nullable Runnable retired;
    private @Nullable FakeTask task;
    private long period;
    private int scheduleCount;
    private final @NotNull List<Runnable> submitted = new ArrayList<>();
    private @Nullable BooleanSink pendingTeleport;

    @Override
    public @NotNull UUID rootId() {
        return this.rootId;
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
    public boolean readInto(@NotNull VehicleSample sample) {
        if (!this.alive) {
            return false;
        }
        sample.set(this.x, this.y, this.z, this.yaw);
        return true;
    }

    @Override
    public @Nullable VehicleTask repeat(
            @NotNull Runnable newBody,
            @NotNull Runnable newRetired,
            long periodTicks
    ) {
        if (!this.alive || this.refuseSchedule) {
            // The platform's documented behaviour, and the branch where neither callback will ever run.
            // Separately switchable from liveness, because the window it models is the entity going
            // between the caller's own check and the dispatch — a state the fake cannot otherwise be in.
            return null;
        }
        this.body = newBody;
        this.retired = newRetired;
        this.period = periodTicks;
        this.scheduleCount++;
        final FakeTask scheduled = new FakeTask();
        this.task = scheduled;
        this.journal.add("repeat:" + periodTicks);
        return scheduled;
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        if (this.refuseSubmit) {
            return false;
        }
        this.submitted.add(work);
        this.journal.add("submit");
        return true;
    }

    @Override
    public boolean supportsLocalMove() {
        return this.localMove;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ) {
        this.x += deltaX;
        this.y += deltaY;
        this.z += deltaZ;
        this.journal.add("moveLocal");
        return true;
    }

    @Override
    public void setYaw(float yawDegrees) {
        this.yaw = yawDegrees;
        this.journal.add("setYaw:" + yawDegrees);
    }

    @Override
    public void teleport(
            double toX,
            double toY,
            double toZ,
            float yawDegrees,
            @NotNull BooleanSink whenDone
    ) {
        this.x = toX;
        this.y = toY;
        this.z = toZ;
        this.yaw = yawDegrees;
        this.pendingTeleport = whenDone;
        this.journal.add("teleport");
    }

    @Override
    public @Nullable Player rider() {
        return this.rider;
    }

    @Override
    public boolean mount(@NotNull Player newRider) {
        if (this.refuseMount) {
            return false;
        }
        this.rider = newRider;
        this.journal.add("mount");
        return true;
    }

    @Override
    public boolean dismount() {
        final boolean had = this.rider != null;
        this.rider = null;
        this.journal.add("dismount");
        return had;
    }

    @Override
    public boolean isClientSteered(@NotNull Player candidate) {
        return this.clientSteered;
    }

    @Override
    public @NotNull RiderIntent intentOf(@NotNull Player candidate) {
        return this.intent;
    }

    @Override
    public @NotNull String describe() {
        return "FakeRoot " + this.rootId;
    }

    // ------------------------------------------------------------------ test control

    /** Dispatches one control step, as the platform would. */
    void runOnce() {
        final Runnable scheduled = this.body;
        if (scheduled == null) {
            throw new IllegalStateException("Nothing has been scheduled");
        }
        if (this.task != null && this.task.cancelled) {
            throw new IllegalStateException(
                    "The task is cancelled; the platform would not dispatch it again");
        }
        scheduled.run();
    }

    /** Fires the platform's retired callback, as it would when the entity is removed. */
    void retire() {
        final Runnable callback = this.retired;
        this.alive = false;
        if (callback != null) {
            callback.run();
        }
    }

    /** Completes a pending relocation, standing in for the destination region. */
    void completeTeleport(boolean succeeded) {
        final BooleanSink sink = this.pendingTeleport;
        if (sink == null) {
            throw new IllegalStateException("No relocation is in flight");
        }
        this.pendingTeleport = null;
        sink.accept(succeeded);
    }

    boolean hasPendingTeleport() {
        return this.pendingTeleport != null;
    }

    /** Runs everything handed to {@link #submit}, as the owning thread would a tick later. */
    void drainSubmitted() {
        final List<Runnable> pending = List.copyOf(this.submitted);
        this.submitted.clear();
        pending.forEach(Runnable::run);
    }

    FakeVehicleHost owned(boolean value) {
        this.owned = value;
        return this;
    }

    FakeVehicleHost alive(boolean value) {
        this.alive = value;
        return this;
    }

    FakeVehicleHost localMove(boolean value) {
        this.localMove = value;
        return this;
    }

    FakeVehicleHost clientSteered(boolean value) {
        this.clientSteered = value;
        return this;
    }

    FakeVehicleHost refuseMount(boolean value) {
        this.refuseMount = value;
        return this;
    }

    FakeVehicleHost refuseSubmit(boolean value) {
        this.refuseSubmit = value;
        return this;
    }

    FakeVehicleHost refuseSchedule(boolean value) {
        this.refuseSchedule = value;
        return this;
    }

    FakeVehicleHost at(double newX, double newY, double newZ, float newYaw) {
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.yaw = newYaw;
        return this;
    }

    FakeVehicleHost holding(@NotNull RiderIntent newIntent) {
        this.intent = newIntent;
        return this;
    }

    /** Puts somebody in the seat without going through the vehicle, as the server's own path can. */
    void seat(@Nullable Player newRider) {
        this.rider = newRider;
    }

    double x() {
        return this.x;
    }

    double z() {
        return this.z;
    }

    float yaw() {
        return this.yaw;
    }

    long period() {
        return this.period;
    }

    int scheduleCount() {
        return this.scheduleCount;
    }

    boolean isTaskAlive() {
        return this.task != null && !this.task.cancelled;
    }

    @NotNull List<String> journal() {
        return List.copyOf(this.journal);
    }

    /**
     * The live journal, so a visual can append to the same sequence.
     *
     * <p>One sequence rather than two, because every assertion worth making about a visual is about when
     * it was released relative to what the host was doing.
     */
    @NotNull List<String> sharedJournal() {
        return this.journal;
    }

    void clearJournal() {
        this.journal.clear();
    }

    private static final class FakeTask implements VehicleTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
