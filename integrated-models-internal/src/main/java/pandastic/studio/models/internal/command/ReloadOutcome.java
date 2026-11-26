package pandastic.studio.models.internal.command;

/**
 * What the last reload of this server's configuration did, as far as anything can tell.
 *
 * <h2>Why {@link #UNKNOWN} is a member rather than an absent value</h2>
 * Because "nobody has reloaded since this server started" is the state a healthy server spends almost
 * all of its life in, and it is not the same claim as "the last reload worked". The configuration layer
 * keeps no history of its own — {@link pandastic.studio.models.internal.config.ConfigService} publishes a
 * snapshot and forgets how it got there, which is correct for a reader on the simulation thread and
 * useless to an operator asking whether their last edit took — so the only honest answer before the first
 * reload is this one, and a health report that said "applied" instead would be inventing an event.
 *
 * @see ReloadHistory
 */
public enum ReloadOutcome {

    /** No reload has been observed on this server since it started. */
    UNKNOWN,

    /** The last reload validated every file and published a fresh snapshot. */
    APPLIED,

    /**
     * The last reload was refused, so the values in force are the ones from before it.
     *
     * <p>A reported state rather than a fault: the server is running on values a file still describes.
     * It is a degradation only in the sense that the files on disk and the values in force disagree,
     * which is exactly what the operator needs to be told.
     */
    REFUSED,

    /**
     * The last reload threw rather than being refused — an unreadable directory, or the plugin disabled
     * while it was in flight.
     *
     * <p>Distinct from {@link #REFUSED} because a refusal is operator input the validator understood and
     * said no to, while this is a failure the configuration layer does not model. The configuration is
     * unchanged either way, which is why both leave the revision meaningful.
     */
    FAILED
}
