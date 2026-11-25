package pandastic.studio.models.internal.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.model.ModelManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one registry every model is in, keyed by {@link Model#getUniqueIdentifier()}.
 *
 * <p>Filled by the three binders rather than by the models themselves: a binder is already the thing that
 * hands a handle out and is already told when one tears down, so making it register is one line in each
 * and no new bookkeeping anywhere. See {@code ModelBinderImpl}, {@code DecorationBinderImpl} and
 * {@code VehicleBinderImpl}, each of which adds on bind and removes through the same {@code onStopped}
 * callback that maintains its own live set.
 *
 * <h2>Why a ConcurrentHashMap and not a synchronized map</h2>
 * Writers are region threads — a model is bound and torn down from whichever thread owns its anchor, and
 * the two can be different threads at the same instant — while readers include the two off-tick loops and
 * any plugin on any thread. A lock here would put every bind behind the same monitor as every read for no
 * benefit, since nothing in this class needs two operations to be atomic with respect to each other.
 *
 * <p>Every read copies. {@code values()} is a live, weakly consistent view that supports removal, and
 * handing one out would let a caller iterate the registry while a region thread is binding into it, or
 * mutate it by accident. A copy is also what makes the "never a live view" promise in
 * {@link ModelManager} true rather than aspirational.
 */
public final class ModelManagerImpl implements ModelManager {

    private final @NotNull ConcurrentHashMap<@NotNull UUID, @NotNull Model> models =
            new ConcurrentHashMap<>();

    @Override
    public @NotNull Collection<Model> getModels() {
        return List.copyOf(this.models.values());
    }

    @Override
    public @NotNull Optional<Model> getModel(@NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");
        return Optional.ofNullable(this.models.get(uniqueIdentifier));
    }

    /**
     * {@inheritDoc}
     *
     * <p>No unchecked cast: {@link Class#cast} does the narrowing the old entity-type filter needed a
     * suppression for, because the question is now about the model's own type rather than about a type
     * parameter the registry never stored.
     */
    @Override
    public @NotNull <ModelType extends Model> Collection<ModelType> getModelsByType(
            @NotNull Class<ModelType> modelType
    ) {
        Objects.requireNonNull(modelType, "modelType");
        final List<ModelType> matching = new ArrayList<>();
        for (final Model model : this.models.values()) {
            if (modelType.isInstance(model)) {
                matching.add(modelType.cast(model));
            }
        }
        return List.copyOf(matching);
    }

    @Override
    public @NotNull Collection<Model> getModelsByEntityType(@NotNull Class<? extends Entity> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        final List<Model> matching = new ArrayList<>();
        for (final Model model : this.models.values()) {
            // The entity itself, not a declared type parameter: a model's entity is what a caller asking
            // this question is actually filtering on, and it is the only form the three rig kinds have.
            if (model.getEntity().filter(entityType::isInstance).isPresent()) {
                matching.add(model);
            }
        }
        return List.copyOf(matching);
    }

    @Override
    public @NotNull Collection<Model> getModelsByEntity(@NotNull UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        final List<Model> matching = new ArrayList<>();
        for (final Model model : this.models.values()) {
            if (model.getEntity()
                    .filter(entity -> entity.getUniqueId().equals(entityId))
                    .isPresent()) {
                matching.add(model);
            }
        }
        return List.copyOf(matching);
    }

    @Override
    public void addModel(@NotNull Model model) {
        Objects.requireNonNull(model, "model");
        this.models.put(model.getUniqueIdentifier(), model);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes by identity as well as by key: a model whose entry has already been replaced by another
     * model under the same id must not be able to evict its successor. Reaching that state would take a
     * caller registering two models with one identifier, which {@link #addModel} permits, so the two-arg
     * removal costs nothing and closes it.
     */
    @Override
    public void removeModel(@NotNull Model model) {
        Objects.requireNonNull(model, "model");
        this.models.remove(model.getUniqueIdentifier(), model);
    }

    @Override
    public int size() {
        return this.models.size();
    }
}
