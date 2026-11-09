package pandastic.studio.models.api.util.schedule;

import org.jetbrains.annotations.NotNull;

/**
 * Owned by a {@link TickerController}, will be ticked by the controller on each tick of the scheduled task
 * @param <TaskType>
 */
public interface Ticker<TaskType>{
    void onTick();

    default void onStop() {
    }

    default void onStart() {
    }

    @NotNull TickerController<TaskType> getController();

}
