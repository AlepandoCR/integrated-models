package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * One thing a creature knows how to do.
 *
 * <h2>The lifecycle, and which method the server actually asks</h2>
 * The brain holds a prioritised list of behaviours per activity. On each tick of an active activity it
 * walks that list in priority order and, for every behaviour that is not already running, asks
 * {@link #canStart}. The first one that says yes starts, and keeps being ticked until it says it is
 * finished, exceeds {@link #maxDurationTicks()}, or its activity stops. So:
 *
 * <ol>
 *   <li>{@link #canStart} — a <em>test</em>. Called every tick while this behaviour is not running.
 *       It must be cheap and it must not change anything: a test that publishes an intent or writes a
 *       memory does so on every tick that something higher priority is running, which is the
 *       hardest-to-find version of this mistake.</li>
 *   <li>{@link #start} — once, immediately after a successful test.</li>
 *   <li>{@link #tick} — every tick while running, with the tick count since {@link #start}.</li>
 *   <li>{@link #shouldContinue} — every tick, before {@link #tick}. Saying no stops the behaviour
 *       and makes it a candidate to start again next tick.</li>
 *   <li>{@link #stop} — once, however the behaviour ended, including when its activity was switched
 *       away from underneath it or the plan was uninstalled. The place to release anything
 *       {@link #start} took.</li>
 * </ol>
 *
 * <h2>Declaring required memories is not documentation</h2>
 * {@link #requiredMemories()} has two jobs. It tells {@link AiPlan} which memories to build the
 * creature's brain with — a memory nothing declares is a memory a write silently discards — and it
 * tells the server not to even consider starting this behaviour while one of them is empty, which is a
 * check done once by the brain rather than at the top of every {@link #canStart}.
 *
 * <h2>A body may not throw, and if it does the layer catches it</h2>
 * A behaviour runs inside the creature's tick. An exception escaping it would, unhandled, take the
 * creature's tick with it, and because the brain ticks every tick it would do so every tick. So the
 * layer wraps every call: the first failure is logged with the behaviour's {@link #name()}, repeats are
 * counted rather than logged, and a behaviour that keeps failing is stopped. That is a backstop, not a
 * licence — a behaviour that relies on it is one whose creature silently does nothing.
 *
 * <h2>Threading</h2>
 * Every method is called on the thread that owns the creature, and is handed an {@link AiContext} that
 * is only valid for the duration of the call. See {@link AiContext}.
 */
public interface AiBehaviour {

    /**
     * {@link #maxDurationTicks()} meaning "until it stops itself".
     *
     * <p>A structural zero: the server's own behaviours express an unbounded run as the absence of a
     * duration, and zero is how that is spelled here. Not a tunable default — a behaviour that should
     * time out says so with a real number, and one that should not says so with this.
     */
    long UNBOUNDED = 0L;

    /**
     * A short name for diagnostics.
     *
     * <p>Appears in the plan's validation messages and in the one log line a failing behaviour is
     * allowed, which is the only place anybody will ever see it — so it should name the decision
     * ("follow-owner") rather than the class.
     *
     * @return the name, non-blank
     */
    @NotNull String name();

    /**
     * Memories that must hold a value before this behaviour is considered at all.
     *
     * <p>Empty by default, which means "consider me whenever my activity is active". Every memory named
     * here is automatically part of the plan, so there is no separate declaration step.
     *
     * @return the required memories; immutable, and the same set every call
     */
    default @NotNull Set<AiMemory<?>> requiredMemories() {
        return Set.of();
    }

    /**
     * Memories this behaviour reads or writes in its body.
     *
     * <p>Distinct from {@link #requiredMemories()}: those gate the behaviour, these merely have to
     * exist. A cooldown the behaviour <em>sets</em> belongs here — putting it in the required set would
     * mean the behaviour only runs once the cooldown it has not set yet is present.
     *
     * <p>This is the declaration that turns "my write did nothing" into a plan that refuses to build,
     * so it is worth getting right even though nothing checks that the body matches it.
     *
     * @return the memories used; immutable, and the same set every call
     */
    default @NotNull Set<AiMemory<?>> usedMemories() {
        return Set.of();
    }

    /**
     * Whether this behaviour wants to start now.
     *
     * <p>A pure test. See the class documentation for why.
     *
     * @param context the creature and what it can see, valid only for this call
     * @return whether to start
     */
    boolean canStart(@NotNull AiContext context);

    /**
     * Called once when this behaviour starts.
     *
     * @param context the creature and what it can see, valid only for this call
     */
    default void start(@NotNull AiContext context) {
        // Most behaviours have nothing to set up; the default keeps them to one method.
    }

    /**
     * Called every tick while this behaviour runs.
     *
     * @param context      the creature and what it can see, valid only for this call
     * @param ticksRunning ticks since {@link #start}, starting at zero on the first tick
     */
    void tick(@NotNull AiContext context, long ticksRunning);

    /**
     * Whether this behaviour wants to keep running.
     *
     * <p>Checked before each {@link #tick}. The default keeps running until
     * {@link #maxDurationTicks()} or the activity changes, which is right for a behaviour whose end
     * condition is a timeout and wrong for one chasing something that can disappear.
     *
     * @param context      the creature and what it can see, valid only for this call
     * @param ticksRunning ticks since {@link #start}
     * @return whether to continue
     */
    default boolean shouldContinue(@NotNull AiContext context, long ticksRunning) {
        return true;
    }

    /**
     * Called once when this behaviour stops, however it stopped.
     *
     * <p>Including when the plan is uninstalled and when the activity was switched away from, so this
     * is the only place a behaviour can rely on being told it is finished.
     *
     * @param context the creature and what it can see, valid only for this call
     */
    default void stop(@NotNull AiContext context) {
        // Symmetric with start(): most behaviours hold nothing that needs releasing.
    }

    /**
     * Ticks after which this behaviour is stopped regardless of {@link #shouldContinue}.
     *
     * <p>{@link #UNBOUNDED} for no limit. A limit is the cheap insurance against a behaviour whose end
     * condition turns out to be unreachable — the creature gets stuck in it forever, and because it is
     * the highest-priority behaviour that can start, nothing else ever gets a turn.
     *
     * @return the tick limit, or {@link #UNBOUNDED}
     */
    default long maxDurationTicks() {
        return UNBOUNDED;
    }
}
