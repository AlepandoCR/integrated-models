package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;

/**
 * Where a model is, who is allowed to ask, and what renders it.
 *
 * <h2>The seam the whole Folia argument rests on</h2>
 * On a regionised server an entity may only be touched from the thread ticking the region that owns it,
 * and reading its position from anywhere else is <em>undefined</em> — not racy, undefined. The platform
 * does not protect against it: {@code TickThread.ensureTickThread} only fires where Folia happened to
 * place it, so a cross-region read fails silently and produces a torn coordinate rather than an
 * exception. Two consequences shape this interface:
 *
 * <ol>
 *   <li>Every read of the anchor goes through {@link #readInto}, which is contractually confined to the
 *       owning thread, and leaves as a value. The simulation thread never sees an anchor at all — it
 *       sees an immutable {@link pandastic.studio.models.api.physics.PhysicsDriver} a region thread
 *       published.</li>
 *   <li>The anchor owns its own dispatcher. {@link #schedule} is the only way to get code onto the
 *       thread allowed to read it, and for an entity it is the <em>entity</em> scheduler rather than the
 *       region scheduler, because an entity scheduler follows its entity across region and world
 *       changes while a region scheduler is pinned to the region the task was posted from.</li>
 * </ol>
 *
 * <h2>Why "anchor" and not "entity"</h2>
 * A rigged model does not need an entity; it needs a position, a yaw, a stance and a thread that may
 * read them. An entity is one source of those. A fixed location is another, and so is a position some
 * other plugin computes. Naming the seam after the role rather than the implementation is what makes a
 * free-floating model a second implementation of this interface instead of a second copy of
 * {@link RiggedModelImpl} with the entity handling cut out.
 *
 * <p>It also carries {@link #render}, which looks like a different responsibility and is not: the
 * renderer's tracker kinds split along exactly this line — an entity-attached tracker that follows its
 * entity, and a standalone one that sits at a location. The anchor is the only thing that knows which
 * kind it is, so it is the only thing that can create one.
 *
 * <h2>Implementations are the only place a platform type may appear</h2>
 * Everything else in this package is arithmetic, state and ordering. {@link EntityAnchor} is the Bukkit
 * implementation and the only unfakeable one; a test substitutes its own.
 */
public interface ModelAnchor {

    /**
     * Fills {@code sample} with this anchor's current position, yaw and stance.
     *
     * <p><strong>May only be called from the thread that owns this anchor</strong> — in practice, from
     * the body of a task {@link #schedule} dispatched. Everything about the value is undefined
     * otherwise.
     *
     * @param sample a buffer the caller owns and reuses; untouched if this returns {@code false}
     * @return {@code false} if the anchor no longer exists, in which case the model driving it should
     *         stop. This is the second of the two ways a binding learns its anchor is gone, and the one
     *         that catches an entity that is dead but whose scheduler has not been retired yet
     */
    boolean readInto(@NotNull AnchorSample sample);

    /** Whether this anchor still exists. Only meaningful on the owning thread. */
    boolean isAlive();

    /**
     * Whether the calling thread is the one allowed to read and mutate this anchor.
     *
     * <p>The guard on this layer's own entry points. The platform has no strict checking mode, so an
     * unguarded entry point that happens to be called from the wrong thread produces a corrupt read and
     * no diagnostic — which is why spawning refuses rather than trusting its caller.
     */
    boolean isOwnedByCurrentThread();

    /**
     * Dispatches {@code body} every {@code periodTicks} on the thread allowed to read this anchor.
     *
     * @param body        the sampler, run on the owning thread; never concurrently with itself
     * @param retired     run instead, once, if the anchor is removed. For an entity this is the
     *                    <em>only</em> notification the platform gives that it is gone, which is what
     *                    makes it the authoritative teardown trigger. The platform documents it as
     *                    critical code: it must not remove entities, load chunks or load worlds, so the
     *                    callback a binding installs here does cheap bookkeeping and nothing else
     * @param periodTicks ticks between executions; at most once per tick is useful, because the anchor's
     *                    own state only changes that often
     * @return the handle to cancel with, or {@code null} if the anchor was already gone — in which case
     *         neither callback will ever run, so the caller owns the unwind
     */
    @Nullable AnchorTask schedule(@NotNull Runnable body, @NotNull Runnable retired, long periodTicks);

    /**
     * Runs {@code work} on the thread allowed to touch this anchor's world state, soon.
     *
     * <p>For the one piece of teardown that mutates the world — releasing the tracker — when the
     * teardown was triggered from somewhere else, which is the normal case for a plugin shutting down.
     *
     * @return {@code false} if there is no such thread any more, meaning the anchor is gone and
     *         {@code work} will never run. The caller decides what that means; for a tracker release it
     *         means the renderer has already torn it down from its own entity-removal hook
     */
    boolean submit(@NotNull Runnable work);

    /**
     * Creates the renderer-side binding for {@code rig} against this anchor.
     *
     * <p>Resolves every rig bone to a cached display transformer, and fails with the complete list of
     * names that did not resolve rather than the first one. Must run on the owning thread: creating a
     * tracker reads the anchor's position and spawns the renderer's displays.
     *
     * @throws IllegalStateException if the renderer has no model by the rig's name, or any bone of the
     *                               rig cannot be driven
     */
    @NotNull RenderBinding render(@NotNull Rig rig);

    /** This anchor in one short phrase, for a log line or a failure message. */
    @NotNull String describe();
}
