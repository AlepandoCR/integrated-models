package pandastic.studio.models.internal.model.component.animation;

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

    /**
     * {@inheritDoc}
     *
     * <p>Reports what the renderer answered, which is what lets
     * {@link pandastic.studio.models.api.model.AnimatedModel#playAnimation(String)} route to this method
     * instead of assuming success. An unbound model answers {@code false} and logs nothing: there is no
     * renderer to refuse the clip, so nothing happened worth reporting.
     */
    @Override
    public boolean setAnimation(@NotNull String animationId) {
        boolean animated = false;
        final var handle = this.model.getEntityTracker();
        if (handle.isPresent()) {
            // Null metadata and a null callback are the renderer's own defaults; see
            // ModelHandle#playAnimation for why the four combinations are one method.
            animated = handle.get().playAnimation(animationId, currentAnimationMetadata, null);

            if(!animated){
                plugin.getComponentLogger().info(Component.text("Animation " + animationId + " didn't play for model " + this.model.getName()));
            }
        }

        // Recorded whatever the renderer said, as it always was: this field is what was asked for, and
        // setMetadata re-plays it.
        this.currentAnimation = animationId;
        return animated;
    }

    @Override
    public boolean setAnimation(@NotNull String animationId, @NotNull Runnable runnable) {
        boolean animated = false;
        final var handle = this.model.getEntityTracker();
        if (handle.isPresent()) {
            animated = handle.get().playAnimation(animationId, currentAnimationMetadata, runnable);

            if(!animated){
                plugin.getComponentLogger().info(Component.text("Animation " + animationId + " didn't play for model " + this.model.getName()));
            }
        }

        this.currentAnimation = animationId;
        return animated;
    }

    @Override
    public boolean stopAnimation() {
        final var handle = this.model.getEntityTracker();
        final var current = getAnimation();
        return handle.isPresent() && current.isPresent() && handle.get().stopAnimation(current.get());
    }

    @Override
    public void setMetadata(@NotNull AnimationMetadata animationMetadata) {
        if(animationMetadata instanceof AnimationMetadataImpl animationMetadataImpl){
            this.currentAnimationMetadata = animationMetadataImpl;

            getAnimation().ifPresent(this::setAnimation);
        }else{
            // Says "this plugin's own", not a renderer's name: the rejected object fails an
            // instanceof against AnimationMetadataImpl, which is ours and has nothing to do with
            // which rendering backend is installed.
            plugin.getComponentLogger().info(Component.text("AnimationMetadata for model "
                    + model.getName() + " is not this plugin's own implementation and was ignored;"
                    + " build it through AnimationMetadata's factory.", NamedTextColor.RED));
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
