package pandastic.studio.models.internal.model.component;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.internal.model.ModelImpl;

public abstract class ModelComponentImpl<BaseEntityType extends Entity> implements ModelComponent<BaseEntityType> {

    protected final @NotNull ModelImpl<BaseEntityType> model;

    public ModelComponentImpl(@NotNull ModelImpl<BaseEntityType> model) {
        this.model = model;
    }

    @Override
    public @NotNull ModelImpl<BaseEntityType> getModel() {
        return model;
    }
}
