package pandastic.studio.models.api.input;

import org.jetbrains.annotations.NotNull;

/**
 * What runs when a combination matches.
 *
 * <h2>Threading is on the delivery, not on this interface</h2>
 * A handler may be called on the player's region thread or on a network thread, and
 * {@link InputEvent#dispatch()} says which. Reading {@link InputEvent#snapshot()} is safe either way;
 * touching an entity is not, and {@link InputEvent#onOwningRegion(Runnable)} is the way across. See
 * {@link InputDispatch}.
 *
 * <h2>A handler that throws is contained, counted and eventually stopped</h2>
 * It has to be. A handler runs inside a player's input, which on a network thread means inside the
 * read loop of that player's connection and on a region thread means inside a tick. An exception
 * escaping it would otherwise fail whatever is above it, and because input arrives every client tick it
 * would fail it again immediately. So the dispatcher catches everything, reports it through the
 * plugin's logger at a configured interval rather than once per occurrence, and stops calling a handler
 * that has failed as many times in a row as the configuration permits.
 *
 * <p>Which means a handler must not use an exception to decline: there is nothing to decline to. A
 * handler that has nothing to do should return.
 */
@FunctionalInterface
public interface InputHandler {

    /**
     * Called once per publication on which the bound combination matches, subject to the configured
     * repeat interval for a combination that keeps matching.
     *
     * @param event what matched, for whom, and on which thread
     */
    void onInput(@NotNull InputEvent event);
}
