package pandastic.studio.models.api.model;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.animation.ModelAnimator;

/**
 * An {@link EntityModel} that carries an animator component.
 *
 * <p>{@link Model}'s two unconditional animation methods are implemented here by routing them to that
 * component, so {@code model.playAnimation("walk")} and {@code model.getAnimator().setAnimation("walk")}
 * are the same call and report the same thing. The animator remains the richer surface — it is the only
 * way to attach an {@link pandastic.studio.models.api.model.component.animation.AnimationMetadata} or an
 * end callback — and the flat form is what a consumer holding a mixed collection of models can use
 * without knowing which kind it is holding.
 *
 * <p>{@link Model#playAnimationOnce(String)}, {@link Model#replaceAnimation(String, String)} and
 * {@link Model#getAnimationNames()} keep their no-op defaults: on this path a looping flag belongs to an
 * {@code AnimationMetadata} rather than to the call, and the component API never had a way to enumerate a
 * model's clips.
 */
public interface AnimatedModel<BaseEntityType extends Entity> extends EntityModel<BaseEntityType> {

    /**
     * This model's animation state, as a component.
     *
     * @return the animator
     */
    @NotNull ModelAnimator<BaseEntityType> getAnimator();

    @Override
    default boolean playAnimation(@NotNull String animationName) {
        return getAnimator().setAnimation(animationName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only stops {@code animationName} if that is what is playing. The animator stops whatever is
     * current, which is a different question from the one this method asks, and answering the wrong one
     * would let a caller stop an animation it never named.
     */
    @Override
    default boolean stopAnimation(@NotNull String animationName) {
        final ModelAnimator<BaseEntityType> animator = getAnimator();
        return animator.getAnimation().filter(animationName::equals).isPresent()
                && animator.stopAnimation();
    }
}
