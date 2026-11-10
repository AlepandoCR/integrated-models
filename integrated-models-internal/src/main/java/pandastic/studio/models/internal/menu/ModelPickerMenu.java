package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.dialog.api.Dialog;
import pandastic.studio.core.dialog.api.DialogBody;
import pandastic.studio.core.dialog.api.DialogButton;
import pandastic.studio.core.dialog.api.DialogButtonHandler;
import pandastic.studio.core.dialog.api.DialogInput;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The model picker: a paged list of model ids and the actions the original had.
 *
 * <h2>The primitive, and why</h2>
 * A <strong>single-option input plus action buttons</strong>, which is the shape the original screen
 * had: a list you move through and a separate Wear press that commits. Making each model id a button
 * instead would have collapsed those two steps into one, which is a different interaction — you could
 * no longer look at something without putting it on.
 *
 * <p>An inventory GUI was the other candidate and loses here for a reason that has nothing to do with
 * taste: the whole menu layer is required to go through the dialog API, and a chest full of item stacks
 * is not that API. It would also mean a second input convention in one plugin for no gain — a picker is
 * not continuous adjustment, it is one choice and one commit, which a form does well.
 *
 * <p>The chooser is cycled rather than scrolled, so a long list is paged instead.
 *
 * <h2>The id that comes back is not trusted</h2>
 * A click carries the inputs as the client composed them, so the model id in a Wear press is a string
 * from the network and nothing more. {@link ModelCatalogue#hasModel} is what makes it a model, and it is
 * re-asked on every press — not once when the screen was built — because the catalogue can be reloaded
 * in between by this very screen.
 */
@Dialog
final class ModelPickerMenu extends MenuScreen {

    /** The input key the chooser's selection comes back under. */
    private static final String MODEL_KEY = "model";

    /** Every model id available, in the catalogue's own order. Read once, per build. */
    private final @NotNull List<String> models;

    /** The window of {@link #models} this screen shows. */
    private final @NotNull MenuPage page;

    /** What the viewer is wearing, read once on their own region thread. */
    private final @Nullable String worn;

    /** The id the thumbnail and the chooser's initial entry are for, or {@code null} when there is none. */
    private final @Nullable String previewed;

    ModelPickerMenu(@NotNull MenuService service, @NotNull Player viewer, @NotNull MenuSettings settings) {
        super(service, viewer, settings);

        this.models = service.catalogue().modelIds();
        this.page = MenuPage.of(
                this.models.size(), settings.picker().pageSize(), this.session.pickerPage());
        // Write the clamped index back, so a page number left over from a larger catalogue stops being
        // stale rather than being clamped again on every build.
        this.session.pickerPage(this.page.index());

        this.worn = service.wardrobe().wornModel(viewer).orElse(null);
        this.previewed = resolvePreview();
    }

    /**
     * What the thumbnail shows and the chooser opens on.
     *
     * <p>In order: what the player last previewed, then what they are wearing, then the first entry on
     * the page. The fallbacks matter because the first two can both name a model that no longer exists —
     * a reload between two clicks is the ordinary case, not the pathological one — and a chooser whose
     * initial entry is absent opens on something arbitrary.
     *
     * @return the id to preview, or {@code null} when the catalogue is empty
     */
    private @Nullable String resolvePreview() {
        final String selected = this.session.selectedModel().orElse(null);
        if (selected != null && this.models.contains(selected)) {
            return selected;
        }
        if (this.worn != null && this.models.contains(this.worn)) {
            return this.worn;
        }
        final List<String> onPage = this.page.of(this.models);
        return onPage.isEmpty() ? null : onPage.getFirst();
    }

    @Override
    protected @NotNull Component title() {
        return MenuText.of(this.settings.picker().title());
    }

    // ------------------------------------------------------------------ body

    @DialogBody(id = "heading", order = 0)
    @Nullable PlainMessageDialogBody heading() {
        return optionalLine(this.settings.picker().heading());
    }

    @DialogBody(id = "worn", order = 10)
    @Nullable PlainMessageDialogBody wornLine() {
        if (this.worn == null) {
            return optionalLine(this.settings.picker().nothingWornLine());
        }
        return optionalLine(this.settings.picker().wornLine(),
                MenuText.placeholder("model", this.worn));
    }

    /** Retained as a compatibility seam for presenters; dialog registration intentionally does not expose it. */
    @Nullable ItemDialogBody preview() {
        if (this.previewed == null) {
            return null;
        }
        final MenuSettings.Picker picker = this.settings.picker();
        return this.service.surface().thumbnail(
                picker.thumbnailMaterial(),
                picker.hasItemModel()
                        ? picker.thumbnailItemModel().replace("<model>", this.previewed)
                        : null,
                picker.thumbnailWidth(),
                picker.thumbnailHeight(),
                MenuText.optional(picker.thumbnailDescription(),
                        MenuText.placeholder("model", this.previewed)));
    }

    @DialogBody(id = "empty", order = 30)
    @Nullable PlainMessageDialogBody emptyLine() {
        return this.models.isEmpty() ? optionalLine(this.settings.picker().emptyMessage()) : null;
    }

    @DialogBody(id = "page", order = 40)
    @Nullable PlainMessageDialogBody pageLine() {
        if (this.page.pageCount() <= 1) {
            return null;
        }
        return optionalLine(this.settings.layout().pageIndicator(),
                MenuText.placeholder("page", this.page.humanIndex()),
                MenuText.placeholder("pages", this.page.pageCount()));
    }

    // ------------------------------------------------------------------ input

    @DialogInput(id = MODEL_KEY, order = 0)
    @Nullable SingleOptionDialogInput chooser() {
        final List<String> onPage = this.page.of(this.models);
        if (onPage.isEmpty()) {
            return null;
        }

        final List<MenuSurface.Option> options = new ArrayList<>(onPage.size());
        for (final String modelId : onPage) {
            options.add(new MenuSurface.Option(
                    modelId, Component.text(modelId), modelId.equals(this.previewed)));
        }
        return this.service.surface().chooser(
                MODEL_KEY,
                MenuText.of(this.settings.picker().chooserLabel()),
                options,
                this.settings.layout().inputWidth());
    }

    // ------------------------------------------------------------------ buttons

    @DialogButton(id = "wear", order = 10)
    @Nullable ActionButton.Builder wearButton() {
        return this.models.isEmpty()
                ? null
                : button(this.settings.picker().wearLabel(), this.settings.picker().wearTooltip());
    }

    /** Retained for tests and extensions; the thumbnail refresh button is no longer registered. */
    @Nullable ActionButton.Builder refreshButton() {
        return this.models.isEmpty()
                ? null
                : button(this.settings.picker().refreshLabel(), this.settings.picker().refreshTooltip());
    }

    @DialogButton(id = "remove", order = 30)
    @Nullable ActionButton.Builder removeButton() {
        return this.worn == null
                ? null
                : button(this.settings.picker().removeLabel(), this.settings.picker().removeTooltip());
    }

    @DialogButton(id = "appearance", order = 40)
    @Nullable ActionButton.Builder appearanceButton() {
        return this.worn == null ? null : button(this.settings.picker().appearanceLabel(), "");
    }

    @DialogButton(id = "animations", order = 50)
    @Nullable ActionButton.Builder animationsButton() {
        return this.worn == null ? null : button(this.settings.picker().animationsLabel(), "");
    }

    @DialogButton(id = "previousPage", order = 60)
    @Nullable ActionButton.Builder previousPageButton() {
        return this.page.hasPrevious() ? button(this.settings.layout().previousPageLabel(), "") : null;
    }

    @DialogButton(id = "nextPage", order = 70)
    @Nullable ActionButton.Builder nextPageButton() {
        return this.page.hasNext() ? button(this.settings.layout().nextPageLabel(), "") : null;
    }

    @DialogButton(id = "reload", order = 80)
    @NotNull ActionButton.Builder reloadButton() {
        return button(this.settings.picker().reloadLabel(), this.settings.picker().reloadTooltip());
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Puts the selected model on, or says why not.
     *
     * @param context the click
     */
    @DialogButtonHandler(buttonId = "wear")
    void onWear(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        final String chosen = submittedModel(context);
        if (chosen == null) {
            this.service.openPicker(this.viewer);
            return;
        }
        if (!this.service.catalogue().hasModel(chosen)) {
            notice(this.settings.picker().unknownModelMessage(),
                    MenuText.placeholder("model", chosen));
            this.service.openPicker(this.viewer);
            return;
        }

        this.session.selectedModel(chosen);
        this.service.wardrobe().wear(this.viewer, chosen);
        notice(this.settings.picker().wornMessage(), MenuText.placeholder("model", chosen));
        this.service.openPicker(this.viewer);
    }

    /** Retained for callers that explicitly request a picker refresh; it is no longer a dialog action. */
    void onRefresh(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        final String chosen = submittedModel(context);
        if (chosen != null && this.service.catalogue().hasModel(chosen)) {
            this.session.selectedModel(chosen);
        } else if (chosen != null) {
            notice(this.settings.picker().unknownModelMessage(),
                    MenuText.placeholder("model", chosen));
        }
        this.service.openPicker(this.viewer);
    }

    @DialogButtonHandler(buttonId = "remove")
    void onRemove(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.wardrobe().remove(this.viewer);
        notice(this.settings.picker().removedMessage());
        this.service.openPicker(this.viewer);
    }

    @DialogButtonHandler(buttonId = "appearance")
    void onAppearance(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        rememberSelection(context);
        this.service.openAppearance(this.viewer);
    }

    @DialogButtonHandler(buttonId = "animations")
    void onAnimations(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        rememberSelection(context);
        this.service.openAnimations(this.viewer);
    }

    @DialogButtonHandler(buttonId = "previousPage")
    void onPreviousPage(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        rememberSelection(context);
        this.session.pickerPage(this.page.previousIndex());
        this.service.openPicker(this.viewer);
    }

    @DialogButtonHandler(buttonId = "nextPage")
    void onNextPage(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        rememberSelection(context);
        this.session.pickerPage(this.page.nextIndex());
        this.service.openPicker(this.viewer);
    }

    /**
     * Re-reads what is installed and says how much there is.
     *
     * <p>Deliberately does not clear the remembered page: {@link MenuPage#of} clamps an index the new
     * catalogue is too small for, so a reload that removed models leaves the player on the last page
     * rather than back at the first.
     *
     * @param context the click
     */
    @DialogButtonHandler(buttonId = "reload")
    void onReload(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        rememberSelection(context);
        final int available = this.service.catalogue().reload();
        notice(this.settings.picker().reloadedMessage(), MenuText.placeholder("count", available));
        this.service.openPicker(this.viewer);
    }

    // ------------------------------------------------------------------ click helpers

    /**
     * The model id the click carried, if the screen had a chooser at all.
     *
     * @param context the click
     * @return the submitted id, or {@code null} when the screen offered no chooser
     */
    private @Nullable String submittedModel(@NotNull DialogContext context) {
        return context.dialogResponseView().getText(MODEL_KEY);
    }

    /**
     * Keeps the selection across a navigation that is not about it.
     *
     * <p>Paging, or stepping into another screen, should not lose what the player had picked — but an id
     * that is not in the catalogue is not worth remembering, because the next build would discard it
     * anyway and the player would rather be told nothing than told twice.
     *
     * @param context the click
     */
    private void rememberSelection(@NotNull DialogContext context) {
        final String chosen = submittedModel(context);
        if (chosen != null && this.service.catalogue().hasModel(chosen)) {
            this.session.selectedModel(chosen);
        }
    }
}
