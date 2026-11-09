package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiPoint;

import java.util.List;
import java.util.UUID;

/**
 * One creature, the thread allowed to touch it, and how far around it that thread may look.
 *
 * <h2>The seam the whole region-safety argument rests on</h2>
 * On a regionised server a creature may only be touched from the thread ticking the region that owns it,
 * and the platform has no strict checking mode — a cross-region read fails silently rather than
 * throwing. Worse, the two kinds of look-around fail <em>differently</em>: an entity box query raises an
 * exception when it reaches into a region this thread does not own, while the player list is global and
 * reading a foreign player's position out of it simply gives a torn answer.
 *
 * <p>So this interface exists to put every one of those operations behind one object whose
 * implementation is the only place in the AI layer that names a platform type. Everything else in the
 * package — the plan membership rule, the radius clamping, the scan shrinking, the fault counting, the
 * install and teardown ordering — is logic over this seam and is exercised against a substitute.
 *
 * <h2>Why the scheduler is the creature's and not its region's</h2>
 * An entity scheduler follows its entity across region and world changes. A region-scheduled task is
 * pinned to the region it was posted from, so a sensor dispatched that way runs on the wrong thread the
 * moment the creature walks over a boundary — and runs there silently, because nothing checks.
 *
 * <h2>Why {@link #ownedRadius(double)} returns a number rather than a boolean</h2>
 * Because the useful answer to "may I look sixteen blocks?" on a region boundary is not "no". It is
 * "four", and a creature that scans four blocks is a creature that still works. Collapsing that to a
 * boolean would leave the layer choosing between a blind creature and an exception from inside the
 * brain's tick.
 */
public interface AiHost {

    /** Chunk radius covering the creature's own chunk and nothing else. Structural, not tunable. */
    int OWN_CHUNK_ONLY = 0;

    /** {@link #ownedRadius(double)} for a thread that does not own even the creature's own chunk. */
    double NOT_OWNED = -1.0D;

    /** The creature. Safe to hold; not safe to read from a thread that does not own it. */
    @NotNull Mob mob();

    /**
     * The creature's identity.
     *
     * <p>On the seam rather than read through {@link #mob()} because it is the one thing about a
     * creature this layer needs from a thread that may not own it: the teardown path runs from anywhere
     * — a plugin shutting down, the platform's retired callback — and has to say which creature it
     * belonged to. An identity never changes, so a host is expected to capture it once.
     *
     * @return the creature's unique identifier
     */
    @NotNull UUID creatureId();

    /**
     * Whether the calling thread is the one allowed to read and mutate this creature.
     *
     * <p>The guard on this layer's own entry points. The platform will not guard them, so an entry point
     * that happens to be called from the wrong thread produces a corrupt read or a corrupt registration
     * and no diagnostic at all.
     *
     * @return whether this thread owns the creature
     */
    boolean isOwnedByCurrentThread();

    /** Whether the creature still exists. Only meaningful on the owning thread. */
    boolean isAlive();

    /**
     * Where the creature is.
     *
     * <p>Owning-thread only, and the reason it returns an {@link AiPoint} rather than a platform
     * location: what comes out has to be publishable into an intent the simulation thread reads, and a
     * location carries a world reference that is only meaningful on the thread that owns it.
     *
     * @return the position
     */
    @NotNull AiPoint position();

    /** The game time of the creature's world, in ticks. Owning-thread only. */
    long gameTime();

    /**
     * Runs {@code work} on the thread allowed to touch this creature, soon.
     *
     * <p>For the operations that have to happen there and were triggered from somewhere else: the
     * install itself, and the brain restoration half of an uninstall.
     *
     * @param work what to run
     * @return {@code false} if there is no such thread any more, meaning the creature is gone and
     *         {@code work} will never run. The caller decides what that means
     */
    boolean submit(@NotNull Runnable work);

    /**
     * Dispatches {@code body} every {@code periodTicks} on the thread allowed to touch this creature.
     *
     * @param body        the sensor, run on the owning thread; never concurrently with itself
     * @param retired     run instead, once, if the creature is removed. For an entity this is the
     *                    <em>only</em> notification the platform gives that it is gone, which is what
     *                    makes it the authoritative teardown trigger. The platform documents it as
     *                    critical code — it must not remove entities, load chunks or load worlds — so a
     *                    callback installed here does cheap bookkeeping and nothing else
     * @param periodTicks ticks between executions
     * @return the handle to cancel with, or {@code null} if the creature was already gone, in which case
     *         neither callback will ever run and the caller owns the unwind
     */
    @Nullable AiTask repeat(@NotNull Runnable body, @NotNull Runnable retired, long periodTicks);

    /**
     * The largest radius at or below {@code requestedBlocks} that is wholly inside the region this
     * thread owns.
     *
     * <p>{@link #NOT_OWNED} when the thread does not own even the creature's own chunk, which is a window
     * that genuinely exists: a creature can change region between a task being dispatched and its body
     * running.
     *
     * <p>Granularity is a chunk, because that is the unit a region owns. An implementation therefore
     * answers in multiples of the chunk width, except for an exact match which it answers precisely —
     * so a scan that fits inside the owned area is not needlessly rounded down.
     *
     * @param requestedBlocks the radius a body asked for, in blocks; finite and non-negative
     * @return the radius it may have, or {@link #NOT_OWNED}
     */
    double ownedRadius(double requestedBlocks);

    /**
     * Every entity except the creature itself within {@code radiusBlocks} of it.
     *
     * <p><strong>Must only be called with a radius {@link #ownedRadius(double)} returned</strong>, from
     * the owning thread. A box query that reaches into a foreign region raises an exception from inside
     * whatever was calling it — which, for a behaviour body, is the creature's own tick.
     *
     * @param radiusBlocks the radius to search, already region-checked
     * @return what was found, within a sphere of that radius
     */
    @NotNull List<Entity> entitiesWithin(double radiusBlocks);

    /**
     * Every player within {@code radiusBlocks} of the creature.
     *
     * <p>Same rule as {@link #entitiesWithin(double)}, and the reason it is a separate method rather
     * than a filter: the player list is global while the entity index is per-region, so the two are
     * different queries with different costs, and this is the one nearly every plan asks.
     *
     * @param radiusBlocks the radius to search, already region-checked
     * @return the players found, within a sphere of that radius
     */
    @NotNull List<Player> playersWithin(double radiusBlocks);

    /** This creature in one short phrase, for a log line or a failure message. */
    @NotNull String describe();
}
