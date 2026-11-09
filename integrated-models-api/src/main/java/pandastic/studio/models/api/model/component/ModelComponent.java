package pandastic.studio.models.api.model.component;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.Model;

public interface ModelComponent<BaseEntityType extends Entity> {

    @NotNull Model<BaseEntityType> getModel();

}
