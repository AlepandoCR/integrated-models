package pandastic.studio.models.internal.view;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads every online player's eye and look on the thread that owns them, and publishes each into a
 * {@link ViewerIndex}.
 *
 * <h2>This is the only region-thread work in the whole view layer</h2>
 * And it is unavoidable. On a regionised server a player's position may only be read from the thread
 * ticking the region that owns them; from anywhere else the read is <em>undefined</em> rather than racy,
 * and the platform neither throws nor reports it. So the position is read where it is legal, converted to
 * an immutable value there, and published. Everything downstream — the whole culling decision, for every
 * model against every viewer — then runs off the tick against numbers, which is what
 * {@link ViewportCuller} means by touching no entity.
 *
 * <h2>One repeating task per player, on that player's own scheduler</h2>
 * Not one server-wide task that iterates the player list, which is the shape the rest of this plugin's
 * loops have. The difference is the thread rule: a single task cannot read a hundred players, because
 * they are not all on one thread, and hopping to each of their regions from one body is a hundred
 * dispatches to do the work of a hundred dispatches. A player's own {@code EntityScheduler} is already
 * exactly "the thread that may touch this player, wherever they are now", it follows them across regions
 * and worlds, and it stops running when they are gone.
 *
 * <p>Which also makes the cost honest about where it falls. On a regionised server these bodies spread
 * across region threads; on a non-regionised one the entity scheduler <em>is</em> the main thread, so the
 * samplers are N bodies per interval on one thread. Each body is a location read, a trigonometric pair
 * and a map put, and the default interval is one in two ticks — but that is why the interval is a key
 * and why raising it is the first thing to try on a server with many players.
 *
 * <h2>Why the join and quit hooks are not optional</h2>
 * Attaching the players who are <em>already</em> online matters for the same reason it does in the input
 * layer: a plugin enabled with players on would otherwise cull against an empty index, which means every
 * model is held by nobody, which means every model is suspended. Detaching on quit matters more — a
 * viewer left in the index is a viewer the culler keeps deciding for and keeps counting as somebody who
 * holds a model, so a server that lost its quit hook would progressively stop culling anything.
 *
 * <h2>Shutdown</h2>
 * {@link #stop()} must run on disable. An entity-scheduled repeating task is <strong>never</strong> swept
 * for a plugin, and the classloader closes before the platform cancels anything, so a live sampler fails
 * on its first lazy class load after that — during shutdown, attributable to nothing.
 */
public final class ViewerSampler implements ViewerPublisher, Listener {

    /**
     * Ticks before a freshly attached player's first sample.
     *
     * <p><strong>Structure, not a tunable.</strong> One tick is the earliest a repeating task can run,
     * and a player who just joined wants to be in the index on the next pass rather than after a full
     * interval — otherwise the models around them are suspended for the length of their first interval
     * and visibly catch up. The interval between <em>subsequent</em> samples is configuration.
     */
    private static final long INITIAL_DELAY_TICKS = 1L;

    private final @NotNull Plugin plugin;
    private final @NotNull SimulationClock clock;
    private final @NotNull ViewSettingsSource settings;
    private final @NotNull ViewerIndex index;

    /**
     * One live sampler per attached player.
     *
     * <p>Concurrent because a join is seen on the joining player's region, a quit on theirs, and the
     * shutdown sweep on whichever thread is disabling the plugin.
     */
    private final @NotNull Map<UUID, ScheduledTask> samplers = new ConcurrentHashMap<>();

    /** Guarded by {@code this}, alongside {@link #scheduledPeriodTicks}. */
    private boolean started;

    /**
     * The period the live samplers were dispatched at, which is what {@link #reload()} compares against.
     * A repeating task's rate belongs to the platform, so the configured period and the dispatched one
     * differ exactly between a reload and the call that acts on it.
     */
    private long scheduledPeriodTicks;

    /**
     * @param plugin   the plugin the per-player tasks are scheduled for
     * @param clock    the clock a snapshot's timestamp comes from, so staleness is measurable against the
     *                 same reading every other loop in this plugin uses
     * @param settings where the sampling interval comes from, read once per dispatch rather than per
     *                 sample — a repeating task cannot change its own period from inside its own body
     * @param index    where each sample is published
     */
    public ViewerSampler(
            @NotNull Plugin plugin,
            @NotNull SimulationClock clock,
            @NotNull ViewSettingsSource settings,
            @NotNull ViewerIndex index
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Registers the player lifecycle and attaches everybody who is already online.
     *
     * <p>Each attach is dispatched on that player's own scheduler, because scheduling a repeating task on
     * a player is itself something only that player's region may be asked to do safely.
     */
    @Override
    public synchronized void start() {
        if (this.started) {
            return;
        }
        this.started = true;
        this.scheduledPeriodTicks = this.settings.current().sampleIntervalTicks();

        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            attach(player, this.scheduledPeriodTicks);
        }
    }

    /**
     * Cancels every sampler, unregisters the lifecycle listener and empties the index. Idempotent.
     *
     * @return how many samplers were cancelled
     */
    @Override
    public synchronized int stop() {
        if (!this.started) {
            return 0;
        }
        this.started = false;

        HandlerList.unregisterAll(this);
        int cancelled = 0;
        for (final UUID viewer : Set.copyOf(this.samplers.keySet())) {
            final ScheduledTask task = this.samplers.remove(viewer);
            if (task != null) {
                task.cancel();
                cancelled++;
            }
        }
        this.index.clear();
        return cancelled;
    }

    /** Whether the lifecycle hooks are registered. */
    public synchronized boolean isRunning() {
        return this.started;
    }

    /** How many players are currently being sampled. */
    @Override
    public int size() {
        return this.samplers.size();
    }

    /** The period the live samplers were dispatched at, or the last one if this is stopped. */
    public synchronized long scheduledPeriodTicks() {
        return this.scheduledPeriodTicks;
    }

    /**
     * Re-dispatches every sampler if configuration moved the interval.
     *
     * <p>Explicit rather than detected per sample, for the reason every repeating task in this plugin
     * carries a {@code reload()}: the platform owns a task's period, so the only way to change it is to
     * cancel and dispatch again, which a task cannot do to itself from inside its own body.
     *
     * <p>Cancels before dispatching, never the other way round. Two live samplers for one player would
     * both publish into the same key, which is harmless in itself, and would leave one of them
     * uncancellable because only the later handle is kept.
     *
     * @return how many samplers were re-dispatched
     */
    @Override
    public synchronized int reload() {
        final long periodTicks = this.settings.current().sampleIntervalTicks();
        if (!this.started || periodTicks == this.scheduledPeriodTicks) {
            return 0;
        }
        this.scheduledPeriodTicks = periodTicks;

        int redispatched = 0;
        for (final UUID viewer : Set.copyOf(this.samplers.keySet())) {
            final ScheduledTask previous = this.samplers.remove(viewer);
            if (previous != null) {
                previous.cancel();
            }
            final Player player = this.plugin.getServer().getPlayer(viewer);
            // Gone between the copy and here. Their quit hook has already forgotten them, or is about
            // to; re-dispatching for a player who is not there would be a task that never runs.
            if (player != null) {
                attach(player, periodTicks);
                redispatched++;
            }
        }
        return redispatched;
    }

    /**
     * A player became observable.
     *
     * <p>{@link EventPriority#MONITOR} because this changes nothing about the join. The period is read
     * from the live configuration rather than from the field, so a player joining between a reload and
     * the {@link #reload()} that acts on it is dispatched at the new rate and simply does not count as a
     * re-dispatch.
     *
     * @param event the join
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        attach(event.getPlayer(), this.settings.current().sampleIntervalTicks());
    }

    /**
     * A player left.
     *
     * <p>Both halves matter and the second one more. Cancelling the sampler stops work for somebody who
     * is not there; forgetting them from the index is what makes the next pass drop them from every
     * model's viewport, without which a model would believe a reconnected player already holds it and
     * would never send them anything again.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        detach(event.getPlayer().getUniqueId());
    }

    /**
     * Schedules {@code player}'s sampler, replacing any it already had.
     *
     * <p>The scheduling hop is the player's own scheduler, so the {@code runAtFixedRate} call itself
     * lands on the region that owns them. A null return means they were gone before it ran, in which case
     * neither callback will ever fire and there is nothing to retain.
     */
    private void attach(@NotNull Player player, long periodTicks) {
        final UUID viewer = player.getUniqueId();
        player.getScheduler().run(this.plugin, ignored -> {
            final ScheduledTask task = player.getScheduler().runAtFixedRate(
                    this.plugin,
                    ignoredTask -> publish(player),
                    // The retired callback: the platform's only notification that this player is gone
                    // for good. Belt and braces next to the quit event, because a player removed without
                    // a quit — a cross-server transfer, a disable mid-join — still has to leave the index.
                    () -> detach(viewer),
                    INITIAL_DELAY_TICKS,
                    periodTicks);
            if (task == null) {
                return;
            }
            final ScheduledTask previous = this.samplers.put(viewer, task);
            if (previous != null) {
                previous.cancel();
            }
            // Lost a race with stop(): the sweep has already run, so nobody is left to cancel this.
            if (!isRunning()) {
                final ScheduledTask orphan = this.samplers.remove(viewer);
                if (orphan != null) {
                    orphan.cancel();
                }
            }
        }, null);
    }

    /** Cancels {@code viewer}'s sampler and drops them from the index. Idempotent. */
    private void detach(@NotNull UUID viewer) {
        final ScheduledTask task = this.samplers.remove(viewer);
        if (task != null) {
            task.cancel();
        }
        this.index.forget(viewer);
    }

    /**
     * One sample: read the player, publish the value.
     *
     * <p>Runs on the thread that owns the player, which is the whole point of this class. The location is
     * read <strong>once</strong> and both the position and the orientation come out of that one reading —
     * reading twice would let the tick move the player between them and publish an eye that does not
     * match the look vector, which for a viewer turning on the spot is a culling decision made against a
     * direction nobody was facing.
     *
     * <p>The eye rather than the feet: that is where the camera is anchored in first person and the point
     * every other camera mode is an offset from. See {@link ViewerSnapshot}.
     */
    private void publish(@NotNull Player player) {
        final Location at = player.getLocation();
        final World world = at.getWorld();
        if (world == null) {
            // Not reachable through a live player, and cheaper to answer than to argue about: a snapshot
            // with no world could not be compared against a model's world at all.
            return;
        }
        this.index.publish(ViewerSnapshot.of(
                player.getUniqueId(),
                world.getUID(),
                at.getX(),
                at.getY() + player.getEyeHeight(),
                at.getZ(),
                at.getYaw(),
                at.getPitch(),
                player.getClientViewDistance(),
                this.clock.nanoTime()));
    }

    @Override
    public @NotNull String toString() {
        return "ViewerSampler[" + this.samplers.size() + " sampler(s) every "
                + this.scheduledPeriodTicks + " tick(s), running=" + isRunning() + "]";
    }
}
