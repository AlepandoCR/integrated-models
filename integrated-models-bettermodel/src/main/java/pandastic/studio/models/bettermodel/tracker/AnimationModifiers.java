package pandastic.studio.models.bettermodel.tracker;

import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.util.function.FloatSupplier;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.animation.AnimationMetadata;

/**
 * Turns an {@link AnimationMetadata} into BetterModel's own {@code AnimationModifier}.
 *
 * <p>Lifted verbatim out of {@code AnimationMetadataImpl}, which used to carry it. It belongs on this side
 * of the renderer seam for the obvious reason — {@code AnimationModifier} and {@code AnimationIterator}
 * are BetterModel types — and for a less obvious one: the mapping is a <em>BetterModel</em> reading of
 * what those seven options mean, and a second backend is entitled to read them differently. Keeping it
 * next to the tracker that uses it is what makes that possible without touching the component API.
 *
 * <h2>Every option is optional, and an absent one is left at the renderer's default</h2>
 * The metadata's accessors return {@link java.util.Optional}, so this builds the modifier by applying
 * only what was set. A caller who set nothing gets the same modifier the renderer would have used anyway,
 * which is why the component API can hand a metadata value through unconditionally once it has one.
 *
 * <h2>Nothing here is tunable</h2>
 * The one literal below — the {@code 1F} a null speed falls back to — is an identity value, not a
 * calibration: a speed multiplier of one means "play at the authored rate". A {@code Supplier<Float>}
 * that answers {@code null} is a consumer's bug, and the honest reading of "no multiplier supplied" is
 * "do not multiply".
 */
final class AnimationModifiers {

    /**
     * The multiplier a null speed falls back to.
     *
     * <p><strong>An identity value, not a tunable.</strong> The metadata carries a
     * {@code Supplier<Float>}, which can answer {@code null}; one means "play at the rate the model was
     * authored at", which is the only defensible reading of a supplier that supplied nothing.
     */
    private static final float NEUTRAL_SPEED = 1F;

    private AnimationModifiers() {
        throw new AssertionError("No instances.");
    }

    /** {@code metadata} as a modifier, with every unset option left at the renderer's default. */
    static @NotNull AnimationModifier of(@NotNull AnimationMetadata metadata) {
        var builder = AnimationModifier.builder();

        metadata.getPredicate().ifPresent(builder::predicate);
        metadata.getStart().ifPresent(builder::start);
        metadata.getEnd().ifPresent(builder::end);
        metadata.getPriority().ifPresent(builder::priority);
        metadata.getAnimationType().map(AnimationModifiers::toIteratorType).ifPresent(builder::type);
        metadata.getOverride().ifPresent(builder::override);

        metadata.getSpeedSupplier().ifPresent(supplier -> {
            FloatSupplier floatSupplier = () -> {
                Float value = supplier.get();
                return value != null ? value : NEUTRAL_SPEED;
            };
            builder.speed(floatSupplier);
        });

        return builder.build();
    }

    private static @NotNull AnimationIterator.Type toIteratorType(
            @NotNull AnimationMetadata.AnimationType type) {
        return switch (type) {
            case PLAY_ONCE -> AnimationIterator.Type.PLAY_ONCE;
            case LOOP -> AnimationIterator.Type.LOOP;
            case HOLD_LAST_FRAME -> AnimationIterator.Type.HOLD_ON_LAST;
        };
    }
}
