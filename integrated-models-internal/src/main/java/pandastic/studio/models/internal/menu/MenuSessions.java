package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player's in-flight menu state, and the only reason it ever goes away.
 *
 * <h2>The missing event this class exists for</h2>
 * <strong>Nothing tells the server that a dialog was closed.</strong> Not escape, not clicking away, not
 * a client disconnecting halfway through a screen. The platform has no close notification of any kind,
 * so there is no event this store could have hooked to clean itself up — which is why a session carries
 * its own idle timeout and why that timeout is configuration rather than a constant.
 *
 * <p>The consequence worth stating plainly: a session is not a lifetime, it is a lease. A player who
 * closes a menu and comes back two minutes later finds their page where they left it; one who comes back
 * an hour later finds page one. Neither is a bug and both are the timeout.
 *
 * <h2>Pruning is lazy, and that is why there is also a cap</h2>
 * Expiry is evaluated when the store is next touched rather than on a scheduled task. A task would be
 * one more thing to retain and cancel on disable — region and entity tasks are never cancelled for
 * us — for work that is only ever a few map removals. The cost is that a quiet server keeps expired
 * entries until somebody opens a menu, so the configured ceiling is what bounds the store in the
 * meantime: past it, the least recently used session is dropped, which costs that player their page
 * number and nothing else.
 *
 * <h2>Threading</h2>
 * Safe from any thread. Sessions are created and read on the thread that owns their player, but an
 * eviction pass runs on whichever thread happened to open a menu, so the map is concurrent and every
 * field of a session is volatile. Nothing here touches an entity, so none of it needs a region.
 */
public final class MenuSessions {

    private final @NotNull Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();
    private final @NotNull MenuSettingsSource settings;
    private final @NotNull SimulationClock clock;

    /**
     * @param settings where the idle timeout and the ceiling are read from
     * @param clock    the monotonic clock the timeout is measured against
     */
    public MenuSessions(@NotNull MenuSettingsSource settings, @NotNull SimulationClock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The session for {@code player}, created if there is none, with its timeout restarted.
     *
     * <p>Prunes first, so the ceiling is applied against what is actually still live rather than against
     * what has not been swept yet. A caller opening a menu is the only moment this layer is guaranteed to
     * be running at all, which is why the housekeeping hangs off it.
     *
     * @param player who is opening a menu
     * @return their session
     */
    public @NotNull MenuSession open(@NotNull UUID player) {
        final MenuSettings.Session options = this.settings.current().session();
        final long now = this.clock.nanoTime();

        prune(now, options.timeoutNanos());
        final MenuSession session =
                this.sessions.computeIfAbsent(player, id -> new MenuSession(id, now));
        session.touch(now);
        enforceCeiling(options.maxSessions(), player);
        return session;
    }

    /**
     * The session for {@code player}, if they have a live one.
     *
     * <p>Does <strong>not</strong> create one and does not restart the timeout, so a caller that only
     * wants to know whether a player is mid-menu cannot accidentally extend the lease. An expired
     * session is reported as absent and removed on the way past, because answering with one would hand
     * out state the timeout has already decided to forget.
     *
     * @param player whose session to look for
     * @return the session, or empty when there is none or it has expired
     */
    public @NotNull Optional<MenuSession> find(@NotNull UUID player) {
        final MenuSession session = this.sessions.get(player);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(this.clock.nanoTime(), this.settings.current().session().timeoutNanos())) {
            this.sessions.remove(player, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Forgets {@code player}'s session.
     *
     * <p>The one deterministic end a session has, reached by the Close button. Every other end is the
     * timeout, because every other way out of a dialog is invisible to the server.
     *
     * @param player whose session to drop
     * @return {@code true} when there was one
     */
    public boolean close(@NotNull UUID player) {
        return this.sessions.remove(player) != null;
    }

    /** Forgets every session. For plugin disable, where nothing is worth keeping. */
    public void clear() {
        this.sessions.clear();
    }

    /** How many sessions are held right now, expired ones included until the next prune. */
    public int size() {
        return this.sessions.size();
    }

    /**
     * Drops every session that has been idle past its timeout.
     *
     * <p>Exposed so the behaviour can be driven directly by a test with a clock it controls, and so a
     * future command could sweep on demand. Production reaches it through {@link #open}.
     *
     * @return how many sessions were dropped
     */
    public int prune() {
        final MenuSettings.Session options = this.settings.current().session();
        return prune(this.clock.nanoTime(), options.timeoutNanos());
    }

    private int prune(long nowNanos, long timeoutNanos) {
        int dropped = 0;
        for (final Map.Entry<UUID, MenuSession> entry : this.sessions.entrySet()) {
            final MenuSession session = entry.getValue();
            // remove(key, value) rather than remove(key): the session may have been touched by its own
            // player's thread between the test and the removal, and that player is mid-click.
            if (session.isExpired(nowNanos, timeoutNanos)
                    && this.sessions.remove(entry.getKey(), session)) {
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Brings the store back under its ceiling by dropping the least recently used sessions.
     *
     * <p>{@code keep} is never evicted. It is the session of the player who caused this pass, and
     * evicting it would mean a server at its ceiling could not open a menu at all — the pathological
     * case where every attempt immediately discards its own state.
     *
     * @param ceiling how many sessions may be held
     * @param keep    the player whose session is exempt
     */
    private void enforceCeiling(int ceiling, @NotNull UUID keep) {
        if (this.sessions.size() <= ceiling) {
            return;
        }

        final List<MenuSession> candidates = new ArrayList<>(this.sessions.values());
        candidates.sort((left, right) -> Long.compare(left.lastTouchedNanos(), right.lastTouchedNanos()));
        for (final MenuSession candidate : candidates) {
            if (this.sessions.size() <= ceiling) {
                return;
            }
            if (!candidate.player().equals(keep)) {
                this.sessions.remove(candidate.player(), candidate);
            }
        }
    }

}
