package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code menu.yml}: every word a menu puts on a player's screen, every bound a slider
 * offers, and how long a menu a player walked away from is remembered.
 *
 * <h2>Why the labels are here and not in the code</h2>
 * Because a label is the one part of this plugin an operator is certain to want to change, and because
 * there is no second place to put it. The dialog layer has no translation seam: a dialog is serialised
 * to the client as finished components, so the server decides the wording for every viewer. Hard-coding
 * it would mean a fork per language and a rebuild per typo.
 *
 * <p>Every text key is <strong>MiniMessage</strong>. That buys colour, hover and bold from the same
 * string, so there is no separate colour key per label — an operator writing
 * {@code <red><bold>Remove} gets exactly that, and one who writes {@code Remove} gets plain text. The
 * keys documented as carrying a placeholder accept it in angle brackets, which is MiniMessage's own tag
 * syntax; a placeholder nobody substitutes renders as nothing rather than as the literal tag.
 *
 * <h2>The two keys that are not what they look like</h2>
 * {@link #appearanceChannelLabelFormat()} and {@link #appearanceScaleLabelFormat()} are
 * <strong>client translation keys</strong>, not format strings. A number-range slider carries its label
 * and its current value to the client, which renders them through a translation whose two arguments they
 * are; the vanilla default {@code options.generic_value} resolves to {@code <label>: <value>}. A printf
 * pattern written here reaches the client as a missing translation key and is displayed verbatim, which
 * looks like a plugin bug and is not one.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.menu.MenuSettings} is a different type with the same simple
 * name: it is the assembled, already-validated value the menu layer reads. This one is the
 * <em>file</em> — the keys, the comments and the defaults. The project has the same deliberate shadow
 * for render, simulation and ai; nothing outside this package should need to name this interface.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "MenuConfig", format = ConfigFormat.YAML)
public interface MenuSettings {

    // =================================================================================================
    // Sessions
    // =================================================================================================

    /**
     * How long a player's menu state is kept after their last click, in seconds.
     *
     * <p>This key exists because of something the platform does not offer: <strong>there is no close
     * notification of any kind</strong>. A player who presses escape, logs out, or simply walks away
     * leaves no trace the server can observe, so a session can only be ended by a timer. Without one,
     * every menu a player ever opened would be remembered until the server stopped.
     *
     * <p>It is a timeout on <em>pending</em> state — the page they were on, the colour they had dialled
     * in but not applied — and never on anything already applied to a model. Set it long enough that a
     * player who goes to read a sign comes back to the slider where they left it.
     *
     * @return the session idle timeout, in seconds
     */
    @ConfigValue(fieldName = "session.timeoutSeconds",
            comment = "Seconds of inactivity after which a player's pending menu state is forgotten. The client never tells the server a dialog was closed, so a timer is the only way a session can end.")
    default float sessionTimeoutSeconds() {
        return 180F;
    }

    /**
     * How many players may have live menu state at once.
     *
     * <p>A ceiling on memory rather than on behaviour. Sessions expire on their own, but expiry is
     * evaluated lazily when the store is next touched, so a burst of openers can hold more entries than
     * the timeout suggests. Past this many, the least recently used session is dropped — which costs
     * that player their pending edits and nothing else.
     *
     * @return the session count ceiling
     */
    @ConfigValue(fieldName = "session.maxSessions",
            comment = "How many players may hold live menu state at once. Past this, the least recently used session is dropped and that player loses only their unapplied edits.")
    default int sessionMaxSessions() {
        return 256;
    }

    // =================================================================================================
    // Shared layout and chrome
    // =================================================================================================

    /**
     * Width of a body line, in the dialog layout's own units.
     *
     * <p>Clamped by the client to at most 1024, which is why the validator refuses more: a wider value
     * is not a wider line, it is a number the client discards.
     *
     * @return the body width
     */
    @ConfigValue(fieldName = "layout.bodyWidth",
            comment = "Width of a text line in a dialog, 1 to 1024. The client clamps anything larger, so a bigger number buys nothing.")
    default int layoutBodyWidth() {
        return 310;
    }

    /**
     * Width of an input control, in the dialog layout's own units.
     *
     * @return the input width
     */
    @ConfigValue(fieldName = "layout.inputWidth",
            comment = "Width of a slider, toggle or chooser, 1 to 1024.")
    default int layoutInputWidth() {
        return 240;
    }

    /**
     * Width of an action button, in the dialog layout's own units.
     *
     * <p>Buttons are laid out in a grid whose <em>column count the server cannot choose</em>, so this is
     * the only lever on how a row of buttons reads.
     *
     * @return the button width
     */
    @ConfigValue(fieldName = "layout.buttonWidth",
            comment = "Width of an action button, 1 to 1024. The number of button columns is fixed by the client, so width is the only control over how a row of buttons reads.")
    default int layoutButtonWidth() {
        return 150;
    }

    /**
     * The line telling a player which page they are on.
     *
     * <p>Placeholders: {@code <page>} and {@code <pages>}, both one-based.
     *
     * @return the page indicator template
     */
    @ConfigValue(fieldName = "layout.pageIndicator",
            comment = "Line showing which page is open. Placeholders: page, pages. MiniMessage.")
    default String layoutPageIndicator() {
        return "<gray>Page <white><page></white> of <white><pages></white>";
    }

    /** Label of the button that moves one page forward. */
    @ConfigValue(fieldName = "layout.nextPageLabel", comment = "Label of the next-page button. MiniMessage.")
    default String layoutNextPageLabel() {
        return "<white>Next ▶";
    }

    /** Label of the button that moves one page back. */
    @ConfigValue(fieldName = "layout.previousPageLabel",
            comment = "Label of the previous-page button. MiniMessage.")
    default String layoutPreviousPageLabel() {
        return "<white>◀ Previous";
    }

    /** Label of the button that returns to the model picker. */
    @ConfigValue(fieldName = "layout.backLabel",
            comment = "Label of the button that returns to the model picker. MiniMessage.")
    default String layoutBackLabel() {
        return "<gray>↩ Back";
    }

    /**
     * Label of the button that dismisses a menu.
     *
     * <p>Every click closes the dialog whichever button was pressed — the dialog layer offers no way to
     * keep one open — so this button's only job beyond closing is to drop the player's pending state.
     *
     * @return the close label
     */
    @ConfigValue(fieldName = "layout.closeLabel",
            comment = "Label of the button that dismisses a menu and forgets the pending edits. Every button closes the dialog, so this one differs only in discarding that state.")
    default String layoutCloseLabel() {
        return "<red>Close";
    }

    // =================================================================================================
    // Model picker
    // =================================================================================================

    /** Title of the model picker. */
    @ConfigValue(fieldName = "picker.title", comment = "Title of the model picker. MiniMessage.")
    default String pickerTitle() {
        return "<dark_aqua><bold>Models";
    }

    /** First body line of the model picker. Blank to omit the line. */
    @ConfigValue(fieldName = "picker.heading",
            comment = "First body line of the model picker. Leave blank to omit it. MiniMessage.")
    default String pickerHeading() {
        return "<gray>Choose a model, then press Wear.";
    }

    /**
     * How many model ids one page of the chooser offers.
     *
     * <p>The chooser is a single control the player cycles through, so every entry on a page is one more
     * click away from the last. Paging it is not a workaround for a long list, it is what keeps the
     * control usable at all — and the page size is the trade between clicks inside a page and clicks
     * between pages.
     *
     * @return the picker page size
     */
    @ConfigValue(fieldName = "picker.pageSize",
            comment = "How many model ids one page of the chooser offers. The chooser is cycled through rather than scrolled, so a large page means many clicks to reach the last entry.")
    default int pickerPageSize() {
        return 8;
    }

    /** Label shown beside the model chooser. */
    @ConfigValue(fieldName = "picker.chooserLabel",
            comment = "Label shown beside the model chooser. MiniMessage.")
    default String pickerChooserLabel() {
        return "<white>Model";
    }

    /** Body line shown when no model is available at all. */
    @ConfigValue(fieldName = "picker.emptyMessage",
            comment = "Body line shown when no model is available. MiniMessage.")
    default String pickerEmptyMessage() {
        return "<red>No models are installed.";
    }

    /** Label of the button that applies the chosen model. */
    @ConfigValue(fieldName = "picker.wearLabel", comment = "Label of the Wear button. MiniMessage.")
    default String pickerWearLabel() {
        return "<green>Wear";
    }

    /** Tooltip of the Wear button. Blank for no tooltip. */
    @ConfigValue(fieldName = "picker.wearTooltip",
            comment = "Tooltip of the Wear button. Leave blank for none. MiniMessage.")
    default String pickerWearTooltip() {
        return "<gray>Put the selected model on.";
    }

    /** Label of the button that takes the current model off. */
    @ConfigValue(fieldName = "picker.removeLabel", comment = "Label of the Remove button. MiniMessage.")
    default String pickerRemoveLabel() {
        return "<red>Remove";
    }

    /** Tooltip of the Remove button. Blank for no tooltip. */
    @ConfigValue(fieldName = "picker.removeTooltip",
            comment = "Tooltip of the Remove button. Leave blank for none. MiniMessage.")
    default String pickerRemoveTooltip() {
        return "<gray>Take the current model off.";
    }

    /**
     * Label of the button that redraws the picker against the current selection.
     *
     * <p>It exists because of a platform limit rather than a design choice: the server is never told
     * that an input changed, so the thumbnail beside the chooser cannot follow the selection. This
     * button is how a player asks for the thumbnail of what they have just selected.
     *
     * @return the refresh label
     */
    @ConfigValue(fieldName = "picker.refreshLabel",
            comment = "Label of the button that redraws the picker so the thumbnail matches the selection. The server is never told that an input changed, so the preview cannot follow it on its own.")
    default String pickerRefreshLabel() {
        return "<yellow>Show preview";
    }

    /** Tooltip of the preview button. Blank for no tooltip. */
    @ConfigValue(fieldName = "picker.refreshTooltip",
            comment = "Tooltip of the preview button. Leave blank for none. MiniMessage.")
    default String pickerRefreshTooltip() {
        return "<gray>Redraw this screen with the selected model previewed.";
    }

    /** Label of the button that re-reads the installed models. */
    @ConfigValue(fieldName = "picker.reloadLabel", comment = "Label of the Reload button. MiniMessage.")
    default String pickerReloadLabel() {
        return "<aqua>Reload";
    }

    /** Tooltip of the Reload button. Blank for no tooltip. */
    @ConfigValue(fieldName = "picker.reloadTooltip",
            comment = "Tooltip of the Reload button. Leave blank for none. MiniMessage.")
    default String pickerReloadTooltip() {
        return "<gray>Re-read the installed models.";
    }

    /** Label of the button that opens the appearance screen. */
    @ConfigValue(fieldName = "picker.appearanceLabel",
            comment = "Label of the button that opens the appearance screen. MiniMessage.")
    default String pickerAppearanceLabel() {
        return "<light_purple>Appearance";
    }

    /** Label of the button that opens the animation chooser. */
    @ConfigValue(fieldName = "picker.animationsLabel",
            comment = "Label of the button that opens the animation chooser. MiniMessage.")
    default String pickerAnimationsLabel() {
        return "<gold>Animations";
    }

    /**
     * Body line naming the model the player is wearing. Placeholder: {@code <model>}.
     *
     * @return the worn line template
     */
    @ConfigValue(fieldName = "picker.wornLine",
            comment = "Body line naming the model currently worn. Placeholder: model. MiniMessage.")
    default String pickerWornLine() {
        return "<gray>Wearing: <white><model>";
    }

    /** Body line shown when the player is wearing nothing. */
    @ConfigValue(fieldName = "picker.nothingWornLine",
            comment = "Body line shown when the player wears no model. MiniMessage.")
    default String pickerNothingWornLine() {
        return "<dark_gray>Wearing nothing.";
    }

    /**
     * Line shown when the submitted model id is not one this server offers.
     *
     * <p>Not a defensive courtesy. The chosen id arrives in a packet the client composed, so it is
     * whatever the client chose to send; the only thing that makes it a model id is this check.
     * Placeholder: {@code <model>}.
     *
     * @return the unknown model template
     */
    @ConfigValue(fieldName = "picker.unknownModelMessage",
            comment = "Line shown when the submitted model id is not one this server offers. The id arrives in a packet the client composed, so it is checked rather than trusted. Placeholder: model.")
    default String pickerUnknownModelMessage() {
        return "<red>That model is not available: <white><model>";
    }

    /** Line confirming a model was put on. Placeholder: {@code <model>}. */
    @ConfigValue(fieldName = "picker.wornMessage",
            comment = "Line confirming a model was put on. Placeholder: model. MiniMessage.")
    default String pickerWornMessage() {
        return "<green>Now wearing <white><model></white>.";
    }

    /** Line confirming the model was taken off. */
    @ConfigValue(fieldName = "picker.removedMessage",
            comment = "Line confirming the model was taken off. MiniMessage.")
    default String pickerRemovedMessage() {
        return "<yellow>Model removed.";
    }

    /** Line confirming a reload. Placeholder: {@code <count>}. */
    @ConfigValue(fieldName = "picker.reloadedMessage",
            comment = "Line confirming a reload of the installed models. Placeholder: count. MiniMessage.")
    default String pickerReloadedMessage() {
        return "<aqua>Reloaded: <white><count></white> model(s) available.";
    }

    /**
     * Material of the item that stands in for a preview.
     *
     * <p>Matched the way Bukkit matches a material name, so {@code item_frame} and {@code ITEM_FRAME}
     * are the same thing. The validator refuses a name no material has and a material that is not an
     * item, because an item stack of either cannot be put in a dialog body.
     *
     * @return the thumbnail material name
     */
    @ConfigValue(fieldName = "picker.thumbnailMaterial",
            comment = "Material of the item that stands in for a 3D preview. Any item material; matched case-insensitively.")
    default String pickerThumbnailMaterial() {
        return "ITEM_FRAME";
    }

    /**
     * Item model the thumbnail item is drawn with. Placeholder: {@code <model>}.
     *
     * <p>This is where the <em>honest</em> half of the preview lives. There is no server-side 3D
     * viewport — nothing in the protocol can put a posed model inside a dialog — so a preview is a
     * pre-rendered image supplied by a resource pack and selected per model id through the item model
     * component. Leave this blank and the thumbnail is the plain material, which is a working picker
     * with a less useful picture in it.
     *
     * <p>Written as {@code namespace:path}; the placeholder is substituted before the key is parsed, so
     * a model id that is not a legal key path simply has no pack entry and the client falls back to the
     * material's own model.
     *
     * @return the item model key template, or blank to leave the item unchanged
     */
    @ConfigValue(fieldName = "picker.thumbnailItemModel",
            comment = "Item model key the thumbnail is drawn with, as namespace:path, with the placeholder model substituted. A resource pack supplies the pictures; there is no server-side 3D viewport. Blank leaves the plain item.")
    default String pickerThumbnailItemModel() {
        return "integrated_models:thumbnail/<model>";
    }

    /** Width of the thumbnail, in the dialog layout's own units. */
    @ConfigValue(fieldName = "picker.thumbnailWidth",
            comment = "Width of the thumbnail, 1 to 256.")
    default int pickerThumbnailWidth() {
        return 64;
    }

    /** Height of the thumbnail, in the dialog layout's own units. */
    @ConfigValue(fieldName = "picker.thumbnailHeight",
            comment = "Height of the thumbnail, 1 to 256.")
    default int pickerThumbnailHeight() {
        return 64;
    }

    /** Caption under the thumbnail. Placeholder: {@code <model>}. Blank for no caption. */
    @ConfigValue(fieldName = "picker.thumbnailDescription",
            comment = "Caption beside the thumbnail. Placeholder: model. Leave blank for none. MiniMessage.")
    default String pickerThumbnailDescription() {
        return "<white><model>";
    }

    // =================================================================================================
    // Appearance
    // =================================================================================================

    /** Title of the appearance screen. */
    @ConfigValue(fieldName = "appearance.title",
            comment = "Title of the appearance screen. MiniMessage.")
    default String appearanceTitle() {
        return "<light_purple><bold>Appearance";
    }

    /** First body line of the appearance screen. Blank to omit the line. */
    @ConfigValue(fieldName = "appearance.heading",
            comment = "First body line of the appearance screen. Leave blank to omit it. MiniMessage.")
    default String appearanceHeading() {
        return "<gray>Adjust, then press Apply.";
    }

    /** Label of the glow toggle. */
    @ConfigValue(fieldName = "appearance.glowLabel",
            comment = "Label of the glow toggle. MiniMessage.")
    default String appearanceGlowLabel() {
        return "<white>Glow";
    }

    /** Label of the red channel slider. */
    @ConfigValue(fieldName = "appearance.redLabel",
            comment = "Label of the red channel slider. MiniMessage.")
    default String appearanceRedLabel() {
        return "<red>Red";
    }

    /** Label of the green channel slider. */
    @ConfigValue(fieldName = "appearance.greenLabel",
            comment = "Label of the green channel slider. MiniMessage.")
    default String appearanceGreenLabel() {
        return "<green>Green";
    }

    /** Label of the blue channel slider. */
    @ConfigValue(fieldName = "appearance.blueLabel",
            comment = "Label of the blue channel slider. MiniMessage.")
    default String appearanceBlueLabel() {
        return "<blue>Blue";
    }

    /**
     * Client translation key a colour slider renders its label and value through.
     *
     * <p>A translation key, <strong>not</strong> a format string — see the class documentation. The
     * vanilla {@code options.generic_value} takes the label and the value in that order and renders
     * {@code Red: 128}.
     *
     * @return the translation key
     */
    @ConfigValue(fieldName = "appearance.channelLabelFormat",
            comment = "Client translation key a colour slider renders its label and value through. A translation key, not a printf pattern: options.generic_value renders as Label: 128.")
    default String appearanceChannelLabelFormat() {
        return "options.generic_value";
    }

    /** Label of the scale slider. */
    @ConfigValue(fieldName = "appearance.scaleLabel",
            comment = "Label of the scale slider. MiniMessage.")
    default String appearanceScaleLabel() {
        return "<white>Scale";
    }

    /**
     * Client translation key the scale slider renders its label and value through.
     *
     * @return the translation key
     */
    @ConfigValue(fieldName = "appearance.scaleLabelFormat",
            comment = "Client translation key the scale slider renders its label and value through. A translation key, not a printf pattern.")
    default String appearanceScaleLabelFormat() {
        return "options.generic_value";
    }

    /** Label of the physics toggle. */
    @ConfigValue(fieldName = "appearance.physicsLabel",
            comment = "Label of the physics toggle. MiniMessage.")
    default String appearancePhysicsLabel() {
        return "<white>Physics";
    }

    /** Label of the first-person-arms toggle. */
    @ConfigValue(fieldName = "appearance.firstPersonArmsLabel",
            comment = "Label of the first person arms toggle. MiniMessage.")
    default String appearanceFirstPersonArmsLabel() {
        return "<white>First person arms";
    }

    /** Label of the button that commits the sliders. */
    @ConfigValue(fieldName = "appearance.applyLabel",
            comment = "Label of the Apply button. MiniMessage.")
    default String appearanceApplyLabel() {
        return "<green>Apply";
    }

    /** Label of the button that restores every default. */
    @ConfigValue(fieldName = "appearance.resetLabel",
            comment = "Label of the Reset button. MiniMessage.")
    default String appearanceResetLabel() {
        return "<yellow>Reset";
    }

    /**
     * The glyph the colour swatch is drawn with.
     *
     * <p>A dialog cannot draw a rectangle, so the swatch is text in the chosen colour. A run of block
     * characters reads as a filled bar in the default font; any string works, including an emoji font
     * glyph supplied by a resource pack.
     *
     * @return the swatch glyph
     */
    @ConfigValue(fieldName = "appearance.swatchGlyph",
            comment = "Text the colour swatch is drawn with. A dialog cannot draw a rectangle, so the swatch is this string tinted with the colour.")
    default String appearanceSwatchGlyph() {
        return "████";
    }

    /**
     * The swatch line. Placeholders: {@code <swatch>}, {@code <hex>}, {@code <red>}, {@code <green>},
     * {@code <blue>}.
     *
     * @return the swatch line template
     */
    @ConfigValue(fieldName = "appearance.swatchLine",
            comment = "Line carrying the colour swatch. Placeholders: swatch, hex, red, green, blue. MiniMessage.")
    default String appearanceSwatchLine() {
        return "<swatch> <gray><hex>";
    }

    /**
     * The line admitting what the swatch is showing. Blank to omit it.
     *
     * <p>Worth keeping. The swatch shows the colour that is <em>applied</em>, not the one the sliders are
     * currently at, because the server is never told that a slider moved. A player who drags red to zero
     * and sees the swatch stay orange has found a platform limit, and a sentence is cheaper than a
     * support conversation.
     *
     * @return the swatch note, or blank to omit it
     */
    @ConfigValue(fieldName = "appearance.swatchNote",
            comment = "Line explaining that the swatch shows the applied colour rather than the slider positions, because the server is not told when a slider moves. Leave blank to omit it.")
    default String appearanceSwatchNote() {
        return "<dark_gray><italic>The swatch updates when you press Apply.";
    }

    /** Line confirming the appearance was applied. */
    @ConfigValue(fieldName = "appearance.appliedMessage",
            comment = "Line confirming the appearance was applied. MiniMessage.")
    default String appearanceAppliedMessage() {
        return "<green>Appearance applied.";
    }

    /** Line confirming the appearance was reset. */
    @ConfigValue(fieldName = "appearance.resetMessage",
            comment = "Line confirming the appearance was reset to its defaults. MiniMessage.")
    default String appearanceResetMessage() {
        return "<yellow>Appearance reset.";
    }

    /** Line shown when an appearance screen is asked for with no model worn. */
    @ConfigValue(fieldName = "appearance.noModelMessage",
            comment = "Line shown when the appearance screen is opened with no model worn. MiniMessage.")
    default String appearanceNoModelMessage() {
        return "<red>Wear a model first.";
    }

    /**
     * Smallest value a colour slider offers.
     *
     * <p>Bounded to the byte a colour channel is on the wire. Narrowing the range is a legitimate thing
     * to do — a server that wants no near-black tints raises the floor — and widening it past 255 is not,
     * because the extra values cannot be transmitted.
     *
     * @return the channel floor
     */
    @ConfigValue(fieldName = "appearance.minChannel",
            comment = "Smallest value a colour slider offers, 0 to 255. A channel is one byte on the wire, so the range cannot be widened past that; narrowing it is how a server forbids near-black tints.")
    default int appearanceMinChannel() {
        return 0;
    }

    /** Largest value a colour slider offers. */
    @ConfigValue(fieldName = "appearance.maxChannel",
            comment = "Largest value a colour slider offers, 0 to 255.")
    default int appearanceMaxChannel() {
        return 255;
    }

    /**
     * Granularity of a colour slider.
     *
     * <p>The client snaps the handle to multiples of this, so one is what makes the slider land on whole
     * channel values. A larger step is a coarser palette rather than a faster slider.
     *
     * @return the channel step
     */
    @ConfigValue(fieldName = "appearance.channelStep",
            comment = "Granularity a colour slider snaps to. One lands on whole channel values; larger is a coarser palette.")
    default float appearanceChannelStep() {
        return 1F;
    }

    /** Smallest scale the slider offers. */
    @ConfigValue(fieldName = "appearance.minScale",
            comment = "Smallest scale the slider offers. Must stay above zero: a zero-scaled model is invisible rather than small.")
    default float appearanceMinScale() {
        return 0.5F;
    }

    /** Largest scale the slider offers. */
    @ConfigValue(fieldName = "appearance.maxScale",
            comment = "Largest scale the slider offers.")
    default float appearanceMaxScale() {
        return 4F;
    }

    /** Granularity of the scale slider. */
    @ConfigValue(fieldName = "appearance.scaleStep",
            comment = "Granularity the scale slider snaps to.")
    default float appearanceScaleStep() {
        return 0.1F;
    }

    /** Whether a model starts out glowing. */
    @ConfigValue(fieldName = "appearance.defaultGlow",
            comment = "Whether a model starts out glowing, and what Reset goes back to.")
    default boolean appearanceDefaultGlow() {
        return false;
    }

    /** Red channel a model starts at, and what Reset goes back to. */
    @ConfigValue(fieldName = "appearance.defaultRed",
            comment = "Red channel a model starts at, and what Reset goes back to. Must sit inside the slider range.")
    default int appearanceDefaultRed() {
        return 255;
    }

    /** Green channel a model starts at, and what Reset goes back to. */
    @ConfigValue(fieldName = "appearance.defaultGreen",
            comment = "Green channel a model starts at, and what Reset goes back to. Must sit inside the slider range.")
    default int appearanceDefaultGreen() {
        return 255;
    }

    /** Blue channel a model starts at, and what Reset goes back to. */
    @ConfigValue(fieldName = "appearance.defaultBlue",
            comment = "Blue channel a model starts at, and what Reset goes back to. Must sit inside the slider range.")
    default int appearanceDefaultBlue() {
        return 255;
    }

    /** Scale a model starts at, and what Reset goes back to. */
    @ConfigValue(fieldName = "appearance.defaultScale",
            comment = "Scale a model starts at, and what Reset goes back to. Must sit inside the slider range.")
    default float appearanceDefaultScale() {
        return 1F;
    }

    /** Whether secondary motion starts enabled. */
    @ConfigValue(fieldName = "appearance.defaultPhysics",
            comment = "Whether secondary motion starts enabled, and what Reset goes back to.")
    default boolean appearanceDefaultPhysics() {
        return true;
    }

    /** Whether first-person arms start enabled. */
    @ConfigValue(fieldName = "appearance.defaultFirstPersonArms",
            comment = "Whether first person arms start enabled, and what Reset goes back to.")
    default boolean appearanceDefaultFirstPersonArms() {
        return false;
    }

    // =================================================================================================
    // Animations
    // =================================================================================================

    /** Title of the animation chooser. */
    @ConfigValue(fieldName = "animation.title",
            comment = "Title of the animation chooser. MiniMessage.")
    default String animationTitle() {
        return "<gold><bold>Animations";
    }

    /** First body line of the animation chooser. Blank to omit the line. */
    @ConfigValue(fieldName = "animation.heading",
            comment = "First body line of the animation chooser. Leave blank to omit it. MiniMessage.")
    default String animationHeading() {
        return "<gray>Pick an animation to play it.";
    }

    /**
     * How many animations one page of the chooser offers.
     *
     * <p>Unlike the picker's chooser these are buttons, one click each, so the page size is bounded by
     * what fits on a screen rather than by how far a player has to cycle. The client lays buttons out in
     * a grid whose column count the server cannot choose, so a large page becomes a tall grid.
     *
     * @return the animation page size
     */
    @ConfigValue(fieldName = "animation.pageSize",
            comment = "How many animations one page offers. These are buttons, one click each, laid out in a grid whose column count the client fixes, so a large page becomes a tall grid.")
    default int animationPageSize() {
        return 8;
    }

    /** Body line shown when the worn model declares no animation. */
    @ConfigValue(fieldName = "animation.emptyMessage",
            comment = "Body line shown when the worn model declares no animation. MiniMessage.")
    default String animationEmptyMessage() {
        return "<red>This model has no animations.";
    }

    /** Label of one animation button. Placeholder: {@code <animation>}. */
    @ConfigValue(fieldName = "animation.entryLabel",
            comment = "Label of one animation button. Placeholder: animation. MiniMessage.")
    default String animationEntryLabel() {
        return "<white><animation>";
    }

    /** Tooltip of one animation button. Placeholder: {@code <animation>}. Blank for no tooltip. */
    @ConfigValue(fieldName = "animation.entryTooltip",
            comment = "Tooltip of one animation button. Placeholder: animation. Leave blank for none. MiniMessage.")
    default String animationEntryTooltip() {
        return "<gray>Play <white><animation></white>.";
    }

    /** Label of the button that stops whatever is playing. */
    @ConfigValue(fieldName = "animation.stopLabel",
            comment = "Label of the button that stops the current animation. MiniMessage.")
    default String animationStopLabel() {
        return "<red>Stop";
    }

    /** Tooltip of the stop button. Blank for no tooltip. */
    @ConfigValue(fieldName = "animation.stopTooltip",
            comment = "Tooltip of the stop button. Leave blank for none. MiniMessage.")
    default String animationStopTooltip() {
        return "<gray>Stop the animation that is playing.";
    }

    /** Line confirming an animation was started. Placeholder: {@code <animation>}. */
    @ConfigValue(fieldName = "animation.playedMessage",
            comment = "Line confirming an animation was started. Placeholder: animation. MiniMessage.")
    default String animationPlayedMessage() {
        return "<gold>Playing <white><animation></white>.";
    }

    /** Line confirming the animation was stopped. */
    @ConfigValue(fieldName = "animation.stoppedMessage",
            comment = "Line confirming the animation was stopped. MiniMessage.")
    default String animationStoppedMessage() {
        return "<yellow>Animation stopped.";
    }

    /**
     * Line shown when the submitted animation is not one the worn model declares.
     *
     * <p>Checked for the same reason the model id is: the click carries whatever the client sent.
     * Placeholder: {@code <animation>}.
     *
     * @return the unknown animation template
     */
    @ConfigValue(fieldName = "animation.unknownAnimationMessage",
            comment = "Line shown when the submitted animation is not one the worn model declares. Placeholder: animation. MiniMessage.")
    default String animationUnknownAnimationMessage() {
        return "<red>That animation is not available: <white><animation>";
    }

    /** Line shown when the animation chooser is opened with no model worn. */
    @ConfigValue(fieldName = "animation.noModelMessage",
            comment = "Line shown when the animation chooser is opened with no model worn. MiniMessage.")
    default String animationNoModelMessage() {
        return "<red>Wear a model first.";
    }
}
