package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.rig.Rig;

/**
 * Supplies the model a vehicle carries.
 *
 * <h2>Why a seam and not a direct call into the binding layer</h2>
 * Because the two layers have to be composable without being coupled, and because one of the three
 * production shapes does not involve the binding layer at all.
 *
 * <p>A vehicle whose visual is a rig gets one through {@link #binding}, which validates the rig against
 * its model and hands back a single-use handle. A vehicle whose root entity <em>is</em> what the player
 * sees — a prototype, or a construct of displays somebody else owns — gets {@link #none}. And a test gets
 * a substitute, which is the only way the start order, the unwind and the teardown of a vehicle can be
 * observed without a renderer.
 *
 * <p>Note that the visual is created per vehicle rather than shared, for the reason
 * {@link pandastic.studio.models.internal.binding.LocomotionStageSource} gives about gaits: a rig is
 * immutable and shared, while the handle driving one belongs to exactly one thing in the world.
 */
@FunctionalInterface
public interface VehicleVisualFactory {

    /**
     * A visual for {@code rig} riding on {@code root}, not yet spawned.
     *
     * <p>Called once per vehicle, before anything is scheduled, so a rig whose bone names have gone
     * stale fails at creation with every broken name in one message.
     *
     * @param rig  the rig to render
     * @param root the entity the model follows
     * @return the visual
     * @throws IllegalStateException if the rig cannot be bound to its model
     */
    @NotNull VehicleVisual visualFor(@NotNull Rig rig, @NotNull Entity root);

    /**
     * The production factory: a rigged model bound to the vehicle's root entity.
     *
     * <p>The model follows the root through the binding layer's own per-tick sampler, which is what makes
     * a vehicle's wheels turn and its legs swing without the vehicle layer driving anything; see
     * {@link VehicleVisual} for why that is the single-writer design rather than an omission.
     *
     * @param binder the binding layer's entry point
     */
    static @NotNull VehicleVisualFactory binding(@NotNull ModelBinder binder) {
        return (rig, root) -> new RiggedVehicleVisual(binder.bind(rig, root));
    }

    /** The factory for vehicles that carry no model. */
    @NotNull VehicleVisualFactory NONE = (rig, root) -> VehicleVisual.NONE;
}
