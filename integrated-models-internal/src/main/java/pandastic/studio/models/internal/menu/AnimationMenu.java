package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.dialog.api.Dialog;
import pandastic.studio.core.dialog.api.DialogBody;
import pandastic.studio.core.dialog.api.DialogButton;
import pandastic.studio.core.dialog.api.DialogButtonHandler;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The animation chooser: one button per animation, paged, plus Stop.
 *
 * <h2>The primitive, and why</h2>
 * A <strong>grid of buttons</strong>, one per animation. The original was a radial wheel selected by
 * cursor angle and committed on key release — which is one gesture that both picks and commits, and a
 * button is the only portable thing with that property. A chooser input plus a Play button would have
 * been two steps where the original had one.
 *
 * <p>So this screen uses buttons where the picker uses a chooser, and the asymmetry is deliberate: the
 * picker's original had a separate Wear press and this one did not.
 *
 * <h2>What is lost against the original, and why</h2>
 * <ul>
 *   <li><strong>The wheel.</strong> Neither cursor angle nor key release is observable — the client
 *       reports a click on a named element and nothing about where the pointer was or how long a key was
 *       held. A wheel cannot be reproduced as a wheel; the portable form is flat.</li>
 *   <li><strong>The radial arrangement.</strong> Buttons are laid out in a grid whose <em>column count
 *       the server cannot set</em>: the dialog layer builds the multi-action type without touching its
 *       column property, so there is no key for it. Width is the only lever, which is why
 *       {@code layout.buttonWidth} exists.</li>
 *   <li><strong>Hover preview.</strong> There is no mouse-position feedback at all. A tooltip is the
 *       whole of what a button can say before it is pressed.</li>
 * </ul>
 *
 * <h2>Why these buttons carry their own actions</h2>
 * Because {@code @DialogButtonHandler} pairs a handler with a button id <em>written in source</em>, and
 * there is one button per animation read off disk at a count that is a configured page size. No
 * annotation can name them. {@link MenuAction} is the seam that lets them be built anyway, and the
 * fixed buttons on this screen still use the annotation — so the file shows both forms side by side.
 */
@Dialog
final class AnimationMenu extends MenuScreen {

    /** What the viewer is wearing, read once on their own region thread. */
    private final @Nullable String worn;

    /** Every animation the worn model declares, in the catalogue's order. */
    private final @NotNull List<String> animations;

    /** The window of {@link #animations} this screen shows. */
    private final @NotNull MenuPage page;

    AnimationMenu(@NotNull MenuService service, @NotNull Player viewer, @NotNull MenuSettings settings) {
        super(service, viewer, settings);

        this.worn = service.wardrobe().wornModel(viewer).orElse(null);
        this.animations = this.worn == null
                ? List.of()
                : service.catalogue().animationIds(this.worn);
        this.page = MenuPage.of(
                this.animations.size(), settings.animation().pageSize(), this.session.animationPage());
        this.session.animationPage(this.page.index());
    }

    @Override
    protected @NotNull Component title() {
        return MenuText.of(this.settings.animation().title());
    }

    // ------------------------------------------------------------------ body

    @DialogBody(id = "heading", order = 0)
    @Nullable PlainMessageDialogBody heading() {
        return optionalLine(this.settings.animation().heading());
    }

    @DialogBody(id = "empty", order = 10)
    @Nullable PlainMessageDialogBody emptyLine() {
        if (this.worn == null) {
            return optionalLine(this.settings.animation().noModelMessage());
        }
        return this.animations.isEmpty() ? optionalLine(this.settings.animation().emptyMessage()) : null;
    }

    @DialogBody(id = "page", order = 20)
    @Nullable PlainMessageDialogBody pageLine() {
        if (this.page.pageCount() <= 1) {
            return null;
        }
        return optionalLine(this.settings.layout().pageIndicator(),
                MenuText.placeholder("page", this.page.humanIndex()),
                MenuText.placeholder("pages", this.page.pageCount()));
    }

    // ------------------------------------------------------------------ buttons

    /**
     * One button per animation on this page.
     *
     * <p>Returned already finished, with an action each, because the annotation cannot name them. A
     * {@code List<ActionButton.Builder>} is not an accepted return type for exactly this reason: a list
     * of builders would have nothing to attach an action from.
     *
     * @return the buttons, or {@code null} when this page has none
     */
    @DialogButton(id = "choices", order = 10)
    @Nullable List<ActionButton> choiceButtons() {
        final List<String> onPage = this.page.of(this.animations);
        if (onPage.isEmpty()) {
            return null;
        }

        final MenuSettings.Animation animation = this.settings.animation();
        final List<ActionButton> buttons = new ArrayList<>(onPage.size());
        for (final String animationId : onPage) {
            buttons.add(this.service.surface().button(
                    MenuText.of(animation.entryLabel(),
                            MenuText.placeholder("animation", animationId)),
                    MenuText.optional(animation.entryTooltip(),
                            MenuText.placeholder("animation", animationId)),
                    this.settings.layout().buttonWidth(),
                    context -> onChoice(context, animationId)));
        }
        return buttons;
    }

    @DialogButton(id = "stop", order = 20)
    @Nullable ActionButton.Builder stopButton() {
        return this.worn == null
                ? null
                : button(this.settings.animation().stopLabel(), this.settings.animation().stopTooltip());
    }

    @DialogButton(id = "previousPage", order = 30)
    @Nullable ActionButton.Builder previousPageButton() {
        return this.page.hasPrevious() ? button(this.settings.layout().previousPageLabel(), "") : null;
    }

    @DialogButton(id = "nextPage", order = 40)
    @Nullable ActionButton.Builder nextPageButton() {
        return this.page.hasNext() ? button(this.settings.layout().nextPageLabel(), "") : null;
    }

    @DialogButton(id = "back", order = 50)
    @NotNull ActionButton.Builder backButton() {
        return button(this.settings.layout().backLabel(), "");
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Plays the animation a list button stands for.
     *
     * <p>The id is re-checked against the model the player is wearing <em>now</em> rather than against
     * the list this screen was built from. The two differ whenever something took the model off between
     * the build and the click, which a player can arrange by pressing Remove in another window of the
     * same session — and a play call for a model that is gone is worth refusing with a sentence rather
     * than leaving to the wardrobe to ignore.
     *
     * @param context     the click
     * @param animationId the animation this button names
     */
    private void onChoice(@NotNull DialogContext context, @NotNull String animationId) {
        if (!isViewer(context)) {
            return;
        }
        final String model = this.service.wardrobe().wornModel(this.viewer).orElse(null);
        if (model == null) {
            notice(this.settings.animation().noModelMessage());
            this.service.openPicker(this.viewer);
            return;
        }
        if (!this.service.catalogue().animationIds(model).contains(animationId)) {
            notice(this.settings.animation().unknownAnimationMessage(),
                    MenuText.placeholder("animation", animationId));
            this.service.openAnimations(this.viewer);
            return;
        }

        this.service.wardrobe().playAnimation(this.viewer, animationId);
        notice(this.settings.animation().playedMessage(),
                MenuText.placeholder("animation", animationId));
        this.service.openAnimations(this.viewer);
    }

    @DialogButtonHandler(buttonId = "stop")
    void onStop(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.wardrobe().stopAnimation(this.viewer);
        notice(this.settings.animation().stoppedMessage());
        this.service.openAnimations(this.viewer);
    }

    @DialogButtonHandler(buttonId = "previousPage")
    void onPreviousPage(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.session.animationPage(this.page.previousIndex());
        this.service.openAnimations(this.viewer);
    }

    @DialogButtonHandler(buttonId = "nextPage")
    void onNextPage(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.session.animationPage(this.page.nextIndex());
        this.service.openAnimations(this.viewer);
    }

    @DialogButtonHandler(buttonId = "back")
    void onBack(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.openPicker(this.viewer);
    }
}
