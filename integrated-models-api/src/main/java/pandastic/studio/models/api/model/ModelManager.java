package pandastic.studio.models.api.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public interface ModelManager {

    @NotNull Collection<Model<?>> getModels();

    <BaseEntityType extends Entity> @NotNull Collection<Model<BaseEntityType>> getModelsByEntityType(@NotNull Class<BaseEntityType> entityType);

    void addModel(@NotNull Model<?> model);

    void removeModel(@NotNull Model<?> model);

}
