package pandastic.studio.models.api.util.schedule;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Optional;

/**
 * Will own {@link Ticker} instances, controlling with a single scheduled task for all tickers
 * @param <TaskType>
 */
public interface TickerController<TaskType> {

    void start();

    void stop();
    void startTicker(@NotNull Ticker<TaskType> ticker);

    void stopTicker(@NotNull Ticker<TaskType> ticker);

    @NotNull Collection<Ticker<TaskType>> getTickers();

    void addTicker(@NotNull Ticker<TaskType> ticker);

    void removeTicker(@NotNull Ticker<TaskType> ticker);

    @NotNull Collection<Ticker<TaskType>>  getRunningTickers();

    @NotNull Optional<TaskType> getTask();
}
