package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogContext;

/**
 * What a data-driven button does when it is pressed.
 *
 * <h2>Why this exists beside {@code @DialogButtonHandler}</h2>
 * The annotation pairs a handler method with a button <em>id written in source</em>. That is the right
 * shape for a fixed button — Wear, Close, Apply — and it cannot express a button per item in a list,
 * because the list's length is a configured page size and the ids are animation names read from disk.
 * A method cannot be annotated with a name nobody knows at compile time.
 *
 * <p>So the animation chooser's buttons carry their action with them instead, and {@link MenuSurface}
 * is the one place that knows how to attach it. Everything else in the layer keeps using the
 * annotation, which is why this interface takes the same {@link DialogContext} the annotated handlers
 * do: a reader who has met one has met the other, and an action can be moved between the two forms
 * without being rewritten.
 */
@FunctionalInterface
public interface MenuAction {

    /**
     * Runs the action.
     *
     * <p>Called on whichever thread the platform dispatches a dialog click on, which is the viewer's own
     * — a click is a packet from one player's connection. An implementation that touches an entity is
     * still expected to go through the presenter rather than assume that, because assuming it is exactly
     * the class of mistake Folia does not report.
     *
     * @param context who clicked, and what their inputs held at the time
     */
    void onClick(@NotNull DialogContext context);
}
