package pandastic.studio.models.api.model.component.event;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;

public interface ModelEventComponent<BaseEntityType extends Entity, EventType extends Event> extends ModelComponent<BaseEntityType> {

    void onEvent(@NotNull EventType eventType ,@NotNull BaseEntityType entity);

}
