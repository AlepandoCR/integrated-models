package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.locomotion.ArmSwingAxis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * An immutable, validated skeleton overlay: which bones of a model this library drives, how they are
 * nested, and what physics each carries.
 *
 * <p>A rig lives in <em>this</em> plugin and names bones in a model it does not own — the model file
 * belongs to the renderer's asset folder. Nothing here resolves those names; that happens once, at
 * bind time, against the live model. What this type guarantees is that the rig is internally coherent
 * before anybody tries.
 *
 * <h2>Bone names are matched loosely, on purpose</h2>
 * Names are stored lower-cased and compared case-insensitively. Renderer bone identifiers routinely
 * carry behavioural tag prefixes, and the identity of a bone is its bare name — so a rig keyed on the
 * raw identifier breaks the moment a rigger adds a tag in their modelling tool, silently and with no
 * error to point at. Matching the bare name case-insensitively is the forgiving half of that contract;
 * the binding layer owns the other half.
 *
 * <h2>Parents come before children</h2>
 * {@link Builder} requires a bone's parent to be declared first, so the bone list is always in
 * topological order. The physics solver reads each bone's ancestors from the pose that the same pass
 * already finished, so an out-of-order chain would read a one-step-stale parent angle — a defect that
 * shows up as a chain lagging its own root by a frame and is invisible in code review. Making the
 * ordering a construction-time requirement removes the failure mode instead of documenting it.
 */
public final class Rig {

    private final @NotNull String name;
    private final @NotNull String modelId;
    private final @NotNull List<RigBone> bones;
    private final @NotNull Map<String, Integer> indexByName;
    private final int[] physicsBones;
    private final @NotNull ArmSwingAxis armSwingAxis;
    private final @NotNull ArmSwingAxis handSwingAxis;

    private Rig(
            @NotNull String name,
            @NotNull String modelId,
            @NotNull List<RigBone> bones,
            @NotNull Map<String, Integer> indexByName,
            @NotNull ArmSwingAxis armSwingAxis,
            @NotNull ArmSwingAxis handSwingAxis
    ) {
        this.name = name;
        this.modelId = modelId;
        this.bones = List.copyOf(bones);
        this.indexByName = Map.copyOf(indexByName);
        this.armSwingAxis = armSwingAxis;
        this.handSwingAxis = handSwingAxis;

        int physicsCount = 0;
        for (final RigBone bone : this.bones) {
            if (bone.hasPhysics()) {
                physicsCount++;
            }
        }
        this.physicsBones = new int[physicsCount];
        int cursor = 0;
        for (int index = 0; index < this.bones.size(); index++) {
            if (this.bones.get(index).hasPhysics()) {
                this.physicsBones[cursor++] = index;
            }
        }
    }

    /**
     * A builder for a rig called {@code name} that drives the model registered as {@code modelId}.
     *
     * @param rigBounds     the structural ceiling a rig is built within
     * @param profileBounds the ranges every bone's physics is accepted within, and the coefficient that
     *                      folds a cloth strip's clearance into a pitch clamp. Taken here because a rig
     *                      compile is where both are needed, and because validating each profile as it
     *                      arrives keeps the "api types throw on a bad coefficient" guarantee intact now
     *                      that {@link PhysicsProfile}'s own constructor cannot reach the ranges
     */
    @Contract(value = "_, _, _, _ -> new", pure = true)
    public static @NotNull Builder builder(
            @NotNull String name,
            @NotNull String modelId,
            @NotNull RigBounds rigBounds,
            @NotNull ProfileBounds profileBounds
    ) {
        return new Builder(name, modelId, rigBounds, profileBounds);
    }

    /**
     * Normalises a bone name to the form this rig keys on.
     *
     * <p>Lower-cases, and <strong>nothing else</strong> — in particular it does not strip {@code -} or
     * {@code _}. That is deliberate and it is worth stating, because the rig <em>file format</em> is
     * lenient about separators in its own keys ({@code collision-radius}, {@code collision_radius} and
     * {@code collisionRadius} are one key) and the inconsistency looks like an oversight.
     *
     * <p>It is not. A key is ours to interpret; a bone name has to match a bone in a model this plugin
     * does not own, and the renderer matches the bare name case-insensitively and no more. Stripping
     * separators here would let {@code cloth_left} satisfy this rig's validation and then fail to resolve
     * against a model whose bone is {@code clothleft} — a rig that loads clean and binds to nothing.
     * Being stricter than the file format is what keeps "the rig validated" and "the rig will bind" the
     * same statement.
     */
    public static @NotNull String normalise(@NotNull String boneName) {
        return boneName.toLowerCase(Locale.ROOT);
    }

    /** This rig's own identifier, for logs and diagnostics. */
    public @NotNull String getName() {
        return this.name;
    }

    /** The identifier of the model whose bones this rig names. */
    public @NotNull String getModelId() {
        return this.modelId;
    }

    /** The authored local axis used by the generic arm gait and attack swing. */
    public @NotNull ArmSwingAxis armSwingAxis() {
        return this.armSwingAxis;
    }

    /** The authored local axis used for the hand's stabilising motion. */
    public @NotNull ArmSwingAxis handSwingAxis() {
        return this.handSwingAxis;
    }

    /** Every bone, in topological order: a bone's parent always precedes it. */
    public @NotNull List<RigBone> getBones() {
        return this.bones;
    }

    /** The number of bones, i.e. the pose width this rig expects. */
    public int boneCount() {
        return this.bones.size();
    }

    /** The bone at {@code index}. */
    public @NotNull RigBone bone(int index) {
        return this.bones.get(index);
    }

    /** The index of {@code boneName}, matched case-insensitively, or empty if this rig has no such bone. */
    public @NotNull OptionalInt indexOf(@NotNull String boneName) {
        final Integer index = this.indexByName.get(normalise(boneName));
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /**
     * Indices of the bones carrying physics, ascending — and therefore also in topological order,
     * which is what lets the solver walk them in one pass.
     *
     * @return a fresh array; callers may keep and mutate it
     */
    public int[] physicsBoneIndices() {
        return this.physicsBones.clone();
    }

    /** How many bones carry physics. */
    public int physicsBoneCount() {
        return this.physicsBones.length;
    }

    @Override
    public @NotNull String toString() {
        return "Rig[" + this.name + " -> " + this.modelId + ", " + this.bones.size()
                + " bones, " + this.physicsBones.length + " simulated]";
    }

    /** Accumulates bones and validates the rig as a whole. */
    public static final class Builder {

        private final @NotNull String name;
        private final @NotNull String modelId;
        private final @NotNull RigBounds rigBounds;
        private final @NotNull ProfileBounds profileBounds;
        private final @NotNull List<RigBone> bones = new ArrayList<>();
        private final @NotNull Map<String, Integer> indexByName = new HashMap<>();
        private @NotNull ArmSwingAxis armSwingAxis = ArmSwingAxis.PITCH;
        private @NotNull ArmSwingAxis handSwingAxis = ArmSwingAxis.PITCH;

        private Builder(
                @NotNull String name,
                @NotNull String modelId,
                @NotNull RigBounds rigBounds,
                @NotNull ProfileBounds profileBounds
        ) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("A rig needs a name");
            }
            if (modelId.isBlank()) {
                throw new IllegalArgumentException("A rig needs a model id to point at");
            }
            this.name = name;
            this.modelId = modelId;
            this.rigBounds = rigBounds;
            this.profileBounds = profileBounds;
        }

        /** Sets the authored local axis used for arm locomotion. */
        public @NotNull Builder armSwingAxis(@NotNull ArmSwingAxis axis) {
            this.armSwingAxis = axis;
            return this;
        }

        /** Sets the authored local axis used for hand stabilisation. */
        public @NotNull Builder handSwingAxis(@NotNull ArmSwingAxis axis) {
            this.handSwingAxis = axis;
            return this;
        }

        /** Adds a root bone with no physics — a hierarchy anchor for bones that do have some. */
        public @NotNull Builder root(@NotNull String boneName) {
            return bone(boneName, null, null);
        }

        /** Adds a root bone carrying {@code physics}. */
        public @NotNull Builder root(@NotNull String boneName, @NotNull PhysicsProfile physics) {
            return bone(boneName, null, physics);
        }

        /**
         * Adds a bone parented to {@code parentName}, which must already have been added.
         *
         * @param physics the bone's secondary motion, or {@code null} for a pass-through bone
         * @throws IllegalArgumentException if the name is a duplicate or the parent is unknown
         */
        public @NotNull Builder bone(
                @NotNull String boneName,
                @Nullable String parentName,
                @Nullable PhysicsProfile physics
        ) {
            final String key = normalise(boneName);
            if (this.indexByName.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Rig '" + this.name + "' declares the bone '" + boneName + "' twice");
            }
            if (this.bones.size() >= this.rigBounds.maxBones()) {
                throw new IllegalArgumentException(
                        "Rig '" + this.name + "' exceeds the " + this.rigBounds.maxBones()
                                + "-bone ceiling");
            }

            final int parentIndex;
            if (parentName == null) {
                parentIndex = RigBone.NO_PARENT;
            } else {
                final Integer resolved = this.indexByName.get(normalise(parentName));
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "Rig '" + this.name + "': bone '" + boneName + "' is parented to '"
                                    + parentName + "', which has not been declared yet. Parents must be"
                                    + " added before their children.");
                }
                parentIndex = resolved;
            }

            this.indexByName.put(key, this.bones.size());
            this.bones.add(new RigBone(key, parentIndex, compile(physics)));
            return this;
        }

        /**
         * Checks a bone's physics against the operator's ranges, then folds a cloth strip's collision
         * cone into a plain pitch clamp so the solver never runs a collision test. A strip hanging from
         * its pivot may not swing further inward than the angle whose chord equals its clearance; past a
         * short strip that approximation stops holding, which is the point at which a real collider
         * would be worth having.
         */
        private @Nullable PhysicsProfile compile(@Nullable PhysicsProfile physics) {
            if (physics == null) {
                return null;
            }
            physics.requireWithin(this.profileBounds);
            if (physics.kind() != PhysicsKind.CLOTH_STRIP || physics.collisionRadius() <= 0F) {
                return physics;
            }
            final float ceiling =
                    physics.collisionRadius() * this.profileBounds.collisionRadiusToRadians();
            return new PhysicsProfile(
                    physics.kind(), physics.stiffness(), physics.damping(), physics.lag(),
                    physics.gravity(), physics.mass(), physics.wind(), physics.collisionRadius(),
                    physics.reachX(), physics.reachY(), physics.reachZ(),
                    physics.limits().withPitchCeiling(ceiling));
        }

        /** Validates and freezes the rig. */
        public @NotNull Rig build() {
            if (this.bones.isEmpty()) {
                throw new IllegalArgumentException("Rig '" + this.name + "' has no bones");
            }
            return new Rig(this.name, this.modelId, this.bones,
                    Collections.unmodifiableMap(this.indexByName), this.armSwingAxis,
                    this.handSwingAxis);
        }
    }
}
