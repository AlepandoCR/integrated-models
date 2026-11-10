package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.NotNull;

/**
 * One bone of a model as a rig derivation needs to see it: its bare name, its place in the hierarchy,
 * and whether the renderer will give it anything to drive.
 *
 * <h2>Three fields, and the third is the one that matters</h2>
 * A derived rig has to be bindable without anybody checking it by hand, and the binding layer refuses a
 * rig for three distinct reasons: a bone the model does not have, a bone name that matches more than one
 * model bone, and a bone with <strong>no display</strong>. The first two are answerable from the names
 * alone. The third is not, and it is the common one: a group with no cubes in it — a pivot, a locator,
 * the empty root a rigger parents everything under — gets no display entity, so a rig naming it cannot
 * move anything and {@code TrackerPoseSink.bind} says so and fails. A derivation that could not see that
 * bit would pick such a bone roughly as often as models have empty roots, which is most of them.
 *
 * <p>So it travels with the name, which is also what keeps
 * {@link ModelBoneSource} the only type in this package that has to know how the renderer decides it.
 *
 * <h2>Hierarchy order is the contract, not the parent index</h2>
 * A source hands these out with a parent always before its children, which is what lets a derivation
 * take "the first acceptable bone" and mean "the most root-ward acceptable bone". {@link #parentIndex()}
 * is what makes that claim <em>checkable</em> — a list whose bones precede their parents is a defect in
 * the source rather than a different, quietly worse, choice of bone.
 *
 * @param name        the bone's bare name in the model, behavioural tag prefixes already stripped. Not
 *                    normalised: normalising is {@link pandastic.studio.models.api.rig.Rig#normalise}'s
 *                    job and a source should hand over what the model says
 * @param parentIndex index of this bone's parent in the same list, which must precede it, or
 *                    {@link #NO_PARENT} for the root of a tree
 * @param hasDisplay  whether the renderer will create a display for this bone, and therefore whether a
 *                    rig that names it can drive it
 */
public record ModelBoneLink(@NotNull String name, int parentIndex, boolean hasDisplay) {

    /**
     * {@link #parentIndex()} of a bone at the top of a model tree.
     *
     * <p><strong>A sentinel, not a tunable</strong>, like the two it deliberately agrees with —
     * {@code RigBone.NO_PARENT} and {@code ModelBone.NO_PARENT}. It is the one value that cannot be a
     * valid index into the bone list, which is the whole of its meaning. Declared here rather than
     * borrowed so that a rig derivation needs nothing from the bind-time geometry layer: the only thing
     * it reads a model for is names.
     */
    public static final int NO_PARENT = -1;

    public ModelBoneLink {
        if (name.isBlank()) {
            // A nameless bone cannot be named by a rig, so it cannot be the bone a derivation picks and
            // it cannot be matched against one. Refusing it here means the derivation never has to.
            throw new IllegalArgumentException("A model bone needs a name");
        }
        if (parentIndex < NO_PARENT) {
            throw new IllegalArgumentException("Invalid parent index: " + parentIndex);
        }
    }

    /** Whether this bone is at the top of a model tree. */
    public boolean isRoot() {
        return this.parentIndex == NO_PARENT;
    }
}
