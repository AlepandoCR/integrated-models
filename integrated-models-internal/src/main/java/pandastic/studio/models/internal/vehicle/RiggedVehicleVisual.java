package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.RiggedModel;

import java.util.Objects;

/**
 * A {@link VehicleVisual} backed by a rigged model bound to the vehicle's root entity.
 *
 * <p>Three delegating methods, and the reason it is a class rather than a method reference is that the
 * handle is single use: a rigged model that has despawned refuses to spawn again, so the vehicle layer
 * must never try — and the only way to be sure of that is for the thing holding the handle to be the
 * thing that owns its lifecycle.
 *
 * <p>Note what it does <em>not</em> do: drive anything. The model follows the root entity through the
 * binding layer's own per-tick sampler, which is the single writer into the pose pipeline; see
 * {@link VehicleVisual} for why that is deliberate.
 */
public final class RiggedVehicleVisual implements VehicleVisual {

    private final @NotNull RiggedModel model;

    /**
     * @param model the rigged model bound to this vehicle's root entity, not yet spawned
     */
    public RiggedVehicleVisual(@NotNull RiggedModel model) {
        this.model = Objects.requireNonNull(model, "model");
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

    /** The model behind this visual, so a caller can reach the rig it drives. */
    public @NotNull RiggedModel model() {
        return this.model;
    }

    @Override
    public @NotNull String toString() {
        return "RiggedVehicleVisual[" + this.model + "]";
    }
}
