package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A surface that builds nothing and records everything.
 *
 * <h2>Why this is the whole reason the menus are testable</h2>
 * Every real dialog element is built through a provider the running server installs, so a screen built
 * against the real surface cannot exist in a unit test. Every element here is an ordinary object
 * implementing the same interface, so a test reads back the slider's bounds, the chooser's entries and
 * the buttons' labels directly — which is how every configured number in this layer is asserted against
 * the configuration that produced it.
 *
 * <p>The one thing it cannot fake is an {@link ItemStack}: constructing one needs the server's item
 * factory. {@link Item#item()} therefore throws rather than returning a plausible lie, and no test calls
 * it — what a test wants to know about a thumbnail is which material and which item model were asked
 * for, and both are recorded.
 */
final class FakeMenuSurface implements MenuSurface {

    private final @NotNull List<Message> messages = new ArrayList<>();
    private final @NotNull List<Item> thumbnails = new ArrayList<>();
    private final @NotNull List<Toggle> toggles = new ArrayList<>();
    private final @NotNull List<Slider> sliders = new ArrayList<>();
    private final @NotNull List<Chooser> choosers = new ArrayList<>();
    private final @NotNull List<Button> buttons = new ArrayList<>();

    /**
     * Whether {@link #thumbnail} should answer {@code null}, as the real surface does for a material
     * name the server does not know.
     */
    private boolean materialMissing;

    /** Makes every later thumbnail request fail the way an unknown material does. */
    void breakMaterial() {
        this.materialMissing = true;
    }

    @Override
    public @NotNull PlainMessageDialogBody message(@NotNull Component text, int width) {
        final Message built = new Message(text, width);
        this.messages.add(built);
        return built;
    }

    @Override
    public @Nullable ItemDialogBody thumbnail(
            @NotNull String materialName,
            @Nullable String itemModelKey,
            int width,
            int height,
            @Nullable Component caption
    ) {
        if (this.materialMissing) {
            return null;
        }
        final Item built = new Item(materialName, itemModelKey, width, height, caption);
        this.thumbnails.add(built);
        return built;
    }

    @Override
    public @NotNull BooleanDialogInput toggle(
            @NotNull String key,
            @NotNull Component label,
            boolean initial
    ) {
        final Toggle built = new Toggle(key, label, initial);
        this.toggles.add(built);
        return built;
    }

    @Override
    public @NotNull NumberRangeDialogInput slider(
            @NotNull String key,
            @NotNull Component label,
            @NotNull String labelFormat,
            float start,
            float end,
            float initial,
            float step,
            int width
    ) {
        final Slider built = new Slider(key, label, labelFormat, start, end, initial, step, width);
        this.sliders.add(built);
        return built;
    }

    @Override
    public @NotNull SingleOptionDialogInput chooser(
            @NotNull String key,
            @NotNull Component label,
            @NotNull List<Option> options,
            int width
    ) {
        final Chooser built = new Chooser(key, label, List.copyOf(options), width);
        this.choosers.add(built);
        return built;
    }

    @Override
    public @NotNull ActionButton.Builder button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width
    ) {
        return new Builder(new Button(label, tooltip, width, null));
    }

    @Override
    public @NotNull ActionButton button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width,
            @NotNull MenuAction action
    ) {
        final Button built = new Button(label, tooltip, width, action);
        this.buttons.add(built);
        return built;
    }

    // ------------------------------------------------------------------ what a test reads back

    /** Every body line built, in order. */
    @NotNull List<Message> messages() {
        return List.copyOf(this.messages);
    }

    /** The plain text of every body line built, in order. */
    @NotNull List<String> lines() {
        final List<String> rendered = new ArrayList<>(this.messages.size());
        for (final Message message : this.messages) {
            rendered.add(plain(message.contents()));
        }
        return rendered;
    }

    /** Every thumbnail built, in order. */
    @NotNull List<Item> thumbnails() {
        return List.copyOf(this.thumbnails);
    }

    /** The toggle built for {@code key}. */
    @NotNull Toggle toggle(@NotNull String key) {
        for (final Toggle toggle : this.toggles) {
            if (toggle.key().equals(key)) {
                return toggle;
            }
        }
        throw new AssertionError("No toggle was built for '" + key + "'");
    }

    /** The slider built for {@code key}. */
    @NotNull Slider slider(@NotNull String key) {
        for (final Slider slider : this.sliders) {
            if (slider.key().equals(key)) {
                return slider;
            }
        }
        throw new AssertionError("No slider was built for '" + key + "'");
    }

    /** The chooser built for {@code key}. */
    @NotNull Chooser chooser(@NotNull String key) {
        for (final Chooser chooser : this.choosers) {
            if (chooser.key().equals(key)) {
                return chooser;
            }
        }
        throw new AssertionError("No chooser was built for '" + key + "'");
    }

    /** Every button that carries its own action, in order. */
    @NotNull List<Button> actionButtons() {
        return List.copyOf(this.buttons);
    }

    /** Forgets everything recorded, so one test can build two screens. */
    void reset() {
        this.messages.clear();
        this.thumbnails.clear();
        this.toggles.clear();
        this.sliders.clear();
        this.choosers.clear();
        this.buttons.clear();
    }

    /** The plain text of a component, which is what an assertion about a label wants. */
    static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ the recorded elements

    record Message(@NotNull Component contents, int width) implements PlainMessageDialogBody {
    }

    /**
     * A thumbnail, recorded by what was asked for rather than by what would have been built.
     *
     * @param materialName the material the screen asked for
     * @param itemModelKey the item model the screen asked for, or {@code null}
     * @param width        the requested width
     * @param height       the requested height
     * @param caption      the requested caption, or {@code null}
     */
    record Item(
            @NotNull String materialName,
            @Nullable String itemModelKey,
            int width,
            int height,
            @Nullable Component caption
    ) implements ItemDialogBody {

        @Override
        public @NotNull ItemStack item() {
            throw new UnsupportedOperationException(
                    "Building an item stack needs the server's item factory");
        }

        @Override
        public @Nullable PlainMessageDialogBody description() {
            return this.caption == null ? null : new Message(this.caption, this.width);
        }

        @Override
        public boolean showDecorations() {
            return true;
        }

        @Override
        public boolean showTooltip() {
            return true;
        }
    }

    record Toggle(@NotNull String key, @NotNull Component label, boolean initial)
            implements BooleanDialogInput {

        @Override
        public @NotNull String onTrue() {
            return "true";
        }

        @Override
        public @NotNull String onFalse() {
            return "false";
        }
    }

    record Slider(
            @NotNull String key,
            @NotNull Component label,
            @NotNull String labelFormat,
            float start,
            float end,
            float initialValue,
            float stepSize,
            int width
    ) implements NumberRangeDialogInput {

        @Override
        public @NotNull Float initial() {
            return this.initialValue;
        }

        @Override
        public @NotNull Float step() {
            return this.stepSize;
        }
    }

    record Chooser(
            @NotNull String key,
            @NotNull Component label,
            @NotNull List<Option> options,
            int width
    ) implements SingleOptionDialogInput {

        @Override
        public @NotNull List<OptionEntry> entries() {
            final List<OptionEntry> built = new ArrayList<>(this.options.size());
            for (final Option option : this.options) {
                built.add(new Entry(option.id(), option.display(), option.initial()));
            }
            return built;
        }

        @Override
        public boolean labelVisible() {
            return true;
        }

        /** The ids this chooser offers, which is what a pagination assertion reads. */
        @NotNull List<String> ids() {
            final List<String> ids = new ArrayList<>(this.options.size());
            for (final Option option : this.options) {
                ids.add(option.id());
            }
            return ids;
        }

        /** The id the control opens on, or {@code null} when none is marked. */
        @Nullable String initialId() {
            for (final Option option : this.options) {
                if (option.initial()) {
                    return option.id();
                }
            }
            return null;
        }
    }

    record Entry(@NotNull String id, @NotNull Component display, boolean initial)
            implements SingleOptionDialogInput.OptionEntry {
    }

    /**
     * A finished button.
     *
     * @param label   the rendered label
     * @param tooltip the rendered tooltip, or {@code null}
     * @param width   the requested width
     * @param onClick the action, or {@code null} when the dialog factory would have attached one
     */
    record Button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width,
            @Nullable MenuAction onClick
    ) implements ActionButton {

        @Override
        public @Nullable DialogAction action() {
            return null;
        }

        /** The plain text of the label. */
        @NotNull String text() {
            return plain(this.label);
        }
    }

    /**
     * An unfinished button, which is what a {@code @DialogButton} provider hands back for the factory to
     * finish. A test reads the label off it directly rather than building it.
     */
    static final class Builder implements ActionButton.Builder {

        private @NotNull Button button;

        Builder(@NotNull Button button) {
            this.button = button;
        }

        @Override
        public @NotNull ActionButton.Builder tooltip(@Nullable Component tooltip) {
            this.button = new Button(this.button.label(), tooltip, this.button.width(),
                    this.button.onClick());
            return this;
        }

        @Override
        public @NotNull ActionButton.Builder width(int width) {
            this.button = new Button(this.button.label(), this.button.tooltip(), width,
                    this.button.onClick());
            return this;
        }

        @Override
        public @NotNull ActionButton.Builder action(@Nullable DialogAction action) {
            return this;
        }

        @Override
        public @NotNull ActionButton build() {
            return this.button;
        }

        /** The plain text of the label this builder would produce. */
        @NotNull String text() {
            return this.button.text();
        }

        /** The width this builder would produce. */
        int width() {
            return this.button.width();
        }

        /** The tooltip this builder would produce, or {@code null}. */
        @Nullable Component tooltip() {
            return this.button.tooltip();
        }
    }
}
