package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which viewers currently have one model's bones spawned.
 *
 * <p>The state is deliberately tiny — a set of player ids — because it is the <em>only</em> thing the
 * culler needs to remember between passes, and because being wrong about it is expensive in both
 * directions: a viewer recorded as seeing a model they cannot gets no updates and no repair, and a
 * viewer recorded as not seeing one they can gets a full respawn every pass.
 *
 * <p>Concurrent because a cull pass runs on the simulation thread while a player quitting is noticed
 * on theirs. The set is only ever added to or removed from, never iterated and mutated at once, so a
 * concurrent set is sufficient and no lock is needed.
 */
public final class ModelViewport {

    private final @NotNull Set<UUID> shown = ConcurrentHashMap.newKeySet();

    /** Whether {@code viewer} currently has this model's bones. */
    public boolean isShownTo(@NotNull UUID viewer) {
        return this.shown.contains(viewer);
    }

    /**
     * Records that {@code viewer} now has this model.
     *
     * @return {@code true} if this was a change, so a caller can avoid a redundant send
     */
    public boolean markShown(@NotNull UUID viewer) {
        return this.shown.add(viewer);
    }

    /**
     * Records that {@code viewer} no longer has this model.
     *
     * @return {@code true} if this was a change
     */
    public boolean markHidden(@NotNull UUID viewer) {
        return this.shown.remove(viewer);
    }

    /**
     * Forgets {@code viewer} without sending anything.
     *
     * <p>For a player who disconnected: their client is gone, so despawning for them would be a packet
     * to nobody, but leaving them recorded would leak an id and — if they reconnect — have the model
     * believe they can already see it, which is the one state that never repairs itself.
     */
    public void forget(@NotNull UUID viewer) {
        this.shown.remove(viewer);
    }

    /**
     * Forgets every recorded viewer that is not in {@code present}, and says how many there were.
     *
     * <h2>Why the sweep belongs here rather than in the pass</h2>
     * Because it is the hottest thing the view layer does and the caller cannot do it without copying.
     * {@link ViewportCuller} used to read {@link #viewers()} and {@code List.copyOf} it — a defensive
     * copy, because it could not know whether removing while iterating was safe — which cost an
     * unmodifiable wrapper plus a list plus its backing array <em>per model per pass</em>, whether or not
     * the model was held by anybody. Measured over four hundred models that was 48 bytes a model for a
     * sweep with nothing to sweep, and the dominant allocation of the whole pass once models were held.
     *
     * <p>Inside this class the copy is unnecessary, and provably so rather than by hope: {@link #shown}
     * is a {@link ConcurrentHashMap} key set, whose iterator is weakly consistent and documents removal
     * through it as safe. That is a fact about the field, which only this class can see — which is
     * exactly why the loop belongs here.
     *
     * <p>The empty case returns before allocating an iterator at all, and it is the common one: the whole
     * purpose of culling is that most models are held by nobody.
     *
     * @param present the viewers that still exist, as a set the caller already has — a map's key set
     *                rather than a copy of it
     * @return how many stale viewers were dropped
     */
    public int forgetAbsent(@NotNull Set<UUID> present) {
        if (this.shown.isEmpty()) {
            return 0;
        }
        int forgotten = 0;
        for (final Iterator<UUID> recorded = this.shown.iterator(); recorded.hasNext(); ) {
            if (!present.contains(recorded.next())) {
                recorded.remove();
                forgotten++;
            }
        }
        return forgotten;
    }

    /** Forgets every viewer. For a model being unbound. */
    public void clear() {
        this.shown.clear();
    }

    /** How many viewers this model is currently costing bandwidth for. */
    public int shownCount() {
        return this.shown.size();
    }

    /** The viewers currently holding this model, as an unmodifiable view. */
    public @NotNull Set<UUID> viewers() {
        return Collections.unmodifiableSet(this.shown);
    }

    @Override
    public @NotNull String toString() {
        return "ModelViewport[" + this.shown.size() + " viewer(s)]";
    }
}
