package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.spi.ModelRenderBackends;

/**
 * Where a vehicle's seat locators come from: the live model standing on its root entity.
 *
 * <h2>Why a seam over the renderer rather than a call into it</h2>
 * The same division of labour {@link pandastic.studio.models.internal.rig.ModelBoneSource} follows. The
 * production answer is one delegation to the installed backend, and every <em>decision</em> about seats —
 * which one a rider is offered, what a seat that resolved to a bone means, what gets logged — lives above
 * this interface, where a test can drive it with no renderer and no server anywhere.
 *
 * <p>It is keyed on the entity and the model id rather than on a binding handle on purpose: a renderer
 * keeps its own registry of what is standing on an entity, so this answers for the model that is actually
 * there. A vehicle whose visual somebody else spawned is therefore seatable, and a vehicle whose visual
 * failed to spawn resolves to {@link SeatLocators#NONE} rather than to a stale handle.
 *
 * <h2>Threading</h2>
 * Reads the live model, so it is confined to the thread that owns {@code root} — in practice the body of
 * a task the vehicle's {@link VehicleHost} dispatched, which is where a vehicle resolves its seats.
 */
@FunctionalInterface
public interface SeatLocatorSource {

    /**
     * The locators of {@code modelId} as it is currently rendered on {@code root}.
     *
     * @param root    the vehicle's root entity, owned by the calling thread
     * @param modelId the model the rig names
     * @return the locators, or {@link SeatLocators#NONE} if the renderer has nothing of that model on
     *         that entity — which is a fact about the world rather than a fault, and is why it is not an
     *         exception
     */
    @NotNull SeatLocators locatorsOf(@NotNull Entity root, @NotNull String modelId);

    /** A source that finds no locators, for a vehicle that carries no model. */
    @NotNull SeatLocatorSource NONE = (root, modelId) -> SeatLocators.NONE;

    /**
     * The production source: the installed rendering backend.
     *
     * <p>Reaches {@link ModelRenderBackends} for the reason every other {@code renderer()} factory in
     * this project does — see that class's note on why a process-wide holder is acceptable for the
     * production side of a seam whose test side takes an explicit value.
     */
    static @NotNull SeatLocatorSource renderer() {
        return (root, modelId) -> ModelRenderBackends.require().seatLocators(root, modelId);
    }
}
