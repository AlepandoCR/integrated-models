package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.RiggedModel;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link VehicleVisual} backed by a rigged model bound to the vehicle's root entity.
 *
 * <p>Four delegating methods, and the reason it is a class rather than a method reference is that the
 * handle is single use: a rigged model that has despawned refuses to spawn again, so the vehicle layer
 * must never try — and the only way to be sure of that is for the thing holding the handle to be the
 * thing that owns its lifecycle.
 *
 * <p>Note what it does <em>not</em> do: drive anything. The model follows the root entity through the
 * binding layer's own per-tick sampler, which is the single writer into the pose pipeline; see
 * {@link VehicleVisual} for why that is deliberate.
 *
 * <h2>The seats come from here because the model does</h2>
 * {@link #locators()} is the one method that is not a delegation. A seat is a locator of the model this
 * visual spawned, so the place that knows which model is standing on which entity is the place that can
 * ask the renderer about it — which it does through {@link SeatLocatorSource}, keyed on the entity and
 * the model id rather than on a handle, so that the answer is about what is actually rendered.
 */
public final class RiggedVehicleVisual implements VehicleVisual {

    private final @NotNull RiggedModel model;
    private final @NotNull SeatLocatorSource locators;

    /**
     * @param model the rigged model bound to this vehicle's root entity, not yet spawned
     * @param locators where this vehicle's seat locators are read from, usually
     *                 {@link SeatLocatorSource#renderer()}
     */
    public RiggedVehicleVisual(
            @NotNull RiggedModel model,
            @NotNull SeatLocatorSource locators
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.locators = Objects.requireNonNull(locators, "locators");
    }

    @Override
    public boolean spawn() {
        return this.model.spawn();
    }

    @Override
    public void despawn() {
        this.model.despawn();
    }

    @Override
    public boolean isAlive() {
        return this.model.isAlive();
    }

    @Override
    public @NotNull SeatLocators locators() {
        // Empty when the model is bound to something that is not an entity, which cannot happen for a
        // vehicle but is a legal shape of a rigged model. Answering NONE rather than throwing keeps the
        // seat chart the place that reports it.
        final Optional<Entity> entity = this.model.getEntity();
        if (entity.isEmpty()) {
            return SeatLocators.NONE;
        }
        return this.locators.locatorsOf(entity.get(), this.model.getRig().getModelId());
    }

    /** The model behind this visual, so a caller can reach the rig it drives. */
    public @NotNull RiggedModel model() {
        return this.model;
    }

    @Override
    public @NotNull String toString() {
        return "RiggedVehicleVisual[" + this.model + "]";
    }
}
