package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.ModelSkeleton;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.render.RigBoneResolution;

import java.util.List;

/**
 * Answers "can this rig bind to its model" without spawning anything.
 *
 * <h2>Offline first, always</h2>
 * A rig is written by hand against a model somebody else exported, so the failure that matters is a bone
 * name that stopped matching — a group renamed in Blockbench breaks every rig naming it. There are two
 * moments that could discover this: binding, which is cheap and happens before anything exists, and
 * spawning, which has already created a tracker and spawned displays by the time it finds out. The first
 * produces one error message naming every broken bone; the second produces a model that appears in the
 * world and then does not move.
 *
 * <p>So the binder validates here, before it hands back a handle, and the live bind in
 * {@link pandastic.studio.models.internal.render.TrackerPoseSink} is the backstop rather than the
 * discovery. The two check different things — this one checks the model definition, that one checks a
 * live tracker's bones and their displays — and a rig that fails this one can never pass that one.
 *
 * <h2>A seam, because the renderer is not constructible in a test</h2>
 * {@link #renderer} is the production validator and reaches {@code BetterModel}'s static registry.
 * {@link #over} does exactly the same resolution against a bone list handed in, which makes the
 * all-of-them-at-once failure reporting testable, and is also the right validator for a caller that
 * already holds the model's bone names.
 */
@FunctionalInterface
public interface RigBindValidator {

    /**
     * Throws unless {@code rig} can bind.
     *
     * @throws IllegalStateException naming the rig, the model and <em>every</em> bone that failed —
     *                               missing and ambiguous reported separately, because a missing bone
     *                               does nothing whereas an ambiguous one silently drives the wrong bone
     */
    void requireBindable(@NotNull Rig rig);

    /**
     * The validator that asks the live renderer.
     *
     * @param settings supplies how many of the model's own bone names a failure message may list, read
     *                 once per validation
     */
    static @NotNull RigBindValidator renderer(@NotNull RenderSettingsSource settings) {
        return rig -> ModelSkeleton.requireBindable(rig, settings.current());
    }

    /**
     * The same validation against a bone list the caller already has.
     *
     * <p>{@code modelBoneNames} are bare names and need no normalising — both sides go through
     * {@link Rig#normalise}. Copied defensively, so a validator cannot be invalidated by a caller
     * mutating the list it was built from.
     */
    static @NotNull RigBindValidator over(
            @NotNull List<String> modelBoneNames,
            @NotNull RenderSettingsSource settings
    ) {
        final List<String> names = List.copyOf(modelBoneNames);
        return rig -> RigBoneResolution.resolve(rig, names).requireBindable(settings.current());
    }
}
