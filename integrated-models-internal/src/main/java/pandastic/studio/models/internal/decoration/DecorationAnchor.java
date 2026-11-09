package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.ModelAnchor;

import java.util.UUID;

/**
 * One world, and the right to create a tracker standing in it.
 *
 * <p>The decoration layer's counterpart to {@link ModelAnchor}, and the only place a platform type or a
 * renderer type may appear. Everything above this seam deals in a {@link DecorationPlacement} — five
 * numbers — which is what lets the handle, the driver loop and the whole lifecycle be exercised without
 * a server.
 *
 * <h2>Why it is not a {@link ModelAnchor}</h2>
 * {@code ModelAnchor} is shaped around a thing that moves on its own and can vanish without being told
 * to: it is <em>read</em> once per tick through a sampler it schedules itself, and it reports its own
 * disappearance through a retired callback that the platform runs in critical code. A decoration has
 * none of that. Its position changes only when a caller changes it, so there is nothing to sample and
 * no per-decoration task to schedule; and a position cannot be removed, so there is no retirement and no
 * teardown path that may not touch the world. Implementing the entity seam here would mean three methods
 * whose honest implementation is "this cannot happen", which is how a layer acquires unreachable
 * branches.
 *
 * <h2>Why every method takes the placement</h2>
 * On a regionised server the thread that owns a position is a property of its <em>chunk</em>, and a
 * decoration can be moved from one region into another. An anchor that remembered a placement would
 * therefore have two copies of a value the handle already owns, and the two would disagree for exactly
 * as long as a move took. Passing it in makes an anchor stateless, shareable between every decoration in
 * a world, and incapable of answering a thread question about the wrong position.
 */
public interface DecorationAnchor {

    /**
     * The world this anchor places decorations in.
     *
     * @return the world's unique id
     */
    @NotNull UUID worldId();

    /**
     * Whether the calling thread is the one allowed to mutate world state at {@code placement}.
     *
     * <p>The guard on this layer's own entry point, because the platform has no strict checking mode:
     * an unguarded call from the wrong thread produces no diagnostic, only a listener running somewhere
     * it was not written to run.
     *
     * @param placement the position whose owning region is in question
     * @return whether this thread owns that region
     */
    boolean isOwnedByCurrentThread(@NotNull DecorationPlacement placement);

    /**
     * Runs {@code work} on the thread allowed to mutate world state at {@code placement}, soon.
     *
     * <p>For the one piece of teardown that is not region-free — releasing the tracker, which fires a
     * platform event — when the teardown was triggered from somewhere else. That is the normal case for
     * a plugin shutting down.
     *
     * @param placement where the work has to happen
     * @param work      what to run
     * @return {@code false} if there is no such thread, meaning {@code work} will never run
     */
    boolean submit(@NotNull DecorationPlacement placement, @NotNull Runnable work);

    /**
     * Creates the renderer-side binding for {@code rig} standing at {@code placement}.
     *
     * <p>Resolves every rig bone to a cached display transformer and fails with the complete list of
     * names that did not resolve rather than the first. Takes ownership of whatever it created: a bind
     * that throws leaves nothing standing in the world.
     *
     * <p>Must run on the owning thread — see {@link #isOwnedByCurrentThread}.
     *
     * @param rig       the rig to drive
     * @param placement where the decoration stands
     * @return the live rendering
     * @throws IllegalStateException if the renderer has no model by the rig's name, or any bone of the
     *                               rig cannot be driven
     */
    @NotNull DecorationRendering open(@NotNull Rig rig, @NotNull DecorationPlacement placement);

    /**
     * This anchor in one short phrase, for a log line or a failure message.
     *
     * @return a description naming the world
     */
    @NotNull String describe();
}
