package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.sim.ClipPlayer;

import java.util.Objects;

/**
 * Plays a model's own animation clips through its live {@link RenderBinding}.
 *
 * <p>The whole of the adapter between {@link ClipPlayer}'s two operations and the binding's. It exists
 * rather than being a lambda for one reason: a lambda would have to be written at the one call site that
 * has a binding in hand, and the question "which thread may call this" belongs in a type where it can be
 * answered once.
 *
 * <p>Clips always loop. A locomotion clip that played once would end with the model frozen in whatever
 * pose the last keyframe left it, and the layer above has no notion of "the clip finished" to react to —
 * it only knows what state the owner is in, which does not stop being true when a cycle ends.
 *
 * <h2>Threading</h2>
 * Both methods reach the renderer's animation state for a live tracker, so both are region work: they are
 * called from the thread that owns the model's anchor. {@link RiggedModelImpl} satisfies that by
 * advancing the clip layer from inside its anchor sampler, which runs on exactly that thread.
 */
final class BindingClipPlayer implements ClipPlayer {

    private final @NotNull RenderBinding binding;

    BindingClipPlayer(@NotNull RenderBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public boolean play(@NotNull String clip, int blendInTicks, int blendOutTicks) {
        if (this.binding.isClosed()) {
            // The tracker went between the decision and this call. Reported as "declined" rather than
            // thrown: the caller's next move is to remember the clip did not start, which is correct.
            return false;
        }
        return this.binding.playAnimation(clip, true, blendInTicks, blendOutTicks);
    }

    @Override
    public boolean stop(@NotNull String clip) {
        return !this.binding.isClosed() && this.binding.stopAnimation(clip);
    }

    @Override
    public @NotNull String toString() {
        return "BindingClipPlayer[" + this.binding + "]";
    }
}
