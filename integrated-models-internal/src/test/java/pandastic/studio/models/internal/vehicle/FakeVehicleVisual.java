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
    private int rotationCount;
    private float pitchDegrees;
    private float yawDegrees;
    private float rollDegrees;

    /**
     * The locators of the model this visual stands in for.
     *
     * <p>{@link SeatLocators#NONE} by default, which is a model with no locator in it — the state every
     * model in this repository is in today, and therefore the right default for a test that is not about
     * seats.
     */
    private @NotNull SeatLocators locators = SeatLocators.NONE;

    /**
     * Whether anybody can see this model.
     *
     * <p>True by default, which is both the seam's own default and the state every test that is not
     * about culling wants: a vehicle nobody holds reads no blocks, so a fixture that defaulted this to
     * false would silently switch the terrain reaction off in every other suite.
     */
    private boolean hasViewers = true;

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

    /**
     * {@inheritDoc}
     *
     * <p>Records all three angles including the roll, which the shipped backend drops. A test asserting
     * on the roll is asserting that the layer computed and delivered it, which is the part that is this
     * project's to get right.
     */
    @Override
    public void setBodyRotation(float pitchDegrees, float yawDegrees, float rollDegrees) {
        this.journal.add("visual.rotate");
        this.rotationCount++;
        this.pitchDegrees = pitchDegrees;
        this.yawDegrees = yawDegrees;
        this.rollDegrees = rollDegrees;
    }

    @Override
    public boolean hasViewers() {
        return this.hasViewers;
    }

    @Override
    public @NotNull SeatLocators locators() {
        return this.locators;
    }

    /** Takes every viewer away from this model, as the culler does to one nobody is near. */
    FakeVehicleVisual hasViewers(boolean value) {
        this.hasViewers = value;
        return this;
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

    /** How many rotations were pushed, which is what the emission gate is asserted through. */
    int rotationCount() {
        return this.rotationCount;
    }

    /** Forgets the pushes so far, for a test whose subject is one step rather than a whole life. */
    void clearRotations() {
        this.rotationCount = 0;
    }

    float pitchDegrees() {
        return this.pitchDegrees;
    }

    float yawDegrees() {
        return this.yawDegrees;
    }

    /** The roll this layer decided, whether or not a backend could show it. */
    float rollDegrees() {
        return this.rollDegrees;
    }
}
