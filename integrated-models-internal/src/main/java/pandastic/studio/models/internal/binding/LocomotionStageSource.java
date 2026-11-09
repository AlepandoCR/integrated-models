package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.LocomotionStage;

/**
 * Supplies the walk cycle for a rig, or says it has none.
 *
 * <h2>Why this is a seam and not a field on the rig</h2>
 * A {@link pandastic.studio.models.internal.sim.LocomotionStage} owns a mutable
 * {@link pandastic.studio.models.api.locomotion.GaitState}, so it belongs to exactly one model — two
 * models sharing one would walk in lockstep and fight over each other's stance flags. A rig, by
 * contrast, is immutable and shared by every model that uses it. So the thing a rig can carry is the
 * <em>description</em> of a gait, and the thing a binding needs is a fresh stage per model, which means
 * something has to make one on demand. This is that something.
 *
 * <p>It is also where the gait becomes optional. A decoration — a banner, a hanging chain, a floating
 * crystal — has no locomotion at all, and {@link pandastic.studio.models.internal.sim.ModelSimulation}
 * already treats a null stage as "this model does not walk" rather than as an error. Returning null is
 * therefore a normal answer, not a failure.
 */
@FunctionalInterface
public interface LocomotionStageSource {

    /**
     * A stage for a model driving {@code rig}, or {@code null} if that rig has no gait.
     *
     * <p>Called once per spawn, on the anchor's owning thread, and the result belongs to that one model.
     * An implementation that returned a shared instance would be a defect — see the class note.
     *
     * @throws IllegalArgumentException if the rig's gait description is inconsistent with the rig, which
     *                                 is a content error and should surface at bind time
     */
    @Nullable LocomotionStage stageFor(@NotNull Rig rig);

    /**
     * The source for a server whose rigs carry no gait description yet.
     *
     * <p>Every model bound through it gets secondary motion and no walk cycle, which is exactly right
     * for the decorative rigs the rig file format currently describes.
     */
    @NotNull LocomotionStageSource NONE = rig -> null;
}
