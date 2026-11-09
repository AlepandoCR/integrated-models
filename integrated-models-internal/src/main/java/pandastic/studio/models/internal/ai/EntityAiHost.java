package pandastic.studio.models.internal.ai;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one {@link AiHost} that talks to the server.
 *
 * <h2>The chunk arithmetic, in full, because getting it wrong is silent</h2>
 * A region owns whole chunks, so the platform's ownership question is asked in chunks: "do I own every
 * chunk within {@code r} of this one?". Turning that into a block radius needs the worst case over where
 * inside its chunk the creature is standing.
 *
 * <p>A chunk at index {@code c} spans blocks {@code [16c, 16c+15]}. Owning every chunk within {@code r}
 * means owning blocks {@code [16(c-r), 16(c+r)+15]}. A creature at the eastern edge of its own chunk,
 * block {@code 16c+15}, can therefore reach {@code 16(c+r)+15 - (16c+15) = 16r} blocks east before
 * leaving owned ground; one at the western edge, block {@code 16c}, can reach {@code 16c - 16(c-r) = 16r}
 * blocks west. So <strong>{@code 16r} blocks is safe from anywhere in the chunk</strong>, and that is
 * what {@link #ownedRadius(double)} returns when it has to narrow. Vertically there is nothing to check:
 * a region is a column, so it owns every height of every chunk it owns.
 *
 * <p>The sixteen is the chunk width. A protocol fact, not a tunable: it is the unit the platform's own
 * ownership question is asked in, and a configurable value here would be a configurable
 * misunderstanding.
 *
 * <h2>Why the narrowing walks down instead of computing</h2>
 * Because ownership is not monotonic in a way worth deriving. A region is an arbitrary connected set of
 * chunks, so "I do not own radius 3" does not tell you whether you own radius 2 — only that you do not
 * own everything out to 3. Walking down from the requested radius to zero asks the platform the question
 * it can answer, at most once per chunk of the requested radius, and the requested radius is bounded by
 * configuration to keep that loop short.
 *
 * <h2>Scans are filtered to a sphere</h2>
 * The platform's query is a box. A behaviour comparing distances against the radius it asked for would
 * otherwise see things in the corners that are further away than it allowed, which is a bug that only
 * shows up as a creature noticing something slightly too far off. Filtering costs one squared distance
 * per candidate, computed from the coordinate accessors rather than from a {@code Location}, so the scan
 * allocates one list and nothing else.
 */
public final class EntityAiHost implements AiHost {

    /**
     * Blocks per chunk along one axis.
     *
     * <p>A protocol fact. The platform's ownership check is asked in chunks and the conversion to blocks
     * is derived from this; see the class documentation for the worst-case argument.
     */
    private static final int CHUNK_WIDTH = 16;

    private final @NotNull Plugin plugin;
    private final @NotNull Mob mob;

    /**
     * Reused by {@link #position()} and the scans, and confined to the owning thread.
     *
     * <p>{@code Entity#getLocation(Location)} fills a buffer rather than allocating, which matters
     * because a scan runs once per sensor period per creature on a region thread. Safe to share across
     * the methods of this class precisely because every one of them is owning-thread only — the same
     * confinement argument the binding layer's sample buffer rests on.
     */
    private final @NotNull Location buffer = new Location(null, 0.0D, 0.0D, 0.0D);

    /**
     * @param plugin the plugin tasks are scheduled under, which is what lets the platform attribute and
     *               cancel them
     * @param mob    the creature this host speaks for
     */
    public EntityAiHost(@NotNull Plugin plugin, @NotNull Mob mob) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mob = Objects.requireNonNull(mob, "mob");
    }

    @Override
    public @NotNull Mob mob() {
        return this.mob;
    }

    @Override
    public @NotNull UUID creatureId() {
        return this.mob.getUniqueId();
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return Bukkit.isOwnedByCurrentRegion(this.mob);
    }

    @Override
    public boolean isAlive() {
        return this.mob.isValid();
    }

    @Override
    public @NotNull AiPoint position() {
        return new AiPoint(this.mob.getX(), this.mob.getY(), this.mob.getZ());
    }

    @Override
    public long gameTime() {
        return this.mob.getWorld().getGameTime();
    }

    @Override
    public boolean submit(@NotNull Runnable work) {
        Objects.requireNonNull(work, "work");
        // The three-argument form answers whether it will ever run, which is exactly this method's
        // contract: false means the creature retired and the work is not merely late but impossible.
        // The trailing zero is the delay in ticks, i.e. the next tick of the owning region.
        return this.mob.getScheduler().execute(this.plugin, work, null, 0L);
    }

    @Override
    public @Nullable AiTask repeat(
            @NotNull Runnable body,
            @NotNull Runnable retired,
            long periodTicks
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(retired, "retired");
        final ScheduledTask task = this.mob.getScheduler()
                .runAtFixedRate(this.plugin, ignored -> body.run(), retired, periodTicks, periodTicks);
        return task == null ? null : new ScheduledAiTask(task);
    }

    @Override
    public double ownedRadius(double requestedBlocks) {
        if (!Double.isFinite(requestedBlocks) || requestedBlocks < 0.0D) {
            throw new IllegalArgumentException(
                    "requestedBlocks must be finite and non-negative but was " + requestedBlocks);
        }
        final Location centre = this.mob.getLocation(this.buffer);
        final int wantedChunks = (int) Math.ceil(requestedBlocks / CHUNK_WIDTH);

        for (int chunks = wantedChunks; chunks >= OWN_CHUNK_ONLY; chunks--) {
            if (!Bukkit.isOwnedByCurrentRegion(centre, chunks)) {
                continue;
            }
            // An exact match is answered precisely so a scan that already fits is not rounded down; a
            // narrowed one is answered in whole chunks, which is the worst case derived in the class
            // documentation.
            return chunks == wantedChunks
                    ? requestedBlocks
                    : Math.min(requestedBlocks, (double) chunks * CHUNK_WIDTH);
        }
        return NOT_OWNED;
    }

    @Override
    public @NotNull List<Entity> entitiesWithin(double radiusBlocks) {
        final Location centre = this.mob.getLocation(this.buffer);
        final double squared = radiusBlocks * radiusBlocks;
        final double centreX = centre.getX();
        final double centreY = centre.getY();
        final double centreZ = centre.getZ();

        return new ArrayList<>(this.mob.getWorld().getNearbyEntities(
                centre, radiusBlocks, radiusBlocks, radiusBlocks,
                candidate -> candidate != this.mob
                        && withinSphere(candidate, centreX, centreY, centreZ, squared)));
    }

    @Override
    public @NotNull List<Player> playersWithin(double radiusBlocks) {
        final Location centre = this.mob.getLocation(this.buffer);
        final double squared = radiusBlocks * radiusBlocks;
        final double centreX = centre.getX();
        final double centreY = centre.getY();
        final double centreZ = centre.getZ();

        return new ArrayList<>(this.mob.getWorld().getNearbyPlayers(
                centre, radiusBlocks,
                candidate -> withinSphere(candidate, centreX, centreY, centreZ, squared)));
    }

    @Override
    public @NotNull String describe() {
        return this.mob.getType() + " " + this.mob.getUniqueId();
    }

    @Override
    public @NotNull String toString() {
        return "EntityAiHost[" + describe() + "]";
    }

    /** Squared distance against a squared radius, from the coordinate accessors, allocating nothing. */
    private static boolean withinSphere(
            @NotNull Entity candidate,
            double centreX,
            double centreY,
            double centreZ,
            double radiusSquared
    ) {
        final double deltaX = candidate.getX() - centreX;
        final double deltaY = candidate.getY() - centreY;
        final double deltaZ = candidate.getZ() - centreZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= radiusSquared;
    }

    /**
     * The platform's task handle, narrowed to what this layer does with it.
     *
     * <p>{@code ScheduledTask#cancel()} returns a tri-state saying whether the cancellation caught the
     * task before, during or after an execution. This layer does not care: a sensor body that is
     * already running writes its own creature's memories and nothing reads them afterwards, so the
     * distinction has no consequence here and swallowing it keeps {@link AiTask} to two methods.
     */
    private record ScheduledAiTask(@NotNull ScheduledTask task) implements AiTask {

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
