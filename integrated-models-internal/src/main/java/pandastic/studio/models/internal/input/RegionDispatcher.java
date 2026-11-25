package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * How a handler gets from the thread that saw an input to the thread that may act on it.
 *
 * <h2>Why the entity scheduler and nothing else</h2>
 * A handler can be dispatched from a network thread, which owns nothing. Anything it wants to do to the
 * player has to run on the region that owns the player, and on a regionised server there is exactly one
 * correct dispatcher for that: the player's own {@code EntityScheduler}. It runs the task on whichever
 * region owns the player <em>at the time it runs</em>, which is what makes it still correct after the
 * player has walked across a region boundary in the meantime, and it drops the task if the player is
 * gone — which is the behaviour a handler wants rather than an exception it cannot handle.
 *
 * <p>The global region scheduler would run the task on a thread that owns no player; the main
 * scheduler does not exist; a region scheduler for a position would be right only until the player
 * moved. This project's ticking rules say the same thing for models, and the reason is identical.
 *
 * <h2>Why it is a seam</h2>
 * So the dispatcher is testable. A test exercising a handler wants the task to run, and it has no
 * server to run it on; {@link #inline()} makes the hop a direct call and leaves the dispatch logic
 * under test rather than the platform.
 */
@FunctionalInterface
public interface RegionDispatcher {

    /**
     * Where a task touching {@code player} has to run.
     *
     * @param player the player the task is about
     * @return an executor that runs a task on that player's owning region
     */
    @NotNull Executor forPlayer(@NotNull Player player);

    /**
     * The production dispatcher: each player's own entity scheduler.
     *
     * @param plugin the plugin the tasks are scheduled for
     * @return the dispatcher
     */
    static @NotNull RegionDispatcher entityScheduler(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return player -> task -> player.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    /**
     * A dispatcher that runs the task on the calling thread.
     *
     * <p>For tests only. In production this would run world-touching code on a network thread, which
     * is the single thing this type exists to prevent.
     *
     * @return the dispatcher
     */
    static @NotNull RegionDispatcher inline() {
        return player -> Runnable::run;
    }
}
