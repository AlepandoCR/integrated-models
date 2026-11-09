package pandastic.studio.models.internal.model.component.animation;

import kr.toxicity.model.api.animation.AnimationModifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.component.animation.AnimationMetadata;
import pandastic.studio.models.api.model.component.animation.ModelAnimator;
import pandastic.studio.models.internal.IntegratedModelsPlugin;
import pandastic.studio.models.internal.model.ModelImpl;
import pandastic.studio.models.internal.model.component.ModelComponentImpl;

import java.util.Optional;

public class ModelAnimatorImpl<BaseEntityType extends Entity> extends ModelComponentImpl<BaseEntityType> implements ModelAnimator<BaseEntityType>{

    private @Nullable String currentAnimation;

    private @Nullable AnimationMetadataImpl currentAnimationMetadata;

    private final @NotNull IntegratedModelsPlugin plugin;

    public ModelAnimatorImpl(@NotNull ModelImpl<BaseEntityType> model) {
        super(model);
        this.plugin = model.getPlugin();
    }

    @Override
    public void setAnimation(@NotNull String animationId) {
        this.model.getEntityTracker().ifPresent(tracker -> {
            var animated = false;
            if(currentAnimationMetadata == null){
                animated = tracker.animate(animationId);
            }else{
                animated = tracker.animate(animationId, currentAnimationMetadata.toAnimationModifier());
            }

            if(!animated){
                plugin.getComponentLogger().info(Component.text("Animation " + animationId + " didn't play for model " + this.model.getName()));
            }
        });

        this.currentAnimation = animationId;
    }

    @Override
    public void setAnimation(@NotNull String animationId, @NotNull Runnable runnable) {
        this.model.getEntityTracker().ifPresent(tracker -> {
            var animated = false;
            if(currentAnimationMetadata == null){
                animated = tracker.animate(animationId, AnimationModifier.DEFAULT, runnable);
            }else{
                animated = tracker.animate(animationId, currentAnimationMetadata.toAnimationModifier(), runnable);
            }

            if(!animated){
                plugin.getComponentLogger().info(Component.text("Animation " + animationId + " didn't play for model " + this.model.getName()));
            }
        });

        this.currentAnimation = animationId;
    }

    @Override
    public void stopAnimation() {
        this.model.getEntityTracker().ifPresent(tracker -> getAnimation().ifPresent(tracker::stopAnimation));
    }

    @Override
    public void setMetadata(@NotNull AnimationMetadata animationMetadata) {
        if(animationMetadata instanceof AnimationMetadataImpl animationMetadataImpl){
            this.currentAnimationMetadata = animationMetadataImpl;

            getAnimation().ifPresent(this::setAnimation);
        }else{
            plugin.getComponentLogger().info(Component.text("AnimationMetadata is not of BetterModel implementation for model " + model.getName(), NamedTextColor.RED));
        }

    }

    @Override
    public @NotNull Optional<AnimationMetadata> getAnimationMetadata() {
        return Optional.ofNullable(currentAnimationMetadata);
    }

    @Override
    public @NotNull Optional<String> getAnimation() {
        return Optional.ofNullable(currentAnimation);
    }
}
