package pandastic.studio.models.api.locomotion;

import org.jetbrains.annotations.Nullable;

/**
 * The limb slots a gait knows how to drive.
 *
 * <p>A role is <em>not</em> a property of a bone. {@link pandastic.studio.models.api.rig.RigBone}
 * deliberately carries no role field: the same rig is driven by physics, by animation and by
 * locomotion, and baking one layer's vocabulary into the skeleton would mean every future layer either
 * adds its own field or reuses one that does not fit. The mapping lives in a
 * {@link LocomotionBinding} instead, which is per-layer, optional, and throwable away.
 *
 * <p>Every role is optional. A rig may bind a torso and nothing else — a floating decoration still
 * wants to breathe — and the solver writes only the bones that are actually bound.
 *
 * <h2>Three segments per limb, and the last two are independent</h2>
 * A limb is modelled as an upper segment, a middle segment and an extremity: thigh, shin, foot, and
 * upper arm, forearm, hand. Only the upper segment is load-bearing for the limb's existence. The other
 * two are independently optional, which is the part worth stating because the obvious reading is wrong:
 *
 * <ul>
 *   <li>Binding only the upper segment selects the one-piece form, which simply swings about the hip
 *       or the shoulder.</li>
 *   <li>Binding the middle segment selects the kneed (or elbowed) form. For a leg that means the limb
 *       is solved with inverse kinematics, which is why a shin is the thing that makes the binding
 *       demand segment lengths.</li>
 *   <li>Binding the extremity is <strong>independent of the middle segment</strong>. A one-piece leg
 *       may still have a foot — plenty of rigs model a thigh and a boot and nothing between — so a
 *       foot's parent is the shin when there is one and the thigh otherwise. {@link #upperSegment()}
 *       therefore reports a foot's requirement as the <em>thigh</em>, and the shin is reported
 *       separately by {@link #intermediateSegment()}.</li>
 * </ul>
 *
 * <p>What is never legal is a segment with nothing above it: a shin with no thigh has nothing to hang
 * from, and a foot with no leg at all is a boot floating where a hip should be. The binding refuses
 * both rather than silently ignoring them.
 */
public enum GaitRole {

    /** The body's root for gait purposes: takes the forward lean, the walk bob and the crouch pitch. */
    TORSO,

    /** Counter-rotates against the torso so the model keeps looking where it is going. */
    HEAD,

    /** Upper segment of the left leg. Bound alone, the whole leg swings as one piece. */
    LEFT_THIGH,

    /** Lower segment of the left leg. Its presence is what makes the left leg kneed. */
    LEFT_SHIN,

    /**
     * Extremity of the left leg. Counter-rotates against everything above it so the sole stays level
     * with the ground instead of pointing wherever the shin happens to be aimed.
     */
    LEFT_FOOT,

    /** Upper segment of the right leg. */
    RIGHT_THIGH,

    /** Lower segment of the right leg. */
    RIGHT_SHIN,

    /** Extremity of the right leg. */
    RIGHT_FOOT,

    /** Upper segment of the left arm. Swings in counter-phase to the left leg. */
    LEFT_UPPER_ARM,

    /** Lower segment of the left arm. Adds a one-way elbow curl that follows the swing. */
    LEFT_FOREARM,

    /**
     * Extremity of the left arm. Unlike a foot it has no ground to stay level with, so how much of its
     * parent's rotation it cancels is a style rather than a geometric requirement — see
     * {@link GaitProfile#handStabilise()}.
     */
    LEFT_HAND,

    /** Upper segment of the right arm. */
    RIGHT_UPPER_ARM,

    /** Lower segment of the right arm. */
    RIGHT_FOREARM,

    /** Extremity of the right arm. */
    RIGHT_HAND;

    /** Cached because {@link #values()} allocates, and a binding indexes by ordinal on every lookup. */
    private static final GaitRole[] VALUES = values();

    /**
     * The number of roles, i.e. the width of a binding's role table.
     *
     * <p><strong>Deliberately not configurable</strong>, and not really a number: it is the cardinality
     * of this enum, derived from it rather than written down, so adding a limb pair updates it
     * automatically and no configuration can put it out of step with the constants above.
     */
    public static final int COUNT = VALUES.length;

    /** The role at {@code ordinal}, without the defensive copy {@link #values()} makes. */
    public static GaitRole at(int ordinal) {
        return VALUES[ordinal];
    }

    /**
     * The segment this role may not be bound without, or {@code null} if it anchors a limb itself.
     *
     * <p>Drives the binding's "no orphan segment" check in one place, so adding a limb pair means
     * extending this switch rather than finding every rule that mentions shins.
     *
     * <p>For an extremity this is the limb's <em>upper</em> segment rather than the segment it actually
     * hangs from, and that is the whole point: requiring a foot's shin would reject the legal one-piece
     * leg with a boot on the end. The segment it hangs from when there is one is
     * {@link #intermediateSegment()}.
     */
    public @Nullable GaitRole upperSegment() {
        return switch (this) {
            case LEFT_SHIN, LEFT_FOOT -> LEFT_THIGH;
            case RIGHT_SHIN, RIGHT_FOOT -> RIGHT_THIGH;
            case LEFT_FOREARM, LEFT_HAND -> LEFT_UPPER_ARM;
            case RIGHT_FOREARM, RIGHT_HAND -> RIGHT_UPPER_ARM;
            default -> null;
        };
    }

    /**
     * The segment that sits between this role and its {@link #upperSegment()} when that segment is
     * bound, or {@code null} if nothing ever does.
     *
     * <p>Only an extremity has one. It is the bone whose rotation the extremity additionally inherits,
     * so a solver holding a foot level has to cancel the shin's angle as well as the thigh's — but only
     * when a shin is there to cancel.
     */
    public @Nullable GaitRole intermediateSegment() {
        return switch (this) {
            case LEFT_FOOT -> LEFT_SHIN;
            case RIGHT_FOOT -> RIGHT_SHIN;
            case LEFT_HAND -> LEFT_FOREARM;
            case RIGHT_HAND -> RIGHT_FOREARM;
            default -> null;
        };
    }

    /**
     * Whether this role is the far end of a limb — a foot or a hand.
     *
     * <p>An extremity is driven by cancelling what it inherits rather than by an amplitude of its own,
     * which is why it needs no segment length and takes no part in the inverse-kinematics solve.
     *
     * <p>Derived from {@link #intermediateSegment()} rather than listed again: having a segment that may
     * sit between it and the limb's root is exactly what being the far end of a limb means, and a second
     * list of the same four constants is a second thing to forget when a limb is added.
     */
    public boolean isExtremity() {
        return intermediateSegment() != null;
    }

    /**
     * Whether this role is one of the two leg segments the inverse kinematics solves, and therefore
     * needs the binding's segment lengths.
     *
     * <p>A foot is deliberately <em>not</em> one. The two-bone solve places the ankle, and the foot is
     * then posed by cancelling the chain above it: it contributes no length to the reach, so demanding
     * one would reject a rig that binds a boot on a one-piece leg.
     */
    public boolean isLegSegment() {
        return this == LEFT_THIGH || this == LEFT_SHIN || this == RIGHT_THIGH || this == RIGHT_SHIN;
    }
}
