package pandastic.studio.models.internal.menu;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
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

/**
 * The settings screen: a glow toggle, three colour sliders, a scale slider, two more toggles, Reset and
 * Close.
 *
 * <h2>The primitive, and why</h2>
 * A <strong>form</strong>, and this is the screen where that choice is worth defending, because an
 * inventory GUI is a genuine contender for continuous adjustment: it survives rapid repeated clicking
 * far better than a form does.
 *
 * <p>It loses anyway, for three reasons that outrank it.
 * <ol>
 *   <li><strong>A slider is one gesture.</strong> Dragging a handle from 255 to 32 is one interaction;
 *       in a chest GUI it is seven clicks at a step of 32, or 223 at a step of one, and the step is then
 *       a configured compromise between precision and patience. The dialog has no such trade: the range
 *       and the granularity are both exact and both configured.</li>
 *   <li><strong>The value is on the handle.</strong> A number-range input renders its own label and
 *       value through a client translation, so the player reads {@code Red: 128} while they drag. That
 *       is what the original's hex readout was <em>for</em> — knowing the number — and it is the part of
 *       the live readout that survives.</li>
 *   <li><strong>The dialog API is the constraint.</strong> An inventory GUI is not the dialog layer, and
 *       building one would mean a second input convention in the same plugin reached from the same
 *       buttons.</li>
 * </ol>
 *
 * <h2>What is lost against the original, and why</h2>
 * <ul>
 *   <li><strong>The live swatch.</strong> A dialog reports its inputs once, on a click; nothing tells
 *       the server that a slider moved. So the swatch shows the <em>applied</em> colour and Apply is
 *       what moves it. {@code appearance.swatchNote} exists to say so on the screen, because a swatch
 *       that silently lags looks like a bug.</li>
 *   <li><strong>Watching it resize while dragging.</strong> Same cause. The scale takes effect on
 *       Apply.</li>
 *   <li><strong>Hover highlighting.</strong> There is no mouse-position feedback of any kind. A tooltip
 *       is the whole of what a control can say about itself.</li>
 * </ul>
 *
 * <h2>Why the submitted values are clamped rather than checked</h2>
 * Because the sliders' bounds are a hint to the client's interface and not a constraint on the packet.
 * {@link ModelAppearance#clamped} is the gate, and it is applied to every submission — including the
 * ones that arrive while the player wears nothing, where the wardrobe is free to do nothing with them.
 */
@Dialog
final class AppearanceMenu extends MenuScreen {

    private static final String GLOW_KEY = "glow";
    private static final String RED_KEY = "red";
    private static final String GREEN_KEY = "green";
    private static final String BLUE_KEY = "blue";
    private static final String SCALE_KEY = "scale";
    private static final String PHYSICS_KEY = "physics";
    private static final String ARMS_KEY = "arms";

    /**
     * The state the controls open at, read once on the viewer's own region thread.
     *
     * <p>Also what an unanswered input falls back to. An input the client omitted is an input the
     * player did not change, so the honest default is what they were shown rather than the
     * configuration's default — which would quietly reset a channel because a packet was short.
     */
    private final @NotNull ModelAppearance current;

    AppearanceMenu(@NotNull MenuService service, @NotNull Player viewer, @NotNull MenuSettings settings) {
        super(service, viewer, settings);
        this.current = service.wardrobe().readAppearance(viewer, settings.appearance().defaults());
    }

    @Override
    protected @NotNull Component title() {
        return MenuText.of(this.settings.appearance().title());
    }

    // ------------------------------------------------------------------ body

    @DialogBody(id = "heading", order = 0)
    @Nullable PlainMessageDialogBody heading() {
        return optionalLine(this.settings.appearance().heading());
    }

    /**
     * The colour, as text, because a dialog cannot draw a rectangle.
     *
     * @return the swatch line, or {@code null} when the operator emptied it
     */
    @DialogBody(id = "swatch", order = 10)
    @Nullable PlainMessageDialogBody swatchLine() {
        final MenuSettings.Appearance appearance = this.settings.appearance();
        return optionalLine(appearance.swatchLine(),
                MenuText.swatch(appearance.swatchGlyph(), this.current),
                MenuText.placeholder("hex", this.current.hex()),
                MenuText.placeholder("red", this.current.red()),
                MenuText.placeholder("green", this.current.green()),
                MenuText.placeholder("blue", this.current.blue()));
    }

    /** The sentence admitting that the swatch is one Apply behind the sliders. */
    @DialogBody(id = "swatchNote", order = 20)
    @Nullable PlainMessageDialogBody swatchNote() {
        return optionalLine(this.settings.appearance().swatchNote());
    }

    // ------------------------------------------------------------------ inputs

    @DialogInput(id = GLOW_KEY, order = 0)
    @NotNull BooleanDialogInput glowToggle() {
        return this.service.surface().toggle(GLOW_KEY,
                MenuText.of(this.settings.appearance().glowLabel()), this.current.glow());
    }

    @DialogInput(id = RED_KEY, order = 10)
    @NotNull NumberRangeDialogInput redSlider() {
        return channelSlider(RED_KEY, this.settings.appearance().redLabel(), this.current.red());
    }

    @DialogInput(id = GREEN_KEY, order = 20)
    @NotNull NumberRangeDialogInput greenSlider() {
        return channelSlider(GREEN_KEY, this.settings.appearance().greenLabel(), this.current.green());
    }

    @DialogInput(id = BLUE_KEY, order = 30)
    @NotNull NumberRangeDialogInput blueSlider() {
        return channelSlider(BLUE_KEY, this.settings.appearance().blueLabel(), this.current.blue());
    }

    @DialogInput(id = SCALE_KEY, order = 40)
    @NotNull NumberRangeDialogInput scaleSlider() {
        final MenuSettings.Appearance appearance = this.settings.appearance();
        return this.service.surface().slider(
                SCALE_KEY,
                MenuText.of(appearance.scaleLabel()),
                appearance.scaleLabelFormat(),
                appearance.minScale(),
                appearance.maxScale(),
                appearance.clampScale(this.current.scale()),
                appearance.scaleStep(),
                this.settings.layout().inputWidth());
    }

    @DialogInput(id = PHYSICS_KEY, order = 50)
    @NotNull BooleanDialogInput physicsToggle() {
        return this.service.surface().toggle(PHYSICS_KEY,
                MenuText.of(this.settings.appearance().physicsLabel()), this.current.physics());
    }

    @DialogInput(id = ARMS_KEY, order = 60)
    @NotNull BooleanDialogInput armsToggle() {
        return this.service.surface().toggle(ARMS_KEY,
                MenuText.of(this.settings.appearance().firstPersonArmsLabel()),
                this.current.firstPersonArms());
    }

    /**
     * One colour slider.
     *
     * <p>The initial value is clamped as well as the submitted one, because a model can be carrying a
     * channel the configuration no longer offers — an operator who narrowed the range after a player had
     * already picked a colour. An out-of-range initial value is refused by the client rather than
     * displayed.
     *
     * @param key           the input key
     * @param labelTemplate the MiniMessage label source
     * @param initial       the channel to open at
     * @return the slider
     */
    private @NotNull NumberRangeDialogInput channelSlider(
            @NotNull String key,
            @NotNull String labelTemplate,
            int initial
    ) {
        final MenuSettings.Appearance appearance = this.settings.appearance();
        return this.service.surface().slider(
                key,
                MenuText.of(labelTemplate),
                appearance.channelLabelFormat(),
                appearance.minChannel(),
                appearance.maxChannel(),
                appearance.clampChannel(initial),
                appearance.channelStep(),
                this.settings.layout().inputWidth());
    }

    // ------------------------------------------------------------------ buttons

    @DialogButton(id = "apply", order = 10)
    @NotNull ActionButton.Builder applyButton() {
        return button(this.settings.appearance().applyLabel(), "");
    }

    @DialogButton(id = "reset", order = 20)
    @NotNull ActionButton.Builder resetButton() {
        return button(this.settings.appearance().resetLabel(), "");
    }

    @DialogButton(id = "back", order = 30)
    @NotNull ActionButton.Builder backButton() {
        return button(this.settings.layout().backLabel(), "");
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Commits the controls and reopens the screen, so the swatch catches up.
     *
     * @param context the click
     */
    @DialogButtonHandler(buttonId = "apply")
    void onApply(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.wardrobe().applyAppearance(this.viewer, submitted(context));
        notice(this.settings.appearance().appliedMessage());
        this.service.openAppearance(this.viewer);
    }

    @DialogButtonHandler(buttonId = "reset")
    void onReset(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.wardrobe().applyAppearance(this.viewer, this.settings.appearance().defaults());
        notice(this.settings.appearance().resetMessage());
        this.service.openAppearance(this.viewer);
    }

    @DialogButtonHandler(buttonId = "back")
    void onBack(@NotNull DialogContext context) {
        if (!isViewer(context)) {
            return;
        }
        this.service.openPicker(this.viewer);
    }

    /**
     * The appearance the click describes, clamped into what the sliders offer.
     *
     * <p>Every input falls back to what the screen was opened with rather than to a configured default:
     * an absent key means the client did not send that control, which is not the same as the player
     * asking for its default.
     *
     * @param context the click
     * @return the submitted appearance
     */
    private @NotNull ModelAppearance submitted(@NotNull DialogContext context) {
        final DialogResponseView response = context.dialogResponseView();
        return ModelAppearance.clamped(
                this.settings.appearance(),
                flag(response.getBoolean(GLOW_KEY), this.current.glow()),
                number(response.getFloat(RED_KEY), this.current.red()),
                number(response.getFloat(GREEN_KEY), this.current.green()),
                number(response.getFloat(BLUE_KEY), this.current.blue()),
                number(response.getFloat(SCALE_KEY), this.current.scale()),
                flag(response.getBoolean(PHYSICS_KEY), this.current.physics()),
                flag(response.getBoolean(ARMS_KEY), this.current.firstPersonArms()));
    }

    private static boolean flag(@Nullable Boolean submitted, boolean fallback) {
        return submitted == null ? fallback : submitted;
    }

    private static float number(@Nullable Float submitted, float fallback) {
        return submitted == null ? fallback : submitted;
    }
}
