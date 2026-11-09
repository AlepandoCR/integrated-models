package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.dialog.api.DialogBody;
import pandastic.studio.core.dialog.api.DialogButton;
import pandastic.studio.core.dialog.api.DialogButtonHandler;
import pandastic.studio.core.dialog.api.DialogConfigProvider;
import pandastic.studio.core.dialog.api.factory.DialogConfig;
import pandastic.studio.core.dialog.api.factory.DialogContext;

/**
 * What the three screens share: a viewer, one generation of settings, a session, a notice, and a Close
 * button.
 *
 * <h2>Why a base class and not duplication</h2>
 * The dialog factory resolves annotations across the whole hierarchy, so an annotated provider declared
 * here is as real as one declared in the subclass. That makes the shared chrome — the one-shot notice
 * line and the Close button — declarable once, which matters because both of them encode a platform
 * fact rather than a preference and the fact should be stated in one place.
 *
 * <p>Note that {@code @Dialog} itself is <strong>not</strong> inherited: the annotation is not
 * {@code @Inherited}, so every concrete screen carries its own. That is why this class has none.
 *
 * <h2>The two platform facts the chrome encodes</h2>
 * <ol>
 *   <li><strong>Every click closes the dialog.</strong> The factory builds every screen with its
 *       after-action set to close and offers no way to change it, so no screen can stay open across a
 *       press. Everything that looks like a menu reacting in place is a new screen, which is why
 *       {@link #settings} is read once into a field and why the notice is one-shot: a build is a whole
 *       screen's lifetime.</li>
 *   <li><strong>Nothing reports that a dialog was closed.</strong> So Close is a real button with real
 *       work to do — dropping the session — and pressing escape instead leaves the session to its
 *       timeout. The two paths differ, and only one of them is observable.</li>
 * </ol>
 *
 * <h2>One read per build, of everything</h2>
 * The settings, the session, the notice and whatever a subclass reads off the viewer are all captured in
 * the constructor, which the presenter runs on the viewer's own region thread exactly once per screen.
 * A provider that re-read the configuration could otherwise straddle a reload and produce a screen whose
 * title came from one generation and whose buttons came from another.
 */
abstract class MenuScreen {

    /** Where a screen reaches for navigation, the catalogue, the wardrobe and the element surface. */
    protected final @NotNull MenuService service;

    /** Who the screen is being built for. */
    protected final @NotNull Player viewer;

    /** The configuration generation this whole screen is built from. Read once; never re-read. */
    protected final @NotNull MenuSettings settings;

    /** What the player has in flight across screens. */
    protected final @NotNull MenuSession session;

    /** The line describing the click that led here, consumed by this build and no other. */
    private final @Nullable Component notice;

    /**
     * The title, read by the factory on every build.
     *
     * <p>Declared here so the hierarchy has exactly one such field — the factory refuses more than
     * one — and so a subclass only has to answer {@link #title()}.
     */
    @DialogConfigProvider
    private final @NotNull DialogConfig config;

    /**
     * @param service  the owning service
     * @param viewer   who the screen is for
     * @param settings the configuration generation to build against
     */
    MenuScreen(@NotNull MenuService service, @NotNull Player viewer, @NotNull MenuSettings settings) {
        this.service = service;
        this.viewer = viewer;
        this.settings = settings;
        this.session = service.sessions().open(viewer.getUniqueId());
        this.notice = this.session.takeNotice();
        // A supplier, so title() is called by the factory rather than by this constructor — a subclass
        // is not fully initialised yet.
        this.config = new MenuDialogConfig(this::title);
    }

    /** The screen's title, from the subclass's own section of {@code menu.yml}. */
    protected abstract @NotNull Component title();

    // ------------------------------------------------------------------ shared chrome

    /**
     * The line describing what the last click did, if there is one.
     *
     * <p>At order 5, between a screen's heading and its content, because it is about the click that
     * produced this screen rather than about the screen.
     *
     * @return the notice, or {@code null} to omit the line
     */
    @DialogBody(id = "notice", order = 5)
    final @Nullable PlainMessageDialogBody noticeLine() {
        if (this.notice == null) {
            return null;
        }
        return line(this.notice);
    }

    /**
     * The button that dismisses the menu and forgets the pending state.
     *
     * <p>Last, at an order no screen's own buttons reach, so Close is always in the same place.
     *
     * @return the button
     */
    @DialogButton(id = "close", order = 1_000)
    final @NotNull ActionButton.Builder closeButton() {
        return button(this.settings.layout().closeLabel(), "");
    }

    /**
     * Drops the session.
     *
     * <p>The dialog is already closing — every button closes it — so this handler's only job is the part
     * the platform cannot tell us about.
     *
     * @param context who clicked
     */
    @DialogButtonHandler(buttonId = "close")
    final void onClose(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.close(this.viewer);
    }

    // ------------------------------------------------------------------ helpers for subclasses

    /** A body line at the configured body width. */
    protected final @NotNull PlainMessageDialogBody line(@NotNull Component text) {
        return this.service.surface().message(text, this.settings.layout().bodyWidth());
    }

    /**
     * A body line from a template, or none when the template is blank.
     *
     * @param template  the MiniMessage source; blank omits the line
     * @param resolvers the placeholders to substitute
     * @return the body element, or {@code null} to omit it
     */
    protected final @Nullable PlainMessageDialogBody optionalLine(
            @NotNull String template,
            @NotNull TagResolver @NotNull ... resolvers
    ) {
        final Component rendered = MenuText.optional(template, resolvers);
        return rendered == null ? null : line(rendered);
    }

    /**
     * An unfinished button at the configured button width, with its action still to be attached by the
     * factory.
     *
     * @param labelTemplate   the MiniMessage label source
     * @param tooltipTemplate the MiniMessage tooltip source; blank for no tooltip
     * @return the unfinished button
     */
    protected final @NotNull ActionButton.Builder button(
            @NotNull String labelTemplate,
            @NotNull String tooltipTemplate
    ) {
        return this.service.surface().button(
                MenuText.of(labelTemplate),
                MenuText.optional(tooltipTemplate),
                this.settings.layout().buttonWidth());
    }

    /**
     * Whether the click really came from the player this screen was built for.
     *
     * <p>Defence in depth rather than a suspicion about the platform: a callback is registered per build
     * and only reachable by its recipient, so this should never fail. It costs one reference comparison
     * and removes the possibility that a future change to how callbacks are keyed turns one player's
     * button into another player's action.
     *
     * @param context the click
     * @return {@code true} when the clicker is this screen's viewer
     */
    protected final boolean isViewer(@NotNull DialogContext context) {
        return context.getAudience() == this.viewer;
    }

    /** Queues {@code template} to be shown on the next screen this player is sent. */
    protected final void notice(
            @NotNull String template,
            @NotNull TagResolver @NotNull ... resolvers
    ) {
        this.session.notice(MenuText.optional(template, resolvers));
    }
}
