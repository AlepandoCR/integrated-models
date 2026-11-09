package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPoint;
import pandastic.studio.models.api.ai.AiScan;

import java.util.List;
import java.util.Objects;

/**
 * What a behaviour or sensor body is handed, and the place the region asymmetry is dealt with.
 *
 * <h2>Scanning, in the order the two limits apply</h2>
 * <ol>
 *   <li><strong>The operator's ceiling.</strong> A radius above
 *       {@link AiBounds#maxScanRadiusBlocks()} is reduced to it. A plan asking for two hundred blocks
 *       does not get to walk the world once per creature per period just because it asked politely.</li>
 *   <li><strong>The region.</strong> The remaining radius is reduced to the largest one wholly inside
 *       the region this thread owns. That is the limit that matters and the one nobody expects: a
 *       creature standing on a region boundary <em>genuinely cannot see</em> past it, because the chunks
 *       on the other side are being ticked by another thread and asking about them raises an exception
 *       from inside the creature's own tick. Vanilla's own sensors live under the same rule, which is
 *       why a mob one region away from a player is not looking at them.</li>
 * </ol>
 *
 * <p>Both reductions produce the same observable thing — an {@link AiScan} whose
 * {@link AiScan#complete()} is false — and that is deliberate. To a behaviour, "the operator capped you"
 * and "the region capped you" mean the identical thing: you did not see everything you asked about, so
 * do not conclude anything about what is out there. Distinguishing them would invite a behaviour to
 * treat one of them as an empty result, and that is exactly the bug.
 *
 * <p>The requested radius reported in the scan is the <em>caller's</em> figure, not the clamped one, so
 * that {@link AiScan#provesEmptyWithin(double)} answers the question the caller actually asked.
 *
 * <h2>Why the thread is not checked here</h2>
 * It is checked where it can be acted on. A scan that runs off the owning thread ends up asking the
 * host for a radius, and {@link AiHost#ownedRadius(double)} answers {@link AiHost#NOT_OWNED} for a
 * thread that owns nothing — which this turns into {@link AiScan#unavailable(double)}, an honest "I could
 * not look" rather than an exception out of a body that may be mid-decision. Memory access, where a torn
 * read would be silently believed, does throw; see {@link GuardedMemoryView}.
 *
 * <h2>One instance per installation</h2>
 * Every field is per-creature and fixed, so the adapter below the NMS seam builds one of these and
 * reuses it for every behaviour invocation, and the controller reuses the same one for its sensors. The
 * api's rule that a context is valid only for the duration of a call is therefore a statement about
 * caller discipline — the object itself is immutable and would happily be used later, on a thread that
 * no longer owns the creature, which is why the rule is stated rather than enforced.
 */
public final class AiContextImpl implements AiContext {

    private final @NotNull AiHost host;
    private final @NotNull AiMemoryView memories;
    private final @NotNull IntentHolder intents;
    private final @NotNull AiBounds bounds;

    /**
     * @param host     the creature and the thread rules around it
     * @param memories the guarded view; see {@link GuardedMemoryView}
     * @param intents  where a decision is published
     * @param bounds   the operator's limits, fixed for this installation. Fixed rather than read live
     *                 because a scan that re-read configuration would be able to shrink halfway through
     *                 a body, and a body comparing two scans in one tick needs them to have been taken
     *                 under one ceiling
     */
    public AiContextImpl(
            @NotNull AiHost host,
            @NotNull AiMemoryView memories,
            @NotNull IntentHolder intents,
            @NotNull AiBounds bounds
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.memories = Objects.requireNonNull(memories, "memories");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public @NotNull Mob getMob() {
        return this.host.mob();
    }

    @Override
    public @NotNull AiPoint position() {
        return this.host.position();
    }

    @Override
    public @NotNull AiMemoryView memories() {
        return this.memories;
    }

    @Override
    public long gameTime() {
        return this.host.gameTime();
    }

    @Override
    public @NotNull AiScan<Entity> scanEntities(double radiusBlocks) {
        final double permitted = permittedRadius(radiusBlocks);
        if (permitted == AiHost.NOT_OWNED) {
            return AiScan.unavailable(radiusBlocks);
        }
        return new AiScan<>(this.host.entitiesWithin(permitted), radiusBlocks, permitted);
    }

    @Override
    public @NotNull AiScan<Player> scanPlayers(double radiusBlocks) {
        final double permitted = permittedRadius(radiusBlocks);
        if (permitted == AiHost.NOT_OWNED) {
            return AiScan.unavailable(radiusBlocks);
        }
        return new AiScan<>(this.host.playersWithin(permitted), radiusBlocks, permitted);
    }

    @Override
    public void publish(@NotNull AiIntent intent) {
        this.intents.publish(intent);
    }

    @Override
    public @NotNull AiIntent intent() {
        return this.intents.current();
    }

    @Override
    public @NotNull String toString() {
        return "AiContextImpl[" + this.host.describe() + "]";
    }

    /**
     * The radius a scan may actually use, or {@link AiHost#NOT_OWNED}.
     *
     * <p>Both reductions in one method so the order cannot be got wrong by a future second scan kind:
     * the operator's ceiling has to be applied first, or the region walk in
     * {@link AiHost#ownedRadius(double)} would loop over chunks of a radius the operator already
     * refused.
     *
     * @param radiusBlocks what the body asked for
     * @return what it may have
     */
    private double permittedRadius(double radiusBlocks) {
        final double allowed = this.bounds.clampScanRadius(radiusBlocks);
        final double owned = this.host.ownedRadius(allowed);
        return owned;
    }

    /**
     * The entities a scan would return, for a caller that already knows its radius is permitted.
     *
     * <p>Not part of the api, and only here because a diagnostic command wants to show what a creature
     * can currently see without pretending to be a behaviour.
     *
     * @param radiusBlocks the radius to search
     * @return what was found, or an empty list if this thread owns nothing
     */
    public @NotNull List<Entity> visibleEntities(double radiusBlocks) {
        final double permitted = permittedRadius(radiusBlocks);
        return permitted == AiHost.NOT_OWNED ? List.of() : this.host.entitiesWithin(permitted);
    }
}
