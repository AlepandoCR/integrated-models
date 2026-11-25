package pandastic.studio.models.api.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Every model this plugin currently has in the world.
 *
 * <p>One registry for all four kinds, because they are all {@link Model}s. A
 * {@link pandastic.studio.models.api.binding.RiggedModel}, a
 * {@link pandastic.studio.models.api.decoration.Decoration} and a
 * {@link pandastic.studio.models.api.vehicle.Vehicle} are entered by their binder the moment it hands one
 * out, and remove themselves as the last step of their own teardown — whether that teardown came from the
 * consumer, from the platform noticing the entity is gone, or from the plugin disabling. So membership is
 * exactly "handed out and not yet despawned", and a model that stopped is gone from here without anybody
 * having had to remember to say so.
 *
 * <p>An {@link EntityModel} a consumer subclassed is <em>not</em> entered automatically: nothing in this
 * plugin constructs one, so there is no binder to do it. {@link #addModel(Model)} and
 * {@link #removeModel(Model)} are public for exactly that case.
 *
 * <p>One thing worth knowing before counting entries: a
 * {@link pandastic.studio.models.api.vehicle.Vehicle}'s visual <em>is</em> a
 * {@link pandastic.studio.models.api.binding.RiggedModel}, bound to the vehicle's own root entity, so a
 * vehicle contributes two entries — itself and the model it renders. That is not double counting; both
 * are real models with real lifetimes, and the visual is despawned by the vehicle's own teardown.
 * {@link #getModelsByType(Class)} with {@code Vehicle.class} is how to ask for the vehicles alone.
 *
 * <h2>Thread rules</h2>
 * Every method is safe from any thread, and has to be: models are bound from whichever region thread owns
 * their anchor and torn down from whichever thread noticed they were finished, while the off-tick loops
 * read models the whole time. Every collection returned is a <strong>snapshot</strong>, never a live view
 * — iterating a live view while a region thread binds a model is the kind of thing that works until it
 * does not.
 */
public interface ModelManager {

    /**
     * Every registered model.
     *
     * @return a snapshot; mutating it changes nothing
     */
    @NotNull Collection<Model> getModels();

    /**
     * The model with this identity.
     *
     * @param uniqueIdentifier the model's {@link Model#getUniqueIdentifier()}
     * @return the model, or empty if nothing by that id is registered
     */
    @NotNull Optional<Model> getModel(@NotNull UUID uniqueIdentifier);

    /**
     * Every registered model of one kind.
     *
     * <p>How to ask for "every vehicle" or "every decoration" without this interface growing a method per
     * kind.
     *
     * @param modelType the interface or class to filter by
     * @param <ModelType> the model type
     * @return a snapshot of the matching models
     */
    <ModelType extends Model> @NotNull Collection<ModelType> getModelsByType(
            @NotNull Class<ModelType> modelType);

    /**
     * Every registered model attached to an entity of this type.
     *
     * <p>Filtered on {@link Model#getEntity()}, so a model attached to nothing — a decoration — never
     * matches, and neither does a kind that keeps its entity to itself; see {@link Model#getEntity()}.
     *
     * @param entityType the entity class to filter by
     * @return a snapshot of the matching models
     */
    @NotNull Collection<Model> getModelsByEntityType(@NotNull Class<? extends Entity> entityType);

    /**
     * Every registered model attached to this exact entity.
     *
     * <p>Several is a normal answer: two models on one entity is a supported shape, which is why a model's
     * identity is its own rather than its entity's.
     *
     * @param entityId the entity's {@link Entity#getUniqueId()}
     * @return a snapshot of the models attached to it
     */
    @NotNull Collection<Model> getModelsByEntity(@NotNull UUID entityId);

    /**
     * Registers {@code model}, replacing any model already held under the same identity.
     *
     * @param model the model to register
     */
    void addModel(@NotNull Model model);

    /**
     * Unregisters {@code model}. A model that was never registered is ignored.
     *
     * @param model the model to unregister
     */
    void removeModel(@NotNull Model model);

    /** How many models are registered. */
    int size();
}
