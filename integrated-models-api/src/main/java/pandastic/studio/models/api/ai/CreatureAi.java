package pandastic.studio.models.api.ai;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Installing a plan on a creature. The one entry point into the AI layer.
 *
 * <h2>Why installing returns a future instead of a controller</h2>
 * Because registering behaviour on a creature is only legal on the thread that owns it, and the layer
 * will not trust a caller to have arranged that. On a regionised server the creature's thread is
 * whichever region thread is ticking the region it is standing in, which the caller frequently is not
 * on — a plugin installing a plan from a load routine, a command, or a pool thread is the normal case
 * rather than the exception.
 *
 * <p>What makes this worth forcing rather than documenting is that registering is the one operation in
 * this area with <strong>no thread checking at all</strong>. The goal selector is a plain set and the
 * brain's maps are plain maps; a registration from the wrong thread does not throw, it interleaves with
 * the creature's own tick and corrupts whichever structure it landed in. There is no mode to turn on
 * that would catch it. So {@link #install} takes the decision away: it validates on the calling thread,
 * dispatches the touching part onto the creature's own scheduler, and hands back a future that
 * completes there.
 *
 * <p>The creature's <em>own</em> scheduler rather than its region's, deliberately: an entity scheduler
 * follows its entity across region and world changes, while a region-scheduled task is pinned to the
 * region it was posted from and would run on the wrong thread the moment the creature walked over a
 * boundary between the post and the run.
 *
 * <h2>Do not block the creature's own thread on the future</h2>
 * {@code install(...).join()} from a region thread deadlocks if that region is the one that owns the
 * creature: the task that would complete the future is queued behind the task that is waiting for it.
 * Use {@code thenAccept}. From any other thread blocking is merely unwise.
 *
 * <h2>Not every creature can be given a brain</h2>
 * {@link #supports(Mob)} is not a courtesy check. A brain only ticks for the twenty or so species whose
 * own tick ticks it; on anything else the brain is installed, is correct, and is never asked a single
 * question — the creature carries on being driven by its goals, and the plan has no effect whatsoever
 * with nothing logged. That is the worst failure available in this area, so the install refuses it, and
 * {@link #supportedSpecies()} exists so the refusal can name the alternatives.
 *
 * <h2>Everything tunable comes from configuration</h2>
 * {@link #getBounds()} reads the live {@code ai.yml}. Take the bounds from here when building a plan,
 * rather than keeping a set: a reload replaces them, and a plan validated against a stale set is a plan
 * that passes a check the operator has since tightened.
 */
public interface CreatureAi {

    /**
     * Whether {@code mob} is a species whose tick ticks its brain.
     *
     * <p>Safe from any thread: it is a test of what the creature <em>is</em>, not of what it is doing.
     *
     * @param mob the creature to test
     * @return whether a plan installed on it would ever run
     */
    boolean supports(@NotNull Mob mob);

    /**
     * The species a plan can be installed on, named as the server names them.
     *
     * <p>For the message a caller shows when {@link #supports(Mob)} says no. The list is the same one
     * {@link #supports(Mob)} consults, so the two cannot disagree.
     *
     * @return the supported species names, immutable
     */
    @NotNull List<String> supportedSpecies();

    /**
     * The limits a plan is checked against, as the operator currently has them.
     *
     * <p>Read this when building a plan rather than caching it; see the class documentation.
     *
     * @return the live bounds
     */
    @NotNull AiBounds getBounds();

    /**
     * Installs {@code plan} on {@code mob}.
     *
     * <p>Validation that needs no creature happens on the calling thread and throws, because a plan
     * that does not fit the bounds is a programming error and a future carrying it would be an error
     * discovered one callback later. Everything that touches the creature happens on its own thread.
     *
     * <p>Replaces any plan already installed on the creature: the previous controller is uninstalled
     * first, on the same thread, so there is never a moment with two plans driving one brain.
     *
     * @param plan what the creature should do
     * @param mob  the creature; must be one {@link #supports(Mob)} accepts
     * @return a future completing on the creature's own thread with the handle, or completing
     *         exceptionally with an {@link IllegalStateException} if the creature was gone by the time
     *         the install ran, if its species cannot be given a brain, or if the server's internals
     *         could not be reached — the last of which means no adapter was loaded for this server
     *         version, and is the one case a caller should degrade over rather than treat as a bug
     * @throws IllegalArgumentException if the plan does not fit the live {@link #getBounds()}
     */
    @NotNull CompletableFuture<AiController> install(@NotNull AiPlan plan, @NotNull Mob mob);
}
