package pandastic.studio.models.api.model.component.animation;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;

import java.util.Optional;

/**
 * The animation state of an {@link pandastic.studio.models.api.model.EntityModel}, as a component.
 *
 * <p>The three methods that drive a clip report whether the renderer accepted it, which is what lets
 * {@link pandastic.studio.models.api.model.AnimatedModel} implement
 * {@link pandastic.studio.models.api.model.Model#playAnimation(String)} by routing it here rather than
 * guessing. Each still records {@code animationId} as the current animation whatever the renderer
 * answered, so {@link #getAnimation()} reports what was asked for.
 */
public interface ModelAnimator<BaseEntityType extends Entity> extends ModelComponent<BaseEntityType> {

    /**
     * Plays {@code animationId}, with whatever metadata is set.
     *
     * @param animationId the clip to play
     * @return whether the renderer started it; {@code false} for an unbound model or a clip the model
     *         does not declare
     */
    boolean setAnimation(@NotNull String animationId);

    /**
     * {@link #setAnimation(String)} with a callback run when the clip ends.
     *
     * @param animationId the clip to play
     * @param runnable what to run when it finishes
     * @return whether the renderer started it
     */
    boolean setAnimation(@NotNull String animationId, @NotNull Runnable runnable);

    /**
     * Stops whatever clip is currently set.
     *
     * @return whether a clip was stopped
     */
    boolean stopAnimation();

    void setMetadata(@NotNull AnimationMetadata animationMetadata);

    @NotNull Optional<AnimationMetadata> getAnimationMetadata();

    /**
     * All models are triggered to be on idle animation when spawning, but if they don't have any animation, this will be empty
     * @return the optional current animation
     */
    @NotNull Optional<String> getAnimation();

}
