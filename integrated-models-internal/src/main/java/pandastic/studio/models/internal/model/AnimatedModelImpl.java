package pandastic.studio.models.internal.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.AnimatedModel;
import pandastic.studio.models.internal.IntegratedModelsPlugin;
import pandastic.studio.models.internal.model.component.animation.ModelAnimatorImpl;

public class AnimatedModelImpl<BaseEntityType extends Entity> extends ModelImpl<BaseEntityType> implements AnimatedModel<BaseEntityType>{

    private final @NotNull ModelAnimatorImpl<BaseEntityType> modelAnimator;

    protected AnimatedModelImpl(@NotNull Class<BaseEntityType> baseEntityTypeClass, @NotNull String modelId, @NotNull IntegratedModelsPlugin plugin) {
        super(baseEntityTypeClass, modelId, plugin);
        this.modelAnimator = new ModelAnimatorImpl<>(this);
    }

    @Override
    public @NotNull ModelAnimatorImpl<BaseEntityType> getAnimator() {
        return modelAnimator;
    }
}
