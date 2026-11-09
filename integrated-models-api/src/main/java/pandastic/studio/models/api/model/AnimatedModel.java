package pandastic.studio.models.api.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.animation.ModelAnimator;

public interface AnimatedModel<BaseEntityType extends Entity> extends Model<BaseEntityType> {

    @NotNull ModelAnimator<BaseEntityType> getAnimator();
}
