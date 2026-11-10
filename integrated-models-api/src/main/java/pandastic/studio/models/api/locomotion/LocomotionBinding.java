package pandastic.studio.models.api.locomotion;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.rig.Rig;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Which bones of a {@link Rig} the gait drives, and the limb geometry it needs to solve them.
 *
 * <p>Immutable and validated at construction, like a rig. The point of validating here is that every
 * failure mode of a bad binding is silent at runtime: a bone index one past the end of the rig throws
 * from deep inside the solver's hot loop rather than at load; two roles pointing at the same bone means
 * whichever one writes last wins, and which one that is depends on the solver's internal ordering; a
 * shin bound with no thigh produces a knee hanging in space. All four are cheap to catch once, here.
 *
 * <h2>It holds no reference to the rig it was built against</h2>
 * Only the rig's name — for diagnostics — and its bone count, which is what the solver checks a pose
 * against. That keeps a binding usable across every instance of the same rig, and keeps it from pinning
 * a rig definition alive.
 *
 * <h2>Segment lengths are in blocks, and only legs need them</h2>
 * {@link #thighLength()} and {@link #shinLength()} feed
 * {@link pandastic.studio.models.api.math.TwoBoneIk}, so they have to be the real lengths of the bones
 * in the model or the foot lands in the wrong place. Arms are not solved with inverse kinematics —
 * nothing constrains where a hand goes during a walk — so they need no lengths.
 *
 * <p>Nor do the extremities. The two-bone solve places the <em>ankle</em>; a foot hangs below that and
 * is posed by cancelling the chain above it, contributing no length to the reach. That is why a rig may
 * bind a boot to a one-piece leg and declare no segment lengths at all.
 *
 * <h2>A binding with no legs is legal</h2>
 * Deliberately. A hovering crystal, a banner, a floating eye: none of them have legs and all of them
 * look dead without the idle oscillation the gait layer provides. What is <em>not</em> legal is a
 * binding that drives nothing at all, which is a configuration mistake rather than a design.
 *
 * <h2>The authored facing lives here, not in the profile</h2>
 * {@link #facingDegrees()} says which way the modeller pointed the rig, and it belongs to this type for
 * the same reason {@link #kneeBendSign()} does: it is a fact about how one asset was built, not a style
 * of walking. A {@link GaitProfile} is deliberately dimensionless and portable — {@code legSwing: 1}
 * means "the calibrated look" — whereas a rig authored facing south walks sideways until somebody states
 * that it was authored facing south, and no amount of profile tuning expresses that. It is also not a
 * server-wide setting: two rigs in one models folder may disagree about it, so it is per-rig data and a
 * global configuration key could only ever be the value a rig file falls back to.
 */
public final class LocomotionBinding {

    /**
     * {@link #boneOf(GaitRole)} for a role this binding does not drive.
     *
     * <p><strong>Deliberately not configurable.</strong> It is a sentinel rather than a number: the
     * contract is "no legal bone index collides with it", and {@code -1} satisfies that by construction
     * because a bone index is an array offset. An operator could only move it to another value that
     * means the same thing, or to one that collides with a real bone.
     */
    public static final int UNBOUND = -1;

    private final @NotNull String rigName;
    private final int boneCount;

    /** Bone index per {@link GaitRole#ordinal()}, {@link #UNBOUND} where the role is not driven. */
    private final int[] boneByRole;

    /** The bones this binding writes, ascending and distinct. The "do not touch anything else" list. */
    private final int[] ownedBones;

    private final float thighLength;
    private final float shinLength;
    private final float kneeBendSign;
    private final float facingDegrees;
    private final @NotNull ArmSwingAxis armSwingAxis;
    private final @NotNull ArmSwingAxis handSwingAxis;

    /**
     * {@link #facingDegrees} as a rotation, resolved once.
     *
     * <p>The gait projects a driver sample through this on every step for every model on the server, and
     * a sine and a cosine of a value that cannot change are two transcendental calls per step to learn
     * something known at build time.
     */
    private final float facingCos;
    private final float facingSin;

    private LocomotionBinding(
            @NotNull String rigName,
            int boneCount,
            int[] boneByRole,
            float thighLength,
            float shinLength,
            float kneeBendSign,
            float facingDegrees,
            @NotNull ArmSwingAxis armSwingAxis,
            @NotNull ArmSwingAxis handSwingAxis
    ) {
        this.rigName = rigName;
        this.boneCount = boneCount;
        this.boneByRole = boneByRole;
        this.thighLength = thighLength;
        this.shinLength = shinLength;
        this.kneeBendSign = kneeBendSign;
        this.facingDegrees = facingDegrees;
        this.armSwingAxis = armSwingAxis;
        this.handSwingAxis = handSwingAxis;

        final double radians = Math.toRadians(facingDegrees);
        this.facingCos = (float) Math.cos(radians);
        this.facingSin = (float) Math.sin(radians);

        int bound = 0;
        for (final int bone : boneByRole) {
            if (bone != UNBOUND) {
                bound++;
            }
        }
        final int[] owned = new int[bound];
        int cursor = 0;
        for (final int bone : boneByRole) {
            if (bone != UNBOUND) {
                owned[cursor++] = bone;
            }
        }
        Arrays.sort(owned);
        this.ownedBones = owned;
    }

    /**
     * A builder that resolves and range-checks every role against {@code rig}, and every segment length
     * against {@code bounds}.
     *
     * @param bounds the operator's limb-geometry ranges, which depend on the scale this server's models
     *               are authored at and so cannot be known here
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull Builder builder(@NotNull Rig rig, @NotNull BindingBounds bounds) {
        return new Builder(rig, bounds);
    }

    /** The name of the rig this binding was validated against, for diagnostics. */
    public @NotNull String getRigName() {
        return this.rigName;
    }

    /** The bone count of that rig, i.e. the pose width this binding expects. */
    public int boneCount() {
        return this.boneCount;
    }

    /** The bone driving {@code role}, or {@link #UNBOUND}. */
    public int boneOf(@NotNull GaitRole role) {
        return this.boneByRole[role.ordinal()];
    }

    /** The bone driving {@code role}, or empty — for callers that prefer not to compare to a sentinel. */
    public @NotNull OptionalInt findBone(@NotNull GaitRole role) {
        final int bone = boneOf(role);
        return bone == UNBOUND ? OptionalInt.empty() : OptionalInt.of(bone);
    }

    /** Whether this binding drives {@code role}. */
    public boolean isBound(@NotNull GaitRole role) {
        return this.boneByRole[role.ordinal()] != UNBOUND;
    }

    /** How many roles are bound. Never zero: a binding that drives nothing is refused at build time. */
    public int boundRoleCount() {
        return this.ownedBones.length;
    }

    /**
     * The bones this binding writes, ascending.
     *
     * @return a fresh array; callers may keep and mutate it
     */
    public int[] ownedBoneIndices() {
        return this.ownedBones.clone();
    }

    /**
     * Whether {@code bone} is one this binding writes.
     *
     * <p>Exists so a caller composing layers can assert the division of labour — locomotion sets its own
     * bones absolutely, physics adds to everything — rather than discovering an overlap as a bone that
     * drifts.
     */
    public boolean ownsBone(int bone) {
        return Arrays.binarySearch(this.ownedBones, bone) >= 0;
    }

    /** Whether at least one thigh is bound, and therefore whether there is a walk cycle to run. */
    public boolean hasLegs() {
        return isBound(GaitRole.LEFT_THIGH) || isBound(GaitRole.RIGHT_THIGH);
    }

    /** Whether the leg on this side is solved with inverse kinematics rather than swung as one piece. */
    public boolean isKneed(@NotNull GaitRole thighRole) {
        return switch (thighRole) {
            case LEFT_THIGH -> isBound(GaitRole.LEFT_SHIN);
            case RIGHT_THIGH -> isBound(GaitRole.RIGHT_SHIN);
            default -> throw new IllegalArgumentException(thighRole + " is not a thigh");
        };
    }

    /** Length of the upper leg segment in blocks. Meaningless unless a shin is bound. */
    public float thighLength() {
        return this.thighLength;
    }

    /** Length of the lower leg segment in blocks. */
    public float shinLength() {
        return this.shinLength;
    }

    /** How far a fully straightened leg reaches below the hip, in blocks. */
    public float legReach() {
        return this.thighLength + this.shinLength;
    }

    /**
     * Which way the knee folds: {@code +1} or {@code -1}.
     *
     * <p>A rig property, not a tuning value — it depends on which way the modeller pointed the bone's
     * local axes, and the only two possible answers are "this way" and "the other way".
     */
    public float kneeBendSign() {
        return this.kneeBendSign;
    }

    /**
     * Which way the elbow folds — the opposite of the knee.
     *
     * <p>Derived rather than configured. A knee and an elbow fold in opposite directions in every
     * vertebrate, so a second sign would only ever be set to the negation of the first, and offering it
     * would invite a rig where the two disagree.
     */
    public float elbowBendSign() {
        return -this.kneeBendSign;
    }

    /**
     * How far the rig's own forward axis is turned from the direction its owner walks in, in degrees.
     *
     * <p>Measured the way a yaw is — positive turning clockwise seen from above — so a rig whose mesh
     * was modelled facing the opposite way is {@code 180}, and one modelled facing its owner's right is
     * {@code 90}. Zero means the modeller pointed it the way the gait assumes, which is the case this
     * whole value exists to stop being an assumption.
     *
     * <p>A rig property rather than a tuning value, for the same reason as {@link #kneeBendSign()}: it
     * records which way one person pointed one mesh in Blockbench. Nothing an operator knows about their
     * server bears on it.
     */
    public float facingDegrees() {
        return this.facingDegrees;
    }

    /** The authored local axis used by arm walking and attack motion. */
    public @NotNull ArmSwingAxis armSwingAxis() {
        return this.armSwingAxis;
    }

    /** The authored local axis used for the hand's stabilising motion. */
    public @NotNull ArmSwingAxis handSwingAxis() {
        return this.handSwingAxis;
    }

    /**
     * {@code driver}'s fore-aft speed, re-expressed along the rig's own forward axis.
     *
     * <p>A {@link PhysicsDriver} reports movement in the <em>owner's</em> frame, because that is the
     * frame an entity's velocity is naturally differenced in. The gait needs it in the <em>rig's</em>
     * frame, because that is the frame the bones were modelled in. Rotating the pair here — rather than
     * mirroring or swapping something downstream of the solve — is what keeps the correction one
     * statement about the input instead of a sign scattered across every limb.
     *
     * <p>Note that the rotation preserves the pair's magnitude, by construction. So the facing offset
     * cannot change how fast the model appears to walk; it changes only which way round the stride runs.
     *
     * @param driver the owner's movement this step
     * @return blocks per tick along the rig's forward axis, negative when the rig is travelling backward
     */
    public float forwardSpeedInRigFrame(@NotNull PhysicsDriver driver) {
        return driver.forwardSpeed() * this.facingCos - driver.sideSpeed() * this.facingSin;
    }

    /**
     * {@code driver}'s lateral speed, re-expressed along the rig's own left axis.
     *
     * <p>The companion of {@link #forwardSpeedInRigFrame(PhysicsDriver)}, and the other half of the same
     * rotation. The walk cycle has no sideways gait, so it uses this only through the pair's magnitude —
     * but a strafe solver would need exactly this, and splitting the rotation across two types later
     * would be how the two halves end up disagreeing about the sign.
     *
     * @param driver the owner's movement this step
     * @return blocks per tick to the rig's own left
     */
    public float sideSpeedInRigFrame(@NotNull PhysicsDriver driver) {
        return driver.forwardSpeed() * this.facingSin + driver.sideSpeed() * this.facingCos;
    }

    @Override
    public @NotNull String toString() {
        return "LocomotionBinding[" + this.rigName + ", " + this.ownedBones.length + " of "
                + GaitRole.COUNT + " roles bound, legs=" + hasLegs()
                + ", facing=" + this.facingDegrees + "]";
    }

    /** Accumulates role bindings and validates the set as a whole. */
    public static final class Builder {

        private final @NotNull Rig rig;
        private final @NotNull BindingBounds bounds;
        private final int[] boneByRole = new int[GaitRole.COUNT];
        private float thighLength;
        private float shinLength;

        /**
         * Seeded {@code +1}, which is a direction rather than a tunable.
         *
         * <p>{@link #kneeBendSign(float)} accepts exactly {@code +1} or {@code -1}, so the only thing a
         * configured default could say is which of the two answers to assume — and the answer depends on
         * which way one modeller pointed one bone's local axes, not on anything an operator knows about
         * their server. It is a per-rig property, so it belongs in the rig file that describes that rig,
         * and {@code +1} is simply the identity this sign is multiplied in as.
         */
        private float kneeBendSign = 1F;

        /**
         * Seeded {@code 0}, which is the identity rotation rather than a chosen bearing.
         *
         * <p>Zero degrees means "the rig points the way the gait assumes", so this seed asserts nothing
         * about any model — it is the additive identity of the angle it is added as, exactly as
         * {@link #kneeBendSign} is the multiplicative identity of the sign it is multiplied in as. The
         * real answer depends on which way one modeller pointed one mesh, so it belongs in the rig file
         * that describes that rig; a loader with a server-wide fallback simply passes that fallback to
         * {@link #facing(float)}.
         */
        private float facingDegrees;
        private @NotNull ArmSwingAxis armSwingAxis = ArmSwingAxis.PITCH;
        private @NotNull ArmSwingAxis handSwingAxis = ArmSwingAxis.PITCH;

        private Builder(@NotNull Rig rig, @NotNull BindingBounds bounds) {
            this.rig = rig;
            this.bounds = bounds;
            Arrays.fill(this.boneByRole, UNBOUND);
        }

        /**
         * Binds {@code role} to the rig bone called {@code boneName}, matched the way the rig matches
         * names — case-insensitively, on the bare name.
         *
         * @throws IllegalArgumentException if the rig has no such bone, or the role is already bound
         */
        public @NotNull Builder bone(@NotNull GaitRole role, @NotNull String boneName) {
            final OptionalInt resolved = this.rig.indexOf(boneName);
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "Rig '" + this.rig.getName() + "' has no bone called '" + boneName + "' to bind "
                                + role + " to");
            }
            return boneIndex(role, resolved.getAsInt());
        }

        /**
         * Binds {@code role} to a bone index directly, for callers that already resolved it.
         *
         * @throws IllegalArgumentException if the index is outside the rig, or the role is already bound
         */
        public @NotNull Builder boneIndex(@NotNull GaitRole role, int bone) {
            if (bone < 0 || bone >= this.rig.boneCount()) {
                throw new IllegalArgumentException(
                        "Cannot bind " + role + " to bone " + bone + ": rig '" + this.rig.getName()
                                + "' has bones 0.." + (this.rig.boneCount() - 1));
            }
            if (this.boneByRole[role.ordinal()] != UNBOUND) {
                throw new IllegalArgumentException(
                        role + " is already bound to bone " + this.boneByRole[role.ordinal()]);
            }
            this.boneByRole[role.ordinal()] = bone;
            return this;
        }

        /**
         * The real lengths of the leg's two segments, in blocks. Required as soon as a shin is bound.
         *
         * @throws IllegalArgumentException if either length is outside the configured
         *                                 {@code [minSegmentLength, maxSegmentLength]}
         */
        public @NotNull Builder legSegments(float thighLength, float shinLength) {
            requireSegment(thighLength, "thighLength");
            requireSegment(shinLength, "shinLength");
            this.thighLength = thighLength;
            this.shinLength = shinLength;
            return this;
        }

        /**
         * Which way the knee folds.
         *
         * @throws IllegalArgumentException unless the value is exactly {@code +1} or {@code -1}
         */
        public @NotNull Builder kneeBendSign(float sign) {
            if (sign != 1F && sign != -1F) {
                throw new IllegalArgumentException(
                        "A bend sign is +1 or -1, got " + sign + ". It selects a direction, not a scale.");
            }
            this.kneeBendSign = sign;
            return this;
        }

        /**
         * How far the rig's forward axis is turned from its owner's, in degrees.
         *
         * <p>No range is imposed, deliberately: a bearing is periodic, so every finite value names a
         * legal direction and {@code 450} is simply {@code 90} written by somebody adding turns. Only
         * NaN and infinity are refused, because neither names a direction at all and both would reach
         * the pose as a non-finite rotation.
         *
         * @param degrees the authored bearing, positive clockwise seen from above
         * @throws IllegalArgumentException if the value is not finite
         */
        public @NotNull Builder facing(float degrees) {
            if (!Float.isFinite(degrees)) {
                throw new IllegalArgumentException(
                        "A facing is an angle in degrees and must be finite, got " + degrees
                                + ". Any finite value is legal: a bearing wraps.");
            }

            this.facingDegrees = degrees;
            return this;
        }

        /** Sets the authored local axis used for arm walking and attack motion. */
        public @NotNull Builder armSwingAxis(@NotNull ArmSwingAxis axis) {
            this.armSwingAxis = axis;
            return this;
        }

        /** Sets the authored local axis used for hand stabilisation. */
        public @NotNull Builder handSwingAxis(@NotNull ArmSwingAxis axis) {
            this.handSwingAxis = axis;
            return this;
        }

        private void requireSegment(float length, @NotNull String name) {
            final float min = this.bounds.minSegmentLength();
            final float max = this.bounds.maxSegmentLength();
            if (!Float.isFinite(length) || length < min || length > max) {
                throw new IllegalArgumentException(
                        name + " must be within [" + min + ", " + max + "] blocks but was " + length);
            }
        }

        /** Validates and freezes the binding. */
        public @NotNull LocomotionBinding build() {
            int bound = 0;
            boolean needsSegments = false;
            for (int ordinal = 0; ordinal < GaitRole.COUNT; ordinal++) {
                if (this.boneByRole[ordinal] == UNBOUND) {
                    continue;
                }
                bound++;
                final GaitRole role = GaitRole.at(ordinal);
                final GaitRole upper = role.upperSegment();
                if (upper != null && this.boneByRole[upper.ordinal()] == UNBOUND) {
                    throw new IllegalArgumentException(
                            "Rig '" + this.rig.getName() + "': " + role + " is bound but " + upper
                                    + " is not. A segment has nothing to hang from on its own — bind"
                                    + " both, or bind only " + upper + " for a one-piece limb.");
                }
                // A leg role that has an upper segment is a shin, and a shin is what makes a leg kneed.
                // A foot is excluded by isLegSegment() rather than by this condition: it also reports an
                // upper segment, but it adds no length to the reach, so a boot on a one-piece leg must
                // not be made to declare segment lengths it has no use for.
                if (role.isLegSegment() && upper != null) {
                    needsSegments = true;
                }
            }

            if (bound == 0) {
                throw new IllegalArgumentException(
                        "Rig '" + this.rig.getName() + "': the locomotion binding drives no bones at all."
                                + " A binding with no legs is fine — one with nothing bound is a mistake.");
            }

            if (needsSegments && (this.thighLength == 0F || this.shinLength == 0F)) {
                throw new IllegalArgumentException(
                        "Rig '" + this.rig.getName() + "' binds a kneed leg but declares no segment"
                                + " lengths. Inverse kinematics cannot place a foot without them; call"
                                + " legSegments(thigh, shin).");
            }

            // Every role must point at a distinct bone. Two roles on one bone is not a blend: the solver
            // writes each role's absolute rotation, so the later write simply erases the earlier one, and
            // which that is depends on an ordering the caller cannot see.
            final int[] sorted = new int[bound];
            int cursor = 0;
            for (final int bone : this.boneByRole) {
                if (bone != UNBOUND) {
                    sorted[cursor++] = bone;
                }
            }
            Arrays.sort(sorted);
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] == sorted[i - 1]) {
                    throw new IllegalArgumentException(
                            "Rig '" + this.rig.getName() + "': bone " + sorted[i] + " ('"
                                    + this.rig.bone(sorted[i]).name() + "') is bound to two gait roles."
                                    + " Locomotion writes absolute rotations, so one would silently"
                                    + " overwrite the other.");
                }
            }

            return new LocomotionBinding(
                    this.rig.getName(), this.rig.boneCount(), this.boneByRole.clone(),
                    this.thighLength, this.shinLength, this.kneeBendSign, this.facingDegrees,
                    this.armSwingAxis, this.handSwingAxis);
        }
    }
}
