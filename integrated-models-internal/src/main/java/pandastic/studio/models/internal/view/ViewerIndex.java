package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every viewer's latest {@link ViewerSnapshot} is published, and the only thing the sampling
 * threads and the culling thread share.
 *
 * <h2>One holder per viewer, not one map swap per round</h2>
 * This is {@link pandastic.studio.models.internal.sim.DriverHolder}'s pattern applied per player rather
 * than per model, and the reason is the same: a position may only be read on the thread that owns the
 * thing it belongs to, so each player publishes its own value from its own region and nothing coordinates
 * the publications.
 *
 * <p>The alternative — build a whole map each round and swap one reference — would need a barrier saying
 * "every player has reported", and there is no such instant on a regionised server. Region threads tick
 * independently, a player can change regions between two of their own samples, and a player whose region
 * is lagging would hold up the publication of everybody else's. A map of immutable values, written one
 * key at a time, has no barrier to get wrong: a pass reads whatever each viewer last said, which is the
 * strongest statement anybody can truthfully make about where several players are at once.
 *
 * <h2>What a reader is promised</h2>
 * Every value is an immutable record, so a reader that has one cannot see it change underneath. The
 * <em>map</em> is weakly consistent: a pass iterating it may or may not observe a player who joined while
 * it ran, and may still observe one who quit. Both are correct — a player who joined is picked up next
 * pass, and the culler already treats a viewer it no longer finds as a viewer to forget. What it must
 * never do is block the sampling threads, which is why this is a concurrent map and not a guarded one.
 *
 * <h2>Nothing here touches the platform</h2>
 * Deliberately: this is the half of the sampler that has decisions in it — publication, replacement,
 * removal, the staleness question — and therefore the half a test can drive with no server anywhere. The
 * platform half is {@link ViewerSampler}, which reads players and calls {@link #publish}.
 */
public final class ViewerIndex {

    private final @NotNull Map<UUID, ViewerSnapshot> viewers = new ConcurrentHashMap<>();

    /**
     * Publishes {@code snapshot} as what is currently known about its player, replacing whatever that
     * player last said.
     *
     * <p>Call this from the thread that owns the player, immediately after reading them; see
     * {@link ViewerSnapshot} for why the look vector is converted there rather than here.
     *
     * @param snapshot the sample
     * @return the snapshot this replaced, or {@code null} if the player was not known yet
     */
    public @Nullable ViewerSnapshot publish(@NotNull ViewerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return this.viewers.put(snapshot.playerId(), snapshot);
    }

    /**
     * Drops {@code viewer}, for a player who left.
     *
     * <p>Dropping them is what makes the culler forget them from every model's viewport, which is the one
     * piece of bookkeeping that never repairs itself if it is missed: a model that still believes a
     * reconnected player holds it will never send them anything again.
     *
     * @param viewer the player who left
     * @return whether they were known
     */
    public boolean forget(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        return this.viewers.remove(viewer) != null;
    }

    /** What {@code viewer} last published, or {@code null} if they are not known. */
    public @Nullable ViewerSnapshot viewer(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        return this.viewers.get(viewer);
    }

    /**
     * Every known viewer, as the map a pass reads.
     *
     * <p>An unmodifiable view rather than a copy: the culler iterates it once per pass over however many
     * players are online, and copying it would put an allocation proportional to the player count on a
     * path whose whole purpose is to save work. Unmodifiable because a pass has no business publishing.
     */
    public @NotNull Map<UUID, ViewerSnapshot> viewers() {
        return Collections.unmodifiableMap(this.viewers);
    }

    /** How many viewers are known. */
    public int size() {
        return this.viewers.size();
    }

    /** Forgets everybody, for a layer being shut down. Sends nothing; there is nothing to send. */
    public void clear() {
        this.viewers.clear();
    }

    @Override
    public @NotNull String toString() {
        return "ViewerIndex[" + this.viewers.size() + " viewer(s)]";
    }
}
