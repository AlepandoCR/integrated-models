package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitRole;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.math.IkTolerance;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.config.ConfigService;
import pandastic.studio.models.internal.locomotion.WalkCycleSolver;
import pandastic.studio.models.internal.render.ModelBone;
import pandastic.studio.models.internal.render.ModelSkeleton;
import pandastic.studio.models.internal.sim.LocomotionStage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the configured walk cycle for rigs whose conventional limb names are present.
 *
 * <p>Locomotion is intentionally derived here rather than embedded in {@link Rig}: physics rigs stay
 * reusable, while a model can opt into walking simply by declaring the standard limb bones. The source
 * returns {@code null} for decorative rigs that do not have a torso or a leg.
 */
public final class DerivedLocomotionStageSource implements LocomotionStageSource {

    private final @NotNull ConfigService configs;

    public DerivedLocomotionStageSource(@NotNull ConfigService configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    @Override
    public @Nullable LocomotionStage stageFor(@NotNull Rig rig) {
        Objects.requireNonNull(rig, "rig");
        final ModelSkeleton skeleton = ModelSkeleton.of(rig.getModelId()).orElse(null);
        if (skeleton == null) {
            return null;
        }

        final Map<String, ModelBone> modelBones = new HashMap<>();
        for (final ModelBone bone : skeleton.bones()) {
            modelBones.put(Rig.normalise(bone.name()), bone);
        }

        final BindingBounds bounds = this.configs.bindingBounds();
        final LocomotionBinding.Builder binding = LocomotionBinding.builder(rig, bounds);
        binding.armSwingAxis(rig.armSwingAxis());
        binding.handSwingAxis(rig.handSwingAxis());
        bindIfPresent(binding, rig, GaitRole.TORSO, "body");
        bindIfPresent(binding, rig, GaitRole.HEAD, "head");
        bindIfPresent(binding, rig, GaitRole.LEFT_UPPER_ARM, "left_arm");
        bindIfPresent(binding, rig, GaitRole.LEFT_FOREARM, "left_forearm");
        bindIfPresent(binding, rig, GaitRole.LEFT_HAND, "left_hand");
        bindIfPresent(binding, rig, GaitRole.RIGHT_UPPER_ARM, "right_arm");
        bindIfPresent(binding, rig, GaitRole.RIGHT_FOREARM, "right_forearm");
        bindIfPresent(binding, rig, GaitRole.RIGHT_HAND, "right_hand");
        final float thigh = segmentLength(modelBones, "right_leg", "right_leg_joint");
        final float shin = segmentLength(modelBones, "right_leg_joint", "right_foot");
        bindIfPresent(binding, rig, GaitRole.LEFT_THIGH, "left_leg");
        bindIfPresent(binding, rig, GaitRole.RIGHT_THIGH, "right_leg");
        if (validSegmentPair(thigh, shin, bounds)
                && rig.indexOf("left_leg_joint").isPresent()
                && rig.indexOf("right_leg_joint").isPresent()
                && rig.indexOf("left_foot").isPresent()
                && rig.indexOf("right_foot").isPresent()) {
            bindIfPresent(binding, rig, GaitRole.LEFT_SHIN, "left_leg_joint");
            bindIfPresent(binding, rig, GaitRole.LEFT_FOOT, "left_foot");
            bindIfPresent(binding, rig, GaitRole.RIGHT_SHIN, "right_leg_joint");
            bindIfPresent(binding, rig, GaitRole.RIGHT_FOOT, "right_foot");
            binding.legSegments(thigh, shin);
        } else {
            bindIfPresent(binding, rig, GaitRole.LEFT_FOOT, "left_foot");
            bindIfPresent(binding, rig, GaitRole.RIGHT_FOOT, "right_foot");
        }

        if (!hasLocomotionBones(rig)) {
            return null;
        }

        final GaitTuning tuning = this.configs.gait();
        final IkTolerance tolerance = this.configs.ikTolerance();
        final GaitProfile profile = GaitProfile.defaults(tuning).requireWithin(bounds);
        return new LocomotionStage(
                new WalkCycleSolver(tuning, tolerance),
                binding.build(),
                profile);
    }

    private static void bindIfPresent(
            @NotNull LocomotionBinding.Builder binding,
            @NotNull Rig rig,
            @NotNull GaitRole role,
            @NotNull String name
    ) {
        if (rig.indexOf(name).isPresent()) {
            binding.bone(role, name);
        }
    }

    private static boolean hasLocomotionBones(@NotNull Rig rig) {
        return rig.indexOf("body").isPresent()
                || rig.indexOf("head").isPresent()
                || rig.indexOf("left_leg").isPresent()
                || rig.indexOf("right_leg").isPresent()
                || rig.indexOf("left_arm").isPresent()
                || rig.indexOf("right_arm").isPresent();
    }

    private static float segmentLength(
            @NotNull Map<String, ModelBone> bones,
            @NotNull String from,
            @NotNull String to
    ) {
        final ModelBone first = bones.get(Rig.normalise(from));
        final ModelBone second = bones.get(Rig.normalise(to));
        return first == null || second == null
                ? Float.NaN
                : first.restPosition().distance(second.restPosition());
    }

    private static boolean validSegmentPair(float thigh, float shin, @NotNull BindingBounds bounds) {
        return Float.isFinite(thigh) && Float.isFinite(shin)
                && thigh >= bounds.minSegmentLength() && thigh <= bounds.maxSegmentLength()
                && shin >= bounds.minSegmentLength() && shin <= bounds.maxSegmentLength();
    }
}
