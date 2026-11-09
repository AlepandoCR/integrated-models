package pandastic.studio.models.api.render;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.Rig;

/**
 * Where a finished {@link Pose} goes — the single seam between simulation and rendering.
 *
 * <p>This interface exists so the physics layer never names a renderer. Everything upstream of it is
 * arithmetic over {@code float[]} with no platform dependency, which is what makes it legal to run on
 * an arbitrary thread; everything downstream is packets. Keeping the boundary to one method keeps that
 * property checkable rather than aspirational.
 *
 * <h2>Called off the server tick</h2>
 * Implementations are invoked from the simulation thread, never from a region thread. An implementation
 * may therefore touch only its own state and a player's connection — not an entity, not a world, not a
 * block. On a regionised server reading an entity's position from here is not merely racy but
 * undefined.
 *
 * <h2>The window is handed in, not decided here</h2>
 * {@code interpolationTicks} comes from {@link EmissionCadence} and must be written alongside the
 * transforms, because the client only re-anchors its tween when the interpolation-start field changes.
 * An implementation that writes transforms but forgets the window produces motion that snaps instead of
 * easing — the single most common way this goes wrong.
 */
@FunctionalInterface
public interface PoseSink {

    /**
     * Publishes {@code pose} for one model.
     *
     * <p>The pose is <strong>borrowed, not given</strong>: it is the simulation's live buffer and will
     * be mutated as soon as this call returns. An implementation that needs the values later must copy
     * them, and must never retain the reference.
     *
     * @param rig                the rig {@code pose} was produced for; its bone count matches the pose's
     * @param pose               bone rotations in radians, valid only for the duration of this call
     * @param interpolationTicks the window to send with the transforms, in client ticks. Zero means
     *                           snap, which is a different mode rather than a shorter window
     */
    void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks);

    /** A sink that discards everything. Useful for a model nobody can see, and in tests. */
    @NotNull PoseSink DISCARDING = (rig, pose, interpolationTicks) -> {
    };
}
