package pandastic.studio.models.internal.util.schedule;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.util.schedule.Ticker;
import pandastic.studio.models.api.util.schedule.TickerController;

/**
 * Base for standalone tickers whose controller must be stored as a field —
 * unlike {@link pandastic.studio.models.api.model.component.scheduled.ModelScheduledComponent},
 * which derives its controller from its owning model.
 */
public abstract class TickerImpl<TaskType> implements Ticker<TaskType>{

    private final @NotNull TickerController<TaskType> controller;

    public TickerImpl(@NotNull TickerController<TaskType> controller) {
        this.controller = controller;
    }

    @Override
    public @NotNull TickerController<TaskType> getController() {
        return this.controller;
    }
}
