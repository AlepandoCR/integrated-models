package pandastic.studio.models.api.ai;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One plan installed on one creature: the handle a plugin holds onto.
 *
 * <h2>What it owns</h2>
 * The rebuilt brain, the sensors' scheduled tasks, the memory view, and the published intent. None of
 * those are reachable any other way, and all of them are released by {@link #uninstall()}.
 *
 * <h2>Thread rules, which differ per method on purpose</h2>
 * <ul>
 *   <li>{@link #memories()} returns a view whose every method is confined to the thread that owns the
 *       creature. Outside a behaviour or sensor body that is not the thread you are on, so the view
 *       refuses rather than tearing a read. Schedule onto the creature's own scheduler, or publish what
 *       you need as an intent.</li>
 *   <li>{@link #intent()} is safe from any thread, and is the designed way to read a creature's current
 *       decision from somewhere else — the simulation thread included. One volatile read of one
 *       immutable value.</li>
 *   <li>{@link #uninstall()} is safe from any thread, any number of times. It has to be: the most
 *       common caller is the platform telling us the creature is gone, and the second most common is a
 *       plugin shutting down from a thread that owns nothing.</li>
 *   <li>{@link #isInstalled()}, {@link #getPlan()} and {@link #getUniqueIdentifier()} are safe from any
 *       thread.</li>
 * </ul>
 *
 * <h2>Single use</h2>
 * A controller installs once and uninstalls once. There is no reinstall: a plan change is a fresh
 * {@link CreatureAi#install} on the same creature, which rebuilds the brain again. The alternative is a
 * handle that has to decide what happens to sensor tasks dispatched against a brain that no longer
 * exists, and every answer to that is a task writing memories nothing reads.
 *
 * <h2>Uninstalling does not fully undo installing</h2>
 * The brain is restored — rebuilt from the creature's own species factory, carrying the memories it had
 * — but the vanilla goal selectors are not, when the plan cleared them. Goals are registered in the
 * creature's constructor and nothing can re-run that. See {@link AiPlan#clearsVanillaGoals()}.
 */
public interface AiController extends AutoCloseable {

    /** The plan that was installed. Fixed for this handle's life. */
    @NotNull AiPlan getPlan();

    /**
     * The creature this plan is driving.
     *
     * <p>Safe to hold, because the controller holds it too. Not safe to <em>read</em> from a thread
     * that does not own it, which is a property of the creature rather than of this method.
     */
    @NotNull Mob getMob();

    /**
     * This installation's identity, distinct from the creature's.
     *
     * <p>Two plans on one creature are not supported — the second install replaces the first — but an
     * installation still needs a name of its own so that a log line about a plan that has already been
     * replaced is distinguishable from one about its replacement.
     */
    @NotNull UUID getUniqueIdentifier();

    /**
     * What the creature remembers.
     *
     * <p>Owning-thread only; see the class documentation and {@link AiMemoryView}.
     *
     * @return the view, valid until {@link #uninstall()}
     */
    @NotNull AiMemoryView memories();

    /**
     * The creature's current decision.
     *
     * <p>Safe from any thread. {@link AiIntent#NONE} before any behaviour has published, and after
     * {@link #uninstall()}.
     *
     * @return the live intent, never {@code null}
     */
    @NotNull AiIntent intent();

    /** Whether this plan is still driving the creature. */
    boolean isInstalled();

    /**
     * Stops every sensor, stops every running behaviour, and restores the creature's own brain.
     *
     * <p>Idempotent and safe from any thread. The work that has to happen on the creature's thread is
     * dispatched there; the work that does not — cancelling tasks, clearing the intent — happens
     * immediately, so that a caller on a pool thread is not left wondering whether the creature is still
     * being driven when this returns.
     *
     * <p>Cancelling is not optional bookkeeping. A repeating task dispatched through an entity's
     * scheduler is <strong>never</strong> cancelled for a plugin on disable, unlike a main-scheduler
     * one, so a sensor whose handle was dropped keeps running after the plugin's classloader has been
     * closed and fails on the first lazy class load after that — during shutdown, pointing nowhere near
     * the creature that leaked it.
     */
    void uninstall();

    /**
     * {@link #uninstall()}, so a controller can be managed with try-with-resources.
     *
     * <p>Declared without the checked exception {@link AutoCloseable} permits: nothing about releasing
     * an installation can fail in a way a caller could handle, and a checked exception here would force
     * an empty catch block at every call site.
     */
    @Override
    default void close() {
        uninstall();
    }
}
