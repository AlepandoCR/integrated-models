package pandastic.studio.models.internal.menu;

import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogContext;

/**
 * A {@link DialogContext} for the clicks the dialog factory does not dispatch.
 *
 * <p>The factory builds one of these itself for every {@code @DialogButtonHandler} it invokes, but the
 * type it uses lives in its own internal package and the interface is what a handler is written against.
 * So the list buttons — the ones that carry their own action because no annotation can name them, see
 * {@link MenuAction} — build their context here, and every handler in this layer reads the same
 * interface whichever route brought it the click.
 *
 * <p>Not a record only because one of the interface's accessors is spelled {@code getAudience}, and a
 * record component by that name would put the prefix in every call site and every piece of
 * documentation.
 */
final class MenuContext implements DialogContext {

    private final @NotNull Audience audience;
    private final @NotNull DialogResponseView response;

    /**
     * @param audience who clicked
     * @param response what their inputs held at the time
     */
    MenuContext(@NotNull Audience audience, @NotNull DialogResponseView response) {
        this.audience = audience;
        this.response = response;
    }

    @Override
    public @NotNull Audience getAudience() {
        return this.audience;
    }

    @Override
    public @NotNull DialogResponseView dialogResponseView() {
        return this.response;
    }
}
