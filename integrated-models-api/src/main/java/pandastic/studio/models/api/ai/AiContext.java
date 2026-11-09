package pandastic.studio.models.api.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Everything a behaviour or a sensor body is allowed to touch, and the thread it may touch it from.
 *
 * <h2>The context is the thread guarantee</h2>
 * A body that is handed one of these is running on the thread that owns the creature. That is not a
 * convention it has to uphold — it is what the layer arranged before calling: a behaviour runs inside
 * the creature's own brain tick, and a sensor runs on a task dispatched through the creature's own
 * scheduler, which follows it across regions and worlds. So inside a body, the creature, its world
 * around it, and its memories are all legitimately readable.
 *
 * <p>The corollary is the part to internalise: <strong>a context must not outlive the body it was
 * handed to.</strong> Stashing one in a field and using it from a scheduled task later moves every
 * method here onto a thread that may no longer own the creature, and the platform will not say so. The
 * values are cheap to re-ask for; the context is not a handle.
 *
 * <h2>Looking around is bounded by the region, not by the radius you ask for</h2>
 * {@link #scanEntities(double)} and {@link #scanPlayers(double)} return an {@link AiScan} rather than a
 * list precisely because a creature cannot see past the boundary of the region ticking it. Read
 * {@link AiScan}; it is the single most consequential fact in this package. Both radii are additionally
 * clamped to a configured ceiling, so a plan asking for a two-hundred-block scan gets the operator's
 * limit and is told what it got rather than being allowed to walk the whole world every tick.
 *
 * <h2>Deciding is this layer's job; acting is not</h2>
 * There is no {@code walkTo}, no {@code lookAt} and no {@code attack} here. A body decides and
 * {@link #publish(AiIntent)}es; something else applies it. That split is what keeps the creature's
 * movement out of the hands of a body that might be running concurrently with the simulation posing
 * its model — see {@link AiIntent} for the argument in full.
 */
public interface AiContext {

    /**
     * The creature this body is running for.
     *
     * <p>Readable and mutable from here, because this is its own region thread. The one thing not to do
     * with it is keep it: a behaviour that remembers a mob across ticks should remember its
     * {@link java.util.UUID} in a memory and resolve it again.
     *
     * @return the creature
     */
    @NotNull Mob getMob();

    /** Where this creature is, as a value that can be remembered or published. */
    @NotNull AiPoint position();

    /** What the creature remembers. Declared memories only; see {@link AiMemoryView}. */
    @NotNull AiMemoryView memories();

    /**
     * The world's game time, in ticks.
     *
     * <p>The clock a cooldown should be measured against, because it is the same clock the brain's own
     * memory expiry uses. Not wall time, and not a counter of our own: a creature in an unloaded chunk
     * stops ticking, and a cooldown measured in wall time would expire while it was not there to notice.
     *
     * @return the game time of the creature's world
     */
    long gameTime();

    /**
     * What is around the creature, out to {@code radiusBlocks} or as far as the region permits.
     *
     * <p>Excludes the creature itself, because every caller would otherwise have to.
     *
     * @param radiusBlocks how far to look, in blocks; clamped to the configured ceiling
     * @return what was found, and how far the search actually reached
     */
    @NotNull AiScan<Entity> scanEntities(double radiusBlocks);

    /**
     * Which players are around the creature, out to {@code radiusBlocks} or as far as the region
     * permits.
     *
     * <p>Separate from {@link #scanEntities(double)} rather than a filter over it, because this is the
     * question nearly every plan asks and filtering a full entity scan to find it does the expensive
     * part first. It is also where the region limit bites hardest: a player one region away is a player
     * this creature cannot see, which is the same rule vanilla's own sensors live under.
     *
     * @param radiusBlocks how far to look, in blocks; clamped to the configured ceiling
     * @return the players found, and how far the search actually reached
     */
    @NotNull AiScan<Player> scanPlayers(double radiusBlocks);

    /**
     * Publishes {@code intent} as this creature's current decision.
     *
     * <p>Replaces whatever was published before, including by another behaviour in the same tick: the
     * last publish of a tick wins, which is why priority ordering within an activity matters. Safe from
     * any thread by construction — it is one store of one immutable reference — but there is no reason
     * to call it from anywhere but a body.
     *
     * @param intent what the creature should be doing, or {@link AiIntent#NONE} to decide nothing
     */
    void publish(@NotNull AiIntent intent);

    /**
     * The intent currently published for this creature.
     *
     * <p>So a behaviour can add to a decision another behaviour in the same activity already made —
     * typically a look target on top of someone else's move target — rather than overwriting it.
     *
     * @return the live intent, never {@code null}
     */
    @NotNull AiIntent intent();
}
