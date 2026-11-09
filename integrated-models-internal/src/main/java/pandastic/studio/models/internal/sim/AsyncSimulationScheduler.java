package pandastic.studio.models.internal.sim;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link SimulationScheduler}: the platform async scheduler, and nothing else.
 *
 * <p>Deliberately the only class in this package that names a Bukkit type, and deliberately trivial —
 * everything with a decision in it lives in {@link SimulationLoop}, where a test can reach it.
 *
 * <h2>Why the async scheduler and not the others</h2>
 * {@code Bukkit.getScheduler()} throws outright on Folia, so it is not an option for a Folia-targeted
 * plugin. The region and global-region schedulers run <em>on</em> the tick, which is exactly what the
 * simulation is trying to stay off, and would also pin every model's physics to one region's thread.
 * The entity scheduler is the right dispatcher for anything that touches an entity — and the
 * simulation touches none, by construction: it reads a {@link DriverHolder} that a region thread
 * published into.
 *
 * <p>The async scheduler is also the only one <strong>usable at {@code onEnable()}</strong>. Its
 * executors are built in {@code CraftServer}'s constructor, before {@code Bukkit.setServer}, with no
 * start gate and no dependency on the server having ticked. The main scheduler, by contrast, only
 * dispatches once ticking begins — and plugins enable before the first tick, so a task posted there at
 * enable time waits.
 */
public final class AsyncSimulationScheduler implements SimulationScheduler {

    private final @NotNull Plugin plugin;
    private final @NotNull AsyncScheduler scheduler;

    /** Binds to the server's async scheduler. Safe to call from {@code onEnable()}; see the class doc. */
    public AsyncSimulationScheduler(@NotNull Plugin plugin) {
        this(plugin, Bukkit.getAsyncScheduler());
    }

    /** Visible for a caller that already holds the scheduler. */
    public AsyncSimulationScheduler(@NotNull Plugin plugin, @NotNull AsyncScheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public @NotNull SimulationTask runAtFixedRate(
            @NotNull Runnable body,
            long initialDelay,
            long period,
            @NotNull TimeUnit unit
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(unit, "unit");
        // The platform hands the body its own task handle; the loop has no use for it, since it keeps
        // the handle this method returns.
        return new PlatformTask(this.scheduler.runAtFixedRate(
                this.plugin, ignoredTask -> body.run(), initialDelay, period, unit));
    }

    /**
     * Adapts a platform task to the loop's two-method view.
     *
     * <p>{@code cancel()} returns a {@code CancelledState} describing whether an iteration was already
     * running. Discarded here on purpose: the loop cannot act on it without a way to join the in-flight
     * iteration, and pretending otherwise would hide the shutdown hazard documented on
     * {@link SimulationTask#cancel()} rather than state it.
     */
    private record PlatformTask(@NotNull ScheduledTask task) implements SimulationTask {

        @Override
        public void cancel() {
            this.task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return this.task.isCancelled();
        }
    }
}
