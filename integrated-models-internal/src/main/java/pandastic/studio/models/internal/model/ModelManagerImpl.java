package pandastic.studio.models.internal.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.model.ModelManager;
import pandastic.studio.models.internal.IntegratedModelsPlugin;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModelManagerImpl implements ModelManager{

    private final @NotNull ConcurrentHashMap<@NotNull UUID, @NotNull Model<?>> models;

    private final @NotNull IntegratedModelsPlugin plugin;

    public ModelManagerImpl(@NotNull IntegratedModelsPlugin plugin) {
        this.plugin = plugin;
        this.models = new ConcurrentHashMap<>();
    }

    @Override
    public @NotNull Collection<Model<?>> getModels() {
        return models.values();
    }

    @Override
    @SuppressWarnings("unchecked")
    public @NotNull <BaseEntityType extends Entity> Collection<Model<BaseEntityType>> getModelsByEntityType(@NotNull Class<BaseEntityType> entityType) {
        return getModels().stream()
                .filter(model -> entityType.isAssignableFrom(model.getBaseEntityType()))
                .map(model -> (Model<BaseEntityType>) model)
                .toList();
    }

    @Override
    public void addModel(@NotNull Model<?> model) {
        this.models.put(model.getUniqueIdentifier(), model);
    }

    @Override
    public void removeModel(@NotNull Model<?> model) {
        this.models.remove(model.getUniqueIdentifier());
    }
}
