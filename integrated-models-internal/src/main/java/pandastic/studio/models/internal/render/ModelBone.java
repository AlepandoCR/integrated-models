package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One bone of a renderer model, reduced to the rest transform its display sits at and its place in the
 * model's hierarchy.
 *
 * <h2>Why this type exists</h2>
 * It is the seam that keeps the geometry arithmetic testable. Everything that reads BetterModel —
 * {@link ModelSkeleton} — produces a list of these and then stops; everything that computes transforms
 * from a pose — {@link RigBindPose} — consumes them and names no renderer type. A {@code RendererGroup}
 * cannot be constructed off a live server (its constructor reaches for {@code BetterModel.platform()}
 * and the plugin config), so a composer written against it would be untestable by construction.
 *
 * <h2>Rest, not current</h2>
 * These are the values the renderer would send for a model standing in its authored pose with no
 * animation playing: absolute, already composed down the hierarchy, and expressed in the display's own
 * frame. They are read once at bind time and never again, which is what makes the per-frame path
 * arithmetic over arrays.
 *
 * @param name         the bone's bare name in the model, tags already stripped
 * @param parentIndex  index of this bone's parent in the same list, which must precede it, or
 *                     {@link #NO_PARENT} for the root of a tree
 * @param restPosition the display position at rest
 * @param restRotation the absolute display rotation at rest, parent rotations already folded in
 * @param restScale    the display scale at rest
 */
public record ModelBone(
        @NotNull String name,
        int parentIndex,
        @NotNull Vector3f restPosition,
        @NotNull Quaternionf restRotation,
        @NotNull Vector3f restScale
) {

    /**
     * {@link #parentIndex()} of a bone at the top of a model tree.
     *
     * <p><strong>A sentinel, not a tunable.</strong> It is the one value that cannot be a valid index
     * into the bone list, which is the whole of its meaning; configuring it would be configuring what
     * "has no parent" is encoded as, and any other choice would either collide with a real index or stop
     * the {@code parentIndex < NO_PARENT} validation below from rejecting garbage.
     */
    public static final int NO_PARENT = -1;

    public ModelBone {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A model bone needs a name");
        }
        if (parentIndex < NO_PARENT) {
            throw new IllegalArgumentException("Invalid parent index: " + parentIndex);
        }
        // Copied because the renderer hands out its live internal vectors — RendererGroup#getPosition
        // returns the field itself, not a view — and because this record is treated as immutable
        // everywhere downstream. Bind-time cost only.
        restPosition = new Vector3f(restPosition);
        restRotation = new Quaternionf(restRotation);
        restScale = new Vector3f(restScale);
    }

    /** Whether this bone is at the top of a model tree. */
    public boolean isRoot() {
        return this.parentIndex == NO_PARENT;
    }
}
