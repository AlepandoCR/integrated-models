package pandastic.studio.models.internal.binding;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.RenderSettingsSource;

import java.util.Objects;

/**
 * A model anchored to a Bukkit entity: the only class in this package that names a platform type.
 *
 * <h2>The entity scheduler, not the region scheduler</h2>
 * An entity can change region, change world, be temporarily removed and be removed outright, and the
 * entity scheduler is the only dispatcher that tracks all of that. It guarantees the body runs on
 * whichever region owns the entity <em>at that moment</em>, with the entity actually present, and it
 * reports the entity's removal through a retired callback. A region scheduler, by contrast, is pinned to
 * the region the task was posted from — so the first time the entity walks across a region boundary the
 * task is reading it from the wrong thread, silently, because the platform has no strict check to fire.
 *
 * <p>Two of its documented properties shape the code below. Scheduling can fail, returning null, if the
 * entity is already gone; and the retired callback runs in critical code that must not remove entities,
 * load chunks or load worlds. The first is why {@link #schedule} is nullable and the second is why
 * {@link RiggedModelImpl} declines to release a tracker from that path.
 *
 * <h2>Why ownership is checked rather than assumed</h2>
 * {@code Bukkit.isOwnedByCurrentRegion(entity)} is documented as the only appropriate way to check for
 * ownership of an entity, because retrieving its location is undefined unless the current region owns
 * it. Undefined, not racy: there is no exception and no log line, just a coordinate assembled from two
 * different ticks. The check costs a thread-local read, and the alternative is a class of corruption
 * that cannot be reproduced on demand.
 */
public final class EntityAnchor implements ModelAnchor {

    /**
     * Initial delay of the sampler, in ticks.
     *
     * <p><strong>A platform constraint, not a tunable.</strong> The entity scheduler treats any delay
     * below one tick as one tick, so this is the earliest a dispatched task can run — and the earliest
     * is what is wanted, because until the first sample lands the simulation is driving a motionless
     * owner.
     */
    private static final long INITIAL_DELAY_TICKS = 1L;

    /**
     * Delay of a deferred teardown step, in ticks.
     *
     * <p><strong>The same platform constraint.</strong> {@link #submit} exists to get world mutation
     * onto the owning thread, and one tick is the soonest the scheduler will do that.
     */
    private static final long DEFERRED_DELAY_TICKS = 1L;

    private final @NotNull Plugin plugin;
    private final @NotNull Entity entity;
    private final @NotNull RenderSettingsSource renderSettings;

    /**
     * @param renderSettings where a bound sink reads its dirty epsilon from, once per emission. A
     *                       source rather than a snapshot because a sink outlives any one configuration
     */
    public EntityAnchor(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull RenderSettingsSource renderSettings
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.renderSettings = Objects.requireNonNull(renderSettings, "renderSettings");
    }

    @Override
    public boolean readInto(@NotNull AnchorSample sample) {
        if (!this.entity.isValid()) {
            return false;
        }
        // One read of each coordinate, straight into the caller's buffer. Legal only because this runs
        // on the owning region thread, and cheap enough to be worth doing per tick per model: the
        // component getters avoid allocating the Location that getLocation() would.
        sample.set(
                this.entity.getX(),
                this.entity.getY(),
                this.entity.getZ(),
                this.entity.getYaw(),
                this.entity.isSneaking(),
                // The server updates this when the entity moves and does not recompute it otherwise,
                // which is exactly the semantics the gait's airborne blend wants: "is it being held up
                // by something", not "is there a block underneath right now".
                !this.entity.isOnGround());
        return true;
    }

    @Override
    public boolean isAlive() {
        return this.entity.isValid();
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return Bukkit.isOwnedByCurrentRegion(this.entity);
    }

    @Override
    public @Nullable AnchorTask schedule(
            @NotNull Runnable body,
            @NotNull Runnable retired,
            long periodTicks
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(retired, "retired");
        // The platform hands the body its own handle; the binding has no use for it, since it keeps the
        // one returned here and that is the handle it must cancel by hand on disable.
        final ScheduledTask task = this.entity.getScheduler().runAtFixedRate(
                this.plugin, ignoredTask -> body.run(), retired, INITIAL_DELAY_TICKS, periodTicks);
        return task == null ? null : new PlatformAnchorTask(task);
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        Objects.requireNonNull(work, "work");
        // No retired callback: the whole reason to defer is that the work must not run in critical
        // code, and a retired callback is critical code. A false return says it will never run, which
        // is what the caller needs to know.
        return this.entity.getScheduler()
                .execute(this.plugin, work, null, DEFERRED_DELAY_TICKS);
    }

    @Override
    public @NotNull RenderBinding render(@NotNull Rig rig) {
        Objects.requireNonNull(rig, "rig");
        final ModelRenderer renderer = BetterModel.model(rig.getModelId()).orElseThrow(
                () -> new IllegalStateException(
                        "Rig '" + rig.getName() + "' points at model '" + rig.getModelId()
                                + "', which BetterModel has not loaded. Check the model name and that"
                                + " its file is in BetterModel's model folder."));
        final EntityTracker tracker = renderer.create(BukkitAdapter.adapt(this.entity));
        // Takes ownership of the tracker, including closing it if the rig cannot be bound.
        return TrackerRenderBinding.bind(rig, tracker, this.renderSettings);
    }

    @Override
    public @NotNull String describe() {
        return this.entity.getType() + " " + this.entity.getUniqueId();
    }

    /** The entity this anchor follows. */
    public @NotNull Entity entity() {
        return this.entity;
    }

    @Override
    public @NotNull String toString() {
        return "EntityAnchor[" + describe() + "]";
    }

    /**
     * Adapts a platform task to the two methods the binding layer uses.
     *
     * <p>{@code cancel()} returns nothing useful on this scheduler and {@code isCancelled()} is the
     * whole of the state worth exposing — a task whose entity retired reports itself cancelled, which is
     * how a diagnostic can tell a sampler that was stopped from one that stopped itself.
     */
    private record PlatformAnchorTask(@NotNull ScheduledTask task) implements AnchorTask {

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
