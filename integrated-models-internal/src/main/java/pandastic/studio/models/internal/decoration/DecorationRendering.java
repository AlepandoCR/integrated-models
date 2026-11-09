package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.internal.binding.RenderBinding;

import java.util.UUID;

/**
 * The renderer half of a live decoration: everything an entity-backed binding gets from its entity.
 *
 * <p>It extends {@link RenderBinding} rather than restating it, because the pose path and the teardown
 * ordering are identical — a sink to write poses through, a detach that stops writing without touching
 * the world, and a close that releases the tracker, in that order. What a decoration adds is the three
 * operations an entity would otherwise have performed on the renderer's behalf: it moves, and it is
 * spawned and despawned per viewer because nothing else tracks who can see it.
 *
 * <h2>Why these three are on the rendering rather than on the anchor</h2>
 * All three are operations on a <em>live tracker</em>. An anchor that carried them would have to answer
 * for what they do before anything is open, and every answer to that is a silent no-op — which is the
 * shape of bug that gets found by a decoration standing invisible in the world. Here they cannot be
 * reached before {@link DecorationAnchor#open} has returned something.
 *
 * <h2>All three are region-free</h2>
 * Moving sends a teleport packet to whoever holds the model; showing and hiding send spawn and despawn
 * packets to one connection. A player's connection is not region state, so none of this is bound to the
 * thread that owns the decoration's chunk — which is the whole point of a model with no entity in it.
 * {@link #close()} is the exception, and it is on {@link RenderBinding} where its rule is already
 * documented.
 */
public interface DecorationRendering extends RenderBinding {

    /**
     * Moves the rendered model to {@code placement}.
     *
     * <p>Safe from any thread. A move to the position it already occupies is free: the renderer compares
     * before it builds a packet.
     *
     * @param placement where the decoration now stands
     */
    void moveTo(@NotNull DecorationPlacement placement);

    /**
     * Spawns every bone for one viewer and sends their complete state.
     *
     * <p>Not a resume. Transforms go out as deltas against a per-viewer baseline, so a viewer who was
     * merely never sent the earlier updates would keep a pose nothing will ever correct.
     *
     * @param viewer the player to spawn for
     * @return {@code false} if the player is not online or the tracker is closed, in which case nothing
     *         was sent and the caller's viewer bookkeeping should not claim otherwise
     */
    boolean show(@NotNull UUID viewer);

    /**
     * Despawns every bone for one viewer.
     *
     * @param viewer the player to despawn for
     * @return {@code false} if the player is not online or the tracker is closed
     */
    boolean hide(@NotNull UUID viewer);
}
