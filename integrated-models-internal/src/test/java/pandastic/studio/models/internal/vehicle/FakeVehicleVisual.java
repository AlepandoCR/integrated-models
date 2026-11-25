package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A model that records its lifecycle instead of rendering one.
 *
 * <p>Shares the host's journal, which is the point: the assertions that matter about a visual are about
 * <em>when</em> it is released relative to the control loop being cancelled and the rider being put down,
 * and an ordering assertion across two objects needs one sequence.
 */
final class FakeVehicleVisual implements VehicleVisual {

    private final @NotNull List<String> journal;

    private boolean alive;
    private boolean refuseSpawn;
    private boolean throwOnSpawn;
    private int despawnCount;

    /**
     * The locators of the model this visual stands in for.
     *
     * <p>{@link SeatLocators#NONE} by default, which is a model with no locator in it — the state every
     * model in this repository is in today, and therefore the right default for a test that is not about
     * seats.
     */
    private @NotNull SeatLocators locators = SeatLocators.NONE;

    FakeVehicleVisual(@NotNull List<String> journal) {
        this.journal = journal;
    }

    @Override
    public boolean spawn() {
        this.journal.add("visual.spawn");
        if (this.throwOnSpawn) {
            // What a rig whose bone names have gone stale does, and the failure the start order is
            // arranged to make cheap: nothing is scheduled yet.
            throw new IllegalStateException("rig cannot be bound");
        }
        if (this.refuseSpawn) {
            return false;
        }
        this.alive = true;
        return true;
    }

    @Override
    public void despawn() {
        this.journal.add("visual.despawn");
        this.despawnCount++;
        this.alive = false;
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    @Override
    public @NotNull SeatLocators locators() {
        return this.locators;
    }

    /** Gives this visual's model some locators to resolve seats against. */
    FakeVehicleVisual locators(@NotNull SeatLocators newLocators) {
        this.locators = newLocators;
        return this;
    }

    FakeVehicleVisual refuseSpawn() {
        this.refuseSpawn = true;
        return this;
    }

    FakeVehicleVisual throwOnSpawn() {
        this.throwOnSpawn = true;
        return this;
    }

    int despawnCount() {
        return this.despawnCount;
    }
}
