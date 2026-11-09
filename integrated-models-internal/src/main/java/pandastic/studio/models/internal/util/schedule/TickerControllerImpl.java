package pandastic.studio.models.internal.util.schedule;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.util.schedule.Ticker;
import pandastic.studio.models.api.util.schedule.TickerController;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public abstract class TickerControllerImpl<TaskType> implements TickerController<TaskType>{

    protected @Nullable TaskType task;

    private final @NotNull Map<Ticker<TaskType>, Boolean> tickers;

    protected TickerControllerImpl() {
        tickers = new ConcurrentHashMap<>();
    }

    @Override
    public void startTicker(@NotNull Ticker<TaskType> ticker) {
        tickers.put(ticker, true);
    }

    @Override
    public void stopTicker(@NotNull Ticker<TaskType> ticker) {
        tickers.put(ticker, false);
    }

    @Override
    public @NotNull Collection<Ticker<TaskType>> getTickers() {
        return tickers.keySet();
    }

    public @NotNull Collection<Ticker<TaskType>> getRunningTickers(){
        return tickers.keySet()
                .stream()
                .filter(tickers::get)
                .toList();
    }

    @Override
    public void addTicker(@NotNull Ticker<TaskType> ticker) {
        this.tickers.put(ticker, true);
    }

    @Override
    public void removeTicker(@NotNull Ticker<TaskType> ticker) {
        this.tickers.remove(ticker);
    }

    @Override
    public @NotNull Optional<TaskType> getTask() {
        return Optional.ofNullable(this.task);
    }
    protected void setTask(@Nullable TaskType task, @NotNull TickerControllerTaskCleanup<TaskType> cleanup){
        if (this.task != null) {
            cleanup.onCleanup(this.task);
        }

        this.task = task;
    }

    @FunctionalInterface
    public interface TickerControllerTaskCleanup<TaskType>{
        void onCleanup(@NotNull TaskType task);
    }
}
