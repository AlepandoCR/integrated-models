package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The one place a screen asks for a dialog element, and the reason the menus are testable.
 *
 * <h2>Why a seam at all</h2>
 * Every factory that builds a dialog element — {@code DialogBody.plainMessage},
 * {@code DialogInput.numberRange}, {@code ActionButton.builder} — goes through a provider the running
 * server installs. Calling one without a server throws, which would mean no menu could be constructed in
 * a test and the only way to check that a screen offers a 0-255 slider would be to open it on a server
 * and look.
 *
 * <p>Behind this interface it is the opposite: a fake surface hands back ordinary objects implementing
 * the same element interfaces, and a test reads back the slider's bounds, the chooser's entries and the
 * buttons' labels directly. Every number in this layer — page sizes, widths, colour ranges, step
 * granularity — is therefore asserted against the configuration that produced it, with no server
 * anywhere.
 *
 * <h2>Why it returns Paper's element types</h2>
 * Because the dialog annotations require them: a {@code @DialogBody} method must return a
 * {@code DialogBody}, and a {@code @DialogButton} method an {@code ActionButton} or its builder. There is
 * no pandastic-core abstraction over the <em>contents</em> of a dialog, only over the controller that
 * assembles one, so these types are where the two layers meet. Nothing here touches
 * {@code io.papermc.paper.dialog} itself — the finished dialog is still built by
 * {@code DialogFactory}.
 *
 * <h2>Widths are parameters, not defaults</h2>
 * Every method that can take a width does, because the alternative is a plugin with a hard-coded
 * layout. A caller passes what the configuration said and this interface has no opinion.
 */
public interface MenuSurface {

    /**
     * A body line.
     *
     * @param text  the rendered line
     * @param width the line width
     * @return the body element
     */
    @NotNull PlainMessageDialogBody message(@NotNull Component text, int width);

    /**
     * The stand-in for a 3D preview: an item, optionally drawn with a pack-supplied model.
     *
     * <p><strong>There is no server-side 3D viewport.</strong> No packet can place a posed model inside a
     * dialog, so a preview is a flat picture and the only lever the server has over which picture is the
     * item model component — which a resource pack maps to an image per model id. That is the honest
     * substitute and it is the whole of what is available.
     *
     * @param materialName the material to show; matched the way Bukkit matches a material name
     * @param itemModelKey the {@code namespace:path} model to draw it with, or {@code null} for the
     *                     material's own model
     * @param width        width of the element
     * @param height       height of the element
     * <p>Typed as the item body rather than as a body, because a screen's provider method has to declare
     * a return type and the alternative name collides with the {@code @DialogBody} annotation in every
     * file that needs both.
     *
     * @param caption      a line beside the item, or {@code null} for none
     * @return the body element, or {@code null} when {@code materialName} names no item on this server
     */
    @Nullable ItemDialogBody thumbnail(
            @NotNull String materialName,
            @Nullable String itemModelKey,
            int width,
            int height,
            @Nullable Component caption
    );

    /**
     * A two-state toggle.
     *
     * @param key     the input key the click response carries it under
     * @param label   the rendered label
     * @param initial the state the control opens in
     * @return the input element
     */
    @NotNull BooleanDialogInput toggle(@NotNull String key, @NotNull Component label, boolean initial);

    /**
     * A slider over a numeric range.
     *
     * <p>{@code labelFormat} is a <strong>client translation key</strong> taking the label and the value,
     * not a format string; see {@code menu.yml}. The bounds are what the client is offered and not what
     * makes a submitted value legal — a click carries whatever the client composed, so the caller clamps
     * on the way back in.
     *
     * @param key         the input key the click response carries it under
     * @param label       the rendered label
     * @param labelFormat the client translation key the label and value are rendered through
     * @param start       the low end of the range
     * @param end         the high end of the range
     * @param initial     the value the handle opens at
     * @param step        the granularity the handle snaps to
     * @param width       width of the control
     * @return the input element
     */
    @NotNull NumberRangeDialogInput slider(
            @NotNull String key,
            @NotNull Component label,
            @NotNull String labelFormat,
            float start,
            float end,
            float initial,
            float step,
            int width
    );

    /**
     * A control offering one of a fixed set of choices.
     *
     * <p>The client cycles through the entries rather than scrolling them, which is why the caller pages
     * the list before handing it over: every entry is one more click away from the last.
     *
     * @param key     the input key the click response carries it under
     * @param label   the rendered label
     * @param options the choices, in order; at most one marked initial
     * @param width   width of the control
     * @return the input element
     */
    @NotNull SingleOptionDialogInput chooser(
            @NotNull String key,
            @NotNull Component label,
            @NotNull List<Option> options,
            int width
    );

    /**
     * A button whose action the dialog factory attaches from a {@code @DialogButtonHandler}.
     *
     * <p>The builder is handed back unfinished on purpose: the factory is what calls {@code build()},
     * after it has looked up the handler declared for this button's id.
     *
     * @param label   the rendered label
     * @param tooltip the rendered tooltip, or {@code null} for none
     * @param width   width of the button
     * @return the unfinished button
     */
    @NotNull ActionButton.Builder button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width
    );

    /**
     * A finished button carrying its own action.
     *
     * <p>For the buttons that cannot be annotated, because there is one per item in a list whose length
     * and contents are configuration and disk. See {@link MenuAction} for why the annotation cannot
     * express that.
     *
     * @param label   the rendered label
     * @param tooltip the rendered tooltip, or {@code null} for none
     * @param width   width of the button
     * @param action  what the click does
     * @return the finished button
     */
    @NotNull ActionButton button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width,
            @NotNull MenuAction action
    );

    /**
     * One choice offered by a {@link #chooser}.
     *
     * @param id      the value the click response carries when this entry is chosen
     * @param display what the player reads
     * @param initial whether the control opens on this entry
     */
    record Option(@NotNull String id, @NotNull Component display, boolean initial) {
    }
}
