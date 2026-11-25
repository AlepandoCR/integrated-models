package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;

/**
 * Reads the renderer's <em>live</em> animated rotation for the bones a clip owns.
 *
 * <h2>The one thing on this side of the seam a renderer has to be asked for</h2>
 * Everything else the render layer needs is static: a model's authored skeleton is read once at bind
 * time and turned into {@link ModelBone}s, and from there the whole emission path is arithmetic over
 * arrays. The animated orientation is the exception, because it is whatever the renderer's own animation
 * pass has advanced to in this frame — it cannot be reconstructed from the blueprint and it cannot be
 * cached. So it is a seam, and it is the narrowest one that answers the question: fill in a delta per
 * owned bone, say whether anything was filled.
 *
 * <p>Nothing here names a renderer type, which is what keeps {@link RigBindPose} and its tests free of
 * one. The implementation lives in the backend module beside the sink that uses it.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The caller has already called {@link AnimatedRotations#clear()}. An implementation writes only
 *       the bones it could read, and a bone it leaves alone correctly reads as "not being animated"
 *       rather than as whatever it was last pass.</li>
 *   <li>Only {@link AnimatedRotations#isOwned(int)} bones may be written;
 *       {@link AnimatedRotations#set} refuses the rest.</li>
 *   <li>Called once per emission, from the thread that owns the pose. It must therefore be safe off the
 *       server tick and must not touch an entity, a world or a block — reading a renderer's own
 *       interpolated bone state is legal there, reading the entity it stands on is not.</li>
 * </ul>
 *
 * @see AnimationOwnership#COMPOSE
 */
@FunctionalInterface
public interface AnimatedRotationSource {

    /**
     * Writes the current animation delta of every owned bone of {@code dest}.
     *
     * @param dest the overlay to fill, already cleared
     * @return whether any bone was written at all. {@code false} is a normal answer — a model standing
     *         with no clip playing writes nothing — and lets a caller skip the composition entirely
     */
    boolean sample(@NotNull AnimatedRotations dest);

    /**
     * The source for a binding whose rig owns no animated bones, or whose renderer cannot be asked.
     *
     * <p>Writes nothing, so every bone composes against its authored rest orientation and the emission
     * path behaves exactly as it did before this seam existed.
     */
    @NotNull AnimatedRotationSource NONE = dest -> false;
}
