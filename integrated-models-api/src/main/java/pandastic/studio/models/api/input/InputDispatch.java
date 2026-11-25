package pandastic.studio.models.api.input;

/**
 * Which thread a handler is being called on.
 *
 * <h2>The hazard this type exists to name</h2>
 * An input does not arrive on a thread that is allowed to touch much. On a regionised server an entity
 * may only be read or written from the thread that owns its region, and the two places an input can be
 * observed are not the same thread:
 *
 * <ul>
 *   <li>the platform's input notification fires on the <strong>player's own region thread</strong>, so
 *       a handler there may read and move that player and anything in the same region — and still may
 *       not touch an entity in another region;</li>
 *   <li>a packet arrives on a <strong>network thread</strong>, which owns nothing at all. A handler
 *       there may not read a position, may not read a passenger list, and may not call anything on a
 *       model or a vehicle.</li>
 * </ul>
 *
 * <p>Both are perfectly safe places to read the published {@link InputSnapshot}, which is the whole
 * reason the capture layer publishes one. What is not safe is doing the work there, and a handler has
 * no way to tell which thread it is on unless it is told. So it is told, and
 * {@link InputEvent#onOwningRegion(Runnable)} is the way across.
 *
 * <p>Sending a packet is region-independent and therefore fine from either; reading an entity is not.
 */
public enum InputDispatch {

    /**
     * The player's own region thread.
     *
     * <p>Reading and modifying that player, and anything else the same region owns, is legal inline.
     * {@link InputEvent#onOwningRegion(Runnable)} runs the task immediately.
     */
    PLAYER_REGION,

    /**
     * A network thread.
     *
     * <p>Nothing about the world may be touched. Read the snapshot, decide, and hand anything that
     * touches an entity to {@link InputEvent#onOwningRegion(Runnable)}, which schedules it on the
     * player's own scheduler.
     */
    NETWORK
}
