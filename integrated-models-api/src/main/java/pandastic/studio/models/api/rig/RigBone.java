package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One bone of a {@link Rig}: a name that must resolve against the real model, its place in the
 * hierarchy, and optionally the physics that drive it.
 *
 * @param name        the bone's name in the underlying model, already normalised by {@link Rig}
 * @param parentIndex index of this bone's parent within the owning rig, or {@link #NO_PARENT}
 * @param physics     the secondary motion applied to this bone, or {@code null} if the rig only
 *                    declares it so that children can hang off it
 */
public record RigBone(
        @NotNull String name,
        int parentIndex,
        @Nullable PhysicsProfile physics
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

    /** Whether this bone is a root within its rig. */
    public boolean isRoot() {
        return this.parentIndex == NO_PARENT;
    }
}
