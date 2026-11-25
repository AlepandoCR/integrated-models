package pandastic.studio.models.internal.spi;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.render.ModelBone;
import pandastic.studio.models.internal.rig.ModelBoneLink;

import java.util.Collection;
import java.util.List;

/**
 * One loaded model, reduced to the four questions this plugin asks about a model it does not own.
 *
 * <h2>Lazy on purpose, and that is load-bearing</h2>
 * An interface with four methods rather than a record with four components, because two of them cost
 * wildly different amounts and the callers are deliberately split along that line.
 * {@link #boneNames()} and {@link #boneLinks()} walk a hierarchy and read names; {@link #bones()}
 * reconstructs every bone's rest transform, which is three vector copies and a quaternion composition per
 * bone. {@code RigFactory} derives a rig from names alone and
 * {@link pandastic.studio.models.internal.rig.ModelBoneSource} documents that paying a 256-bone model's
 * bind geometry to answer a question about names would be the wrong trade. An eager value would charge
 * every caller for the most expensive answer.
 *
 * <h2>No caching, by contract</h2>
 * Every method answers from whatever the renderer holds at the moment it is asked, and an implementation
 * may not hold a copy between calls. A rendering library reloads its own models on its own operator
 * command and reports that to nobody, so a cached list has no event that could invalidate it and would go
 * stale with nothing to point at — the symptom being a menu offering a model the renderer no longer has,
 * which then fails when a player presses a button rather than when the list was built.
 *
 * <p>Nor may a {@code ModelDefinition} be retained: it is obtained from
 * {@link ModelRenderBackend#definition(String)} at the point of use and dropped. The lists it returns are
 * plain values and may be kept.
 *
 * <h2>The order of the bone lists is part of the contract</h2>
 * A parent always precedes its children in {@link #bones()} and {@link #boneLinks()}, and the two are in
 * the same order as each other. Callers depend on it in two different ways: the rig derivation's "the
 * first acceptable bone" is only "the most root-ward acceptable bone" while the order holds, and
 * {@code RigBindPose}'s hierarchy walk resolves a chain in a single pass because of it.
 */
public interface ModelDefinition {

    /** The id this model is registered under, as the renderer reports it. */
    @NotNull String modelId();

    /**
     * Every bone's bare name, a parent always before its children.
     *
     * <p>Bare means with any behavioural tag prefix stripped, which is the identity a {@link
     * pandastic.studio.models.api.rig.Rig} keys on — see {@code Rig#normalise} for why a rig must never
     * key on the raw renderer identifier.
     *
     * <p>The cheap path for validation: callers are
     * {@link pandastic.studio.models.internal.render.ModelSkeleton#requireBindable} and
     * {@code ModelBinderImpl.bind}, neither of which spawns anything.
     */
    @NotNull List<String> boneNames();

    /**
     * Every bone's name, its parent and whether the renderer will give it a display, a parent always
     * before its children.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.rig.ModelBoneSource#renderer()}, through which
     * {@code RigFactory} derives a rig without spawning anything. The display bit is the one fact a
     * derivation cannot get from a name: a bone with no display can be posed and will not move, so a
     * derivation that could not see it would produce rigs that bind and do nothing.
     */
    @NotNull List<ModelBoneLink> boneLinks();

    /**
     * Every bone's rest transform, a parent always before its children.
     *
     * <p>The expensive answer. Callers:
     * {@link pandastic.studio.models.internal.render.ModelSkeleton#of(String)}, and through it
     * {@code ModelBinderImpl.bind} and {@code DerivedLocomotionStageSource.stageFor}.
     *
     * <p>A backend reproduces the rest transform <em>its own renderer</em> would send for an unanimated
     * bone, in this plugin's convention: positions in blocks, rotations as absolute quaternions composed
     * down the hierarchy, and the model-scale multiplier left out because it is a property of a live
     * instance rather than of the model.
     */
    @NotNull List<ModelBone> bones();

    /**
     * Every animation id this model declares, in the renderer's own order.
     *
     * <p>Caller: {@link pandastic.studio.models.internal.menu.ModelLibrary#renderer()}. Empty when the
     * model declares none.
     */
    @NotNull Collection<String> animationIds();
}
