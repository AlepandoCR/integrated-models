package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The menus, as one object: three ways in, one way out, and everything a screen needs to reach.
 *
 * <h2>Why navigation lives here and not in the screens</h2>
 * Because every click closes the dialog, so moving between screens is the normal case rather than the
 * exception — a button that looks like it changed a page actually built a new screen. If each controller
 * constructed the next, the graph would be spread across three files and the rule that a screen is built
 * on its viewer's own thread would be restated three times. Here it is stated once, in
 * {@link MenuPresenter}, and every transition goes through it.
 *
 * <h2>Why the refusals are transitions</h2>
 * {@link #openAppearance} and {@link #openAnimations} need a model to exist, and the only legal place to
 * find out is inside the presenter's hop. So they do not refuse by returning — they decide, inside the
 * hop, to build the picker with a line explaining why. The player gets one screen for one click either
 * way, which is the only behaviour a form can offer: there is no way to open a screen and then tell the
 * player it was the wrong one.
 *
 * <h2>Threading</h2>
 * Every {@code open} is safe from any thread. Nothing here reads an entity; the reads are in the
 * controllers' constructors, which run on the viewer's thread because the presenter puts them there.
 */
public final class MenuService {

    private final @NotNull MenuSettingsSource settings;
    private final @NotNull MenuSurface surface;
    private final @NotNull MenuSessions sessions;
    private final @NotNull ModelCatalogue catalogue;
    private final @NotNull ModelWardrobe wardrobe;
    private final @NotNull MenuPresenter presenter;

    /**
     * @param settings  where the live configuration is read from
     * @param surface   how a dialog element is built
     * @param sessions  where pending menu state lives
     * @param catalogue what the menus may offer
     * @param wardrobe  the write path
     * @param presenter how a screen reaches a player
     */
    public MenuService(
            @NotNull MenuSettingsSource settings,
            @NotNull MenuSurface surface,
            @NotNull MenuSessions sessions,
            @NotNull ModelCatalogue catalogue,
            @NotNull ModelWardrobe wardrobe,
            @NotNull MenuPresenter presenter
    ) {
        this.settings = settings;
        this.surface = surface;
        this.sessions = sessions;
        this.catalogue = catalogue;
        this.wardrobe = wardrobe;
        this.presenter = presenter;
    }

    /**
     * Shows {@code viewer} the model picker.
     *
     * <p>Also the screen every refusal and every Back press lands on, which is why it has no
     * precondition of its own: a picker with nothing in it is still a picker, and it says so.
     *
     * @param viewer who to show it to
     */
    public void openPicker(@NotNull Player viewer) {
        this.presenter.show(viewer, () -> new ModelPickerMenu(this, viewer, this.settings.current()));
    }

    /**
     * Shows {@code viewer} the appearance screen, or the picker with a reason.
     *
     * @param viewer who to show it to
     */
    public void openAppearance(@NotNull Player viewer) {
        this.presenter.show(viewer, () -> {
            final MenuSettings current = this.settings.current();
            if (this.wardrobe.wornModel(viewer).isEmpty()) {
                return refuse(viewer, current, current.appearance().noModelMessage());
            }
            return new AppearanceMenu(this, viewer, current);
        });
    }

    /**
     * Shows {@code viewer} the animation chooser, or the picker with a reason.
     *
     * @param viewer who to show it to
     */
    public void openAnimations(@NotNull Player viewer) {
        this.presenter.show(viewer, () -> {
            final MenuSettings current = this.settings.current();
            if (this.wardrobe.wornModel(viewer).isEmpty()) {
                return refuse(viewer, current, current.animation().noModelMessage());
            }
            return new AnimationMenu(this, viewer, current);
        });
    }

    /**
     * Forgets {@code viewer}'s pending menu state.
     *
     * <p>The Close button, and the only deterministic end a session has: pressing escape instead leaves
     * it to the timeout, because nothing reports that a dialog was closed.
     *
     * @param viewer whose state to drop
     */
    public void close(@NotNull Player viewer) {
        this.sessions.close(viewer.getUniqueId());
    }

    /** Forgets every session. For plugin disable. */
    public void shutdown() {
        this.sessions.clear();
    }

    /**
     * The picker, carrying the line that explains why it is not the screen that was asked for.
     *
     * <p>The notice is queued on the session and then immediately consumed by the picker's own
     * constructor, which is the same path a notice from a click takes. One mechanism, so a refusal reads
     * on screen exactly like a confirmation.
     *
     * @param viewer   who is being refused
     * @param current  the configuration generation in hand
     * @param template the MiniMessage source of the reason
     * @return the picker
     */
    private @NotNull Object refuse(
            @NotNull Player viewer,
            @NotNull MenuSettings current,
            @NotNull String template
    ) {
        this.sessions.open(viewer.getUniqueId()).notice(MenuText.optional(template));
        return new ModelPickerMenu(this, viewer, current);
    }

    /** How a screen builds a dialog element. */
    @NotNull MenuSurface surface() {
        return this.surface;
    }

    /** Where pending menu state lives. */
    @NotNull MenuSessions sessions() {
        return this.sessions;
    }

    /** What a screen may offer. */
    @NotNull ModelCatalogue catalogue() {
        return this.catalogue;
    }

    /** The write path. */
    @NotNull ModelWardrobe wardrobe() {
        return this.wardrobe;
    }
}
