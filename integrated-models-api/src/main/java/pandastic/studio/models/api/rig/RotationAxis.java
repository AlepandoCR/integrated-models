package pandastic.studio.models.api.rig;

/**
 * The three Euler components of a bone rotation, in the order they are laid out in a
 * {@link pandastic.studio.models.api.pose.Pose}.
 *
 * <p>The ordinal <em>is</em> the offset within a bone's three-float slot, so it is part of the pose
 * layout contract. Do not reorder these constants.
 */
public enum RotationAxis {

    /** Rotation about X. The axis gravity pulls on. */
    PITCH,

    /** Rotation about Y. */
    YAW,

    /** Rotation about Z. */
    ROLL;

    /** Cached to avoid an array copy per access; {@link #values()} allocates. */
    private static final RotationAxis[] VALUES = values();

    /**
     * The number of axes, i.e. the number of floats a single bone occupies in a pose.
     *
     * <p><strong>Deliberately not configurable</strong>, and not really a number: it is the cardinality
     * of this enum, derived from it rather than written down, so it cannot drift out of step with the
     * constants above. Three is how many Euler components a rotation has.
     */
    public static final int COUNT = VALUES.length;

    /** The axis at {@code ordinal}, without the defensive copy {@link #values()} makes. */
    public static RotationAxis at(int ordinal) {
        return VALUES[ordinal];
    }
}
