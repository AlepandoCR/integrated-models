package pandastic.studio.models.api.model.component.scheduled;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.api.util.schedule.Ticker;
import pandastic.studio.models.api.util.schedule.TickerController;

/**
 * Scheduled components will be ticked by the base entity's scheduler {@link EntityScheduler} contained by the {@link pandastic.studio.models.api.util.schedule.TickerController} in {@link pandastic.studio.models.api.model.Model}
 * Entity schedulers ensure ticking only when the entity is available
 * @param <BaseEntityType>
 */
public interface ModelScheduledComponent<BaseEntityType extends Entity> extends ModelComponent<BaseEntityType>, Ticker<ScheduledTask> {

    /**
     * The owning model is its own {@link TickerController}, so the controller of a
     * scheduled component is always the model itself.
     */
    @Override
    default @NotNull TickerController<ScheduledTask> getController() {
        return getModel();
    }
}
