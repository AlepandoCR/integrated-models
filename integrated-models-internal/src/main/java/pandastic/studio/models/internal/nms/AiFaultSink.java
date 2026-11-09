package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;

/**
 * Where a behaviour or sensor body's exception goes, and who decides whether to keep running it.
 *
 * <h2>Why a body that throws must not be allowed to throw</h2>
 * A behaviour runs inside the creature's own tick, from inside the brain's tick, from inside the
 * server's entity tick. An exception escaping it does not fail the behaviour — it fails the creature's
 * tick, and because the brain ticks every tick it fails it again on the next one. On a regionised
 * server that is a repeating exception on a region thread with other players' creatures on it.
 *
 * <p>So the adapter below this seam catches everything a body throws and reports it here. What it must
 * not do is decide what to do about it: the throttling interval and the failure count that stops a body
 * being run are configuration, and nothing below the seam reads configuration.
 *
 * <h2>Why the answer is a boolean and not a second question</h2>
 * The counting has to live with the configuration, so the sink is the only thing that can decide. One
 * call that reports and answers keeps the decision and the count in one place and leaves nothing below
 * the seam to get out of step — an adapter that asked "should I disable this?" separately would be
 * asking about a count it had already changed.
 *
 * <h2>Threading</h2>
 * Called from the thread that owns the creature, which is any region thread. An implementation is
 * therefore shared across region threads and has to be safe for that; the one this plugin installs
 * holds its counters under a monitor, which is affordable because the only path that reaches it is a
 * path where something is already broken.
 */
public interface AiFaultSink {

    /** {@link #failed} kind for a behaviour body. */
    @NotNull String BEHAVIOUR = "behaviour";

    /** {@link #failed} kind for a sensor body. */
    @NotNull String SENSOR = "sensor";

    /**
     * Reports that {@code name} threw, and answers whether to keep running it.
     *
     * @param kind    {@link #BEHAVIOUR} or {@link #SENSOR}; what kind of body failed, so the report can
     *                say so without the caller formatting a sentence
     * @param name    the body's own name, which is the only thing a report can say about it
     * @param failure what it threw
     * @return {@code true} to keep running it, {@code false} once it has failed often enough that the
     *         configured limit says to stop. A caller that gets {@code false} must stop calling that
     *         body; it will not be told again
     */
    boolean failed(@NotNull String kind, @NotNull String name, @NotNull Throwable failure);

    /**
     * Reports that {@code name} ran without throwing.
     *
     * <p>Here rather than inferred, because the limit {@link #failed} answers against is a count of
     * <em>consecutive</em> failures and only the caller knows a body returned normally. A body failing
     * once an hour over something transient should not be stopped on its tenth hour; one failing ten
     * times in ten ticks is broken, and without this call the two are the same number.
     *
     * <p>Expected to be cheap and to be called on the success path of every body invocation: an
     * implementation should do nothing at all for a body that has never failed.
     *
     * @param kind {@link #BEHAVIOUR} or {@link #SENSOR}
     * @param name the body's own name
     */
    void succeeded(@NotNull String kind, @NotNull String name);
}
