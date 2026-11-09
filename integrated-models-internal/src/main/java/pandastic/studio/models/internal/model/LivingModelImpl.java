package pandastic.studio.models.internal.model;

import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.LivingModel;
import pandastic.studio.models.internal.IntegratedModelsPlugin;

public abstract class LivingModelImpl<BaseLivingEntityType extends LivingEntity> extends AnimatedModelImpl<BaseLivingEntityType> implements LivingModel<BaseLivingEntityType>{
    protected LivingModelImpl(@NotNull Class<BaseLivingEntityType> baseLivingEntityTypeClass, @NotNull String modelId, @NotNull IntegratedModelsPlugin plugin) {
        super(baseLivingEntityTypeClass, modelId, plugin);
    }
}
