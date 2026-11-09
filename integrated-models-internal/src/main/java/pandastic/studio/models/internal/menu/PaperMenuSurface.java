package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real surface: every element built through the provider a running server installs.
 *
 * <h2>What it adds beyond delegation</h2>
 * Three things, each of which would otherwise be repeated in every screen.
 *
 * <ul>
 *   <li>A material name becomes an item stack, and a <em>bad</em> name becomes an absent thumbnail
 *       rather than an exception — plus one line in the console. This is the half of the material check
 *       the validator cannot do: resolving a name initialises the server's registry, so the pure
 *       validator in {@code ConfigMapper} only checks the name's shape and a misspelling arrives here.
 *       Reported once per distinct name, because a thumbnail is built every time a player opens the
 *       picker and a per-open warning would bury the rest of the log.</li>
 *   <li>A blank tooltip becomes no tooltip. Passing an empty component instead would show an empty
 *       tooltip box, which looks like a rendering fault.</li>
 *   <li>A {@link MenuAction} becomes a registered click callback with a single use. One use is not a
 *       tuning choice: the dialog layer closes the screen on any click, so a second press of the same
 *       button cannot happen from the same dialog — and the next screen the player is shown is a freshly
 *       built dialog with freshly registered callbacks.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method is called while a screen is being built, which the presenter does on the viewer's own
 * region thread. Nothing here reads an entity or a world, but the registry providers are server state,
 * so the surface inherits that discipline rather than claiming independence from it.
 */
public final class PaperMenuSurface implements MenuSurface {

    /**
     * How many times a click callback attached to a list button may fire.
     *
     * <p>Structural rather than tunable: every button press closes the dialog it was in, so one press is
     * all a built dialog can deliver. A larger budget would retain a callback nobody can reach; an
     * unlimited one would never be collected.
     */
    private static final int SINGLE_USE = 1;

    private final @NotNull ComponentLogger logger;

    /**
     * Material names already complained about, so the complaint is made once rather than once per
     * screen.
     *
     * <p>Bounded by the number of distinct values the configuration has held, which is one per reload
     * that changed the key.
     */
    private final @NotNull Set<String> reportedMaterials = ConcurrentHashMap.newKeySet();

    /**
     * @param logger where an unresolvable material name is reported
     */
    public PaperMenuSurface(@NotNull ComponentLogger logger) {
        this.logger = logger;
    }

    @Override
    public @NotNull PlainMessageDialogBody message(@NotNull Component text, int width) {
        return DialogInstancesProvider.instance().plainMessageDialogBody(text, width);
    }

    @Override
    public @Nullable ItemDialogBody thumbnail(
            @NotNull String materialName,
            @Nullable String itemModelKey,
            int width,
            int height,
            @Nullable Component caption
    ) {
        final Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            reportMaterial(materialName, material == null);
            return null;
        }

        final ItemStack item = ItemStack.of(material);
        if (itemModelKey != null && !itemModelKey.isBlank()) {
            // A model id is a file name and a key path is not, so a name the pack could never have an
            // entry for is left to the material's own model instead of refusing the thumbnail.
            final NamespacedKey key = NamespacedKey.fromString(itemModelKey);
            if (key != null) {
                item.editMeta(meta -> meta.setItemModel(key));
            }
        }

        final ItemDialogBody.Builder builder = DialogInstancesProvider.instance()
                .itemDialogBodyBuilder(item)
                .width(width)
                .height(height);
        if (caption != null) {
            builder.description(DialogInstancesProvider.instance().plainMessageDialogBody(caption));
        }
        return builder.build();
    }

    @Override
    public @NotNull BooleanDialogInput toggle(
            @NotNull String key,
            @NotNull Component label,
            boolean initial
    ) {
        return DialogInstancesProvider.instance()
                .booleanBuilder(key, label)
                .initial(initial)
                .build();
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
        return DialogInstancesProvider.instance()
                .numberRangeBuilder(key, label, start, end)
                .labelFormat(labelFormat)
                .initial(initial)
                .step(step)
                .width(width)
                .build();
    }

    @Override
    public @NotNull SingleOptionDialogInput chooser(
            @NotNull String key,
            @NotNull Component label,
            @NotNull List<Option> options,
            int width
    ) {
        final List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>(options.size());
        for (final Option option : options) {
            entries.add(DialogInstancesProvider.instance()
                    .singleOptionEntry(option.id(), option.display(), option.initial()));
        }
        return DialogInstancesProvider.instance()
                .singleOptionBuilder(key, label, entries)
                .width(width)
                .build();
    }

    @Override
    public @NotNull ActionButton.Builder button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width
    ) {
        final ActionButton.Builder builder = DialogInstancesProvider.instance()
                .actionButtonBuilder(label)
                .width(width);
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return builder;
    }

    @Override
    public @NotNull ActionButton button(
            @NotNull Component label,
            @Nullable Component tooltip,
            int width,
            @NotNull MenuAction action
    ) {
        return button(label, tooltip, width).action(register(action)).build();
    }

    /**
     * Says once that a configured material cannot become a thumbnail.
     *
     * <p>A warning rather than an error: the picker still works, it just has a less useful picture in
     * it, and the message names the key so the fix is one edit away.
     *
     * @param materialName the configured name
     * @param unknown      {@code true} when no material has that name, {@code false} when it has no item
     *                     form
     */
    private void reportMaterial(@NotNull String materialName, boolean unknown) {
        if (!this.reportedMaterials.add(materialName)) {
            return;
        }
        this.logger.warn(Component.text("picker.thumbnailMaterial in menu.yml is '" + materialName
                + "', which " + (unknown ? "names no material" : "has no item form")
                + ". The model picker will show no preview.", NamedTextColor.YELLOW));
    }

    /**
     * Registers {@code action} as a click callback.
     *
     * <p>The callback adapts the platform's {@code (response, audience)} pair into the same
     * {@link DialogContext} an annotated handler receives, so an action written for one form works
     * unchanged in the other.
     *
     * @param action what the click does
     * @return the registered action
     */
    private static @NotNull DialogAction register(@NotNull MenuAction action) {
        final DialogActionCallback callback = (response, audience) ->
                action.onClick(new MenuContext(audience, response));
        return DialogInstancesProvider.instance().register(
                callback, ClickCallback.Options.builder().uses(SINGLE_USE).build());
    }
}
