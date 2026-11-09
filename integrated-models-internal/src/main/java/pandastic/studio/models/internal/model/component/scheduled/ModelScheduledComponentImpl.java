package pandastic.studio.models.internal.model.component.scheduled;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.scheduled.ModelScheduledComponent;
import pandastic.studio.models.api.util.schedule.TickerController;
import pandastic.studio.models.internal.model.ModelImpl;
import pandastic.studio.models.internal.model.component.ModelComponentImpl;

public abstract class ModelScheduledComponentImpl<BaseEntityType extends Entity> extends ModelComponentImpl<BaseEntityType> implements ModelScheduledComponent<BaseEntityType> {

    public ModelScheduledComponentImpl(@NotNull ModelImpl<BaseEntityType> model) {
        super(model);
    }

    /**
     * Called every tick of the owning model's entity-scheduler task while the model is spawned.
     * {@link #onStart()} and {@link #onStop()} are optional hooks — override them only if needed.
     */
    @Override
    public abstract void onTick();

    /**
     * Final: a scheduled component's controller is always its owning model.
     */
    @Override
    public final @NotNull TickerController<ScheduledTask> getController() {
        return this.model;
    }
}
