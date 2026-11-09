package pandastic.studio.models.internal.model.component.event;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.event.ModelEventComponent;
import pandastic.studio.models.internal.model.ModelImpl;
import pandastic.studio.models.internal.model.component.ModelComponentImpl;

public abstract class ModelEventComponentImpl<BaseEntityType extends Entity, EventType extends Event> extends ModelComponentImpl<BaseEntityType> implements ModelEventComponent<BaseEntityType, EventType> {

    public ModelEventComponentImpl(@NotNull ModelImpl<BaseEntityType> model) {
        super(model);
    }
}
