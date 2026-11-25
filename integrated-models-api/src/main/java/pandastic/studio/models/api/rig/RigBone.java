package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One bone of a {@link Rig}: a name that must resolve against the real model, its place in the
 * hierarchy, optionally the physics that drive it, and whether the model's own animations already do.
 *
 * @param name           the bone's name in the underlying model, already normalised by {@link Rig}
 * @param parentIndex    index of this bone's parent within the owning rig, or {@link #NO_PARENT}
 * @param physics        the secondary motion applied to this bone, or {@code null} if the rig only
 *                       declares it so that children can hang off it
 * @param animationOwned whether a clip shipped inside the model keyframes this bone; see
 *                       {@link #animationOwned()}
 */
public record RigBone(
        @NotNull String name,
        int parentIndex,
        @Nullable PhysicsProfile physics,
        boolean animationOwned
) {

    /**
     * {@link #parentIndex()} of a bone with no parent inside this rig.
     *
     * <p><strong>Deliberately not configurable.</strong> It is a sentinel, not a bound: the contract is
     * that it collides with no legal index, and {@code -1} meets that by construction because an index
     * is an array offset. Any other value either means the same thing or names a real bone.
     */
    public static final int NO_PARENT = -1;

    public RigBone {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A rig bone needs a name");
        }
        if (parentIndex < NO_PARENT) {
            throw new IllegalArgumentException("Invalid parent index: " + parentIndex);
        }
    }

    /** Whether this bone carries secondary motion. */
    public boolean hasPhysics() {
        return this.physics != null;
    }

    /**
     * Whether a clip shipped inside the model keyframes this bone.
     *
     * <p>A statement about the <em>asset</em>, not a switch: it is true when a rigger put this bone in an
     * animation, and the rig file says so because nothing in this plugin can find out — the clips live
     * in a {@code .bbmodel} in the renderer's folder and the renderer exposes no "which bones does this
     * clip touch" query.
     *
     * <p>It matters because this plugin's pose writer and the renderer's animation pass write the
     * <em>same</em> display. On a bone neither touches twice there is no conflict; on an
     * animation-owned bone the renderer sends {@code bind ∘ animation} while we send
     * {@code bind ∘ physics}, both inside one frame, and the display keeps whichever arrived last. The
     * symptom is a bone that flickers between two poses — which reads as a renderer bug and is in fact
     * two writers disagreeing, and is exactly why this flag exists for the composition layer to read.
     *
     * <p>Combining it with {@link #physics()} is therefore legal but suspicious, and a rig
     * <em>file</em> that does gets a warning naming the bone. It is not refused: a rigger who knows the
     * clip only moves the bone on one axis may mean it.
     */
    public boolean animationOwned() {
        return this.animationOwned;
    }

    /** Whether this bone is a root within its rig. */
    public boolean isRoot() {
        return this.parentIndex == NO_PARENT;
    }
}
