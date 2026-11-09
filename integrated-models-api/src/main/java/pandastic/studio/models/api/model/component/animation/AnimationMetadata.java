package pandastic.studio.models.api.model.component.animation;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public interface AnimationMetadata {

    enum AnimationType{
        PLAY_ONCE,
        LOOP,
        HOLD_LAST_FRAME
    }
    @NotNull Optional<BooleanSupplier> getPredicate();

    @NotNull Optional<Integer> getStart();

    @NotNull Optional<Integer> getEnd();

    @NotNull Optional<Integer> getPriority();

    @NotNull Optional<AnimationType> getAnimationType();

    @NotNull Optional<Supplier<Float>> getSpeedSupplier();

    @NotNull Optional<Boolean> getOverride();

}
