package pandastic.studio.models.internal.model.component.animation;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.component.animation.AnimationMetadata;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The only {@link AnimationMetadata} implementation this plugin accepts, and a plain value.
 *
 * <p>Carries no renderer type and performs no translation: mapping these seven options onto a rendering
 * library's own animation options is the backend module's job, behind
 * {@link pandastic.studio.models.internal.spi.ModelHandle#playAnimation}. That is why
 * {@code ModelAnimatorImpl.setMetadata} still gates on {@code instanceof AnimationMetadataImpl} — the
 * check is about this class being the one whose accessors a backend is written against, not about which
 * renderer is installed.
 */
public class AnimationMetadataImpl implements AnimationMetadata{

    private final @Nullable BooleanSupplier predicate;
    private final @Nullable Integer start;
    private final @Nullable Integer end;
    private final @Nullable Integer priority;
    private final @Nullable AnimationType animationType;
    private final @Nullable Supplier<Float> speedSupplier;
    private final @Nullable Boolean override;

    public AnimationMetadataImpl(
            @Nullable BooleanSupplier predicate,
            @Nullable Integer start,
            @Nullable Integer end,
            @Nullable Integer priority,
            @Nullable AnimationType animationType,
            @Nullable Supplier<Float> speedSupplier,
            @Nullable Boolean override
    ){
        this.predicate = predicate;
        this.start = start;
        this.end = end;
        this.priority = priority;
        this.animationType = animationType;
        this.speedSupplier = speedSupplier;
        this.override = override;
    }

    @Override
    public @NotNull Optional<BooleanSupplier> getPredicate() {
        return Optional.ofNullable(predicate);
    }

    @Override
    public @NotNull Optional<Integer> getStart() {
        return Optional.ofNullable(start);
    }

    @Override
    public @NotNull Optional<Integer> getEnd() {
        return Optional.ofNullable(end);
    }

    @Override
    public @NotNull Optional<Integer> getPriority() {
        return Optional.ofNullable(priority);
    }

    @Override
    public @NotNull Optional<AnimationType> getAnimationType() {
        return Optional.ofNullable(animationType);
    }

    @Override
    public @NotNull Optional<Supplier<Float>> getSpeedSupplier() {
        return Optional.ofNullable(speedSupplier);
    }

    @Override
    public @NotNull Optional<Boolean> getOverride() {
        return Optional.ofNullable(override);
    }

    @Contract(value = " -> new", pure = true)
    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private @Nullable BooleanSupplier predicate;
        private @Nullable Integer start;
        private @Nullable Integer end;
        private @Nullable Integer priority;
        private @Nullable AnimationType animationType;
        private @Nullable Supplier<Float> speedSupplier;
        private @Nullable Boolean override;

        public @NotNull Builder predicate(@Nullable BooleanSupplier predicate) {
            this.predicate = predicate;
            return this;
        }

        public @NotNull Builder start(@Nullable Integer start) {
            this.start = start;
            return this;
        }

        public @NotNull Builder end(@Nullable Integer end) {
            this.end = end;
            return this;
        }

        public @NotNull Builder range(int start, int end) {
            this.start = start;
            this.end = end;
            return this;
        }

        public @NotNull Builder priority(@Nullable Integer priority) {
            this.priority = priority;
            return this;
        }

        public @NotNull Builder animationType(@Nullable AnimationType animationType) {
            this.animationType = animationType;
            return this;
        }

        public @NotNull Builder speedSupplier(@Nullable Supplier<Float> speedSupplier) {
            this.speedSupplier = speedSupplier;
            return this;
        }

        public @NotNull Builder speed(float speed) {
            this.speedSupplier = () -> speed;
            return this;
        }

        public @NotNull Builder override(@Nullable Boolean override) {
            this.override = override;
            return this;
        }

        public @NotNull AnimationMetadataImpl build() {
            return new AnimationMetadataImpl(
                    predicate,
                    start,
                    end,
                    priority,
                    animationType,
                    speedSupplier,
                    override
            );
        }
    }
}
