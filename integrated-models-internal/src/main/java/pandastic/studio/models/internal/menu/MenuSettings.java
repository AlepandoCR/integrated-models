package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every word and every bound a menu uses, assembled and already validated.
 *
 * <h2>Why the text is still text</h2>
 * The labels arrive here as MiniMessage <em>source</em> rather than as rendered components, and that is
 * deliberate. Half of them carry a placeholder whose value only exists at the moment a screen is built —
 * the model being previewed, the page the player is on, the colour they just applied — so a component
 * parsed at reload time would have to be re-parsed anyway. Keeping the source means one parse per screen
 * build with the substitutions in hand, which is a few microseconds on a click rather than work done
 * eagerly on the wrong thread with the wrong data.
 *
 * <p>It also keeps this record free of Adventure, which is what lets {@code ConfigMapper} build it
 * without knowing how text is rendered.
 *
 * <h2>Why it throws</h2>
 * Same split as the rest of the project: a rig file is written by a human and is clamped with a warning,
 * while these values arrive from a validator that has already refused the whole reload. Anything
 * reaching this constructor out of range is a defect in the mapper, and swallowing it would hide which
 * of the two is wrong.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.config.MenuSettings} is the <em>declaration</em> of
 * {@code menu.yml} — the keys, the comments and the defaults. This one is the value the menu layer
 * reads. The project has the same deliberate shadow for render, simulation and ai.
 *
 * @param session   how long pending menu state survives
 * @param layout    widths and the chrome every screen shares
 * @param picker    the model picker's text and page size
 * @param appearance the appearance screen's text, ranges and defaults
 * @param animation the animation chooser's text and page size
 */
public record MenuSettings(
        @NotNull Session session,
        @NotNull Layout layout,
        @NotNull Picker picker,
        @NotNull Appearance appearance,
        @NotNull Animation animation
) {

    /**
     * Largest width a dialog element may declare.
     *
     * <p>A protocol fact: the client clamps a dialog element's width to this, so a larger number is not
     * a wider element, it is a value the client discards. Bounding it here means an operator hears about
     * it instead of wondering why nothing changed.
     */
    public static final int MAX_ELEMENT_WIDTH = 1024;

    /**
     * How long a player's unapplied menu state is kept, and how many players may hold some.
     *
     * <p>Both keys exist because of one missing platform feature: <strong>nothing tells the server a
     * dialog was closed</strong>. Not escape, not a click elsewhere, not logging out mid-screen. So a
     * session has no end event and can only be ended by a clock, and the store that holds them needs a
     * ceiling in case the clock has not been consulted recently.
     *
     * @param timeoutSeconds seconds of inactivity after which pending state is forgotten
     * @param maxSessions    how many players may hold pending state at once
     */
    public record Session(float timeoutSeconds, int maxSessions) {

        /** Nanoseconds in a second. An SI conversion, not a tunable. */
        private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

        public Session {
            if (!Float.isFinite(timeoutSeconds) || timeoutSeconds <= 0F) {
                throw new IllegalArgumentException(
                        "timeoutSeconds must be finite and positive, but was " + timeoutSeconds
                                + ": a session with no timeout can never be ended, because nothing"
                                + " reports that a dialog was closed");
            }
            if (maxSessions < 1) {
                throw new IllegalArgumentException("maxSessions must be at least 1, but was " + maxSessions);
            }
        }

        /**
         * The timeout in nanoseconds, which is the unit a monotonic clock compares in.
         *
         * <p>The widening to {@code double} before the multiply is not decoration: a {@code float}
         * second count times a billion lands in a range where consecutive floats are hundreds of
         * nanoseconds apart.
         *
         * @return the timeout, in nanoseconds
         */
        @Contract(pure = true)
        public long timeoutNanos() {
            return (long) ((double) this.timeoutSeconds * NANOS_PER_SECOND);
        }
    }

    /**
     * The widths and the chrome every screen shares.
     *
     * <p>Shared rather than declared per screen because they are the same decision three times. A server
     * that wants a narrower dialog wants it everywhere, and three keys that have to agree are three keys
     * that will eventually not.
     *
     * @param bodyWidth          width of a body line
     * @param inputWidth         width of a slider, toggle or chooser
     * @param buttonWidth        width of an action button
     * @param pageIndicator      template naming the open page; placeholders {@code page}, {@code pages}
     * @param nextPageLabel      label of the forward button
     * @param previousPageLabel  label of the back button
     * @param backLabel          label of the button returning to the picker
     * @param closeLabel         label of the button dismissing a menu
     */
    public record Layout(
            int bodyWidth,
            int inputWidth,
            int buttonWidth,
            @NotNull String pageIndicator,
            @NotNull String nextPageLabel,
            @NotNull String previousPageLabel,
            @NotNull String backLabel,
            @NotNull String closeLabel
    ) {

        public Layout {
            requireWidth(bodyWidth, "bodyWidth");
            requireWidth(inputWidth, "inputWidth");
            requireWidth(buttonWidth, "buttonWidth");
            requireText(pageIndicator, "pageIndicator");
            requireText(nextPageLabel, "nextPageLabel");
            requireText(previousPageLabel, "previousPageLabel");
            requireText(backLabel, "backLabel");
            requireText(closeLabel, "closeLabel");
        }
    }

    /**
     * The model picker's text, page size and thumbnail.
     *
     * <p>{@code thumbnailItemModel} is the only key here that is about something the server cannot do.
     * There is no server-side 3D viewport — no packet can place a posed model inside a dialog — so a
     * preview is a flat picture that a resource pack supplies and this key selects per model id. Blank
     * means the plain item, which is a working picker with a less useful picture in it.
     *
     * @param title                title of the screen
     * @param heading              opening body line; blank omits it
     * @param pageSize             model ids offered per page
     * @param chooserLabel         label beside the chooser
     * @param emptyMessage         body line shown when no model exists
     * @param wearLabel            label of the apply button
     * @param wearTooltip          tooltip of the apply button; blank for none
     * @param removeLabel          label of the take-off button
     * @param removeTooltip        tooltip of the take-off button; blank for none
     * @param refreshLabel         label of the button that redraws the screen around the selection
     * @param refreshTooltip       tooltip of that button; blank for none
     * @param reloadLabel          label of the button that re-reads the catalogue
     * @param reloadTooltip        tooltip of that button; blank for none
     * @param appearanceLabel      label of the button opening the appearance screen
     * @param animationsLabel      label of the button opening the animation chooser
     * @param wornLine             body line naming the worn model; placeholder {@code model}
     * @param nothingWornLine      body line shown when nothing is worn
     * @param unknownModelMessage  line shown when a submitted id is not offered; placeholder {@code model}
     * @param wornMessage          line confirming a model was put on; placeholder {@code model}
     * @param removedMessage       line confirming the model was taken off
     * @param reloadedMessage      line confirming a reload; placeholder {@code count}
     * @param thumbnailMaterial    material name of the stand-in item
     * @param thumbnailItemModel   item model key template; blank leaves the plain item
     * @param thumbnailWidth       width of the thumbnail
     * @param thumbnailHeight      height of the thumbnail
     * @param thumbnailDescription caption beside the thumbnail; blank for none
     */
    public record Picker(
            @NotNull String title,
            @NotNull String heading,
            int pageSize,
            @NotNull String chooserLabel,
            @NotNull String emptyMessage,
            @NotNull String wearLabel,
            @NotNull String wearTooltip,
            @NotNull String removeLabel,
            @NotNull String removeTooltip,
            @NotNull String refreshLabel,
            @NotNull String refreshTooltip,
            @NotNull String reloadLabel,
            @NotNull String reloadTooltip,
            @NotNull String appearanceLabel,
            @NotNull String animationsLabel,
            @NotNull String wornLine,
            @NotNull String nothingWornLine,
            @NotNull String unknownModelMessage,
            @NotNull String wornMessage,
            @NotNull String removedMessage,
            @NotNull String reloadedMessage,
            @NotNull String thumbnailMaterial,
            @NotNull String thumbnailItemModel,
            int thumbnailWidth,
            int thumbnailHeight,
            @NotNull String thumbnailDescription
    ) {

        public Picker {
            requireText(title, "title");
            requirePresent(heading, "heading");
            if (pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be at least 1, but was " + pageSize);
            }
            requireText(chooserLabel, "chooserLabel");
            requireText(emptyMessage, "emptyMessage");
            requireText(wearLabel, "wearLabel");
            requirePresent(wearTooltip, "wearTooltip");
            requireText(removeLabel, "removeLabel");
            requirePresent(removeTooltip, "removeTooltip");
            requireText(refreshLabel, "refreshLabel");
            requirePresent(refreshTooltip, "refreshTooltip");
            requireText(reloadLabel, "reloadLabel");
            requirePresent(reloadTooltip, "reloadTooltip");
            requireText(appearanceLabel, "appearanceLabel");
            requireText(animationsLabel, "animationsLabel");
            requireText(wornLine, "wornLine");
            requireText(nothingWornLine, "nothingWornLine");
            requireText(unknownModelMessage, "unknownModelMessage");
            requireText(wornMessage, "wornMessage");
            requireText(removedMessage, "removedMessage");
            requireText(reloadedMessage, "reloadedMessage");
            requireText(thumbnailMaterial, "thumbnailMaterial");
            requirePresent(thumbnailItemModel, "thumbnailItemModel");
            requireWidth(thumbnailWidth, "thumbnailWidth");
            requireWidth(thumbnailHeight, "thumbnailHeight");
            requirePresent(thumbnailDescription, "thumbnailDescription");
        }

        /** Whether a resource pack is expected to supply a picture per model id. */
        @Contract(pure = true)
        public boolean hasItemModel() {
            return !this.thumbnailItemModel.isBlank();
        }
    }

    /**
     * The appearance screen's text, slider ranges and defaults.
     *
     * <p>The ranges are what the client is <em>offered</em>; they are not what makes a value legal.
     * A click carries whatever the client composed, so {@link #clampChannel} and {@link #clampScale} are
     * applied on the way in and are the only reason a submitted appearance is inside them.
     *
     * @param title                  title of the screen
     * @param heading                opening body line; blank omits it
     * @param glowLabel              label of the glow toggle
     * @param redLabel               label of the red slider
     * @param greenLabel             label of the green slider
     * @param blueLabel              label of the blue slider
     * @param channelLabelFormat     client translation key a colour slider renders through
     * @param scaleLabel             label of the scale slider
     * @param scaleLabelFormat       client translation key the scale slider renders through
     * @param physicsLabel           label of the physics toggle
     * @param firstPersonArmsLabel   label of the first-person-arms toggle
     * @param applyLabel             label of the commit button
     * @param resetLabel             label of the restore-defaults button
     * @param swatchGlyph            text the swatch is drawn with
     * @param swatchLine             swatch line template
     * @param swatchNote             line admitting the swatch lags the sliders; blank omits it
     * @param appliedMessage         line confirming a commit
     * @param resetMessage           line confirming a restore
     * @param noModelMessage         line shown when the screen is opened with nothing worn
     * @param minChannel             smallest value a colour slider offers
     * @param maxChannel             largest value a colour slider offers
     * @param channelStep            granularity a colour slider snaps to
     * @param minScale               smallest scale the slider offers
     * @param maxScale               largest scale the slider offers
     * @param scaleStep              granularity the scale slider snaps to
     * @param defaults               what a model starts at and what Reset goes back to
     */
    public record Appearance(
            @NotNull String title,
            @NotNull String heading,
            @NotNull String glowLabel,
            @NotNull String redLabel,
            @NotNull String greenLabel,
            @NotNull String blueLabel,
            @NotNull String channelLabelFormat,
            @NotNull String scaleLabel,
            @NotNull String scaleLabelFormat,
            @NotNull String physicsLabel,
            @NotNull String firstPersonArmsLabel,
            @NotNull String applyLabel,
            @NotNull String resetLabel,
            @NotNull String swatchGlyph,
            @NotNull String swatchLine,
            @NotNull String swatchNote,
            @NotNull String appliedMessage,
            @NotNull String resetMessage,
            @NotNull String noModelMessage,
            int minChannel,
            int maxChannel,
            float channelStep,
            float minScale,
            float maxScale,
            float scaleStep,
            @NotNull ModelAppearance defaults
    ) {

        public Appearance {
            requireText(title, "title");
            requirePresent(heading, "heading");
            requireText(glowLabel, "glowLabel");
            requireText(redLabel, "redLabel");
            requireText(greenLabel, "greenLabel");
            requireText(blueLabel, "blueLabel");
            requireText(channelLabelFormat, "channelLabelFormat");
            requireText(scaleLabel, "scaleLabel");
            requireText(scaleLabelFormat, "scaleLabelFormat");
            requireText(physicsLabel, "physicsLabel");
            requireText(firstPersonArmsLabel, "firstPersonArmsLabel");
            requireText(applyLabel, "applyLabel");
            requireText(resetLabel, "resetLabel");
            requireText(swatchGlyph, "swatchGlyph");
            requireText(swatchLine, "swatchLine");
            requirePresent(swatchNote, "swatchNote");
            requireText(appliedMessage, "appliedMessage");
            requireText(resetMessage, "resetMessage");
            requireText(noModelMessage, "noModelMessage");

            if (minChannel < ModelAppearance.CHANNEL_FLOOR || maxChannel > ModelAppearance.CHANNEL_CEILING) {
                throw new IllegalArgumentException("The channel range [" + minChannel + ", " + maxChannel
                        + "] must stay inside [" + ModelAppearance.CHANNEL_FLOOR + ", "
                        + ModelAppearance.CHANNEL_CEILING + "], which is the byte a channel travels in");
            }
            if (minChannel >= maxChannel) {
                throw new IllegalArgumentException("minChannel (" + minChannel
                        + ") must stay below maxChannel (" + maxChannel + "), or the slider has no range");
            }
            requireStep(channelStep, maxChannel - (float) minChannel, "channelStep");

            if (!Float.isFinite(minScale) || minScale <= 0F) {
                throw new IllegalArgumentException("minScale must be finite and positive, but was "
                        + minScale + ": a zero-scaled model is invisible rather than small");
            }
            if (!Float.isFinite(maxScale) || minScale >= maxScale) {
                throw new IllegalArgumentException("minScale (" + minScale
                        + ") must stay below maxScale (" + maxScale + "), or the slider has no range");
            }
            requireStep(scaleStep, maxScale - minScale, "scaleStep");

            // The defaults are what Reset restores and what a model with no stored appearance starts
            // at, so they are the one appearance that is never submitted and therefore never clamped.
            // A default outside the sliders would be a state the screen can display and the player can
            // never return to.
            if (defaults.red() < minChannel || defaults.red() > maxChannel
                    || defaults.green() < minChannel || defaults.green() > maxChannel
                    || defaults.blue() < minChannel || defaults.blue() > maxChannel) {
                throw new IllegalArgumentException("The default colour [" + defaults.red() + ", "
                        + defaults.green() + ", " + defaults.blue() + "] must sit inside the channel"
                        + " range [" + minChannel + ", " + maxChannel + "] the sliders offer");
            }
            if (defaults.scale() < minScale || defaults.scale() > maxScale) {
                throw new IllegalArgumentException("The default scale " + defaults.scale()
                        + " must sit inside the range [" + minScale + ", " + maxScale + "]"
                        + " the slider offers");
            }
        }

        /**
         * {@code value} pulled inside the offered channel range.
         *
         * @param value the submitted channel
         * @return the clamped channel
         */
        @Contract(pure = true)
        public int clampChannel(int value) {
            return Math.min(this.maxChannel, Math.max(this.minChannel, value));
        }

        /**
         * {@code value} pulled inside the offered scale range.
         *
         * <p>A non-finite value is replaced by the default rather than clamped, because neither end of
         * the range is nearer to NaN than the other and a comparison against it is false whichever way
         * it is written.
         *
         * @param value the submitted scale
         * @return the clamped scale
         */
        @Contract(pure = true)
        public float clampScale(float value) {
            if (!Float.isFinite(value)) {
                return this.defaults.scale();
            }
            return Math.min(this.maxScale, Math.max(this.minScale, value));
        }
    }

    /**
     * The animation chooser's text and page size.
     *
     * @param title                    title of the screen
     * @param heading                  opening body line; blank omits it
     * @param pageSize                 animations offered per page
     * @param emptyMessage             body line shown when the model declares none
     * @param entryLabel               label of one animation button; placeholder {@code animation}
     * @param entryTooltip             tooltip of one animation button; blank for none
     * @param stopLabel                label of the stop button
     * @param stopTooltip              tooltip of the stop button; blank for none
     * @param playedMessage            line confirming a start; placeholder {@code animation}
     * @param stoppedMessage           line confirming a stop
     * @param unknownAnimationMessage  line shown when a submitted animation is not declared
     * @param noModelMessage           line shown when the screen is opened with nothing worn
     */
    public record Animation(
            @NotNull String title,
            @NotNull String heading,
            int pageSize,
            @NotNull String emptyMessage,
            @NotNull String entryLabel,
            @NotNull String entryTooltip,
            @NotNull String stopLabel,
            @NotNull String stopTooltip,
            @NotNull String playedMessage,
            @NotNull String stoppedMessage,
            @NotNull String unknownAnimationMessage,
            @NotNull String noModelMessage
    ) {

        public Animation {
            requireText(title, "title");
            requirePresent(heading, "heading");
            if (pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be at least 1, but was " + pageSize);
            }
            requireText(emptyMessage, "emptyMessage");
            requireText(entryLabel, "entryLabel");
            requirePresent(entryTooltip, "entryTooltip");
            requireText(stopLabel, "stopLabel");
            requirePresent(stopTooltip, "stopTooltip");
            requireText(playedMessage, "playedMessage");
            requireText(stoppedMessage, "stoppedMessage");
            requireText(unknownAnimationMessage, "unknownAnimationMessage");
            requireText(noModelMessage, "noModelMessage");
        }
    }

    // ------------------------------------------------------------------ shared guards

    /**
     * Requires a label that has to say something.
     *
     * <p>The parameter is {@code @Nullable} against the components' own {@code @NotNull} on purpose: a
     * generated configuration reads a text key through {@code getString}, which answers {@code null} for
     * a key that is present with an empty value. {@code title:} on its own line is an edit an operator
     * can make, and it has to produce a sentence rather than a {@link NullPointerException} from
     * {@link String#isBlank()}.
     */
    private static void requireText(@Nullable String value, @NotNull String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank: it is a label a player reads");
        }
    }

    /**
     * Requires a label to be present but allows it to be blank.
     *
     * <p>Blank is how an operator turns one line off, so the only thing left to refuse is {@code null} —
     * which, as above, is what a key present with an empty value reads back as.
     */
    private static void requirePresent(@Nullable String value, @NotNull String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null; use an empty string to omit it");
        }
    }

    private static void requireWidth(int value, @NotNull String name) {
        if (value < 1 || value > MAX_ELEMENT_WIDTH) {
            throw new IllegalArgumentException(name + " must be within [1, " + MAX_ELEMENT_WIDTH
                    + "], but was " + value + ": the client clamps a wider element rather than drawing it");
        }
    }

    private static void requireStep(float step, float range, @NotNull String name) {
        if (!Float.isFinite(step) || step <= 0F) {
            throw new IllegalArgumentException(
                    name + " must be finite and positive, but was " + step);
        }
        if (step > range) {
            throw new IllegalArgumentException(name + " (" + step + ") must not exceed the " + range
                    + " the slider spans, or the handle has one position");
        }
    }
}
