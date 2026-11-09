package pandastic.studio.models.api.model.component.animation;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;

import java.util.Optional;

public interface ModelAnimator<BaseEntityType extends Entity> extends ModelComponent<BaseEntityType> {

    void setAnimation(@NotNull String animationId);

    void setAnimation(@NotNull String animationId, @NotNull Runnable runnable);

    void stopAnimation();

    void setMetadata(@NotNull AnimationMetadata animationMetadata);

    @NotNull Optional<AnimationMetadata> getAnimationMetadata();

    /**
     * All models are triggered to be on idle animation when spawning, but if they don't have any animation, this will be empty
     * @return the optional current animation
     */
    @NotNull Optional<String> getAnimation();

}
