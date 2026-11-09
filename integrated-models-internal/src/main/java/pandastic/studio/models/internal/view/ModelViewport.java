package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
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
