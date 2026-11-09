package pandastic.studio.models.internal;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.internal.vehicle.VehicleHost;
import pandastic.studio.models.internal.vehicle.VehicleSample;
import pandastic.studio.models.internal.vehicle.VehicleTask;

import java.util.UUID;

/**
 * A vehicle host that records its lifecycle events under a name.
 *
 * <p>Reports an alive root owned by the calling thread, supports an in-place move, and has no rider:
 * the vehicle layer's own refusals, authority model and control arithmetic are {@code VehicleImplTest}'s
 * and {@code VehicleStepperTest}'s subject, and duplicating them here would test them twice and the
 * wiring once. What this double is for is the control loop — that creating a vehicle through the
 * runtime dispatches one, that a reload re-dispatches it, and that a shutdown cancels it.
 */
final class RecordingVehicleHost implements VehicleHost {

    private final @NotNull RuntimeFixtures.Journal journal;
    private final @NotNull String name;
    private final @NotNull UUID rootId = RuntimeFixtures.id();

    private @Nullable Task task;
    private long periodTicks;
    private int repeatCount;

    RecordingVehicleHost(@NotNull RuntimeFixtures.Journal journal, @NotNull String name) {
        this.journal = journal;
        this.name = name;
    }

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
        sample.set(0D, 0D, 0D, 0F);
        return true;
    }

    @Override
    public @Nullable VehicleTask repeat(
            @NotNull Runnable body,
            @NotNull Runnable retired,
            long period
    ) {
        this.repeatCount++;
        this.periodTicks = period;
        final Task scheduled = new Task();
        this.task = scheduled;
        this.journal.record(this.name + "-repeat");
        return scheduled;
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        work.run();
        return true;
    }

    @Override
    public boolean supportsLocalMove() {
        return true;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ) {
        return true;
    }

    @Override
    public void setYaw(float yawDegrees) {
        // Intentionally empty: these tests never step a vehicle.
    }

    @Override
    public void teleport(
            double x,
            double y,
            double z,
            float yawDegrees,
            @NotNull BooleanSink whenDone
    ) {
        whenDone.accept(true);
    }

    @Override
    public @Nullable Player rider() {
        return null;
    }

    @Override
    public boolean mount(@NotNull Player rider) {
        return false;
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
        return RiderIntent.NONE;
    }

    @Override
    public @NotNull String describe() {
        return this.name;
    }

    /** The period the live control loop was dispatched at, which is what a reload moves. */
    long periodTicks() {
        return this.periodTicks;
    }

    /** How many control loops were dispatched. Two means a reload re-dispatched one. */
    int repeatCount() {
        return this.repeatCount;
    }

    boolean isControlCancelled() {
        return this.task == null || this.task.cancelled;
    }

    /** Inner rather than static, so a cancel lands in the same journal under the same name. */
    private final class Task implements VehicleTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            if (this.cancelled) {
                return;
            }
            this.cancelled = true;
            RecordingVehicleHost.this.journal.record(RecordingVehicleHost.this.name + "-cancel");
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
